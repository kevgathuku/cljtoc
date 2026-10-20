---
name: issue-to-pr
description: "Take a beads issue through TDD to an opened PR."
disable-model-invocation: true
---

Take a beads issue id through validation, TDD implementation, and an opened PR that closes it.

## 1. Fetch and validate

Run `bd show <id>` (description, acceptance criteria, blockers, comments) and validate the bead before claiming it. If blockers are still open, or the bead is stale, stop and ask how to proceed — do not claim it. Only when the bead is current and work can proceed, run `bd update <id> --claim`.

Map the codebase with graft, not grep: run `graft build` first so the graph matches the working tree, then use `graft ask "<question>" --source`, `graft skeleton <file>`, and `graft callers <symbol>` per the `AGENTS.md` Graft section. Reserve raw `grep -rn` for unindexed files and exhaustive "every occurrence" scans (`graft grep` covers literals over indexed files). For every file:line reference in the issue, open the file at the span graft points to and confirm the claim still holds. Check the palace before finalizing verdicts: `mempal search --wing torrent_client_clj "<area> decision"` for prior verdicts on the touched files (one room, `default`, in this store — qualify with keywords, not room filters) — a claim the code seems to confirm may already have been refuted (do not re-file it), and a prior decision may have settled the approach. State the verdict in one line per claim (confirmed / stale / partially true), palace verdicts included. Stop if the issue is stale and ask how to proceed.

## 2. Branch

Check `git status` is clean and `git branch --show-current` is `main`, then create a dedicated branch: `fix/<id-suffix>-<short-slug>` (suffix is the trailing bead segments, e.g. `fix/0c9-26-assemble-envelope` for `torrent-client-clj-0c9.26`).

## 3. Recall prior decisions

Via the `mempal-recall` skill, run four lookups on the issue's area (project wing `torrent_client_clj`) and fold anything still binding into the plan (seams, option picks, things already tried); flag contradictions for the PR body:

- `mempal search --wing torrent_client_clj "<area> decision"`: prior approaches, reverted attempts, naming choices that constrain the plan (verdicts on the claims themselves were settled in step 1 — do not re-litigate them here).
- `mempal search --wing torrent_client_clj "<area> lesson failure mode"`: recurring failure modes for this area — mock drift from real envelopes, guard-before-call-site, inferred-vs-observed termination.
- `mempal kg timeline <entity>` on the touched file / issue entity: what already fixes / causes what, in order.
- `mempal wake-up`: unfinished work from recent sessions, plus any diagnosed-but-unfixed P0 gap the PR must not claim to close.

## 4. Implement with /tdd

Follow the `tdd` skill: confirm the seams under test before writing anything, then red → green in vertical slices (one seam, one test, one minimal implementation per cycle). Run single test namespaces during the loop and the full suite (`lein test`) once at the end; it must be green before proceeding.

Commit after each todo is done and validated: one commit per vertical slice, only once its tests are green. Never commit a red test or mix slices in one commit.

## 5. Test and lint gate

Both gates must pass before proceeding; fix what they report, don't work around it:

- `lein test` — full suite green, zero failures and zero errors.
- `clj-kondo --lint src test` — no findings in files this change touched. Pre-existing findings elsewhere are out of scope: leave them, never fix unrelated files to satisfy the gate.
- `cljfmt fix` on the changed files before committing, then `cljfmt check` clean. Unbalanced delimiters go to `clj-paren-repair` first, before any hand fix (AGENTS.md Clojure Parenthesis Repair).

Re-run each gate after its fixes until clean.

## 6. Coverage gate

Before self-review, prove the new code is exercised — green tests alone hide untested branches, and whole-file percentages hide which lines actually lack coverage. The default path is `scripts/coverage_diff.sh --base <ref>`, which gives you the intersection you actually need:

```bash
scripts/coverage_diff.sh --base main        # default; current branch against main
scripts/coverage_diff.sh --base origin/main  # against the remote's main specifically
scripts/coverage_diff.sh --base HEAD~3       # the last 3 commits only
scripts/coverage_diff.sh --base 248e114      # since a specific commit
```

The script runs `lein cloverage --text` scoped to the diff's namespaces, then reads cloverage's text report directly (no HTML scraping) and prints only the per-line rows that sit in the diff's added set, with the cover marker (✔/✘/~/?). Exit codes are `0 = no uncovered added lines / 1 = uncovered found / 2 = usage error`. The cloverage summary table lands under `target/coverage-diff/coverage.txt`; the human-navigable HTML at `target/coverage-diff/index.html` for the local review (target/ is gitignored, so the HTML is local-only).

Interpret the output as a gate, not a decoration:

