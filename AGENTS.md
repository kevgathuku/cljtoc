# torrent-client-clj Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-02-17

## Active Technologies
- Clojure 1.11+ (JVM-based) + core.async for coordination,clojure.spec.alpha for validation (006-download-orchestration)
- File system for downloaded pieces, JSON/EDN for state persistence (006-download-orchestration)

- Clojure 1.11+ (JVM-based) + None (pure functions only, core.async not needed at this layer) (004-peer-wire-protocol)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Clojure 1.11+ (JVM-based)

## Code Style

Clojure 1.11+ (JVM-based): Follow standard conventions

## Recent Changes
- 006-download-orchestration: Added Clojure 1.11+ (JVM-based) + core.async for coordination,clojure.spec.alpha for validation

- 004-peer-wire-protocol: Added Clojure 1.11+ (JVM-based) + None (pure functions only, core.async not needed at this layer)

<!-- MANUAL ADDITIONS START -->

## Naming

Prefer human-readable names everywhere. Avoid single-letter variables;
use full words that say what the value is (`address-str` not `s`,
`host` / `port-str` not `h` / `p`, `colon-index` not `i`).

## Writing voice and tone

Follow the Google developer documentation style guide (https://developers.google.com/style) for all human-readable writing: feature docs, ADRs, CONTEXT.md, READMEs, code comments and docstrings, commit messages, and PR comments.

- Write to the reader in second person with active voice and a clear actor: you run, you configure, the peer sends.
- Keep a conversational, friendly, respectful tone without slang, jargon, or culturally specific references; write for a global audience.
- Put conditions before instructions: if X holds, do Y.
- Use sentence case for titles and headings; numbered lists for sequences and bulleted lists for unordered sets, with parallel structure.
- Format code spans in backticks and write descriptive link text; spell out each acronym on first use.
- Write inclusively with gender-neutral, accessible language; add alt text for images in docs and READMEs.
- Commit messages carry the voice subset only: imperative second-person subject (`Add X`), concise body explaining why.

## Project Overview

A BitTorrent client in Clojure implementing crash-only design with OTP-style supervision, pure domain logic, and explicit effect boundaries. Uses core.async for concurrency.

## Commands

```bash
# Run all tests
lein test

# Run a single test namespace
lein test dev.cljtoc.core-test

# Run a single test
lein test :only dev.cljtoc.core-test/test-name

# Start REPL
lein repl

# Build uberjar
lein uberjar

# Run the application
lein run
java -jar target/uberjar/dev.cljtoc-0.1.0-SNAPSHOT-standalone.jar
```

## Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.
The port changes on every restart — always use the announced or discovered port, never a hardcoded one.

**Stale sessions:** the persisted session can lag the working tree (branch switches, new commits), and `:reload` refreshes only the namespaces named — their already-loaded dependencies stay stale. Signature: `No such var: <ns>/<name>` for a var that `rg` proves exists in the tree, or results contradicting current source. When that happens, stop: no result from the session is trustworthy. Prompt the user to stop and restart the nREPL server, then re-verify with a probe eval before continuing. Never `:reload-all` across protocol namespaces to recover: `defrecord` classes keep their old protocol identity past `remove-ns`, so only a fresh server heals that split.

## Clojure Parenthesis Repair

When delimiters go unbalanced, in order:

1. Run `clj-paren-repair <files>` before anything else — before counting parens by eye, before hand-editing delimiters, before reverting (it also runs cljfmt).
2. If the compiler still disagrees, the repair likely closed the wrong scope (symptom: `recur` tail-position errors far from the real gap) — revert the file (`git checkout -- <file>`) and re-apply the edits one at a time.
3. After each edit, run a paren-depth scan and `clj-kondo --lint <file>`: kondo pinpoints the exact unclosed opener (`Found an opening ( with no matching )`). Counting closers by eye is unreliable — one extra `)` early silently shifts every scope below it.
4. The compiler is the final arbiter: full `lein test` green means the structure is right.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

The tool automatically formats files with cljfmt when it processes them.

## Structural edits (rewrite-clj)

For multi-file structural campaigns (renames across the port seams, hint
insertion at scale, codemods), reach for rewrite-clj (a `:dev`-profile dep,
never production code) instead of text replacement: locating by s-expression
survives indentation drift that defeats exact-match edits, and zipper
printing stays balanced by construction. Single sites stay on the edit tool
with small anchors plus `cljfmt fix`. Two gotchas earned the hard way:

- A `^Type` hint on a `->`/`->>` chain evaporates during macroexpansion —
  an interop call over the chain reflects even when hinted. Restructure to
  nested keyword calls (`(:b (:a %))`), which carry hints fine. Never hint
  a `defrecord`/`deftype` protocol-method param: the impl signature must
  match the protocol, so the compiler rejects it (`Can't find matching
  method ... leave off hints`); hint the call-site locals instead.
- `scripts/threading_lint.clj` encodes the first lesson as a runnable
  check (`lein run -m clojure.main scripts/threading_lint.clj`, or
  `./scripts/threading_lint.clj` under babashka for instant startup): it fails
  on any `->`/`->>` inside `#()` feeding interop (`-dotted`, `alength`,
  `new`, `Class/static`), with a self-test proving the query fires. Run
  it after structural edits touching fdef `:fn` bodies or inline checks.

## Architecture

Four-layer architecture with strict dependency rules (lower layers cannot depend on higher):

```
Supervisor Layer   → lifecycle management, restart policies
         ↓
Coordination Layer → core.async channels, message routing, backpressure
         ↓
Protocol Layer     → bencode parsing, peer wire protocol, tracker protocol
         ↓
Domain Layer       → pure torrent logic (piece selection, state transitions, verification)
```

### Layer Rules

- **Domain Layer**: Pure functions only. No I/O, no core.async, no time/randomness. State transitions as `(state, event) → state`.
- **Protocol Layer**: Pure parsing/encoding. No network sockets, no channels.
- **Coordination Layer**: core.async flows connecting domain to effects. No domain mutation or business rules.
- **Supervisor Layer**: Owns all go blocks and channel lifetimes. Workers crash on error; supervisors restart them.

### Effect Ports

All side effects isolated behind injectable protocols:
- Network I/O (peer/tracker communication)
- Disk I/O (piece storage)
- Time (current time, delays)
- Randomness (peer selection jitter)

## Constitutional Principles

See `.specify/memory/constitution.md` for authoritative rules. Key points:

1. **Pure Domain**: Domain functions must be deterministic with no side effects
2. **Explicit Effects**: Effects behind protocols, injected at construction
3. **Crash-Only**: Workers crash on errors; supervisors handle restarts
4. **No Hidden State**: No global atoms/vars for app state; all state explicitly passed
5. **I/O-Free Testing**: 90%+ coverage without actual I/O; inject test doubles

## Feature docs

Each feature has a dedicated page under `doc/` (overview plus contract pages):
- Bencode parsing - `doc/bencode-parser.md` (+ `doc/bencode-contracts.md`)
- Peer discovery - `doc/tracker-protocol.md` (+ `doc/tracker-http.md`, `doc/tracker-udp.md`, `doc/tracker-fdef.md`)
- BitTorrent peer messages - `doc/peer-wire-protocol.md` (+ `doc/peer-wire-api.md`, `doc/peer-wire-contracts.md`)
- Pure domain logic for pieces - `doc/piece-management.md` (+ `doc/piece-contracts.md`)
- End-to-end download coordination - `doc/download-orchestration.md` (+ `doc/orchestration-contracts.md`)

`doc/architecture.md` maps the layers, principles, and roadmap. When you change behavior, update the matching feature doc in the same branch.

## Project Conventions

- Torrent/bencode data is binary — do not apply UTF-8 encoding/decoding; treat all byte arrays as raw binary
- Validate a trust boundary once, in the function its callers share: when two entry points enforce the same rule, fix the shared function rather than each caller, or the one you did not patch is still open. A security primitive must not depend on a separate policy check being present and correct at every call site
- Property-test oracles must be derived independently of the code under test (compute expected offsets from declared lengths, not by calling the domain function): a generated assertion that reuses the implementation proves nothing. Give them room to fail — a spec that passes on the first run has usually not been tried hard enough, and the shrinker is what localizes a real gap. Generate hostile inputs by construction (each value invalid independent of the check — a `"zz"` suffix that can never match hex, a forced odd length), never by picking an index among hand-written breaks: enumeration inside `for-all` is still examples. Prove a new spec bites by weakening the implementation and watching it fail before trusting its green
- Never assert a performance claim without measuring it, and never call an O(n·m) recomputation negligible because the constant is small: 0.087 ms/call at 100 files became 5 ms/call and ~14 min of CPU at 5000 files. Prefer `clj-nrepl-eval` over `lein run -m clojure.main` for microbenchmarks (no JVM or compile cost per call)
- Persisted state must decode back to runtime values: byte arrays are stored as tagged `{:cljtoc/bytes hex}` maps (see `dev.cljtoc.ports.disk/encode-state` / `decode-state`) so a resumed download carries real bytes into handshake and verification. Test the decoded value, not the on-disk shape — asserting the encoded form passes while resume is broken
- Pause/resume must be covered by a cycle test through the real port (temp dirs), not just the mock: mocks drift from the real envelope contract (e.g. `load-state` returning the download instead of `{:ok download}`)
- Extract shared test helpers (e.g., `to-bytes`) to `test/dev/cljtoc/test_utils.clj` rather than duplicating across test namespaces
- Co-locate function specs: `(s/fdef NAME ...)` directly after its `defn`, never in a trailing section. Data `s/def` specs must be defined before any fdef referencing them (the spec registry resolves at load time)
- Edge effects must handle port result envelopes: after planning moved state forward, a failed send/write strands it — unwind (requeue + drop bookkeeping) or fail explicitly, never discard `{:error ...}`
- Test doubles must mirror the real ports' error envelopes (e.g., `MockDiskPort` `:write-error`) so edge failure paths stay drivable; success-only mocks leave failure handling untestable
- Filesystem writes must containment-check the canonical path: lexical component validation does not stop a pre-existing symlink inside the target dir from redirecting the write outside it — check containment *before* `mkdirs`, create the parents, then check again (`DiskPortImpl/resolve-contained`). Checking only afterwards is too late: `mkdirs` follows a symlinked component and creates the directory outside the dir first, so the write is refused but the directory is not. Append the separator only when missing: a canonical dir that is itself the filesystem root already ends in one, and doubling it yields `//`, which no child can start with
- Policy refusals are not containment checks: keep them separate and say which is which. Refusing a filesystem root (`DiskPortImpl/declined-output-dir`) is ergonomics — containment already holds there — and it turns a `:write-error` that depends on whether the process may write there into a deterministic, actionable error. Detect a root by shape (a canonical path with no parent), never by comparing against `File/separator`: that matches the Unix `/` only, and lets a Windows drive root through
- Never test a filesystem guard by writing to a real system location: assert refusal happens before any creation, or exercise the pure helper directly (`#'` on a private fn) so the test never touches `/`. A rule only one platform can exercise is pinned on the helper — macOS has no `C:\` to hand the port, it canonicalizes to an ordinary file under the cwd
- Every declared output path must be initialized, not just the paths with data: zero-length files produce no spans and would otherwise be missing from a completed download. `write-layout!` deliberately opens only the files a piece overlaps, so this is `init-layout!`'s job (reached via `initialize-output-layout`), not the piece writer's
- A layout the two derivations disagree about is not a layout: `output-file-sizes` collapses duplicate declared paths into one map entry while `piece-file-spans` still hands out a distinct byte range for each, and both ranges land in the same physical file. Validate such a layout once, in the guard both callers share (`torrent/layout-error`) — a check that lives in one derivation leaves the other writing a file nothing declared
- A path inside another is the same corruption: no declaration order repairs a layout where one entry claims a path as a file and another needs it as a directory, so refuse it in the shared guard (`torrent/prefix-collision?`, adjacent pairs in sorted order — a prefix sorts immediately before everything it prefixes). Bare `[]` never reaches the port (every layout path carries the root) and the containment primitive refuses it anyway, since a dir is not contained under itself
- Pin check-grade fdefs with `stest/check`: every pure, total public fn gets a generative check over its fdef (see `test-utils/check-fdefs`; `torrent-test` / `download-test` `fdef-specs-hold-generatively-test`). Fns behind effect ports are excluded on principle, not by accident — the generator cannot conjure a protocol implementation, so a check on `calculate-rate` dies in `(time/now <generated-long>)` before its `:ret` is even reached. Record the exclusion at the test site. A failing check is either a spec to tighten or a real bug: the first run here found `(char b)` in `bencode/decode-value` throwing on negative bytes
- Calibrate fdef `:args` and `:ret` to each other, never independently to maximum precision: a strict `:ret` over loose `:args` passes only until something emits the shape `:ret` cannot survive (`total-size` with `map?` args + `nat-int?` ret survived 1000 generated cases, yet hand-built `{:length "x"}` returns `"x"`). Tighten args toward the real input shape (`torrent/::totalable-info`: a present `:length` and every present `:files` entry must be nat-int) or loosen the ret — never pin a lucky pass. `stest/instrument` checks `:args` only (proven from `spec-checking-fn` source: it conforms args, calls through, returns unchecked); `:ret`/`:fn` are exercised solely by `stest/check`. `s/keys` matches keys by spec name, so one key carries one meaning per namespace: `:length` cannot be both the pos-int? span length and the nat-int file length — the second meaning gets a predicate (`torrent/span-shaped?`), not a second `s/keys`
- Name every keyword with its namespace (`:piece/index`, `:tracker/url`): download state merges maps from several layers, and bare keys collide. `{:cljtoc/bytes}` already sets the pattern
- Suffix predicates with `?` and effectful functions with `!` (`peer-has-piece?`, `write!`) so effects stay visible at the call site
- Log data, not strings: emit `{:event ...}` maps through a port and keep `println` inside `core.clj` only
- Construct stateful components with an explicit deps map (`make-coordinator {:keys [network disk time]}`); never reach for global system state
- Do no work when a namespace loads: no sockets, go blocks, or file reads at top level, so `:reload` stays safe after branch switches
- Throw `ex-info` only at effect boundaries with a namespaced `:type` plus data, and convert to `{:error ...}` at the layer edge; domain and protocol code returns error maps
- Enable `*warn-on-reflection*` around byte-array hot paths; the parse loops are where reflection hides
- Prefer transducers over chained lazy seqs in domain hot paths (selection counts, block decomposition), and measure before claiming speed
- Never use `with-redefs` in tests; drive seams through injected port doubles instead
- Write `testing` context strings as behavior claims and use `are` for envelope tables
- Log user-visible behavior changes in CHANGELOG.md under `[Unreleased]` (Keep a Changelog `Added`/`Fixed`); internal refactors and docs-only `[skip ci]` commits are exempt
- Locate code through the graft graph before grepping: `graft ask --source`, `graft grep`, `graft skeleton`, `graft callers` (see the Graft section); raw `grep -rn` is the fallback for unindexed files only

## Common Errors to Avoid

- When capping a double before casting to long, apply `min` first: `(long (min double-val cap))` not `(min (long double-val) cap)` — the latter overflows if `double-val` exceeds `Long/MAX_VALUE` (e.g., exponential backoff with large attempt numbers)
- When consolidating duplicated logic into one function, search every namespace including `*_impl` before claiming it is single-sourced (the fourth copy of the size math lived in `network-impl`)
- When unwrapping a channel result, bind the envelope first: `(:ok (async/<!! ...))`, never `assoc` onto the `{:ok ...}` map itself
- `(byte n)` throws "Value out of range for byte" for any `n` above 127; use `(unchecked-byte ...)` when reducing a computed value into a byte array
- test.check: `gen/boolean` is a def'd generator **value**, not a function — `(gen/boolean)` throws "Generator cannot be cast to IFn". Same for `gen/return`, `gen/nat`. Generators built by a fn (`gen/choose`, `gen/vector`, `gen/elements`) do take parens
- `test.check` is `:dev`-only in `project.clj` — never `require` `clojure.test.check.generators` in `src/` code (the uberjar excludes it and the namespace fails to load). Use `clojure.spec.gen.alpha` combinators instead: `gen/fmap` over `gen/tuple` replaces `test.check.generators/let` and loads test.check lazily at generation time only. The lazy-loading mechanism is `clojure.spec.gen.alpha/delay` (used internally by the combinators) — it wraps the underlying `gensub` in a Clojure delay forced at gen-time, not at require-time
- Clojure's `for` supports only `:let`, `:when` and `:while` — `:keys` destructuring there fails with "for requires an even number of forms in binding vector". Bind the map, then key it

## Code Review Gates

All PRs must verify:
1. Domain namespaces contain no I/O imports
2. Side effects only in designated effect namespaces
3. All go blocks have explicit supervisor ownership
4. No new global state introduced
5. New code has corresponding tests; domain tests are pure
6. New public fns carry co-located fdefs; pure, total ones are pinned by `stest/check` (effect-port fns excluded with the reason recorded at the test site)
7. Changed files are `cljfmt`-clean (`cljfmt fix` before committing, `cljfmt check` after)
8. Behavior changes under `src/` add a CHANGELOG.md entry under `[Unreleased]`; internal refactors and docs-only `[skip ci]` commits are exempt

## Memory

After implementing an issue and opening its PR, file two records in the palace (wing `torrent_client_clj`): the key decision (room `decisions`): issue number, branch/PR, what changed and why, plus any deviation from the issue as written; and one drawer per new learning (room `lessons`): every discovery from the implementation a future session would otherwise re-derive the hard way — platform facts, diagnostic methods that worked, gotchas actually hit. Rule of thumb: if the session paid for it in debugging time, file it; if it merely confirms existing docs, skip it. Verify both landed via `mempalace_search` before moving on.

After addressing PR review findings, file a **per-thread outcome record** in the same room: PR number, one line per thread with its verdict (confirmed / false positive / partially true) and either the fixing commit or the proof that refuted it, plus any deviation from the review as written. A thread left unfixed must be recorded as such, pointing at the issue that now carries it — a consolidated record, not one drawer per round, so the whole review history sits in one place.

One-off work gets the same discipline without the issue/PR trigger: whenever a commit lands from a user-directed change, or the user makes an explicit decision (an adoption, an override, a scope cut), file the decision drawer in the same sitting, plus one lessons drawer per discovery paid for in debugging time. Route repo-scoped items to wing `torrent_client_clj` (`decisions` / `lessons`) and personal preferences to `wing_pi` (`preferences`). When a new decision reverses a filed one, supersede it in the knowledge graph rather than leaving two live contradictions. Close each topic with a diary entry naming what landed and what is next. Verify every filing via `mempalace_search` before moving on.

<!-- MANUAL ADDITIONS END -->

## Agent skills

### Issue tracker

Issues tracked in GitHub Issues for kevgathuku/cljtoc. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout (`CONTEXT.md` + `docs/adr/` at repo root). See `docs/agents/domain.md`.

### PR review threads

- Reply per thread via `gh api repos/<owner>/<repo>/pulls/<number>/comments --input` with numeric `in_reply_to` JSON; `-f in_reply_to=<id>` sends a string and is rejected.
- The Copilot reviewer identity doesn't resolve via `--add-reviewer`; pushing the branch retriggers its pass.

<!-- graft:start -->
## Graft — repo context graph

This repo is indexed in `graft/`: small linked markdown nodes that explain each
system and carry exact file:line spans, kept in sync with the code through git.

For ANY task here — understanding how something works, finding where code lives,
or scoping a change — get context from the graph before grepping or opening
source files. Re-ask freely (it's cheap) and reuse literal identifiers you
already have (symbol, error string, file name) as the query. New to this repo?
Run `graft map` first — a token-budgeted orientation (dir clusters, hubs,
hotspots), no LLM, no key.

- Run `graft ask "<your question>" --source` → ranked nodes with the relevant
  code spans inlined (each hit's ≤8-line crux by default; `--full` for whole
  definitions when the crux isn't enough). Match the tool to the task shape:
  for understanding or editing, the top node IS the answer — cite its
  `covers:` file:line spans and edit straight from `--source`. For
  exhaustive tasks ("every occurrence / every caller of this pattern"), ranked
  results are top-N, not complete — run `graft grep "<literal>"` instead
  (exhaustive over indexed files, grouped by enclosing symbol), falling back
  to raw `grep -rn` only for unindexed files.
- `graft skeleton <file>` → every definition's signature + span, ~10× cheaper
  than reading the file; use it to skim an API surface.
- `graft callers <symbol>` gives precomputed, exact edges — who calls this.
  Add `--direction out` for what it calls, or `--depth N` to walk
  transitively for the full blast radius. For structural questions, skip
  ranking and use this directly.
- Or browse: `graft/INDEX.md` lists every node; follow the links.
- Monorepos and folders of multiple repos rank fairly across sub-projects —
  hits carry `[scope/]` labels naming which one they're from. Narrow with
  `graft ask "<task>" --in <scope>/` once you know where you're working.

If a returned span is truncated ("+N more lines"), open the file at that exact
range before finalizing. Only open source files when a node genuinely lacks a
needed detail, and then at the exact file:line the node points to — never
re-read whole files.

After big code changes, refresh the graph with `graft build` (deterministic,
no API key, $0). Freshness: `ask`/`grep`/`skeleton` read live files, but
`callers` edges come from the last build — rebuild before trusting them
after moves or renames, and expect no callers for a symbol defined in two
files mid-refactor (ambiguous names are dropped, not guessed).
<!-- graft:end -->
