# Tasks: Bencode Parser & Torrent Domain Model

**Feature**: 002-bencode-parser
**Branch**: `002-bencode-parser`
**Input**: Design documents from `/specs/002-bencode-parser/`
**Prerequisites**: ✅ plan.md, ✅ spec.md, ✅ research.md, ✅ data-model.md, ✅ contracts/

**Tests**: Included - this feature requires 100% unit test coverage per spec (FR-023, SC-002)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] [ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [x] T001 Create directory structure: src/dev/cljtoc/domain/ and test/dev/cljtoc/domain/
- [x] T002 Add test.check dependency to project.clj for property-based testing
- [x] T003 [P] Create test fixtures directory: test/dev/cljtoc/domain/fixtures/torrents/

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core utilities that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Implement sha1-hash pure function in src/dev/cljtoc/domain/bencode.clj (wraps Java MessageDigest)
- [x] T005 [P] Implement byte array utility functions (bytes->hex-string, compare bytes) in src/dev/cljtoc/domain/bencode.clj
- [x] T006 [P] Create error data structure constructors (bencode-error, torrent-error) in src/dev/cljtoc/domain/bencode.clj

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Decode Valid Bencode Data (Priority: P1) 🎯 Foundation

**Goal**: Parse bencode-encoded bytes into Clojure data structures (strings, integers, lists, dictionaries)

**Independent Test**: Provide bencode byte sequences and verify decoded output matches expected structures

### Tests for User Story 1

> **NOTE: Write these tests FIRST using TDD - tests should FAIL before implementation**

- [x] T007 [P] [US1] Unit test for decode-string in test/dev/cljtoc/domain/bencode_test.clj
- [x] T008 [P] [US1] Unit test for decode-integer in test/dev/cljtoc/domain/bencode_test.clj
- [x] T009 [P] [US1] Unit test for decode-list in test/dev/cljtoc/domain/bencode_test.clj
- [x] T010 [P] [US1] Unit test for decode-dict in test/dev/cljtoc/domain/bencode_test.clj
- [x] T011 [P] [US1] Unit test for nested structure decoding in test/dev/cljtoc/domain/bencode_test.clj
- [x] T012 [P] [US1] Property-based test for bencode decode invariants in test/dev/cljtoc/domain/bencode_test.clj

### Implementation for User Story 1

- [x] T013 [P] [US1] Implement decode-string function (format: <length>:<content>) in src/dev/cljtoc/domain/bencode.clj
- [x] T014 [P] [US1] Implement decode-integer function (format: i<number>e) in src/dev/cljtoc/domain/bencode.clj
- [x] T015 [US1] Implement decode-list function (format: l<elements>e) in src/dev/cljtoc/domain/bencode.clj (depends on T013, T014)
- [x] T016 [US1] Implement decode-dict function (format: d<k><v>...e) in src/dev/cljtoc/domain/bencode.clj (depends on T013, T014)
- [x] T017 [US1] Implement decode-bencode main function with type detection in src/dev/cljtoc/domain/bencode.clj (depends on T013-T016)
- [x] T018 [US1] Add position tracking for parse errors in src/dev/cljtoc/domain/bencode.clj
- [x] T019 [US1] Handle edge cases: empty strings, negative integers, truncated input in src/dev/cljtoc/domain/bencode.clj

**Checkpoint**: User Story 1 complete - bencode decoder functional and tested

---

## Phase 4: User Story 2 - Extract Torrent Metadata (Priority: P2) 🎯 Core Value

**Goal**: Transform .torrent file bytes into structured TorrentMetainfo domain model with info hash, announce URLs, and piece data

**Independent Test**: Provide .torrent file bytes and verify all metadata fields are correctly extracted

### Tests for User Story 2

- [x] T020 [P] [US2] Unit test for extract-info-dict-bytes in test/dev/cljtoc/domain/torrent_test.clj
- [x] T021 [P] [US2] Unit test for compute-info-hash in test/dev/cljtoc/domain/torrent_test.clj
- [x] T022 [P] [US2] Unit test for parse-single-file-torrent in test/dev/cljtoc/domain/torrent_test.clj
- [x] T023 [P] [US2] Unit test for parse-multi-file-torrent in test/dev/cljtoc/domain/torrent_test.clj
- [x] T024 [P] [US2] Integration test with real .torrent file fixtures in test/dev/cljtoc/domain/torrent_test.clj
- [x] T025 [P] [US2] Test info hash matches known values for fixture torrents in test/dev/cljtoc/domain/torrent_test.clj

### Implementation for User Story 2

- [x] T026 [P] [US2] Create torrent namespace in src/dev/cljtoc/domain/torrent.clj
- [x] T027 [P] [US2] Implement extract-info-dict-bytes function in src/dev/cljtoc/domain/torrent.clj
- [x] T028 [US2] Implement compute-info-hash function in src/dev/cljtoc/domain/torrent.clj (depends on T004, T027)
- [x] T029 [P] [US2] Implement extract-announce-urls (primary and announce-list) in src/dev/cljtoc/domain/torrent.clj
- [x] T030 [P] [US2] Implement parse-info-dict for single-file torrents in src/dev/cljtoc/domain/torrent.clj
- [x] T031 [P] [US2] Implement parse-info-dict for multi-file torrents in src/dev/cljtoc/domain/torrent.clj
- [x] T032 [US2] Implement parse-pieces (split into 20-byte hashes) in src/dev/cljtoc/domain/torrent.clj (depends on T030, T031)
- [x] T033 [US2] Implement parse-torrent main function in src/dev/cljtoc/domain/torrent.clj (depends on T027-T032)
- [x] T034 [US2] Extract optional fields (comment, created-by, creation-date, encoding) in src/dev/cljtoc/domain/torrent.clj
- [x] T035 [US2] Add sample .torrent files to test/dev/cljtoc/domain/fixtures/torrents/ for testing

**Checkpoint**: User Story 2 complete - torrent parser extracts all metadata with correct info hash

---

## Phase 5: User Story 3 - Validate Torrent File Structure (Priority: P3) 🎯 Robustness

**Goal**: Detect and report structural issues like missing fields, type mismatches, or malformed bencode

**Independent Test**: Provide malformed torrent files and verify appropriate error messages are returned

### Tests for User Story 3

- [x] T036 [P] [US3] Unit test for missing required fields validation in test/dev/cljtoc/domain/torrent_test.clj
- [x] T037 [P] [US3] Unit test for type mismatch detection in test/dev/cljtoc/domain/torrent_test.clj
- [x] T038 [P] [US3] Unit test for invalid pieces field length in test/dev/cljtoc/domain/torrent_test.clj
- [x] T039 [P] [US3] Unit test for malformed bencode error reporting in test/dev/cljtoc/domain/bencode_test.clj
- [x] T040 [P] [US3] Test error messages include descriptive context in test/dev/cljtoc/domain/torrent_test.clj

### Implementation for User Story 3

- [x] T041 [P] [US3] Implement validate-required-fields function in src/dev/cljtoc/domain/torrent.clj
- [x] T042 [P] [US3] Implement validate-field-types function in src/dev/cljtoc/domain/torrent.clj
- [x] T043 [P] [US3] Implement validate-pieces-length (must be multiple of 20) in src/dev/cljtoc/domain/torrent.clj
- [x] T044 [P] [US3] Implement validate-piece-length (must be positive) in src/dev/cljtoc/domain/torrent.clj
- [x] T045 [US3] Implement validate-torrent main function in src/dev/cljtoc/domain/torrent.clj (depends on T041-T044)
- [x] T046 [US3] Enhance parse-torrent to call validate-torrent in src/dev/cljtoc/domain/torrent.clj
- [x] T047 [US3] Improve bencode error messages with byte position in src/dev/cljtoc/domain/bencode.clj
- [x] T048 [US3] Add context to torrent parse errors (found keys, expected types) in src/dev/cljtoc/domain/torrent.clj

**Checkpoint**: User Story 3 complete - robust validation with clear error messages

---

## Phase 6: User Story 4 - Encode Data to Bencode (Priority: P4) 🎯 Protocol Support

**Goal**: Convert Clojure data structures to bencode-encoded bytes for protocol operations

**Independent Test**: Encode data structures and verify output matches expected bencode format; round-trip should be identity

### Tests for User Story 4

- [x] T049 [P] [US4] Unit test for encode-string in test/dev/cljtoc/domain/bencode_test.clj
- [x] T050 [P] [US4] Unit test for encode-integer in test/dev/cljtoc/domain/bencode_test.clj
- [x] T051 [P] [US4] Unit test for encode-list in test/dev/cljtoc/domain/bencode_test.clj
- [x] T052 [P] [US4] Unit test for encode-dict (with key sorting) in test/dev/cljtoc/domain/bencode_test.clj
- [x] T053 [P] [US4] Property-based test for round-trip encode/decode in test/dev/cljtoc/domain/bencode_test.clj
- [x] T054 [P] [US4] Test dictionary key lexicographic sorting in test/dev/cljtoc/domain/bencode_test.clj

### Implementation for User Story 4

- [x] T055 [P] [US4] Implement encode-string function in src/dev/cljtoc/domain/bencode.clj
- [x] T056 [P] [US4] Implement encode-integer function in src/dev/cljtoc/domain/bencode.clj
- [x] T057 [P] [US4] Implement encode-list function in src/dev/cljtoc/domain/bencode.clj
- [x] T058 [US4] Implement encode-dict with key sorting in src/dev/cljtoc/domain/bencode.clj (depends on T055, T056)
- [x] T059 [US4] Implement encode-bencode main function with type dispatch in src/dev/cljtoc/domain/bencode.clj (depends on T055-T058)
- [x] T060 [US4] Add bencode-roundtrip? utility function in src/dev/cljtoc/domain/bencode.clj
- [x] T061 [US4] Handle byte arrays as raw bencode strings in src/dev/cljtoc/domain/bencode.clj

**Checkpoint**: User Story 4 complete - full bencode encoder with round-trip validation

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final integration, documentation, and quality gates

- [x] T062 [P] Add docstrings to all public functions in src/dev/cljtoc/domain/bencode.clj
- [x] T063 [P] Add docstrings to all public functions in src/dev/cljtoc/domain/torrent.clj
- [x] T064 [P] Add type hints for performance optimization in src/dev/cljtoc/domain/bencode.clj
- [x] T065 Run performance test: parse 1MB .torrent file in <100ms
- [x] T066 Verify test coverage ≥90% for all domain functions
- [x] T067 [P] Create REPL examples in quickstart.md
- [x] T068 Update project README with bencode parser usage

**Checkpoint**: Feature complete and ready for merge

---

## Dependencies Between Stories

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundation) ← MUST complete before user stories
    ↓
    ├─→ US1 (Decode) ← Foundation for all other stories
    │       ↓
    │   ├─→ US2 (Extract Metadata) ← Uses decode from US1
    │   ├─→ US3 (Validate) ← Uses decode from US1
    │   └─→ US4 (Encode) ← Independent of decode, but tests use decode
    │
    └─→ All stories can run in parallel after US1 is complete