- **Every ✘ row must be either a real test or a documented residue.** A ✘ row is a real failure if it sits on a function's public contract path that callers branch on; add a test. A ✘ row is acceptable if it sits on a path the codebase cannot test (e.g. `System/exit` in a CLI command body, with-redefs-forbidden by AGENTS.md), and you have a residue comment in the test site explaining why. The script's exit code only catches ✘ — `?` and `~` rows are informational and don't fail the gate.
- **Every new public function is covered in both directions:** at least one test driving its success path and at least one driving each failure envelope it documents. A function whose tests only pass valid inputs has an unproven contract on exactly the paths callers branch on.
- **Partial branch lines need judgement, not reflex fixes.** Fail-closed branches on hostile input (guards, validators, error envelopes) get **mutation-based generative specs** — generate a valid input, break one field per mutation, assert rejection — plus one positive invariant (valid input always accepted). Prefer these over envelope-only example doseqs: examples cover shapes, mutations cover branch combinations. Keep the mutation vocabulary as shape-diverse as the examples it replaces (an empty string exercises a different subform than an empty vector); verify by re-running the script after each fix.
- **Acceptable residues go in the test site, not in the PR body.** Every documented residue has the format `;; (foo) — <why we don't cover this>`. The script's run then shows only "I didn't fix this because..." rows, not actual gaps. A row the script flags that has no comment in the test site is a real failure by default.

When the script's output is harder to read than needed (3-byte UTF-8 markers rendering as separate glyphs in some terminals), widen the namespace filter rather than dropping the gate:

```
scripts/coverage_diff.sh --ns 'dev.cljtoc.cli.*' --ns 'dev.cljtoc.core'
```

`--ns REGEX` is forwarded to `lein cloverage -n` and can be repeated; it speeds cloverage and narrows the test surface for this run.

**Use `lein cloverage` directly** only when the script's output isn't enough — you want the full per-form/per-line summary, the historical cloverage report, or the EMMA XML for tooling. The script does the diff intersection; raw `lein cloverage` gives you the per-namespace totals. Both write to `target/`, so they share state cleanly.

**Use `git diff main...HEAD` directly** only when the script's diff intersection is too narrow — you want every changed line, not just added lines. Coverage per changed line is the script's job; coverage of the branch as a whole is `lein cloverage`'s.

## 7. Self-review with /code-review

Run the `code-review` skill against the branch (fixed point `main`) before opening the PR: Standards (repo conventions) and Spec (the beads issue as written, acceptance criteria included — every criterion must hold). Address what it finds — fix, then re-run the gates in step 5 — and note deliberate deviations for the PR body and the palace entry.

As part of the review, judge whether the touched code could benefit from clojure.spec tests (fdef arg/ret contracts, generative checks on pure seams); where it would, add them, then re-run the gates in step 5.

## 8. Update docs and specs

Before opening the PR, keep the docs consistent with the change: search `docs/`, `doc/`, `CONTEXT.md`, and `docs/adr/` for statements this PR invalidates (removed APIs, changed seams, renamed concepts, altered behavior). If the change alters user-visible behavior, add a CHANGELOG.md entry under `[Unreleased]`. Update what the change actually breaks, in the same branch so docs and code land together. Scoped to broken assumptions only — never rewrite unrelated docs.

## 9. Open the PR

Stage only intended files, commit with a message describing what changed and why, push with `-u origin`, then `gh pr create` with:

- Title naming the change.
- `Bead: <id>.` as the first body line (GitHub `Closes #` no longer applies — GitHub issues are migrated and closed).
- Validation / Changes / Verification sections: what was confirmed, what changed, acceptance criteria checked off, and the test counts.
- Write the commit message, title, and body in the repo Writing voice and tone (`AGENTS.md`): second person, active voice, sentence-case title, descriptive link text.

Return the PR URL, then `bd close <id> --reason="..."` with the PR link.

## 10. File the decision and the learnings

Two records, not one — the decision alone strands the discoveries that produced it (on #40 the hang diagnosis and predicate facts sat embedded in the decision drawer until asked for):

- Decision (wing `torrent_client_clj`, room `decisions`): bead id, branch/PR, what changed and why, plus any deviation from the issue as written.
- Learnings (same wing, room `lessons`, one drawer per lesson): every new discovery from this implementation that a future session would otherwise re-derive the hard way — platform facts (generator magnitudes, predicate ranges), diagnostic methods that worked (per-sym REPL timing for a hung pin), gotchas actually hit (stale-session false reds, out-of-domain probes that instrument rejects). Rule of thumb: if the session paid for it in debugging time, file it; if it merely confirms existing docs, skip it.
- Verify both landed: `mempal search --wing torrent_client_clj` the issue entity and confirm the new drawers rank first.
