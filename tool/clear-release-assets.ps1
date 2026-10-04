# Delete stale release assets so create-release.ps1 can re-upload cleanly.
# GitHub returns 422 when an asset with the same name already exists.
# Pure ASCII (PowerShell 5.1 reads .ps1 as ANSI/GBK).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
$tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
if (-not $tok) { throw 'no token from credential manager' }

$hdr = @{
    Authorization = "token $tok"
    'User-Agent'  = 'dsh'
    Accept        = 'application/vnd.github+json'
}

$repo = 'Hu-Yang-rui/bili-v3-public'
$relId = '403116697'
$api = "https://api.github.com/repos/$repo/releases/$relId/assets"

$assets = Invoke-RestMethod -Uri $api -Headers $hdr -Method Get
foreach ($a in $assets) {
    Write-Host ("deleting asset {0} (id={1}, {2} B)" -f $a.name, $a.id, $a.size)
    Invoke-RestMethod -Uri ("https://api.github.com/repos/$repo/releases/assets/" + $a.id) `
        -Headers $hdr -Method Delete | Out-Null
}

Write-Host 'remaining assets:'
$left = Invoke-RestMethod -Uri $api -Headers $hdr -Method Get
if (-not $left -or $left.Count -eq 0) { Write-Host '  (none)' }
else { $left | ForEach-Object { Write-Host ("  " + $_.name) } }
