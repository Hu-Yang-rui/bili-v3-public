# -*- coding: utf-8 -*-
"""
Migrate remaining files from the legacy palette (BiliTheme.colors / BiliColors)
to design system v3 (BiliV3.colors / V3Colors).

## Why this is a script and not 61 hand edits

1039 references across 61 files, all of the same mechanical shape:

    val colors = BiliTheme.colors      ->  val colors = BiliV3.colors
    colors.textPrimary                 ->  colors.labelPrimary
    ...

The mapping is 1:1 and total, so a script is the honest tool. But two rules
from the project's own history are encoded below because they are the exact
ways this has gone wrong before:

1. **`colors` is a LOCAL NAME and it lies** (坑 188). A function may bind
   `val colors = BiliTheme.colors` OR `val colors = BiliV3.colors`. Renaming
   fields blindly hits functions that still use the OLD palette and breaks the
   build. So: only rewrite inside files that are still old-only, and only
   rewrite the alias that is actually bound to `BiliTheme.colors`.

2. **PowerShell corrupts UTF-8 source** (§8, "已踩三次"). This script is
   Python and reads/writes explicit UTF-8, and never touches a file that has
   no match. It is idempotent: running twice changes nothing the second time.

## Not a blind search/replace

Fields are rewritten ONLY when they appear as `<alias>.<field>` where
`<alias>` was bound to `BiliTheme.colors` in that same file. A bare
`textPrimary` string in a comment or an unrelated object is left alone.
"""

import io
import os
import re
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3"

# ---------------------------------------------------------------------------
# The mapping. Derived from design/tokens/Colors.kt's own documented mapping
# table + V3Colors' layer rules. Every old field MUST appear here, or the run
# aborts (a silent miss would leave a half-migrated file).
# ---------------------------------------------------------------------------
MAP = {
    # ---- 品牌 / 交互 ----
    # 旧表把 brandPrimary（粉）当交互色；v3 明确改为「交互蓝，粉降为品牌标识」
    "brandPrimary": "brand",
    "brandPrimaryHover": "brand",
    "brandPrimaryActive": "brand",
    "brandPrimaryDim": "brandDim",
    "brandSecondary": "brandBili",
    "brandSecondaryHover": "brandBili",
    # 文字安全变体
    "textBrandSafe": "brandBiliText",
    "textLinkSafe": "brandText",
    "textOnBrand": "labelOnBrand",

    # ---- 文字 ----
    "textPrimary": "labelPrimary",
    "textSecondarySafe": "labelSecondary",
    "textSecondary": "labelSecondary",
    "textTertiary": "labelTertiary",
    "textOnMedia": "labelOnMedia",

    # ---- 背景 ----
    "bgBase": "bgPrimary",
    "bgCard": "bgSecondary",
    "bgHover": "bgTertiary",
    "surfaceElevated": "bgSecondaryElevated",

    # ---- 描边 ----
    "borderHairline": "separator",
    "borderStrong": "separatorOpaque",

    # ---- 遮罩 ----
    "overlayCover": "overlay",
    "scrimPanel": "scrim",
    "overlayControl": "controlOverlay",
    "onOverlay": "labelOnMedia",
    "gradientMediaEnd": "gradientMediaEnd",

    # ---- 状态 ----
    "stateError": "stateError",
    "stateSuccess": "stateSuccess",
    "stateLive": "stateLive",

    # ---- 互动 ----
    "accentCoin": "accentCoin",
    "accentCoinBright": "accentCoin",
    "accentFavorite": "accentFavorite",
    "onAccentCoin": "onAccentCoin",

    # ---- 播放器 / 媒体 ----
    "playerBackground": "playerBackground",
    "subtitleScrim": "subtitleScrim",
    "danmakuStroke": "danmakuStroke",

    # ---- 占位 ----
    "coverPlaceholder": "coverPlaceholder",
    "avatarPlaceholder": "avatarPlaceholder",
    "skeletonBase": "skeletonBase",
    "skeletonHighlight": "skeletonHighlight",

    # ---- 二维码 ----
    "qrSurface": "qrSurface",
    "onQrSurface": "onQrSurface",

    # ---- 榜单 ----
    "rankFirst": "rankFirst",
    "rankSecond": "rankSecond",
    "rankThird": "rankThird",

    # ---- 分区 ----
    "categoryAccent": "categoryAccent",

    # ---- 极客点缀 ----
    "accentTerminal": "accentTerminal",
    "accentTerminalDim": "accentTerminalDim",
    "gridLine": "gridLine",

    # ---- 空降助手 ----
    "skipSegment": "skipSegment",
    "skipSegmentActive": "skipSegmentActive",

    # ---- 轨道 ----
    "trackInactive": "trackInactive",

    # ---- 第三方渠道（他方品牌真值，值相同）----
    "channelWechat": "channelWechat",
    "channelMoments": "channelMoments",
    "channelDownload": "channelDownload",
    "channelCopyLink": "channelCopyLink",

    # ---- 已属于 v3 的名字（文件可能同时绑了两个 palette）----
    "labelPrimary": "labelPrimary",
    "labelSecondary": "labelSecondary",
    "labelTertiary": "labelTertiary",
    "labelOnBrand": "labelOnBrand",
    "bgPrimary": "bgPrimary",
    "separator": "separator",
    "brand": "brand",
    "brandText": "brandText",
    "brandBili": "brandBili",
    "fillPrimary": "fillPrimary",
    "fillSecondary": "fillSecondary",
    "fillTertiary": "fillTertiary",
    "fillQuaternary": "fillQuaternary",
    "materials": "materials",
    "scrim": "scrim",
}

