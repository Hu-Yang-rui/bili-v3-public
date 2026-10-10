# -*- coding: utf-8 -*-
"""
Dead-import scan for the BiliV3 Kotlin sources.

## 🔴 Why the naive version gave 364 false positives

The first version stripped block comments with:

    re.sub(r"/\\*.*?\\*/", "", s, flags=re.S)

That is wrong for Kotlin, because **Kotlin block comments NEST**. The project
documents this pitfall in its own prompt volumes (「Kotlin 块注释会嵌套 ——
KDoc 里写 `home/*` 会开启嵌套注释」). A KDoc that mentions a path glob like
`x/web-interface/*` opens a nested comment, so the naive regex matches from
that inner `/*` to the next `*/` — which can be hundreds of lines later —
**eating real code**. The eaten code then looks "unused", and every import it
referenced gets reported as dead.

`MainShell.kt` reported ~40 dead imports this way, including screens it
obviously uses. That is the tell.

## The fix: a real scanner

Track block-comment depth with a small state machine so nesting is handled,
and skip string literals so a `/*` inside a string does not open a comment.
"""

import io
import os
import re
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3"

# Used via Kotlin syntax sugar (`by`), never referenced by name.
SUGAR = {"getValue", "setValue", "provideDelegate"}


def strip_code(src):
    """
    Remove comments (nesting-aware) and string/char literals.

    ## 🔴 Kotlin STRING TEMPLATES contain real code

    A naive "blank out everything between quotes" pass is WRONG here:

        text = "${formatCount(fans)} 粉丝 · ${formatCount(followingCount)} 关注"

    `${...}` is executable code that references `formatCount`. Blanking it
    makes the symbol look unused, and the fixer then deletes a live import —
    which fails to compile.

    > This is exactly what happened: the first version deleted
    > `import com.example.biliv3.data.model.formatCount` from 6 files
    > (AicuScreen / LiveScreen / RankingScreen / SpaceScreen / …).
    > Compile caught it — but only because the caller compiles.

    So: inside a string, keep the contents of `${ ... }` (including nested
    braces) as code, and blank only the literal text around it.

    ## 🔴 Kotlin block comments NEST

    The project documents this pitfall in its own prompt volumes
    (「Kotlin 块注释会嵌套 —— KDoc 里写 `home/*` 会开启嵌套注释」).
    A KDoc that mentions a path glob like `x/web-interface/*` opens a nested
    comment, so a non-greedy `/* ... */` regex matches from that inner `/*`
    to the next `*/` — potentially hundreds of lines later — **eating real
    code**. The eaten code then looks "unused".

    > This produced **364 false positives**, including ~40 in `MainShell.kt`
    > for screens it obviously uses.

    So: track block-comment depth with a state machine.
    """
    out = []
    i = 0
    n = len(src)
    depth = 0          # block-comment nesting depth

    def emit(s):
        out.append(s)

    while i < n:
        c = src[i]

        # ---- inside a block comment ----
        if depth > 0:
            if src.startswith("/*", i):
                depth += 1
                i += 2
                continue
            if src.startswith("*/", i):
                depth -= 1
                i += 2
                continue
            emit("\n" if c == "\n" else " ")
            i += 1
            continue

        # ---- line comment ----
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
            continue

        # ---- block comment start ----
        if src.startswith("/*", i):
            depth = 1
            i += 2
            continue

        # ---- raw string  """..."""  (no interpolation handling: raw strings
        #      in this codebase are never used for symbol references) ----
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                emit("\n" if src[i] == "\n" else " ")
                i += 1
            i += 3
            continue

        # ---- normal string, WITH string-template awareness ----
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    # escaped char: blank both
                    emit(" ")
                    i += 2
                    continue
                if src.startswith("${", i):
                    # keep the template expression as CODE
                    emit(" ")          # the '$'
                    emit(" ")          # the '{'
                    i += 2
                    depth_brace = 1
                    while i < n and depth_brace > 0:
                        ch = src[i]
                        if ch == "{":
                            depth_brace += 1
                        elif ch == "}":
                            depth_brace -= 1
                            if depth_brace == 0:
                                emit(" ")
                                i += 1
                                break
                        emit(ch)
                        i += 1
                    continue
                if src[i] == "$":
                    # simple `$name` interpolation — keep the name as code
                    emit(" ")
                    i += 1
                    while i < n and (src[i].isalnum() or src[i] == "_"):
                        emit(src[i])
                        i += 1
                    continue
                emit("\n" if src[i] == "\n" else " ")
                i += 1
            i += 1
            continue

        # ---- char literal ----
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue

        emit(c)
        i += 1

    return "".join(out)


def main():
    files = []
    for dp, _dn, fn in os.walk(ROOT):
        for f in fn:
            if f.endswith(".kt"):
                files.append(os.path.join(dp, f))

    total = 0
    per_file = {}

    for p in files:
        raw = io.open(p, encoding="utf-8").read()
        imports = re.findall(r"^import\s+([\w.]+)\s*$", raw, re.M)
        if not imports:
            continue

        code = strip_code(raw)
        # drop the import lines themselves from the "is it used" haystack
        code = re.sub(r"^import\s+[\w.]+[^\n]*$", "", code, flags=re.M)

        dead = []
        for imp in imports:
            sym = imp.split(".")[-1]
            if sym in SUGAR or sym == "*":
                continue
            if not re.search(r"\b" + re.escape(sym) + r"\b", code):
                dead.append(imp)

        if dead:
            rel = os.path.relpath(p, ROOT)
            per_file[rel] = dead
            total += len(dead)

    print("mode      :", "DRY RUN (report only)")
    print("files     :", len(per_file))
    print("dead imports:", total)
    print()
    for rel in sorted(per_file, key=lambda k: -len(per_file[k])):
        print("  %s  (%d)" % (rel, len(per_file[rel])))
        for d in per_file[rel]:
            print("      ", d)


if __name__ == "__main__":
    main()
