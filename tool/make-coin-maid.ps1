# Make the reference character image's background transparent, preserving
# INTERIOR white areas (apron / headdress) by flood-filling from the borders.
#
# Why flood fill instead of "all white -> transparent":
#   The character wears a WHITE apron and a WHITE headdress. A naive
#   threshold would punch holes through her costume. Flood fill only removes
#   white pixels CONNECTED to the image border, i.e. the actual backdrop.
#
# ASCII-only (PowerShell 5.1 reads .ps1 as ANSI/GBK).
#
# ⚠️ No param() block on purpose -- this script had two PowerShell traps:
#   1. A $src variable collided with a $Src parameter (names are
#      case-insensitive), so $src silently became a String.
#   2. `New-Object Type($arg)` returns $null in PS 5.1; must use
#      -ArgumentList. Combined with (1) this produced a confusing
#      "cannot call a method on null" deep in the pixel loop.
#   Hard-coding the paths removes trap (1) entirely.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$srcPath = 'C:\Users\27209\.dsh\attachments\v1\objects\28\28581666639eb10dbe4cf94532283a8fb00665486d2fe1fa8fbbc6456f5c81ff'
$dstPath = 'D:\deep\bili-v3\app\src\main\res\drawable-nodpi\coin_maid.png'
$tolerance = 12

$img = [System.Drawing.Image]::FromFile($srcPath)
$w = $img.Width
$h = $img.Height

# -ArgumentList is REQUIRED (see header note 2)
$srcBmp = New-Object System.Drawing.Bitmap -ArgumentList @($img)
$img.Dispose()

$dst = New-Object System.Drawing.Bitmap -ArgumentList @($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

# ---- 1. read source pixels ----
$rect = New-Object System.Drawing.Rectangle -ArgumentList @(0, 0, $w, $h)
$data = $srcBmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$srcBmp.UnlockBits($data)

# ---- 2. flood fill from border ----
$visited = New-Object bool[] ($w * $h)
$queue = New-Object 'System.Collections.Generic.Queue[int]'

function Test-Bg {
    param([int]$Index)
    $o = $Index * 4
    $b = $bytes[$o]
    $g = $bytes[$o + 1]
    $r = $bytes[$o + 2]
    return (($r -ge (255 - $tolerance)) -and ($g -ge (255 - $tolerance)) -and ($b -ge (255 - $tolerance)))
}

for ($x = 0; $x -lt $w; $x++) {
    foreach ($y in @(0, ($h - 1))) {
        $idx = $y * $w + $x
        if ((-not $visited[$idx]) -and (Test-Bg -Index $idx)) {
            $visited[$idx] = $true
            $queue.Enqueue($idx)
        }
    }
}
for ($y = 0; $y -lt $h; $y++) {
    foreach ($x in @(0, ($w - 1))) {
        $idx = $y * $w + $x
        if ((-not $visited[$idx]) -and (Test-Bg -Index $idx)) {
            $visited[$idx] = $true
            $queue.Enqueue($idx)
        }
    }
}

$removed = 0
while ($queue.Count -gt 0) {
    $idx = $queue.Dequeue()
    $removed++
    $x = $idx % $w
    $y = [int](($idx - $x) / $w)

    $n1 = $idx + 1
    if (($x + 1) -lt $w -and (-not $visited[$n1]) -and (Test-Bg -Index $n1)) {
        $visited[$n1] = $true; $queue.Enqueue($n1)
    }
    $n2 = $idx - 1
    if (($x - 1) -ge 0 -and (-not $visited[$n2]) -and (Test-Bg -Index $n2)) {
        $visited[$n2] = $true; $queue.Enqueue($n2)
    }
    $n3 = $idx + $w
    if (($y + 1) -lt $h -and (-not $visited[$n3]) -and (Test-Bg -Index $n3)) {
        $visited[$n3] = $true; $queue.Enqueue($n3)
    }
    $n4 = $idx - $w
    if (($y - 1) -ge 0 -and (-not $visited[$n4]) -and (Test-Bg -Index $n4)) {
        $visited[$n4] = $true; $queue.Enqueue($n4)
    }
}

# ---- 3. write output ----
$out = New-Object byte[] ($stride * $h)
[Array]::Copy($bytes, $out, $bytes.Length)
for ($i = 0; $i -lt ($w * $h); $i++) {
    if ($visited[$i]) {
        $out[($i * 4) + 3] = 0
    }
}

$ddata = $dst.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
[System.Runtime.InteropServices.Marshal]::Copy($out, 0, $ddata.Scan0, $out.Length)
$dst.UnlockBits($ddata)

# ---- 4. save ----
$dir = Split-Path $dstPath -Parent
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$dst.Save($dstPath, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
$srcBmp.Dispose()

Write-Host ("size      : {0} x {1}" -f $w, $h)
Write-Host ("bg pixels : {0} of {1} ({2:N1}%)" -f $removed, ($w * $h), (100.0 * $removed / ($w * $h)))
Write-Host ("saved     : {0}" -f $dstPath)
Write-Host ("bytes     : {0}" -f (Get-Item $dstPath).Length)