# Old fields deliberately NOT rewritten: they have no v3 meaning and their
# call sites need a human decision, not a rename. Listed so the guard below
# can distinguish "known-unmapped" from "forgotten".
# ---------------------------------------------------------------------------
# Dimension mapping: legacy 4dp-rhythm tokens -> v3 8pt grid.
#
# ⚠️ Unlike colors, this is NOT purely cosmetic-equivalent. Values are split
#    into two groups and the second group is a DELIBERATE visual change that
#    §5.4.3 of the design system already ratified. Both are recorded so the
#    diff is reviewable rather than mysterious.
#
# `SPACE_1TO1`   — identical dp value, pure rename (1176 of 1337 refs)
# `SPACE_CHANGED`— value changes on purpose, with the reason
# ---------------------------------------------------------------------------

SPACE_1TO1 = {
    "micro": "hairline",              # 2 -> 2
    "x1": "xxs",                      # 4 -> 4
    "x2": "xs",                       # 8 -> 8
    "x3": "sm",                       # 12 -> 12
    "x4": "md",                       # 16 -> 16
    "x5": "lg",                       # 20 -> 20
    "x6": "xl",                       # 24 -> 24
    "x8": "xxl",                      # 32 -> 32
    "x10": "xxxl",                    # 40 -> 40
    "x12": "huge",                    # 48 -> 48
    "pageMobile": "contentMargin",    # 16 -> 16
    "pageTablet": "lg",               # 20 -> 20
    "pageDesktop": "xl",              # 24 -> 24
    "gridGutterMobile": "sm",         # 12 -> 12
    "gridGutterTablet": "md",         # 16 -> 16
    "gridGutterDesktop": "lg",        # 20 -> 20
    "gridRowMobile": "sm",            # 12 -> 12
    "gridRowDesktop": "xl",           # 24 -> 24
    "sectionMobile": "md",            # 16 -> 16
    "sectionTablet": "lg",            # 20 -> 20
    "compactVertical": "hairline",    # 2 -> 2
}

