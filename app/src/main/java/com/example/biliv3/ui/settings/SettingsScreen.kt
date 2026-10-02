package com.example.biliv3.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biliv3.data.Settings
import com.example.biliv3.design.BiliTheme
import com.example.biliv3.design.biliCard
import com.example.biliv3.design.tokens.FontSize
import com.example.biliv3.design.tokens.Radius
import com.example.biliv3.design.tokens.Sizes
import com.example.biliv3.design.tokens.Space
import com.example.biliv3.ui.component.InlinePicker
import com.example.biliv3.ui.component.PickerOption
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant

/**
 * 设置页。
 *
 * ## 分组
 *
 * ```
 * 播放   默认倍速 / 自动起播 / 默认清晰度 / 优先 H.264
 * 弹幕   总开关 / 不透明度 / 字号 / 显示区域
 * 隐私   保存观看历史 / 个性化推荐
 * 通知   回复我的 / 更新提醒
 * 关于   版本号
 * ```
 *
 * ## 交互约定
 *
 * - **开关类**：整行可点（不只点 Switch）—— 触摸目标大得多
 * - **档位类**：离散胶囊，与播放器弹层一致（不引入滑块，理由见下）
 * - **危险项**：`优先 H.264` 关掉可能导致黑屏，明确写进副标题
 *
 * ## 为什么档位用胶囊而不是滑块
 *
 * 与 `PlayerSettingsSheet` 保持一致：官方弹幕设置是**离散档位**
 * （字号 5 档、不透明度 5 档），离散档位心智负担低。
 * 而且滑块在设置页里没有实时预览，用户拖动时不知道效果差异。
 *
 * ## 持久化
 *
 * 全部写 `SettingsStore`（DataStore）。改完即写盘，
 * 不需要"保存"按钮 —— 移动端设置页普遍是即改即生效。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /**
     * 清理图片缓存。
     *
     * ⚠️ 用回调而不是直接在这里拿 Context：缓存清理属于**应用级动作**
     * （需要 Coil 的 ImageLoader 单例），设置页只负责触发。
     */
    onClearImageCache: () -> Unit = {},
    /**
     * 当前图片缓存占用的可读文案（如 "12.4 MB"）。
     *
     * 由调用方异步计算后传入 —— 设置页不做 IO。
     */
    cacheLabel: String = "计算中…",
    viewModel: SettingsViewModel = viewModel(),
) {
    val colors = BiliTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    /**
     * 当前打开的选择器（null = 未打开）。
     *
     * 与播放设置弹层同一套做法：用密封类表达"当前打开哪个"，
     * 天然排除"两个面板同时弹出"的非法状态。
     */
    var picker by remember { mutableStateOf<SettingsPickerId?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase)
            // ⚠️ 点页面空白处收起已展开的选择器。
            // 子控件（选项行）自己消费点击，不会误触。
            .clickable { picker = null },
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .biliCard(
                    elevation = 0.dp,
                    shape = RoundedCornerShape(
                        bottomStart = Radius.card,
                        bottomEnd = Radius.card,
                    ),
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(Sizes.topBarMobile)
                .padding(horizontal = Space.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Space.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(Sizes.iconXl),
                )
            }
            Spacer(Modifier.width(Space.x1))
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = FontSize.titleMd,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Space.x8),
        ) {
            // ================= 播放 =================
            SectionHeader("播放")

            InlinePicker(
                label = "默认倍速",
                currentLabel = "${formatSpeedLabel(settings.defaultSpeed)}×",
                options = SPEEDS.map {
                    PickerOption(key = it, label = "${formatSpeedLabel(it)}×")
                },
                selectedKey = settings.defaultSpeed,
                expanded = picker == SettingsPickerId.Speed,
                onToggle = {
                    picker = if (picker == SettingsPickerId.Speed) null
                    else SettingsPickerId.Speed
                },
                onSelect = {
                    viewModel.setDefaultSpeed(it)
                    picker = null
                },
            )

            SwitchRow(
                title = "自动起播",
                subtitle = "进入视频页立即开始播放（关闭则点封面才播）",
                checked = settings.autoPlay,
                onCheckedChange = viewModel::setAutoPlay,
            )

            InlinePicker(
                label = "默认清晰度",
                currentLabel = QUALITY_OPTIONS.firstOrNull {
                    it.first == settings.defaultQuality
                }?.second ?: "自动",
                options = QUALITY_OPTIONS.map { (q, label) ->
                    PickerOption(key = q, label = label)
                },
                selectedKey = settings.defaultQuality,
                expanded = picker == SettingsPickerId.Quality,
                onToggle = {
                    picker = if (picker == SettingsPickerId.Quality) null
                    else SettingsPickerId.Quality
                },
                onSelect = {
                    viewModel.setDefaultQuality(it)
                    picker = null
                },
            )

            SwitchRow(
                title = "优先 H.264 编码",
                subtitle = "部分设备的硬解不支持 HEVC，关闭后可能黑屏",
                checked = settings.preferH264,
                onCheckedChange = viewModel::setPreferH264,
            )

            // ================= 弹幕 =================
            SectionHeader("弹幕")

            SwitchRow(
                title = "显示弹幕",
                subtitle = null,
                checked = settings.danmakuEnabled,
                onCheckedChange = viewModel::setDanmakuEnabled,
            )

            // 关掉总开关后细项无意义，直接不渲染（避免"改了没反应"的困惑）
            if (settings.danmakuEnabled) {
                InlinePicker(
                    label = "不透明度",
                    currentLabel = "${(settings.danmakuAlpha * 100).toInt()}%",
                    options = DANMAKU_ALPHA_OPTIONS.map {
                        PickerOption(key = it, label = "${(it * 100).toInt()}%")
                    },
                    selectedKey = settings.danmakuAlpha,
                    expanded = picker == SettingsPickerId.Alpha,
                    onToggle = {
                        picker = if (picker == SettingsPickerId.Alpha) null
                        else SettingsPickerId.Alpha
                    },
                    onSelect = {
                        viewModel.setDanmakuAlpha(it)
                        picker = null
                    },
                )
                InlinePicker(
                    label = "字号",
                    currentLabel = "${(settings.danmakuFontScale * 100).toInt()}%",
                    options = DANMAKU_FONT_OPTIONS.map {
                        PickerOption(key = it, label = "${(it * 100).toInt()}%")
                    },
                    selectedKey = settings.danmakuFontScale,
                    expanded = picker == SettingsPickerId.Font,
                    onToggle = {
                        picker = if (picker == SettingsPickerId.Font) null
                        else SettingsPickerId.Font
                    },
                    onSelect = {
                        viewModel.setDanmakuFontScale(it)
                        picker = null
                    },
                )
                InlinePicker(
                    label = "显示区域",
                    currentLabel = areaLabel(settings.danmakuArea),
                    options = DANMAKU_AREA_OPTIONS.map {
                        PickerOption(key = it, label = areaLabel(it))
                    },
                    selectedKey = settings.danmakuArea,
                    expanded = picker == SettingsPickerId.Area,
                    onToggle = {
                        picker = if (picker == SettingsPickerId.Area) null
                        else SettingsPickerId.Area
                    },
                    onSelect = {
                        viewModel.setDanmakuArea(it)
                        picker = null
                    },
                )

                // ---- 弹幕屏蔽（本地规则）----
                //
                // ⚠️ 这是「弹幕屏蔽」从"未实现"变"已实现"的地方。
                // 官方 App 的屏蔽分三类：类型 / 关键词 / 用户。
                // 本项目做**前两类**（纯本地过滤，不发请求、不依赖额外接口）：
                // - 类型：滚动 / 顶部 / 底部 各自开关
                // - 关键词：命中任一即不渲染
                // 「按用户屏蔽」需要拉黑名单接口，属另一件事，暂不做。
                SectionHeader("弹幕屏蔽（本地生效）")

                SwitchRow(
                    title = "屏蔽滚动弹幕",
                    subtitle = null,
                    checked = DANMAKU_MODE_SCROLL in settings.danmakuBlockModes,
                    onCheckedChange = { on ->
                        viewModel.setDanmakuBlockModes(
                            toggleMode(settings.danmakuBlockModes, DANMAKU_MODE_SCROLL, on),
                        )
                    },
                )
                SwitchRow(
                    title = "屏蔽顶部弹幕",
                    subtitle = null,
                    checked = DANMAKU_MODE_TOP in settings.danmakuBlockModes,
                    onCheckedChange = { on ->
                        viewModel.setDanmakuBlockModes(
                            toggleMode(settings.danmakuBlockModes, DANMAKU_MODE_TOP, on),
                        )
                    },
                )
                SwitchRow(
                    title = "屏蔽底部弹幕",
                    subtitle = null,
                    checked = DANMAKU_MODE_BOTTOM in settings.danmakuBlockModes,
                    onCheckedChange = { on ->
                        viewModel.setDanmakuBlockModes(
                            toggleMode(settings.danmakuBlockModes, DANMAKU_MODE_BOTTOM, on),
                        )
                    },
                )

                KeywordBlockRow(
                    keywords = settings.danmakuBlockKeywords,
                    editing = picker == SettingsPickerId.Keywords,
                    onToggleEdit = {
                        picker = if (picker == SettingsPickerId.Keywords) null
                        else SettingsPickerId.Keywords
                    },
                    onSave = { words ->
                        viewModel.setDanmakuBlockKeywords(words)
                        picker = null
                    },
                )
            }

            // ================= 外观 =================
            SectionHeader("外观")

            InlinePicker(
                label = "主题",
                currentLabel = themeLabel(settings.themeMode),
                options = THEME_MODES.map { m ->
                    PickerOption(key = m, label = themeLabel(m))
                },
                selectedKey = settings.themeMode,
                expanded = picker == SettingsPickerId.Theme,
                onToggle = {
                    picker = if (picker == SettingsPickerId.Theme) null
                    else SettingsPickerId.Theme
                },
                onSelect = {
                    viewModel.setThemeMode(it)
                    picker = null
                },
            )

            // ================= 隐私 =================
            SectionHeader("隐私")

            SwitchRow(
                title = "保存观看历史",
                subtitle = "关闭后本机不再记录，已产生的记录需在 B 站端清理",
                checked = settings.saveHistory,
                onCheckedChange = viewModel::setSaveHistory,
            )

            SwitchRow(
                title = "个性化推荐",
                subtitle = "关闭后首页推荐将不基于观看偏好",
                checked = settings.personalizedRecommend,
                onCheckedChange = viewModel::setPersonalizedRecommend,
            )

            SwitchRow(
                title = "凭据加密存储",
                subtitle = "SESSDATA 经 Android Keystore 加密，不可关闭",
                checked = true,
                enabled = false,
                onCheckedChange = { },
            )

            // ================= 通知 =================
            SectionHeader("通知")

            SwitchRow(
                title = "回复我的",
                subtitle = "关闭后首页铃铛不再因「回复我的」未读而亮红点",
                checked = settings.notifyReply,
                onCheckedChange = viewModel::setNotifyReply,
            )

            // ================= 空降助手 =================
            //
            // 数据来自第三方 bsbsb.top（社区标注的可跳过片段）。
            // 页面上**明确标注来源**（AGENTS.md §4.3 的合规要求）。
            SectionHeader("空降助手（第三方数据）")

            SwitchRow(
                title = "自动跳过赞助片段",
                subtitle = "数据来自 bsbsb.top 社区标注；默认关闭",
                checked = settings.sponsorBlockEnabled,
                onCheckedChange = viewModel::setSponsorBlockEnabled,
            )

            // 总开关关着时，下面的细项无意义 —— 不渲染（而不是灰掉，
            // 灰掉会让人以为"点了没反应"）
            if (settings.sponsorBlockEnabled) {
                CategoryPicker(
                    selected = settings.sponsorBlockCategories,
                    onToggle = { cat ->
                        val next = if (cat in settings.sponsorBlockCategories) {
                            settings.sponsorBlockCategories - cat
                        } else {
                            settings.sponsorBlockCategories + cat
                        }
                        viewModel.setSponsorBlockCategories(next)
                    },
                )

                SwitchRow(
                    title = "显示跳过提示",
                    subtitle = "关闭后静默跳过，不显示「已跳过」提示条",
                    checked = settings.sponsorBlockShowToast,
                    onCheckedChange = viewModel::setSponsorBlockShowToast,
                )

                SwitchRow(
                    title = "允许撤销",
                    subtitle = "社区标注可能出错；开启后提示条可点「撤销」退回",
                    checked = settings.sponsorBlockAllowUndo,
                    onCheckedChange = viewModel::setSponsorBlockAllowUndo,
                )

                InfoRow(label = "数据来源", value = "bsbsb.top（非官方接口）")
            }

            // ================= 存储 =================
            SectionHeader("存储")

            InfoRow(label = "图片缓存", value = cacheLabel)
            ActionRow(
                label = "清理图片缓存",
                subtitle = "清除已下载的封面与头像，下次进入页面会重新加载",
                onClick = onClearImageCache,
            )

            // ================= 关于 =================
            SectionHeader("关于")

            // ⚠️ 版本号必须从构建配置读。硬编码 "0.6.1" 时
            // versionName 已经是 0.6.4 —— 这就是「反模式 #3」的实例。
            InfoRow(label = "版本", value = com.example.biliv3.BuildConfig.VERSION_NAME)
            InfoRow(label = "构建号", value = com.example.biliv3.BuildConfig.VERSION_CODE.toString())
            InfoRow(label = "关于", value = "第三方 B 站客户端（个人自用）")
            InfoRow(label = "合规", value = "不解析大会员 / 付费内容")
        }
    }
}

