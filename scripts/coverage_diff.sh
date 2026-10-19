#!/usr/bin/env bash
# Show coverage of just the lines added or modified in a diff.
#
# Whole-file percentages hide the change you actually shipped: a 100%-line
# file with a single uncovered addition still reads as 100%. This script
# runs cloverage, reads its text report (no HTML to parse), and prints
# only the per-line rows that sit in the diff's added set, with the
# cover marker (✔/✘/~/?). Catches the regression class the table misses.
#
# Why the text report, not the HTML one: cloverage's --text output is two
# files on disk (coverage.txt summary plus the source with line markers),
# both trivially line-parseable; the HTML one needs a class-name aware
# scraper and the marker color in CSS.
#
# Format of the per-line file is `marker source_text` (the marker is
# column 1, the rest is the source line). The file is 1:1 with the
# source, so physical line index in the per-line file = source line
# number. Diff `+` hunks give us those source line numbers.
#
# Usage: scripts/coverage_diff.sh [--base REF] [--ns REGEX]... [--]
#                       [-- lein-cloverage-args]
#   Default base: main (the merge-base against HEAD).
#   --ns REGEX: pass through to lein cloverage -n (can be repeated).
# Exit codes: 0 = no uncovered added lines, 1 = uncovered added lines
#             found, 2 = usage/setup error.

set -euo pipefail

base="main"
ns_args=()
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --ns)   ns_args+=(-n "$2"); shift 2 ;;
    --)    shift; extra=("$@"); break ;;
    -h|--help)
      sed -n '2,/^# ---/p' "$0" | sed 's/^# \{0,1\}//' | head -n 30
      exit 0 ;;
    *)    echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

mapfile -t files < <(git diff --name-only "$base"...HEAD \
  -- 'src/**/*.clj' | sort -u)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "no src/ files changed against $base" >&2
  exit 2
fi

touch_target() {
  sed -E 's|^src/||; s|\.clj$||; s|/|.|g' <<<"$1"
}
ns_flags=()
for f in "${files[@]}"; do
  ns_flags+=(-n "$(touch_target "$f")")
done

target_dir="target/coverage-diff"
rm -rf "$target_dir"

# `--text` enables both the text report (per-line markers + summary,
# what the diff intersection reads) and the HTML report (what a human
# opens in a browser to navigate the file tree and see uncovered lines
# in context). target/ is gitignored, so the HTML is local-only. Disable
# every other report writer so we don't churn the tree or hit the network.
if ! lein cloverage \
    --text --summary \
    --no-coveralls --no-codecov --no-emma-xml --no-lcov --no-junit --no-raw \
    --no-colorize \
    -o "$target_dir" \
    "${ns_flags[@]}" \
    --fail-threshold 0 \
    "${extra[@]}"; then
  echo "cloverage failed" >&2
  exit 2
fi

report="$target_dir/coverage.txt"
if [[ ! -f "$report" ]]; then
  echo "no report at $report" >&2
  exit 2
fi

# Compute the set of added line numbers per file. Pure bash hunk
# arithmetic mishandles @@ -a,b +c,d when b==0 (the "new file" form),
# so we let Python do the +N traversal.
compute_added() {
  git diff --unified=0 "$base"...HEAD -- "$1" \
    | python3 -c "
import sys, re
ln = 0
out = []
for L in sys.stdin.read().splitlines():
    if L.startswith('@@ '):
        m = re.match(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@', L)
        ln = int(m.group(1)) if m else 0
        continue
    if not L or L.startswith('+++') or L.startswith('---'):
        continue
    sig = L[0]
    if sig == '+':
        out.append(ln)
    if sig in '+-':
        ln += 1
print(' '.join(str(x) for x in out))
"
}

# Output: "<rel>\t<line>\t<marker> <source>". The marker is column 1 of
# the per-line file (1:1 with the source, so physical line index =
# source line number). Use a sidecar file for the per-file ✘ count so
# the awk can also tally without polluting the row stream.
#
# Marker legend (column 1 of the cloverage text file):
#   ✘ (U+2718) uncovered, fail count
#   ~           partial, ignored — not a regression
#   ✔ (U+2714) covered
#   ?           not tracked (docstring, data, etc.) — not a regression
#   <space>     blank
counts=$(mktemp)
# FD 3 writes per-file ✘ totals, separate from stdout.
exec 3> "$counts"
for src in "${files[@]}"; do
  rel="${src#src/}"
  cov="$target_dir/$rel"
  if [[ ! -f "$cov" ]]; then
    printf '%s\t(no per-line file — namespace was not instrumented)\n' "$rel"
    continue
  fi
  added_nums=$(compute_added "$src")
  [[ -z "$added_nums" ]] && continue

  awk -v added="$added_nums" -v rel="$rel" '
    BEGIN { n = split(added, a, " "); for (i=1; i<=n; i++) want[a[i]] = 1 }
    {
      ln = NR
      if (!(ln in want)) next
      printf("%s\t%d\t%s\n", rel, ln, $0)
      if (substr($0,1,1) == "\xe2\x9c\x98") c++
    }
    END { print c+0 > "/dev/fd/3" }
  ' "$cov"
done | sort -t$'\t' -k1,1 -k2,2n
exec 3>&-

fails=$(awk '{s+=$1}END{print s+0}' "$counts")
rm -f "$counts"

if [[ $fails -gt 0 ]]; then
  echo
  echo "$fails uncovered added line(s) across the diff" >&2
  exit 1
fi
exit 0