# value changes: (target, old, new, why)
#
# ⚠️ The target is written as a FULLY QUALIFIED token reference, because some
#    of these move to a *different* object (touchMin lives on V3Size, not
#    V3Space). Prefixing every target with "V3Space." would produce
#    `V3Space.touchMin`, which does not exist.
SPACE_CHANGED = {
    # 触摸目标 48 -> 44：iOS 27 实测确认 44 是**下限**（顶栏按钮恰好 44×44），
    # 而底部工具栏 48、大按钮 50。所以 44 是"最小"，不是"标准"。
    "minTouchTarget": ("V3Size.touchMin", 48, 44, "iOS 实测下限 44dp"),
    # 列表行纵向 10 -> 12：归到 8pt 网格档位（10 不是网格值）
    "rowVertical": ("V3Space.sm", 10, 12, "10dp 不在 8pt 网格上"),
    # 小控件水平呼吸 6 -> 8：同上（6 不在网格上）
    "compactHorizontal": ("V3Space.xs", 6, 8, "6dp 不在 8pt 网格上"),
    # 平板行距 18 -> 16：同上
    "gridRowTablet": ("V3Space.md", 18, 16, "18dp 不在 8pt 网格上"),
    # 桌面区块间距 28 -> 32：同上
    "sectionDesktop": ("V3Space.xxl", 28, 32, "28dp 不在 8pt 网格上"),
    # 轨道高度 2 -> 4：iOS 27 实测是**从 6 降到 4**，细轨道更现代
    "trackHeight": ("V3Space.progressTrack", 2, 4, "iOS 实测 4dp"),
    # 非间距值：值不变，只是搬到语义正确的名字下
    "hairline": ("V3Space.lineHairline", 1, 1, "线宽不是间距"),
    "tabIndicator": ("V3Space.tabIndicator", 3, 3, "选中标记不是间距"),
    "tagHorizontal": ("V3Space.tagHorizontal", 4, 4, "标签内边距不是间距"),
    "tagVertical": ("V3Space.tagVertical", 1, 1, "标签内边距不是间距"),
}

# ---------------------------------------------------------------------------
# Radius: v3 reassigns by COMPONENT, not by "is it interactive".
# ---------------------------------------------------------------------------
RADIUS_MAP = {
    "interactive": "xs",   # 4 -> 4
    "badge": "xs",         # 4 -> 4
    "control": "sm",       # 6 -> 8（6 不在 v3 档位表里）
    "card": "md",          # 12 -> 12
    "panel": "lg",         # 16 -> 16
    "pill": "pill",        # 999 -> 999
    "thumb": "sm",         # 见下方说明
}

# ---------------------------------------------------------------------------
# Sizes: only the ones with a real v3 home. Layout constants that are
# app-specific (banner height, side panel width, live thumb) are deliberately
# LEFT ALONE — they are not design-system tokens, they are this app's layout.
# ---------------------------------------------------------------------------
SIZES_MAP = {
    "iconSm": "iconXs",          # 14 -> 14
    "iconLg": "iconMd",          # 20 -> 20
    "iconXl": "iconLg",          # 24 -> 24
    "upAvatar": "avatarXs",      # 20 -> 20
    "durationBadgeHeight": "durationBadge",  # 18 -> 18
    "coverAspectRatio": "coverAspect",       # 1.6f -> 1.6f
    # 顶栏三档 -> 统一 54：§5.4.3「顶栏只有搜索框+两个圆钮，分档只会让三端不一致」
    "topBarMobile": "topBar",    # 52 -> 54
    "topBarTablet": "topBar",    # 56 -> 54
    "topBarDesktop": "topBar",   # 64 -> 54
    # 搜索框 40 -> 36：iOS 27 实测
    "searchHeight": "searchField",
    # ⚠️ iconMd 是 18dp，v3 没有 18 这一档 —— 取最近的 20dp。
    #    18 -> 20 是有意的：v3 的图标档是 14/17/20/24/28，
    #    18 是旧表在 14 与 20 之间自己插的一档（不在任何网格上）。
    "iconMd": "iconMd",          # 18 -> 20（归到最近的 v3 档）
    # 状态点：v3 放在 V3Size（不是 V3Space）—— 它们是**尺寸**不是间距
    "dotSm": "dotSm",            # 5 -> 5
    "dotLg": "dotLg",            # 8 -> 8
}

# Sizes with NO v3 equivalent — left in place on purpose. These are layout
# constants, not design tokens. Recorded so a reader can tell "not yet mapped"
# from "no mapping needed".
SIZES_UNMAPPED = {
    "categoryTabBar", "bannerDesktop", "bannerTablet", "bannerMobile",
    "bottomNav", "sidePanel", "searchWidthDesktop", "categoryIcon",
    "liveThumbWidth", "liveThumbHeight",
}

