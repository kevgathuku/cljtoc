# Tasks: Piece Management

**Feature**: 005-piece-management
**Branch**: `005-piece-management`
**Generated**: 2026-02-21

---

## Overview

Implementation tasks for BitTorrent piece management — pure domain functions for piece-status state machine, rarest-first selection, block decomposition, SHA-1 integrity verification, and endgame mode. All code lives in `dev.cljtoc.domain.pieces` with full clojure.spec coverage and generative tests.

---

## Phase 1: Setup

**Goal**: Initialize source and test files with namespace declarations

- [x] T001 Create `src/dev/cljtoc/domain/pieces.clj` with namespace declaration requiring `[clojure.spec.alpha :as s]` and `[dev.cljtoc.domain.bencode :as bencode]`
- [x] T002 Create `test/dev/cljtoc/domain/pieces_test.clj` with namespace declaration requiring `[clojure.test :refer :all]`, `[clojure.spec.alpha :as s]`, `[clojure.test.check.generators :as gen]`, `[clojure.test.check.properties :as prop]`, `[clojure.test.check.clojure-test :refer [defspec]]`, and `[dev.cljtoc.domain.pieces :as pieces]`

**Independent Test Criteria**: Both files load without errors in the REPL

---

## Phase 2: Foundational — Records and Specs

**Goal**: Define core data structures that all user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T003 Define `PieceState` record in `src/dev/cljtoc/domain/pieces.clj` with fields: `total-pieces`, `needed`, `in-flight`, `verified`
- [x] T004 [P] Define `Block` record in `src/dev/cljtoc/domain/pieces.clj` with fields: `piece-index`, `offset`, `length`
- [x] T005 [P] Add `s/def` specs for primitive domain types in `src/dev/cljtoc/domain/pieces.clj`: `::piece-index` (nat-int?), `::piece-index-set` (set of nat-int), `::total-pieces` (pos-int?), `::length` (1 to 16384)
- [x] T006 [P] Add `s/def` composite specs in `src/dev/cljtoc/domain/pieces.clj`: `::piece-state` (keys: total-pieces, needed, in-flight, verified) and `::block` (keys: piece-index, offset, length)

**Checkpoint**: Records and specs defined — user story implementation can now begin

---

## Phase 3: User Story 1 — Track Piece Download Status (Priority: P1) 🎯 MVP

**Goal**: Immutable PieceState state machine — init, transitions, queries, completion detection

**Independent Test**: Create state for N pieces; apply mark-in-flight, mark-verified, requeue-piece; assert correct counts and that original state is unchanged

**Acceptance Criteria** (from spec.md):
1. All N pieces in "needed" after init; none in-flight or verified
2. `mark-in-flight` moves piece: needed → in-flight; needed count decreases by 1
3. `mark-verified` moves piece: in-flight → verified; in-flight count decreases by 1
4. `requeue-piece` moves piece: in-flight → needed on failure
5. Prior state snapshot is unmodified after any transition (pure, no mutation)
6. `complete?` returns `true` only when all pieces verified

### Implementation for User Story 1

