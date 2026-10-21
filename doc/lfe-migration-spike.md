# Spike: migrating to LFE for real OTP supervision

**Status:** spike complete; recommendation: stay in Elixir, do not adopt LFE. Date: 2026-10-21.
**Question:** can a migration to LFE (Lisp Flavoured Erlang) fix the process
supervision and resilience weaknesses of the Clojure/JVM version?
**Short answer:** yes for the VM weaknesses, no better than Elixir does —
and LFE keeps the paren-tax while trading away Elixir's ecosystem. The
differentiator would be taste and macro power, not capability.

## Primary-source facts

- LFE is actively maintained: v2.2.2 released 2026-08-05 with OTP 28/29
  compatibility (sources: `api.github.com/repos/lfe/lfe/releases/latest`,
  `hex.pm/api/packages/lfe` both report 2.2.2).
- The 2.2.2 release notes show OTP-version-aware maintenance
  (`lfe_init` dispatching across OTP <26 / 26–27 / 28+ for the `user_drv`
  API change) plus a new Common Test suite — i.e. tested, not dormant.
- The same release adds **Clojure-inspired functions to the `clj` module**
  (`println`, `printf`, `assoc`/`assoc-in`, `update`/`update-in`,
  polymorphic over proplists, dicts, maps, lists) — the maintainers are
  deliberately courting Clojure refugees.
- Tooling exists but is thin (source: `api.github.com/orgs/lfe/repos`):
  a rebar3 plugin, a `fmt` formatter, Mix tooling (`lfe/mix`), an examples
  repo, a docs site, a Common Test DSL (`dlct`), an OTP platform sketch
  (`olp`). No spec/test.check equivalent; property testing would be
  PropEr (BEAM-wide, usable from any BEAM language) plus eunit/Common Test.

## What migration would actually fix

The JVM weaknesses named in the constitution and lessons, all of which are
VM physics on BEAM:

- Fault isolation: per-process heaps and preemptive scheduling contain a
  malicious peer's handler by construction (today: one shared heap, OOM
  kills the daemon).
- Supervision: ADR-0011's hand-rolled supervisors, restart budgets, and
  prune timers become `supervisor` child specs with `max_restarts` /
  `max_seconds` (today: ~6 namespaces reimplementing OTP).
- Crash-only recovery and distribution fall out of the runtime instead of
  discipline.

LFE and Elixir fix these identically — same VM, same OTP. There is no
resilience argument for LFE *over* Elixir.

## What transfers (rewrite, not port)

JVM→BEAM shares no runtime, so no code migrates — only knowledge:

- Transfers almost verbatim: ADRs 0002–0012 (all are VM-neutral except
  JVM-specific rejections: `ByteBuffer`, `BitSet`, `Long`, `URLEncoder`),
  the supervision design (strategies per tier already chosen), test
  fixtures (`.torrent` files, tracker captures), palace lessons, beads.
- Rewritten: all effect and coordination code (`core.async` → processes,
  ports → behaviours/callback modules), all specs (spec/fdef has no LFE
  counterpart; closest is PropEr properties + `ltest`/eunit).
- Notably, ADR-0001 inverts twice: Clojure hand-rolled bencode,
  Elixir uses Bento; LFE would use an Erlang bencode lib or a short
  recursive-descent port — the byte-fidelity rule (ADR-0002) is what
  actually transfers.

## LFE vs Elixir, given `torrent_client` already exists in Elixir

For LFE:

- Lisp syntax and homoiconicity: the team's structural-editing habits
  (rewrite-clj scripts, zipper transforms) and macro power carry over;
  the `clj` compat module softens the port.
- Zero-cost Erlang/OTP interop with Lisp syntax, and BEAM-wide MFA
  interop means LFE code can *call the existing Elixir modules*
  incrementally — a strangler migration is possible, unlike from Clojure.

Against LFE:

- The agent-syntax weakness persists: anonymous delimiters, one missing
  paren shifting every scope below it — the whole repair ladder in
  AGENTS.md applies to LFE exactly as to Clojure. Elixir's `do/end`
  advantage is lost.
- Ecosystem: tiny community, thin docs/answers, no spec-grade contract
  net, `typed` still an experiment. Every OTP-mapping question gets
  fewer eyes than the same question in Elixir.
- Supervision must still be *written* — same work as Elixir, minus the
  examples and guides.

## Actual state of the Elixir version (2026-10-21, re-surveyed)

The comparison above treats Elixir as the incumbent, so here is what
the incumbent actually is — surveyed twice, not assumed. It has moved
fast: parity tasks kog.1–kog.5 are closed and `mix test` reports 81
passing (11 properties, 70 tests).

`lib/` now totals ~931 lines across 16 modules:

- `TorrentClient.Application`: Registry plus session DynamicSupervisor —
  the supervision skeleton, per the `unnamed-workers` memory.
- Three behaviour seams with impls and mocks: network (peer TCP),
  disk (piece storage/resume), clock (monotonic ms) — plus the
  pre-existing tracker-HTTP seam.
- `PeerMessage` (154 lines): all nine BEP-3 messages as structs with
  bitstring encode/decode, 16 KiB block cap, rest-returning decode (no
  stream reassembly in the layer), malformed input as error tuples.
- `PeerState` (77 lines): pure `handle/2` posture machine
  (choke/interest/bitfield/have).
- `Torrent` (137 lines, grown from 57): `verify_hash?`,
  `announce_tiers`, `validate_block` (malformed-block error envelopes —
  the closed-clj-bead-0c9.26 lesson applied *at build time*), and
  `validate_path` confinement. The `info_hash` re-encode question is
  still open: it hashes a re-encoded `Map.take`n struct, and the fixture
  proof that key order canonicalizes is still owed.
- Purity and fidelity gates exist as tests (`domain_purity_test`,
  `torrent_fidelity_test`, `peer_compact_property_test`).

Still open, i.e. the download path itself: UDP tracker (`.6`), disk
implementation (`.7`), supervised peer worker (`.8`), coordinator plus
watchdog (`.9`), session/persistence/config/CLI (`.10`), then webseed
(`.11`) and seeding/DHT (`.12`). No `url-list` parsing yet — the
proven Transmission gap is still unaddressed on both sides.

Beyond code, the scaffolding stands: `CONSTITUTION.md`, `docs/adr/0001–
0012`, 28 filed memories, beads epic `torrent_client-kog` with 23
children (5 closed). So an LFE spike now competes against a fully
planned Elixir build with foundation and protocol layers landed and
pinned by property tests — its bar is demonstrating an advantage over
*that* trajectory (supervisor slices landing faster, PropEr matching
`stest/check`), not merely outrunning an early prototype.

## The spike this doc called for

A spike, not a migration: port one pure domain module (piece selection,
ADR-0005) plus one supervisor slice to LFE, calling the existing Elixir
code where possible, and evaluate (a) agent edit experience vs Clojure,
(b) the PropEr test story vs `stest/check`, (c) rebar3/mix workflow fit.
Decision criteria up front: the spike must show an advantage over
continuing in Elixir, not just over Clojure — beating Clojure alone is
already achieved by the Elixir tree.

## Spike results (2026-10-21)

### What was ported and how it was checked

The spike lives in `spikes/lfe` in this tree. It ported two slices:

- `dev.cljtoc.domain.pieces` to `src/torrent_pieces.lfe` (276 lines), with
  the Clojure example cases as 19 `ltest` tests and 12 PropEr properties.
  `verify-piece` calls the existing Elixir `Torrent.verify_hash?/2` through
  the `'Elixir.Torrent` atom; the tests use `Utils.sha_sum/1` the same way.
