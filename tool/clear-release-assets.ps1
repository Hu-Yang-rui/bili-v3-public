# Delete stale release assets for a GIVEN release so create-release.ps1 can
# re-upload cleanly. GitHub returns 422 when an asset with the same name exists.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tool\clear-release-assets.ps1 -ReleaseId 403455716
#   powershell -ExecutionPolicy Bypass -File tool\clear-release-assets.ps1 -Tag v1.5.1
#
# Pure ASCII (PowerShell 5.1 reads .ps1 as ANSI/GBK).

param(
    [string]$ReleaseId = '',
    [string]$Tag = ''
)

$ErrorActionPreference = 'Stop'

if (-not $ReleaseId -and -not $Tag) {
    throw 'pass -ReleaseId <id> or -Tag <vX.Y.Z>'
}

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
$tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
if (-not $tok) { throw 'no token from credential manager' }

$hdr = @{
    Authorization = "token $tok"
    'User-Agent'  = 'dsh'
    Accept        = 'application/vnd.github+json'
}

$repo = 'Hu-Yang-rui/bili-v3-public'

if (-not $ReleaseId) {
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Tag" `
        -Headers $hdr -Method Get
    $ReleaseId = $rel.id
    Write-Host ("tag {0} -> release id {1}" -f $Tag, $ReleaseId)
}

$api = "https://api.github.com/repos/$repo/releases/$ReleaseId/assets"

$assets = Invoke-RestMethod -Uri $api -Headers $hdr -Method Get
if (-not $assets -or $assets.Count -eq 0) {
    Write-Host 'no assets to delete'
} else {
    foreach ($a in $assets) {
        Write-Host ("deleting asset {0} (id={1}, {2} B)" -f $a.name, $a.id, $a.size)
        Invoke-RestMethod -Uri ("https://api.github.com/repos/$repo/releases/assets/" + $a.id) `
            -Headers $hdr -Method Delete | Out-Null
    }
}

Write-Host 'remaining assets:'
$left = Invoke-RestMethod -Uri $api -Headers $hdr -Method Get
if (-not $left -or $left.Count -eq 0) { Write-Host '  (none)' }
else { $left | ForEach-Object { Write-Host ("  " + $_.name) } }