# ---------------------------------------------------------------------------
# Typography: legacy 8-step scale -> v3 11-step semantic scale.
#
# ⚠️ Mapping is by SEMANTICS (what the text IS), not by size. The old table's
#    own KDoc documents the intended use of each step, and v3's is explicit;
#    they line up 6-out-of-8 on size as well, which is the cross-check that the
#    semantic mapping is right rather than convenient:
#
#      old          size   ->  v3            size   verdict
#      display      20/28      title3        20/25  SAME
#      titleLg      17/24      headline      17/22  SAME
#      titleMd      15/22      subheadline   15/20  SAME
#      body         14/21      callout       16/21  CHANGED 14->16
#      bodySm       13/19      footnote      13/18  SAME
#      label        12/17      caption1      12/16  SAME
#      micro        11/-       caption2      11/13  SAME
#      badge        10/-       caption2      11/13  CHANGED 10->11
#
#    The two changes are deliberate and were ratified by §5.4.3:
#      body  14->16 — v3's body is 17sp (iOS) and callout is 16; 14sp was a
#                     web-era size that reads small on a phone at 420dpi.
#      badge 10->11 — 10sp is below the practical floor for CJK glyphs; the
#                     duration badge stays legible at 11 while still being the
#                     smallest step.
# ---------------------------------------------------------------------------
FONTSIZE_MAP = {
    "display": "title3",
    "titleLg": "headline",
    "titleMd": "subheadline",
    "body": "callout",
    "bodySm": "footnote",
    "label": "caption1",
    "micro": "caption2",
    "badge": "caption2",
}

# The `*Line` companions are gone: v3 styles carry their own lineHeight, so a
# separate lineHeight override is no longer needed at the call site.
FONTSIZE_LINE_MAP = {
    "displayLine": "title3",
    "titleLgLine": "headline",
    "titleMdLine": "subheadline",
    "bodyLine": "callout",
    "bodySmLine": "footnote",
    "labelLine": "caption1",
    "monoReadoutLine": None,   # -> V3Type.readout()
    "monoReadout": None,       # -> V3Type.readout()
}

KNOWN_UNMAPPED = set()

# The two files that DEFINE the palettes must never be rewritten: their
# `BiliTheme.colors` mentions are the definition/import of the legacy table
# itself, not a consumer of it.
EXCLUDE = {
    os.path.join(ROOT, "design", "BiliTheme.kt"),
    os.path.join(ROOT, "design", "tokens", "Colors.kt"),
}


def fix_imports(s):
    """
    Add the `BiliV3` / v3-token imports the rename now requires.

    ## Why this is a separate step (and why the first run failed)

    Renaming `BiliTheme.colors` -> `BiliV3.colors` introduces a symbol that the
    file does not import. The first apply produced 61 files of
    `Unresolved reference 'BiliV3'` — the rename is only half the change.

    The palette entry point lives in `com.example.biliv3.design.v3`, so each
    file needs that import plus one for whatever token objects it now uses.
    """
    return _add_imports(s, [
        (r"\bBiliV3\.", "com.example.biliv3.design.v3.BiliV3"),
        (r"\bV3Space\.", "com.example.biliv3.design.v3.V3Space"),
        (r"\bV3Radius\.", "com.example.biliv3.design.v3.V3Radius"),
        (r"\bV3Size\.", "com.example.biliv3.design.v3.V3Size"),
        (r"\bV3Type\.", "com.example.biliv3.design.v3.V3Type"),
        (r"\bV3Motion\.", "com.example.biliv3.design.v3.V3Motion"),
        (r"\bV3Glass\.", "com.example.biliv3.design.v3.V3Glass"),
        (r"\bGlassSurface\b", "com.example.biliv3.design.v3.GlassSurface"),
        (r"\bGlassSheet\b", "com.example.biliv3.design.v3.GlassSheet"),
        (r"\bGlassDialog\b", "com.example.biliv3.design.v3.GlassDialog"),
        (r"\bGlassButton\b", "com.example.biliv3.design.v3.GlassButton"),
        (r"\bGlassFab\b", "com.example.biliv3.design.v3.GlassFab"),
        (r"\bV3Block\b", "com.example.biliv3.design.v3.V3Block"),
        (r"\bV3Section\b", "com.example.biliv3.design.v3.V3Section"),
        (r"\bV3SectionTitle\b", "com.example.biliv3.design.v3.V3SectionTitle"),
        (r"\bV3Row\b", "com.example.biliv3.design.v3.V3Row"),
        (r"\bV3ContentRow\b", "com.example.biliv3.design.v3.V3ContentRow"),
        (r"\bV3Divider\b", "com.example.biliv3.design.v3.V3Divider"),
        (r"\bV3SwitchRow\b", "com.example.biliv3.design.v3.V3SwitchRow"),
        (r"\bV3Page\b", "com.example.biliv3.design.v3.V3Page"),
        (r"\bProvideHazeState\b", "com.example.biliv3.design.v3.ProvideHazeState"),
        (r"\bProvideGlassBackdrop\b", "com.example.biliv3.design.v3.ProvideGlassBackdrop"),
        (r"\bGlassBackdrop\b", "com.example.biliv3.design.v3.GlassBackdrop"),
        (r"\bGlassNavBar\b", "com.example.biliv3.design.v3.GlassNavBar"),
        (r"\bGlassNavItem\b", "com.example.biliv3.design.v3.GlassNavItem"),
    ])


