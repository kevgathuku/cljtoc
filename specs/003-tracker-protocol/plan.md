# Implementation Plan: Tracker Protocol Communication

**Branch**: `003-tracker-protocol` | **Date**: 2026-02-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-tracker-protocol/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement BitTorrent tracker communication protocols (HTTP and UDP) for peer discovery. The feature provides pure parsing and message-building functions for both tracker protocols, with all network I/O isolated behind injectable ports. HTTP tracker support (BEP 3) is prioritized as P1-P2, followed by UDP tracker support (BEP 15) as P3-P4. The implementation adheres to the constitutional requirement for pure domain/protocol logic with explicit effect boundaries.

## Technical Context

**Language/Version**: Clojure 1.11+ (JVM-based)
**Primary Dependencies**:
  - dev.cljtoc.domain.bencode (feature 002, for HTTP tracker response parsing)
  - clojure.string (URL encoding/decoding)
  - Java networking types (InetAddress for IP validation)
**Storage**: N/A (stateless protocol parsing)
**Testing**: clojure.test with property-based testing (test.check) for protocol message generation
**Target Platform**: JVM (Java 11+)
**Project Type**: Single project (domain/protocol library)
**Performance Goals**:
  - Parse compact peer lists with 1000+ peers without performance degradation
  - URL encoding for binary data completes in <1ms
**Constraints**:
  - All parsing/building functions MUST be pure (no I/O, no side effects)
  - Network I/O MUST be behind injectable port protocols
  - Time operations MUST use injectable time port
  - No exceptions for expected failures (return error maps)
**Scale/Scope**:
  - Support HTTP and UDP tracker protocols per BEP 3 and BEP 15
  - Handle peer lists up to 1000+ peers
  - Support both IPv4 and IPv6 addresses

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Based on `.specify/memory/constitution.md` version 1.0.0:

### Principle I: Pure Domain Layer ✅ PASS

**Requirement**: All protocol logic MUST be pure, deterministic functions with no side effects.

**Verification**:
- [x] HTTP tracker request building: Pure function (torrent metadata → URL string)
- [x] HTTP tracker response parsing: Pure function (bytes → peer list data)
- [x] UDP message building: Pure function (parameters → byte array)
- [x] UDP message parsing: Pure function (bytes → structured data)
- [x] Timing calculations: Pure function (interval → next announce time)
- [x] URL encoding: Pure transformation (binary data → encoded string)

**Status**: Protocol layer functions are pure transformations with no I/O imports.

### Principle II: Explicit Effect Boundaries ✅ PASS

**Requirement**: All side effects MUST be isolated behind explicit port interfaces.

**Verification**:
- [x] Network I/O (HTTP requests, UDP datagrams) behind `TrackerNetworkPort` protocol
- [x] Time access (current time, scheduling) behind `TimePort` protocol
- [x] No direct access to Java networking from protocol functions

**Ports Required**:
1. `TrackerNetworkPort`: HTTP GET requests, UDP send/receive
2. `TimePort`: Current timestamp, future time calculations

**Status**: All effects isolated; protocol functions accept port implementations at construction.

### Principle III: Crash-Only Supervision ⚠️ DEFERRED

**Requirement**: Workers MUST crash on errors; supervisors handle restarts.

**Status**: Supervision is out of scope for this feature (protocol parsing only). Coordination layer (feature 006+) will add supervisors for tracker announce workers.

**Justification**: This feature delivers pure protocol functions. Crash-only semantics apply when these functions are used by supervised workers in later features.

### Principle IV: No Hidden State ✅ PASS

**Requirement**: No global atoms/vars for application state.

**Verification**:
- [x] All functions are stateless transformations
- [x] Connection ID (UDP) passed explicitly as parameter
- [x] No global configuration; all parameters passed to functions

**Status**: Zero global state; all context passed explicitly.

### Principle V: Testability Without I/O ✅ PASS

**Requirement**: 90%+ test coverage without actual I/O.

**Verification**:
- [x] All parsing functions testable with byte arrays (no network)
- [x] All building functions testable by inspecting output (no network)
- [x] Time calculations testable with fixed timestamp values
- [x] Property-based testing for round-trip encoding/decoding

**Test Doubles Required**:
- `FakeTrackerNetworkPort`: Returns canned HTTP/UDP responses
- `FakeTimePort`: Returns fixed timestamps for deterministic tests

**Status**: All protocol logic testable without network I/O.

### Gate Summary

| Principle | Status | Blocker? |
|-----------|--------|----------|
| I. Pure Domain Layer | ✅ PASS | No |
| II. Explicit Effect Boundaries | ✅ PASS | No |
| III. Crash-Only Supervision | ⚠️ DEFERRED | No (out of scope) |
| IV. No Hidden State | ✅ PASS | No |
| V. Testability Without I/O | ✅ PASS | No |

**Overall**: ✅ **APPROVED** - All applicable principles satisfied. Supervision is deferred to coordination layer features.

## Project Structure

### Documentation (this feature)

```text
specs/003-tracker-protocol/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── http-tracker.md  # HTTP tracker protocol contract
│   └── udp-tracker.md   # UDP tracker protocol contract
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/dev/cljtoc/
├── protocol/
│   └── tracker.clj      # Tracker protocol parsing and message building (NEW)
└── domain/
    └── bencode.clj      # Bencode parser (existing, dependency)

test/dev/cljtoc/
└── protocol/
    └── tracker_test.clj # Tracker protocol tests (NEW)
```

