# Sync AGENTS-P2.md (the archive volume) to a PRIVATE GitHub repo.
#
# ## Why ONLY AGENTS-P2.md is synced (not AGENTS.md)
#
# AGENTS.md is the **front-loaded rule file** -- it is auto-loaded into the
# agent's workspace instructions, so it MUST stay local. Deleting it would
# silently disable every project convention.
#
# AGENTS-P2.md is the **archive volume** (interface tables, historical
# pitfalls, aicu details). It is NOT auto-loaded; it is consulted on demand.
# That makes it the right candidate to live in a private repo.
#
# Result: each file has exactly ONE home -- no two-place drift
# (see AGENTS.md 2.4 / the "0.6.1 vs 0.6.4" incident).
#
# Token comes from git credential manager (never printed).
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI/GBK,
# which mangles non-ASCII and breaks string terminators.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tool\sync-agents.ps1          # push
#   powershell -ExecutionPolicy Bypass -File tool\sync-agents.ps1 -Pull    # fetch back
#
# NOTE: the param() block is REQUIRED. Without it, `$Pull` is undefined and
# `if ($Pull)` is always false -- so `-Pull` silently runs the PUSH path
# instead (it printed "unchanged" and looked like it worked, because the
# local copy already matched). That is exactly the kind of silent no-op
# this project keeps getting bitten by.
param(
    [switch]$Pull
)

$ErrorActionPreference = 'Stop'

$owner = 'Hu-Yang-rui'
$repo  = 'bili-v3-agents'
$root  = 'D:\deep\bili-v3'

# Archive volume only. Do NOT add AGENTS.md here (see header).
$files = @('AGENTS-P2.md')

function Get-Token {
    $cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
    $tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
    if (-not $tok) { throw 'no token from credential manager' }
    return $tok
}

$tok = Get-Token
$hdr = @{
    Authorization = "token $tok"
    'User-Agent'  = 'dsh'
    Accept        = 'application/vnd.github+json'
}

# ---- ensure the private repo exists ----
$exists = $true
try {
    Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$repo" `
        -Headers $hdr -TimeoutSec 30 | Out-Null
} catch { $exists = $false }

if (-not $exists) {
    Write-Host "repo $owner/$repo not found; creating (private)..."
    $body = @{
        name        = $repo
        private     = $true
        description = 'BiliV3 archive volume (AGENTS-P2.md) - consulted on demand'
        has_issues  = $false
        has_wiki    = $false
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' `
        -Headers $hdr -Body $body -ContentType 'application/json; charset=utf-8' `
        -TimeoutSec 60 | Out-Null
    Write-Host 'created.'
}

foreach ($f in $files) {
    $path = Join-Path $root $f
    $api  = "https://api.github.com/repos/$owner/$repo/contents/$f"

    if ($Pull) {
        # ---- pull back (restore the archive locally when needed) ----
        $cur = Invoke-RestMethod -Uri $api -Headers $hdr -TimeoutSec 30
        $raw = [Convert]::FromBase64String(($cur.content -replace '\s', ''))
        [System.IO.File]::WriteAllBytes($path, $raw)
        Write-Host ("pulled {0} ({1} bytes)" -f $f, $raw.Length)
        continue
    }

    if (-not (Test-Path $path)) {
        Write-Host ("skip {0} (not present locally)" -f $f)
        continue
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $b64   = [Convert]::ToBase64String($bytes)

    # Existing sha is required to update; absent means create.
    $sha = $null
    $cur = $null
    try {
        $cur = Invoke-RestMethod -Uri $api -Headers $hdr -TimeoutSec 30
        $sha = $cur.sha
    } catch { $sha = $null }

    # Skip the upload when content is unchanged (avoids empty commits).
    if ($sha -and $cur) {
        $remote = [Convert]::FromBase64String(($cur.content -replace '\s', ''))
        $same = $remote.Length -eq $bytes.Length
        if ($same) {
            for ($i = 0; $i -lt $bytes.Length; $i++) {
                if ($remote[$i] -ne $bytes[$i]) { $same = $false; break }
            }
        }
        if ($same) {
            Write-Host ("unchanged {0}" -f $f)
            continue
        }
    }

    $payload = @{
        message = ("sync {0} from local" -f $f)
        content = $b64
    }
    if ($sha) { $payload['sha'] = $sha }
    $json = $payload | ConvertTo-Json -Compress

    Invoke-RestMethod -Method Put -Uri $api -Headers $hdr -Body $json `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 60 | Out-Null
    Write-Host ("pushed {0} ({1} bytes)" -f $f, $bytes.Length)
}
