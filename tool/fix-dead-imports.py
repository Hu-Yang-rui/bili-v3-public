# -*- coding: utf-8 -*-
"""
Remove dead imports found by `scan-dead-imports.py`.

## Safety model

Removing imports is *mostly* mechanical but has two traps this project has hit
before, so the script guards both:

1. **Kotlin block comments nest.** The scanner that feeds this script is
   nesting-aware; do not swap in a regex-based stripper (see the scanner's
   docstring for the 364-false-positive incident).

2. **`by` delegates are used without naming the symbol.** `getValue` /
   `setValue` / `provideDelegate` never appear by name, so they are whitelisted
   in the scanner and never removed here.

After rewriting, this script does NOT claim success on its own — the caller
must compile. A removed import that was actually needed fails loudly at
compile time (unresolved reference), which is the check that matters.
"""

import io
import os
import re
import subprocess
import sys

ROOT = r"D:\deep\bili-v3\app\src\main\java\com\example\biliv3"
SCANNER = r"D:\deep\bili-v3\tool\scan-dead-imports.py"


def load_dead():
    """Run the scanner and parse its report into {abs_path: [fqcn, ...]}."""
    out = subprocess.run(
        [sys.executable, SCANNER], capture_output=True, text=True,
        encoding="utf-8", cwd=r"D:\deep\bili-v3",
    ).stdout

    dead = {}
    cur = None
    for line in out.splitlines():
        # File header: two-space indent, path, then "  (N)".
        m = re.match(r"^  (\S.*\.kt)\s+\((\d+)\)$", line)
        if m:
            cur = os.path.join(ROOT, m.group(1))
            dead[cur] = []
            continue
        # Import line: SEVEN-space indent (the scanner prints "      " + " ").
        # ⚠️ The first version of this parser used six spaces and therefore
        #    matched 84 files but ZERO imports — it silently reported success
        #    while removing nothing. Always print the raw repr of the source
        #    format before trusting a parser (that is how this was caught).
        m2 = re.match(r"^\s{6,}(\S+)$", line)
        if m2 and cur:
            dead[cur].append(m2.group(1))
    return dead


def main(apply):
    dead = load_dead()
    total = sum(len(v) for v in dead.values())
    print("files with dead imports :", len(dead))
    print("total dead imports      :", total)

    if not apply:
        print("\n(dry run — nothing written)")
        return

    removed = 0
    touched = []
    for path, imps in dead.items():
        src = io.open(path, encoding="utf-8").read()
        lines = src.split("\n")
        keep = []
        for ln in lines:
            m = re.match(r"^import\s+([\w.]+)\s*$", ln)
            if m and m.group(1) in imps:
                removed += 1
                continue
            keep.append(ln)
        new = "\n".join(keep)
        if new != src:
            io.open(path, "w", encoding="utf-8", newline="").write(new)
            touched.append(os.path.relpath(path, ROOT))

    print("removed :", removed)
    print("files   :", len(touched))
    for t in sorted(touched):
        print("   ", t)


if __name__ == "__main__":
    main("--apply" in sys.argv)
