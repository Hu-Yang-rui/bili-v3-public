# -*- coding: utf-8 -*-
"""
Wrap a sheet's panel in `GlassSurface` (v3 material) without hand-editing braces.

## Why a script

Hand-editing the opening/closing braces of a deeply nested Compose tree is
error-prone: I broke `PlayerSettingsSheet.kt` and `FavFolderSheet.kt` twice each
by adding a `{` on one line and miscounting the matching `}` far away
(700+ lines later). A compiler error 700 lines from the edit gives no clue.

So: do the wrap by exact-string replacement, then **verify brace balance
programmatically before writing**. If the result is unbalanced, write nothing
and report — the file stays in its known-good state.

## The transform

    Column(                      ->   GlassSurface(
        modifier = Modifier               modifier = Modifier
            .fillMaxWidth()                   .fillMaxWidth()
            ...                               ...
            .background(colors.bgSecondaryElevated)   <- dropped
        ) {                               shape = ..., level = ...
                                      ) {
                                      Column(
                                          modifier = Modifier.fillMaxWidth() ...
                                      ) {

i.e. the old panel's `Column` becomes the *content* of a new `GlassSurface`,
and the panel's own background/clip is dropped (GlassSurface draws the surface).

The closing side needs exactly ONE extra `}`. The script finds the right place
by locating the line that closes the enclosing `Box {` (the one that also
closes the `Dialog`), and inserts before it.
"""

import io
import os
import re
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3\ui\video"


def strip_comments_and_strings(src):
    s = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    s = re.sub(r'"(?:\\.|[^"\\])*"', '""', s)
    return s


def brace_depth(src):
    """Return (final_depth, first_negative_line)."""
    depth = 0
    neg = None
    for i, line in enumerate(strip_comments_and_strings(src).split("\n")):
        for ch in line:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth < 0 and neg is None:
                    neg = i + 1
    return depth, neg


def add_imports(src, syms):
    have = set(m.group(1) for m in re.finditer(r"^\s*import\s+([\w.]+)\s*$", src, re.M))
    add = [s for s in syms if s not in have]
    if not add:
        return src, []
    lines = src.split("\n")
    last = max(i for i, l in enumerate(lines) if re.match(r"\s*import\s+", l))
    lines = lines[: last + 1] + ["import " + n for n in add] + lines[last + 1 :]
    return "\n".join(lines), add


GLASS_IMPORTS = [
    "com.example.biliv3.design.v3.GlassSurface",
    "com.example.biliv3.design.v3.V3Glass",
]


def wrap(fname, open_old, open_new, close_anchor, apply):
    p = os.path.join(ROOT, fname)
    src = io.open(p, encoding="utf-8").read()

    d0, n0 = brace_depth(src)
    print("--- %s ---" % fname)
    print("  baseline depth=%d negative=%s" % (d0, n0))
    if d0 != 0 or n0 is not None:
        print("  !! baseline unbalanced, refusing to edit")
        return False

    if open_new in src:
        print("  already wrapped (marker present), nothing to do")
        return True

    if open_old not in src:
        print("  !! opening pattern not found")
        return False
    if close_anchor not in src:
        print("  !! closing anchor not found")
        return False

    s = src.replace(open_old, open_new, 1)
    # insert exactly ONE extra closing brace before the anchor
    s = s.replace(close_anchor, "}\n" + close_anchor, 1)

    d1, n1 = brace_depth(s)
    print("  after wrap depth=%d negative=%s" % (d1, n1))
    if d1 != 0 or n1 is not None:
        print("  !! WOULD BE UNBALANCED -> not writing")
        return False

    s, added = add_imports(s, GLASS_IMPORTS)
    print("  imports added: %s" % (added or "none"))
    if apply:
        io.open(p, "w", encoding="utf-8", newline="").write(s)
        print("  WRITTEN")
    else:
        print("  (dry run)")
    return True


# ---------------------------------------------------------------------------
# FavFolderSheet: bottom sheet, only top corners rounded
# ---------------------------------------------------------------------------
FAV_OPEN_OLD = """            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = V3Radius.lg, topEnd = V3Radius.lg))
                    .background(colors.bgSecondaryElevated)
                    .clickable(enabled = false) {}
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H),
            ) {"""

FAV_OPEN_NEW = """            // v3：面板材质从实心深灰改为 Liquid Glass（判据见 §7.37 坑 219）。
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = SHEET_MAX_H),
                shape = RoundedCornerShape(
                    topStart = V3Radius.sheet,
                    topEnd = V3Radius.sheet,
                ),
                level = V3Glass.Level.UltraThin,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {},
            ) {"""


AI_OPEN_OLD = """            Column(
                modifier = Modifier
                    .padding(horizontal = V3Space.md)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(V3Radius.lg))
                    // 弹层用 surfaceElevated（比卡片亮一档）—— 深色下分层靠提亮
                    .background(colors.bgSecondaryElevated)
                    .clickable(enabled = false) {}
                    .heightIn(max = MAX_SHEET_HEIGHT)
                    .padding(bottom = V3Space.sm),
            ) {"""

AI_OPEN_NEW = """            // v3：面板材质从实心深灰改为 Liquid Glass（判据见 §7.37 坑 219）。
            GlassSurface(
                modifier = Modifier
                    .padding(horizontal = V3Space.md)
                    .fillMaxWidth()
                    .heightIn(max = MAX_SHEET_HEIGHT),
                shape = RoundedCornerShape(V3Radius.sheet),
                level = V3Glass.Level.UltraThin,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
                    .padding(bottom = V3Space.sm),
            ) {"""


if __name__ == "__main__":
    apply = "--apply" in sys.argv
    ok = wrap(
        "FavFolderSheet.kt",
        FAV_OPEN_OLD,
        FAV_OPEN_NEW,
        # the line that closes the enclosing Box, right after Column's close
        "                Spacer(Modifier.height(V3Space.xs))\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "}",
        apply,
    )
    print()
    print("result:", "OK" if ok else "FAILED")
