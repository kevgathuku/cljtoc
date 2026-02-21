# Implementation Tasks: End-to-End Single Torrent Download

**Feature**: 006-download-orchestration  
**Branch**: `006-download-orchestration`  
**Date**: 2026-02-21

## Task Summary

- **Total Tasks**: 47
- **User Stories**: 4 (US1: Download, US2: Progress, US3: Errors, US4: Pause/Resume)
- **Parallelizable Tasks**: 8

## Phase 1: Setup

Project initialization and directory structure.

- [x] T001 Create port protocols namespace in src/dev/cljtoc/ports/network.clj
- [x] T002 Create port protocols namespace in src/dev/cljtoc/ports/disk.clj
- [x] T003 Create port protocols namespace in src/dev/cljtoc/ports/time.clj
- [x] T004 Create orchestration namespace in src/dev/cljtoc/orchestration/download.clj
- [x] T005 Create orchestration namespace in src/dev/cljtoc/orchestration/manager.clj
- [x] T006 Create test doubles directory test/dev/cljtoc/test_doubles/
- [x] T007 [P] Create test double for network port in test/dev/cljtoc/test_doubles/network.clj
- [x] T008 [P] Create test double for disk port in test/dev/cljtoc/test_doubles/disk.clj
- [x] T009 [P] Create test double for time port in test/dev/cljtoc/test_doubles/time.clj
- [x] T010 Create test directory test/dev/cljtoc/orchestration/

---

## Phase 2: Foundational (Blocking Prerequisites)

Port protocols and test doubles must be complete before any user story implementation.

- [x] T011 Define INetworkPort protocol in src/dev/cljtoc/ports/network.clj
- [x] T012 Define IDiskPort protocol in src/dev/cljtoc/ports/disk.clj
- [x] T013 Define ITimePort protocol in src/dev/cljtoc/ports/time.clj
- [x] T014 [P] Implement mock network test double in test/dev/cljtoc/test_doubles/network.clj
- [x] T015 [P] Implement mock disk test double in test/dev/cljtoc/test_doubles/disk.clj
- [x] T016 [P] Implement mock time test double in test/dev/cljtoc/test_doubles/time.clj

---

## Phase 3: User Story 1 - Download Complete Torrent (P1)

Core functionality: parse torrent, connect to tracker, download pieces, verify integrity, write to disk.

**Independent Test**: Provide small .torrent file, verify all pieces download and hash correctly.

### Setup

- [x] T017 [US1] Create Download record in src/dev/cljtoc/orchestration/download.clj
- [x] T018 [US1] Create Peer record in src/dev/cljtoc/orchestration/download.clj
- [x] T019 [US1] Create DownloadStats record in src/dev/cljtoc/orchestration/download.clj
- [x] T020 [US1] Create ErrorInfo record in src/dev/cljtoc/orchestration/download.clj

### Implementation

- [x] T021 [US1] Implement manager constructor in src/dev/cljtoc/orchestration/manager.clj
- [x] T022 [US1] Implement start-download in src/dev/cljtoc/orchestration/download.clj
- [x] T023 [US1] Implement torrent file parsing via IDiskPort in src/dev/cljtoc/orchestration/download.clj
- [x] T024 [US1] Implement tracker announcement via INetworkPort in src/dev/cljtoc/orchestration/download.clj
- [x] T025 [US1] Implement peer connection management in src/dev/cljtoc/orchestration/download.clj
- [x] T026 [US1] Implement piece selection using rarest-first from pieces.clj in src/dev/cljtoc/orchestration/download.clj
- [x] T027 [US1] Implement piece download request/response handling in src/dev/cljtoc/orchestration/download.clj
- [x] T028 [US1] Implement SHA-1 verification using pieces/verify-piece in src/dev/cljtoc/orchestration/download.clj
- [x] T029 [US1] Implement piece assembly and disk write via IDiskPort in src/dev/cljtoc/orchestration/download.clj
- [x] T030 [US1] Implement download state machine transitions in src/dev/cljtoc/orchestration/download.clj
- [x] T031 [US1] Implement stop-download in src/dev/cljtoc/orchestration/download.clj

### Tests

- [x] T032 [US1] Create unit tests for Download record in test/dev/cljtoc/orchestration/download_test.clj
- [ ] T033 [US1] Create integration test for complete download flow in test/dev/cljtoc/integration/download_integration_test.clj

---