- The session supervisor stop path to `src/download_worker.lfe` (85 lines)
  and `src/session_sup.lfe` (74 lines), with 6 `ltest` cases covering
  `stop`, `stop_child`, `remove_child`, transient crash restart, and the
  terminal-download rule.

Run them from `spikes/lfe`:

```bash
rebar3 as test lfe ltest          # 25 tests, ~1 s warm
rebar3 lfe format                 # in-place formatter
erl -noshell -pa _build/test/lib/*/ebin \
  -eval 'io:format("~p~n",[proper:module(prop_torrent_pieces,[{numtests,300}])]), halt().'
```

The full source is in the appendix. The port is not a proposal; it is the
evidence for the answers below.

### (a) Agent edit experience against Clojure

The paren tax is unchanged. One missing `)` in `session_sup.lfe` was
reported by the compiler as `missing_token` at line 10, the `defmodule`
line, while the actual gap was at the end of the file. The
`clj-paren-repair` ladder in `AGENTS.md` does not understand LFE; the
recovery was a paren-depth scan plus extracting the nested `init` body into
`child-specs`/`child-spec`. That is the same class of failure as Clojure,
not an improvement.

Structural editing does not carry over. `rewrite-clj` and zipper scripts are
JVM/Clojure tools, and the spike found no LFE zipper library. The LFE parser
(`lfe_parse`, `lfe_codegen`) could be scripted, but no ready-made equivalent
was available in this setup, so edits were exact-match text edits followed by
the compiler. The
`clj` compat module does soften the map habit: `clj:assoc`, `clj:update`,
`clj:'assoc-in'`, and `clj:'update-in'` all worked on the piece state, and
the `->`/`->>` macros exist. It is a library, not the language, and the port
still used `maps:put`/`sets` because the state is maps and sets.

The semantic traps cost more than the parens:

- `#(...)` is a literal tuple and does not evaluate its elements; `#M(...)`
  is a literal map and does not evaluate its values. Use `(tuple ...)` and
  `(map ...)`.
- The atom quote is prefix-only: `'Elixir.Torrent` is correct;
  `'Elixir.Torrent'` parses as an atom whose name ends in a quote and fails
  with `undef`.
- `=` is match/unify, not equality (`==`); less-or-equal is `=<`.
- `sets:is_set/1` is not a legal guard; the port dispatches on `is_list`.
- `let` does not destructure tuple patterns; use `let*` plus `element` or a
  `case`.
- `gen_server` does not trap exits by default. Without
  `process_flag('trap_exit, true)` in `init`, the supervisor's `shutdown`
  signal kills the worker without calling `terminate/2`, so the paused
  record never lands. This is the one OTP trap that directly changed the
  design.

The port also drifted from the Clojure envelope: success returns are
`(tuple 'ok ...)` while errors are maps, where Clojure returns maps for
both. The drift is small but real, and it caused one property failure while
the oracle learned to handle both shapes. An agent editing LFE has to
enforce the contract deliberately; the syntax makes the wrong shape easier
to write.

Verdict for (a): no advantage over Clojure. LFE keeps the paren tax and adds
semantic traps, weaker diagnostics, and no zipper tooling. Elixir is better
than both for agent edits because `do/end`, the compiler messages, and the
integrated formatter reduce exactly these cycles.

### (b) PropEr against `stest/check`

The repo's gate is stronger than "run some properties": every pure, total
public fn carries a co-located `fdef`, and `stest/check` derives argument
generators from `:args` and checks `:ret`/`:fn` automatically. That gate
caught a real `(char b)` throw in `bencode/decode-value`. LFE has no spec
equivalent. Dialyzer type specs are separate and do not generate tests, so
the equivalent is a hand-written PropEr property per function or behavior.

The spike wrote 12 properties, with oracles computed from the declared
inputs rather than by calling the implementation again: the partition
invariant, initial state, `complete?`, `endgame?`, selection safety and
rarest-first tie-break, endgame selection, piece lengths, block coverage,
hash acceptance, bad hash lengths, and invalid transitions. Each ran 300
cases.

The missed-bug rate used 12 hand-written mutants:

| Mutant | Examples (19) | Properties (initial 10) | Properties (final 12) |
|---|---|---|---|
| M1 `complete?` counts needed instead of verified | missed | caught | caught |
| M2 `endgame?` ignores in-flight | missed | caught | caught |
| M3 tie-break picks the highest index | caught | caught | caught |
| M4 `piece-length` drops the zero clamp | caught | caught | caught |
| M5 `total-piece-count` floors instead of ceilings | caught | caught | caught |
| M6 `mark-verified` leaves the piece in flight | caught | caught | caught |
| M7 endgame selection drops in-flight pieces | caught | missed | caught |
| M8 block size is 16383 | caught | missed | caught |
| M9 `verify-piece` only rejects an empty hash | caught | caught | caught |
| M10 selection frequency counts every peer | caught | caught | caught |
| M11 initial state omits the last piece | caught | missed | caught |
| M12 selection unions in-flight pieces into candidates | caught | caught | caught |

Examples missed 2 of 12 (16.7%); the initial property suite missed 3 of 12
(25%); the final suite missed 0 of 12. The two semantic mutants the examples
missed are the interesting ones: a state with every piece in flight has
`needed-count == 0` and no verified piece, so `complete?` and `endgame?`
both look right to example tests that only cover all-needed and
all-verified. The property over the partition caught them.

The initial property gap is the honest caveat. M7 was not covered because
the property suite had no endgame-selection property; M11 was not covered
because the state generator built maps directly instead of calling
`initial-piece-state`; M8 was not covered because PropEr's generators are
size-parameterised and `choose(1, 1048576)` draws from the current size,
which starts at 1, so 100 cases only saw tiny pieces. `resize` and a
boundary generator (exact multiples of 16384, plus or minus one) fixed the
last one. Property tests are only as strong as their generators, and the
generator is not checked against the contract the way an fdef is.

`ltest` exits 0 even when tests fail, which the spike confirmed with a
failing mutant. CI therefore cannot rely on the command's exit code; it must
parse the summary or use Common Test. `proper:module` was run through `erl`;
there is no rebar3 PropEr provider in this setup, and
`rebar3 as test lfe eval` crashed in `rebar_core` instead of evaluating.

Verdict for (b): PropEr is capable, and it caught the two bugs the examples
missed. It is not as strong as the fdef plus `stest/check` gate for this
repo: generators are manual, there is no co-located contract lint, there is
no automatic `:ret` check, and the test runner does not fail CI. The gate
would have to be rebuilt, not ported.

### (c) rebar3 and mix workflow fit

Build and test work. `rebar3 as test lfe ltest` ran 25 tests in about 1 s
warm; the 12 properties ran in about 0.6 s. `rebar3 lfe format` rewrote the
three modules (reordered exports and reindented; `torrent_pieces.lfe` alone
showed a 286-line diff) and the tests stayed green. `dlct` was not used; `ltest` was enough for this slice.

The workflow gaps matter more than the speed:

- `ltest` returns 0 on failure. The Clojure `lein test` gate is lost unless
  the summary is parsed.
- `ltest` caches its compiled tests in `test/*.beam` and skips a source
  whose mtime is not newer. Restoring a file with an older mtime produced
  stale results twice; the fix is deleting the beams or a clean build.
  `rebar3 as test compile` also skipped a changed LFE source when its mtime
  was not newer than the beam.
