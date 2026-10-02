# Create GitHub Release and upload APKs.
# Token comes from git credential manager (never printed).
#
# Version (tag / release name / APK filenames) is read from
# app/build.gradle.kts -- the SINGLE source of truth (see AGENTS.md 2.1).
# Do NOT hardcode the version here; bump build.gradle.kts instead.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI/GBK,
# which mangles non-ASCII and breaks string terminators.
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
$tag = "v$ver"
Write-Host ("version {0} (code {1}) -> tag {2}" -f $ver, $code, $tag)

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

# ---- changelog: derived from git so it can never drift or be forgotten ----
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
$prevEnc = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    # Tags are created by the GitHub API, so they exist on the remote but not
    # necessarily in this clone. Fetch them or the range lookup below silently
    # falls back to "first release" and the changelog would be wrong.
    & git -C $root fetch --tags --quiet origin 2>$null | Out-Null

    # Newest tag by version that is not the tag we are about to publish.
    $tags = @(& git -C $root tag -l 'v*' --sort=-v:refname 2>$null) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and $_ -ne $tag }
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
        prerelease       = $false
    } | ConvertTo-Json

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$owner/$repo/releases" -Headers $hdr -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("created release id={0} tag={1}" -f $rel.id, $rel.tag_name)
} else {
    # Re-running must be able to FIX an existing release (e.g. the body was
    # written in English before release-notes.md existed). Without this the
    # script would silently keep the stale body.
    $payload = @{ name = "BiliV3 $tag"; body = $notes } | ConvertTo-Json
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
    $rel = Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$owner/$repo/releases/$($existing.id)" -Headers $hdr -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    Write-Host ("updated release id={0} body+name" -f $rel.id)
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
        Write-Host ("upload FAILED: {0} :: {1}" -f $a, $_.Exception.Message)
    }
}

Write-Host ''
Write-Host '=== release assets ==='
$final = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo/releases/$($rel.id)" -Headers $hdr -TimeoutSec 30
foreach ($as in $final.assets) {
    Write-Host ("  {0,-30} {1,10:N0} B" -f $as.name, $as.size)
}
Write-Host ("  page: {0}" -f $final.html_url)
