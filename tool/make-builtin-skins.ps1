# 生成内置示例装扮（Fake Skin）
#
# ⚠️ 版权说明（任务书第二十七条）：
#   `Rovniced/bilibili-skin` 里的资源属于其原作者/相关权利方。
#   所以**不把它们的图片打进 APK** —— 这里生成的是**本项目自制**的
#   简单几何图形，只用来演示装扮系统的各个映射位置。
#
#   用户想用真实装扮时，用「导入装扮」自己导入即可。

Add-Type -AssemblyName System.Drawing

$outRoot = Join-Path $PSScriptRoot '..\app\src\main\assets\fake_skin'
$outRoot = [System.IO.Path]::GetFullPath($outRoot)

function New-Dir($p) {
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
    return $p
}

function Save-Png($bmp, $path) {
    # ⚠️ 失败必须**抛出去** —— 早期版本这里静默吞掉异常，
    #    结果三套装扮各缺一个文件，脚本却打印"跑完了"。
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# ---------- 画一张渐变色块图 ----------
function New-Gradient($w, $h, $c1, $c2, $diagonal = $false) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList @($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.Rectangle -ArgumentList @(0, 0, $w, $h)
    $mode = if ($diagonal) { [System.Drawing.Drawing2D.LinearGradientMode]::ForwardDiagonal }
            else { [System.Drawing.Drawing2D.LinearGradientMode]::Vertical }
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush -ArgumentList @($rect, $c1, $c2, $mode)
    $g.FillRectangle($brush, $rect)
    $brush.Dispose(); $g.Dispose()
    return $bmp
}

# ---------- 画一个图标（透明底 + 简单形状）----------
function New-Icon($size, $color, $shape, $filled) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList @($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $c = [System.Drawing.ColorTranslator]::FromHtml($color)
    $pad = [int]($size * 0.18)
    $rect = New-Object System.Drawing.Rectangle -ArgumentList @($pad, $pad, ($size - 2 * $pad), ($size - 2 * $pad))

    if ($filled) {
        $brush = New-Object System.Drawing.SolidBrush -ArgumentList @($c)
        if ($shape -eq 'circle') { $g.FillEllipse($brush, $rect) }
        elseif ($shape -eq 'square') { $g.FillRectangle($brush, $rect) }
        else { $g.FillPolygon($brush, @(
            (New-Object System.Drawing.Point -ArgumentList @([int]($size/2), $pad)),
            (New-Object System.Drawing.Point -ArgumentList @(($size-$pad), ($size-$pad))),
            (New-Object System.Drawing.Point -ArgumentList @($pad, ($size-$pad))))) }
        $brush.Dispose()
    } else {
        $pen = New-Object System.Drawing.Pen -ArgumentList @($c, ([float]($size * 0.09)))
        if ($shape -eq 'circle') { $g.DrawEllipse($pen, $rect) }
        elseif ($shape -eq 'square') { $g.DrawRectangle($pen, $rect) }
        else { $g.DrawPolygon($pen, @(
            (New-Object System.Drawing.Point -ArgumentList @([int]($size/2), $pad)),
            (New-Object System.Drawing.Point -ArgumentList @(($size-$pad), ($size-$pad))),
            (New-Object System.Drawing.Point -ArgumentList @($pad, ($size-$pad))))) }
        $pen.Dispose()
    }
    $g.Dispose()
    return $bmp
}

# =====================================================================
# 三套内置示例
# =====================================================================
$skins = @(
    @{
        Id = 'builtin_aurora'
        Name = '极光（示例）'
        Color = '#5AC8FA'
        Color2 = '#0B2A3A'
        Tail = '#8A94A6'
        TailSel = '#5AC8FA'
        Bg1 = '#0B2A3A'; Bg2 = '#123B52'
        Accent = '#5AC8FA'
    },
    @{
        Id = 'builtin_sakura'
        Name = '樱（示例）'
        Color = '#FF8FB1'
        Color2 = '#3A1B26'
        Tail = '#9A8A90'
        TailSel = '#FF8FB1'
        Bg1 = '#3A1B26'; Bg2 = '#542838'
        Accent = '#FF8FB1'
    },
    @{
        Id = 'builtin_mint'
        Name = '薄荷（示例）'
        Color = '#4ADE80'
        Color2 = '#0E2A1C'
        Tail = '#8A9A90'
        TailSel = '#4ADE80'
        Bg1 = '#0E2A1C'; Bg2 = '#164030'
        Accent = '#4ADE80'
    }
)

foreach ($s in $skins) {
    $dir = New-Dir (Join-Path $outRoot $s.Id)

    # 预览图（16:9 横向）
    $prev = New-Gradient 640 360 $s.Bg1 $s.Bg2 $true
    $g = [System.Drawing.Graphics]::FromImage($prev)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $bar = New-Object System.Drawing.SolidBrush -ArgumentList @([System.Drawing.ColorTranslator]::FromHtml($s.Color))
    $g.FillRectangle($bar, 0, 320, 640, 40)   # 模拟底部导航条
    $g.FillEllipse($bar, 40, 40, 64, 64)       # 模拟头像位
    $bar.Dispose(); $g.Dispose()
    Save-Png $prev (Join-Path $dir 'preview.png')
    $prev.Dispose()

    # 顶部背景（宽幅）
    $b = New-Gradient 1080 400 $s.Bg1 $s.Bg2
    Save-Png $b (Join-Path $dir 'head_bg.png'); $b.Dispose()

    # Tab 背景
    $b = New-Gradient 1080 120 $s.Bg2 $s.Bg1
    Save-Png $b (Join-Path $dir 'head_tab_bg.png'); $b.Dispose()

    # 我的页背景
    $b = New-Gradient 1080 600 $s.Bg1 $s.Bg2 $true
    Save-Png $b (Join-Path $dir 'head_myself_squared_bg.png'); $b.Dispose()

    # 底部导航背景
    $b = New-Gradient 1080 160 $s.Bg2 $s.Bg1
    Save-Png $b (Join-Path $dir 'tail_bg.png'); $b.Dispose()

    # 发布按钮背景
    #
    # ⚠️ 必须**各建一个 Bitmap** —— GDI+ 的 `Bitmap.Save` 会占用文件句柄，
    #    对**同一个实例**保存两次会抛 `ArgumentException`
    #    （实测三套装扮的 `tail_icon_selected_pub_btn_bg.png` 全部缺失，
    #     而脚本只打印一行不显眼的异常，看起来像"跑完了"）。
    $b1 = New-Gradient 120 120 $s.Color $s.Accent $true
    Save-Png $b1 (Join-Path $dir 'tail_icon_pub_btn_bg.png'); $b1.Dispose()
    $b2 = New-Gradient 120 120 $s.Accent $s.Color $true
    Save-Png $b2 (Join-Path $dir 'tail_icon_selected_pub_btn_bg.png'); $b2.Dispose()

    # Tab 图标：未选中（描边、灰） / 选中（填充、主题色）
    $icons = @(
        @{ Name = 'tail_icon_main';            Shape = 'circle' },
        @{ Name = 'tail_icon_dynamic';         Shape = 'square' },
        @{ Name = 'tail_icon_channel';         Shape = 'triangle' },
        @{ Name = 'tail_icon_myself';          Shape = 'circle' }
    )
    foreach ($ic in $icons) {
        $i1 = New-Icon 96 $s.Tail $ic.Shape $false
        Save-Png $i1 (Join-Path $dir "$($ic.Name).png"); $i1.Dispose()

        $i2 = New-Icon 96 $s.TailSel $ic.Shape $true
        $selName = $ic.Name -replace '^tail_icon_', 'tail_icon_selected_'
        Save-Png $i2 (Join-Path $dir "$selName.png"); $i2.Dispose()
    }

    # 元数据（与本项目 SkinRepository 读的格式一致）
    $meta = [ordered]@{
        id = $s.Id
        name = $s.Name
        preview = ''
        color = $s.Color
        color_mode = 'dark'
        color_second_page = $s.Color2
        tail_color = $s.Tail
        tail_color_selected = $s.TailSel
        side_bg_color = ''
        tail_icon_ani = 'false'
        tail_icon_ani_mode = ''
        resources = [ordered]@{}
    }
    $metaJson = $meta | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $dir 'skin.json'), $metaJson, (New-Object System.Text.UTF8Encoding($false)))

    Write-Output "  $($s.Id): $((Get-ChildItem $dir).Count) 个文件"
}

Write-Output ""
Write-Output "输出目录: $outRoot"
Write-Output "总计: $((Get-ChildItem $outRoot -Recurse -File).Count) 个文件, $([math]::Round((Get-ChildItem $outRoot -Recurse -File | Measure-Object Length -Sum).Sum/1KB,1)) KB"
