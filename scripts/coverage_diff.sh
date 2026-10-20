#!/usr/bin/env bash
# Show coverage of just the lines added or modified in a diff.
#
# Whole-file percentages hide the change you actually shipped: a 100%-line
# file with a single uncovered addition still reads as 100%. This script
# runs cloverage, then runs scripts/coverage_diff.py to read the text
# report, intersect with the diff's added lines, and print the rows.
#
# Why the text report, not the HTML: cloverage's --text output is two
# files on disk (coverage.txt summary plus per-line file markers),
# both trivially line-parseable. The HTML needs class-name aware scraping
# and the marker color lives in CSS.
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

# The namespace a source file declares, read from its ns form. The file
# name is not the namespace: this repo spells a namespace with hyphens
# in a file named with underscores (download_supervisor.clj holds
# dev.cljtoc.supervision.download-supervisor), and cloverage selects
# namespaces by the name the code declares, so the path-derived name
# selects nothing. Falls back to the path when the file declares no ns.
ns_of() {
  local file="$1"
  local declared
  declared=$(sed -nE 's/^\(ns[[:space:]]+([A-Za-z0-9._-]+).*/\1/p' "$file" | head -n 1)
  if [[ -n "$declared" ]]; then
    echo "$declared"
  else
    touch_target "$file"
  fi
}

ns_flags=()
for f in "${files[@]}"; do
  ns_flags+=(-n "$(ns_of "$f")")
done

target_dir="target/coverage-diff"
rm -rf "$target_dir"

# `--text` enables both the text report (per-line markers + summary,
# what the diff intersection reads) and the HTML report (what a human
# opens in a browser to navigate the file tree and see uncovered lines
# in context). target/ is gitignored, so the HTML is local-only. Disable
# every other report writer so we don't churn the tree or hit the network.
# Send cloverage's test runner chatter to stderr so only the diff rows
# end up on stdout.
if ! lein cloverage \
    --text --summary \
    --no-coveralls --no-codecov --no-emma-xml --no-lcov --no-junit --no-raw \
    --no-colorize \
    -o "$target_dir" \
    "${ns_flags[@]}" \
    "${ns_args[@]}" \
    --fail-threshold 0 \
    "${extra[@]}" 1>&2; then
  echo "cloverage failed" >&2
  exit 2
fi

python3 "$(dirname "$0")/coverage_diff.py" --dir "$target_dir" --base "$base"
