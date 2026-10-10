package com.example.biliv3.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.biliv3.data.SpeedTiers
import com.example.biliv3.design.RuleLine
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Type
import com.example.biliv3.design.v3.V3SectionTitle
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.InlinePicker
import com.example.biliv3.ui.component.PickerOption
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.design.v3.V3SwitchRow

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
    /**
     * 从剪贴板读 cookie 文本并导入（开发者工具）。
     *
     * ⚠️ 剪贴板读取必须在**调用方**做 —— 设置页不碰 `Context`，
     * 而且剪贴板内容属于敏感数据，读取与提示风险应由一处统一负责。
     */
    onImportCookieFromClipboard: () -> Unit = {},
    /**
     * 导出 cookie 到用户选择的文件（开发者工具）。
     *
     * 由调用方弹 `CreateDocument` 选择器并写文件；设置页只触发。
     */
    onExportCookie: () -> Unit = {},
    /** 复制当前 cookie 到剪贴板。 */
    onCopyCookie: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val colors = BiliV3.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cookieState by viewModel.cookie.collectAsStateWithLifecycle()

    // 清除凭据是破坏性操作，必须二次确认
    var showClearCookieConfirm by remember { mutableStateOf(false) }

    // AI 配置：编辑态 + 清空确认（与 Cookie 同一套交互约定）
    var aiEditing by remember { mutableStateOf(false) }
    var showClearAiConfirm by remember { mutableStateOf(false) }
    val aiConfig by viewModel.aiConfig.collectAsStateWithLifecycle()

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
            .background(colors.bgPrimary)
            // ⚠️ 点页面空白处收起已展开的选择器。
            // 子控件（选项行）自己消费点击，不会误触。
            .clickable { picker = null },
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不再是卡片：与页面同明度，只靠底边一条发丝线分隔
                .ruleBottom(color = Rule.color)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(V3Size.topBar)
                .padding(horizontal = V3Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.labelPrimary,
                    modifier = Modifier.size(V3Size.iconLg),
                )
            }
            Spacer(Modifier.width(V3Space.xxs))
            Text(
                text = "设置",
                style = V3Type.subheadline.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = V3Space.xxl),
        ) {
            // ================= 播放 =================
            V3SectionTitle(
                title = "播放",
                topSpace = V3Space.xl,
            )

            InlinePicker(
                label = "默认倍速",
                // ⚠️ 档位来自共享的 `SpeedTiers`（v1.6.5）——
                // 与播放器弹层、直播读同一个定义，不再各写一份
                currentLabel = "${SpeedTiers.label(settings.defaultSpeed)}×",
                options = SpeedTiers.ALL.map {
                    PickerOption(
                        key = it.value,
                        label = "${SpeedTiers.label(it.value)}×",
                        description = if (it.verified) null else "可能音频失真",
                    )
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

            // ---- 退出后自动小窗（v1.4.2 新增）----
            //
            // 默认关闭：这是新增能力，默认值必须保持改动前的行为，
            // 否则升级会带来"按 Home 键突然多出一个小窗"的意外。
            SwitchRow(
                title = "退出后自动小窗",
                subtitle = "按 Home 键离开时自动进入画中画继续播放；" +
                    "听视频（纯音频）模式不会弹出画面",
                checked = settings.autoPip,
                onCheckedChange = viewModel::setAutoPip,
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
            V3SectionTitle(
                title = "弹幕",
                topSpace = V3Space.xxl,
            )

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
                V3SectionTitle(
                title = "弹幕屏蔽（本地生效）",
                    topSpace = V3Space.xxl,
                )

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
            //
            // 🔴 原「主题」三选一（跟随系统 / 深色 / 浅色）已移除（v1.1.3）。
            //
            // 原因：浅色主题下的"玻璃"在静态页上是假的 ——
            // 静态页背后是纯色底，没有东西可模糊。
            // 详见 `design/BiliTheme.kt` 的 KDoc。
            //
            // ⚠️ 该分组**已无任何设置项**，故连标题一并删掉 ——
            // 留一个下面空无一物的章节标记比没有更糟（§1.6 死入口）。

            // ================= 隐私 =================
            V3SectionTitle(
                title = "隐私",
                topSpace = V3Space.xxl,
            )

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
            V3SectionTitle(
                title = "通知",
                topSpace = V3Space.xxl,
            )

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
            V3SectionTitle(
                title = "空降助手（第三方数据）",
                topSpace = V3Space.xxl,
            )

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

            // ================= 直播 =================
            //
            // 直播已在 v1.6.3 改为**应用内播放**，所以"隐身入场"的
            // 语义是完整无歧义的：本应用进入直播间只有这一个入口。
            V3SectionTitle(
                title = "直播",
                topSpace = V3Space.xxl,
            )

            SwitchRow(
                title = "隐身入场",
                subtitle = "开启后进入直播间不发送入场上报（不影响观看）",
                checked = settings.liveIncognito,
                onCheckedChange = viewModel::setLiveIncognito,
            )

            // 开启时补一句说明：让用户知道它**具体**做了什么、没做什么。
            // 不写清楚的话，用户无法判断这个开关到底有没有生效。
            if (settings.liveIncognito) {
                InfoRow(label = "当前状态", value = "本应用不发入场上报")
            }

            // ================= 存储 =================
            V3SectionTitle(
                title = "存储",
                topSpace = V3Space.xxl,
            )

            InfoRow(label = "图片缓存", value = cacheLabel)


            ActionRow(
                label = "清理图片缓存",
                subtitle = "清除已下载的封面与头像，下次进入页面会重新加载",
                onClick = onClearImageCache,
            )

            // ================= 第三方 AI（AI 总结用）=================
            //
            // ## 为什么单独一节而不是塞进"播放"
            //
            // 它是**外部服务凭据**（会产生费用），与本地偏好性质不同，
            // 需要用户明确看到"我在配置什么、数据发给谁"。
            //
            // ## 🔴 Key 的安全约定（页面上必须体现）
            //
            // - 输入框**不回显已存的 Key**（只显示脱敏摘要）
            // - 保存后只显示 `sk-a…(48)` 这种前缀 + 长度
            // - 清空是显式动作（不是"留空即清空"）
            V3SectionTitle(
                title = "第三方 AI（AI 总结）",
                topSpace = V3Space.xxl,
            )

            AiConfigSection(
                config = aiConfig,
                editing = aiEditing,
                onToggleEdit = { aiEditing = !aiEditing },
                onSave = { base, model, key ->
                    viewModel.saveAiConfig(base, model, key)
                    aiEditing = false
                },
                onClear = { showClearAiConfirm = true },
            )

            if (showClearAiConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearAiConfirm = false },
                    title = { Text("清空 AI 配置？") },
                    text = {
                        Text(
                            "将删除本机保存的 API 地址、模型名与 API Key。" +
                                "AI 总结会退回「仅 B 站官方」这一条路径。",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearAiConfirm = false
                                viewModel.clearAiConfig()
                            },
                        ) { Text("清空") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearAiConfirm = false }) {
                            Text("取消")
                        }
                    },
                )
            }

            // ================= 烂梗库 =================
            //
            // ## 为什么只在这里说明来源
            //
            // 内容是**本项目整理**的常用直播用语。写清楚来源，
            // 避免被误认为是 B 站官方内容。
            V3SectionTitle(
                title = "烂梗库",
                topSpace = V3Space.xxl,
            )

            InfoRow(
                label = "内置库",
                value = "${com.example.biliv3.data.meme.MemeLibrary.BUILT_IN.size} 条（本项目整理）",
            )
            Text(
                text = "内置内容为本项目整理的常用直播用语，不是 B 站官方内容。" +
                    "可在直播间的聊天输入框左侧打开，支持搜索、复制与一键填入。",
                style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                    color = colors.labelTertiary,
                ),
                modifier = Modifier.padding(
                    start = V3Space.md,
                    end = V3Space.md,
                    top = V3Space.xxs,
                ),
            )

            // ================= 自动画质 / 音质（未发版）=================
            //
            // 移植自 `AHCorn/Bilibili-Auto-Quality`（greasyfork 486151）。
            // 放在「烂梗库」与「开发者工具」之间：它是**播放向**的功能，
            // 但不该挤在播放区 —— 那里的 4 项是"每次都改"的，
            // 这里的是"设一次就不动"的。
            V3SectionTitle(
                title = "自动画质 / 音质",
                topSpace = V3Space.xxl,
            )

            AutoQualitySection(
                aq = settings.autoQuality,
                onEnabled = viewModel::setAutoQualityEnabled,
                onPreferred = viewModel::setAutoQualityPreferred,
                onFallback = viewModel::setAutoQualityFallback,
                onDolby = viewModel::setAutoQualityDolby,
                onFlac = viewModel::setAutoQualityFlac,
                onLive = viewModel::setAutoQualityLive,
                onUnlock = viewModel::setAutoQualityUnlock,
            )

            // ================= 开发者工具 · Cookie 管理 =================
            //
            // 为什么放在「存储」与「关于」之间：它是**开发者向**的功能，
            // 不该出现在普通用户视线中心（播放 / 弹幕那些区）。
            //
            // ⚠️ 整块只显示**字段名与长度**，永不显示值 —— 见 CookieState 的注释。
            V3SectionTitle(
                title = "开发者工具 · Cookie",
                topSpace = V3Space.xxl,
            )

            CookieSection(
                state = cookieState,
                onImportFromClipboard = onImportCookieFromClipboard,
                onExport = onExportCookie,
                onCopy = onCopyCookie,
                onValidate = { viewModel.refreshCookieState("已重新读取本地凭据") },
                onClear = { showClearCookieConfirm = true },
            )

            if (showClearCookieConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearCookieConfirm = false },
                    title = { Text("清除登录凭据？") },
                    text = {
                        Text(
                            "将删除本地保存的 Cookie，所有依赖登录的功能" +
                                "（收藏、评论、私信、历史同步）会退回未登录状态。\n\n" +
                                "设备指纹会保留 —— 清掉会让下次请求看起来像全新设备，" +
                                "反而更容易触发风控。",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearCookieConfirm = false
                                viewModel.clearCookie()
                            },
                        ) { Text("清除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearCookieConfirm = false }) {
                            Text("取消")
                        }
                    },
                )
            }

            // ================= 关于 =================
            V3SectionTitle(
                title = "关于",
                topSpace = V3Space.xxl,
            )

            // ⚠️ 版本号必须从构建配置读。硬编码 "0.6.1" 时
            // versionName 已经是 0.6.4 —— 这就是「反模式 #3」的实例。
            //
            // 这一组是**唯一保留发丝线**的地方：四行"标签 + 值"完全同构
            // （同样的字号、同样的行高、同样的右对齐值），去掉线后整块
            // 会糊成一片、看不出是四条独立信息。
            //
            // 其余分组已改为纯留白分组（§5.1 硬规则 4：优先间距，线是兜底）。
            InfoRow(label = "版本", value = com.example.biliv3.BuildConfig.VERSION_NAME)
            RuleLine(color = Rule.subtle)
            InfoRow(label = "构建号", value = com.example.biliv3.BuildConfig.VERSION_CODE.toString())
            RuleLine(color = Rule.subtle)
            InfoRow(label = "关于", value = "第三方 B 站客户端（个人自用）")
            RuleLine(color = Rule.subtle)
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
    val colors = BiliV3.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = V3Space.md, vertical = V3Space.xs),
    ) {
        Text(
            text = "跳过哪些内容",
            style = V3Type.caption1.copy(
                color = colors.labelTertiary,
            ),
        )
        Spacer(Modifier.height(V3Space.xs))

        // 手工分行：用 FlowRow 需要 experimental API，而这里只有 7 项、
        // 每行 3 个足够稳定（文字长度可控）。
        val cats = com.example.biliv3.data.SkipSegment.SELECTABLE
        cats.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
                modifier = Modifier.padding(bottom = V3Space.xs),
            ) {
                row.forEach { cat ->
                    val on = cat in selected
                    Text(
                        text = com.example.biliv3.data.SkipSegment.LABELS[cat] ?: cat,
                        style = V3Type.caption1.copy(
                            color = if (on) colors.labelOnBrand else colors.labelSecondary,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(V3Radius.pill))
                            .background(if (on) colors.brand else colors.bgTertiary)
                            .clickable { onToggle(cat) }
                            .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
                    )
                }
            }
        }
    }
}