- [x] T007 [US1] Implement `initial-piece-state` in `src/dev/cljtoc/domain/pieces.clj`: returns `PieceState` with `needed = #{0..N-1}`, `in-flight = #{}`, `verified = #{}`
- [x] T008 [US1] [P] Implement query functions `needed-count`, `in-flight-count`, `verified-count` in `src/dev/cljtoc/domain/pieces.clj`: each returns count of the respective set
- [x] T009 [US1] [P] Implement `complete?` predicate in `src/dev/cljtoc/domain/pieces.clj`: `true` iff `(count verified) = total-pieces`
- [x] T010 [US1] Implement `mark-in-flight` in `src/dev/cljtoc/domain/pieces.clj`: moves piece-index from `needed` → `in-flight`; returns `{:error :invalid-transition}` if not in `needed`
- [x] T011 [US1] Implement `mark-verified` in `src/dev/cljtoc/domain/pieces.clj`: moves piece-index from `in-flight` → `verified`; returns `{:error :invalid-transition}` if not in `in-flight`
- [x] T012 [US1] Implement `requeue-piece` in `src/dev/cljtoc/domain/pieces.clj`: moves piece-index from `in-flight` → `needed`; returns `{:error :invalid-transition}` if not in `in-flight`
- [x] T013 [US1] [P] Add `s/fdef` for `initial-piece-state` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariant: `(= total-pieces (count (:needed ret)))`
- [x] T014 [US1] [P] Add `s/fdef` for `mark-in-flight`, `mark-verified`, `requeue-piece` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariant: `:total-pieces` unchanged; `(+ needed in-flight verified)` = `total-pieces` in success result
- [x] T015 [US1] [P] Add test: `initial-piece-state` with N=3 returns state with `needed=#{0 1 2}`, empty `in-flight` and `verified` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T016 [US1] [P] Add test: `mark-in-flight` on piece in `needed` → new state has piece in `in-flight`, `needed-count` decremented by 1 in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T017 [US1] [P] Add test: `mark-verified` on piece in `in-flight` → new state has piece in `verified`, `in-flight-count` decremented by 1 in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T018 [US1] [P] Add test: `requeue-piece` on failed piece → piece returns to `needed`, `in-flight-count` decremented by 1 in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T019 [US1] [P] Add test: invalid transitions (e.g., `mark-in-flight` on already in-flight piece) return `{:error :invalid-transition :message string}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T020 [US1] [P] Add test: original `PieceState` record is unmodified after any transition — verify prior state is equal to pre-transition value in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T021 [US1] [P] Add test: `complete?` returns `false` during download; returns `true` after all pieces verified in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T022 [US1] [P] Add generative test (`defspec state-partition-invariant`): for random total and random piece-index, after any transition sequence `(+ needed-count in-flight-count verified-count) = total-pieces` in `test/dev/cljtoc/domain/pieces_test.clj`

**Checkpoint**: US1 fully functional — PieceState machine verified independently with 100% of acceptance criteria passing

---

## Phase 4: User Story 2 — Select Next Piece to Download (Priority: P2)

**Goal**: Rarest-first piece selection given peer availability sets

**Independent Test**: Provide PieceState with known needed pieces and peer bitfield sets; verify rarest piece selected, in-flight pieces excluded, and {:ok nil} returned when no overlap

**Acceptance Criteria** (from spec.md):
1. Selected piece is one the peer actually has and is in `needed`
2. Rarest piece (fewest peers have it) is preferred
3. Peer with no needed pieces → `{:ok nil}`
4. Equally rare pieces → any valid selection is acceptable (lowest index tie-break)
5. In-flight pieces are not selected in normal mode

### Implementation for User Story 2

- [x] T023 [US2] Implement `select-piece` in `src/dev/cljtoc/domain/pieces.clj`: candidates = `(intersection peer-available needed)`; count frequency across `all-peers-available`; return lowest-index minimum-frequency candidate as `{:ok piece-index}` or `{:ok nil}`
- [x] T024 [US2] [P] Add `s/fdef` for `select-piece` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariant: non-nil result is always in `(:needed piece-state)` and `peer-available`
- [x] T025 [US2] [P] Add test: rarest piece is selected — provide 3 peers where piece 5 is held by 1 peer and piece 2 by all 3; verify piece 5 is selected in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T026 [US2] [P] Add test: peer whose bitfield has no needed pieces returns `{:ok nil}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T027 [US2] [P] Add test: tie-breaking — two equally rare pieces → lowest piece index is selected in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T028 [US2] [P] Add test: in-flight pieces are excluded — piece marked in-flight is not returned by `select-piece` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T029 [US2] [P] Add test: empty peer list (`all-peers-available = []`) returns `{:ok nil}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T030 [US2] [P] Add generative test (`defspec select-piece-safety`): when result is non-nil, selected piece is always in `peer-available` and in `(:needed state)` in `test/dev/cljtoc/domain/pieces_test.clj`

**Checkpoint**: US2 fully functional — rarest-first selection independently verified

---

## Phase 5: User Story 3 — Decompose Piece into Blocks (Priority: P3)

**Goal**: Pure arithmetic decomposition of any piece into ≤16 KiB block requests

**Independent Test**: Provide piece-index, standard-piece-length, total-length; verify blocks are contiguous, non-overlapping, each ≤16384 bytes, and sum to exact piece size

**Acceptance Criteria** (from spec.md):
1. Piece size = exact multiple of 16 KiB → all blocks are exactly 16 KiB
2. Piece size not a multiple of 16 KiB → last block covers remainder bytes
3. Last piece of torrent (shorter than standard) → blocks sum to actual shorter length
4. Any valid piece → block lengths sum to exact piece byte count
5. Single-byte piece → exactly one 1-byte block
6. Out-of-range piece index → `{:error :invalid-input}`

### Implementation for User Story 3

- [x] T031 [US3] Implement `piece-blocks` in `src/dev/cljtoc/domain/pieces.clj`: compute actual piece length (last piece may be shorter); generate `Block` records at offsets 0, 16384, 32768… capped at 16384 bytes each; validate piece-index in range
- [x] T032 [US3] [P] Add `s/fdef` for `piece-blocks` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariants: all blocks ≤16384 bytes; offsets are contiguous; sum of lengths = actual piece size
- [x] T033 [US3] [P] Add test: `(piece-blocks 0 524288 1073741824)` → 32 blocks each `{:length 16384}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T034 [US3] [P] Add test: piece length not a multiple of 16 KiB → all-but-last blocks are 16384, last block = remainder in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T035 [US3] [P] Add test: last piece of torrent shorter than standard — `(piece-blocks 2 524288 1056768)` → `[{:piece-index 2 :offset 0 :length 8192}]` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T036 [US3] [P] Add test: 1-byte piece → exactly one `Block` with `:length 1` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T037 [US3] [P] Add test: out-of-range piece index (≥ total piece count) returns `{:error :invalid-input :message string}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T038 [US3] [P] Add generative test (`defspec piece-blocks-coverage`): for random valid piece-index/piece-length/total-length, `(reduce + (map :length blocks)) = actual-piece-length` in `test/dev/cljtoc/domain/pieces_test.clj`

**Checkpoint**: US3 fully functional — block decomposition independently verified for standard, remainder, and last-piece cases

---

## Phase 6: User Story 4 — Verify Piece Integrity (Priority: P4)

**Goal**: SHA-1 verification of assembled piece bytes against expected hash from torrent metadata

**Independent Test**: Provide byte array and expected SHA-1 hash; verify correct data passes, corrupted data fails, and result carries the piece-index

**Acceptance Criteria** (from spec.md):
1. Matching bytes → `{:ok piece-index}`
2. Single-byte corruption → `{:error :hash-mismatch}`
3. Incorrect length → `{:error :hash-mismatch}`
4. Empty bytes → `{:error :invalid-input}`
5. Expected hash not exactly 20 bytes → `{:error :invalid-input}`

### Implementation for User Story 4

- [x] T039 [US4] Implement `verify-piece` in `src/dev/cljtoc/domain/pieces.clj`: validate inputs (non-empty bytes, 20-byte hash); call `(bencode/sha1-hash assembled-bytes)`; compare with `expected-hash`; return `{:ok piece-index}` or `{:error :hash-mismatch :piece-index piece-index :message string}`
- [x] T040 [US4] [P] Add `s/fdef` for `verify-piece` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariant: when `:ok`, returned piece-index equals input piece-index
- [x] T041 [US4] [P] Add test: `(verify-piece 5 data (bencode/sha1-hash data))` → `{:ok 5}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T042 [US4] [P] Add test: one byte changed in assembled bytes → `{:error :hash-mismatch :piece-index 5}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T043 [US4] [P] Add test: empty byte array returns `{:error :invalid-input :message string}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T044 [US4] [P] Add test: expected hash of length ≠ 20 bytes returns `{:error :invalid-input :message string}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T045 [US4] [P] Add generative test (`defspec verify-piece-referentially-transparent`): for random byte arrays, `verify-piece` called twice with the same inputs returns the same result in `test/dev/cljtoc/domain/pieces_test.clj`

**Checkpoint**: US4 fully functional — SHA-1 verification independently verified; correct data passes, corrupted data fails deterministically

---

## Phase 7: User Story 5 — Detect and Enter Endgame Mode (Priority: P5)

**Goal**: Endgame detection predicate and in-flight+needed piece selection for duplicate requesting

**Independent Test**: Provide PieceState and threshold; verify endgame? returns correct boolean; verify select-pieces-endgame returns needed AND in-flight pieces held by the peer

**Acceptance Criteria** (from spec.md):
1. `(+ needed in-flight) ≤ threshold` → `endgame?` returns `true`
2. Remaining > threshold → `endgame?` returns `false`
3. In endgame: `select-pieces-endgame` returns pieces in both `needed` and `in-flight`
4. Completed torrent (0 remaining) satisfies endgame? for any threshold ≥ 0

### Implementation for User Story 5

- [x] T046 [US5] Implement `endgame?` predicate in `src/dev/cljtoc/domain/pieces.clj`: returns `(+ (count needed) (count in-flight)) <= threshold`
- [x] T047 [US5] Implement `select-pieces-endgame` in `src/dev/cljtoc/domain/pieces.clj`: returns `{:ok [piece-index]}` for all pieces peer has that are in `needed` OR `in-flight`
- [x] T048 [US5] [P] Add `s/fdef` for `endgame?` and `select-pieces-endgame` in `src/dev/cljtoc/domain/pieces.clj` with `:fn` invariants: endgame result is boolean; endgame selection result pieces are all in `(union needed in-flight)`
- [x] T049 [US5] [P] Add test: `endgame?` returns `true` when `(+ needed in-flight)` ≤ threshold (e.g., 3 remaining, threshold=20) in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T050 [US5] [P] Add test: `endgame?` returns `false` when remaining pieces exceed threshold (e.g., 50 remaining, threshold=20) in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T051 [US5] [P] Add test: completed torrent `(verified-count = total-pieces)` → `endgame?` returns `true` for any positive threshold in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T052 [US5] [P] Add test: `select-pieces-endgame` returns both needed AND in-flight pieces that peer has in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T053 [US5] [P] Add test: `select-pieces-endgame` returns `{:ok []}` when peer has nothing in `needed` or `in-flight` in `test/dev/cljtoc/domain/pieces_test.clj`

**Checkpoint**: US5 fully functional — endgame mode independently verified

---

## Phase 8: Polish & Cross-Cutting Concerns

**Goal**: Edge cases, constitution gate check, coverage verification

- [x] T054 [P] Add edge case test: torrent with exactly 1 piece — init, mark-in-flight, mark-verified, complete? all work correctly in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T055 [P] Add edge case test: two peers with identical bitfields — `select-piece` returns deterministic lowest-index result in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T056 [P] Add edge case test: all peers lack a specific needed piece — `select-piece` correctly returns `{:ok nil}` in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T057 [P] Add edge case test: `piece-blocks` for piece at index 0 in a 1-byte total torrent — returns one block of length 1 in `test/dev/cljtoc/domain/pieces_test.clj`
- [x] T058 Verify constitution gate: `src/dev/cljtoc/domain/pieces.clj` namespace requires only `[clojure.spec.alpha]` and `[dev.cljtoc.domain.bencode]` — no I/O, no network, no core.async imports
- [x] T059 Run `lein test dev.cljtoc.domain.pieces-test` and confirm all tests pass with 0 failures (SC-005: ≥90% coverage without I/O)
- [x] T060 [P] Add docstrings to all public functions in `src/dev/cljtoc/domain/pieces.clj` documenting parameters, return shape, and error keywords
- [x] T061 Mark completed tasks in `specs/005-piece-management/tasks.md`

**Independent Test Criteria**: All tests pass; no I/O imports in pieces.clj; all 5 US acceptance criteria verified

---

## Dependency Graph

```
Phase 1: Setup
  └── T001 pieces.clj ──→ T002 pieces_test.clj
         │
         ▼
