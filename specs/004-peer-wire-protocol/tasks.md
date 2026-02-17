# Tasks: Peer Wire Protocol

**Feature**: 004-peer-wire-protocol  
**Branch**: `004-peer-wire-protocol`  
**Generated**: 2026-02-17

---

## Overview

Implementation tasks for BitTorrent peer wire protocol (BEP 3) - pure functions for parsing/building handshake and peer messages, plus peer connection state machine.

---

## Phase 1: Setup

**Goal**: Initialize project structure and core utilities

- [ ] T001 Create directory structure per implementation plan: `src/dev/cljtoc/protocol/` and `test/dev/cljtoc/protocol/`
- [ ] T002 Create `src/dev/cljtoc/protocol/peer.clj` with namespace declaration and docstring
- [ ] T003 Create `src/dev/cljtoc/protocol/peer_state.clj` with namespace declaration and docstring
- [ ] T004 Create `test/dev/cljtoc/protocol/peer_test.clj` with namespace declaration and test scaffolding
- [ ] T005 Create `test/dev/cljtoc/protocol/peer_state_test.clj` with namespace declaration and test scaffolding
- [ ] T006 [P] Implement byte utility functions in `src/dev/cljtoc/protocol/peer.clj`: `bytes-to-int32`, `int32-to-bytes`, `bytes-to-int16`, `int16-to-bytes`, `concat-bytes`
- [ ] T007 [P] Add tests for byte utilities in `test/dev/cljtoc/protocol/peer_test.clj`: verify big-endian encoding/decoding

**Independent Test Criteria**: Byte utilities round-trip correctly (encode then decode returns original value)

---

## Phase 2: Foundational - PeerHandshake Record

**Goal**: Define PeerHandshake record and validation utilities

- [ ] T008 Define `PeerHandshake` record in `src/dev/cljtoc/protocol/peer.clj` with fields: protocol, reserved, info-hash, peer-id
- [ ] T009 [P] Implement handshake validation function in `src/dev/cljtoc/protocol/peer.clj`: validate protocol string and byte lengths
- [ ] T010 [P] Add PeerHandshake record tests in `test/dev/cljtoc/protocol/peer_test.clj`: verify record creation and field access

**Independent Test Criteria**: PeerHandshake record can be created and fields accessed correctly

---

## Phase 3: User Story 1 - Parse Peer Handshake

**Story**: Parse 68-byte handshake to extract protocol, reserved bytes, info-hash, and peer-id

**Acceptance Criteria** (from spec.md):
1. Parse valid 68-byte handshake → returns record with all fields
2. Wrong protocol string → returns `:unsupported-protocol` error
3. Mismatched info_hash → detectable from parsed record
4. < 68 bytes → returns `:incomplete-handshake` error
5. Non-zero reserved bytes → preserved for extension detection

**Tasks**:

- [ ] T011 [US1] Implement `parse-handshake` function in `src/dev/cljtoc/protocol/peer.clj` with error handling for incomplete input
- [ ] T012 [US1] [P] Add test: valid handshake parses correctly with all fields extracted
- [ ] T013 [US1] [P] Add test: wrong protocol string returns `:unsupported-protocol` error
- [ ] T014 [US1] [P] Add test: incomplete handshake (< 68 bytes) returns `:incomplete-handshake` error
- [ ] T015 [US1] [P] Add test: non-zero reserved bytes are preserved in parsed record
- [ ] T016 [US1] [P] Add generative test: random valid handshakes parse correctly

**Independent Test Criteria**: Run `parse-handshake` tests - all 5 acceptance scenarios pass

---

## Phase 4: User Story 2 - Parse Peer Messages

**Story**: Parse all 9 BEP 3 message types from length-prefixed byte sequences

**Acceptance Criteria** (from spec.md):
1. Keep-alive (4 zero bytes) → KeepAlive record
2. Choke (id=0) → Choke record
3. Unchoke (id=1) → Unchoke record
4. Interested (id=2) → Interested record
5. Not-interested (id=3) → NotInterested record
6. Have (id=4) → Have record with piece index
7. Bitfield (id=5) → Bitfield record with accessible bits
8. Request (id=6) → Request record with index, begin, length
9. Piece (id=7) → Piece record with index, begin, data
10. Cancel (id=8) → Cancel record with index, begin, length
11. Unknown message id → `:unknown-message-type` error
12. Declared length > available bytes → `:incomplete-message` error

