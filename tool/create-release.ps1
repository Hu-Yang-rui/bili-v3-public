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

# Release body. Kept inline (no separate .md file) -- this repo intentionally
# has exactly one doc file (AGENTS.md).
#
# ASCII-only: see the header note. Release body is intentionally English-free
# of non-ASCII so this script stays safe under GBK-reading PowerShell.
$notes = @"
## BiliV3 $tag

versionName = $ver / versionCode = $code

Install packages and signing details: see AGENTS.md in the repository.

> Personal-use third-party client. Not publicly distributed, not on any store,
> not commercialized, does not bypass any paywall.
"@

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
    $rel = $existing
}

$apks = @("bili-v3-$tag-release.apk", "bili-v3-$tag-debug.apk")

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
