package com.example.biliv3.ui.accounts

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.biliv3.data.auth.SavedAccount
import com.example.biliv3.design.ruleBottom
import com.example.biliv3.design.ruleTop
import com.example.biliv3.design.tokens.Rule
import com.example.biliv3.ui.component.BrandButton
import com.example.biliv3.ui.component.BrandButtonVariant
import com.example.biliv3.design.v3.BiliV3
import com.example.biliv3.design.v3.V3Space
import com.example.biliv3.design.v3.V3Radius
import com.example.biliv3.design.v3.V3Size
import com.example.biliv3.design.v3.V3Type

/**
 * 切换账号页（多账号管理）。
 *
 * ## 布局（紧凑、收纳式）
 *
 * ```
 * [← 切换账号]
 * ┌──────────────────────────────┐
 * │ (头像) 昵称        ✓ 当前     │  ← 当前账号高亮
 * │        UID 12345              │
 * ├──────────────────────────────┤
 * │ (头像) 昵称              🗑    │  ← 其它账号，点击即切换
 * │        UID 67890              │
 * └──────────────────────────────┘
 * [ + 添加新账号 ]
 * [ 退出当前账号 ]
 * ```
 *
 * ## 交互约定
 *
 * - 点账号行 = **切换**（不是"查看详情"，避免多一层跳转）
 * - 移除是**破坏性操作**，走 ⋮/垃圾桶 + 二次确认（`ConfirmSheet`），
 *   不裸暴露在行上（AGENTS.md §7.2 第 19 条）
 * - 当前账号行**不可点击切换**（点了给"已经是当前账号"提示）
 *
 * @param onAddAccount 去登录页添加新账号
 * @param onBack 返回
 */
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel,
) {
    val colors = BiliV3.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    /** 待移除的账号（非 null 时弹确认面板）。 */
    var pendingRemove by remember { mutableStateOf<SavedAccount?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgPrimary),
        ) {
            // ---- 顶栏 ----
            // 通栏：不再是卡片，内容直接排。
            // ⚠️ 底线不可省（v1.2.4 补）：§7.4-32 要求「二级页标题栏一律
            // ruleBottom(color = Rule.color)」。此处原先完全没有线，
            // 标题"浮"在账号列表上（其余 15 个二级页都有）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    text = "切换账号",
                    style = V3Type.subheadline.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.labelPrimary,
                    ),
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    top = V3Space.xs,
                    bottom = V3Space.xl,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                // ---- 账号列表 ----
                if (state.accounts.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = V3Space.md,
                                    vertical = V3Space.xxxl,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "还没有已保存的账号",
                                style = V3Type.callout.copy(
                                    color = colors.labelSecondary,
                                ),
                            )
                            Spacer(Modifier.height(V3Space.xs))
                            Text(
                                text = "登录后账号会自动出现在这里，方便下次一键切换",
                                style = V3Type.caption1.copy(
                                    color = colors.labelTertiary,
                                ),
                            )
                        }
                    }
                } else {
                    items(state.accounts, key = { it.mid }) { account ->
                        AccountRow(
                            account = account,
                            isCurrent = account.mid == state.currentMid,
                            switching = state.switchingMid == account.mid,
                            anySwitching = state.switchingMid != 0L,
                            onClick = { viewModel.switchTo(account.mid) },
                            onRemove = { pendingRemove = account },
                        )
                    }
                }

                // ---- 添加新账号 ----
                item(key = "add") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 列表项之间用发丝线分隔（账号列表项一律直角）
                            .ruleTop(color = Rule.subtle)
                            .clickable(onClick = onAddAccount)
                            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = colors.brandBiliText,
                                modifier = Modifier.size(V3Size.iconMd),
                            )
                            Spacer(Modifier.width(V3Space.xs))
                            Text(
                                text = "添加新账号",
                                style = V3Type.callout.copy(
                                    color = colors.brandBiliText,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                }

                // ---- 退出登录 ----
                if (state.currentMid != 0L) {
                    item(key = "logout") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 列表项 + 上边发丝线
                                .ruleTop(color = Rule.subtle)
                                .clickable { viewModel.logout() }
                                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                        ) {
                            Text(
                                text = "退出当前账号",
                                style = V3Type.callout.copy(
                                    color = colors.stateError,
                                ),
                            )
                        }
                    }
                }

                // 已保存多个账号时才给"清除全部"——只有一个号时
                // 它与"退出当前账号"效果几乎一样，多一个入口反而费解。
                if (state.accounts.size > 1) {
                    item(key = "forget-all") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 列表项 + 上边发丝线
                                .ruleTop(color = Rule.subtle)
                                .clickable { viewModel.logoutAndForgetAll() }
                                .padding(horizontal = V3Space.md, vertical = V3Space.sm),
                        ) {
                            Text(
                                text = "退出并清除全部账号",
                                style = V3Type.callout.copy(
                                    color = colors.labelTertiary,
                                ),
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = V3Space.huge),
        )
    }

    // ---- 移除账号二次确认 ----
    //
    // 用页面内底部面板而不是 `AlertDialog`：与项目其它弹层
    // （举报理由、评论输入）同一套做法，返回手势优先级一致。
    pendingRemove?.let { target ->
        ConfirmPanel(
            title = "移除账号",
            message = "确定移除「${target.displayName}」吗？移除后需要重新登录才能再用这个账号。",
            confirmLabel = "移除",
            onConfirm = {
                viewModel.remove(target.mid)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

/**
 * 二次确认面板（破坏性操作）。
 *
 * 参考 `ReportReasonSheet` 的做法：页面内浮层 + 半透明遮罩 +
 * 底部圆角面板 + 把手，不用 `Dialog` —— 保证返回手势优先级与
 * 其它弹层完全一致（统一心智）。
 */
@Composable
private fun ConfirmPanel(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BiliV3.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 底部确认面板是**独立浮起面**，用 `panel`（16dp）而非页面内元素圆角。
                .clip(RoundedCornerShape(topStart = V3Radius.lg, topEnd = V3Radius.lg))
                .background(colors.bgSecondary)
                // 阻止点击穿透到遮罩
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(vertical = V3Space.sm),
        ) {
            // 把手（暗示可下拉关闭）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(32.dp)
                    .height(V3Space.xxs)
                    .clip(RoundedCornerShape(V3Radius.pill))
                    .background(colors.separatorOpaque),
            )
            Spacer(Modifier.height(V3Space.sm))

            Text(
                text = title,
                style = V3Type.callout.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.labelPrimary,
                ),
                modifier = Modifier.padding(horizontal = V3Space.md),
            )
            Spacer(Modifier.height(V3Space.xxs))
            Text(
                text = message,
                style = V3Type.footnote.copy(
                    color = colors.labelSecondary,
                ),
                modifier = Modifier.padding(horizontal = V3Space.md),
            )
            Spacer(Modifier.height(V3Space.md))

            Row(
                modifier = Modifier.padding(horizontal = V3Space.md),
                horizontalArrangement = Arrangement.spacedBy(V3Space.xs),
            ) {
                BrandButton(
                    label = "取消",
                    onClick = onDismiss,
                    variant = BrandButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                BrandButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    variant = BrandButtonVariant.Filled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(V3Space.xs))
        }
    }
}

