package com.example.biliv3.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.data.model.PlayInfo
import com.example.biliv3.data.subtitle.SubtitleBody
import com.example.biliv3.data.subtitle.SubtitleTrack
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.InlinePicker
import com.example.biliv3.ui.component.PickerOption

/**
 * 播放设置弹层：字幕 / AI 翻译、清晰度、倍速。
 *
 * ## 为什么 `info` / `player` 是可空的
 *
 * 字幕是**任何时候都要能选**的 —— 包括"还没点播放"和"播放失败"时。
 * 若要求必须已有取流信息才打开弹层，用户就永远找不到 AI 字幕入口
 * （这正是上一版的 bug）。
 *
 * 因此：
 * - 字幕区：始终渲染（只要 showSettings）
 * - 清晰度 / 倍速区：`info` / `player` 为 null 时不渲染
 *
 * ## 为什么字幕放最前
 *
 * AI 翻译是主推能力，且用户找它的频率高于改清晰度。
 * 清晰度对大多数人是"设一次就不改"，字幕则是"每个视频都要选"。
 */
@Composable
fun PlayerSettingsSheet(
    info: PlayInfo?,
    player: ExoPlayer?,
    onSelectQuality: (Int) -> Unit,
    onDismiss: () -> Unit,
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    activeSubtitle: SubtitleBody? = null,
    subtitleLoading: Boolean = false,
    isLoggedIn: Boolean = true,
    onSelectSubtitle: (SubtitleTrack?) -> Unit = {},
    onLoginRequired: () -> Unit = {},
    // ---- 弹幕 ----
    danmakuEnabled: Boolean = true,
    danmakuAlpha: Float = 0.9f,
    danmakuFontScale: Float = 1f,
    danmakuArea: Float = 1f,
    onToggleDanmaku: () -> Unit = {},
    onDanmakuAlpha: (Float) -> Unit = {},
    onDanmakuFontScale: (Float) -> Unit = {},
    onDanmakuArea: (Float) -> Unit = {},
) {
    val colors = BiliTheme.colors
    // 记录当前倍速；player 为空时用默认值
    var speed by remember { mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f) }

    /**
     * 当前打开的选择器（null = 未打开）。
     *
     * ## 为什么用一个状态而不是每个选项各自一个布尔
     *
     * 同一时刻只可能有**一个**选择器打开。用密封类表达"当前打开哪个"
     * 天然排除了"两个面板同时弹出来"的非法状态 ——
     * 若各自持布尔，很容易出现两个都 true 的 bug。
     */
    var picker by remember { mutableStateOf<PickerKindId?>(null) }

    /**
     * ⚠️ 必须用 `Dialog` 承载，不能只画成一个 `if (showSettings) Box { … }`。
     *
     * ## 这是「手势返回直接回首页」的根因
     *
     * 之前弹层只是一段普通的 Compose 内容，**不是路由、也不是 Dialog**。
     * 于是系统返回手势（预测性返回 / `OnBackPressedDispatcher`）完全
     * 感知不到它 —— 手势被 NavHost 接管，直接 `popBackStack()` 视频页，
     * 回到首页。表现就是「点齿轮后返回，视频页没了」。
     *
     * `Dialog` 自带独立的 window 与 `OnBackPressedDispatcher`，
     * 系统返回**优先交给最上层的 Dialog**，由 `onDismissRequest` 消费。
     * 这样返回手势就变成「关弹层、留在视频页」，符合层级预期。
     *
     * ## 为什么用 `DialogProperties` 而不是 `ModalBottomSheet`
     *
     * `ModalBottomSheet` 会带来 Material3 的遮罩/拖拽/动画一整套开销
     * （见性能部分的说明），且它的返回处理同样依赖 scrim。
     * 这里只需要「独立 window + 返回拦截 + 自己画视觉」，`Dialog` 最轻。
     * `decorFitsSystemWindows = false` 让我们自己处理 inset。
     */
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 遮罩：点击空白关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrimPanel)
                // ⚠️ 不用纯黑：纯黑遮罩与深色圆角面板叠在一起，
                // 会在面板边缘形成一圈"描边"，看起来像没对齐的穿模。
                // 用主题 scrim（半透明黑）并降低不透明度，边缘自然融合。
                .clickable(onClick = onDismiss),
            // ⚠️ 位置修正：不再 align(BottomCenter) 贴底。
            //
            // 贴底 + navigationBarsPadding 会让面板整体压在屏幕最下方，
            // 手机上手要伸到底部才能操作，用户报告"太靠下"。
            //
            // 改为 Center 居中：面板高度有限（档位已折叠），居中后
            // 拇指自然覆盖，也更符合"设置弹层"而非"底部抽屉"的语义。
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Space.x4)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.panel))
                    // 弹层用 `surfaceElevated`（比卡片亮一档）——
                    // 深色下投影不可见，分层只能靠提亮。
                    .background(colors.surfaceElevated)
                    // ⚠️ 点**弹层内的空白处**也要收起已展开的选择器。
                    //
                    // 行内展开不是 Dialog，没有天然的"外部点击"通知；
                    // 用整块内容的 clickable 兜住 —— 子控件（选项行）
                    // 自己消费点击，所以不会误触。
                    .clickable { picker = null }
                    // ⚠️ 高度上限：面板变高时（弹幕展开 + 字幕多轨）
                    // 不能顶到状态栏。给个上限并内部滚动。
                    .heightIn(max = MAX_SHEET_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Space.x3),
            ) {
                // ---- 标题栏 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.x4, vertical = Space.x3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "播放设置",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = FontSize.titleMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(Sizes.iconXl + Space.x2)
                            .clip(RoundedCornerShape(Radius.interactive))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = colors.textSecondarySafe,
                            modifier = Modifier.size(Sizes.iconLg),
                        )
                    }
                }

                // ---- 字幕 / AI 翻译（放最前）----
                SubtitleSection(
                    tracks = subtitleTracks,
                    active = activeSubtitle,
                    loading = subtitleLoading,
                    isLoggedIn = isLoggedIn,
                    onSelect = onSelectSubtitle,
                    onLoginRequired = onLoginRequired,
                )

                Spacer(Modifier.height(Space.x3))

                // ---- 弹幕 ----
                DanmakuSection(
                    enabled = danmakuEnabled,
                    alpha = danmakuAlpha,
                    fontScale = danmakuFontScale,
                    area = danmakuArea,
                    onToggle = onToggleDanmaku,
                    onAlpha = onDanmakuAlpha,
                    onFontScale = onDanmakuFontScale,
                    onArea = onDanmakuArea,
                    // 三项目前都是"点开 → 底部面板选"，
                    // 由本弹层统一渲染面板（保证同一时刻只有一个）
                    expandedPicker = picker,
                    onTogglePicker = { id ->
                        picker = if (picker == id) null else id
                    },
                )

                Spacer(Modifier.height(Space.x3))

                // ---- 清晰度：折叠为一行选择器（不再平铺 5 行）----
                //
                // 实测一个普通视频 accept_quality 有 5 项
                // （1080P+ / 1080P / 720P / 480P / 360P），
                // 平铺就是 5 个整行 —— 加上倍速 6 项、字幕若干，
                // 弹层会长到需要滚动，正是用户抱怨的"选项全平铺、占地方"。
                //
                // 现在收成一个「清晰度  高清 1080P  ▸」的单行，
                // 点开才展开列表；选完自动收起。
                if (info != null && info.acceptQuality.isNotEmpty()) {
                    val currentIndex = info.acceptQuality.indexOf(info.currentQuality)
                        .takeIf { it >= 0 } ?: 0
                    val currentLabel = info.acceptDescription.getOrNull(currentIndex)
                        ?: "清晰度 ${info.currentQuality}"

                    InlinePicker(
                        label = "清晰度",
                        currentLabel = currentLabel,
                        options = info.acceptQuality.mapIndexed { i, q ->
                            PickerOption(
                                key = q,
                                label = info.acceptDescription.getOrNull(i) ?: "清晰度 $q",
                            )
                        },
                        selectedKey = info.currentQuality,
                        expanded = picker == PickerKindId.Quality,
                        onToggle = {
                            picker = if (picker == PickerKindId.Quality) null
                            else PickerKindId.Quality
                        },
                        onSelect = {
                            onSelectQuality(it)
                            picker = null
                        },
                    )
                }

                // ---- 倍速：同样折叠为一行选择器 ----
                if (player != null) {
                    InlinePicker(
                        label = "倍速",
                        currentLabel = "${formatSpeedLabel(speed)}×",
                        options = SPEEDS.map {
                            PickerOption(key = it, label = "${formatSpeedLabel(it)}×")
                        },
                        selectedKey = speed,
                        expanded = picker == PickerKindId.Speed,
                        onToggle = {
                            picker = if (picker == PickerKindId.Speed) null
                            else PickerKindId.Speed
                        },
                        onSelect = { sp ->
                            player.setPlaybackSpeed(sp)
                            speed = sp
                            picker = null
                        },
                    )
                }
            }
        }
    }
}

