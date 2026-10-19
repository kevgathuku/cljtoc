---
name: sweep
description: Work the beads queue autonomously, one issue at a time, until ready work runs out or a stop condition fires.
disable-model-invocation: true
---

# Sweep

Work the beads queue with minimal supervision. You pull ready work, implement it, gate it, close it, and move on. The human reviews at the end, not per issue.

## Setup (once per run)

1. Create a branch: `git checkout -b sweep/<date>-<topic>`.
2. Confirm authority with the human if unclear: you may commit (one commit per bead, message references the bead id) but never push.

## The loop

Repeat until `bd ready` is empty or a stop condition fires:

1. **Pull.** Run `bd ready --json`, take the top issue, `bd update <id> --claim`. Refresh the lease with `bd heartbeat <id>` roughly every 15 minutes while it is yours.
2. **Implement.** One issue, one minimal diff. Run the `issue-to-pr` skill per issue for the implementation discipline (validate claims, TDD in vertical slices, gates, self-review, docs) — with two sweep adaptations: the bead replaces the GitHub issue number throughout, and you stop at a validated commit, never opening a PR. Respect the repo's review gates: co-located fdefs, `stest/check` pins for pure fns, CHANGELOG entry under `[Unreleased]` for behavior changes.
3. **Gate.** `cljfmt fix` on touched files, `clj-kondo` clean, then `lein test` for the touched namespace; full `lein test` before closing.
4. **Close.** `bd close <id> --reason="..."`. File discovered follow-ups as beads with `--deps discovered-from:<id>`; never markdown TODOs.
5. **Commit.** One commit per bead, message references the bead id (e.g. `Add per-torrent watchdog isolation [torrent-client-clj-0c9.16]`). Never push.

## Stop conditions

Stop the loop and hand off when any one holds:

- `bd ready` is empty.
- 5 beads closed this run.
- Same failure twice on one issue: `bd human <id>` with the error, unclaim, continue to the next ready issue (or hand off if none).
- An issue's scope contradicts its acceptance criteria.
- You need a decision only the human can make.

## Handoff

Report: beads closed, commits on the branch, `git status`, `bd stats`, and anything flagged `bd human`. Propose the next command (usually review + PR); do not push.