/**
 * 自动画质 / 音质（未发版）。
 *
 * 移植自 `AHCorn/Bilibili-Auto-Quality` 的「设置音质和画质」面板。
 *
 * ## 与原面板的对应关系
 *
 * | 原脚本 | 这里 |
 * |---|---|
 * | 首选画质 | [InlinePicker]「首选画质」 |
 * | 备选画质 | [InlinePicker]「备选画质」 |
 * | 自动音质 | 「自动选最高音质」（本项目的默认行为） |
 * | 杜比全景声 | [SwitchRow] |
 * | Hi-Res 音质 | [SwitchRow] |
 * | 解锁设置面板 | 「解锁设置」子块（8K / 杜比视界 / 无损 / AV1） |
 *
 * ## 🔴 必须如实写清的两件事
 *
 * 1. **这些开关只是"请求"，不是"解锁"** —— 非会员账号带上全部请求位，
 *    服务端照样只给 480P + 64k AAC（本项目已实测，见坑 172）。
 *    所以文案写的是"向服务端请求"，而不是"开启后即可使用"。
 * 2. **首选/备选是"在你能拿到的档位里挑"** —— 不是"强制切到该档"。
 *    服务端没给的档位选了也拿不到，所以列表里会标出「需要大会员」。
 *
 * 这两条都是原脚本 README 自己声明的立场（"不是让非会员用户使用会员选项"），
 * 本项目的实现与之保持一致。
 */
