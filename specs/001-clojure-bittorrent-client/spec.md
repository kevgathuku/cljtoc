# Architecture Specification: Clojure BitTorrent Client

**Feature Branch**: `001-clojure-bittorrent-client`
**Created**: 2026-01-26
**Status**: Complete - Architecture Defined
**Type**: Parent Specification (No Implementation)
**Input**: User description: "Build a BitTorrent client in Clojure that embraces crash-only design, uses core.async for concurrency, applies OTP-style supervision semantics, maintains hard boundaries between Domain (pure), Protocol logic, Effects (network, disk, time), and Supervision/lifecycle. Must be testable without I/O and avoid hidden global state."

## Overview

This specification defines the architecture and overall vision for a production-grade BitTorrent client in Clojure. The implementation is split into independently deliverable sub-features, each with its own specification, acceptance criteria, and success metrics.

## Vision & Scope

Build a BitTorrent client that can download and seed files from the BitTorrent network while exemplifying clean architecture principles:

- **Crash-Only Design**: The system expects and handles failures gracefully; restart is the primary recovery mechanism
- **Pure Domain Logic**: All business logic is pure, deterministic functions with no side effects
- **Explicit Effects**: Network I/O, disk I/O, time, and randomness are isolated behind ports/interfaces
- **Supervision Trees**: OTP-style supervisors manage worker lifecycles with explicit restart policies
- **Zero Hidden State**: All state is explicitly passed or managed by supervised components
- **Testability First**: Full system behavior testable without actual I/O using test doubles

## Sub-Features Roadmap

This architecture is implemented through the following independently deliverable features:

| Feature | Description | Status | Dependencies | Priority |
|---------|-------------|--------|--------------|----------|
| [001a-bencode-parser](../001a-bencode-parser/) | Parse .torrent files (bencode format) to domain model | Not Started | None | P1 - Foundation |
| [001b-tracker-protocol](../001b-tracker-protocol/) | HTTP/UDP tracker communication for peer discovery | Not Started | 001a | P1 - Required |
| [001c-peer-wire-protocol](../001c-peer-wire-protocol/) | BitTorrent peer message protocol implementation | Not Started | 001a | P1 - Required |
| [001d-piece-selection](../001d-piece-selection/) | Pure domain logic for piece management & verification | Not Started | 001a | P1 - Required |
| [001e-download-orchestration](../001e-download-orchestration/) | End-to-end single torrent download coordination | Not Started | 001a-d | P1 - MVP |
| [001f-seeding](../001f-seeding/) | Accept connections and serve pieces to peers | Not Started | 001e | P2 |
| [001g-multi-torrent](../001g-multi-torrent/) | Concurrent multi-torrent management | Not Started | 001f | P3 |
| [001h-monitoring](../001h-monitoring/) | Real-time statistics and progress reporting | Not Started | 001g | P3 |
| [001i-production-hardening](../001i-production-hardening/) | Supervision trees and crash recovery | Not Started | 001h | P4 |

**Implementation Strategy**: Features 001a-001d can be developed in parallel as they have minimal dependencies. Feature 001e integrates them into a working MVP. Features 001f-001i add incremental value on top of the MVP.

## High-Level User Journeys

### Primary Journey: Download a Torrent (Spans features 001a-001e)

A user provides a .torrent file and download directory, and the client downloads the complete file by connecting to peers, requesting pieces in optimal order, verifying integrity, and assembling the final file.

**End-to-End Success**: User can download a 1GB torrent from a healthy swarm to completion with full verification.

### Secondary Journey: Seed to the Swarm (Adds feature 001f)

A user with completed downloads contributes back to the network by accepting incoming connections and serving pieces to other peers.

**End-to-End Success**: Client can seed a file and serve pieces to requesting peers with fair upload distribution.

### Tertiary Journey: Manage Multiple Torrents (Adds feature 001g)

A user manages multiple concurrent downloads and seeds with appropriate resource sharing and independent progress tracking.

**End-to-End Success**: Multiple torrents make progress concurrently without resource starvation.

### Observability Journey: Monitor Progress (Adds feature 001h)

A user queries real-time statistics including progress, speeds, peer counts, and ETAs for all active transfers.

**End-to-End Success**: Statistics update within 5 seconds and accurately reflect system state.

### Reliability Journey: Automatic Recovery (Adds feature 001i)

The system automatically recovers from any component failure (peer disconnect, tracker unavailable, disk errors, crashes) without user intervention or data corruption.

**End-to-End Success**: Forceful termination at any point results in no corruption; restart resumes from last verified state.

## Critical Edge Cases (Cross-Cutting)

These scenarios must be considered across all sub-features:

