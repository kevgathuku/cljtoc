---
name: address-pr-review
description: "Address PR review findings: validate each thread against the code, fix confirmed ones with TDD, reply per thread, request re-review. Use when the user says 'review the PR comments', 'address feedback', or names a PR with findings."
---

# Skill: address-pr-review

Work every finding on a pull request to done: validated, fixed with tests where confirmed, rebutted with proof where not, replied per thread, re-review requested.

## 1. Fetch the threads

- Overview: `gh pr view <number> --json reviews,comments`.
- Inline threads with reply IDs: `gh api repos/<owner>/<repo>/pulls/<number>/comments` (keep each comment's `id`, `path`, `line` — replies need `in_reply_to`).
- Only work unresolved threads: check `isResolved` via the GraphQL `reviewThreads` field and skip resolved ones entirely.

Done when every unresolved thread is listed with its location.

## 2. Validate each finding

For every finding, open the cited file:line and reproduce the claim. One verdict per thread: confirmed / false positive (with proof: a passing test, a compiler result, a spec reference) / partially true. Never implement on an unvalidated finding — an unconfirmed bug is not a seam, and the `tdd` skill forbids building on one.

## 3. Fix confirmed findings with TDD

One finding, one vertical slice: red test first, minimal green, then the next finding. Cover each fix in both directions: the red test proving the finding plus at least one test per failure envelope the touched code documents. Full suite (`lein test`) and lint (`clj-kondo --lint src test`, touched files clean) before moving on. Before committing, run `cljfmt fix` on the changed files and re-verify with `cljfmt check`. Two cautions earned the hard way:

- Hang-shaped bugs (deadlocks, blocked channels): run the red test under `timeout`, or the loop never returns.
- Delimiter repair tools can vandalize the file (closing the wrong scope while "balancing"). If the compiler disagrees with a repair, revert the file, re-apply the edit cleanly, and trust the compiler.

## 4. Reply to each thread

POST one reply per thread (`in_reply_to` comment id): confirmed → what changed, the covering test, the commit SHA; false positive → the proof, evidence only; partial → what was accepted and what was declined, with why. Write each reply in the repo Writing voice and tone (`AGENTS.md`): second person, active voice, conversational and respectful.

## 5. Push

Push the branch (the PR updates itself), the push itself usually triggers a fresh review. If any fix in this round alters user-visible behavior, add a CHANGELOG.md entry under `[Unreleased]` before pushing.

## 6. File the findings

Record the outcome in the palace (wing `torrent_client_clj`, room `decisions`): PR number, one line per thread (verdict, fixing commit or rebuttal proof), and any deviation from the review as written — same home as the `issue-to-pr` decision entry, so the issue, its PR, and its review history sit together.

## 7. File the diary entry

Write session continuity to the palace diary (`mempalace_diary_write`, pi diary, AAAK-compressed): the review round addressed, the re-review state (requested, pending, or blocked), threads deferred to follow-up issues, and the next run's starting point. The per-thread verdicts already live in the step 6 decisions entry — the diary carries only what the next session needs to resume, never a second copy of the findings.

Done when a diary entry exists naming the round, the re-review state, and where the next run starts.
