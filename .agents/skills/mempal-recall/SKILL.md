---
name: mempal-recall
description: Recall protocol for mempal project memory — search the palace before answering about past work, people, projects, or prior decisions. Use when the user asks what was decided, what happened before, who someone is, or anything that may already be filed; or when another skill needs past context.
---

# Mempal Recall

Search-before-answer for `mempal` project memory. Verbatim recall from the palace beats a confident guess from model memory — wrong is worse than slow.

## Step 0 — Verify mempal is available

Run `mempal --version` and `mempal status` once per session. If the CLI is missing or the store unreachable, say so and point at the repo's memory section in `AGENTS.md`. Do not silently answer from model memory.

## When to recall

Search the palace **before answering** whenever the user asks about something that may already be filed:

- Past work or prior decisions — "what did we decide / try / do?"
- A person, project, or entity — "who is …", "what is …"
- An earlier session — "remember when …", "last time …"
- A preference, fact, or relationship that could have changed over time

Do **not** search on greenfield work with no memory relevance (rename a variable, fix a typo). Recall is question-driven, not reflexive.

## Commands

This store keeps one room (`default`) per project wing, so qualify with keywords instead of room filters. Omit `--wing` for a global search when the wing is unknown; `mempal projects` lists exact wing names when scope is unclear.

| You need | Command |
|---|---|
| Find any memory by meaning | `mempal search --wing <wing> "<short keyword query>"` (start here; `--top-k 10`, `--with-neighbors` for chunk context) |
| Ordered guidance, not raw hits | `mempal context "<question>"` (assembles dao_tian → dao_ren → shu → qi; add `--include-evidence --include-cards` when weighing a decision) |
| Relational / time-bound facts | `mempal kg query [--subject S] [--predicate P] [--object O]`; `mempal kg timeline <entity>` for the story in order |
| Resume a project elsewhere | `mempal resume <fragment>` (returns worktree path + recent decisions + next step) |
| Recent session continuity | `mempal wake-up`; diary wings (`pi-diary`, `agent-diary`) are searchable with `mempal search --wing <diary-wing>` |
| Record this session | Write a note file, then `mempal ingest --wing <wing> --room <room> <dir>` |

For this repo the project wing is `torrent_client_clj`; personal material lives in `pi-diary` (room `diary`).

## Protocol

1. Before responding about people, projects, past events, or prior decisions: `mempal search` first. Use `mempal kg query` / `mempal kg timeline` for relational or time-bound facts.
2. If unsure about a fact: run the search and say so until it returns.
3. Return the drawer's **verbatim** text with its drawer id and source file. Never paraphrase stored content — quoting the exact words is the point.
4. After a substantive session, record continuity (decision + one lessons drawer per discovery paid for in debugging time) and verify with `mempal search --wing torrent_client_clj` that the new drawers rank first.
5. When a fact changes: add the new triple with `mempal kg add` and invalidate the old one, rather than leaving two live contradictions.

## Unhappy paths

- **Empty results.** Say the palace has nothing on this; do not invent an answer. Widen the search (drop `--wing`) or file the new information.
- **CLI error / store down.** Surface the error and suggest `mempal doctor`. Never fall back to guessing.
- **Conflicting facts.** Trust the knowledge graph's time-valid answer; supersede single-valued replacements, invalidate facts that ended without replacement.

## Anti-patterns — never do these

- Answer about past work, people, or decisions from model memory when the palace might know — search first.
- Paraphrase or summarize what the palace returns instead of quoting it with its citation.
- Search on every turn, including greenfield tasks with no memory relevance.
- Paste a whole conversation into the query — keep queries short and keyword-driven.