Phase 2: Foundational
  └── T003 PieceState record
  └── T004 Block record [P]
  └── T005 Primitive s/def specs [P]
  └── T006 Composite s/def specs [P]
         │
         ├────────────────────┬────────────────────┬────────────────────┬────────────────────┐
         ▼                    ▼                    ▼                    ▼                    ▼
Phase 3: US1            Phase 4: US2         Phase 5: US3         Phase 6: US4         Phase 7: US5
(State Machine)      (Piece Selection)   (Block Decomposition)  (Verification)     (Endgame Mode)
T007–T022               T023–T030            T031–T038            T039–T045          T046–T053
```

**Story Dependencies**:
- US1 (P1): Depends only on Phase 2 — no dependencies on other stories
- US2 (P2): Depends on Phase 2 + US1 (needs `PieceState` with `needed` set to select from)
- US3 (P3): Depends only on Phase 2 — fully independent (only needs `Block` record and arithmetic)
- US4 (P4): Depends only on Phase 2 — fully independent (pure bytes comparison)
- US5 (P5): Depends on Phase 2 + US1 (needs `PieceState` fields `needed`, `in-flight`)

---

## Parallel Execution Examples

### Parallel Set A: State Machine + Block Decomposition (US1 + US3)

After Phase 2, these can proceed in parallel (different function groups, no shared state):

```bash
# Thread A: US1 — State machine
- T007 initial-piece-state
- T008-T009 query functions + complete?
- T010-T012 transition functions
- T013-T022 specs + tests

