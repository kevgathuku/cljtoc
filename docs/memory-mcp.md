# Project memory via mempal (direct tools in this environment)

All persistent project memory lives in mempal. The project wing is
`torrent_client_clj`; below, "the project wing" means that wing.
Personal preferences live in wing `pi-diary` (room `diary`); below,
"the personal wing" means that wing. Mempal is the only memory store —
do not use `bd remember`; `bd` stays strictly for issue tracking.

## Connecting (nothing to do per session)

The tools arrive as direct `mcp__mempal__*` functions, already connected at
startup via `~/.pi/agent/extensions/mempal.ts` (`mempal serve --mcp`). There
is no gateway connect dance here: call the tools by name. If a call reports
the server disconnected, reconnect from the `/mcp` panel (or `pi-mcp-adapter
doctor` from a shell) and retry.

## The three ops you need

- **Recall**: `mempal_search` before any decision; `mempal_brief` for a
  citation-first brief; `mempal_context` for ordered guidance. Scope
  `wing` to the project wing when the area is project-specific; omit it
  when unsure — wing filtering is strict equality, so a guessed name
  silently returns nothing and global search beats zero results. If you
  have not seen the wing list this session, call `mempal_status` first —
  never guess wing or room names.
- **Record**: `mempal_ingest` with the project wing, rationale rather
  than just outcome, and `importance` 0-5. Default writes are evidence
  drawers: content + wing only — knowledge-only fields (`statement`,
  `tier`, `status`, `*_refs`, ...) are rejected.
- **Never**: `bd remember`, `MEMORY.md`, or any other ad-hoc memory file.

## When to file

These are requirements, not suggestions. This store exists so no session
ever re-discovers what a prior session already learned.

- **After you implement an issue and open its PR**, file two records:
  the key decision (room `decisions`): issue number, branch/PR, what
  changed and why, plus any deviation from the issue as written; and one
  drawer per new learning (room `lessons`): every discovery from the
  implementation a future session would otherwise re-derive the hard way —
  platform facts, diagnostic methods that worked, gotchas actually hit.
  Rule of thumb: if the session paid for it in debugging time, file it;
  if it merely confirms existing docs, skip it.
- **After you address PR review findings**, file a **per-thread outcome
  record** in the same room: PR number, one line per thread with its
  verdict (confirmed / false positive / partially true) and either the
  fixing commit or the proof that refuted it, plus any deviation from the
  review as written. Leave no thread unfixed without recording it as such,
  pointing at the issue that now carries it — a consolidated record, not
  one drawer per round, so the whole review history sits in one place.
- **For one-off work**, apply the same discipline without the issue/PR
  trigger: whenever a commit lands from a user-directed change, or the
  user makes an explicit decision (an adoption, an override, a scope cut),
  file the decision drawer in the same sitting, plus one lessons drawer
  per discovery paid for in debugging time. Route repo-scoped items to
  the project wing (`decisions` / `lessons`) and personal preferences to
  the personal wing. When a new decision reverses a filed one, record the
  reversal as a new decision drawer that cites the drawer it replaces,
  rather than leaving two live contradictions. Close each topic with a
  diary entry in the personal wing naming what landed and what is next.

After every filing, verify it landed by searching for what you filed
before moving on.

## Why this doc exists

Without it, each session re-discovers the access path, wings, and filing
discipline from scratch. AGENTS.md carries only a pointer here. If
anything on this page changes (access path, wings, filing rules), update
this file — not AGENTS.md.