- `rebar3 as test lfe eval` crashed, so the REPL/eval path was not usable
  for quick checks; the spike used `erl -noshell -pa ...`.
- `scripts/coverage_diff.sh` has no LFE counterpart. `rebar3 cover` exists,
  but it does not instrument `ltest` without custom work, so the coverage
  gate is simply lost unless you build the integration.
- `bb scripts/fdef_lint.clj` has no counterpart; there are no fdefs to lint.
  `clj-kondo` has no LFE equivalent here; the LFE compiler and `lfe_lint`
  catch undefined functions, not style or contract drift.
- `cljfmt check` maps to `rebar3 lfe format`, but the spike only exercised
  the rewrite mode, not a check mode.

Mix (the Elixir tree) has the integrated version of all of this: `mix test`
with real exit codes, `mix format --check-formatted`, `mix credo`,
`mix dialyzer`, and `mix test --cover`. The comparison is rebar3 versus mix,
and rebar3 loses on every gate this repo actually uses.

Verdict for (c): usable for a spike, not a replacement for the current
gates. The coverage gate and the test-failure exit code are the two that
would cost real work to restore.

### The queue split, for the record

The queue is resilience-heavy right now, which flatters any migration:

- Resilience and runtime: `0c9.18` periodic persistence and SIGTERM
  shutdown, `0c9.12` daemon mode, `0c9.16` per-torrent watchdog isolation,
  `0c9.15` periodic re-announce, `0c9.3` listening port, `2f6` budget
  exhaustion discarding verified progress. OTP makes this class easier.
- Pure domain and protocol: `0c9.2`, `0c9.4`, `0c9.24`, `0c9.25`, `0c9.28`,
  `0c9.29`, `1kr`, `0c9.8`, `0c9.9`. These transfer verbatim either way.
- Contract and testing: `0c9.30` generative pin for tracker fdefs, `dmg`
  covering the `core.clj` CLI bodies, `bnv` pinning CLI output with pure
  render functions, `1o1` coverage-diff fixtures. These get harder with no
  spec/fdef equivalent and no coverage tooling.

The supervision slice confirms the first row: `terminate_child` is
synchronous, so the Clojure done promise, `join-run` deadline, and
`InterruptedException` handling
(`src/dev/cljtoc/supervision/session_supervisor.clj:189-280`) collapse into
`terminate/2` plus a child spec. The worker's pause rule is
`download_worker.lfe:69`; the stop path is `session_sup.lfe:47`. But Elixir
gets the same collapse, and the LFE version still needs `trap_exit`, child
specs, and an ETS live view. The line count is not lower; the win is that
the restart policy is declarative.

### Decision

LFE shows no advantage over continuing in Elixir. It is the same VM and the
same OTP, so supervision, fault isolation, and crash-only recovery are
identical. The edit experience is no better than Clojure and worse than
Elixir; PropEr is weaker than the fdef plus `stest/check` gate; and the
rebar3 workflow loses the test-failure exit code and the coverage gate. The
only differentiator the spike did not exercise is macro power, and nothing
in the ported modules needed it.

Recommendation: **stay in Elixir; do not adopt LFE.** Do not run a second
spike on this evidence. A second spike would need a macro-heavy module to
test the one remaining hypothesis, and the tooling and contract costs are
already higher than the taste benefit. The Clojure source is unchanged; this
report and `spikes/lfe` are the only additions.

### Review follow-up (2026-10-21)

Three findings from the PR review were checked against the port and fixed in
`spikes/lfe`; the appendix carries the fixed versions.

- `piece-blocks/3` accepted a negative index and returned blocks with a
  negative `piece-index`. The Clojure source has the same upper-bound-only
  check (`src/dev/cljtoc/domain/pieces.clj:421`), but its `::piece-index`
  spec is `nat-int?`, so the domain excludes negatives. The port has no
  spec layer, so it now checks the lower bound explicitly. This is a
  deliberate divergence from the Clojure function body, not a port defect.
- `assemble-piece/2` sorted blocks before validating them. A block that was
  not a map, or a map without `offset`, crashed in the sort comparator
  instead of returning `invalid-block`; Clojure's destructuring returns
  `nil` for those shapes, so the LFE port had introduced a real regression.
  The port now validates every block first and rejects non-maps in
  `block-shape-error/2`.
- Both test helpers hard-coded the author's Elixir BEAM path. They now read
  `ELIXIR_EBIN` and fall back to that path, so another machine can point
  them at its own build.

The fixes kept the mutation table unchanged: 25 `ltest` cases, 12
properties, and the same 12 mutants with 0 missed by the final property
suite.

## Appendix: full spike source

The spike project lives in `spikes/lfe`. These files are the complete port,
and the appendix mirrors that directory.

### `rebar.config`

```erlang
{erl_opts, [debug_info]}.
{plugins, [{rebar3_lfe, "~> 0.5"}, {lfe, "2.2.2"}]}.
{deps, [{lfe, "2.2.2"}, {proper, "~> 1.4"}, {ltest, "~> 0.13"}]}.
{profiles, [
  {test, [
    {src_dirs, ["src", "test"]}
  ]}
]}.
```

`lfe` is both a plugin and an app dependency. Without the plugin entry, a
fresh build compiles `ltest`'s LFE sources before `lfe_comp` is on the code
path and fails with `undef`. The spike's first build happened to reuse an
already-compiled `_build`, which hid this until the port moved into the repo.

### `src/torrent_pieces.lfe`