/**
 * 当前展开的选择器标识。
 *
 * 行内展开后，面板就渲染在选择框正下方、能直接读到本地数据，
 * 所以状态只需回答"哪个展开了" —— 枚举足够。
 */
private enum class SettingsPickerId {
    Speed,
    Quality,
    Alpha,
    Font,
    Area,
    Keywords,
    Theme,
}

// ---------------------------------------------------------------------------
// 组件
// ---------------------------------------------------------------------------

/**
 * 空降助手的类别多选。
 *
 * ## 为什么用"可点标签"而不是 Switch 列表
 *
 * 类别有 7 个，每个都做成一行 Switch 会让设置页非常长（一屏放不下），
 * 而且用户改的通常只有一两个。标签形式一行能放 3~4 个，一眼看全。
 *
 * ## 选中态不只用颜色
 *
 * 选中的标签是**品牌粉底 + 白字**，未选中是**弱灰底 + 次文字色** ——
 * 同时改变了底色与文字色两个维度，色盲用户也能区分。
 */
@Composable
private fun CategoryPicker(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = BiliTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x4, vertical = Space.x2),
    ) {
        Text(
            text = "跳过哪些内容",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textTertiary,
            ),
        )
        Spacer(Modifier.height(Space.x2))

        // 手工分行：用 FlowRow 需要 experimental API，而这里只有 7 项、
        // 每行 3 个足够稳定（文字长度可控）。
        val cats = com.example.biliv3.data.SkipSegment.SELECTABLE
        cats.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
                modifier = Modifier.padding(bottom = Space.x2),
            ) {
                row.forEach { cat ->
                    val on = cat in selected
                    Text(
                        text = com.example.biliv3.data.SkipSegment.LABELS[cat] ?: cat,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = FontSize.label,
                            color = if (on) colors.textOnBrand else colors.textSecondarySafe,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(if (on) colors.brandPrimary else colors.bgHover)
                            .clickable { onToggle(cat) }
                            .padding(horizontal = Space.x3, vertical = Space.x1 + 2.dp),
                    )
                }
            }
        }
    }
}

