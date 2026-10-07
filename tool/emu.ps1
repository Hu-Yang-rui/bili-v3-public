# Emulator driver helper: tap / swipe / type / screenshot / logcat / ui-dump.
# ASCII-only (PowerShell 5.1 reads .ps1 as ANSI/GBK).
#
# Usage:
#   powershell -File tool\emu.ps1 tap 270 715
#   powershell -File tool\emu.ps1 shot 03-video
#   powershell -File tool\emu.ps1 text "hello"
#   powershell -File tool\emu.ps1 ui
#   powershell -File tool\emu.ps1 log 30
param(
    [Parameter(Position = 0)][string]$Cmd = 'shot',
    [Parameter(Position = 1)][string]$A1 = '',
    [Parameter(Position = 2)][string]$A2 = '',
    [Parameter(Position = 3)][string]$A3 = ''
)

$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\27209\AppData\Local\Android\Sdk'
$adb = "$sdk\platform-tools\adb.exe"
$dev = 'emulator-5554'
$root = 'D:\deep\bili-v3'
$shotDir = Join-Path $root '_probe\shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Shot($name) {
    $remote = '/sdcard/_s.png'
    & $adb -s $dev shell screencap -p $remote 2>&1 | Out-Null
    $local = Join-Path $shotDir ("{0}.png" -f $name)
    & $adb -s $dev pull $remote $local 2>&1 | Out-Null
    if (Test-Path $local) {
        Write-Host ("shot: {0} ({1} bytes)" -f $local, (Get-Item $local).Length)
    } else {
        Write-Host 'shot FAILED'
    }
}

switch ($Cmd) {
    'tap' {
        & $adb -s $dev shell input tap $A1 $A2 2>&1 | Out-Null
        Write-Host ("tap {0},{1}" -f $A1, $A2)
    }
    'longpress' {
        & $adb -s $dev shell input swipe $A1 $A2 $A1 $A2 800 2>&1 | Out-Null
        Write-Host ("longpress {0},{1}" -f $A1, $A2)
    }
    'swipe' {
        # swipe x1 y1 x2 y2 duration
        & $adb -s $dev shell input swipe $A1 $A2 $A3 $((Get-Date).Ticks % 1) 2>&1 | Out-Null
        Write-Host 'swipe done'
    }
    'swipe4' {
        & $adb -s $dev shell input swipe $A1 $A2 $A3 '400' 2>&1 | Out-Null
        Write-Host ("swipe {0},{1} -> {2}" -f $A1, $A2, $A3)
    }
    'text' {
        # Escape spaces for the shell
        $esc = $A1 -replace ' ', '%s'
        & $adb -s $dev shell input text $esc 2>&1 | Out-Null
        Write-Host ("text: {0}" -f $A1)
    }
    'key' {
        & $adb -s $dev shell input keyevent $A1 2>&1 | Out-Null
        Write-Host ("keyevent {0}" -f $A1)
    }
    'back' {
        & $adb -s $dev shell input keyevent 4 2>&1 | Out-Null
        Write-Host 'back'
    }
    'shot' {
        Shot $A1
    }
    'ui' {
        & $adb -s $dev shell uiautomator dump /sdcard/_ui.xml 2>&1 | Out-Null
        $local = Join-Path $root '_probe\ui.xml'
        & $adb -s $dev pull /sdcard/_ui.xml $local 2>&1 | Out-Null
        if (Test-Path $local) {
            $c = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
            # Print text + clickable with full bounds
            foreach ($m in [regex]::Matches($c, '(?:text|content-desc)="([^"]{1,50})"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
                $t = $m.Groups[1].Value
                if ($t.Trim()) {
                    $cx = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                    $cy = [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2)
                    Write-Host ("  '{0}'  center=({1},{2})" -f $t, $cx, $cy)
                }
            }
        } else { Write-Host 'ui dump FAILED' }
    }
    'log' {
        $n = if ($A1) { [int]$A1 } else { 40 }
        & $adb -s $dev logcat -d -t $n 2>&1 | Select-Object -Last $n
    }
    'crash' {
        & $adb -s $dev logcat -d -b crash 2>&1 | Select-Object -Last 40
    }
    'clear' {
        & $adb -s $dev logcat -c 2>&1 | Out-Null
        Write-Host 'logcat cleared'
    }
    'restart' {
        & $adb -s $dev shell am force-stop com.example.biliv3 2>&1 | Out-Null
        Start-Sleep -Seconds 1
        & $adb -s $dev shell am start -n com.example.biliv3/.MainActivity 2>&1 | Out-Null
        Write-Host 'restarted'
    }
    default { Write-Host ("unknown cmd: {0}" -f $Cmd) }
}