## Phase 4: User Story 2 - Track and Display Download Progress (P1)

Progress reporting: percentage, pieces, bytes, rate, peer count.

**Independent Test**: Start download, query progress state at intervals, verify accuracy.

### Implementation

- [x] T034 [US2] Implement progress function in src/dev/cljtoc/orchestration/download.clj
- [x] T035 [US2] Implement rate calculation in src/dev/cljtoc/orchestration/download.clj
- [x] T036 [US2] Implement peer count tracking in src/dev/cljtoc/orchestration/download.clj
- [x] T037 [US2] Add progress updates on piece completion in src/dev/cljtoc/orchestration/download.clj

### Tests

- [x] T038 [US2] Create unit tests for progress function in test/dev/cljtoc/orchestration/download_test.clj

---

## Phase 5: User Story 3 - Handle Download Failures Gracefully (P2)

Error handling: corrupt piece re-download, peer disconnect recovery, error reporting.

**Independent Test**: Disconnect peer mid-download, verify download continues from other peers.

### Implementation

- [x] T039 [US3] Implement piece re-queue on verification failure in src/dev/cljtoc/orchestration/download.clj
- [x] T040 [US3] Implement handle-peer-disconnect in src/dev/cljtoc/orchestration/download.clj
- [x] T041 [US3] Implement peer reconnect logic in src/dev/cljtoc/orchestration/download.clj
- [x] T042 [US3] Implement error state transitions in src/dev/cljtoc/orchestration/download.clj
- [x] T043 [US3] Implement failure retry limit in src/dev/cljtoc/orchestration/download.clj

### Tests

- [x] T044 [US3] Create unit tests for failure handling in test/dev/cljtoc/orchestration/download_test.clj

---

## Phase 6: User Story 4 - Pause and Resume Download (P2)

State persistence: pause closes peers and saves state, resume restores and continues.

**Independent Test**: Pause mid-download, check state, resume, verify no data lost.

### Implementation

- [x] T045 [US4] Implement pause-download in src/dev/cljtoc/orchestration/download.clj
- [x] T046 [US4] Implement state persistence via IDiskPort in src/dev/cljtoc/orchestration/download.clj
- [x] T047 [US4] Implement resume-download in src/dev/cljtoc/orchestration/download.clj

### Tests

- [x] T048 [US4] Create unit tests for pause/resume in test/dev/cljtoc/orchestration/download_test.clj

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T049 [P] Add spec validation to all public functions in src/dev/cljtoc/orchestration/download.clj
- [x] T050 [P] Add spec validation to all public functions in src/dev/cljtoc/orchestration/manager.clj
- [x] T051 Run full test suite and fix any failures
- [x] T052 Verify all Success Criteria from spec.md are met

---

## Dependency Graph

```
Phase 1 (Setup)
    │
    ▼
Phase 2 (Foundational) ──────► Phase 3 (US1) ──► Phase 4 (US2)
    │                               │                   │
    │                               │                   │
    │                               ▼                   │
    │                         Phase 5 (US3) ◄──────────┘
    │                               │
    ▼                               ▼
Phase 6 (US4) ◄──────────────────────────► Phase 7 (Polish)
```

---

## Parallel Execution Opportunities

| Tasks | Files | Reason |
|-------|-------|--------|
| T007, T008, T009 | test_doubles/*.clj | Independent test doubles |
| T014, T015, T016 | test_doubles/*.clj | Independent mock implementations |
| T017, T018, T019, T020 | orchestration/download.clj | Same namespace, can batch |
| T049, T050 | orchestration/*.clj | Independent namespaces |

---

## Independent Test Criteria

| User Story | Test Criteria |
|------------|---------------|
| **US1** | Complete a small torrent download end-to-end with correct SHA-1 verification |
| **US2** | Query progress and verify percentage updates within 1 second of piece completion |
| **US3** | Disconnect peer mid-download; download continues from remaining peers |
| **US4** | Pause download, verify state persisted; resume and verify no data loss |

---

## Implementation Strategy

**MVP Scope**: US1 (Phase 3) — Core download functionality only

**Incremental Delivery**:
1. MVP: US1 completes basic download
2. Add US2 for visibility
3. Add US3 for robustness  
4. Add US4 for usability

**Note**: Phases 3-6 are ordered by dependency but US2, US3, US4 can be implemented in parallel after Phase 3 is complete, as they operate on the same Download record.
