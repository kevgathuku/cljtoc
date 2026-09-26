---
name: address-pr-review
description: "Address PR review findings: validate each thread against the code, fix confirmed ones with TDD, reply per thread, request re-review. Use when the user says 'review the PR comments', 'address feedback', or names a PR with findings."
---

# Skill: address-pr-review

Work every finding on a pull request to done: validated, fixed with tests where confirmed, rebutted with proof where not, replied per thread, re-review requested.

## 1. Fetch the threads

- Overview: `gh pr view <number> --json reviews,comments`.
- Inline threads with reply IDs: `gh api repos/<owner>/<repo>/pulls/<number>/comments` (keep each comment's `id`, `path`, `line` — replies need `in_reply_to`).

Done when every open thread is listed with its location.

## 2. Validate each finding

For every finding, open the cited file:line and reproduce the claim. One verdict per thread: confirmed / false positive (with proof: a passing test, a compiler result, a spec reference) / partially true. Never implement on an unvalidated finding — an unconfirmed bug is not a seam, and the `tdd` skill forbids building on one.

## 3. Fix confirmed findings with TDD

One finding, one vertical slice: red test first, minimal green, then the next finding. Full suite (`lein test`) and lint (`clj-kondo --lint src test`, touched files clean) before moving on. Two cautions earned the hard way:

- Hang-shaped bugs (deadlocks, blocked channels): run the red test under `timeout`, or the loop never returns.
- Delimiter repair tools can vandalize the file (closing the wrong scope while "balancing"). If the compiler disagrees with a repair, revert the file, re-apply the edit cleanly, and trust the compiler.

## 4. Reply to each thread

POST one reply per thread (`in_reply_to` comment id): confirmed → what changed, the covering test, the commit SHA; false positive → the proof, evidence only; partial → what was accepted and what was declined, with why.

## 5. Push and request re-review

Push the branch (the PR updates itself), then request re-review from the original reviewer. If the reviewer identity doesn't resolve, report that instead of stalling — the push itself usually triggers a fresh review. Never poll waiting for the review to land; it arrives asynchronously, minutes later.

## 6. File the findings

Record the outcome in the palace (wing `torrent_client_clj`, room `decisions`): PR number, one line per thread (verdict, fixing commit or rebuttal proof), and any deviation from the review as written — same home as the `issue-to-pr` decision entry, so the issue, its PR, and its review history sit together.