```lfe
;;;; Piece management ported from dev.cljtoc.domain.pieces (ADR-0005).
;;;;
;;;; Pure functions only: state transitions return new maps, selection is
;;;; rarest-first with a lowest-index tie break, and verification calls the
;;;; existing Elixir Torrent.verify_hash?/2 through plain BEAM interop.

(defmodule torrent_pieces
  (export
   (assemble-piece 2)
   (complete? 1)
   (endgame? 2)
   (in-flight-count 1)
   (initial-piece-state 1)
   (mark-in-flight 2)
   (mark-verified 2)
   (needed-count 1)
   (piece-blocks 3)
   (piece-length 3)
   (requeue-piece 2)
   (requeue-verified 2)
   (select-piece 3)
   (select-pieces-endgame 2)
   (verified-count 1)
   (verify-piece 3)))

(defun block-size () 16384)

(defun piece-error (error-kw message)
  (map 'error error-kw 'message message))

(defun initial-piece-state (total-pieces)
  (map 'total-pieces total-pieces
       'needed (sets:from_list (lists:seq 0 (- total-pieces 1)))
       'in-flight (sets:new)
       'verified (sets:new)))

(defun needed-count (state)
  (sets:size (maps:get 'needed state)))

(defun in-flight-count (state)
  (sets:size (maps:get 'in-flight state)))

(defun verified-count (state)
  (sets:size (maps:get 'verified state)))

(defun complete? (state)
  (== (sets:size (maps:get 'verified state)) (maps:get 'total-pieces state)))

(defun mark-in-flight (state piece-index)
  (let ((needed (maps:get 'needed state)))
    (if (sets:is_element piece-index needed)
      (tuple 'ok
             (maps:put 'needed (sets:del_element piece-index needed)
                       (maps:put 'in-flight
                                 (sets:add_element piece-index
                                                   (maps:get 'in-flight state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in needed; cannot mark in-flight"
                       (list piece-index)))))))

(defun mark-verified (state piece-index)
  (let ((in-flight (maps:get 'in-flight state)))
    (if (sets:is_element piece-index in-flight)
      (tuple 'ok
             (maps:put 'in-flight (sets:del_element piece-index in-flight)
                       (maps:put 'verified
                                 (sets:add_element piece-index
                                                   (maps:get 'verified state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in-flight; cannot mark verified"
                       (list piece-index)))))))

(defun requeue-piece (state piece-index)
  (let ((in-flight (maps:get 'in-flight state)))
    (if (sets:is_element piece-index in-flight)
      (tuple 'ok
             (maps:put 'in-flight (sets:del_element piece-index in-flight)
                       (maps:put 'needed
                                 (sets:add_element piece-index
                                                   (maps:get 'needed state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not in-flight; cannot requeue"
                       (list piece-index)))))))

(defun requeue-verified (state piece-index)
  (let ((verified (maps:get 'verified state)))
    (if (sets:is_element piece-index verified)
      (tuple 'ok
             (maps:put 'verified (sets:del_element piece-index verified)
                       (maps:put 'needed
                                 (sets:add_element piece-index
                                                   (maps:get 'needed state))
                                 state)))
      (piece-error 'invalid-transition
                   (lists:flatten
                     (io_lib:format
                       "Piece ~p is not verified; cannot requeue"
                       (list piece-index)))))))

(defun select-piece (piece-state peer-available all-peers-available)
  (let* ((needed (maps:get 'needed piece-state))
          (candidates (sets:intersection (as-set peer-available) needed)))
    (if (== 0 (sets:size candidates))
      (tuple 'ok 'nil)
      (let* ((ranked
               (lists:map
                 (lambda (piece-index)
                   (tuple (held-by-count piece-index all-peers-available)
                          piece-index))
                 (sets:to_list candidates)))
              (rarest (lists:min ranked)))
        (tuple 'ok (element 2 rarest))))))

(defun held-by-count (piece-index all-peers-available)
  (lists:foldl
    (lambda (peer acc)
      (if (sets:is_element piece-index (as-set peer))
        (+ acc 1)
        acc))
    0
    all-peers-available))

(defun as-set
  ((list) (when (is_list list))
   (sets:from_list list))
  ((other) other))

(defun piece-length (piece-index standard-piece-length total-length)
  (let* ((start (* piece-index standard-piece-length))
          (finish (min (+ start standard-piece-length) total-length)))
    (max 0 (- finish start))))

(defun total-piece-count (total-length standard-piece-length)
  (+ (div (- total-length 1) standard-piece-length) 1))

(defun piece-blocks (piece-index standard-piece-length total-length)
  (let ((total-pieces (total-piece-count total-length standard-piece-length)))
    (if (or (< piece-index 0) (>= piece-index total-pieces))
      (piece-error 'invalid-input
                   (lists:flatten
                     (io_lib:format
                       "Piece index ~p is out of range [0, ~p)"
                       (list piece-index total-pieces))))
      (let* ((piece-size
               (piece-length piece-index standard-piece-length total-length))
              (blocks (block-loop piece-index piece-size 0 '())))
        (tuple 'ok (lists:reverse blocks))))))

(defun block-loop (piece-index piece-size offset acc)
  (if (>= offset piece-size)
    acc
    (let ((block-length (min (block-size) (- piece-size offset))))
      (block-loop piece-index
                  piece-size
                  (+ offset block-length)
                  (cons
                    (map 'piece-index piece-index
                         'offset offset
                         'length block-length)
                    acc)))))

(defun verify-piece (piece-index assembled-bytes expected-hash)
  (cond
    ((not (is_binary assembled-bytes))
     (piece-error 'invalid-input "assembled-bytes must be a binary"))
    ((== 0 (byte_size assembled-bytes))
     (piece-error 'invalid-input "assembled-bytes must not be empty"))
    ((not (is_binary expected-hash))
     (piece-error 'invalid-input
                  "expected-hash must be exactly 20 bytes, got nil"))
    ((not (== 20 (byte_size expected-hash)))
     (piece-error 'invalid-input
                  (lists:flatten
                    (io_lib:format
                      "expected-hash must be exactly 20 bytes, got ~p"
                      (list (byte_size expected-hash))))))
    ((apply 'Elixir.Torrent 'verify_hash? (list assembled-bytes expected-hash))
     (tuple 'ok piece-index))
    ('true
     (map 'error 'hash-mismatch
          'piece-index piece-index
          'message
          (lists:flatten
            (io_lib:format "Piece ~p hash mismatch" (list piece-index)))))))

(defun block-shape-error (block expected-length)
  (cond
    ((not (is_map block))
     (piece-error 'invalid-block
                  (lists:flatten
                    (io_lib:format "Block is not a map: ~p" (list block)))))
    ('true
     (let* ((offset (maps:get 'offset block 'undefined))
             (data (maps:get 'data block 'undefined)))
       (cond
         ((not (and (is_integer offset) (>= offset 0)))
          (piece-error 'invalid-block
                       (lists:flatten
                         (io_lib:format
                           "Block offset must be a non-negative integer, got ~p"
                           (list offset)))))
         ((not (is_binary data))
          (piece-error 'invalid-block
                       (lists:flatten
                         (io_lib:format
                           "Block at offset ~p carries no byte array"
                           (list offset)))))
         ((> (+ offset (byte_size data)) expected-length)
          (piece-error 'block-overflow
                       (lists:flatten
                         (io_lib:format
                           "Block at offset ~p with ~p bytes runs past piece length ~p"
                           (list offset (byte_size data) expected-length)))))
         ('true 'ok))))))

(defun first-shape-error (blocks expected-length)
  (case blocks
    ('() 'none)
    ((cons block rest)
     (case (block-shape-error block expected-length)
       ('ok
        (first-shape-error rest expected-length))
       (error error)))))

(defun assemble-piece (blocks expected-length)
  (if (or (not (is_list blocks)) (== 0 (length blocks)))
    (piece-error 'invalid-input "No blocks to assemble")
    (case (first-shape-error blocks expected-length)
      ('none
       (let ((sorted
               (lists:sort
                 (lambda (a b) (< (maps:get 'offset a) (maps:get 'offset b)))
                 blocks)))
         (assemble-loop sorted expected-length 0 '())))
      (error error))))

(defun assemble-loop (blocks expected-length expected-offset acc)
  (case blocks
    ('()
     (if (== expected-offset expected-length)
       (tuple 'ok (iolist_to_binary (lists:reverse acc)))
       (piece-error 'incomplete-piece
                    (lists:flatten
                      (io_lib:format "Blocks cover ~p of ~p bytes"
                                     (list expected-offset expected-length))))))
    ((cons block rest)
     (let ((offset (maps:get 'offset block))
            (data (maps:get 'data block)))
       (if (not (== offset expected-offset))
         (piece-error 'gap-in-blocks
                      (lists:flatten
                        (io_lib:format "Expected offset ~p but got ~p"
                                       (list expected-offset offset))))
         (assemble-loop rest
                        expected-length
                        (+ expected-offset (byte_size data))
                        (cons data acc)))))))

(defun endgame? (piece-state threshold)
  (=< (+ (needed-count piece-state) (in-flight-count piece-state)) threshold))

(defun select-pieces-endgame (piece-state peer-available)
  (let* ((remaining
           (sets:union (maps:get 'needed piece-state)
                       (maps:get 'in-flight piece-state)))
          (selected (sets:intersection (as-set peer-available) remaining)))
    (tuple 'ok (lists:sort (sets:to_list selected)))))
```

### `src/download_worker.lfe`

