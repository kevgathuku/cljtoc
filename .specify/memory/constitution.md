<!--
================================================================================
SYNC IMPACT REPORT
================================================================================
Version change: 0.0.0 → 1.0.0 (initial ratification)
Modified principles: N/A (initial version)
Added sections:
  - Core Principles (5 principles)
  - Layered Architecture
  - Development Workflow
  - Governance
Removed sections: N/A (initial version)
Templates requiring updates:
  - .specify/templates/plan-template.md: ✅ Compatible (Constitution Check section exists)
  - .specify/templates/spec-template.md: ✅ Compatible (no updates required)
  - .specify/templates/tasks-template.md: ✅ Compatible (phase structure aligns)
Follow-up TODOs: None
================================================================================
-->

# Clojure BitTorrent Client Constitution

## Core Principles

### I. Pure Domain Layer

All torrent logic MUST be implemented as pure, deterministic functions with no side effects:

- Piece selection algorithms MUST be pure functions of current state
- Hash verification MUST be a pure transformation (bytes → boolean)
- State transitions MUST be expressed as `(state, event) → state`
- Domain functions MUST NOT import I/O namespaces or core.async
- Domain data structures MUST be immutable and serializable
- The domain layer MUST NOT know it is running concurrently

**Rationale**: Pure domain logic enables exhaustive unit testing without mocks, deterministic behavior under all conditions, and clear reasoning about correctness independent of timing or I/O failures.

### II. Explicit Effect Boundaries

All side effects MUST be isolated behind explicit port interfaces:

- Network I/O (peer connections, tracker communication)
- Disk I/O (piece storage, state persistence)
- Time (current time, delays, timeouts)
- Randomness (peer selection, protocol jitter)

Effect ports MUST:

- Be defined as protocols/interfaces separate from implementation
- Accept injected implementations at construction time
- Own their failure modes and communicate via channels
- Live at the edges of the system, never in domain or coordination layers

**Rationale**: Explicit effect boundaries enable testing without actual I/O, deterministic integration tests with simulated time/network/disk, and clear ownership of failure modes.

### III. Crash-Only Supervision

The system MUST embrace crash-only design with explicit OTP-style supervision:

- Worker processes MUST be allowed to crash on unexpected errors
- Workers MUST NOT perform their own retries; retry logic belongs to supervisors
- Every concurrent process MUST have an explicit supervisor responsible for its lifecycle
- Supervisors MUST implement defined restart policies (one-for-one, rest-for-one, etc.)
- Failure detection MUST be explicit (monitor channels, not implicit timeouts)
- Recovery MUST preserve data integrity; no partial states allowed post-restart

**Rationale**: Crash-only design simplifies error handling, prevents cascading failures through isolation, and ensures the system can recover from any failure state automatically.

### IV. No Hidden State

All state MUST be explicitly passed or managed through supervised components:

- No global atoms, vars, or dynamic bindings for application state
- No hidden go blocks or orphaned channels
- All channel lifetimes MUST be owned by a supervisor
- Configuration MUST be passed explicitly, not read from ambient environment
- State changes MUST be traceable through explicit function calls or channel messages

**Rationale**: Explicit state management eliminates race conditions, enables reproducible testing, and makes system behavior predictable and debuggable.

### V. Testability Without I/O

The system MUST support comprehensive testing without actual I/O:

- Domain logic MUST achieve 90%+ unit test coverage with pure function tests
- Integration tests MUST run deterministically with simulated time, network, and disk
- All injectable ports MUST have corresponding test double implementations
- Tests MUST NOT be flaky; any non-determinism indicates an architecture violation
- Crash/restart scenarios MUST be testable through supervisor test harnesses

**Rationale**: I/O-free testing enables fast feedback cycles, reliable CI/CD, and confidence that the system behaves correctly under all conditions including failures.

## Layered Architecture

The system MUST be organized into four distinct layers with strict dependency rules:

```
+---------------------+
|  Supervisor Layer   |  ← Owns lifecycles, restart policies, monitors
|---------------------|
| restart policies    |
| lifecycle mgmt      |
+----------+----------+
           |
           | spawns/monitors
           v
+---------------------+
| Coordination Layer  |  ← core.async flows, message routing, backpressure
|---------------------|
| core.async channels |
| message routing     |
+----------+----------+
           |
           | calls
           v
+---------------------+
| Protocol Layer      |  ← BitTorrent protocol encoding/decoding, wire format
|---------------------|
| bencode parsing     |
| peer wire protocol  |
+----------+----------+
           |
           | uses
           v
+---------------------+
| Domain Layer        |  ← Pure torrent logic, no I/O, no concurrency
|---------------------|
| piece selection     |
| state transitions   |
+---------------------+
```

**Dependency Rules**:

- Domain Layer MUST NOT depend on any layer above it
- Protocol Layer MAY depend on Domain Layer only
- Coordination Layer MAY depend on Protocol and Domain Layers
- Supervisor Layer MAY depend on all layers below
- Effect implementations (network, disk) are injected at the edges, not imported directly

## Development Workflow

### Code Review Gates

All code changes MUST pass these gates before merge:

1. **Pure Domain Check**: Domain namespace files contain no I/O imports
2. **Effect Boundary Check**: Side effects only occur in designated effect namespaces
3. **Supervision Check**: All go blocks have explicit supervisor ownership documented
4. **State Check**: No new global state introduced; state flows are explicit
5. **Test Check**: New code has corresponding tests; domain tests are pure

### Testing Requirements

- Domain logic: Pure unit tests, no mocks required
- Coordination logic: Integration tests with simulated ports
- Full system: End-to-end tests with test doubles for all effects
- Crash scenarios: Supervisor tests that simulate process failures

## Governance

This constitution supersedes all other architectural practices for this project. Violations require explicit documentation in the Complexity Tracking section of implementation plans.

**Amendment Process**:

1. Propose amendment with rationale
2. Assess impact on existing code and tests
3. Document migration plan if breaking
4. Update version according to semantic versioning:
   - MAJOR: Principle removal or incompatible redefinition
   - MINOR: New principle or material expansion
   - PATCH: Clarifications and non-semantic refinements
5. Update all dependent templates and documentation

**Compliance Review**:

- Every PR MUST verify compliance with all five principles
- Architecture violations MUST be flagged and justified or resolved
- Periodic audits SHOULD verify no drift from constitutional principles

**Version**: 1.0.0 | **Ratified**: 2026-01-26 | **Last Amended**: 2026-01-26
