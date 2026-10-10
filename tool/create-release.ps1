# Create GitHub Release and upload APKs.
# Token comes from git credential manager (never printed).
#
# Version (tag / release name / APK filenames) is read from
# app/build.gradle.kts -- the SINGLE source of truth (see AGENTS.md 2.1).
# Do NOT hardcode the version here; bump build.gradle.kts instead.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI/GBK,
# which mangles non-ASCII and breaks string terminators.
#
# ---- -Beta switch: publish a PRERELEASE without touching the version ----
#
# Why this exists:
#   Version numbers may only be bumped when the user explicitly asks
#   (AGENTS.md 2.5). But a build can still be worth shipping to the owner
#   for testing BEFORE that. Those two facts conflict if the only publish
#   path requires a version bump.
#
# So: -Beta publishes the CURRENT versionName as a prerelease under a
# distinct tag, leaving build.gradle.kts untouched.
#
#   tag         v1.6.8-beta.1   (auto-increments: -beta.2, -beta.3, ...)
#   name        BiliV3 v1.6.8-beta.1
#   prerelease  true            (GitHub marks it "Pre-release", and
#                                /releases/latest does NOT return it)
#   APK name    bili-v3-v1.6.8-beta.1-release.apk
#
# The APK still reports versionName 1.6.8 on-device, so the ONLY thing
# distinguishing a beta build from the real 1.6.8 is which Release page it
# came from. That is intentional: the code is identical, and the owner needs
# to be told which one they are being handed.
#
# ALWAYS state clearly, when offering to publish, whether the target is the
# official release or a beta. See prompts/ (volume: checklist, section 6.5).
param(
    [switch]$Beta
)
$ErrorActionPreference = 'Stop'

$owner = 'Hu-Yang-rui'
# Repo name must match `git remote -v` (origin).
# NOTE: the old private repo 'bili-v3' still exists but is ABANDONED
# (frozen at v0.6.4). Publishing there would ship to nobody.
# Active repo is the public one.
$repo  = 'bili-v3-public'
$root  = 'D:\deep\bili-v3'

# Guard: fail loudly if origin disagrees with $repo, so a renamed/old
# remote can never silently receive a release again.
$originUrl = (& git -C $root remote get-url origin 2>&1 | Out-String).Trim()
if ($originUrl -notmatch [regex]::Escape($repo)) {
    throw "origin ($originUrl) does not match `$repo ($repo); refusing to publish"
}

# ---- derive version from build.gradle.kts ----
$gradle = [System.IO.File]::ReadAllText(
    (Join-Path $root 'app\build.gradle.kts'), [System.Text.Encoding]::UTF8)

$vm = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"')
$cm = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)')
if (-not $vm.Success -or -not $cm.Success) {
    throw 'cannot parse versionName/versionCode from app/build.gradle.kts'
}
$ver = $vm.Groups[1].Value
$code = $cm.Groups[1].Value

# ---- tag: official vs beta ----
#
# Beta tags are derived by scanning existing tags so a re-run never
# overwrites the previous beta (each beta is an immutable snapshot of a
# distinct build). Scanning is done with an explicit @() wrap -- see the
# single-tag bug documented further down; the same PowerShell unwrapping
# trap applies here.
if ($Beta) {
    # NOTE: do NOT build the pattern from [regex]::Escape(...).
    # Escaping yields 'v1\.6\.8-beta\.' and embedding that inside a larger
    # pattern silently fails to match ('v1.6.8-beta.2' returns Success=False),
    # which would restart the counter at 1 and overwrite beta.1 forever.
    # The version string only contains digits and dots, so escaping it for a
    # literal match is unnecessary -- match on the raw prefix instead.
    $prefix = "v$ver-beta."
    $existing = @(@(& git -C $root tag -l "v$ver-beta.*" 2>$null) | Where-Object { $_ })
    $max = 0
    foreach ($t in $existing) {
        if ($t.StartsWith($prefix)) {
            $tail = $t.Substring($prefix.Length)
            if ($tail -match '^\d+$') {
                $n = [int]$tail
                if ($n -gt $max) { $max = $n }
            }
        }
    }
    $next = $max + 1
    $tag = "v$ver-beta.$next"
    $isPre = $true
    Write-Host ("BETA: version {0} (code {1}) -> tag {2} (prerelease)" -f $ver, $code, $tag)
} else {
    $tag = "v$ver"
    $isPre = $false
    Write-Host ("version {0} (code {1}) -> tag {2}" -f $ver, $code, $tag)
}

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
$tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
if (-not $tok) { throw 'no token from credential manager' }

