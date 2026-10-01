# Full pipeline: start emulator -> wait boot -> install APK -> launch app -> screenshot
# ASCII-only on purpose: PowerShell 5.1 reads .ps1 as ANSI/GBK by default,
# which mangles non-ASCII characters and breaks string terminators.
$ErrorActionPreference = 'Continue'
$adb = 'D:\android-sdk\platform-tools\adb.exe'
$avd = "$env:USERPROFILE\.android\avd\bili_flutter_avd.avd"
$apk = 'D:\deep\bili-v3\app\build\outputs\apk\debug\app-debug.apk'
$shotDir = 'D:\deep\bili-v3\_shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format HH:mm:ss), $m) }

Log "1/7 cleanup"
Get-Process | Where-Object { $_.ProcessName -match 'qemu|netsim|emulator' } |
  Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
cmd.exe /c "del /f /q `"$avd\multiinstance.lock`"" 2>&1 | Out-Null
cmd.exe /c "rmdir /s /q `"$avd\hardware-qemu.ini.lock`"" 2>&1 | Out-Null
& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 2

Log "2/7 start emulator (-gpu host -no-window, detached)"
$emu = 'D:\android-sdk\emulator\emulator.exe'
$emuArgs = '-avd bili_flutter_avd -gpu host -no-window -no-boot-anim -no-snapshot -no-audio'
cmd.exe /c "start `"emu`" /b `"$emu`" $emuArgs" 2>&1 | Out-Null

Log "3/7 wait for device online (max 6 min)"
$online = $false
for ($i = 0; $i -lt 45; $i++) {
  $out = (& $adb devices 2>&1 | Out-String)
  if ($out -match 'emulator-5554\s+device') {
    Log ("  device online @ {0}s" -f ($i * 8))
    $online = $true
    break
  }
  Start-Sleep -Seconds 8
}
if (-not $online) { Log "FAIL: device never came online"; exit 1 }

Log "4/7 wait for boot_completed (max 6 min)"
$booted = $false
for ($i = 0; $i -lt 45; $i++) {
  $b = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
  if ($b -eq '1') {
    Log ("  boot complete @ {0}s" -f ($i * 8))
    $booted = $true
    break
  }
  Start-Sleep -Seconds 8
}
if (-not $booted) { Log "FAIL: boot never completed"; exit 2 }

Log "5/7 install APK"
& $adb install -r $apk 2>&1 | Select-Object -Last 2 | ForEach-Object { Log ("  " + $_) }

Log "6/7 launch app"
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -n com.example.biliv3/.MainActivity 2>&1 |
  Select-Object -Last 1 | ForEach-Object { Log ("  " + $_) }
Start-Sleep -Seconds 25

Log "7/7 screenshot"
& $adb shell screencap -p /sdcard/home.png 2>&1 | Out-Null
& $adb pull /sdcard/home.png "$shotDir\home-01.png" 2>&1 |
  Select-Object -Last 1 | ForEach-Object { Log ("  " + $_) }

Log "===== DONE ====="
$f = Get-Item "$shotDir\home-01.png" -ErrorAction SilentlyContinue
if ($f) { Log ("screenshot: {0}  {1} KB" -f $f.FullName, [math]::Round($f.Length / 1KB, 1)) }
$pid1 = (& $adb shell pidof com.example.biliv3 2>$null | Out-String).Trim()
Log ("app pid: {0}" -f $pid1)
