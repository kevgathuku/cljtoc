# Implementation Plan: Peer Wire Protocol

**Branch**: `004-peer-wire-protocol` | **Date**: 2026-02-17 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-peer-wire-protocol/spec.md`

## Summary

Implement pure functions for parsing and building BitTorrent peer wire protocol messages (BEP 3). This feature provides the Protocol Layer functions to encode/decode handshake and peer messages (keep-alive, choke, unchoke, interested, not-interested, have, bitfield, request, piece, cancel) as well as pure state transition functions for peer connection state management. No network I/O - operates on byte arrays only.

## Technical Context

**Language/Version**: Clojure 1.11+ (JVM-based)
**Primary Dependencies**: None (pure functions only, core.async not needed at this layer)
**Storage**: N/A - operates on in-memory byte arrays only
**Testing**: clojure.test with generative testing (test.check optional)
**Target Platform**: JVM (cross-platform CLI)
**Project Type**: Single project (library component)
**Performance Goals**: Parse/build single message in <1ms; support streaming message parsing
**Constraints**: 
  - All functions must be pure (no I/O, no side effects)
  - Must follow 4-layer architecture (Protocol Layer)
  - Zero exceptions for expected failures (return `{:error ...}` maps)
  - Must achieve 90%+ test coverage without any network I/O
**Scale/Scope**: Handle all 9 BEP 3 message types plus handshake; bitfield support for torrents with up to 100k pieces

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Research Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ✅ PASS | Protocol Layer placement appropriate per constitution. State machine (User Story 5) uses pure `(state, event) → state` transitions. No I/O imports allowed. |
| **II. Explicit Effect Boundaries** | ✅ PASS | FR-013 explicitly prohibits network I/O: "byte arrays are the boundary". All functions operate on raw bytes only. |
| **III. Crash-Only Supervision** | ⚪ N/A | Supervision concerns Coordination/Supervisor layers, not Protocol Layer. This feature is pure functions only. |
| **IV. No Hidden State** | ✅ PASS | FR-007 requires immutable data structures. FR-008 mandates pure state-transition functions. No atoms/refs/globals. |
| **V. Testability Without I/O** | ✅ PASS | SC-005 requires "90%+ of test coverage without any network I/O — all tests use in-memory byte arrays". |

**Pre-Research Gate Result**: ✅ ALL GATES PASS - Proceed to Phase 0 research

### Post-Design Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ✅ PASS | Confirmed: `peer.clj` and `peer_state.clj` use only pure functions. No `core.async` or I/O imports. Records are immutable. |
| **II. Explicit Effect Boundaries** | ✅ PASS | API contracts specify byte array boundaries only. No network or disk operations in module. |
| **III. Crash-Only Supervision** | ⚪ N/A | Still N/A at Protocol Layer. Supervision handled at Coordination Layer (feature 006). |
| **IV. No Hidden State** | ✅ PASS | `PeerState` record design uses immutable data structures. State transitions return new instances. No global atoms. |
| **V. Testability Without I/O** | ✅ PASS | Test strategy defined: generative tests for round-trip properties, example-based for edge cases. 90%+ coverage target. |

**Post-Design Gate Result**: ✅ ALL GATES PASS - Design compliant with constitution

## Project Structure

### Documentation (this feature)

```text
specs/004-peer-wire-protocol/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Following the 4-layer architecture defined in the constitution:

```text
src/
└── dev/
    └── cljtoc/
        └── protocol/
            ├── peer.clj         # Peer wire message parsing/building
            └── peer_state.clj   # Peer connection state machine (pure transitions)

test/
└── dev/
    └── cljtoc/
        └── protocol/
            ├── peer_test.clj       # Generative tests for message parsing/building
            └── peer_state_test.clj # State machine transition tests
```

**Structure Decision**: Single project with Protocol Layer namespace. Per constitution Layered Architecture, peer wire protocol functions live in the Protocol Layer (`dev.cljtoc.protocol.*`). State machine functions also in Protocol Layer as they manage connection protocol state, not domain business logic.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |

---

## Planning Complete

**Status**: ✅ Phase 0 (Research) and Phase 1 (Design) Complete

### Generated Artifacts

| Artifact | Path | Description |
|----------|------|-------------|
| Implementation Plan | `plan.md` | This document - technical context, constitution check, structure |
| Research | `research.md` | Phase 0 - byte manipulation, message representation, testing strategy decisions |
| Data Model | `data-model.md` | Phase 1 - PeerHandshake, PeerMessage, PeerState, BlockRequest entities |
| API Contracts | `contracts/api.md` | Phase 1 - Function signatures, invariants, error handling contracts |
| Quickstart | `quickstart.md` | Phase 1 - Developer usage guide with examples |
| Agent Context | `AGENTS.md` | Updated with Clojure 1.11+ context |

### Next Step: Task Breakdown

Run `/speckit.tasks` to generate the implementation task list:

```
/speckit.tasks
```

This will create `tasks.md` with actionable work items for implementation.

### Architecture Compliance

✅ **All constitution principles verified**:
- Pure functions only (no I/O, no side effects)
- Byte array boundary (no network in protocol layer)
- Immutable state transitions
- 90%+ test coverage without I/O

### Dependencies

- **Parent**: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md) - Architecture specification
- **Depends On**: 002-bencode-parser, 003-tracker-protocol
- **Required By**: 005-piece-selection, 006-download-orchestration
