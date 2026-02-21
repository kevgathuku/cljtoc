# Implementation Plan: End-to-End Single Torrent Download

**Branch**: `006-download-orchestration` | **Date**: 2026-02-21 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/006-download-orchestration/spec.md`

## Summary

Implements the download orchestration layer that coordinates bencode parsing (002), tracker communication (003), peer wire protocol (004), and piece management (005) into a functional end-to-end torrent download engine. Coordinates concurrent peer connections using core.async, manages piece state machine, handles persistence of download progress, and assembles verified pieces to disk. All I/O isolated behind injectable port protocols.

## Technical Context

**Language/Version**: Clojure 1.11+ (JVM-based)  
**Primary Dependencies**: core.async for coordination,clojure.spec.alpha for validation  
**Storage**: File system for downloaded pieces, JSON/EDN for state persistence  
**Testing**: clojure.test + clojure.spec.alpha + test.check (same as existing test suite)  
**Target Platform**: JVM (cross-platform CLI)  
**Project Type**: Single project (library + CLI)  
**Performance Goals**: 80%+ bandwidth utilization with 10+ peers, <100MB memory per active torrent, 50+ concurrent peer connections  
**Constraints**: All I/O behind ports, pure coordination logic, explicit supervision for all workers  
**Scale/Scope**: Single torrent download MVP, handles torrents with up to 100,000 pieces and 200+ peers

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Research Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ⚪ N/A | This is coordination layer, not domain. Uses domain functions from features 002-005 but is not itself domain. |
| **II. Explicit Effect Boundaries** | ✅ PASS | Network, disk, and time effects are isolated behind port protocols (INetworkPort, IDiskPort, ITimePort). No direct I/O imports in coordination namespace. |
| **III. Crash-Only Supervision** | ✅ PASS | All peer connection workers have explicit supervisor ownership. Workers crash on errors; supervisors handle restart/reconnect logic. |
| **IV. No Hidden State** | ✅ PASS | All state is explicit in download manager records. No global atoms or vars. Piece state is passed through channels, not stored in globals. |
| **V. Testability Without I/O** | ✅ PASS | All ports can be injected with test doubles. Coordination logic is testable with mock network/disk/time implementations. |

**Pre-Research Gate Result**: ✅ ALL GATES PASS — Proceed to Phase 0 research

### Post-Design Check

| Principle | Status | Evidence |
|-----------|--------|----------|
| **I. Pure Domain Layer** | ⚪ N/A | Coordination layer - uses domain functions but is not domain itself. |
| **II. Explicit Effect Boundaries** | ✅ PASS | Ports defined in `dev.cljtoc.ports.*`. Coordination namespace requires ports, does not import implementations. |
| **III. Crash-Only Supervision** | ✅ PASS | `dev.cljtoc.supervision.download-supervisor` manages peer workers with defined restart policies. |
| **IV. No Hidden State** | ✅ PASS | `Download` record holds all mutable state. State passed explicitly to handlers. No global state. |
| **V. Testability Without I/O** | ✅ PASS | Test doubles in `dev.cljtoc.test-doubles.*` provide mock implementations of all ports. |

**Post-Design Gate Result**: ✅ ALL GATES PASS — Design compliant with constitution

## Project Structure

### Documentation (this feature)

```text
specs/006-download-orchestration/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── api.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/
└── dev/
    └── cljtoc/
        ├── domain/          # Pure domain logic (002, 005)
        │   ├── bencode.clj       ✅ implemented
        │   ├── torrent.clj       ✅ implemented
        │   └── pieces.clj        ✅ implemented
        ├── protocol/        # Protocol implementations (003, 004)
        │   ├── tracker.clj       ✅ implemented
        │   ├── tracker/
        │   │   └── spec.clj     ✅ implemented
        │   ├── peer.clj         ✅ implemented
        │   └── peer_state.clj   ✅ implemented
        ├── ports/           # Effect interfaces (006)
        │   ├── network.clj      # NEW — INetworkPort protocol
        │   ├── disk.clj         # NEW — IDiskPort protocol
        │   └── time.clj         # NEW — ITimePort protocol
        ├── orchestration/   # Coordination (006)
        │   ├── download.clj     # NEW — download coordinator
        │   └── manager.clj      # NEW — download manager
        └── supervision/     # Lifecycle (010)
            └── supervisor.clj   (010 - not started)