```lfe
;;;; One download worker, ported from the stop path of
;;;; dev.cljtoc.supervision.session-supervisor.
;;;;
;;;; The Clojure supervisor cancels a future, waits on a done promise with a
;;;; shared deadline, and catches InterruptedException to persist a paused
;;;; record. Under OTP the supervisor calls terminate/2 synchronously, so the
;;;; pause happens in the callback and the supervisor's shutdown timeout is
;;;; the join bound.

(defmodule download_worker
  (behaviour gen_server)
  (export
   (child_spec 1)
   (code_change 3)
   (handle_call 3)
   (handle_cast 2)
   (handle_info 2)
   (init 1)
   (publish 4)
   (start_link 1)
   (terminate 2)))

(defun start_link (args)
  (gen_server:start_link 'download_worker args (list)))

(defun child_spec (args)
  (map 'id (maps:get 'id args)
       'start (tuple 'download_worker 'start_link (list args))
       'restart 'transient
       'shutdown 5000
       'type 'worker))

(defun publish (live sink id record)
  (ets:insert live (tuple id record))
  (case (is_pid sink)
    ('true
     (erlang:send sink (tuple 'record id record)))
    ('false 'ok))
  'ok)

(defun init (args)
  (process_flag 'trap_exit 'true)
  (let* ((id (maps:get 'id args))
          (live (maps:get 'live args))
          (sink (maps:get 'sink args))
          (record (maps:put 'state 'running (maps:get 'download args))))
    (publish live sink id record)
    (tuple 'ok (map 'id id 'live live 'sink sink 'record record))))

(defun handle_call
  (('record _from state)
   (tuple 'reply (maps:get 'record state) state))
  ((_request _from state)
   (tuple 'reply 'ok state)))

(defun handle_cast
  (((tuple 'progress record) state)
   (publish (maps:get 'live state)
            (maps:get 'sink state)
            (maps:get 'id state)
            record)
   (tuple 'noreply (maps:put 'record record state)))
  ((_message state)
   (tuple 'noreply state)))

(defun handle_info (_message state)
  (tuple 'noreply state))

(defun terminate (reason state)
  (if (pause-reason? reason)
    (publish (maps:get 'live state)
             (maps:get 'sink state)
             (maps:get 'id state)
             (maps:put 'state 'paused (maps:get 'record state)))
    'ok)
  'ok)

(defun code_change (_old-version state _extra)
  (tuple 'ok state))

(defun pause-reason?
  (('normal) 'true)
  (('shutdown) 'true)
  (((tuple 'shutdown _)) 'true)
  ((_) 'false))
```

### `src/session_sup.lfe`

```lfe
;;;; Session supervisor, ported from the stop path of
;;;; dev.cljtoc.supervision.session-supervisor.
;;;;
;;;; One child per download. stop/2 terminates every child; the supervisor
;;;; calls each worker's terminate/2 and waits for it, so the paused record
;;;; is already in the live table when stop returns. The supervisor's
;;;; one_for_one budget replaces the Clojure restart budget, and the child's
;;;; shutdown timeout replaces the shared join deadline.

(defmodule session_sup
  (behaviour supervisor)
  (export
   (init 1)
   (remove_child 3)
   (start_link 1)
   (stop 2)
   (stop_child 3)))

(defun start_link (opts)
  (supervisor:start_link 'session_sup opts))

(defun init (opts)
  (let* ((live (maps:get 'live opts))
          (sink (maps:get 'sink opts))
          (specs (child-specs (maps:get 'downloads opts) live sink)))
    (tuple 'ok (tuple (tuple 'one_for_one 10 10) specs))))

(defun child-specs (downloads live sink)
  (lists:filtermap
    (lambda (download) (child-spec download live sink))
    downloads))

(defun child-spec (download live sink)
  (let ((id (maps:get 'id download)))
    (case (maps:get 'state download)
      ('completed
       (download_worker:publish live sink id download)
       'false)
      ('failed
       (download_worker:publish live sink id download)
       'false)
      (_
       (tuple 'true
              (download_worker:child_spec
                (map 'id id 'download download 'live live 'sink sink)))))))

(defun stop (sup live)
  (lists:foreach
    (lambda (child) (supervisor:terminate_child sup (element 1 child)))
    (supervisor:which_children sup))
  (tuple 'ok (records live)))

(defun stop_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'nil)
    (_child
     (supervisor:terminate_child sup id)
     (case (ets:lookup live id)
       ((list (tuple _id record)) record)
       (_ 'nil)))))

(defun remove_child (sup id live)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'ok)
    (_child
     (supervisor:terminate_child sup id)
     (supervisor:delete_child sup id)
     (ets:delete live id)
     'ok)))

(defun records (live)
  (lists:sort
    (lambda (left right) (=< (maps:get 'id left) (maps:get 'id right)))
    (lists:map (lambda (pair) (element 2 pair)) (ets:tab2list live))))
```

### `test/torrent-pieces-tests.lfe`