- **Malformed Input**: Torrent files with missing/invalid fields, corrupt bencode
- **Malicious Peers**: Pieces that repeatedly fail verification, invalid protocol messages
- **Resource Exhaustion**: Disk full, memory pressure, connection limits
- **Network Failures**: Total outage, tracker unavailability, peer churn
- **Crashes**: SIGKILL mid-write, power loss, OOM termination
- **Scale**: Torrents >1TB, thousands of pieces, memory constraints
- **Cross-Seeding**: Multiple torrents referencing the same files


## Architectural Principles *(mandatory)*

These principles apply across all sub-features and form the non-negotiable foundation of the design:

### AP-001: Pure Domain Core

All business logic MUST be implemented as pure, deterministic functions with no side effects. This includes:
- Piece selection algorithms
- State transitions
- Verification logic
- Protocol message encoding/decoding
- Choking/unchoking decisions

**Rationale**: Pure functions are trivially testable, composable, and reason-able. They eliminate entire classes of bugs related to hidden state and timing.

### AP-002: Explicit Effect Isolation

All side effects MUST be isolated behind explicit ports/interfaces:
- **Network Port**: TCP/UDP socket operations
- **Disk Port**: File read/write operations
- **Time Port**: Current time, timers, delays
- **Random Port**: Random number generation

**Rationale**: Effect isolation enables testing with test doubles, deterministic replay, and clear boundaries between "what to do" (domain) and "how to do it" (effects).

### AP-003: Crash-Only Design

Components MUST be designed to handle crashes as the normal path:
- No graceful shutdown required (though allowed for optimization)
- State must be recoverable from persistent storage
- In-memory state must be reconstructible from durable state
- Restart is the primary recovery mechanism

**Rationale**: Simplifies error handling by eliminating complex cleanup paths. If crashes are safe, all errors become crashes.

### AP-004: Supervision Hierarchies

Every concurrent process MUST have an explicit supervisor:
- Supervisors own worker lifecycles
- Supervisors implement restart policies
- Workers fail fast and delegate recovery to supervisors
- No worker retries its own operations (unless designed as a retry-supervisor)

**Rationale**: Makes failure domains explicit and prevents hidden retry logic that can lead to resource leaks and cascading failures.

### AP-005: Zero Global State

No global mutable state (atoms, refs, agents) outside of supervised components:
- State must be explicitly passed via function arguments
- Stateful components must be managed by supervisors
- Configuration is immutable after initialization

**Rationale**: Global state creates hidden dependencies and makes testing, reasoning, and parallelization difficult.

### AP-006: Contract-First Protocols

All protocol implementations MUST define their interface as a protocol/multimethod:
- Separate "what" (interface) from "how" (implementation)
- Support multiple implementations (production, test, simulation)
- Enable composition and decoration

**Rationale**: Enables dependency injection, testing with fakes/mocks, and runtime selection of implementations.

## Key Domain Entities

These entities form the core domain vocabulary used across all sub-features:

### Torrent
Represents a torrent session with:
- **info-hash**: Unique SHA-1 identifier (20 bytes)
- **metadata**: Name, file list, piece length, total size
- **piece-hashes**: Vector of expected SHA-1 hashes (20 bytes each)
- **tracker-urls**: List of announce endpoints
- **state**: Download/seed progress and piece availability

### Piece
A fixed-size chunk of the torrent:
- **index**: Zero-based position in torrent
- **hash**: Expected SHA-1 hash (20 bytes)
- **length**: Size in bytes (last piece may be shorter)
- **status**: :pending, :downloading, :verifying, :complete
- **data**: Actual bytes (nil until downloaded)

### Block
Sub-piece unit for network transfer (typically 16KB):
- **piece-index**: Parent piece
- **offset**: Byte offset within piece
- **length**: Block size (≤ 16KB)
- **data**: Actual bytes

### Peer
Remote BitTorrent client connection:
- **peer-id**: 20-byte identifier
- **address**: IP and port
- **bitfield**: Which pieces peer has
- **state**: Connection, choke, interest flags
- **statistics**: Upload/download rates, bytes transferred

### Tracker
Announce endpoint for peer discovery:
- **url**: Announce URL
- **type**: :http or :udp
- **interval**: Time between announces
- **peers**: Last received peer list

### Supervisor
Process lifecycle manager:
- **children**: Supervised worker processes
- **strategy**: :one-for-one, :all-for-one, :rest-for-one
- **max-restarts**: Failure rate limit
- **restart-policy**: :permanent, :transient, :temporary

## Cross-Cutting Requirements *(mandatory)*

These requirements apply to all sub-features:

### Testability

- **CR-001**: All sub-features MUST achieve ≥90% unit test coverage for domain logic
- **CR-002**: All sub-features MUST be testable without actual I/O via injectable ports
- **CR-003**: All sub-features MUST support deterministic testing via injectable time/randomness
- **CR-004**: Integration tests MUST run deterministically without flaky failures

### Performance