/**
 * 单个账号行。
 *
 * 当前账号：右侧显示 ✓ + 文字"当前"（不只靠颜色区分），整行不可点切换。
 * 其它账号：整行可点切换，右侧垃圾桶（走二次确认）。
 */
@Composable
private fun AccountRow(
    account: SavedAccount,
    isCurrent: Boolean,
    switching: Boolean,
    anySwitching: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = BiliV3.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 账号行**不再是卡片** —— 列表用留白分组，行间用发丝线分隔。
            // 切换中禁用整行，避免连点导致切换交错
            .ruleTop(color = Rule.subtle)
            .clickable(enabled = !anySwitching, onClick = onClick)
            .padding(horizontal = V3Space.md, vertical = V3Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = account.faceUrl(120),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(V3Size.avatarXs + V3Space.md)
                .clip(CircleShape)
                .background(colors.avatarPlaceholder),
        )
        Spacer(Modifier.width(V3Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayName,
                style = V3Type.callout.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.labelPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(V3Space.hairline))
            Text(
                text = "UID ${account.mid}",
                style = V3Type.caption1.copy(
                    color = colors.labelSecondary,
                ),
                maxLines = 1,
            )
        }

        when {
            // 切换中：转圈（给明确反馈，不是"点了没反应"）
            switching -> CircularProgressIndicator(
                color = colors.brand,
                strokeWidth = V3Space.progressTrack,
                modifier = Modifier.size(V3Size.iconLg),
            )

            isCurrent -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier.size(V3Size.iconMd),
                )
                Spacer(Modifier.width(V3Space.xxs))
                Text(
                    text = "当前",
                    style = V3Type.caption2.copy(
                        color = colors.brand,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            else -> Box(
                modifier = Modifier
                    .size(V3Size.touchMin)
                    .clip(CircleShape)
                    .clickable(enabled = !anySwitching, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "移除账号",
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(V3Size.iconMd),
                )
            }
        }
    }
}
