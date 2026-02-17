# Tasks: Tracker Protocol Communication

**Input**: Design documents from `/specs/003-tracker-protocol/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Following plan.md structure:

- Source: `src/dev/cljtoc/protocol/`
- Tests: `test/dev/cljtoc/protocol/`
- Spec namespace: `src/dev/cljtoc/protocol/tracker/spec.clj`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and directory structure

- [x] T001 Create protocol directory structure: src/dev/cljtoc/protocol/, test/dev/cljtoc/protocol/
- [x] T002 Add clojure.spec.alpha and clojure.spec.gen.alpha dependencies to project.clj
- [x] T003 Verify dev.cljtoc.domain.bencode dependency is available (feature 002)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Create src/dev/cljtoc/protocol/tracker/spec.clj namespace with basic structure
- [x] T005 [P] Define spec ::info-hash (exactly 20 bytes) in tracker/spec.clj
- [x] T006 [P] Define spec ::peer-id (exactly 20 bytes) in tracker/spec.clj
- [x] T007 [P] Define spec ::port (range 1-65535) in tracker/spec.clj
- [x] T008 [P] Define spec ::ip-address (valid IPv4/IPv6 string) in tracker/spec.clj
- [x] T009 [P] Define spec ::event (enumeration: started/completed/stopped/nil) in tracker/spec.clj
- [x] T010 [P] Define spec ::protocol (enumeration: :http or :udp) in tracker/spec.clj
- [x] T011 Create custom generator for ::info-hash (generates exactly 20-byte arrays) in tracker/spec.clj
- [x] T012 Create custom generator for ::peer-id (generates exactly 20-byte arrays) in tracker/spec.clj
- [x] T013 Create custom generator for ::port (range 1024-65535) in tracker/spec.clj
- [x] T014 Create custom generator for ::ip-address (generates valid IPv4 addresses) in tracker/spec.clj
- [x] T015 Define spec ::peer with required keys (:ip, :port) and optional :peer-id in tracker/spec.clj
- [x] T016 Create src/dev/cljtoc/protocol/tracker.clj namespace with basic structure and require tracker.spec
- [x] T017 Create test/dev/cljtoc/protocol/tracker_test.clj namespace with test infrastructure
- [x] T018 Define helper function unsigned-short to convert Java signed short to unsigned 0-65535 in tracker.clj

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Parse HTTP Tracker Responses (Priority: P1) 🎯 MVP

**Goal**: Parse raw HTTP tracker response bytes and extract structured peer list with IPs and ports

**Independent Test**: Provide bencode-encoded HTTP tracker response bytes, verify parsed output contains valid peer information (deliverable: immediate peer discovery capability)

### Tests for User Story 1

> **NOTE: Tests use generative testing with custom generators from tracker.spec**

- [x] T019 [P] [US1] Write generative test for parse-compact-peers-ipv4 (round-trip with generated peers) in tracker_test.clj
- [x] T020 [P] [US1] Write example-based test for parse-compact-peers-ipv4 with known peer bytes in tracker_test.clj
- [x] T021 [P] [US1] Write generative test for parse-compact-peers-ipv6 (18 bytes per peer) in tracker_test.clj
- [x] T022 [P] [US1] Write example-based test for parse-dictionary-peers with peer maps in tracker_test.clj
- [x] T023 [P] [US1] Write test for parse-http-tracker-response with compact peer format in tracker_test.clj
- [x] T024 [P] [US1] Write test for parse-http-tracker-response with dictionary peer format in tracker_test.clj
- [x] T025 [P] [US1] Write test for parse-http-tracker-response with failure reason in tracker_test.clj
- [x] T026 [P] [US1] Write test for parse-http-tracker-response extracting interval/complete/incomplete in tracker_test.clj

### Implementation for User Story 1

- [x] T027 [P] [US1] Implement parse-compact-peers-ipv4 function using ByteBuffer (6 bytes per peer) in tracker.clj
- [x] T028 [P] [US1] Implement parse-compact-peers-ipv6 function using ByteBuffer (18 bytes per peer) in tracker.clj
- [x] T029 [P] [US1] Implement parse-dictionary-peers function for legacy peer format in tracker.clj
- [x] T030 [US1] Implement parse-http-tracker-response function using bencode parser in tracker.clj
- [x] T031 [US1] Add detection logic to choose compact vs dictionary peer format in parse-http-tracker-response
- [x] T032 [US1] Add extraction of interval, min-interval, complete, incomplete, tracker-id fields in tracker.clj
- [x] T033 [US1] Add failure reason extraction for HTTP tracker errors in tracker.clj
- [x] T034 [US1] Define spec ::tracker-response with discriminated union for success/failure in tracker/spec.clj
- [x] T035 [US1] Add input validation at parse-http-tracker-response boundary using specs
- [x] T036 [US1] Transform spec validation failures to {:error :invalid-input} format in tracker.clj

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently (can parse real tracker responses)

---

## Phase 4: User Story 2 - Build HTTP Tracker Announce Requests (Priority: P2)

**Goal**: Build properly formatted HTTP tracker announce request URL with all required BitTorrent protocol parameters

**Independent Test**: Provide torrent metadata (info-hash, peer-id, port, stats), verify generated URL contains all required parameters correctly encoded

### Tests for User Story 2

- [x] T037 [P] [US2] Write test for url-encode-binary with empty bytes in tracker_test.clj
- [x] T038 [P] [US2] Write test for url-encode-binary with simple text "abc" in tracker_test.clj
- [x] T039 [P] [US2] Write test for url-encode-binary with binary data (0x12, 0x34, 0xFF) in tracker_test.clj
- [x] T040 [P] [US2] Write generative test for url-encode-binary (round-trip encode/decode) in tracker_test.clj
- [x] T041 [P] [US2] Write test for build-http-announce-url with required parameters in tracker_test.clj
- [x] T042 [P] [US2] Write test for build-http-announce-url with optional event parameter in tracker_test.clj
- [x] T043 [P] [US2] Write test for build-http-announce-url with existing query parameters in base URL in tracker_test.clj
- [x] T044 [P] [US2] Write test for build-http-announce-url input validation (invalid info-hash length) in tracker_test.clj

### Implementation for User Story 2

- [x] T045 [P] [US2] Implement url-encode-binary function per RFC 3986 (unreserved chars: A-Z a-z 0-9 . - _ ~) in tracker.clj
- [x] T046 [US2] Implement build-http-announce-url function with required parameters in tracker.clj
- [x] T047 [US2] Add support for optional parameters (event, compact, no-peer-id, numwant, tracker-id) in build-http-announce-url
- [x] T048 [US2] Add query parameter appending logic for URLs with existing parameters in tracker.clj
- [x] T049 [US2] Define spec ::tracker-request for HTTP requests in tracker/spec.clj
- [x] T050 [US2] Add input validation at build-http-announce-url boundary using specs
- [x] T051 [US2] Transform spec validation failures to {:error :invalid-input} format in tracker.clj

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently (complete HTTP tracker cycle: build request → parse response)

---

## Phase 5: User Story 3 - Parse UDP Tracker Protocol Messages (Priority: P3)

**Goal**: Parse raw UDP tracker message bytes and extract structured data (connect/announce/error responses)

**Independent Test**: Provide UDP tracker message bytes, verify parsed output matches UDP tracker protocol specification (BEP 15)

### Tests for User Story 3

- [x] T052 [P] [US3] Write test for parse-udp-connect-response with valid 16-byte message in tracker_test.clj
- [x] T053 [P] [US3] Write test for parse-udp-connect-response with invalid action code in tracker_test.clj
- [x] T054 [P] [US3] Write test for parse-udp-announce-response with peers in tracker_test.clj
- [x] T055 [P] [US3] Write test for parse-udp-announce-response extracting interval/leechers/seeders in tracker_test.clj
- [x] T056 [P] [US3] Write test for parse-udp-error-response with error message in tracker_test.clj
- [x] T057 [P] [US3] Write test for parse-udp-scrape-response with torrent statistics in tracker_test.clj
- [x] T058 [P] [US3] Write generative test for UDP message parsing (property: big-endian integers) in tracker_test.clj

### Implementation for User Story 3

- [x] T059 [P] [US3] Implement parse-udp-connect-response function using ByteBuffer (16 bytes) in tracker.clj
- [x] T060 [P] [US3] Implement parse-udp-announce-response function using ByteBuffer (20+ bytes) in tracker.clj
- [x] T061 [P] [US3] Add compact peer parsing (IPv4) to parse-udp-announce-response in tracker.clj
- [x] T062 [P] [US3] Implement parse-udp-error-response function (8 bytes + error string) in tracker.clj
- [x] T063 [P] [US3] Implement parse-udp-scrape-response function (8 + N*12 bytes) in tracker.clj
- [x] T064 [US3] Add transaction ID validation in all UDP response parsers in tracker.clj
- [x] T065 [US3] Add action code validation in all UDP response parsers in tracker.clj
- [x] T066 [US3] Define spec ::udp-tracker-response for UDP responses in tracker/spec.clj
- [x] T067 [US3] Add input validation at UDP parsing function boundaries using specs

**Checkpoint**: User Story 3 complete - can parse all UDP tracker protocol messages

---

## Phase 6: User Story 4 - Build UDP Tracker Protocol Messages (Priority: P4)

**Goal**: Build properly formatted UDP tracker protocol message bytes for connect, announce, and scrape requests

**Independent Test**: Provide connection state and torrent metadata, verify generated message bytes conform to UDP tracker protocol specification (BEP 15)

### Tests for User Story 4

- [x] T068 [P] [US4] Write test for build-udp-connect-request (16 bytes with magic protocol ID) in tracker_test.clj
- [x] T069 [P] [US4] Write test for build-udp-announce-request (98 bytes) in tracker_test.clj
- [x] T070 [P] [US4] Write test for build-udp-scrape-request with multiple info-hashes in tracker_test.clj
- [x] T071 [P] [US4] Write generative round-trip test (build → parse → build) for UDP messages in tracker_test.clj
- [x] T072 [P] [US4] Write test for event code encoding (0=none, 1=completed, 2=started, 3=stopped) in tracker_test.clj

### Implementation for User Story 4

- [x] T073 [P] [US4] Implement build-udp-connect-request function with magic protocol ID 0x41727101980 in tracker.clj
- [x] T074 [P] [US4] Implement build-udp-announce-request function (98 bytes, big-endian) in tracker.clj
- [x] T075 [P] [US4] Implement build-udp-scrape-request function (16 + N*20 bytes) in tracker.clj
- [x] T076 [US4] Add event code encoding logic in build-udp-announce-request in tracker.clj
- [x] T077 [US4] Define spec ::udp-tracker-request for UDP requests in tracker/spec.clj
- [x] T078 [US4] Add input validation at UDP building function boundaries using specs
- [x] T079 [US4] Add helper function for writing big-endian integers using ByteBuffer.putInt/putLong in tracker.clj

**Checkpoint**: User Stories 3 AND 4 complete - full UDP tracker protocol cycle (build request → parse response)

---

## Phase 7: User Story 5 - Handle Tracker Error Responses (Priority: P5)

**Goal**: Detect and extract error messages from tracker responses to enable appropriate retry logic

**Independent Test**: Provide various tracker error responses, verify error messages are correctly extracted with appropriate error types

### Tests for User Story 5

- [x] T080 [P] [US5] Write test for distinguishing network errors vs protocol errors vs tracker failures in tracker_test.clj
- [x] T081 [P] [US5] Write test for malformed tracker response handling (invalid bencode) in tracker_test.clj
- [x] T082 [P] [US5] Write test for missing required fields in tracker response in tracker_test.clj
- [x] T083 [P] [US5] Write test for spec validation failure error messages include explain-data in tracker_test.clj

### Implementation for User Story 5

- [x] T084 [P] [US5] Add error context preservation (tracker URL, error message, error type) in all parsers
- [x] T085 [P] [US5] Implement validate-input wrapper function that transforms spec failures to {:error ...} format
- [x] T086 [US5] Add malformed response handling (try-catch for bencode parse errors) in parse-http-tracker-response
- [x] T087 [US5] Add missing field detection and error reporting in parse-http-tracker-response
- [x] T088 [US5] Define spec ::tracker-error with enumerated error types in tracker/spec.clj
- [x] T089 [US5] Add spec explain-data to error context for debugging in validate-input function

**Checkpoint**: Error handling complete - all parsing functions handle malformed input gracefully

---

## Phase 8: User Story 6 - Calculate Re-Announce Timing (Priority: P6)

**Goal**: Calculate next announce time and retry backoff to enable periodic tracker communication

**Independent Test**: Provide interval values and current time, verify calculated next announce time is correct

### Tests for User Story 6

- [x] T090 [P] [US6] Write test for calculate-next-announce with interval 1800 seconds in tracker_test.clj
- [x] T091 [P] [US6] Write test for calculate-next-announce using min-interval when present in tracker_test.clj
- [x] T092 [P] [US6] Write test for calculate-next-announce using default 1800 when no interval in tracker_test.clj
- [x] T093 [P] [US6] Write test for calculate-exponential-backoff (1s, 2s, 4s, 8s...) in tracker_test.clj
- [x] T094 [P] [US6] Write test for calculate-exponential-backoff respects max delay cap in tracker_test.clj
- [x] T095 [P] [US6] Write test for update-schedule-success (resets retry attempt to 0) in tracker_test.clj
- [x] T096 [P] [US6] Write test for update-schedule-failure (increments retry attempt) in tracker_test.clj

### Implementation for User Story 6

- [x] T097 [P] [US6] Implement calculate-next-announce function (current-time + interval * 1000) in tracker.clj
- [x] T098 [P] [US6] Implement calculate-exponential-backoff function (base * 2^attempt) in tracker.clj
- [x] T099 [P] [US6] Implement update-schedule-success function (pure state transition) in tracker.clj
- [x] T100 [P] [US6] Implement update-schedule-failure function (pure state transition) in tracker.clj
- [x] T101 [US6] Define spec ::announce-schedule with timestamp and interval constraints in tracker/spec.clj
- [x] T102 [US6] Add default interval constant (1800 seconds) in tracker.clj

**Checkpoint**: Re-announce timing complete - can schedule periodic announces and handle failures

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final integration, documentation, and performance verification

- [ ] T103 [P] Add comprehensive docstrings to all public functions in tracker.clj
- [x] T104 [P] Add spec instrumentation enable/disable functions for dev/prod toggle in tracker/spec.clj
- [ ] T105 [P] Write performance test for parsing 1000+ peer lists (SC-007) in tracker_test.clj
- [ ] T106 [P] Write performance test for URL encoding speed <1ms (plan.md goal) in tracker_test.clj
- [ ] T107 [P] Verify all functions return {:ok value} or {:error ...} (no exceptions for expected failures)
- [ ] T108 [P] Run linter (clj-kondo) and fix any warnings in tracker.clj and tracker/spec.clj
- [x] T109 Write integration test combining build-http-announce-url → parse-http-tracker-response in tracker_test.clj
- [x] T110 Write integration test combining build-udp-connect-request → parse-udp-connect-response in tracker_test.clj
- [ ] T111 Verify 90%+ test coverage without network I/O (SC-005) using test coverage tool
- [ ] T112 Add usage examples to quickstart.md demonstrating all public API functions
- [ ] T113 Update README.md with tracker protocol feature status and capabilities

**Final Checkpoint**: All user stories complete and independently testable. Feature ready for integration with coordination layer (future feature 006).

---

## Dependencies & Execution Order

### User Story Dependency Graph

```
Phase 1 (Setup) → Phase 2 (Foundational)
                      ↓
    ┌─────────────────┴─────────────────┐
    ↓                                   ↓