**Tasks**:

- [ ] T017 [US2] Define all PeerMessage records in `src/dev/cljtoc/protocol/peer.clj`: KeepAlive, Choke, Unchoke, Interested, NotInterested, Have, Bitfield, Request, Piece, Cancel
- [ ] T018 [US2] [P] Implement `parse-message` function dispatcher in `src/dev/cljtoc/protocol/peer.clj` with message type routing
- [ ] T019 [US2] [P] Implement keep-alive parser in `src/dev/cljtoc/protocol/peer.clj` (length = 0)
- [ ] T020 [US2] [P] Implement simple message parsers (choke, unchoke, interested, not-interested) in `src/dev/cljtoc/protocol/peer.clj`
- [ ] T021 [US2] [P] Implement Have parser in `src/dev/cljtoc/protocol/peer.clj` (extract piece-index)
- [ ] T022 [US2] [P] Implement Bitfield parser in `src/dev/cljtoc/protocol/peer.clj` (extract byte array)
- [ ] T023 [US2] [P] Implement Request parser in `src/dev/cljtoc/protocol/peer.clj` (extract index, begin, length)
- [ ] T024 [US2] [P] Implement Piece parser in `src/dev/cljtoc/protocol/peer.clj` (extract index, begin, data)
- [ ] T025 [US2] [P] Implement Cancel parser in `src/dev/cljtoc/protocol/peer.clj` (extract index, begin, length)
- [ ] T026 [US2] [P] Implement `parse-messages` function in `src/dev/cljtoc/protocol/peer.clj` for parsing multiple messages from buffer
- [ ] T027 [US2] [P] Add test: keep-alive message parses correctly
- [ ] T028 [US2] [P] Add tests: choke, unchoke, interested, not-interested parse correctly
- [ ] T029 [US2] [P] Add test: have message parses with correct piece index
- [ ] T030 [US2] [P] Add test: bitfield message parses with accessible bytes
- [ ] T031 [US2] [P] Add test: request message parses with all three fields
- [ ] T032 [US2] [P] Add test: piece message parses with index, begin, and data
- [ ] T033 [US2] [P] Add test: cancel message parses with all three fields
- [ ] T034 [US2] [P] Add test: unknown message id returns `:unknown-message-type` error
- [ ] T035 [US2] [P] Add test: incomplete message returns `:incomplete-message` error
- [ ] T036 [US2] [P] Add test: `parse-messages` handles multiple messages and returns remaining bytes

**Independent Test Criteria**: Run `parse-message` tests - all 9 message types parse correctly, error cases handled

---

## Phase 5: User Story 3 - Build Peer Handshake

**Story**: Build 68-byte handshake byte sequence from info-hash and peer-id

**Acceptance Criteria** (from spec.md):
1. Valid info_hash + peer_id → exactly 68 bytes with correct layout
2. Reserved bytes specified → appear at offset 20-27
3. info_hash not 20 bytes → input validation error
4. peer_id not 20 bytes → input validation error
5. Built then parsed → fields match original inputs (round-trip)

**Tasks**:

- [ ] T037 [US3] Implement `build-handshake` function in `src/dev/cljtoc/protocol/peer.clj` with optional reserved parameter
- [ ] T038 [US3] [P] Add test: valid handshake builds to exactly 68 bytes with correct layout
- [ ] T039 [US3] [P] Add test: reserved bytes appear at correct offset
- [ ] T040 [US3] [P] Add test: invalid info_hash length returns `:invalid-input` error
- [ ] T041 [US3] [P] Add test: invalid peer_id length returns `:invalid-input` error
- [ ] T042 [US3] [P] Add generative test: round-trip (build then parse) returns original fields

**Independent Test Criteria**: Run `build-handshake` tests - all 5 acceptance scenarios pass, round-trip property holds

---

## Phase 6: User Story 4 - Build Peer Messages

**Story**: Build length-prefixed byte sequences for all 9 message types

**Acceptance Criteria** (from spec.md):
1. Keep-alive → 4 zero bytes
2. No-payload messages (choke/unchoke/interested/not-interested) → 5 bytes (length=1 + id)
3. Have message → 9 bytes with index at offset 5
4. Bitfield message → correct length and payload
5. Request/Cancel → 17 bytes with fields at correct offsets
6. Piece message → correctly framed with data at offset 13
7. Invalid inputs → `:invalid-input` error (negative index, >16 KiB block)

