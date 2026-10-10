# -*- coding: utf-8 -*-
"""
Collapse the SECOND form of `MaterialTheme.typography.X.copy(...)`.

## Why there are two forms

The first collapse pass (`collapse-typography.py`) handled call sites that
carried an explicit size override:

    MaterialTheme.typography.bodySmall.copy(fontSize = V3Type.footnote.fontSize, ...)
    -> V3Type.footnote.copy(...)

But it required a `fontSize = V3Type.*` argument to recognise the pattern.
That left a second form untouched — one that only overrides colour/font:

    MaterialTheme.typography.bodyMedium.copy(color = colors.labelSecondary)
    -> V3Type.callout.copy(color = colors.labelSecondary)

30 of these survived. They are *not* harmless: they still route through the
Material3 slot, and the M3 slot is only an **approximation** of a v3 style.
Worse, the slot NAME lies about the intent (`bodySmall` maps to
`V3Type.subheadline`, i.e. 15sp — larger than `bodyMedium`/`callout` at 16sp?
No: subheadline is 15, callout is 16 — the M3 names do not track v3 order).

## The mapping

Taken verbatim from `V3Typography` in `V3Theme.kt` — the single source of
truth for how M3 slots map onto v3 semantic styles:

    displayLarge  -> largeTitle      titleLarge   -> title3
    displayMedium -> title1          titleMedium  -> headline
    displaySmall  -> title2          titleSmall   -> subheadline
    headlineLarge -> title2          bodyLarge    -> body
    headlineMedium-> title3          bodyMedium   -> callout
    headlineSmall -> headline        bodySmall    -> subheadline
    labelLarge    -> headline        labelMedium  -> footnote
    labelSmall    -> caption1

⚠️ Note `bodySmall -> subheadline` (15sp) but `bodyMedium -> callout` (16sp):
the M3 ordering is NOT preserved. Reading the slot name and guessing the size
gives the wrong answer — which is exactly why the project's own V3Theme KDoc
says "页面代码请直接用 V3Type.xxx，不要走 MaterialTheme.typography".

## Safety

Only rewrites when the call is exactly `MaterialTheme.typography.<known slot>.copy(`.
An unknown slot is reported and left alone.
"""

import io
import os
import re
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3"

# Verbatim from V3Typography (V3Theme.kt) — do not invent entries here.
SLOT_MAP = {
    "displayLarge": "largeTitle",
    "displayMedium": "title1",
    "displaySmall": "title2",
    "headlineLarge": "title2",
    "headlineMedium": "title3",
    "headlineSmall": "headline",
    "titleLarge": "title3",
    "titleMedium": "headline",
    "titleSmall": "subheadline",
    "bodyLarge": "body",
    "bodyMedium": "callout",
    "bodySmall": "subheadline",
    "labelLarge": "headline",
    "labelMedium": "footnote",
    "labelSmall": "caption1",
}

PAT = re.compile(r"MaterialTheme\.typography\.(\w+)\.copy\(")

# Files that DEFINE or MENTION the mapping — never rewrite.
EXCLUDE = {
    os.path.join(ROOT, "design", "v3", "V3Theme.kt"),
    os.path.join(ROOT, "design", "v3", "V3Kit.kt"),
}


def main(apply):
    files = []
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            if f.endswith(".kt"):
                files.append(os.path.join(dp, f))

    changed = []
    total = 0
    unknown = {}

    for p in files:
        if p in EXCLUDE:
            continue
        s = io.open(p, encoding="utf-8").read()
        if "MaterialTheme.typography" not in s:
            continue

        # Never touch a mention inside a comment.
        parts = re.split(r"(//[^\n]*|/\*.*?\*/)", s, flags=re.S)
        for i in range(0, len(parts), 2):   # even indices = real code
            def repl(m):
                slot = m.group(1)
                if slot in SLOT_MAP:
                    return "V3Type." + SLOT_MAP[slot] + ".copy("
                unknown[slot] = unknown.get(slot, 0) + 1
                return m.group(0)
            parts[i], n = PAT.subn(repl, parts[i])
            total += n
        new = "".join(parts)

        if new != s:
            # make sure V3Type is imported
            if not re.search(r"^import\s+com\.example\.biliv3\.design\.v3\.V3Type\s*$", new, re.M):
                lines = new.split("\n")
                last = -1
                for j, l in enumerate(lines):
                    if re.match(r"\s*import\s+", l):
                        last = j
                if last >= 0:
                    lines = lines[: last + 1] + [
                        "import com.example.biliv3.design.v3.V3Type"
                    ] + lines[last + 1 :]
                    new = "\n".join(lines)
            changed.append(p)
            if apply:
                io.open(p, "w", encoding="utf-8", newline="").write(new)

    print("mode          :", "APPLY" if apply else "DRY RUN")
    print("files changed :", len(changed))
    print("slots collapsed:", total)
    if unknown:
        print()
        print("!! UNKNOWN SLOTS (not in V3Typography) — left alone:")
        for k, v in sorted(unknown.items(), key=lambda x: -x[1]):
            print("     %-20s %d" % (k, v))
    print()
    for c in sorted(changed):
        print("  ", os.path.relpath(c, ROOT))


if __name__ == "__main__":
    main("--apply" in sys.argv)