- **CR-005**: Memory usage MUST remain bounded regardless of torrent size (streaming, not loading entire pieces)
- **CR-006**: CPU usage MUST be efficient (no polling loops, use async notification)
- **CR-007**: Download speed MUST reach ≥80% of available bandwidth with sufficient peers

### Reliability

- **CR-008**: All disk writes MUST be atomic or recoverable (no partial corrupted state)
- **CR-009**: Component failures MUST be contained to failure domains (no cascading failures)
- **CR-010**: Forceful termination MUST result in resumable state (no data loss)

### Protocol Compliance

- **CR-011**: All protocol implementations MUST conform to BitTorrent v1 spec (BEP 3)
- **CR-012**: All network messages MUST be validated before processing
- **CR-013**: Invalid peer behavior MUST result in connection termination, not crashes

## Technology Constraints


## Technology Constraints

- **Language**: Clojure (JVM) for core implementation
- **Concurrency**: core.async for managing concurrent processes
- **Testing**: clojure.test or similar for unit/integration tests
- **Build**: Leiningen or tools.deps for dependency management
- **Target**: BitTorrent v1 protocol (BEP 3) as baseline

## Assumptions

- The client targets BitTorrent v1 (BEP 3); extensions (DHT, PEX, magnet links) are future enhancements
- Both single-file and multi-file torrents are supported
- Command-line interface is the initial user interface; GUI is a future enhancement
- Standard piece selection (rarest-first) is used; endgame mode is handled in sub-feature 001d
- Default block size is 16KB per BitTorrent convention
- Tracker announce intervals follow tracker-specified values with 30-minute default
- The client gracefully degrades when trackers are unavailable (uses cached peers)
- IPv4 is required; IPv6 is optional

## System-Level Success Criteria *(mandatory)*

These criteria apply to the complete system (all sub-features integrated):

### Functional Completeness

- **SC-001**: A user can download a 1GB single-file torrent from a healthy swarm to completion with full verification
- **SC-002**: A user can seed a completed torrent and serve pieces to at least 10 concurrent requesting peers
- **SC-003**: A user can manage 5 concurrent torrents with independent progress and status

### Architectural Quality

- **SC-004**: All domain logic across all sub-features achieves ≥90% unit test coverage without requiring actual I/O
- **SC-005**: Integration tests execute deterministically with simulated time, network, and disk, with zero flaky test failures over 100 runs
- **SC-006**: No component uses global mutable state; all state is explicitly managed or passed

### Reliability

- **SC-007**: The client recovers from any single component failure (peer, tracker, disk writer) within 30 seconds without data loss
- **SC-008**: Forceful termination (SIGKILL) at any point results in zero data corruption; restart resumes from last verified state
- **SC-009**: The client handles 100 sequential peer disconnects during a download without failing or leaking resources

### Performance

- **SC-010**: Download speed reaches ≥80% of available bandwidth when connected to 20+ peers with sufficient upload capacity
- **SC-011**: Memory usage stays below 100MB per active torrent regardless of torrent size (streaming model)
- **SC-012**: The client sustains 50 concurrent peer connections per torrent while maintaining <100ms response to user status queries

## Sub-Feature Requirements Summary

The functional requirements from the original spec are distributed across sub-features as follows:

| Requirement | Sub-Feature | Description |
|-------------|-------------|-------------|
| FR-001 | 001a | Parse and validate .torrent files (bencode) |
| FR-002 | 001c | Implement peer wire protocol messages |
| FR-003 | 001d | Verify pieces against SHA-1 hashes |
| FR-004 | 001b | Announce to HTTP/UDP trackers |
| FR-005 | 001e | Persist download progress for resume |
| FR-006-011 | All | Architecture principles (pure domain, ports, supervision) |
| FR-012 | 001e | Maintain multiple concurrent peer connections |
| FR-013 | 001f | Implement choking/unchoking algorithm |
| FR-014 | 001d | Track piece availability for selection |
| FR-015 | 001e | Handle peer disconnections gracefully |
| FR-016-018 | 001e | Disk management (atomic writes, allocation) |
| FR-019-021 | All | Testability via injectable ports |

## Getting Started

To begin implementing this architecture:

1. **Start with 001a** (bencode parser): This is the foundation with zero dependencies
2. **Proceed with 001b-d in parallel**: These can be developed independently once 001a is complete
3. **Integrate with 001e**: Brings together all pieces into working MVP
4. **Add value incrementally with 001f-i**: Each adds independent value on top of MVP

Each sub-feature has its own specification with detailed user stories, acceptance criteria, and success metrics. Refer to the sub-feature specs for implementation details.

## Notes

- This is an **architecture specification**, not an implementation specification
- Implementation details belong in sub-feature specs (001a-001i)
- Changes to architectural principles require review of impact on all sub-features
- Sub-features can be developed, tested, and merged independently
- The roadmap table should be updated as sub-features are completed
