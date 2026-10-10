package com.example.biliv3.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.biliv3.design.BiliTheme
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.example.biliv3.data.danmaku.DanmakuItem
import kotlinx.coroutines.delay
import com.example.biliv3.design.v3.BiliV3

/**
 * 弹幕渲染层。
 *
 * ## 实现方式：自绘轨道分配 + 逐帧推进
 *
 * Media3 没有弹幕支持，必须自己画。核心是三件事：
 *
 * 1. **轨道分配**：把屏幕纵向切成若干"轨道"（lane），
 *    每条滚动弹幕占一条。同一轨道要等上一条完全移出才能放下一条，
 *    否则会重叠。
 * 2. **水平位移**：弹幕从右往左匀速移动，速度由"屏幕宽 + 自身宽 + 存活时长"决定。
 * 3. **逐帧刷新**：用 `withFrameNanos` 跟随 vsync，
 *    而不是固定 delay —— 固定 delay 会与屏幕刷新错拍、看起来一顿一顿。
 *
 * ## 为什么不用固定 delay 轮询
 *
 * 弹幕是**持续运动**的（不像字幕只在切换时变）。
 * 用 `delay(16)` 近似 60fps 会因为调度抖动累积误差，
 * 表现为弹幕忽快忽慢。`withFrameNanos` 直接拿 vsync 时间戳，位置由
 * `当前时间 - 出现时间` 算出，天然匀速且与刷新同步。
 *
 * ## 当前不支持
 *
 * - **高级弹幕（mode 7）/ 代码弹幕（mode 8）**：带定位脚本，需解析 JSON 后自绘，
 *   硬渲染会显示乱码，直接跳过。
 * - **弹幕防挡脸**：需要额外的 AI 接口（官方是服务端能力）。
 *
 * ## 屏蔽（已实现 · 纯本地）
 *
 * 官方 App 的屏蔽分三类：**类型 / 关键词 / 用户**。
 * 本项目做前两类，全部在客户端过滤，不发请求、不依赖额外接口：
 *
 * | 类别 | 参数 | 说明 |
 * |---|---|---|
 * | 类型 | [blockModes] | 1=滚动 4=底部 5=顶部，各自可关 |
 * | 关键词 | [blockKeywords] | 命中任一即不渲染（子串匹配） |
 *
 * ⚠️ 屏蔽必须在**投放前**做（见下面的 `cursor` 循环），
 * 而不是渲染时过滤 —— 后者会让被屏蔽的弹幕仍然**占用轨道**，
 * 表现为"屏幕上没几条弹幕，但它们之间空着很大间隔"。
 */
