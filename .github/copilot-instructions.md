# Copilot instructions for `cljtoc`

## Start here

- Read `AGENTS.md` first for repo-wide workflow, commands, and architecture constraints.
- Read `CONTEXT.md` before naming domain concepts; use its vocabulary (`torrent metainfo`, `info hash`, `peer address`, `download`, etc.) instead of ad hoc synonyms.
- Read the relevant ADRs in `docs/adr/` and the matching feature spec in `specs/<feature>/` before changing behavior.

## What this repo is

- A Clojure BitTorrent client with a strict layered architecture:
  - `src/dev/cljtoc/domain/` = pure domain logic only
  - `src/dev/cljtoc/protocol/` = pure parsing/encoding and protocol state
  - `src/dev/cljtoc/coordination/` and `src/dev/cljtoc/orchestration/` = core.async coordination
  - `src/dev/cljtoc/ports/` = effect boundaries
  - `src/dev/cljtoc/test_doubles/` = injected test implementations for I/O-free tests
- Preserve the constitution in `.specify/memory/constitution.md`: no hidden state, explicit ports for effects, crash-only supervision, and pure domain functions.

## Working conventions

- Prefer small, surgical changes.
- Keep domain code deterministic and free of I/O, `core.async`, time, randomness, and ambient configuration.
- Return errors as data (`{:error ...}` / `{:ok ...}`), not exceptions, for expected failures.
- Treat torrent/bencode binary fields as raw bytes. Do **not** UTF-8 decode info hashes, piece hashes, or other binary payloads unless the code already does so for a known text field.
- Use human-readable names; avoid single-letter locals.
- When changing behavior, update or add tests in `test/dev/cljtoc/...` at the same time.

## Fast path to common files

- CLI entry point: `src/dev/cljtoc/core.clj`
- Torrent and bencode domain logic: `src/dev/cljtoc/domain/{torrent,bencode,pieces,peer_address}.clj`
- Tracker and peer wire protocol: `src/dev/cljtoc/protocol/`
- Download orchestration: `src/dev/cljtoc/orchestration/download.clj`
- Ports and effect implementations: `src/dev/cljtoc/ports/`
- Integration test for full download flow: `test/dev/cljtoc/integration/download_integration_test.clj`

## Commands

- Run all tests: `lein test`
- Run one namespace: `lein test dev.cljtoc.domain.torrent-test`
- Run one test: `lein test :only dev.cljtoc.domain.torrent-test/parse-torrent-single-file-test`
- Start REPL: `lein repl`
- Run app: `lein run`
- Build uberjar: `lein uberjar`

CI currently runs `lein test` on Java 21 in `.github/workflows/ci.yml`; prefer matching that when you need to reproduce CI behavior.

## Known gotchas / workarounds

- The actual test tree is `test/`, not `tests/`. Some generated docs summarize the layout as `tests/`; use the real `test/dev/cljtoc/...` paths when editing or running targeted tests.
- If you hit unbalanced delimiters in a Clojure file, use `clj-paren-repair <file>` instead of hand-editing parens. It also runs `cljfmt`.
- If you use `clj-nrepl-eval`, require namespaces with `:reload` so REPL state reflects local changes.