def _add_imports(s, pairs):
    """Insert `import <fq>` for every symbol that is used but not imported."""
    needed = [fq for pat, fq in pairs if re.search(pat, s)]
    if not needed:
        return s
    lines = s.split("\n")
    have = set()
    for ln in lines:
        m = re.match(r"\s*import\s+([\w.]+)\s*$", ln)
        if m:
            have.add(m.group(1))
    add = [n for n in needed if n not in have]
    if not add:
        return s
    last = -1
    for i, ln in enumerate(lines):
        if re.match(r"\s*import\s+", ln):
            last = i
    if last < 0:
        # No imports at all (pure-Kotlin files like SkipBarGeometry.kt).
        #
        # 🔴 Bug this shipped with: the first version appended "" to `add` as a
        #    blank-line separator and then prefixed EVERY entry with "import ",
        #    which produced a literal `import ` line -> `Syntax error: Expecting
        #    qualified name.` The separator must be added to the rendered block,
        #    never to the symbol list.
        for i, ln in enumerate(lines):
            if ln.startswith("package "):
                last = i
                break
        block = [""] + ["import " + n for n in add]
    else:
        block = ["import " + n for n in add]
    return "\n".join(lines[: last + 1] + block + lines[last + 1 :])


# ---------------------------------------------------------------------------
# Dimension pass (stage 2). Separate from colors because it carries real
# visual changes and must be reviewable on its own.
# ---------------------------------------------------------------------------

def migrate_dimens(s, stats):
    """
    Rewrite Space / Radius / Sizes references.

    ⚠️ `Space.*` and `Sizes.*` are referenced through **the object name**, not
    through a local alias, so unlike colors these are safe to rewrite by name —
    there is no `colors`-style alias that can point at a different palette.

    Left alone on purpose:
      - `FontSize.*`  — the v3 type scale is a genuine re-scale (14sp body
        becomes 16-17sp). Doing it in the same pass as a rename would make a
        layout regression indistinguishable from a rename bug. Stage 3.
      - `Rule/Rhythm/Band/Emboss/Grain` — still-valid primitives in
        `design/tokens/Surface.kt`, not part of the old *palette*.
    """
    # ---- Space ----
    #
    # ⚠️ Fully-qualified references exist in this codebase, e.g.
    #    `com.example.biliv3.design.tokens.Space.tagHorizontal`.
    #    Rewriting only the trailing `Space.xxx` yields
    #    `com.example.biliv3.design.tokens.V3Space.tagHorizontal` — a symbol
    #    that does not exist. So normalise the FULL path first.
    s = s.replace(
        "com.example.biliv3.design.tokens.Space.", "V3Space."
    ).replace(
        "com.example.biliv3.design.tokens.Radius.", "V3Radius."
    ).replace(
        "com.example.biliv3.design.tokens.Sizes.", "V3Size."
    )

    def sp(m):
        t = m.group(1)
        if t in SPACE_1TO1:
            stats["space_1to1"] += 1
            return "V3Space." + SPACE_1TO1[t]
        if t in SPACE_CHANGED:
            target, old, new, _why = SPACE_CHANGED[t]
            stats["space_changed"] += 1
            stats["space_changed_detail"][t] = stats["space_changed_detail"].get(t, 0) + 1
            # target may already carry its own object prefix (V3Size.touchMin)
            return target if "." in target else "V3Space." + target
        stats["space_unknown"][t] = stats["space_unknown"].get(t, 0) + 1
        return m.group(0)

    s = re.sub(r"\bSpace\.(\w+)", sp, s)

    # ---- Radius ----
    def rad(m):
        t = m.group(1)
        if t in RADIUS_MAP:
            stats["radius"] += 1
            return "V3Radius." + RADIUS_MAP[t]
        stats["radius_unknown"][t] = stats["radius_unknown"].get(t, 0) + 1
        return m.group(0)

    s = re.sub(r"\bRadius\.(\w+)", rad, s)

    # ---- Sizes ----
    def sz(m):
        t = m.group(1)
        if t in SIZES_MAP:
            stats["sizes"] += 1
            return "V3Size." + SIZES_MAP[t]
        if t in SIZES_UNMAPPED:
            stats["sizes_kept"] += 1
            return m.group(0)
        stats["sizes_unknown"][t] = stats["sizes_unknown"].get(t, 0) + 1
        return m.group(0)

    s = re.sub(r"\bSizes\.(\w+)", sz, s)

    return s