test/
└── dev/
    └── cljtoc/
        ├── domain/              ✅ bencode_test, torrent_test, pieces_test
        ├── protocol/            ✅ peer_test, peer_state_test, tracker_test
        ├── orchestration/       # NEW
        │   └── download_test.clj
        ├── integration/         # NEW
        │   └── download_integration_test.clj
        ├── test_utils.clj       ✅ shared test helpers
        └── test_doubles/        # NEW
            ├── network.clj
            ├── disk.clj
            └── time.clj
```

**Structure Decision**: Single Clojure project in repository root. New namespaces added under `dev.cljtoc.ports.*` for effect interfaces and `dev.cljtoc.orchestration.*` for coordination logic. Test doubles in `dev.cljtoc.test-doubles.*`.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations. All effects properly isolated behind ports.

---

# Phase 0: Research

## Research Findings

### Decision: Port Architecture for Effect Isolation

**Decision**: Define three port protocols (INetworkPort, IDiskPort, ITimePort) in `dev.cljtoc.ports.*` namespaces.

**Rationale**: Constitution Principle II requires explicit effect boundaries. Ports enable testability without I/O (Principle V) and allow different implementations (real, mock, simulated).

**Alternatives considered**:
- Direct I/O in coordination functions: Rejected — violates Principle II
- Global effect atoms: Rejected — violates Principle IV (No Hidden State)
- Ambient environment reading: Rejected — violates Principle IV

### Decision: core.async for Coordination

**Decision**: Use core.async channels to coordinate peer connections and message flows.

**Rationale**: Enables concurrent peer handling without thread-per-connection overhead. Channels provide backpressure and clear ownership of message lifetimes.

**Alternatives considered**:
- Thread pools with blocking I/O: Rejected — less composable, harder to test
- Manifold: Rejected — adds dependency; core.async sufficient

### Decision: Download State Machine

**Decision**: Download has explicit states (idle, starting, downloading, paused, completed, failed) with documented transitions.

**Rationale**: Clear state boundaries enable pause/resume (FR-010), progress reporting (FR-009), and error handling (FR-012).

---

# Phase 1: Design

## Data Model

### Download Record

```
Download
├── torrent-metadata : TorrentMetadata  (info-hash, piece-length, piece-hashes, files)
├── piece-state : PieceState            (from feature 005)
├── peers : #{Peer}                     (active peer connections)
├── state : keyword                     (:idle :starting :downloading :paused :completed :failed)
├── output-dir : string                 (where to save files)
├── stats : DownloadStats               (bytes-downloaded, pieces-complete, rate, started-at)
└── error : ErrorInfo (optional)        (when state = :failed)
```

### Peer Record

```
Peer
├── id : string                         (unique identifier)
├── address : string                    (IP:port)
├── bitfield : #{piece-index}           (pieces this peer has)
├── state : PeerState                   (:handshake :connecting :connected :disconnected)
├── downloaded : nat-int                (bytes received from this peer)
└── uploaded : nat-int                  (bytes sent to this peer)
```

### Port Protocols

```
INetworkPort
├── connect-peer(address) → chan of PeerConnection
├── send-message(peer, message) → chan of Response
├── close-peer(peer) → nil
└── peer-loop(peer, message-handler) → supervised-worker

IDiskPort
├── read-piece(piece-index) → (bytes | nil)
├── write-piece(piece-index, bytes) → :ok | {:error reason}
├── read-torrent-file(path) → TorrentMetadata
├── ensure-directory(path) → :ok
└── state-file-path(torrent-id) → string

ITimePort
├── now → instant
├── monotonic → nat-int                 (milliseconds since start)
├── set-timeout(chan, ms, value) → timeout-chan
└── interval-chan(ms, value) → repeating-chan
```

## API Contracts

### Download Management API

```
(start-download torrent-path output-dir)
  → {:ok download-id}
  | {:error :invalid-torrent :message string}