**Tasks**:

- [ ] T043 [US4] Implement `build-message` dispatcher in `src/dev/cljtoc/protocol/peer.clj` with message type routing
- [ ] T044 [US4] [P] Implement `build-keep-alive` convenience function in `src/dev/cljtoc/protocol/peer.clj`
- [ ] T045 [US4] [P] Implement `build-choke`, `build-unchoke`, `build-interested`, `build-not-interested` convenience functions
- [ ] T046 [US4] [P] Implement `build-have` function in `src/dev/cljtoc/protocol/peer.clj` with piece-index validation
- [ ] T047 [US4] [P] Implement `build-bitfield` function in `src/dev/cljtoc/protocol/peer.clj` with payload validation
- [ ] T048 [US4] [P] Implement `build-request` function in `src/dev/cljtoc/protocol/peer.clj` with 16 KiB limit validation
- [ ] T049 [US4] [P] Implement `build-piece` function in `src/dev/cljtoc/protocol/peer.clj` with data length validation
- [ ] T050 [US4] [P] Implement `build-cancel` function in `src/dev/cljtoc/protocol/peer.clj` with 16 KiB limit validation
- [ ] T051 [US4] [P] Implement `build-messages` function in `src/dev/cljtoc/protocol/peer.clj` for building multiple messages
- [ ] T052 [US4] [P] Add test: keep-alive builds to 4 zero bytes
- [ ] T053 [US4] [P] Add tests: no-payload messages build to 5 bytes with correct id
- [ ] T054 [US4] [P] Add test: have message builds with correct index at offset 5
- [ ] T055 [US4] [P] Add test: bitfield message builds with correct length
- [ ] T056 [US4] [P] Add test: request message builds with fields at correct offsets
- [ ] T057 [US4] [P] Add test: cancel message builds with fields at correct offsets
- [ ] T058 [US4] [P] Add test: piece message builds with data at offset 13
- [ ] T059 [US4] [P] Add test: negative piece index returns `:invalid-input` error
- [ ] T060 [US4] [P] Add test: block length > 16 KiB returns `:invalid-input` error
- [ ] T061 [US4] [P] Add generative tests: round-trip for all message types

**Independent Test Criteria**: Run `build-*` tests - all 9 message types build correctly, validation errors work, round-trip property holds

---

## Phase 7: User Story 5 - Peer Connection State Machine

**Story**: Pure state transitions for peer connection state (choked/unchoked, interested/not-interested, piece availability)

**Acceptance Criteria** (from spec.md):
1. Initial state + unchoke → choked = false
2. Unchoked state + choke → choked = true
3. Any state + interested → peer-interested = true
4. Any state + not-interested → peer-interested = false
5. State + have → piece index marked available
6. State with empty bitfield + bitfield message → bitfield updated

**Tasks**:

- [ ] T062 [US5] Define `PeerState` record in `src/dev/cljtoc/protocol/peer_state.clj` with all fields
- [ ] T063 [US5] [P] Implement `initial-peer-state` function in `src/dev/cljtoc/protocol/peer_state.clj` with default values
- [ ] T064 [US5] [P] Implement bitfield wrapper using Java BitSet in `src/dev/cljtoc/protocol/peer_state.clj` with immutability guarantees
- [ ] T065 [US5] [P] Implement `peer-has-piece?` function in `src/dev/cljtoc/protocol/peer_state.clj`
- [ ] T066 [US5] [P] Implement `mark-piece-available` function in `src/dev/cljtoc/protocol/peer_state.clj`
- [ ] T067 [US5] [P] Implement `update-bitfield` function in `src/dev/cljtoc/protocol/peer_state.clj` handling extra bits
- [ ] T068 [US5] [P] Implement `apply-message` function in `src/dev/cljtoc/protocol/peer_state.clj` with state transitions
- [ ] T069 [US5] [P] Implement `peer-piece-count` function in `src/dev/cljtoc/protocol/peer_state.clj`
- [ ] T070 [US5] [P] Implement `can-request?` function in `src/dev/cljtoc/protocol/peer_state.clj`
- [ ] T071 [US5] [P] Add test: initial peer state has correct default values
- [ ] T072 [US5] [P] Add test: unchoke message transitions peer-choking to false
- [ ] T073 [US5] [P] Add test: choke message transitions peer-choking to true
- [ ] T074 [US5] [P] Add test: interested message transitions peer-interested to true
- [ ] T075 [US5] [P] Add test: not-interested message transitions peer-interested to false
- [ ] T076 [US5] [P] Add test: have message marks piece available in bitfield
- [ ] T077 [US5] [P] Add test: bitfield message updates entire bitfield
- [ ] T078 [US5] [P] Add test: peer-has-piece? returns false for unknown pieces
- [ ] T079 [US5] [P] Add test: peer-has-piece? returns true for available pieces
- [ ] T080 [US5] [P] Add test: bitfield with extra bits ignores extras (per A-005)
- [ ] T081 [US5] [P] Add test: can-request? returns true only when unchoked and interested
- [ ] T082 [US5] [P] Add generative test: state transitions are deterministic (same input → same output)