$hdr = @{
    Authorization = "token $tok"
    'User-Agent'  = 'dsh'
    Accept        = 'application/vnd.github+json'
}

# ---- release body: read from tool/release-notes.md (UTF-8, Chinese) ----
#
# The script itself must stay pure ASCII (PowerShell 5.1 reads .ps1 as
# ANSI/GBK and would mangle any non-ASCII literal, breaking string
# terminators). Reading an external UTF-8 file is how we get Chinese notes
# without making the script unsafe.
$notesPath = Join-Path $root 'tool\release-notes.md'
if (-not (Test-Path $notesPath)) { throw "missing release notes: $notesPath" }

# Read the template once, up front: the changelog filter below needs the
# 'changelog-exclude:' rule that is declared inside this file (as an HTML
# comment, so it never shows on the rendered release page).
$rawNotes = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)

# ---- changelog: hand-written highlights first, git log as fallback ----
#
# WHY hand-written highlights exist:
#
# The git-log-derived list is a flat dump of commit subjects. That works when a
# release is a few small fixes, but it collapses when one commit carries several
# features -- the whole release then renders as ONE long run-on line, which tells
# the reader nothing. Commit subjects are written for reviewers (conventional
# prefixes, scopes), not for users.
#
# So: a per-tag file may be supplied at tool/highlights/<tag>.md. When present it
# IS the changelog. When absent, we fall back to the git-derived list below, so
# nothing breaks for releases that do not need one.
#
# The file is UTF-8 and may contain Chinese, so it must live outside this script
# (this .ps1 has to stay pure ASCII -- see the header).
$highlightsPath = Join-Path $root ("tool\highlights\{0}.md" -f $tag)

# For a beta the exact tag (v1.6.8-beta.3) will not have a file -- and the
# plain v1.6.8.md describes the OFFICIAL release, so reusing it would tell
# testers about features that are not what they are installing. Fall back to
# a per-version beta file, then to the git-derived list.
#
# A beta file is therefore OPTIONAL: without one, the notes are still correct
# (git-derived), just less polished.
if (-not (Test-Path $highlightsPath) -and $Beta) {
    $betaPath = Join-Path $root ("tool\highlights\{0}-beta.md" -f $tag)
    if (-not (Test-Path $betaPath)) {
        $betaPath = Join-Path $root ("tool\highlights\v{0}-beta.md" -f $ver)
    }
    if (Test-Path $betaPath) {
        $highlightsPath = $betaPath
    }
}

$changes = $null
if (Test-Path $highlightsPath) {
    $changes = [System.IO.File]::ReadAllText($highlightsPath, [System.Text.Encoding]::UTF8).Trim()
    if ($changes) {
        Write-Host ("changelog: using hand-written highlights ({0})" -f
            ("tool/highlights/{0}" -f (Split-Path $highlightsPath -Leaf)))
    } else {
        $changes = $null
        Write-Host ("changelog: highlights file is empty; falling back to git log")
    }
}

