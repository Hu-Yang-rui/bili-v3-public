# Launch emulator with full output capture + wait for boot.
# ASCII-only (PowerShell 5.1 reads .ps1 as ANSI/GBK).
#
# Usage: powershell -File tool\emu-up.ps1 [-Avd name] [-Wipe]
param(
    [string]$Avd = 'bili_flutter_avd',
    [switch]$Wipe,
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Continue'
$adb = 'D:\android-sdk\platform-tools\adb.exe'
$emu = 'D:\android-sdk\emulator\emulator.exe'
$avdDir = "$env:USERPROFILE\.android\avd\$Avd.avd"
$logDir = 'D:\deep\bili-v3\_probe'
$outLog = Join-Path $logDir 'emu-out.log'
$errLog = Join-Path $logDir 'emu-err.log'

function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }

Log 'kill leftovers'
Get-Process | Where-Object { $_.ProcessName -match 'qemu|netsim|emulator' } |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 4

Log 'reset adb (kills stale transports from earlier `adb connect`)'
& $adb kill-server 2>&1 | Out-Null
Start-Sleep -Seconds 2
# Remove any stale TCP transport entries so emulator-5554 is the only device.
Remove-Item "$env:USERPROFILE\.android\adb_known_hosts" -Force -ErrorAction SilentlyContinue

Log 'clear locks'
Get-ChildItem $avdDir -Filter '*.lock' -Recurse -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

if ($Wipe) {
    Log 'wipe userdata (fresh boot; slow)'
    Get-ChildItem "$avdDir\snapshots" -Recurse -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

$emuArgs = @(
    '-avd', $Avd,
    '-port', '5554',
    '-gpu', 'swiftshader_indirect',
    '-no-window', '-no-boot-anim', '-no-audio', '-no-metrics'
)
if ($Wipe) { $emuArgs += '-wipe-data' } else { $emuArgs += '-no-snapshot' }

Log ("start: {0} {1}" -f $emu, ($emuArgs -join ' '))
# Use cmd's start /b so we never touch Start-Process (it throws on this box:
# the process env has both NO_PROXY and no_proxy, and Start-Process builds a
# case-insensitive dictionary -> "Item has already been added").
$argStr = ($emuArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
cmd.exe /c "start `"emu`" /b `"$emu`" $argStr > `"$outLog`" 2> `"$errLog`"" 2>&1 | Out-Null

Log 'start adb server'
Start-Sleep -Seconds 15
& $adb start-server 2>&1 | Out-Null

Log ("wait for boot (max {0}s)" -f $TimeoutSec)
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$last = ''
$booted = $false
while ((Get-Date) -lt $deadline) {
    $out = (& $adb devices 2>&1 | Out-String)
    $state = if ($out -match 'emulator-5554\s+(\w+)') { $matches[1] } else { 'none' }

    if ($state -eq 'device') {
        $b = (& $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        $line = "  state=device boot=$b"
        if ($line -ne $last) { Log $line; $last = $line }
        if ($b -eq '1') { $booted = $true; break }
    } else {
        $line = "  state=$state"
        if ($line -ne $last) { Log $line; $last = $line }
        # If qemu died, stop early.
        if (-not (Get-Process -Name 'qemu-system-x86_64-headless' -ErrorAction SilentlyContinue)) {
            Log '  qemu process is gone -> aborted'
            break
        }
    }
    Start-Sleep -Seconds 10
}

if ($booted) {
    Log 'BOOT OK'
    & $adb -s emulator-5554 shell wm size 2>&1
    & $adb -s emulator-5554 shell getprop ro.build.version.release 2>&1
} else {
    Log 'BOOT FAILED'
    Log '--- emulator stdout (tail) ---'
    Get-Content $outLog -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
    Log '--- emulator stderr (tail) ---'
    Get-Content $errLog -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
}