```lfe
(defmodule torrent-pieces-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun elixir-ebin ()
  (case (os:getenv "ELIXIR_EBIN")
    ('false "/Users/kevin/code/elixir/torrent_client/_build/dev/lib/torrent_client/ebin")
    (path path)))

(defun ensure-elixir-domain ()
  (case (code:which 'Elixir.Torrent)
    ('non_existing (code:add_patha (elixir-ebin)))
    (_ 'ok)))

(defun set-of (list) (lists:sort (sets:to_list (sets:from_list list))))

(defun needed (state) (set-of (sets:to_list (maps:get 'needed state))))
(defun in-flight (state) (set-of (sets:to_list (maps:get 'in-flight state))))
(defun verified (state) (set-of (sets:to_list (maps:get 'verified state))))

(defun verified-state (total indices)
  (lists:foldl
   (lambda (idx state)
     (element 2 (torrent_pieces:mark-verified
                 (element 2 (torrent_pieces:mark-in-flight state idx))
                 idx)))
   (torrent_pieces:initial-piece-state total)
   indices))

(deftest initial-piece-state
  (let ((state (torrent_pieces:initial-piece-state 3)))
    (is-equal 3 (maps:get 'total-pieces state))
    (is-equal '(0 1 2) (needed state))
    (is-equal '() (in-flight state))
    (is-equal '() (verified state))
    (is-equal 3 (torrent_pieces:needed-count state))
    (is-equal 0 (torrent_pieces:in-flight-count state))
    (is-equal 0 (torrent_pieces:verified-count state))))

(deftest mark-in-flight
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (result (torrent_pieces:mark-in-flight state 2))
         (state2 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '(0 1 3 4) (needed state2))
    (is-equal '(2) (in-flight state2))
    (is-equal 4 (torrent_pieces:needed-count state2))
    (is-equal 1 (torrent_pieces:in-flight-count state2))
    (is-equal '(0 1 2 3 4) (needed state))
    (is-equal 5 (torrent_pieces:needed-count state))))

(deftest mark-verified
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (result (torrent_pieces:mark-verified state2 2))
         (state3 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (in-flight state3))
    (is-equal '(2) (verified state3))
    (is-equal 1 (torrent_pieces:verified-count state3))))

(deftest requeue-piece
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (result (torrent_pieces:requeue-piece state2 2))
         (state3 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (in-flight state3))
    (is-equal '(0 1 2 3 4) (needed state3))
    (is-equal 5 (torrent_pieces:needed-count state3))))

(deftest requeue-verified
  (let* ((state (verified-state 5 '(2)))
         (result (torrent_pieces:requeue-verified state 2))
         (state2 (element 2 result)))
    (is-equal 'ok (element 1 result))
    (is-equal '() (verified state2))
    (is-equal '(0 1 2 3 4) (needed state2))
    (is-equal 5 (torrent_pieces:needed-count state2))))

(deftest invalid-transitions
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 2)))
         (state3 (element 2 (torrent_pieces:mark-verified state2 2)))
         (already-in-flight (torrent_pieces:mark-in-flight state2 2))
         (already-verified (torrent_pieces:mark-in-flight state3 2))
         (not-in-flight (torrent_pieces:mark-verified state 2))
         (not-in-flight-requeue (torrent_pieces:requeue-piece state 2))
         (not-verified (torrent_pieces:requeue-verified state 2)))
    (is-equal 'invalid-transition (maps:get 'error already-in-flight))
    (is (is_list (maps:get 'message already-in-flight)))
    (is-equal 'invalid-transition (maps:get 'error already-verified))
    (is-equal 'invalid-transition (maps:get 'error not-in-flight))
    (is-equal 'invalid-transition (maps:get 'error not-in-flight-requeue))
    (is-equal 'invalid-transition (maps:get 'error not-verified))))

(deftest complete?
  (is-equal 'false (torrent_pieces:complete? (torrent_pieces:initial-piece-state 3)))
  (is-equal 'false (torrent_pieces:complete? (verified-state 3 '(0))))
  (is-equal 'true (torrent_pieces:complete? (verified-state 3 '(0 1 2)))))

(deftest select-piece-rarest
  (let ((state (torrent_pieces:initial-piece-state 10))
        (peer-av (sets:from_list '(2 5)))
        (all-peers (list (sets:from_list '(0 2 5))
                         (sets:from_list '(2 3))
                         (sets:from_list '(0 1 2)))))
    (is-equal (tuple 'ok 5)
              (torrent_pieces:select-piece state peer-av all-peers))))

(deftest select-piece-tie-break
  (let ((state (torrent_pieces:initial-piece-state 10))
        (peer-av (sets:from_list '(3 7)))
        (all-peers (list (sets:from_list '(3 7))
                         (sets:from_list '(3 7)))))
    (is-equal (tuple 'ok 3)
              (torrent_pieces:select-piece state peer-av all-peers))))

(deftest select-piece-no-overlap
  (let ((state (verified-state 5 '(0 1 2 3 4))))
    (is-equal (tuple 'ok 'nil)
              (torrent_pieces:select-piece state
                                           (sets:from_list '(0 1 2 3 4))
                                           (list (sets:from_list '(0 1 2 3 4)))))
    (is-equal (tuple 'ok 'nil)
              (torrent_pieces:select-piece (torrent_pieces:initial-piece-state 5)
                                           (sets:new)
                                           '()))))

(deftest select-piece-excludes-in-flight
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 0)))
         (state3 (element 2 (torrent_pieces:mark-in-flight state2 1)))
         (result (torrent_pieces:select-piece state3
                                              (sets:from_list '(0 1 2 3))
                                              (list (sets:from_list '(0 1 2 3))))))
    (is-equal (tuple 'ok 2) result)))

(deftest piece-length
  (is-equal 524288 (torrent_pieces:piece-length 0 524288 1056768))
  (is-equal 524288 (torrent_pieces:piece-length 1 524288 1056768))
  (is-equal 8192 (torrent_pieces:piece-length 2 524288 1056768))
  (is-equal 524288 (torrent_pieces:piece-length 1 524288 1048576))
  (is-equal 1 (torrent_pieces:piece-length 0 16384 1))
  (is-equal 0 (torrent_pieces:piece-length 5 524288 1048576))
  (is-equal 0 (torrent_pieces:piece-length 9007199254740993 16384 1000)))

(deftest piece-blocks
  (let* ((result (torrent_pieces:piece-blocks 0 524288 1073741824))
         (blocks (element 2 result)))
    (is-equal 32 (length blocks))
    (is-equal '(16384) (lists:usort (lists:map
                                     (lambda (b) (maps:get 'length b))
                                     blocks)))
    (is-equal 0 (maps:get 'offset (lists:nth 1 blocks)))
    (is-equal 507904 (maps:get 'offset (lists:last blocks))))
  (let* ((result (torrent_pieces:piece-blocks 0 20000 20000))
         (blocks (element 2 result)))
    (is-equal 2 (length blocks))
    (is-equal 16384 (maps:get 'length (lists:nth 1 blocks)))
    (is-equal 3616 (maps:get 'length (lists:nth 2 blocks)))
    (is-equal 16384 (maps:get 'offset (lists:nth 2 blocks))))
  (let* ((result (torrent_pieces:piece-blocks 2 524288 1056768))
         (blocks (element 2 result)))
    (is-equal 1 (length blocks))
    (is-equal (map 'piece-index 2 'offset 0 'length 8192) (lists:nth 1 blocks)))
  (let* ((result (torrent_pieces:piece-blocks 0 16384 1))
         (blocks (element 2 result)))
    (is-equal 1 (length blocks))
    (is-equal 1 (maps:get 'length (lists:nth 1 blocks)))))

(deftest piece-blocks-out-of-range
  (let ((result (torrent_pieces:piece-blocks 5 524288 1048576)))
    (is-equal 'invalid-input (maps:get 'error result))
    (is (is_list (maps:get 'message result))))
  (let ((result (torrent_pieces:piece-blocks -1 524288 1048576)))
    (is-equal 'invalid-input (maps:get 'error result))))

(deftest piece-blocks-last-piece-past-two-to-53
  (let* ((total-length 9007199254740993)
         (result (torrent_pieces:piece-blocks 4503599627370496 2 total-length)))
    (is-equal 'ok (element 1 result))
    (is-equal 1 (length (element 2 result))) ))

(deftest verify-piece-modes
  (ensure-elixir-domain)
  (let* ((data (list_to_binary (list 1 2 3 4 5)))
         (hash (apply 'Elixir.Utils 'sha_sum (list data))))
    (is-equal (tuple 'ok 5) (torrent_pieces:verify-piece 5 data hash))
    (is-equal 'hash-mismatch
              (maps:get 'error
                        (torrent_pieces:verify-piece 5 (list_to_binary (list 1 2 3 4 99)) hash)))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary '()) (list_to_binary (lists:duplicate 20 0)))))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary (list 1)) (list_to_binary (list 1)))))
    (is-equal 'invalid-input
              (maps:get 'error
                        (torrent_pieces:verify-piece 0 (list_to_binary (list 1)) (list_to_binary (lists:duplicate 21 0)))))))

(deftest endgame
  (let* ((state (verified-state 5 '(0 1 2)))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 3))))
    (is-equal 'true (torrent_pieces:endgame? state2 20))
    (is-equal 'true (torrent_pieces:endgame? state2 2))
    (is-equal 'false (torrent_pieces:endgame? (torrent_pieces:initial-piece-state 50) 20))
    (is-equal 'true (torrent_pieces:endgame? (verified-state 3 '(0 1 2)) 0))))

(deftest select-pieces-endgame
  (let* ((state (torrent_pieces:initial-piece-state 5))
         (state2 (element 2 (torrent_pieces:mark-in-flight state 0))))
    (is-equal (tuple 'ok '(0 2 4))
              (torrent_pieces:select-pieces-endgame
               state2 (sets:from_list '(0 2 4 99))))
    (is-equal (tuple 'ok '())
              (torrent_pieces:select-pieces-endgame
               (verified-state 3 '(0 1 2)) (sets:from_list '(0 1 2))))))

(deftest assemble-piece-malformed
  (is-equal 'invalid-block
            (maps:get 'error (torrent_pieces:assemble-piece (list (map 'offset 0)) 4)))
  (is-equal 'invalid-block
            (maps:get 'error (torrent_pieces:assemble-piece (list 42) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'data (list_to_binary (list 1)))) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data "abcd")) 4)))
  (is-equal 'block-overflow
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data (list_to_binary (lists:duplicate 8 0)))) 4)))
  (is-equal 'invalid-block
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset -1 'data (list_to_binary (list 1)))) 4)))
  (is-equal 'invalid-input
            (maps:get 'error (torrent_pieces:assemble-piece '() 4)))
  (is-equal 'gap-in-blocks
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 2 'data (list_to_binary (list 3 4)))) 4)))
  (is-equal 'incomplete-piece
            (maps:get 'error
                      (torrent_pieces:assemble-piece
                       (list (map 'offset 0 'data (list_to_binary (list 1 2)))) 4)))
  (is-equal (tuple 'ok (list_to_binary (list 1 2 3 4)))
            (torrent_pieces:assemble-piece
             (list (map 'offset 0 'data (list_to_binary (list 1 2)))
                   (map 'offset 2 'data (list_to_binary (list 3 4)))) 4)))
```

