# Architecture Plan: Clojure BitTorrent Client

**Branch**: `001-clojure-bittorrent-client` | **Date**: 2026-01-26 | **Spec**: [spec.md](spec.md)
**Type**: Architecture Definition (No Implementation)
**Status**: Complete

## Purpose

This is an **architecture specification** that defines principles, entities, and structure for the BitTorrent client project. It does **not** contain implementation tasks.

Implementation happens in independent sub-features: **001a** through **001i**, each with its own spec-kit lifecycle.

## Architecture Artifacts

The following artifacts define the architecture:

### 1. Core Architecture Document
- **File**: [spec.md](spec.md)
- **Contains**: 
  - Vision & scope
  - 6 architectural principles (AP-001 to AP-006)
  - Key domain entities
  - Sub-feature roadmap with dependencies
  - Cross-cutting requirements
  - System-level success criteria

### 2. Requirements Checklist
- **File**: [checklists/requirements.md](checklists/requirements.md)
- **Contains**: Validation checklist for architecture completeness

## Sub-Feature Implementation Path

Each sub-feature follows the full spec-kit workflow independently:

```
1. Create Feature:  /speckit.specify <feature description>
   → Creates specs/001x-feature-name/ directory
   → Generates spec.md with user stories and requirements

2. Plan Feature:    /speckit.plan
   → Creates plan.md with technical approach
   → Generates research.md, data-model.md, etc.

3. Task Breakdown:  /speckit.tasks
   → Creates tasks.md with actionable work items

4. Implementation:  /speckit.implement
   → Executes tasks with automated assistance
```

### Sub-Features Roadmap

#### Core Engine Features

| ID | Feature | Dependencies | Priority | Command to Start | Status |
|----|---------|--------------|----------|------------------|--------|
| 002 | Bencode Parser | None | P1 | `/speckit.specify Parse .torrent files...` | ✅ Complete |
| 003 | Tracker Protocol | 002 | P1 | `/speckit.specify Implement HTTP/UDP tracker communication` | ✅ Complete |
| 004 | Peer Wire Protocol | 002 | P1 | `/speckit.specify Implement BitTorrent peer message protocol` | 🚧 In Progress (spec) |
| 005 | Piece Selection | 002 | P1 | `/speckit.specify Pure domain logic for piece management` | Not Started |
| 006 | Download Orchestration | 002-005 | P1 | `/speckit.specify End-to-end single torrent download` | Not Started |

#### User Interface Features

| ID | Feature | Dependencies | Priority | Command to Start | Status |
|----|---------|--------------|----------|------------------|--------|
| 011 | CLI Interface | 006 | P1 | `/speckit.specify Command-line interface with download, pause, resume, status, seed commands` | Not Started |

#### Enhancement Features

| ID | Feature | Dependencies | Priority | Command to Start | Status |
|----|---------|--------------|----------|------------------|--------|
| 007 | Seeding | 006 | P2 | `/speckit.specify Accept connections and serve pieces` | Not Started |
| 008 | Multi-Torrent | 007 | P2 | `/speckit.specify Manage multiple concurrent torrents` | Not Started |
| 009 | Monitoring | 008 | P3 | `/speckit.specify Real-time statistics and progress` | Not Started |
| 010 | Production Hardening | 009 | P4 | `/speckit.specify Supervision trees and crash recovery` | Not Started |

## Technical Context (Applies to All Sub-Features)

**Language/Version**: Clojure (JVM, latest stable)
**Primary Dependencies**: core.async for concurrency
**Storage**: File system for torrents and downloads
**Testing**: clojure.test with test doubles for I/O
**Target Platform**: JVM (cross-platform CLI)
**Project Type**: Single project (library + CLI)
**Performance Goals**: 
  - ≥80% bandwidth utilization with sufficient peers
  - <100MB memory per active torrent
  - 50+ concurrent peer connections per torrent
**Constraints**: 
  - Pure domain functions (no side effects)
  - Injectable ports for all I/O
  - Explicit supervision for all workers
**Scale/Scope**: 
  - MVP: Single torrent download
  - Full: Multiple torrents, seeding, monitoring

## Project Structure

### Architecture Documentation (Current Feature)

```text
specs/001-clojure-bittorrent-client/
├── spec.md              # Architecture specification
├── plan.md              # This file
└── checklists/
    └── requirements.md  # Architecture validation checklist
```

### Sub-Feature Documentation (Created Independently)

```text
specs/001a-bencode-parser/
├── spec.md              # Feature specification
├── plan.md              # Implementation plan
├── tasks.md             # Task breakdown
└── checklists/          # Feature checklists

specs/001b-tracker-protocol/
├── spec.md
├── plan.md
├── tasks.md
└── checklists/

[... etc for 001c-001i ...]
```

### Source Code (Shared Repository Root)

```text
src/
└── dev/
    └── cljtoc/
        ├── domain/          # Pure domain logic (001a, 001d)
        │   ├── bencode.clj
        │   ├── torrent.clj
        │   └── pieces.clj
        ├── protocol/        # Protocol implementations (001b, 001c)
        │   ├── tracker.clj
        │   └── peer.clj
        ├── ports/           # Effect interfaces (all features)
        │   ├── network.clj
        │   ├── disk.clj
        │   └── time.clj
        ├── orchestration/   # Coordination (001e, 001g)
        │   ├── download.clj
        │   └── manager.clj
        └── supervision/     # Lifecycle (001i)
            └── supervisor.clj

test/
└── dev/
    └── cljtoc/
        ├── domain/
        ├── protocol/
        ├── integration/
        └── test_doubles/    # Fake implementations of ports
```

## Architectural Principles (All Sub-Features MUST Follow)

1. **AP-001: Pure Domain Core** - All business logic as pure functions
2. **AP-002: Explicit Effect Isolation** - I/O behind ports
3. **AP-003: Crash-Only Design** - Restart is primary recovery
4. **AP-004: Supervision Hierarchies** - Explicit supervisors for workers
5. **AP-005: Zero Global State** - No mutable globals
6. **AP-006: Contract-First Protocols** - Interfaces before implementations

See [spec.md](spec.md) for full details on each principle.

## Next Steps

To continue implementation:

1. ~~**Complete Feature 002**: Bencode parser~~ ✅ Merged to main
2. ~~**Proceed to 003**: Tracker protocol (HTTP + UDP, BEP 3/15)~~ ✅ Merged to main
3. **Complete Feature 004**: Peer wire protocol — spec written, planning and implementation next
4. **Complete Feature 005**: Piece selection — can start in parallel with 004
5. **Integrate at 006**: Brings all pieces together into working download engine
6. **Add CLI at 011**: Makes the engine usable by end users - **completes MVP**
7. **Add value with 007-010**: Each adds independent functionality (seeding, multi-torrent, monitoring, hardening)

**True MVP**: Features 002-006 + 011 (Foundation + Engine + CLI)
**Enhanced Product**: Add features 007-010 incrementally

## Notes

- This spec (001) has **no implementation tasks** - it only defines architecture
- Each sub-feature is an **independent spec-kit feature** with its own branch
- Sub-features can be developed, tested, and merged separately
- The roadmap in [spec.md](spec.md) should be updated as sub-features complete