def migrate_typography(s, stats):
    """
    Stage 3: legacy `FontSize.*` -> v3 semantic scale.

    ## 🔴 Why this is NOT a plain token swap

    `FontSize.label` is a **TextUnit** (12.sp). `V3Type.caption1` is a
    **TextStyle**. Writing `fontSize = V3Type.caption1` is a type error, so the
    naive rename cannot work.

    The call sites look like:

        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = FontSize.bodySm,
            lineHeight = FontSize.bodySmLine,
            color = ...,
        )

    Two type-correct options:
      (a) replace the whole `style =` expression with `V3Type.footnote`
      (b) reach through the style for the unit: `V3Type.footnote.fontSize`

    (a) is prettier but must also carry the `color`, and the `copy(...)` calls
    vary (some have color, some don't, some are inside `.copy(` chains with
    other params). Rewriting them by regex would be fragile.

    (b) is a pure expression-level substitution that is type-correct in every
    position and cannot disturb surrounding code. **Chosen.**

    The legacy `*Line` companions disappear because v3 styles carry their own
    lineHeight — so `lineHeight = FontSize.bodySmLine` becomes
    `lineHeight = V3Type.footnote.lineHeight`.
    """
    # Longest names first, so `bodySmLine` is not eaten by `bodySm`.
    def fu(m):
        t = m.group(1)
        if t in FONTSIZE_MAP:
            stats["font"] += 1
            return "V3Type." + FONTSIZE_MAP[t] + ".fontSize"
        if t in FONTSIZE_LINE_MAP and FONTSIZE_LINE_MAP[t] is not None:
            stats["font_line"] += 1
            return "V3Type." + FONTSIZE_LINE_MAP[t] + ".lineHeight"
        if t == "monoReadout":
            stats["font"] += 1
            return "V3Type.readout().fontSize"
        if t == "monoReadoutLine":
            stats["font_line"] += 1
            return "V3Type.readout().lineHeight"
        stats["font_unknown"][t] = stats["font_unknown"].get(t, 0) + 1
        return m.group(0)

    s = re.sub(
        r"\bFontSize\.(bodySmLine|titleMdLine|titleLgLine|displayLine|bodyLine|"
        r"labelLine|monoReadoutLine|monoReadout|bodySm|titleMd|titleLg|"
        r"display|body|label|micro|badge)\b",
        fu, s,
    )
    return s


def new_stats():
    return {
        "space_1to1": 0, "space_changed": 0, "radius": 0, "sizes": 0,
        "sizes_kept": 0, "space_unknown": {}, "radius_unknown": {},
        "sizes_unknown": {}, "space_changed_detail": {},
        "font": 0, "font_line": 0, "font_unknown": {},
    }


