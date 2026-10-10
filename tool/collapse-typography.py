# -*- coding: utf-8 -*-
"""
Collapse `MaterialTheme.typography.X.copy(fontSize = V3Type.Y.fontSize, ...)`
into `V3Type.Y.copy(...)`.

## Why this exists

The token migration (tool/migrate-v3.py) rewrote `FontSize.label` into
`V3Type.caption1.fontSize` — a type-correct but *verbose* form. It left 436
call sites of the shape:

    style = MaterialTheme.typography.bodySmall.copy(
        fontSize = V3Type.footnote.fontSize,
        lineHeight = V3Type.footnote.lineHeight,
        color = colors.labelSecondary,
    )

That is now **self-contradictory**: it asks Material3 for a style and then
overrides every meaningful field of it from V3Type. The M3 slot
(`bodySmall`) contributes nothing, and its NAME lies about the intent
(`bodySmall` reads as "small body text", but the actual size comes from
`V3Type.footnote`).

The v3 design system is explicit about this (V3Theme.kt):

> 页面代码请直接用 `V3Type.xxx`，不要走 `MaterialTheme.typography` ——
> 后者的槽位名（`bodyMedium`）读不出语义，正是旧系统"到处随手挑一个"的原因。

## 🔴 The safety rule

Only collapse when the `fontSize` (and optional `lineHeight`) reference the
**same** V3Type token. If a call site mixes tokens — e.g.
`fontSize = V3Type.callout.fontSize` with
`lineHeight = V3Type.body.lineHeight` — the two are NOT redundant and
collapsing would silently change the line height.

Such mixed cases are left untouched and reported, so they get a human look
instead of a guess.
"""

import io
import os
import re
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3"

# Matches the whole `MaterialTheme.typography.<slot>.copy(` head, capturing the
# first two arguments so they can be compared.
HEAD = re.compile(
    r"MaterialTheme\.typography\.\w+\.copy\(\s*\n"
    r"\s*fontSize\s*=\s*V3Type\.(\w+)\.fontSize\s*,\s*\n"
    r"(?:\s*lineHeight\s*=\s*V3Type\.(\w+)\.lineHeight\s*,\s*\n)?"
)

# The single-line variant: `...copy(fontSize = V3Type.footnote.fontSize, color = ...)`
HEAD_ONELINE = re.compile(
    r"MaterialTheme\.typography\.\w+\.copy\(\s*"
    r"fontSize\s*=\s*V3Type\.(\w+)\.fontSize\s*,\s*"
)


def process(s):
    """Return (new_source, stats)."""
    stats = {"collapsed": 0, "mixed": 0, "oneline": 0}

    def repl(m):
        size_tok = m.group(1)
        line_tok = m.group(2)
        if line_tok is not None and line_tok != size_tok:
            # NOT redundant — leave alone, report.
            stats["mixed"] += 1
            return m.group(0)
        stats["collapsed"] += 1
        return "V3Type." + size_tok + ".copy(\n"

    s = HEAD.sub(repl, s)

    def repl_one(m):
        stats["oneline"] += 1
        return "V3Type." + m.group(1) + ".copy("

    s = HEAD_ONELINE.sub(repl_one, s)
    return s, stats


def main(apply):
    files = []
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            if f.endswith(".kt"):
                files.append(os.path.join(dp, f))

    total = {"collapsed": 0, "mixed": 0, "oneline": 0}
    changed = []
    mixed_files = {}

    for p in files:
        s = io.open(p, encoding="utf-8").read()
        if "MaterialTheme.typography" not in s:
            continue
        new, st = process(s)
        for k in total:
            total[k] += st[k]
        if st["mixed"]:
            mixed_files[os.path.relpath(p, ROOT)] = st["mixed"]
        if new != s:
            changed.append(p)
            if apply:
                io.open(p, "w", encoding="utf-8", newline="").write(new)

    print("mode            :", "APPLY" if apply else "DRY RUN")
    print("files changed   :", len(changed))
    print("collapsed       :", total["collapsed"])
    print("collapsed(1line):", total["oneline"])
    if mixed_files:
        print()
        print("!! MIXED fontSize/lineHeight — left untouched, review by hand:")
        for k, v in sorted(mixed_files.items(), key=lambda x: -x[1]):
            print("     %-46s %d" % (k, v))
    print()
    for c in sorted(changed):
        print("  ", os.path.relpath(c, ROOT))


if __name__ == "__main__":
    main("--apply" in sys.argv)