(pause-download download-id)
  → {:ok download-state}
  | {:error :not-running}

(resume-download download-id)
  → {:ok download-state}
  | {:error :already-running}

(progress download-id)
  → {:ok {:percent float
          :pieces-complete nat-int
          :pieces-total nat-int
          :bytes-downloaded nat-int
          :rate-bytes-per-sec nat-int
          :peers-connected nat-int}}

(stop-download download-id)
  → {:ok}
```

### Internal Coordination API

```
(handle-piece-data peer-id piece-index offset data)
  → :ok | {:error :unexpected :message string}

(handle-peer-disconnect peer-id)
  → :ok

(tracker-announce torrent-metadata)
  → {:ok #{Peer}}
  | {:error :no-peers}
```

## Quickstart Guide

### Starting a Download

```clojure
;; In REPL
(require '[dev.cljtoc.orchestration.download :as download])

;; Start download with real ports
(def download-id
  (download/start-download "/path/to/file.torrent" "/output/dir"))

;; Check progress
(download/progress download-id)
;; => {:ok {:percent 45.2
;;          :pieces-complete 230
;;          :pieces-total 512
;;          :bytes-downloaded 120053248
;;          :rate-bytes-per-sec 524288
;;          :peers-connected 12}}

;; Pause
(download/pause-download download-id)

;; Resume
(download/resume-download download-id)

;; Stop
(download/stop-download download-id)
```

### Testing with Test Doubles

```clojure
(require '[dev.cljtoc.test-doubles.network :as mock-net]
         '[dev.cljtoc.test-doubles.disk :as mock-disk]
         '[dev.cljtoc.test-doubles.time :as mock-time])

;; Create mock ports
(def network (mock-net/mock-network))
(def disk (mock-disk/mock-disk))
(def time (mock-time/mock-time {:now #inst "2026-01-01"}))

;; Use in test
(deftest test-download-progress
  (let [manager (download/manager disk network time)
        {:keys [ok error]} (download/start-download manager "test.torrent" "/out")]
    (is (:ok error))
    (is (= 0 (:percent (:ok (download/progress manager (:ok error))))))))
```

---

## Planning Complete

**Status**: ✅ Phase 0 (Research) and Phase 1 (Design) Complete

### Generated Artifacts

| Artifact | Path | Description |
|----------|------|-------------|
| Implementation Plan | `plan.md` | This document — technical context, constitution check, structure |
| Research | `research.md` | Phase 0 — port architecture, core.async coordination, state machine design |
| Data Model | `data-model.md` | Phase 1 — Download, Peer records, port protocols |
| API Contracts | `contracts/api.md` | Phase 1 — Download management API, internal coordination |
| Quickstart | `quickstart.md` | Phase 1 — Developer usage guide with REPL examples |

### Key Design Decisions

| Concern | Decision | Rationale |
|---------|----------|-----------|
| Effect ports | INetworkPort, IDiskPort, ITimePort | Constitution Principle II - explicit effect isolation |
| Coordination | core.async channels | Enables concurrent peer handling with backpressure |
| State management | Explicit Download record | No hidden state - all state passed explicitly |
| Supervision | Per-peer workers with supervisor | Crash-only design - workers crash, supervisors restart |
| Persistence | EDN to disk for pause/resume | Human-readable, Clojure-native format |

### Dependencies

- **Depends On**: 
  - `002-bencode-parser` (torrent file parsing)
  - `003-tracker-protocol` (peer discovery)
  - `004-peer-wire-protocol` (peer communication)
  - `005-piece-management` (piece state, rarest-first, verification)
- **Required By**: `011-cli-interface` (CLI commands use this API)

### Next Step: Task Breakdown

Run `/speckit.tasks` to generate the implementation task list:

```
/speckit.tasks
```

This will create `tasks.md` with actionable work items for implementation.

### Architecture Compliance

✅ **All constitution principles verified**:
- Effect boundaries — ports for network, disk, time
- Explicit supervision — peer workers supervised
- No hidden state — all state in Download record
- Testable without I/O — test doubles for all ports
- Crash-only design — workers crash, supervisors restart