@Composable
fun DanmakuLayer(
    player: ExoPlayer?,
    /** 全部弹幕（已按时间升序）。 */
    danmaku: List<DanmakuItem>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** 不透明度 0f~1f。 */
    alpha: Float = 0.9f,
    /** 字号缩放 0.5f~1.5f。 */
    fontScale: Float = 1f,
    /** 显示区域占比 0.25f~1f（只在下半部分显示 = 0.5f）。 */
    displayArea: Float = 1f,
    /** 被屏蔽的弹幕类型（1=滚动 4=底部 5=顶部）。空集 = 不按类型屏蔽。 */
    blockModes: Set<Int> = emptySet(),
    /** 屏蔽关键词，命中任一即不渲染。 */
    blockKeywords: List<String> = emptyList(),
) {
    if (!enabled || player == null || danmaku.isEmpty()) return

    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // 轨道数：按字号估算行高，再按显示区域算能放几条
    val laneHeightPx = with(density) { LANE_HEIGHT.toPx() }
    val laneCount = remember(size, laneHeightPx, displayArea) {
        if (size.height <= 0) 0
        else maxOf(1, ((size.height * displayArea) / laneHeightPx).toInt())
    }

    // 当前活跃弹幕（进入屏幕后尚未消失的）。
    // ⚠️ 定义见下方 `danmakuKey` 之后 —— 它必须用指纹作 key（换视频清空）。

    // ---------------------------------------------------------------------
    // 🔴 v1.5.2 修「弹幕加载到了但屏幕上看不见」
    //
    // ## 根因：把 List **引用**当成了 key
    //
    // 原来是：
    // ```kotlin
    // var cursor by remember(danmaku) { mutableStateOf(0) }
    // val laneFreeAt = remember(danmaku) { LongArray(MAX_LANES) }
    // LaunchedEffect(player, danmaku, enabled, ...) { ... }
    // ```
    //
    // `danmaku` 是 `List<DanmakuItem>` —— Compose 的 `remember(key)` /
    // `LaunchedEffect(key)` 用 **`equals`** 比较。`List.equals` 是
    // **逐元素深比较**，看起来没问题……
    //
    // 但上游 `ensureDanmakuLoaded` 每次轮询都执行：
    // ```kotlin
    // _danmaku.value = (current + next).sortedBy { it.progressMs }
    // ```
    // 而 `DanmakuItem` 是 **data class** —— 深比较会走完 201 个元素。
    // 真正致命的是**预取**：`next`（seg 2）一开始是空、后来可能变成非空，
    // 于是列表内容变化 → key 变化 → **effect 重启** →
    // `cursor` 归零、`laneFreeAt` 清空、`active` 里正在飞行的弹幕
    // 与新的轨道状态**对不上**。
    //
    // 表现就是：日志里 `cur=201 total=201 enabled=true`（数据完全正常），
    // 但屏幕上一条都看不到 —— 因为每轮轮询都把投放状态推倒重来。
    //
    // ## 修法：key 用**稳定指纹**，不用 List 本身
    //
    // 指纹 = 条数 + 首条 progressMs + 末条 progressMs。
    // 同一批数据重复赋值时指纹不变 → 不重启 effect。
    // 换了视频 / 换了分P / 真正追加了新分段时指纹才变。
    //
    // ⚠️ 不能用 `danmaku.hashCode()`：它同样是逐元素深比较，
    // 开销大且**空列表与单元素列表的边界会抖动**。
    // ---------------------------------------------------------------------
    val danmakuKey = remember(danmaku) {
        val n = danmaku.size
        if (n == 0) {
            "dm:0"
        } else {
            "dm:$n:${danmaku.first().progressMs}:${danmaku.last().progressMs}"
        }
    }

    // 已投放的弹幕索引（避免重复投放）。
    // ⚠️ key 用 `danmakuKey`（稳定指纹）而不是 `danmaku`（List 引用）——
    // 否则每轮轮询都把 cursor 归零，弹幕反复从头投放。
    var cursor by remember(danmakuKey) { mutableStateOf(0) }
    // 轨道占用结束时间（该轨道可再用的时间戳）
    val laneFreeAt = remember(danmakuKey) { LongArray(MAX_LANES) }
    // `active` 也要跟着指纹重置 —— 否则换视频后旧弹幕残留占轨道。
    //
    // 🔴 v1.5.3 修 lint（`RememberReturnType`）：
    // 原来写 `remember(danmakuKey) { active.clear() }` —— 用 `remember`
    // 的**返回值**做副作用。lint 正确指出这有问题：`remember` 语义是
    // "缓存一个值"，不是"注册副作用"，且 lambda 返回 Unit 无意义。
    //
    // 正解：把 `active` **本身**用 `danmakuKey` 作 key ——
    // 指纹变化时 Compose 自动丢弃旧列表、重建新列表，
    // 这正是我们要的"换视频清空活跃弹幕"，且不需要任何副作用语句。
    val active = remember(danmakuKey) { mutableListOf<ActiveDanmaku>() }

    // 触发重组用的"当前时间"
    var nowMs by remember { mutableStateOf(0L) }

    /**
     * 逐帧推进。
     *
     * 每帧做三件事：
     * 1. 读播放进度，把"该出现"的弹幕投放进轨道
     * 2. 移除已飞出屏幕的
     * 3. 更新 nowMs 触发重组（位置由 nowMs 算出）
     */
    LaunchedEffect(player, danmakuKey, enabled, laneCount, size, blockModes, blockKeywords) {
        if (laneCount <= 0 || danmaku.isEmpty()) return@LaunchedEffect

        val widthPx = size.width.toFloat()
        if (widthPx <= 0f) return@LaunchedEffect

        while (true) {
            // ⚠️ 暂停时**不推进**，且不逐帧唤醒。
            //
            // 原先无条件 `withFrameNanos` 死循环：即使暂停/缓冲中，
            // 也每帧写一次 `nowMs` → 每帧重组整个弹幕层。
            // 看视频时无所谓（本来就在渲染），但暂停看评论、
            // 或缓冲卡住时，这份无谓重组会一直烧 CPU/电量，
            // 也是"操作跟手度变差"的来源之一。
            //
            // 挂在 player 的 isPlaying 上：暂停时挂起，恢复播放时
            // （state 变化触发重启）继续推进。位置由
            // `当前时间 - 出现时间` 算出，所以恢复后不会错位。
            if (player.isPlaying) {
                withFrameNanos { frameNs ->
                    val frameMs = frameNs / 1_000_000

                    // 播放进度（毫秒）。已释放时退出。
                    val posMs = runCatching { player.currentPosition }.getOrNull()
                    if (posMs == null) return@withFrameNanos

                    // 1. 投放：把 progressMs <= posMs 且尚未投放的加入
                    var changed = false
                    while (cursor < danmaku.size && danmaku[cursor].progressMs <= posMs) {
                        val item = danmaku[cursor]
                        cursor++
                        if (item.isAdvanced) continue // 高级弹幕不支持

                        // ⚠️ 屏蔽判定必须在**占轨道之前**做。
                        // 放到渲染阶段过滤的话，被屏蔽的弹幕仍会占掉一条轨道，
                        // 表现为"屏幕上弹幕稀稀拉拉、中间留着大空档"。
                        if (isBlocked(item, blockModes, blockKeywords)) continue

                        val lane = pickLane(item, laneFreeAt, frameMs, widthPx, laneCount, density.density)
                        if (lane < 0) continue // 没有空闲轨道，丢弃（弹幕过密时正常）

                        active.add(
                            ActiveDanmaku(
                                item = item,
                                lane = lane,
                                startMs = frameMs,
                                widthPx = estimateWidthPx(item, density.density),
                            ),
                        )
                        changed = true
                    }

                    // 2. 移除飞出屏幕的
                    //
                    // ⚠️ 先算好 duration，避免在 removeAll 里重复调用
                    // scrollDurationMs（原先每条每帧算一次，纯浪费）。
                    val before = active.size
                    active.removeAll { a ->
                        val duration = scrollDurationMs(a.widthPx, widthPx)
                        frameMs - a.startMs > duration
                    }
                    if (active.size != before) changed = true

                    // 3. 触发重组
                    //
                    // 仅在有增删时更新；`nowMs` 本身每帧都要用于算位移，
                    // 但空列表时没必要写 state 触发重组。
                    if (active.isNotEmpty() || changed) nowMs = frameMs
                }
            } else {
                // 暂停：等状态变化，不逐帧空转
                kotlinx.coroutines.delay(PAUSE_IDLE_POLL_MS)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
    ) {
        val widthPx = size.width.toFloat()
        if (widthPx <= 0f) return@Box

        // 固定弹幕（顶/底）单独渲染，不参与轨道
        val fixedTop = active.filter { it.item.isTop }
        val fixedBottom = active.filter { it.item.isBottom }

        // 滚动弹幕
        active.filter { it.item.isScroll }.forEach { a ->
            val elapsed = nowMs - a.startMs
            val duration = scrollDurationMs(a.widthPx, widthPx)
            if (duration <= 0f) return@forEach
            val progress = (elapsed / duration).coerceIn(0f, 1f)
            // 从右边缘（widthPx）移到完全移出左侧（-a.widthPx）
            val x = widthPx - progress * (widthPx + a.widthPx)

            DanmakuText(
                item = a.item,
                alpha = alpha,
                fontScale = fontScale,
                modifier = Modifier.offsetPx(x, a.lane * laneHeightPx),
            )
        }

        // 顶部固定
        fixedTop.take(2).forEachIndexed { i, a ->
            DanmakuText(
                item = a.item,
                alpha = alpha,
                fontScale = fontScale,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offsetPx(0f, i * laneHeightPx),
            )
        }

        // 底部固定
        fixedBottom.take(2).forEachIndexed { i, a ->
            DanmakuText(
                item = a.item,
                alpha = alpha,
                fontScale = fontScale,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offsetPx(0f, -(i + 1) * laneHeightPx),
            )
        }
    }
}

/** 一条正在显示的弹幕。 */
private data class ActiveDanmaku(
    val item: DanmakuItem,
    val lane: Int,
    /** 投放时刻（帧时间戳，毫秒）。 */
    val startMs: Long,
    /** 估算宽度（px），用于算位移与轨道占用。 */
    val widthPx: Float,
)

/**
 * 弹幕是否被本地规则屏蔽。
 *
 * ## 两类规则
 *
 * 1. **类型**：[blockModes] 里含该弹幕的类型码。
 *    类型码按"用户能理解的分类"归并 —— 用户看到的是「滚动/顶部/底部」三档，
 *    而接口的 `mode` 有 1/2/3 三种滚动、6 逆向滚动。若直接拿 mode 比对，
 *    "屏蔽滚动"只挡住 mode=1，2/3/6 照样飘过去。
 *    所以这里把 mode 归并到 1 / 4 / 5 三个语义档再判。
 * 2. **关键词**：内容包含任一关键词（大小写不敏感）。
 *
 * 关键词匹配用 `contains(ignoreCase = true)` —— 中文没有大小写问题，
 * 但弹幕里混英文时"屏蔽 FGO"应该也挡住 "fgo"。
 */
internal fun isBlocked(
    item: DanmakuItem,
    blockModes: Set<Int>,
    blockKeywords: List<String>,
): Boolean {
    if (blockModes.isNotEmpty() && semanticMode(item) in blockModes) return true
    if (blockKeywords.isEmpty()) return false
    val text = item.content
    return blockKeywords.any { it.isNotEmpty() && text.contains(it, ignoreCase = true) }
}

/**
 * 把弹幕的 `mode` 归并成用户能理解的三个语义档。
 *
 * | 原始 mode | 语义 | 返回 |
 * |---|---|---|
 * | 1 / 2 / 3 / 6 | 滚动（含逆向） | 1 |
 * | 4 | 底部固定 | 4 |
 * | 5 | 顶部固定 | 5 |
 * | 7 / 8 | 高级/代码（本来就不渲染） | -1 |
 */
internal fun semanticMode(item: DanmakuItem): Int = when {
    item.isScroll || item.mode == DanmakuItem.MODE_REVERSE -> 1
    item.isBottom -> 4
    item.isTop -> 5
    else -> -1
}

/**
 * 选一条空闲轨道。
 *
 * 轨道"空闲"的判定：上一条弹幕的**尾部**已经离开右边缘。
 * 用时间比较而不是位置比较，避免每帧遍历。
 *
 * @return 轨道号；-1 表示全忙（此时丢弃该弹幕）
 */
private fun pickLane(
    item: DanmakuItem,
    laneFreeAt: LongArray,
    nowMs: Long,
    widthPx: Float,
    laneCount: Int,
    density: Float,
): Int {
    val myWidth = estimateWidthPx(item, density)
    // 上一条弹幕尾部离开右边缘所需时间
    val needMs = (myWidth / widthPx * SCROLL_DURATION_BASE_MS).toLong() + LANE_GAP_MS

    for (i in 0 until minOf(laneCount, laneFreeAt.size)) {
        if (laneFreeAt[i] <= nowMs) {
            laneFreeAt[i] = nowMs + needMs
            return i
        }
    }
    return -1
}

/** 滚动弹幕的总存活时长（毫秒）。 */
private fun scrollDurationMs(widthPx: Float, screenWidthPx: Float): Float {
    // 速度恒定：整屏宽度对应 SCROLL_DURATION_BASE_MS
    // 自身宽度额外需要同样比例的时长，才能完全移出
    val ratio = (screenWidthPx + widthPx) / screenWidthPx
    return SCROLL_DURATION_BASE_MS * ratio
}

/** 估算弹幕宽度（px）。中文字宽 ≈ 字号，英文约一半。 */
private fun estimateWidthPx(item: DanmakuItem, density: Float): Float {
    val base = DANMAKU_FONT_SP * density * item.fontSize / 25f
    var w = 0f
    for (c in item.content) {
        w += if (c.code > 0x2E80) base else base * 0.55f
    }
    return w.coerceAtLeast(base)
}

/** 单条弹幕的文字。带描边保证在任意画面上可读。 */
@Composable
private fun DanmakuText(
    item: DanmakuItem,
    alpha: Float,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = BiliV3.colors
    Text(
        text = item.content,
        style = TextStyle(
            fontSize = (DANMAKU_FONT_SP * fontScale * item.fontSize / 25f).sp,
            fontWeight = FontWeight.Medium,
            color = Color(item.argb),
            // 描边：深色弹幕压在暗画面上会看不清，官方也是描边方案
            shadow = androidx.compose.ui.graphics.Shadow(
                color = colors.controlOverlay,
                offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                blurRadius = 2f,
            ),
        ),
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/** 按像素偏移（弹幕需要精确像素定位，不能用 dp 取整）。 */
private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(x.toInt(), y.toInt())
            }
        },
    )

/** 轨道高度。比字号大一点，避免上下贴太紧。 */
private val LANE_HEIGHT = 22.dp

/** 弹幕基准字号（官方 25 号对应约 16sp）。 */
private const val DANMAKU_FONT_SP = 16f

/** 整屏宽度滚过所需时长。官方观感约 8 秒。 */
private const val SCROLL_DURATION_BASE_MS = 8000f

/** 同轨道两条弹幕的最小间隔。 */
private const val LANE_GAP_MS = 200L

/** 轨道上限（防止超大屏算出过多轨道）。 */
private const val MAX_LANES = 32

/** 暂停时检查播放状态的间隔。不逐帧空转，省电省 CPU。 */
private const val PAUSE_IDLE_POLL_MS = 200L