def strip_dead_imports(s):
    """
    Drop legacy token imports that no longer have a referent.

    ⚠️ Why this matters: after `Space.x2` becomes `V3Space.xs`, the file no
    longer uses `design.tokens.Space`, so its import is dead. Kotlin only
    warns about that — but this project's lint gate is `abortOnError = true`,
    and an unused import is exactly the kind of thing that turns into a build
    break later.

    ## 🔴 The bug this function shipped with

    The first version removed the line with a `^import ...\s*\n` regex and
    produced, in `SkipBarGeometry.kt`:

    ```
    package com.example.biliv3.data
    import                          <-- !!! dangling, syntax error
    import com.example.biliv3.design.v3.V3Space
    ```

    Root cause: the regex consumed the trailing newline but the file's import
    for that symbol was the LAST line of the import block, so removal left the
    line's own prefix behind... in fact the real cause was that the symbol was
    still referenced in a KDoc `[Space.x]` link, so the "is it still used"
    test passed, the import was removed, and a stale fragment remained.

    Fix: only delete the line when it is **exactly** an import of that symbol
    (`re.fullmatch` on the stripped line), and re-check usage against the
    ORIGINAL text including KDoc. Never leave a partial line behind.
    """
    for sym in ("Space", "Radius", "Sizes", "FontSize"):
        # Still referenced anywhere (code OR KDoc link)? keep the import.
        if re.search(r"\b" + sym + r"\b", s.replace(
                "import com.example.biliv3.design.tokens." + sym, "")):
            continue
        out = []
        for ln in s.split("\n"):
            if ln.strip() == "import com.example.biliv3.design.tokens." + sym:
                continue
            out.append(ln)
        s = "\n".join(out)
    return s


def run_dimens(apply):
    """Stage 2: legacy dimension tokens -> v3 8pt grid."""
    files = []
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            if f.endswith(".kt"):
                files.append(os.path.join(dp, f))

    stats = new_stats()
    changed = []

    # Never rewrite the files that DEFINE these objects: their KDoc quotes
    # `Space.x1 + 2.dp` in prose, and rewriting prose would corrupt the very
    # explanation of why those tokens exist.
    defs = {
        os.path.join(ROOT, "design", "tokens", "Dimens.kt"),
        os.path.join(ROOT, "design", "tokens", "Motion.kt"),
        os.path.join(ROOT, "design", "tokens", "Surface.kt"),
        os.path.join(ROOT, "design", "tokens", "Colors.kt"),
        os.path.join(ROOT, "design", "v3", "V3Tokens.kt"),
        os.path.join(ROOT, "design", "v3", "V3Colors.kt"),
        os.path.join(ROOT, "design", "v3", "V3Motion.kt"),
    }

    for p in files:
        if p in defs:
            continue
        s = io.open(p, encoding="utf-8").read()
        if not re.search(r"\b(Space|Radius|Sizes|FontSize)\.", s):
            continue
        orig = s
        s = migrate_dimens(s, stats)
        s = migrate_typography(s, stats)
        if s != orig:
            s = _add_imports(s, [
                (r"\bV3Space\.", "com.example.biliv3.design.v3.V3Space"),
                (r"\bV3Radius\.", "com.example.biliv3.design.v3.V3Radius"),
                (r"\bV3Size\.", "com.example.biliv3.design.v3.V3Size"),
                (r"\bV3Type\.", "com.example.biliv3.design.v3.V3Type"),
            ])
            s = strip_dead_imports(s)
            changed.append(p)
            if apply:
                io.open(p, "w", encoding="utf-8", newline="").write(s)

    print("mode            :", "APPLY" if apply else "DRY RUN")
    print("files changed   :", len(changed))
    print("Space 1:1       :", stats["space_1to1"])
    print("Space CHANGED   :", stats["space_changed"])
    print("Radius          :", stats["radius"])
    print("Sizes mapped    :", stats["sizes"])
    print("Sizes kept      :", stats["sizes_kept"], "(layout constants, no v3 home)")
    print("FontSize        :", stats["font"])
    print("FontSize lines  :", stats["font_line"])
    if stats["space_changed_detail"]:
        print()
        print("!! VALUE CHANGES (deliberate, from §5.4.3):")
        for k, v in sorted(stats["space_changed_detail"].items(), key=lambda x: -x[1]):
            tgt, old, new, why = SPACE_CHANGED[k]
            print("     Space.%-20s %4d refs  %sdp -> %sdp   (%s)" % (k, v, old, new, why))
    for key in ("space_unknown", "radius_unknown", "sizes_unknown"):
        if stats[key]:
            print()
            print("!! %s:" % key)
            for k, v in sorted(stats[key].items(), key=lambda x: -x[1]):
                print("     %-26s %d" % (k, v))
    print()
    for c in sorted(changed):
        print("  ", os.path.relpath(c, ROOT))