### `test/session-sup-tests.lfe`

```lfe
(defmodule session-sup-tests
  (behaviour ltest-unit)
  (export all))

(include-lib "ltest/include/ltest-macros.lfe")

(defun download (id state)
  (map 'id id 'state state))

(defun live-table ()
  (ets:new 'live (list 'set 'public)))

(defun start-session (downloads live)
  (session_sup:start_link (map 'downloads downloads
                               'live live
                               'sink 'undefined)))

(defun pid-for (sup id)
  (case (lists:keyfind id 1 (supervisor:which_children sup))
    ('false 'undefined)
    (child (element 2 child))))

(defun states (records)
  (lists:map (lambda (record) (maps:get 'state record)) records))

(defun live-record (live id)
  (case (ets:lookup live id)
    ((list (tuple _id record)) record)
    (_ 'undefined)))

(defun wait-for-new-pid
  ((_sup _id _old 0) 'undefined)
  ((sup id old attempts)
   (let ((pid (pid-for sup id)))
     (if (and (is_pid pid) (/= pid old))
       pid
       (progn
         (timer:sleep 10)
         (wait-for-new-pid sup id old (- attempts 1)))))))

(deftest stop-pauses-every-child
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-a (pid-for sup "a"))
         (pid-b (pid-for sup "b"))
         ((tuple 'ok records) (session_sup:stop sup live)))
    (is (is_pid pid-a))
    (is (is_pid pid-b))
    (is (not (is_process_alive pid-a)))
    (is (not (is_process_alive pid-b)))
    (is-equal 2 (length records))
    (is-equal (list 'paused 'paused) (states records))))

(deftest stop-child-pauses-only-that-child
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b"))
         (record (session_sup:stop_child sup "a" live)))
    (is-equal 'paused (maps:get 'state record))
    (is-equal 'undefined (pid-for sup "a"))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest stop-child-unknown-id-is-nil
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)) live)))
    (is-equal 'nil (session_sup:stop_child sup "missing" live))))

(deftest remove-child-forgets-the-record
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (pid-b (pid-for sup "b")))
    (is-equal 'ok (session_sup:remove_child sup "a" live))
    (is-equal 'undefined (pid-for sup "a"))
    (is-equal [] (ets:lookup live "a"))
    (is-equal pid-b (pid-for sup "b"))
    (is (is_process_alive pid-b))))

(deftest crashed-child-restarts-alone
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "a" 'downloading)
                                               (download "b" 'downloading))
                                         live))
         (old-pid (pid-for sup "a"))
         (sibling (pid-for sup "b")))
    (exit old-pid 'kill)
    (let ((new-pid (wait-for-new-pid sup "a" old-pid 100)))
      (is (is_pid new-pid))
      (is (/= old-pid new-pid))
      (is-equal sibling (pid-for sup "b"))
      (is (is_process_alive sibling))
      (is-equal 'running (maps:get 'state (live-record live "a"))))))

(deftest terminal-download-is-kept-but-not-run
  (let* ((live (live-table))
         ((tuple 'ok sup) (start-session (list (download "done" 'completed)
                                               (download "live" 'downloading))
                                         live))
         (children (supervisor:which_children sup))
         ((tuple 'ok records) (session_sup:stop sup live)))
    (is-equal 1 (length children))
    (is-equal "live" (element 1 (lists:nth 1 children)))
    (is-equal 'completed (maps:get 'state (live-record live "done")))
    (is-equal (list 'completed 'paused) (states records))))
```

### `test/prop_torrent_pieces.erl`

