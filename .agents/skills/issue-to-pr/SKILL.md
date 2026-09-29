---
name: issue-to-pr
description: "Take a GitHub issue through TDD to an opened PR."
disable-model-invocation: true
---

Take a GitHub issue number through validation, TDD implementation, and an opened PR that closes it.

## 1. Fetch and validate

Run `gh issue view <number> --comments`. For every file:line reference in the issue, open the file and confirm the claim still holds. Check the palace before finalizing verdicts: `mempalace_search` (wing `torrent_client_clj`, room `decisions`) for prior verdicts on the touched files — a claim the code seems to confirm may already have been refuted (do not re-file it), and a prior decision may have settled the approach. State the verdict in one line per claim (confirmed / stale / partially true), palace verdicts included. Stop if the issue is stale and ask how to proceed.

## 2. Branch

Check `git status` is clean and `git branch --show-current` is `main`, then create a dedicated branch: `fix/issue-<number>-<short-slug>`.

## 3. Recall prior decisions

Via the `mempalace-recall` skill, run four lookups on the issue's area and fold anything still binding into the plan (seams, option picks, things already tried); flag contradictions for the PR body:

- `mempalace_search` (wing `torrent_client_clj`, room `decisions`): prior approaches, reverted attempts, naming choices that constrain the plan (verdicts on the claims themselves were settled in step 1 — do not re-litigate them here).
- `mempalace_search` (same wing, room `lessons`): recurring failure modes for this area — mock drift from real envelopes, guard-before-call-site, inferred-vs-observed termination.
- `mempalace_kg_timeline` on the touched file / issue entity: what already fixes / causes what, in order.
- `mempalace_diary_read` (last 3): unfinished work from recent sessions, plus any diagnosed-but-unfixed P0 gap the PR must not claim to close.

## 4. Implement with /tdd

Follow the `tdd` skill: confirm the seams under test before writing anything, then red → green in vertical slices (one seam, one test, one minimal implementation per cycle). Run single test namespaces during the loop and the full suite (`lein test`) once at the end; it must be green before proceeding.

Commit after each todo is done and validated: one commit per vertical slice, only once its tests are green. Never commit a red test or mix slices in one commit.

## 5. Test and lint gate

Both gates must pass before proceeding; fix what they report, don't work around it:

- `lein test` — full suite green, zero failures and zero errors.
- `clj-kondo --lint src test` — no findings in files this change touched. Pre-existing findings elsewhere are out of scope: leave them, never fix unrelated files to satisfy the gate.
- `cljfmt fix` on the changed files before committing, then `cljfmt check` clean.

Re-run each gate after its fixes until clean.

## 6. Coverage gate

Before self-review, prove the new code is exercised — green tests alone hide untested branches. Run `lein cloverage` scoped to the touched namespaces, then intersect uncovered/partial lines with the PR's added lines (`git diff main...HEAD`):

- Fully-uncovered added lines are missing tests: add them, then re-run the gates in step 5.
- Partial branch lines need judgement, not reflex fixes: fail-closed branches on hostile input (guards, validators, error envelopes) get **mutation-based generative specs** — generate a valid input, break one field per mutation, assert rejection — plus one positive invariant (valid input always accepted). Prefer these over envelope-only example doseqs: examples cover shapes, mutations cover branch combinations. But keep the mutation vocabulary as shape-diverse as the examples it replaces (an empty string exercises a different subform than an empty vector); verify by re-running coverage.
- Acceptable residues, documented in the PR body rather than fixed: loop/recur macro internals (both semantic branches covered), fdef `:fn` false-branches (fail-only-on-bug by design), single short-circuit subforms where every semantic direction has a test.

## 7. Self-review with /code-review

Run the `code-review` skill against the branch (fixed point `main`) before opening the PR: Standards (repo conventions) and Spec (the issue as written). Address what it finds — fix, then re-run the gates in step 5 — and note deliberate deviations for the PR body and the palace entry.

As part of the review, judge whether the touched code could benefit from clojure.spec tests (fdef arg/ret contracts, generative checks on pure seams); where it would, add them, then re-run the gates in step 5.

## 8. Update docs and specs

Before opening the PR, keep the docs consistent with the change: search `docs/`, `specs/`, `CONTEXT.md`, and `docs/adr/` for statements this PR invalidates (removed APIs, changed seams, renamed concepts, altered behavior). Update what the change actually breaks, in the same branch so docs and code land together. Scoped to broken assumptions only — never rewrite unrelated docs.

## 9. Open the PR

Stage only intended files, commit with a message describing what changed and why, push with `-u origin`, then `gh pr create` with:

- Title naming the change.
- `Closes #<number>.` as the first body line.
- Validation / Changes / Verification sections: what was confirmed, what changed, and the test counts.

Return the PR URL.

## 10. File the decision and the learnings

Two records, not one — the decision alone strands the discoveries that produced it (on #40 the hang diagnosis and predicate facts sat embedded in the decision drawer until asked for):

- Decision (wing `torrent_client_clj`, room `decisions`): issue number, branch/PR, what changed and why, plus any deviation from the issue as written.
- Learnings (same wing, room `lessons`, one drawer per lesson): every new discovery from this implementation that a future session would otherwise re-derive the hard way — platform facts (generator magnitudes, predicate ranges), diagnostic methods that worked (per-sym REPL timing for a hung pin), gotchas actually hit (stale-session false reds, out-of-domain probes that instrument rejects). Rule of thumb: if the session paid for it in debugging time, file it; if it merely confirms existing docs, skip it.
- Verify both landed: `mempalace_search` the issue entity and confirm the new drawers rank first.