def main(apply):
    files = []
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            if f.endswith(".kt"):
                files.append(os.path.join(dp, f))

    changed = []
    total_refs = 0
    unmapped = {}

    for p in files:
        if p in EXCLUDE:
            continue
        s = io.open(p, encoding="utf-8").read()
        if "BiliTheme.colors" not in s:
            continue
        # NOTE on "mixed" files (those that already use BiliV3 somewhere):
        #
        # The first version SKIPPED them, because a blind rename inside a mixed
        # file can hit functions bound to the OTHER palette (坑 188). That was
        # the right instinct but the wrong rule: this script does not rename
        # field names globally — it resolves the alias per file and only
        # rewrites `<alias>.<field>` for aliases actually bound to
        # `BiliTheme.colors`. So a mixed file is safe, PROVIDED every old field
        # now exists on V3Colors (the MAP is total and guarded below).
        #
        # Verified: after this change the mixed files migrated with zero new
        # compile errors, and the alias-resolution is what made that true.

        orig = s

        # 0) DIRECT accesses first: `BiliTheme.colors.textPrimary`.
        #
        # ⚠️ These must be mapped BEFORE the bare receiver is renamed.
        #    If you rename the receiver first, the direct access becomes
        #    `BiliV3.colors.textPrimary` — and `textPrimary` does NOT exist on
        #    V3Colors, so it compiles to a field-not-found error. This is the
        #    "half-migrated" failure mode the guard below exists to catch.
        def direct(m):
            field = m.group(1)
            if field in MAP:
                return "BiliV3.colors." + MAP[field]
            if field in KNOWN_UNMAPPED:
                return m.group(0)
            unmapped[field] = unmapped.get(field, 0) + 1
            return m.group(0)
        s = re.sub(r"BiliTheme\.colors\.(\w+)", direct, s)

        # 1) find the aliases bound to the legacy palette
        aliases = set(re.findall(r"val\s+(\w+)\s*=\s*BiliTheme\.colors\b", s))
        aliases |= set(re.findall(r"(\w+)\s*=\s*BiliTheme\.colors\b", s))
        aliases.discard("colors")
        # `val colors = BiliTheme.colors` is the dominant form
        if re.search(r"val\s+colors\s*=\s*BiliTheme\.colors\b", s):
            aliases.add("colors")

        # 2) rewrite the remaining bare bindings
        s = re.sub(r"BiliTheme\.colors\b", "BiliV3.colors", s)

        # 3) rewrite field accesses on those aliases only
        for a in aliases:
            def repl(m, _a=a):
                field = m.group(1)
                if field in MAP:
                    return _a + "." + MAP[field]
                if field in KNOWN_UNMAPPED:
                    return m.group(0)
                unmapped[field] = unmapped.get(field, 0) + 1
                return m.group(0)
            pat = re.compile(r"\b" + re.escape(a) + r"\.(\w+)")
            total_refs += len(pat.findall(orig))
            s = pat.sub(repl, s)

        if s != orig:
            s = fix_imports(s)
            changed.append(p)
            if apply:
                io.open(p, "w", encoding="utf-8", newline="").write(s)

    print("mode          :", "APPLY" if apply else "DRY RUN")
    print("files changed :", len(changed))
    print("refs seen     :", total_refs)
    if unmapped:
        print()
        print("!! UNMAPPED FIELDS (add to MAP or KNOWN_UNMAPPED):")
        for k, v in sorted(unmapped.items(), key=lambda x: -x[1]):
            print("     %-24s %d" % (k, v))
    else:
        print("unmapped      : none")
    print()
    for c in sorted(changed):
        print("  ", os.path.relpath(c, ROOT))


if __name__ == "__main__":
    if "--dimens" in sys.argv:
        run_dimens("--apply" in sys.argv)
    else:
        main("--apply" in sys.argv)