/** 分组标题。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = FontSize.label,
            fontWeight = FontWeight.SemiBold,
            color = BiliTheme.colors.textTertiary,
        ),
        modifier = Modifier.padding(
            start = Space.x4,
            end = Space.x4,
            top = Space.x5,
            bottom = Space.x2,
        ),
    )
}

/**
 * 开关行。
 *
 * 整行可点（不只点 Switch）—— 触摸目标从 52dp 的 Switch 扩大到整行，
 * 与 Material 的 settings 列表规范一致。
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // C 方案：每个设置项是独立卡片（与 InlinePicker 的卡片外观对齐）
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = if (enabled) colors.textPrimary else colors.textTertiary,
                ),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
        }
        Spacer(Modifier.width(Space.x3))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.textOnBrand,
                checkedTrackColor = colors.brandPrimary,
            ),
        )
    }
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bgCard)
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = FontSize.body,
                color = colors.textPrimary,
            ),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = FontSize.label,
                color = colors.textSecondarySafe,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.x3),
        )
    }
}

/** 显示区域档位的中文标签。与播放器弹层一致。 */
private fun areaLabel(ratio: Float): String = when {
    ratio <= 0.3f -> "1/4 屏"
    ratio <= 0.55f -> "半屏"
    ratio <= 0.8f -> "3/4 屏"
    else -> "全屏"
}