# ---- changelog (fallback): derived from git so it can never drift ----
#
# The changelog is USER-FACING, so internal-only commits must not appear in it:
# release scripting, doc maintenance, version bumps, build/CI chores. Users care
# about what the APP does, not how we ship it.
#
# Two-stage filter:
#   1. conventional-commit type/scope (ASCII, so it can live here)
#   2. extra regex from release-notes.md (needs Chinese, so it lives there --
#      this script must stay pure ASCII)
#
# Native command output is decoded using [Console]::OutputEncoding; commit
# subjects here are Chinese, so force UTF-8 first or they come back as '?'.
if (-not $changes) {
$prevEnc = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    # Tags are created by the GitHub API, so they exist on the remote but not
    # necessarily in this clone. Fetch them or the range lookup below silently
    # falls back to "first release" and the changelog would be wrong.
    & git -C $root fetch --tags --quiet origin 2>$null | Out-Null

    # Newest tag by version that is not the tag we are about to publish.
    #
    # WARNING: the outer @(...) is REQUIRED, not cosmetic.
    # When exactly ONE tag matches, PowerShell unwraps the pipeline result to a
    # plain String; $tags[0] would then return its FIRST CHARACTER ('v') instead
    # of the tag name, and `git log "v..HEAD"` dies with
    # "fatal: ambiguous argument 'v..HEAD'".
    # That is not hypothetical -- it broke the very first release after this
    # repo was reduced to a single tag. Re-wrapping the whole pipeline in @()
    # forces Object[] regardless of element count.
    $tags = @(@(& git -C $root tag -l 'v*' --sort=-v:refname 2>$null) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -ne $tag })
    $prevTag = if ($tags.Count -gt 0) { $tags[0] } else { '' }

    if ($prevTag) {
        Write-Host ("changelog range: {0}..HEAD" -f $prevTag)
        $subjects = & git -C $root log "$prevTag..HEAD" --no-merges --pretty=format:%s 2>$null
    } else {
        Write-Host 'changelog range: first release (last 30 commits)'
        $subjects = & git -C $root log -30 --no-merges --pretty=format:%s 2>$null
    }

    $all = @($subjects | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })

    # --- filter 1: conventional-commit types that are never user-visible ---
    # chore/docs/ci/build/test/style/revert are tooling-only by definition.
    # Also drop any scope=release|docs|tool regardless of type.
    $dropTypes = '^(chore|docs|ci|build|test|style|revert)(\(|:|!)'
    $dropScope = '^[a-z]+\((release|docs|tool|repo)\)'

    # --- filter 2: extra regex declared in release-notes.md ---
    $excludeExtra = ''
    $m = [regex]::Match($rawNotes, 'changelog-exclude:\s*(.+)')
    if ($m.Success) { $excludeExtra = $m.Groups[1].Value.Trim() }

    # Fallback text for an empty changelog (Chinese -> must come from the
    # UTF-8 file, not from this ASCII-only script).
    $emptyText = '- (no user-visible changes)'
    $me = [regex]::Match($rawNotes, 'changelog-empty:\s*(.+)')
    if ($me.Success) { $emptyText = $me.Groups[1].Value.Trim() }

    $kept = @($all | Where-Object {
        $s = $_
        if ($s -match $dropTypes) { return $false }
        if ($s -match $dropScope) { return $false }
        if ($excludeExtra -and $s -match $excludeExtra) { return $false }
        return $true
    })

    Write-Host ("changelog: {0} kept / {1} total (filtered out {2})" -f
        $kept.Count, $all.Count, ($all.Count - $kept.Count))

    $lines = @($kept | ForEach-Object { "- " + $_ })
    if ($lines.Count -eq 0) {
        # Never ship an empty section. The fallback text is Chinese, so it
        # cannot live here (this script must stay pure ASCII) -- it is declared
        # as 'changelog-empty:' in release-notes.md and read below.
        $lines = @($emptyText)
    }
    $changes = $lines -join "`n"
} finally {
    [Console]::OutputEncoding = $prevEnc
}
}   # end: only when no hand-written highlights were supplied

$notes = $rawNotes.Replace('{{TAG}}', $tag).
                    Replace('{{VERSION}}', $ver).
                    Replace('{{CODE}}', $code).
                    Replace('{{CHANGES}}', $changes)

# Strip HTML comments before publishing. The rule block at the top of
# release-notes.md is internal (it exists only for this script to read), and
# GitHub stores the raw markdown -- hiding it visually is not enough, it must
# not be in the payload at all.
$notes = [regex]::Replace($notes, '(?s)<!--.*?-->', '').Trim()

if ($notes -match '\{\{') { throw 'unsubstituted placeholder left in release-notes.md' }

