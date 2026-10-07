# Sync the prompt system to the PRIVATE repo bili-v3-agents.
#
# ## What gets synced
#
#   AGENTS.md            the front-loaded rule file (auto-loaded)
#   AGENTS-P2.md         the archive volume (consulted on demand)
#   prompts/*.md         the five rule volumes
#
# ## Why this is safe now (it was NOT before)
#
# AGENTS.md contains local absolute paths and signing-key fingerprints, so it
# must never reach the PUBLIC repo -- it is gitignored there for that reason.
#
# bili-v3-agents is **private**, so those same facts stay private. The earlier
# version of this script synced only AGENTS-P2.md out of caution; the repo is
# now the single private mirror for the whole prompt system.
#
# ## One home, no drift
#
# The LOCAL files are authoritative. This repo is a **backup / mirror** -- it is
# never edited on GitHub. `-Pull` exists only to restore local files if the
# working copy is lost. If you find yourself editing on GitHub, stop: edit
# locally and push.
#
# ## ASCII-only on purpose
#
# Windows PowerShell 5.1 reads .ps1 as ANSI/GBK, which mangles non-ASCII and
# breaks string terminators. The prompt volumes have CHINESE FILENAMES, so the
# script must never hard-code them -- it enumerates the directory instead and
# URL-encodes names for the API. Do not add non-ASCII literals here.
#
# Token comes from git credential manager (never printed).
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

# Files mirrored at the repo root.
$rootFiles = @('AGENTS.md', 'AGENTS-P2.md')

# Directories mirrored as <dir>/<name>. Enumerated at runtime -- see header
# for why the Chinese filenames must not appear as literals here.
$dirs = @('prompts')

function Get-Token {
    $cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>&1
    $tok = ($cred | Select-String '^password=').Line -replace '^password=', ''
    if (-not $tok) { throw 'no token from credential manager' }
    return $tok
}

# Build the manifest: pairs of (local absolute path, repo-relative path).
#
# ## 🔴 Pull must NOT be driven by the local directory listing
#
# The first version enumerated local files and then looped over that list for
# BOTH directions. That works for push but is **fundamentally broken for pull**:
# a file that was deleted locally is not in the listing, so it is never
# restored -- i.e. the exact disaster-recovery case `-Pull` exists for.
#
# (Verified by test: delete prompts/项目结构说明.md, run -Pull, file stays gone,
#  and the script still prints a cheerful "done".)
#
# So the two directions use different sources of truth:
#   push -> local filesystem (enumerate)
#   pull -> REMOTE directory listing (the repo is authoritative for what exists)
$manifest = @()

function Add-LocalFiles {
    param([string]$DirName)
    $dp = Join-Path $root $DirName
    if (-not (Test-Path $dp)) { return }
    Get-ChildItem -Path $dp -Filter *.md -File | Sort-Object Name | ForEach-Object {
        $script:manifest += , @{ local = $_.FullName; rel = ("{0}/{1}" -f $DirName, $_.Name) }
    }
}

if ($Pull) {
    # ---- source of truth = the remote repo ----
    $tokForList = Get-Token
    $hdrForList = @{
        Authorization = "token $tokForList"
        'User-Agent'  = 'dsh'
        Accept        = 'application/vnd.github+json'
    }

    $rootApi = "https://api.github.com/repos/$owner/$repo/contents/"
    try {
        $rootEntries = Invoke-RestMethod -Uri $rootApi -Headers $hdrForList -TimeoutSec 30
    } catch {
        throw "cannot list $owner/$repo -- does it exist and is the token valid?"
    }

    foreach ($e in $rootEntries) {
        if ($e.type -eq 'file' -and $e.name -like '*.md') {
            $manifest += , @{ local = (Join-Path $root $e.name); rel = $e.name }
        } elseif ($e.type -eq 'dir' -and $e.name -eq 'prompts') {
            $dirApi = "https://api.github.com/repos/$owner/$repo/contents/$($e.name)"
            $sub = Invoke-RestMethod -Uri $dirApi -Headers $hdrForList -TimeoutSec 30
            foreach ($s in $sub) {
                if ($s.type -eq 'file' -and $s.name -like '*.md') {
                    $manifest += , @{
                        local = (Join-Path (Join-Path $root $e.name) $s.name)
                        rel   = ("{0}/{1}" -f $e.name, $s.name)
                    }
                }
            }
        }
    }
} else {
    # ---- source of truth = the local filesystem ----
    foreach ($f in $rootFiles) {
        $p = Join-Path $root $f
        if (Test-Path $p) {
            $manifest += , @{ local = $p; rel = $f }
        } else {
            Write-Host ("skip {0} (not present locally)" -f $f)
        }
    }
    foreach ($d in $dirs) {
        $dp = Join-Path $root $d
        if (-not (Test-Path $dp)) {
            Write-Host ("skip {0}/ (not present locally)" -f $d)
            continue
        }
        Add-LocalFiles -DirName $d
    }
}

if ($manifest.Count -eq 0) { throw 'nothing to sync -- check the local paths' }

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
        description = 'BiliV3 prompt system (AGENTS.md + AGENTS-P2.md + prompts/) - private mirror'
        has_issues  = $false
        has_wiki    = $false
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' `
        -Headers $hdr -Body $body -ContentType 'application/json; charset=utf-8' `
        -TimeoutSec 60 | Out-Null
    Write-Host 'created.'
}

$pushed = 0
$skipped = 0

foreach ($item in $manifest) {
    $path = $item.local
    $rel  = $item.rel

    # Chinese filenames must be URL-encoded for the contents API.
    $apiRel = ($rel -split '/' | ForEach-Object { [System.Uri]::EscapeDataString($_) }) -join '/'
    $api = "https://api.github.com/repos/$owner/$repo/contents/$apiRel"

    if ($Pull) {
        # ---- pull back (restore locally when the working copy is lost) ----
        $cur = Invoke-RestMethod -Uri $api -Headers $hdr -TimeoutSec 30
        $raw = [Convert]::FromBase64String(($cur.content -replace '\s', ''))

        # Make sure the parent directory exists before writing.
        $parent = Split-Path $path -Parent
        if (-not (Test-Path $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        [System.IO.File]::WriteAllBytes($path, $raw)
        Write-Host ("pulled {0} ({1} bytes)" -f $rel, $raw.Length)
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
            Write-Host ("unchanged {0}" -f $rel)
            $skipped++
            continue
        }
    }

    $payload = @{
        message = ("sync {0} from local" -f $rel)
        content = $b64
    }
    if ($sha) { $payload['sha'] = $sha }
    $json = $payload | ConvertTo-Json -Compress

    Invoke-RestMethod -Method Put -Uri $api -Headers $hdr -Body $json `
        -ContentType 'application/json; charset=utf-8' -TimeoutSec 60 | Out-Null
    Write-Host ("pushed {0} ({1} bytes)" -f $rel, $bytes.Length)
    $pushed++
}

Write-Host ''
Write-Host ("done: {0} pushed, {1} unchanged, {2} total" -f $pushed, $skipped, $manifest.Count)
Write-Host ("repo: https://github.com/{0}/{1}" -f $owner, $repo)