**Structure Decision**: Single Clojure project following the existing layered architecture:

- **Protocol Layer** (`src/dev/cljtoc/protocol/`): Pure parsing and encoding functions for tracker protocols
  - `tracker.clj`: HTTP and UDP tracker protocol implementation

- **Domain Layer** (`src/dev/cljtoc/domain/`): Existing bencode parser (dependency)

- **Tests** (`test/dev/cljtoc/protocol/`): Mirror source structure
  - Property-based tests for round-trip encoding
  - Unit tests with sample tracker responses
  - No network I/O (use test doubles)

**Namespace Organization**:
- `dev.cljtoc.protocol.tracker`: Public API for tracker communication
- `dev.cljtoc.protocol.tracker.http`: HTTP tracker protocol functions (if needed)
- `dev.cljtoc.protocol.tracker.udp`: UDP tracker protocol functions (if needed)

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

**Status**: No constitutional violations. All applicable principles satisfied.

This feature implements pure protocol functions with explicit effect boundaries, fully compliant with the constitutional architecture.

---

## Planning Phases Complete

### Phase 0: Research ✅ COMPLETE

**Artifacts Generated**:
- [research.md](research.md) - Protocol research and technology decisions

**Key Decisions**:
1. **HTTP Tracker Protocol**: BEP 3 with RFC 3986 URL encoding for binary data
2. **UDP Tracker Protocol**: BEP 15 with big-endian binary message formats
3. **URL Encoding**: Custom implementation (not Java URLEncoder) to preserve binary data
4. **Binary Parsing**: Java ByteBuffer for clean multi-byte integer parsing
5. **Time Handling**: Explicit timestamp parameters for pure function testing
6. **IPv6 Support**: Compact format (18 bytes per peer) alongside IPv4

**Research Findings**:
- Compact peer format preferred (6 bytes IPv4, 18 bytes IPv6) for efficiency
- UDP connection IDs expire after 60 seconds (must reconnect)
- Default announce interval is 1800 seconds when not specified
- Transaction IDs must be random to prevent UDP response spoofing

---

### Phase 1: Design & Contracts ✅ COMPLETE

**Artifacts Generated**:
1. [data-model.md](data-model.md) - Data structures and entity definitions
2. [contracts/http-tracker.md](contracts/http-tracker.md) - HTTP tracker protocol contract
3. [contracts/udp-tracker.md](contracts/udp-tracker.md) - UDP tracker protocol contract
4. [quickstart.md](quickstart.md) - Developer usage guide with examples

**Data Model Entities**:
- `TrackerRequest`: Announce request parameters (HTTP/UDP)
- `TrackerResponse`: Parsed tracker response with peer list
- `Peer`: Individual peer information (IP, port, optional peer ID)
- `AnnounceSchedule`: Timing for re-announces and retry backoff
- `TrackerError`: Error classification and context

**Protocol Contracts**:
- **HTTP Tracker**: 6 public functions (build URL, parse response, parse peers)
- **UDP Tracker**: 8 public functions (connect, announce, scrape for each direction)
- All functions pure with `{:ok value}` or `{:error ...}` return convention

**Agent Context Updated**:
- Added Clojure 1.11+ as active technology
- Added stateless protocol parsing as architecture pattern
- Updated CLAUDE.md with tracker protocol context

---

### Constitution Re-Check (Post-Design) ✅ PASS

All five constitutional principles remain satisfied after design phase:

| Principle | Pre-Design | Post-Design | Notes |
|-----------|------------|-------------|-------|
| I. Pure Domain Layer | ✅ PASS | ✅ PASS | All protocol functions remain pure |
| II. Explicit Effect Boundaries | ✅ PASS | ✅ PASS | NetworkPort and TimePort defined in contracts |
| III. Crash-Only Supervision | ⚠️ DEFERRED | ⚠️ DEFERRED | Still out of scope (protocol layer only) |
| IV. No Hidden State | ✅ PASS | ✅ PASS | All state passed explicitly per data model |
| V. Testability Without I/O | ✅ PASS | ✅ PASS | Test doubles defined in contracts |

**No new risks or violations introduced during design.**

---

## Next Steps

### Ready for Implementation (`/speckit.tasks`)

The planning phase is complete. To proceed with implementation:

1. Run `/speckit.tasks` to generate task breakdown in `tasks.md`
2. Implement HTTP tracker protocol (P1-P2) first
3. Implement UDP tracker protocol (P3-P4) second
4. Add error handling and scheduling (P5-P6)

### Dependencies

- **Blocking**: Feature 002 (bencode-parser) must be complete for HTTP tracker response parsing
- **Non-blocking**: Can develop in parallel with other protocol features

### Success Criteria Validation

After implementation, verify against spec success criteria:
- SC-001: 100% HTTP response parsing accuracy
- SC-002: 100% UDP response parsing accuracy
- SC-005: 90%+ test coverage without network I/O
- SC-007: Handle 1000+ peer lists without degradation
- SC-009: URL encoding matches reference implementations