$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/tags/$tag" -Headers $hdr -TimeoutSec 30
    Write-Host ("existing release: {0} (id={1})" -f $existing.name, $existing.id)
} catch {
    Write-Host ("no {0} release yet; creating" -f $tag)
}

if (-not $existing) {
    $payload = @{
        tag_name         = $tag
        target_commitish = 'main'
        name             = "BiliV3 $tag"
        body             = $notes
        draft            = $false
        prerelease       = $isPre
    } | ConvertTo-Json

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$owner/$repo/releases" -Headers $hdr -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("created release id={0} tag={1} prerelease={2}" -f $rel.id, $rel.tag_name, $rel.prerelease)
} else {
    # Re-running must be able to FIX an existing release (e.g. the body was
    # written in English before release-notes.md existed). Without this the
    # script would silently keep the stale body.
    #
    # prerelease is re-asserted too: an existing v1.6.8 must never be
    # silently flipped to prerelease (or vice versa) by a stray re-run.
    $payload = @{ name = "BiliV3 $tag"; body = $notes; prerelease = $isPre } | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $rel = Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$owner/$repo/releases/$($existing.id)" -Headers $hdr -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("updated release id={0} body+name+prerelease={1}" -f $rel.id, $rel.prerelease)
}

# ONLY the release APK is published.
#
# Why the debug APK is deliberately excluded (security, not size):
#   - It is built with android:debuggable="true"  -> `adb run-as` can read
#     the app's private data (incl. the encrypted-prefs store).
#   - It is signed with the PUBLIC Android debug key (CN=Android Debug),
#     so ANYONE can sign an APK with the same package name + same cert and
#     a device will treat it as a legitimate upgrade over this one.
# Both properties make it unsafe to distribute. Debug builds stay local.
$apks = @("bili-v3-$tag-release.apk")

# Safety net: if a debug asset ever sneaks into this release, delete it.
try {
    $cur = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/$($rel.id)" -Headers $hdr -TimeoutSec 30
    foreach ($a in $cur.assets) {
        if ($a.name -match 'debug') {
            Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$owner/$repo/releases/assets/$($a.id)" -Headers $hdr -TimeoutSec 60 | Out-Null
            Write-Host ("removed debug asset: {0}" -f $a.name)
        }
    }
} catch {
    Write-Host ("debug-asset sweep skipped: {0}" -f $_.Exception.Message)
}

foreach ($a in $apks) {
    $path = Join-Path $root $a
    if (-not (Test-Path $path)) { Write-Host ("skip (missing): {0}" -f $a); continue }

    $sizeMb = [math]::Round((Get-Item $path).Length / 1MB, 2)
    $uri = "https://uploads.github.com/repos/$owner/$repo/releases/$($rel.id)/assets?name=$a"
    try {
        $asset = Invoke-RestMethod -Method Post -Uri $uri -Headers $hdr -InFile $path -ContentType 'application/vnd.android.package-archive' -TimeoutSec 1800
        Write-Host ("uploaded: {0} ({1} MB)" -f $a, $sizeMb)
        Write-Host ("          {0}" -f $asset.browser_download_url)
    } catch {
        # GitHub returns 422 when an asset with the same name already exists.
        # That is the NORMAL outcome of re-running this script to fix a release
        # body -- the asset is already correct, nothing to do. Report it as a
        # skip rather than a scary "FAILED", or the operator will think the
        # release is broken and start deleting assets by hand.
        $msg = $_.Exception.Message
        if ($msg -match '422') {
            Write-Host ("already uploaded, skipped: {0}" -f $a)
            Write-Host ("  (delete the asset first if you really need to replace it)")
        } else {
            Write-Host ("upload FAILED: {0} :: {1}" -f $a, $msg)
        }
    }
}

Write-Host ''
Write-Host '=== release assets ==='
$final = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/$($rel.id)" -Headers $hdr -TimeoutSec 30
foreach ($as in $final.assets) {
    Write-Host ("  {0,-30} {1,10:N0} B" -f $as.name, $as.size)
}
Write-Host ("  page: {0}" -f $final.html_url)