@Composable
private fun AutoQualitySection(
    aq: com.example.biliv3.data.quality.AutoQualitySettings,
    onEnabled: (Boolean) -> Unit,
    onPreferred: (Int) -> Unit,
    onFallback: (Int) -> Unit,
    onDolby: (Boolean) -> Unit,
    onFlac: (Boolean) -> Unit,
    onLive: (Boolean) -> Unit,
    /** 解锁开关：8K / 杜比视界 / AV1（杜比音频与无损位被服务端拒，故无开关）。 */
    onUnlock: (Boolean, Boolean, Boolean) -> Unit,
) {
    val colors = BiliV3.colors

    // 三个子选择器共用一个"当前打开哪个"（与页面其它选择器同一套约定）
    var aqPicker by remember { mutableStateOf<Int?>(null) }

    SwitchRow(
        title = "自动选择最高画质",
        subtitle = "按账号实际能拿到的档位自动选最高的；" +
            "会员专属档位不会发出去（发了也只会被降档）",
        checked = aq.enabled,
        onCheckedChange = onEnabled,
    )

    // ⚠️ 未开启时**明确说明"现在是什么行为"**，而不是只把细项藏起来。
    //    否则用户会以为"没开关就是没这个功能"，而实际上
    //    "取最高码率音轨"这条一直在生效（它是修 bug，不是新功能）。
    if (!aq.enabled) {
        Text(
            text = "关闭时按服务端默认档取流（fnval=16，改动前的行为）。" +
                "音质始终取最高码率音轨。",
            style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(
                start = V3Space.md,
                end = V3Space.md,
                top = V3Space.xxs,
            ),
        )
    }

    // 关掉总开关后细项无意义 —— 不渲染（同弹幕区的做法，避免"改了没反应"）
    if (aq.enabled) {
        InlinePicker(
            label = "首选画质",
            currentLabel = com.example.biliv3.data.quality.QualityNames.label(aq.preferredQn),
            options = QUALITY_CANDIDATES.map { (q, label) ->
                PickerOption(key = q, label = label)
            },
            selectedKey = aq.preferredQn,
            expanded = aqPicker == AQ_PICKER_PREFERRED,
            onToggle = {
                aqPicker = if (aqPicker == AQ_PICKER_PREFERRED) null else AQ_PICKER_PREFERRED
            },
            onSelect = {
                onPreferred(it)
                aqPicker = null
            },
        )

        InlinePicker(
            label = "备选画质",
            currentLabel = com.example.biliv3.data.quality.QualityNames.label(aq.fallbackQn),
            options = QUALITY_CANDIDATES.map { (q, label) ->
                PickerOption(key = q, label = label)
            },
            selectedKey = aq.fallbackQn,
            expanded = aqPicker == AQ_PICKER_FALLBACK,
            onToggle = {
                aqPicker = if (aqPicker == AQ_PICKER_FALLBACK) null else AQ_PICKER_FALLBACK
            },
            onSelect = {
                onFallback(it)
                aqPicker = null
            },
        )

        // ---- 音质 ----
        //
        // 「自动选最高音质」在本项目**没有开关**：它是修过的 bug
        // （旧实现取 audio[0] = 64k，见 AutoQuality.pickAudioStream），
        // 不是一个可选行为。所以这里只显示当前行为，不做成开关。
        InfoRow(label = "普通音质", value = "自动取最高码率（192k）")

        SwitchRow(
            title = "优先杜比全景声",
            subtitle = "服务端返回了杜比音轨（30250）时优先用它；" +
                "没返回就用普通 AAC —— 注意：杜比**不能主动请求**，见下方说明",
            checked = aq.dolbyAtmos,
            onCheckedChange = onDolby,
        )

        SwitchRow(
            title = "优先 Hi-Res 无损音质",
            subtitle = "服务端返回了无损音轨（30251）时优先用它；" +
                "没返回就用普通 AAC —— 同样不能主动请求",
            checked = aq.hiResAudio,
            onCheckedChange = onFlac,
        )

        SwitchRow(
            title = "直播自动最高画质",
            subtitle = "直播间请求原画（qn=10000），服务端按账号权限给到能给的最高档",
            checked = aq.liveAutoQuality,
            onCheckedChange = onLive,
        )

        // ---- 解锁设置（对应原脚本的「解锁设置」面板）----
        Spacer(Modifier.height(V3Space.xs))
        Text(
            text = "解锁设置",
            style = V3Type.caption1.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(horizontal = V3Space.md),
        )
        Text(
            text = "这些是**请求参数**（原脚本改 localStorage，本项目改 fnval 位）。" +
                "非会员账号带上它们，服务端仍然只给 480P —— " +
                "能不能拿到由账号权限决定，不是由开关决定。\n\n" +
                "⚠️ 杜比全景声与 Hi-Res 无损**没有开关**：它们的 fnval 位（32 / 4096）" +
                "实测会让整条取流请求返回 -400 请求错误 —— " +
                "带上不是「没有提升」，而是**整个视频都放不了**。" +
                "所以那两项只做「服务端给了就用」，不做「主动索要」。",
            style = V3Type.caption2.copy(lineHeight = V3Type.caption1.lineHeight,
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(
                start = V3Space.md,
                end = V3Space.md,
                top = V3Space.xxs,
                bottom = V3Space.xxs,
            ),
        )

        SwitchRow(
            title = "请求 8K",
            subtitle = "fnval 位 128",
            checked = aq.unlock.unlock8K,
            onCheckedChange = { v ->
                onUnlock(v, aq.unlock.unlockDolbyVision, aq.unlock.unlockAv1)
            },
        )

        SwitchRow(
            title = "请求杜比视界",
            subtitle = "fnval 位 64",
            checked = aq.unlock.unlockDolbyVision,
            onCheckedChange = { v ->
                onUnlock(aq.unlock.unlock8K, v, aq.unlock.unlockAv1)
            },
        )

        SwitchRow(
            title = "请求 AV1 编码",
            subtitle = "fnval 位 256 + 2048；部分设备无 AV1 硬解",
            checked = aq.unlock.unlockAv1,
            onCheckedChange = { v ->
                onUnlock(aq.unlock.unlock8K, aq.unlock.unlockDolbyVision, v)
            },
        )

        // 当前实际发出的 fnval —— 让用户能核对"我开的开关真的进了请求"
        InfoRow(label = "当前 fnval", value = aq.fnval().toString())
    }
}

/** 自动画质区两个选择器的 id（与页面主 picker 分开，避免互相干扰）。 */
private const val AQ_PICKER_PREFERRED = 101
private const val AQ_PICKER_FALLBACK = 102

/**
 * 首选 / 备选画质的候选档位。
 *
 * ⚠️ 比「默认清晰度」的 `QUALITY_OPTIONS` 多了 1080P+ / 60帧 / 4K / 8K ——
 * 那些是**会员档**，用户是会员时应该能直接指定它们。
 * 非会员选了也不会生效（服务端不给），但列表里保留：
 * 隐藏会让会员用户以为"没有这个选项"。
 */
private val QUALITY_CANDIDATES: List<Pair<Int, String>> =
    listOf(0 to "自动（最高可用）") +
        com.example.biliv3.data.quality.QualityNames.SELECTABLE

/**
 * 开关行。
 *
 * ## 🔴 v3：本函数已改为**委托**给设计系统的 [V3SwitchRow]
 *
 * 原先这里是页面内自己实现的一行开关（自己管 padding、字号、Switch 配色）。
 * 而 `design/v3/V3Kit.kt` 里**已经有一个** `V3SwitchRow` 做同一件事 ——
 * 于是同一个 App 里存在**两套开关行**：
 *
 * | | 本页（旧） | `V3SwitchRow` |
 * |---|---|---|
 * | 字号 | `MaterialTheme.typography.bodyMedium.copy(fontSize = …)` | `V3Type.body` / `V3Type.footnote` |
 * | Switch 配色 | 只给 checked 两色 | checked + unchecked + border 全给 |
 * | 缩放 | 无 | `scale(0.86f)`（收到 iOS 比例）|
 * | 分隔线 | 无 | 有 `showSeparator` |
 *
 * ⚠️ 这是「每条规则只有一个家」的反例 —— 两套实现的**漂移是必然的**
 * （本项目已因同类问题出过「设置页 0.6.1 而 versionName 0.6.4」）。
 *
 * 现在保留这个薄包装只是为了不动 13 处调用点，**实现只有一处**。
 * 新代码请直接用 `V3SwitchRow`。
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    V3SwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        // 设置页的行之间**不画分隔线**：分组靠标题 + 留白
        // （§5.1「分组靠间距，线是兜底手段」）
        showSeparator = false,
    )
}

/** 只读信息行。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与开关行统一：无底色、无框，靠发丝线分隔
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = V3Type.callout.copy(
                color = colors.labelPrimary,
            ),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = V3Type.caption1.copy(
                color = colors.labelSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = V3Space.sm),
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
    val colors = BiliV3.colors
    var draft by remember(keywords) { mutableStateOf(keywords.joinToString(" ")) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 设置项不再是卡片（同「列表用留白分组」原则）
                .clickable(onClick = onToggleEdit)
                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "屏蔽关键词",
                    style = V3Type.callout.copy(
                        color = colors.labelPrimary,
                    ),
                )
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = if (keywords.isEmpty()) {
                        "点此添加（空格或逗号分隔多个）"
                    } else {
                        keywords.joinToString("、")
                    },
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(V3Space.xs))
            Text(
                text = if (editing) "收起" else "编辑",
                style = V3Type.caption1.copy(
                    color = colors.brandBiliText,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ 关键词编辑区不再是卡片 —— 同「列表用留白分组」原则
                    .padding(horizontal = V3Space.md, vertical = V3Space.sm),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    textStyle = V3Type.callout.copy(
                        color = colors.labelPrimary,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(V3Space.sm))
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

/**
 * 第三方 AI 配置区块（v1.6.3）。
 *
 * ## 🔴 Key 的三条显示约定（与 Cookie 区块同源）
 *
 * 1. **不回显**：已保存的 Key 只显示脱敏摘要（`sk-a…(48)`），
 *    输入框留空时**不覆盖**已存值
 * 2. **不硬编码**：本项目不内置任何 Key —— 没配置就明确显示"未配置"
 * 3. **不进日志**：这里只读写 UI 状态，不发请求、不打日志
 *
 * ## 为什么地址与模型也要用户填
 *
 * 需求要求"支持自定义 API 地址与模型、优先兼容 OpenAI 风格"。
 * 写死某一家（如只支持 OpenAI）会把 Prompt 与那个厂商绑死 ——
 * 需求明确禁止。所以三项全开放。
 */
@Composable
private fun AiConfigSection(
    config: com.example.biliv3.data.ai.AiConfig,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onSave: (baseUrl: String, model: String, apiKey: String?) -> Unit,
    onClear: () -> Unit,
) {
    val colors = BiliV3.colors

    // 草稿：编辑态才用；初始值取自当前配置（Key 留空 = 不改）
    var draftBase by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var draftModel by remember(config.model) { mutableStateOf(config.model) }
    var draftKey by remember { mutableStateOf("") }

    // ---- 状态行 ----
    InfoRow(
        label = "状态",
        value = if (config.usable) "已配置" else "未配置",
    )
    InfoRow(
        label = "API 地址",
        value = config.baseUrl.ifEmpty { "—" },
    )
    InfoRow(
        label = "模型",
        value = config.model.ifEmpty { "—" },
    )
    // ⚠️ 只显示脱敏值。**任何情况下都不显示完整 Key。**
    InfoRow(
        label = "API Key",
        value = config.maskedKey.ifEmpty { "未设置" },
    )

    if (!config.usable && !editing) {
        Text(
            text = config.missingHint +
                "（未配置时 AI 总结只能走 B 站官方接口）",
            style = V3Type.footnote.copy(
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
        )
    }

    ActionRow(
        label = if (editing) "收起编辑" else "编辑配置",
        subtitle = "支持任意 OpenAI 兼容接口（官方 / DeepSeek / 本地部署等）",
        onClick = onToggleEdit,
    )

    if (editing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
        ) {
            AiField(
                label = "API 地址",
                value = draftBase,
                placeholder = "https://api.example.com/v1",
                hint = "填到 /v1 为止，不要带 /chat/completions",
                onValueChange = { draftBase = it },
            )
            Spacer(Modifier.height(V3Space.sm))
            AiField(
                label = "模型名",
                value = draftModel,
                placeholder = "gpt-4o-mini",
                hint = "用服务商文档里的模型 ID",
                onValueChange = { draftModel = it },
            )
            Spacer(Modifier.height(V3Space.sm))
            AiField(
                label = "API Key",
                value = draftKey,
                placeholder = if (config.hasKey) "留空 = 不修改已保存的 Key" else "sk-…",
                hint = "加密保存在本机（Android Keystore），不会上传",
                onValueChange = { draftKey = it },
                // 用密码键盘，避免输入时被旁人看到
                secret = true,
            )
            Spacer(Modifier.height(V3Space.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BrandButton(
                    label = "保存",
                    onClick = {
                        // ⚠️ Key 留空 → 传 null（不改动），不是传空串（清空）
                        onSave(
                            draftBase,
                            draftModel,
                            draftKey.takeIf { it.isNotEmpty() },
                        )
                        draftKey = ""
                    },
                    variant = BrandButtonVariant.Filled,
                )
                if (config.hasKey || config.baseUrl.isNotEmpty()) {
                    BrandButton(
                        label = "清空配置",
                        onClick = onClear,
                        variant = BrandButtonVariant.Outline,
                    )
                }
            }
        }
    }
}

/**
 * AI 配置的输入行。
 *
 * ## 为什么用 `BasicTextField` 而不是 Material 的 `OutlinedTextField`
 *
 * 与设置页其它输入（弹幕关键词）保持一致：M3 的 TextField 自带
 * 一套容器与内边距，与"无卡片、靠留白分组"的语言冲突。
 */
@Composable
private fun AiField(
    label: String,
    value: String,
    placeholder: String,
    hint: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
) {
    val colors = BiliV3.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = V3Type.caption1.copy(
                color = colors.labelSecondary,
            ),
        )
        Spacer(Modifier.height(V3Space.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(V3Radius.xs))
                .background(colors.bgTertiary)
                .padding(horizontal = V3Space.sm, vertical = V3Space.xs),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = V3Type.footnote.copy(
                        color = colors.labelTertiary,
                    ),
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                // 密码键盘（不显示已输入字符）—— 旁人看不到
                visualTransformation = if (secret) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                textStyle = V3Type.footnote.copy(
                    color = colors.labelPrimary,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(V3Space.xxs))
        Text(
            text = hint,
            style = V3Type.caption2.copy(
                color = colors.labelTertiary,
            ),
        )
    }
}

/** 通用「动作行」：标题 + 副标题 + 右侧箭头，点击执行动作。 */
@Composable
private fun ActionRow(
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val colors = BiliV3.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 设置项不再是卡片（同「列表用留白分组」原则）
            .clickable(onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = V3Type.callout.copy(
                    color = colors.labelPrimary,
                ),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(V3Space.hairline))
                Text(
                    text = subtitle,
                    style = V3Type.caption1.copy(
                        color = colors.labelSecondary,
                    ),
                )
            }
        }
    }
}