# Thread B: US3 — Block decomposition (entirely independent)
- T031 piece-blocks
- T032 s/fdef
- T033-T038 tests + generative test
```

### Parallel Set B: Piece Selection + Verification (US2 + US4)

After US1 is complete, these can proceed in parallel:

```bash
# Thread A: US2 — Rarest-first selection
- T023 select-piece
- T024 s/fdef
- T025-T030 tests + generative test

# Thread B: US4 — SHA-1 verification (independent of US2)
- T039 verify-piece
- T040 s/fdef
- T041-T045 tests + generative test
```

### Parallel Set C: Endgame + Polish (US5 + Phase 8)

After US1 is complete, US5 can proceed; Polish can proceed after all stories:

```bash
# Thread A: US5
- T046-T047 endgame? + select-pieces-endgame
- T048-T053 specs + tests

# Thread B: Phase 8 edge cases (after all stories)
- T054-T057 edge case tests [P]
- T058 constitution gate check
- T059 coverage run
- T060 docstrings [P]
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete **Phase 1-2** (Setup + Records/Specs)
2. Complete **Phase 3** (US1: State Machine)
3. **STOP and VALIDATE**: Run `lein test dev.cljtoc.domain.pieces-test`
4. Verify: all 8 acceptance scenarios pass; partition invariant holds for 100 generated inputs

