# Upload an APK asset to an EXISTING GitHub release (by tag).
# Used to restore an asset that was deleted by mistake.
#
# Usage: powershell -ExecutionPolicy Bypass -File tool\upload-asset.ps1 -Tag v1.5.1 -Apk bili-v3-v1.5.1-release.apk
#
# Pure ASCII (PowerShell 5.1 reads .ps1 as ANSI/GBK).

param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Apk
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $root $Apk
if (-not (Test-Path $apkPath)) { throw "missing apk: $apkPath" }

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
$tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
if (-not $tok) { throw 'no token from credential manager' }

$hdr = @{
    Authorization = "token $tok"
    'User-Agent'  = 'dsh'
    Accept        = 'application/vnd.github+json'
}

$repo = 'Hu-Yang-rui/bili-v3-public'
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag" `
    -Headers $hdr -Method Get
Write-Host ("release {0} (id={1})" -f $rel.tag_name, $rel.id)

# Refuse to create a duplicate name.
foreach ($a in $rel.assets) {
    if ($a.name -eq $Apk) { throw "asset already exists: $Apk" }
}

$upHdr = @{
    Authorization           = "token $tok"
    'User-Agent'            = 'dsh'
    'Content-Type'          = 'application/vnd.android.package-archive'
    'Content-Length'        = (Get-Item $apkPath).Length
}
$url = "https://uploads.github.com/repos/$repo/releases/" + $rel.id + "/assets?name=" + $Apk

$bytes = [System.IO.File]::ReadAllBytes($apkPath)
Invoke-RestMethod -Uri $url -Headers $upHdr -Method Post -Body $bytes | Out-Null
Write-Host ("uploaded: {0} ({1} B)" -f $Apk, $bytes.Length)

Write-Host 'assets now:'
$left = Invoke-RestMethod -Uri ("https://api.github.com/repos/$repo/releases/" + $rel.id + "/assets") `
    -Headers $hdr -Method Get
$left | ForEach-Object { Write-Host ("  {0}  {1} B" -f $_.name, $_.size) }