Phase 3: US1 (P1) ────→ Phase 4: US2 (P2)
[Parse HTTP]            [Build HTTP]
    ↓                                   ↓
Phase 5: US3 (P3) ────→ Phase 6: US4 (P4)
[Parse UDP]             [Build UDP]
    ↓                                   ↓
    └────────→ Phase 7: US5 (P5) ←──────┘
              [Error Handling]
                      ↓
              Phase 8: US6 (P6)
              [Timing/Scheduling]
                      ↓
              Phase 9: Polish
```

### Parallel Execution Opportunities

**Within Phase 2 (Foundational)**: T005-T014 can all run in parallel (different spec definitions)

**Within Phase 3 (US1 Tests)**: T019-T026 can all run in parallel (independent test files/functions)

**Within Phase 3 (US1 Implementation)**: T027-T029 can run in parallel (independent parsing functions)

**Within Phase 4 (US2)**: T037-T044 (tests) can run in parallel, then T045-T048 (implementation) in parallel

**Within Phase 5 (US3)**: T052-T058 (tests) can run in parallel, then T059-T063 (implementation) in parallel

**Within Phase 6 (US4)**: T068-T072 (tests) can run in parallel, then T073-T075 (implementation) in parallel

**Within Phase 7 (US5)**: T080-T083 (tests) can run in parallel, then T084-T085 (implementation) in parallel

**Within Phase 8 (US6)**: T090-T096 (tests) can run in parallel, then T097-T100 (implementation) in parallel

**Within Phase 9 (Polish)**: T103-T108, T112-T113 can all run in parallel

### Blocking Dependencies

- Phase 2 MUST complete before any user story
- US2 (build HTTP) can start after US1 (parse HTTP) for testing round-trips
- US4 (build UDP) can start after US3 (parse UDP) for testing round-trips
- US5 (error handling) requires US1 and US3 complete
- US6 (timing) requires US1 complete (needs interval from responses)

---

## Implementation Strategy

### MVP Scope (Minimum Viable Product)

**MVP = Phase 1 + Phase 2 + Phase 3 (User Story 1)**

This delivers immediate value:

- ✅ Parse HTTP tracker responses
- ✅ Extract peer lists (IPv4 and IPv6)
- ✅ Extract swarm statistics
- ✅ Pure functions with spec validation
- ✅ 90%+ test coverage

**Value**: Enables peer discovery from HTTP trackers (most common tracker type)

### Incremental Delivery Path

1. **Iteration 1**: MVP (US1) - Parse HTTP tracker responses
2. **Iteration 2**: Add US2 - Complete HTTP cycle (build + parse)
3. **Iteration 3**: Add US3-US4 - UDP tracker support
4. **Iteration 4**: Add US5-US6 - Error handling and scheduling
5. **Iteration 5**: Polish and performance optimization

### Independent Testing Verification

Each user story can be tested independently:

- **US1**: Provide bencode HTTP response bytes → verify peer list output
- **US2**: Provide torrent metadata → verify URL string output
- **US3**: Provide UDP message bytes → verify parsed data structures
- **US4**: Provide connection parameters → verify binary message output
- **US5**: Provide malformed input → verify error handling
- **US6**: Provide intervals and timestamps → verify calculated times

---

## Task Count Summary

- **Total Tasks**: 113
- **Setup (Phase 1)**: 3 tasks
- **Foundational (Phase 2)**: 15 tasks
- **User Story 1 (Phase 3)**: 20 tasks (8 tests + 12 implementation)
- **User Story 2 (Phase 4)**: 15 tasks (8 tests + 7 implementation)
- **User Story 3 (Phase 5)**: 16 tasks (7 tests + 9 implementation)
- **User Story 4 (Phase 6)**: 12 tasks (5 tests + 7 implementation)
- **User Story 5 (Phase 7)**: 10 tasks (4 tests + 6 implementation)
- **User Story 6 (Phase 8)**: 13 tasks (7 tests + 6 implementation)
- **Polish (Phase 9)**: 11 tasks

**Parallel Opportunities**: 68 tasks marked [P] can run in parallel within their phases

**Test Coverage**: 39 test tasks + integration/performance tests = comprehensive coverage targeting 90%+

---

## Success Validation Checklist

After completing all tasks, verify against spec success criteria:

- [ ] **SC-001**: Parser correctly extracts peer lists from 100% of well-formed HTTP tracker responses
- [ ] **SC-002**: Parser correctly extracts peer lists from 100% of well-formed UDP tracker responses
- [ ] **SC-003**: Request builder generates valid announce URLs accepted by reference BitTorrent trackers
- [ ] **SC-004**: Parser handles malformed tracker responses without crashes, returning appropriate error information
- [ ] **SC-005**: All tracker protocol functions are pure with 90%+ unit test coverage requiring no network I/O
- [ ] **SC-006**: System correctly schedules re-announces according to tracker-specified intervals within 1 second accuracy
- [ ] **SC-007**: Compact peer format parser correctly handles peer lists with 1000+ peers without performance degradation
- [ ] **SC-008**: UDP protocol correctly handles connection ID expiration and re-connection sequences
- [ ] **SC-009**: URL encoding for binary data (info_hash, peer_id) matches reference BitTorrent client implementations
- [ ] **SC-010**: Error responses from trackers are correctly distinguished from network failures with appropriate error messages
- [ ] **SC-011**: All data entities have clojure.spec definitions that enable generative testing
- [ ] **SC-012**: Generative tests successfully discover edge cases in protocol parsing and message building
- [ ] **SC-013**: Public API functions validate inputs at boundaries using clojure.spec with clear error messages