/** 倍速文案已移到 `SpeedTiers.label`（v1.6.5）—— 三处共用一个实现。 */

// ---------------------------------------------------------------------------
// 开发者工具：Cookie 管理
// ---------------------------------------------------------------------------

/**
 * Cookie 导入 / 导出区块。
 *
 * ## 🔴 显示约定（安全红线 §4.3）
 *
 * **永不显示 cookie 的值** —— 连前缀都不给。`SESSDATA` 等价于账号密码，
 * 前缀本身就能缩小爆破范围。这里只显示：
 *
 * - 有哪些**字段名**
 * - 每个值**多长**
 * - 缺哪些**关键字段**
 *
 * 摘要文本由 `CookieCodec.maskedSummary` 生成（那条路径有单测保证不含真实值）。
 *
 * ## 为什么"验证登录状态"只是重新读本地
 *
 * 本地只能验证"`SESSDATA` 存在"，**不能**验证它是否还有效 ——
 * 那必须发一次真实请求（`nav` 接口）。这里刻意不做网络请求：
 * 设置页不该为了显示一个状态就偷偷发请求。真正的校验由"导入后
 * 重拉账号信息"完成（失败会体现在头像/昵称仍是空的）。
 */
@Composable
private fun CookieSection(
    state: SettingsViewModel.CookieState,
    onImportFromClipboard: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onValidate: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = BiliV3.colors

    // 状态行：已登录 / 未登录
    InfoRow(
        label = "登录状态",
        value = if (state.loggedIn) "已登录" else "未登录",
    )

    if (state.fieldNames.isNotEmpty()) {
        // 字段清单（只有名字，没有值）
        Text(
            text = "字段：${state.fieldNames.joinToString("、")}",
            style = V3Type.subheadline.copy(
                color = colors.labelSecondary,
            ),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
        )
        // 脱敏摘要：字段名 + 长度
        Text(
            text = state.masked,
            style = V3Type.subheadline.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = colors.labelTertiary,
            ),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xxs),
        )
    }

    if (state.missingEssentials.isNotEmpty() && state.fieldNames.isNotEmpty()) {
        Text(
            text = "缺少关键字段：${state.missingEssentials.joinToString("、")}" +
                "（缺 SESSDATA 一定不是登录态）",
            style = V3Type.subheadline.copy(color = colors.accentCoin),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
        )
    }

    // 操作结果提示（成功 / 失败共用一条，颜色区分）
    state.message?.let { msg ->
        Text(
            text = msg,
            style = V3Type.subheadline.copy(
                color = if (state.isError) colors.stateError else colors.stateSuccess,
            ),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
        )
    }

    ActionRow(
        label = "从剪贴板导入",
        subtitle = "支持 `Cookie:` 头、裸串、换行/& 分隔、JSON —— 导入会覆盖当前登录态",
        onClick = onImportFromClipboard,
    )

    ActionRow(
        label = "导出到文件",
        subtitle = "⚠️ 导出的文件含登录凭据，请勿上传或提交到仓库",
        onClick = onExport,
    )

    ActionRow(
        label = "复制 Cookie",
        subtitle = "复制到剪贴板（同样含凭据，注意别粘贴到公开场合）",
        onClick = onCopy,
    )

    ActionRow(
        label = "验证登录状态",
        subtitle = "重新读取本地凭据并检查关键字段是否齐全",
        onClick = onValidate,
    )

    ActionRow(
        label = "清除 Cookie",
        subtitle = "退出登录（保留设备指纹，避免下次请求像全新设备）",
        onClick = onClear,
    )

    if (state.fieldNames.isEmpty() && state.message == null) {
        Text(
            text = "还没有凭据。导入后即可用开发者账号调试接口。",
            style = V3Type.subheadline.copy(color = colors.labelTertiary),
            modifier = Modifier.padding(horizontal = V3Space.md, vertical = V3Space.xs),
        )
    }
}

// ---------------------------------------------------------------------------
// 档位常量
// ---------------------------------------------------------------------------

// ⚠️ 倍速档位已移到 `com.example.biliv3.data.SpeedTiers`（v1.6.5）——
//    这里刻意不保留副本，避免与播放器弹层/直播漂移。

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

/** 弹幕类型码（与 B 站 `mode` 字段一致）。 */
private const val DANMAKU_MODE_SCROLL = 1
private const val DANMAKU_MODE_BOTTOM = 4
private const val DANMAKU_MODE_TOP = 5