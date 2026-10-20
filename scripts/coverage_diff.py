#!/usr/bin/env python3
"""
Read cloverage's text report under --dir and print only the lines in
the diff-added set (against --base), with the cover marker (✔/✘/~/?).
Exit 1 if any added line is uncovered, 0 otherwise, 2 on usage error.

Why this is python and not bash: the bash version had three independent
bugs (file-descriptor lifetime, multibyte substring vs byte position, and
a sentinel-through-pipe protocol that the second-stage awk never received
correctly). Python reads the files directly — the bug surface drops to
parse-lines-and-compare-bytes.

Marker legend (column 1 of each per-line file in the cloverage text
report, which is 1:1 with the source):
  ✔ (U+2714) covered
  ✘ (U+2718) uncovered, fail count
  ~         partial, ignored
  ?         not tracked (docstring, data, etc.)
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

# cloverage per-line file columns are 1:1 with the source: column 1 is
# the marker byte, the rest is the source line. The diff hunk body is
# traversed manually below (see compute_added).
ADDED_LINE_RE = re.compile(r"^\+([^+])")

# Marker bytes (raw UTF-8 encodings) and their expected lengths.
# `?` and `~` are 1 byte, `✔` and `✘` are 3 bytes each.
MARKERS = [
    (3, b"\xe2\x9c\x94", "covered"),
    (3, b"\xe2\x9c\x98", "uncovered"),
    (1, b"~",            "partial"),
    (1, b"?",            "not-tracked"),
    (1, b" ",            "blank"),
]


def compute_added(src: str, base: str) -> set[int]:
    """Return the set of source line numbers that are ADDED by the diff
    between `base` and HEAD, restricted to `src`.

    Pure-bash hunk arithmetic mishandles `@@ -a,b +c,d` when b==0
    (the "new file" form), so we let Python walk the diff."""
    out = subprocess.run(
        ["git", "diff", "--unified=0", f"{base}...HEAD", "--", src],
        capture_output=True, text=True, check=True,
    )
    added: set[int] = set()
    ln = 0
    for line in out.stdout.splitlines():
        if line.startswith("@@ "):
            m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
            if m:
                ln = int(m.group(1))
            continue
        if not line or line.startswith("+++") or line.startswith("---"):
            continue
        sig = line[0]
        if sig == "+":
            added.add(ln)
        if sig in "+-":
            ln += 1
    return added


def marker_for(raw: bytes) -> tuple[str, bool]:
    """Return (decoded marker char, is_uncovered). If no known marker
    matches the leading bytes, return (' ', False) (blank/untracked)."""
    for size, prefix, _name in MARKERS:
        if raw[:size] == prefix:
            return (prefix.decode("utf-8"), prefix == b"\xe2\x9c\x98")
    return (" ", False)


def intersect(per_line_file: Path, added: set[int]) -> tuple[list[tuple[int, str, str]], int]:
    """Walk the per-line file, return (rows in the diff set, total uncovered).

    The per-line file is 1:1 with the source: physical line index in this
    file equals the source line number. The marker is the first byte of
    each row, decoded to its full UTF-8 character."""
    rows: list[tuple[int, str, str]] = []
    uncovered = 0
    if not per_line_file.exists():
        return rows, uncovered
    with per_line_file.open("rb") as f:
        for idx, raw in enumerate(f, start=1):
            if idx not in added:
                continue
            if not raw:
                continue
            marker, is_uncovered = marker_for(raw)
            text = raw[1:].decode("utf-8", errors="replace").rstrip("\n")
            if is_uncovered:
                uncovered += 1
            rows.append((idx, marker, text))
    return rows, uncovered


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--dir", required=True, type=Path,
                   help="cloverage output directory (target/coverage-diff)")
    p.add_argument("--base", default="main", help="git base ref")
    args = p.parse_args()

    cov_dir: Path = args.dir
    if not cov_dir.is_dir():
        print(f"no coverage dir at {cov_dir}", file=sys.stderr)
        return 2

    base = args.base
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD", "--", "src/**/*.clj"],
        capture_output=True, text=True, check=True,
    )
    files = sorted({Path(l) for l in out.stdout.splitlines() if l})
    if not files:
        print(f"no src/ files changed against {base}", file=sys.stderr)
        return 2

    overall_uncovered = 0
    setup_errors: list[str] = []
    for src in files:
        rel = str(src).removeprefix("src/")
        added = compute_added(str(src), base)
        if not added:
            continue
        cov_file = cov_dir / rel
        if not cov_file.exists():
            # The namespace was not instrumented, so the diff lines in
            # this file were never measured. Reporting them as covered
            # would make the gate pass vacuously, so this is a setup
            # error instead.
            setup_errors.append(rel)
            continue
        rows, uncovered = intersect(cov_file, added)
        overall_uncovered += uncovered
        for ln, marker, text in sorted(rows, key=lambda r: r[0]):
            print(f"{rel}\t{ln}\t{marker} {text}")

    if setup_errors:
        print(
            "\nnamespace(s) not instrumented, so their changed lines were "
            "never measured: " + ", ".join(setup_errors),
            file=sys.stderr,
        )
        return 2
    if overall_uncovered > 0:
        print(f"\n{overall_uncovered} uncovered added line(s) across the diff", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