/** 主题模式的中文标签。 */
private fun themeLabel(mode: com.example.biliv3.data.ThemeMode): String = when (mode) {
    com.example.biliv3.data.ThemeMode.System -> "跟随系统"
    com.example.biliv3.data.ThemeMode.Dark -> "深色"
    com.example.biliv3.data.ThemeMode.Light -> "浅色"
}

/** 在集合里加/减一个弹幕类型。 */
private fun toggleMode(current: Set<Int>, mode: Int, on: Boolean): Set<Int> =
    if (on) current + mode else current - mode

/**
 * 弹幕屏蔽关键词行。
 *
 * ## 交互
 *
 * 点行展开输入框（行内展开，与其它设置项一致，不弹 Dialog ——
 * Dialog 独立 window 收不到 IME inset，键盘会盖住输入框，
 * 这是本项目踩过的坑，见 `CommentInputSheet` 的说明）。
 *
 * ## 为什么用逗号/空格/换行任意分隔
 *
 * 手机输入法打逗号方便，但用户也可能直接空格分隔。
 * 三种都接受，解析时统一切分，不做格式校验报错。
 */
@Composable
private fun KeywordBlockRow(
    keywords: List<String>,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val colors = BiliTheme.colors
    var draft by remember(keywords) { mutableStateOf(keywords.joinToString(" ")) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.x3, vertical = Space.x1)
                .biliCard(shape = RoundedCornerShape(Radius.card))
                .clickable(onClick = onToggleEdit)
                .padding(horizontal = Space.x4, vertical = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "屏蔽关键词",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (keywords.isEmpty()) {
                        "点此添加（空格或逗号分隔多个）"
                    } else {
                        keywords.joinToString("、")
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Space.x2))
            Text(
                text = if (editing) "收起" else "编辑",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = FontSize.label,
                    color = colors.textBrandSafe,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.x3, vertical = Space.x1)
                    .biliCard(shape = RoundedCornerShape(Radius.card))
                    .padding(horizontal = Space.x4, vertical = Space.x3),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = FontSize.body,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brandPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.x3))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    BrandButton(
                        label = "保存",
                        onClick = {
                            onSave(
                                draft.split(' ', ',', '，', '、', '\n')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .distinct(),
                            )
                        },
                        variant = BrandButtonVariant.Filled,
                    )
                }
            }
        }
    }
}