```erlang
%% PropEr properties for the LFE piece module.
%%
%% Oracles are computed from the declared inputs (lengths, peer sets,
%% index ranges), never by calling the function under test again.
%%
%% PropEr generators are size-parameterised: choose(1, 1048576) draws from
%% the current size, which starts at 1. Large domains are resized explicitly
%% or the properties only ever see tiny values.
-module(prop_torrent_pieces).

-include_lib("proper/include/proper.hrl").

-export([prop_initial_state/0,
         prop_partition_invariant/0,
         prop_complete_matches_verified_count/0,
         prop_endgame_matches_remaining_count/0,
         prop_select_piece_is_safe/0,
         prop_select_piece_is_rarest_first/0,
         prop_endgame_selection/0,
         prop_piece_lengths_sum_to_total/0,
         prop_blocks_cover_the_piece/0,
         prop_verify_accepts_matching_hash/0,
         prop_verify_rejects_bad_hash_length/0,
         prop_invalid_transitions_are_rejected/0]).

piece_state() ->
    ?LET({Total, Buckets},
         {choose(1, 12), vector(12, choose(0, 2))},
         begin
             Assigned = lists:zip(lists:seq(0, Total - 1),
                                  lists:sublist(Buckets, Total)),
             Bucket = fun(N) ->
                              sets:from_list([I || {I, B} <- Assigned, B =:= N])
                      end,
             #{'total-pieces' => Total,
               'needed' => Bucket(0),
               'in-flight' => Bucket(1),
               'verified' => Bucket(2)}
         end).

index_set() ->
    ?LET(Indices, list(choose(0, 30)), sets:from_list(Indices)).

peer_lists() ->
    ?LET(Peers, list(index_set()), Peers).

prop_initial_state() ->
    ?FORALL(Total, choose(1, 50),
            begin
                State = torrent_pieces:'initial-piece-state'(Total),
                maps:get('total-pieces', State) =:= Total
                    andalso maps:get('needed', State)
                            =:= sets:from_list(lists:seq(0, Total - 1))
                    andalso sets:size(maps:get('in-flight', State)) =:= 0
                    andalso sets:size(maps:get('verified', State)) =:= 0
            end).

prop_partition_invariant() ->
    ?FORALL({State, Index},
            {piece_state(), choose(0, 30)},
            begin
                Total = maps:get('total-pieces', State),
                Piece = Index rem Total,
                Step = fun(S, Result) ->
                               case Result of
                                   {ok, Next} -> Next;
                                   #{'error' := _} -> S
                               end
                       end,
                S1 = Step(State, torrent_pieces:'mark-in-flight'(State, Piece)),
                S2 = Step(S1, torrent_pieces:'mark-verified'(S1, Piece)),
                S3 = Step(S2, torrent_pieces:'requeue-verified'(S2, Piece)),
                S4 = Step(S1, torrent_pieces:'requeue-piece'(S1, Piece)),
                lists:all(fun(S) -> partition_holds(S, Total) end,
                          [State, S1, S2, S3, S4])
            end).

partition_holds(State, Total) ->
    Needed = maps:get('needed', State),
    InFlight = maps:get('in-flight', State),
    Verified = maps:get('verified', State),
    All = sets:to_list(sets:union(Needed, sets:union(InFlight, Verified))),
    sets:size(Needed) + sets:size(InFlight) + sets:size(Verified) =:= Total
        andalso lists:all(fun(I) -> I >= 0 andalso I < Total end, All).

prop_complete_matches_verified_count() ->
    ?FORALL(State, piece_state(),
            torrent_pieces:'complete?'(State)
                =:= (sets:size(maps:get('verified', State))
                     =:= maps:get('total-pieces', State))).

prop_endgame_matches_remaining_count() ->
    ?FORALL({State, Threshold},
            {piece_state(), choose(0, 20)},
            begin
                Remaining = sets:size(maps:get('needed', State))
                    + sets:size(maps:get('in-flight', State)),
                torrent_pieces:'endgame?'(State, Threshold)
                    =:= (Remaining =< Threshold)
            end).

prop_select_piece_is_safe() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Needed = maps:get('needed', State),
                Result = torrent_pieces:'select-piece'(State, Available, Peers),
                case Result of
                    {ok, nil} ->
                        sets:size(sets:intersection(Available, Needed)) =:= 0;
                    {ok, Piece} ->
                        sets:is_element(Piece, Needed)
                            andalso sets:is_element(Piece, Available);
                    _ ->
                        false
                end
            end).

prop_select_piece_is_rarest_first() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Needed = maps:get('needed', State),
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Candidates = lists:sort(sets:to_list(sets:intersection(Available, Needed))),
                Frequency = fun(I) ->
                                    length([P || P <- Peers, sets:is_element(I, P)])
                            end,
                Result = torrent_pieces:'select-piece'(State, Available, Peers),
                case Candidates of
                    [] ->
                        Result =:= {ok, nil};
                    _ ->
                        Best = lists:min([{Frequency(I), I} || I <- Candidates]),
                        case Result of
                            {ok, Piece} -> {Frequency(Piece), Piece} =:= Best;
                            _ -> false
                        end
                end
            end).

prop_endgame_selection() ->
    ?FORALL({State, Peers},
            {piece_state(), peer_lists()},
            begin
                Available = sets:from_list(lists:append([sets:to_list(P) || P <- Peers])),
                Remaining = sets:union(maps:get('needed', State),
                                       maps:get('in-flight', State)),
                Expected = lists:sort(sets:to_list(
                                          sets:intersection(Available, Remaining))),
                torrent_pieces:'select-pieces-endgame'(State, Available)
                    =:= {ok, Expected}
            end).

boundary_length() ->
    ?LET({Multiple, Delta},
         {choose(1, 64), oneof([0, 1, -1])},
         max(1, Multiple * 16384 + Delta)).

prop_piece_lengths_sum_to_total() ->
    ?FORALL({TotalPieces, Standard, Remainder},
            {choose(1, 25),
             resize(65536, choose(1, 65536)),
             resize(65536, choose(1, 65536))},
            begin
                ActualRemainder = min(Remainder, Standard),
                TotalLength = (TotalPieces - 1) * Standard + ActualRemainder,
                Lengths = [torrent_pieces:'piece-length'(I, Standard, TotalLength)
                           || I <- lists:seq(0, TotalPieces - 1)],
                lists:sum(Lengths) =:= TotalLength
                    andalso lists:all(fun(L) -> L >= 1 andalso L =< Standard end, Lengths)
                    andalso torrent_pieces:'piece-length'(TotalPieces, Standard, TotalLength) =:= 0
            end).

prop_blocks_cover_the_piece() ->
    ?FORALL({TotalPieces, Standard, Remainder, Index},
            {choose(1, 20),
             oneof([boundary_length(),
                    resize(1048576, choose(1, 1048576))]),
             oneof([boundary_length(),
                    resize(1048576, choose(1, 1048576))]),
             choose(0, 19)},
            begin
                ActualRemainder = min(Remainder, Standard),
                TotalLength = (TotalPieces - 1) * Standard + ActualRemainder,
                PieceIndex = Index rem TotalPieces,
                PieceStart = PieceIndex * Standard,
                PieceEnd = min((PieceIndex + 1) * Standard, TotalLength),
                Expected = PieceEnd - PieceStart,
                case torrent_pieces:'piece-blocks'(PieceIndex, Standard, TotalLength) of
                    {ok, Blocks} ->
                        Lengths = [maps:get('length', B) || B <- Blocks],
                        Offsets = [maps:get('offset', B) || B <- Blocks],
                        ExpectedOffsets = running_offsets(Lengths),
                        lists:sum(Lengths) =:= Expected
                            andalso lists:all(fun(L) -> L >= 1 andalso L =< 16384 end, Lengths)
                            andalso Offsets =:= ExpectedOffsets
                            andalso length(Blocks) =:= (Expected + 16383) div 16384;
                    _ ->
                        false
                end
            end).

prop_verify_accepts_matching_hash() ->
    ?FORALL({Data, PieceIndex},
            {non_empty(binary()), choose(0, 999)},
            begin
                code:add_patha(elixir_ebin()),
                Hash = crypto:hash(sha, Data),
                torrent_pieces:'verify-piece'(PieceIndex, Data, Hash)
                    =:= {ok, PieceIndex}
            end).

prop_verify_rejects_bad_hash_length() ->
    ?FORALL({Data, Length},
            {non_empty(binary()), oneof([choose(0, 19), choose(21, 40)])},
            begin
                code:add_patha(elixir_ebin()),
                Hash = case Length < 20 of
                           true -> binary:part(crypto:hash(sha, Data), 0, Length);
                           false -> crypto:strong_rand_bytes(Length)
                       end,
                case torrent_pieces:'verify-piece'(0, Data, Hash) of
                    #{'error' := 'invalid-input'} -> true;
                    _ -> false
                end
            end).

prop_invalid_transitions_are_rejected() ->
    ?FORALL({State, Index},
            {piece_state(), choose(0, 30)},
            begin
                Total = maps:get('total-pieces', State),
                Piece = Index rem Total,
                Needed = maps:get('needed', State),
                InFlight = maps:get('in-flight', State),
                Verified = maps:get('verified', State),
                Check = fun(Result, InSource) ->
                                case InSource of
                                    true -> element(1, Result) =:= ok;
                                    false -> maps:get('error', Result) =:= 'invalid-transition'
                                end
                        end,
                Check(torrent_pieces:'mark-in-flight'(State, Piece),
                      sets:is_element(Piece, Needed))
                    andalso Check(torrent_pieces:'mark-verified'(State, Piece),
                                  sets:is_element(Piece, InFlight))
                    andalso Check(torrent_pieces:'requeue-piece'(State, Piece),
                                  sets:is_element(Piece, InFlight))
                    andalso Check(torrent_pieces:'requeue-verified'(State, Piece),
                                  sets:is_element(Piece, Verified))
            end).

running_offsets(Lengths) ->
    {Reversed, _} = lists:foldl(fun(L, {Acc, Sum}) -> {[Sum | Acc], Sum + L} end,
                                {[], 0},
                                Lengths),
    lists:reverse(Reversed).

elixir_ebin() ->
    case os:getenv("ELIXIR_EBIN") of
        false -> "/Users/kevin/code/elixir/torrent_client/_build/dev/lib/torrent_client/ebin";
        Path -> Path
    end.
```
