# Verify BiliV3 APK signatures (v1/v2/v3) + dump the certificate.
# ASCII-only on purpose: PowerShell 5.1 reads .ps1 as ANSI/GBK by default.
$ErrorActionPreference = 'Continue'

$bt  = 'D:\android-sdk\build-tools\35.0.0\apksigner.bat'
$kt  = 'C:\Program Files\Java\jdk-21.0.10\bin\keytool.exe'
$root = Split-Path -Parent $PSScriptRoot

$targets = @(
    (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'),
    (Join-Path $root 'app\build\outputs\apk\release\app-release.apk'),
    (Join-Path $root 'bili-v3-v0.1.0-debug.apk')
)

function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }

foreach ($apk in $targets) {
    Write-Host ""
    Write-Host ("=" * 72)
    Log ("APK: " + $apk)
    if (-not (Test-Path $apk)) { Log "  SKIP (not found)"; continue }

    $f = Get-Item $apk
    Log ("  size = {0:N0} bytes   mtime = {1}" -f $f.Length, $f.LastWriteTime)
    Log ("  sha256 = " + (Get-FileHash $apk -Algorithm SHA256).Hash)

    Write-Host "  ---- apksigner verify (--min-sdk-version 23 forces v1 check) ----"
    & $bt verify --verbose --min-sdk-version 23 --print-certs $apk 2>&1 | ForEach-Object { "  $_" }

    Write-Host "  ---- META-INF v1 signature entries ----"
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $z = [System.IO.Compression.ZipFile]::OpenRead($apk)
        $v1 = $z.Entries | Where-Object { $_.FullName -match '^META-INF/.*\.(RSA|DSA|EC|SF)$' }
        if ($v1) { $v1 | ForEach-Object { "    " + $_.FullName + "  (" + $_.Length + " B)" } }
        else     { "    NONE  <-- v1 JAR signature absent (tools will report 'no certificate')" }
        $z.Dispose()
    } catch {
        "    ERROR reading zip: " + $_.Exception.Message
    }
}

Write-Host ""
Write-Host "=" * 72
Log "Release keystore certificate"
$ksProps = Join-Path $root 'keystore.properties'
if (Test-Path $ksProps) {
    $kv = @{}
    Get-Content $ksProps | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $p = $_ -split '=', 2
        $kv[$p[0].Trim()] = $p[1].Trim()
    }
    $ksPath = Join-Path $root $kv['storeFile']
    & $kt -list -v -keystore $ksPath -storetype PKCS12 `
          -storepass $kv['storePassword'] -alias $kv['keyAlias'] 2>&1 |
        Select-String 'Alias|Owner|Valid|Signature algorithm|Public Key|SHA1:|SHA256:' |
        ForEach-Object { "  " + $_.Line.Trim() }
} else {
    Log "  keystore.properties not found - skipping"
}

Write-Host ""
Log "===== DONE ====="