/** 通用「动作行」：标题 + 副标题 + 右侧箭头，点击执行动作。 */
@Composable
private fun ActionRow(
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val colors = BiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .biliCard(shape = RoundedCornerShape(Radius.card))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.x4, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = FontSize.body,
                    color = colors.textPrimary,
                ),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = FontSize.label,
                        color = colors.textSecondarySafe,
                    ),
                )
            }
        }
    }
}

/** `1.0` → `1`，`1.25` → `1.25`。 */
private fun formatSpeedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

// ---------------------------------------------------------------------------
// 档位常量
// ---------------------------------------------------------------------------

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private val DANMAKU_ALPHA_OPTIONS = listOf(0.3f, 0.5f, 0.7f, 0.9f, 1.0f)

private val DANMAKU_FONT_OPTIONS = listOf(0.7f, 0.85f, 1.0f, 1.2f, 1.5f)

private val DANMAKU_AREA_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f)

/**
 * 清晰度档位。
 *
 * 数字是 B 站的 `quality` 码：
 * 16=360P, 32=480P, 64=720P, 80=1080P, 0=自动（取最高可用）。
 */
private val QUALITY_OPTIONS = listOf(
    0 to "自动",
    32 to "480P",
    64 to "720P",
    80 to "1080P",
)

/** 主题模式三选一。 */
private val THEME_MODES = listOf(
    com.example.biliv3.data.ThemeMode.System,
    com.example.biliv3.data.ThemeMode.Dark,
    com.example.biliv3.data.ThemeMode.Light,
)

/** 弹幕类型码（与 B 站 `mode` 字段一致）。 */
private const val DANMAKU_MODE_SCROLL = 1
private const val DANMAKU_MODE_BOTTOM = 4
private const val DANMAKU_MODE_TOP = 5