```

---

## Parallel Execution Opportunities

### After Foundation (Phase 2):
- US1 tests (T007-T012) can run in parallel

### After US1 Complete:
- US2, US3, US4 can ALL proceed in parallel
- Within each story, tests marked [P] can run in parallel

### During Implementation:
- Tests can be written in parallel with implementation (TDD approach)
- Multiple developers can work on different user stories simultaneously

---

## Implementation Strategy

1. **Setup & Foundation First** (Phases 1-2): Critical path, must complete before user stories
2. **US1 as Priority** (Phase 3): Everything depends on bencode decoder
3. **Parallel User Stories** (Phases 4-6): US2, US3, US4 can proceed simultaneously
4. **Integrate & Polish** (Phase 7): Final cleanup and documentation

**MVP Scope**: Phases 1-4 (Setup + Foundation + US1 + US2) delivers working torrent parser
**Full Feature**: All phases deliver complete bencode library with validation and encoding

---

## Task Count Summary

- **Setup**: 3 tasks
- **Foundation**: 3 tasks (blocking)
- **User Story 1** (Decode): 13 tasks (6 tests + 7 implementation)
- **User Story 2** (Extract): 16 tasks (6 tests + 10 implementation)
- **User Story 3** (Validate): 13 tasks (5 tests + 8 implementation)
- **User Story 4** (Encode): 13 tasks (6 tests + 7 implementation)
- **Polish**: 7 tasks

**Total**: 68 tasks (all complete)