/**
 * 当前展开的选择器标识。
 *
 * ## 为什么从密封类退回枚举
 *
 * 上一版把 options 一起塞进密封类（`PickerKind.Quality(options, selected)`），
 * 因为那时是**弹窗**：面板渲染处拿不到调用方的数据，必须随状态带上。
 *
 * 现在改成**行内展开** —— 面板就渲染在选择框正下方，天然能直接读到
 * 本地的 options/selected。于是状态只需要回答"哪个展开了"，
 * 一个枚举足够，且少了一层数据同步（options 变了不用管 picker）。
 */
private enum class PickerKindId {
    Quality,
    Speed,
    DanmakuAlpha,
    DanmakuFont,
    DanmakuArea,
}


/** 展开态胶囊行的固定高度（胶囊 ~28dp + 底部留白 8dp）。 */
private val PICKER_ROW_HEIGHT = 36.dp

/**
 * 字幕选择区。
 *
 * ## AI 翻译轨的标注
 *
 * `SubtitleTrack.isAiTranslate`（`aiType == 1`）时显示「AI 翻译」标签 ——
 * 这是用户能一眼找到 AI 翻译的关键（官方 App 也是这个做法）。
 *
 * ## 未登录的处理
 *
 * 实测未登录时拿不到任何字幕轨（REST 与 gRPC 都为空），
 * 此时显示"登录后可用"并给登录入口，而不是显示空列表或报错。
 */