**Independent Test Criteria**: Run `peer-state` tests - all 6 acceptance scenarios pass, state transitions are pure and deterministic

---

## Phase 8: Polish & Cross-Cutting Concerns

**Goal**: Documentation, edge cases, and quality assurance

- [ ] T083 Add docstrings to all public functions in `src/dev/cljtoc/protocol/peer.clj` and `src/dev/cljtoc/protocol/peer_state.clj`
- [ ] T084 Add edge case test: piece message with 0-byte payload
- [ ] T085 Add edge case test: duplicate have messages for same piece
- [ ] T086 Add edge case test: keep-alive during choked state (no change)
- [ ] T087 Add edge case test: parse-message with partial length prefix only
- [ ] T088 Run code coverage analysis and add tests to reach 90%+ coverage
- [ ] T089 Verify all functions use `{:ok value}` / `{:error keyword :message string}` pattern (FR-005 compliance)
- [ ] T090 [P] Add invariant tests: verify handshake always 68 bytes, block length never exceeds 16 KiB
- [ ] T091 Create `README.md` in `specs/004-peer-wire-protocol/` summarizing the API

**Independent Test Criteria**: All tests pass, coverage ≥90%, no exceptions thrown for expected errors

---

## Dependency Graph

```
Phase 1: Setup
  └── T006 Byte utilities ──→ T007 Byte utility tests
         │
         ▼
Phase 2: Foundational
  └── T008 PeerHandshake record ──→ T009 Validation ──→ T010 Record tests
         │
         ├──────────────────────┬──────────────────────┬──────────────────────┬──────────────────────┐
         ▼                      ▼                      ▼                      ▼                      ▼
Phase 3: US1 (Parse Handshake)  Phase 4: US2 (Parse Messages)  Phase 5: US3 (Build Handshake)  Phase 6: US4 (Build Messages)  Phase 7: US5 (State Machine)
         │                      │                      │                      │                      │
         ▼                      ▼                      ▼                      ▼                      ▼
     T011-T016              T017-T036              T037-T042              T043-T061              T062-T082
```

**Story Dependencies**:
- US1, US2, US3, US4 can be developed in parallel (all depend on Phases 1-2)
- US5 (State Machine) can be developed in parallel with US1-US4 (depends on Phase 2 only)
- US5 requires US2 (Have/Bitfield messages) for integration, but core state logic is independent

---

## Parallel Execution Examples

### Parallel Set A: Core Message Parsing (US1 + US2)

These tasks can be completed in parallel after Phase 2:

```bash
# Task 1: Handshake parsing
- T011 Implement parse-handshake
- T012-T016 Handshake tests

# Task 2: Simple message parsing (can parallelize further)
- T017 Define message records
- T018-T020 Dispatcher + simple messages (keep-alive, choke, unchoke, interested, not-interested)
- T027, T028 Tests for simple messages

# Task 3: Complex message parsing
- T021-T025 Have, Bitfield, Request, Piece, Cancel parsers
- T029-T033 Tests for complex messages

# Task 4: Multi-message parsing
- T026 parse-messages function
- T036 Tests for parse-messages
- T034-T035 Error handling tests
```

### Parallel Set B: Message Building (US3 + US4)

These tasks can be completed in parallel after Phase 2:

```bash
# Task 1: Handshake building
- T037 build-handshake
- T038-T042 Handshake building tests

# Task 2: Simple message building
- T043 Dispatcher
- T044-T045 Keep-alive and simple messages
- T052-T053 Tests

# Task 3: Complex message building
- T046-T050 Have, Bitfield, Request, Piece, Cancel builders
- T054-T058 Tests for complex messages

# Task 4: Validation and multi-message
- T059-T060 Validation error tests
- T051 build-messages
- T061 Generative round-trip tests
```

### Parallel Set C: State Machine (US5)

These tasks can be completed in parallel with A and B:

```bash
# Task 1: Core state structure
- T062-T063 PeerState record and initial state
- T071 Tests

# Task 2: Bitfield operations
- T064 BitSet wrapper
- T065-T069 Bitfield functions
- T076-T080 Bitfield tests

# Task 3: State transitions
- T068 apply-message
- T072-T075 Transition tests

# Task 4: Query functions
- T070 can-request?
- T069 peer-piece-count
- T081 Query tests
- T082 Determinism test
```

---

## Implementation Strategy

### MVP Scope (Minimum Viable Product)

For the first working increment, implement:

1. **Phase 1-2**: Setup + PeerHandshake record (T001-T010)
2. **Phase 3 (US1)**: Parse handshake only (T011-T016)
3. **Phase 5 (US3)**: Build handshake only (T037-T042)
4. **Phase 8**: Basic documentation (T083, T091)

**MVP Success Criteria**:
- Can parse valid 68-byte handshakes
- Can detect malformed handshakes (wrong protocol, too short)
- Can build valid 68-byte handshakes
- Round-trip property holds (build → parse → original fields)
- 90%+ test coverage for handshake functionality

### Incremental Delivery

**Sprint 1: Foundation + Handshake (US1 + US3)**
- Phases 1-3, 5
- Deliver: Handshake parsing and building

**Sprint 2: Message Parsing (US2)**
- Phase 4
- Deliver: All 9 message types parse correctly

**Sprint 3: Message Building (US4)**
- Phase 6
- Deliver: All 9 message types build correctly, round-trip verified

**Sprint 4: State Machine (US5)**
- Phase 7
- Deliver: Pure state transitions, bitfield operations

**Sprint 5: Polish**
- Phase 8
- Deliver: Edge cases, documentation, final coverage verification

---

## Task Summary

| Phase | User Story | Tasks | Parallel Opportunities |
|-------|------------|-------|----------------------|
| 1 | Setup | T001-T007 | T006-T007 (byte utilities + tests) |
| 2 | Foundational | T008-T010 | T009-T010 (validation + tests) |
| 3 | US1: Parse Handshake | T011-T016 | T012-T016 (all handshake tests parallel) |
| 4 | US2: Parse Messages | T017-T036 | T019-T025 (individual message parsers), T027-T036 (tests) |
| 5 | US3: Build Handshake | T037-T042 | T038-T042 (all building tests parallel) |
| 6 | US4: Build Messages | T043-T061 | T044-T050 (individual builders), T052-T061 (tests) |
| 7 | US5: State Machine | T062-T082 | T064-T070 (bitfield + state functions), T072-T082 (tests) |
| 8 | Polish | T083-T091 | T084-T091 (edge cases in parallel) |

**Total Tasks**: 91  
**Estimated Parallel Groups**: 12 (within phases)  
**User Stories**: 5 (all can be developed in parallel after Phase 2)

---

## Success Criteria Verification

- [ ] **SC-001**: All message types parse correctly → Verified by US2 tests (T027-T033)
- [ ] **SC-002**: Round-trip property holds → Verified by generative tests (T042, T061)
- [ ] **SC-003**: Malformed inputs handled gracefully → Verified by error tests (T014, T034, T035, T059, T060)
- [ ] **SC-004**: State transitions deterministic → Verified by determinism test (T082)
- [ ] **SC-005**: 90%+ coverage without I/O → Verified by coverage analysis (T088)
- [ ] **SC-006**: Handshake validation correct → Verified by US1 tests (T012-T016)
- [ ] **SC-007**: 16 KiB limit enforced → Verified by validation tests (T060)
- [ ] **SC-008**: Generative tests (100+ inputs) → Verified by generative tests (T016, T042, T061)

---

## Next Steps

1. Start with **Phase 1-2** (Setup + Foundational) - blocking for all user stories
2. Choose any user story (US1-US5) to implement in parallel
3. Run tests continuously: `lein test` or equivalent
4. Verify coverage after each phase
5. Run `/speckit.implement` when ready to execute tasks