### Incremental Delivery

1. **Phase 1-2**: Foundation ready → commit
2. **Phase 3 (US1)**: State machine → commit + test independently (MVP!)
3. **Phase 4 (US2)**: Rarest-first selection → commit + test independently
4. **Phase 5 (US3)**: Block decomposition → commit + test independently
5. **Phase 6 (US4)**: SHA-1 verification → commit + test independently
6. **Phase 7 (US5)**: Endgame mode → commit + test independently
7. **Phase 8**: Polish + coverage run → commit

Each phase adds value without breaking previous phases.

---

## Task Summary

| Phase | User Story | Tasks | Parallel Opportunities |
|-------|------------|-------|----------------------|
| 1 | Setup | T001–T002 | None (sequential file creation) |
| 2 | Foundational | T003–T006 | T004–T006 (record + specs in parallel) |
| 3 | US1: State Machine | T007–T022 | T008–T009 (queries), T013–T014 (fdefs), T015–T022 (tests) |
| 4 | US2: Piece Selection | T023–T030 | T024–T030 (fdef + all tests) |
| 5 | US3: Block Decomposition | T031–T038 | T032–T038 (fdef + all tests) |
| 6 | US4: Verification | T039–T045 | T040–T045 (fdef + all tests) |
| 7 | US5: Endgame Mode | T046–T053 | T048–T053 (fdef + all tests) |
| 8 | Polish | T054–T061 | T054–T057, T060 (edge cases + docstrings) |

**Total Tasks**: 61
**Estimated Parallel Groups**: 10 (within phases)
**User Stories**: 5 (US3 and US4 are fully independent of each other and can develop in parallel with US1)

---

## Success Criteria Verification

- [x] **SC-001**: All operations < 1ms for 100,000 pieces → verified by generative tests completing 100 iterations in <15ms total
- [x] **SC-002**: Rarest-first selects rarer pieces → verified by selection test T025
- [x] **SC-003**: Block decomposition byte-perfect → verified by generative test T038
- [x] **SC-004**: Verification 100% accurate → verified by correctness tests T041–T042 and generative T045
- [x] **SC-005**: 90%+ coverage without I/O → verified by 33 tests, 91 assertions, 0 I/O (T059)
- [x] **SC-006**: Referential transparency across 1,000 inputs → verified by generative tests (T022 `state-partition-invariant`, T030 `select-piece-safety`, T045 `verify-piece-referentially-transparent`)

---

## Next Steps

1. Start with **Phase 1-2** (Setup + Records) — blocking for all stories
2. Implement **Phase 3 (US1)** first — state machine is the foundation
3. Continue with **US2, US3, US4** in parallel (all unblock after US1)
4. Add **US5** after US1 complete
5. Run `/speckit.implement` when ready to execute tasks