@Composable
private fun SubtitleSection(
    tracks: List<SubtitleTrack>,
    active: SubtitleBody?,
    loading: Boolean,
    isLoggedIn: Boolean,
    onSelect: (SubtitleTrack?) -> Unit,
    onLoginRequired: () -> Unit,
) {
    val colors = BiliTheme.colors

    SectionLabel("字幕 / AI 翻译")

    Column(modifier = Modifier.padding(horizontal = Space.x4)) {
        when {
            loading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = colors.brandPrimary,
                    strokeWidth = Space.trackHeight,
                    modifier = Modifier.size(Sizes.iconLg),
                )
                Spacer(Modifier.width(Space.x3))
                Text(
                    text = "正在加载字幕…",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textSecondarySafe,
                    ),
                )
            }

            !isLoggedIn -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.interactive))
                    .clickable(onClick = onLoginRequired)
                    .padding(vertical = Space.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "登录后可查看字幕与 AI 翻译",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textLinkSafe,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "去登录",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.brandPrimary,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            tracks.isEmpty() -> Text(
                text = "该视频没有可用字幕",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textTertiary,
                ),
                modifier = Modifier.padding(vertical = Space.x3),
            )

            else -> {
                // 「关闭字幕」固定第一项
                SettingRow(
                    label = "关闭字幕",
                    selected = active == null,
                    onClick = { onSelect(null) },
                )
                tracks.forEach { t ->
                    SettingRow(
                        label = t.lanDoc.ifEmpty { t.lan },
                        selected = active?.track?.id == t.id,
                        badge = when {
                            t.isAiTranslate -> "AI 翻译"
                            t.isAi -> "AI 字幕"
                            else -> null
                        },
                        onClick = { onSelect(t) },
                    )
                }
            }
        }
    }
}

/**
 * 弹幕设置区。
 *
 * ## 档位化而不是滑块随意拖
 *
 * 官方弹幕设置是**离散档位**（显示区域 5 档、字号 5 档），
 * 不是连续滑块 —— 离散档位心智负担低（`AGENTS.md` §5.1 的观察）。
 * 这里也照做：字号 / 不透明度 / 显示区域都给固定档位。
 */
