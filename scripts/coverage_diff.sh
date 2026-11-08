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
# Usage: scripts/coverage_diff.sh [--base REF] [--ns REGEX]... [--test-ns REGEX]...
#                       [--all-tests] [--skip-run] [--]
#                       [-- lein-cloverage-args]
#   Default base: main (the merge-base against HEAD).
#   --ns REGEX: pass through to lein cloverage -n (can be repeated).
#   --test-ns REGEX: pass through to lein cloverage -t (can be repeated).
#   --all-tests: run every test namespace (the default runs only the test
#     namespaces mapped from the changed src files, so the gate finishes
#     instead of re-running the whole suite under instrumentation).
#   --skip-run: reuse the report already in target/coverage-diff instead
#     of running cloverage again (for re-reading the intersection).
# Exit codes: 0 = no uncovered added lines, 1 = uncovered added lines
#             found, 2 = usage/setup error.

set -euo pipefail

base="main"
ns_args=()
test_ns_args=()
narrow_tests=1
skip_run=0
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --ns)   ns_args+=(-n "$2"); shift 2 ;;
    --test-ns) test_ns_args+=(-t "$2"); narrow_tests=0; shift 2 ;;
    --all-tests) narrow_tests=0; shift ;;
    --skip-run) skip_run=1; shift ;;
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

# The namespace a source file declares, read from its ns form. The file
# name is not the namespace: this repo spells a namespace with hyphens
# in a file named with underscores (download_supervisor.clj holds
# dev.cljtoc.supervision.download-supervisor), and cloverage selects
# namespaces by the name the code declares, so a path-derived name
# selects nothing.
ns_of() {
  sed -nE 's/^\(ns[[:space:]]+([A-Za-z0-9._-]+).*/\1/p' "$1" | head -n 1
}

ns_flags=()
for f in "${files[@]}"; do
  ns=$(ns_of "$f")
  if [[ -n "$ns" ]]; then
    ns_flags+=(-n "$ns")
  fi
done

target_dir="target/coverage-diff"

# Default test selection: the test namespace convened for each changed
# src file (<src-ns>-test), when its test file exists. Running only these
# keeps the gate to the tests that can cover the diff instead of the
# whole suite under instrumentation. A line covered solely from another
# namespace then reports uncovered: widen with --test-ns or --all-tests
# (the failure note says so). Files with no convened test file force the
# full run — silently narrowing there would fail vacuously.
test_flags=()
if [[ $narrow_tests -eq 1 ]]; then
  unmapped=()
  for f in "${files[@]}"; do
    test_file="test/${f#src/}"
    test_file="${test_file%.clj}_test.clj"
    if [[ -f "$test_file" ]]; then
      test_ns=$(ns_of "$test_file")
      if [[ -n "$test_ns" ]]; then
        test_flags+=(-t "$test_ns")
      else
        unmapped+=("$f")
      fi
    else
      unmapped+=("$f")
    fi
  done
  if [[ ${#unmapped[@]} -gt 0 ]]; then
    echo "no convened test file for: ${unmapped[*]} -- running all tests" >&2
    test_flags=()
  else
    echo "running tests only in: $(printf '%s ' "${test_flags[@]}" | sed 's/-t //g')" >&2
  fi
fi

if [[ $skip_run -eq 0 ]]; then
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
    "${test_flags[@]}" \
    "${test_ns_args[@]}" \
    --fail-threshold 0 \
    "${extra[@]}" 1>&2; then
  echo "cloverage failed" >&2
  exit 2
fi
fi

if [[ $skip_run -eq 1 && ! -d "$target_dir" ]]; then
  echo "no coverage dir at $target_dir to reuse" >&2
  exit 2
fi

code=0
python3 "$(dirname "$0")/coverage_diff.py" --dir "$target_dir" --base "$base" || code=$?
if [[ $code -eq 1 && $narrow_tests -eq 1 ]]; then
  echo "(lines covered solely from other test namespaces report uncovered here -- rerun with --all-tests to check)" >&2
fi
exit $code