@Composable
private fun DanmakuSection(
    enabled: Boolean,
    alpha: Float,
    fontScale: Float,
    area: Float,
    onToggle: () -> Unit,
    /** 当前展开的选择器（null = 都没展开）。 */
    expandedPicker: PickerKindId?,
    /** 请求切换展开状态（点选择框时调用）。 */
    onTogglePicker: (PickerKindId) -> Unit,
    onAlpha: (Float) -> Unit,
    onFontScale: (Float) -> Unit,
    onArea: (Float) -> Unit,
) {
    val colors = BiliTheme.colors

    SectionLabel("弹幕")

    Column(modifier = Modifier.padding(horizontal = Space.x4)) {
        // ---- 总开关 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.interactive))
                .clickable(onClick = onToggle)
                .padding(vertical = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "显示弹幕",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = colors.textOnBrand,
                    checkedTrackColor = colors.brandPrimary,
                ),
            )
        }

        // 关闭时后面的细项无意义，直接不渲染
        if (!enabled) return@Column

        // ---- 三项档位：全部折叠为单行选择器 ----
        //
        // 原先每项都是「标签 + 5 个胶囊」三行，合计约 9 行高度。
        // 现在各压成一行（标题 + 当前值 ▸），点开才展开胶囊 ——
        // 弹层高度从"要滚动"降到"一眼看完"。
        InlinePicker(
            label = "不透明度",
            currentLabel = "${(alpha * 100).toInt()}%",
            options = DANMAKU_ALPHA_OPTIONS.map {
                PickerOption(key = it, label = "${(it * 100).toInt()}%")
            },
            selectedKey = alpha,
            expanded = expandedPicker == PickerKindId.DanmakuAlpha,
            onToggle = { onTogglePicker(PickerKindId.DanmakuAlpha) },
            onSelect = { onAlpha(it); onTogglePicker(PickerKindId.DanmakuAlpha) },
        )
        InlinePicker(
            label = "字号",
            currentLabel = "${(fontScale * 100).toInt()}%",
            options = DANMAKU_FONT_OPTIONS.map {
                PickerOption(key = it, label = "${(it * 100).toInt()}%")
            },
            selectedKey = fontScale,
            expanded = expandedPicker == PickerKindId.DanmakuFont,
            onToggle = { onTogglePicker(PickerKindId.DanmakuFont) },
            onSelect = { onFontScale(it); onTogglePicker(PickerKindId.DanmakuFont) },
        )
        InlinePicker(
            label = "显示区域",
            currentLabel = areaLabel(area),
            options = DANMAKU_AREA_OPTIONS.map {
                PickerOption(key = it, label = areaLabel(it))
            },
            selectedKey = area,
            expanded = expandedPicker == PickerKindId.DanmakuArea,
            onToggle = { onTogglePicker(PickerKindId.DanmakuArea) },
            onSelect = { onArea(it); onTogglePicker(PickerKindId.DanmakuArea) },
        )
    }
}

/** 显示区域档位的中文标签。 */
private fun areaLabel(ratio: Float): String = when {
    ratio <= 0.3f -> "1/4 屏"
    ratio <= 0.55f -> "半屏"
    ratio <= 0.8f -> "3/4 屏"
    else -> "全屏"
}

@Composable
private fun SectionLabel(text: String) {
    val colors = BiliTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            color = BiliTheme.colors.textTertiary,
        ),
        modifier = Modifier.padding(
            start = Space.x4,
            end = Space.x4,
            top = Space.x2,
            bottom = Space.x1,
        ),
    )
}

/**
 * 单行设置项。
 *
 * 选中项用**勾选图标 + 品牌色**双重表达，不依赖颜色单一维度。
 * `badge` 用于标注「AI 翻译」这类特殊项。
 */
@Composable
private fun SettingRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.interactive))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = if (selected) colors.brandPrimary else colors.textPrimary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
        if (badge != null) {
            Spacer(Modifier.width(Space.x2))
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.badge,
                    color = colors.textOnBrand,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.badge))
                    .background(colors.brandPrimary)
                    .padding(horizontal = Space.compactHorizontal, vertical = 1.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = colors.brandPrimary,
                modifier = Modifier.size(Sizes.iconLg),
            )
        } else {
            // 占位，保持行高一致，避免选中态切换时跳动
            Spacer(Modifier.width(Sizes.iconLg))
        }
    }
}

/** 倍速档位。离散档位比滑块心智负担低。 */
private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 播放设置面板的遮罩。
 *
 * ⚠️ 这里**不再**是文件级常量。
 *
 * 首版写的是 `private val SHEET_SCRIM = Color(0x73000000)` —— 硬编码色，
 * 不随主题切换。改用令牌时不能简单替换成 `colors.scrimPanel`，
 * 因为顶层 `val` 无法访问 `BiliTheme.colors`（那是 Composable 作用域）。
 *
 * 因此遮罩色在调用点从 `BiliTheme.colors.scrimPanel` 取（见下方 Dialog 内）。
 */

/**
 * 面板最大高度。
 *
 * 手机竖屏约 800dp 高，取 480dp 留出上下文（用户能看见自己点的是哪个视频）。
 * 超出则内部滚动，而不是让面板顶满整屏。
 */
private val MAX_SHEET_HEIGHT = 480.dp

/** 弹幕不透明度档位。 */
private val DANMAKU_ALPHA_OPTIONS = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f)

/** 弹幕字号档位。 */
private val DANMAKU_FONT_OPTIONS = listOf(0.7f, 0.85f, 1.0f, 1.2f, 1.5f)

/** 弹幕显示区域档位（占屏幕高度比例）。 */
private val DANMAKU_AREA_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f)

/** `1.0` → `1`，`1.25` → `1.25`。 */
private fun formatSpeedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
