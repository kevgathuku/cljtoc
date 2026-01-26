# Feature Specification: Clojure BitTorrent Client

**Feature Branch**: `001-clojure-bittorrent-client`
**Created**: 2026-01-26
**Status**: Draft
**Input**: User description: "Build a BitTorrent client in Clojure that embraces crash-only design, uses core.async for concurrency, applies OTP-style supervision semantics, maintains hard boundaries between Domain (pure), Protocol logic, Effects (network, disk, time), and Supervision/lifecycle. Must be testable without I/O and avoid hidden global state."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Download a Single Torrent File (Priority: P1)

A user wants to download a file from a torrent. They provide a .torrent file or magnet link, specify a download location, and the client downloads the complete file by connecting to peers, requesting pieces, verifying integrity, and assembling the final file.

**Why this priority**: This is the core value proposition of a BitTorrent client - the ability to download content from a swarm. Without this capability, the client has no utility.

**Independent Test**: Can be fully tested by providing a .torrent file, starting a download, and verifying the completed file matches the expected hash. Delivers the fundamental value of file acquisition.

**Acceptance Scenarios**:

1. **Given** a valid .torrent file and a specified download directory, **When** the user starts the download, **Then** the client connects to the tracker, discovers peers, and begins downloading pieces
2. **Given** an active download with available peers, **When** pieces are received, **Then** each piece is verified against its hash before being accepted
3. **Given** all pieces have been downloaded and verified, **When** the download completes, **Then** the final file matches the expected info hash and is available at the specified location
4. **Given** a download in progress, **When** the user pauses the download, **Then** all peer connections are gracefully closed and progress is persisted
5. **Given** a paused download with progress saved, **When** the user resumes, **Then** only missing pieces are requested

---

### User Story 2 - Seed Files to the Swarm (Priority: P2)

A user who has completed a download (or has original content) wants to contribute back to the swarm by seeding. The client accepts incoming connections from peers and uploads pieces upon request.

**Why this priority**: Seeding is essential for the health of the BitTorrent ecosystem and completes the bidirectional nature of the protocol. However, it depends on having content to share, making it secondary to downloading.

**Independent Test**: Can be tested by having the client seed a known file and verifying that a separate peer can successfully download pieces from it.

**Acceptance Scenarios**:

1. **Given** a completed download, **When** seeding is enabled, **Then** the client announces to the tracker and accepts incoming peer connections
2. **Given** an incoming peer connection with a valid handshake, **When** the peer requests a piece, **Then** the client sends the requested piece data
3. **Given** multiple peers requesting different pieces, **When** handling requests, **Then** requests are served fairly without starving any single peer

---

### User Story 3 - Monitor Download Progress and Statistics (Priority: P3)

A user wants visibility into the current state of their downloads including progress percentage, download/upload speeds, number of connected peers, and estimated time remaining.

**Why this priority**: Monitoring provides transparency and user confidence but is not essential for the core download/upload functionality.

**Independent Test**: Can be tested by starting a download and verifying that statistics are reported accurately and update in real-time.

**Acceptance Scenarios**:

1. **Given** an active download, **When** the user queries status, **Then** they see: progress percentage, downloaded/total bytes, download speed, upload speed, connected peers count
2. **Given** changing network conditions, **When** speeds fluctuate, **Then** the displayed statistics reflect current conditions within 5 seconds
3. **Given** a completed download, **When** the user queries status, **Then** they see 100% progress and cumulative statistics

---

### User Story 4 - Handle Network Failures Gracefully (Priority: P4)

When network issues occur (peer disconnects, tracker unavailable, timeouts), the client recovers automatically without user intervention and without corrupting download state.

**Why this priority**: Reliability is critical for user trust but builds upon the core download functionality.

**Independent Test**: Can be tested by simulating network failures during download and verifying automatic recovery and data integrity.

**Acceptance Scenarios**:

1. **Given** a peer disconnects unexpectedly, **When** the client detects the failure, **Then** in-flight piece requests from that peer are re-requested from other available peers
2. **Given** the tracker becomes unavailable, **When** the client attempts to announce, **Then** the client continues downloading from known peers and retries tracker contact according to configured intervals
3. **Given** a complete network outage, **When** connectivity is restored, **Then** the client automatically resumes peer connections and continues downloading
4. **Given** any failure scenario, **When** recovery occurs, **Then** no downloaded data is lost and no duplicate pieces are written

---

### User Story 5 - Manage Multiple Concurrent Torrents (Priority: P5)

A user wants to download and seed multiple torrents simultaneously, with the client managing resources across all active transfers.

**Why this priority**: Multi-torrent support increases utility but adds complexity beyond the single-torrent core.

**Independent Test**: Can be tested by starting multiple torrents and verifying all make progress concurrently and resources are shared appropriately.

**Acceptance Scenarios**:

1. **Given** multiple torrents are active, **When** the user queries status, **Then** each torrent shows independent progress
2. **Given** limited bandwidth, **When** multiple torrents compete, **Then** bandwidth is distributed according to configured priorities or fairly if no priority set
3. **Given** one torrent fails catastrophically, **When** the failure is handled, **Then** other torrents continue unaffected

---

### Edge Cases

- What happens when a torrent file is malformed or missing required fields?
- How does the system handle pieces that repeatedly fail hash verification (potentially malicious peers)?
- What happens when disk space runs out mid-download?
- How does the client behave when all peers disconnect and no tracker is reachable?
- What happens when the client is terminated forcefully (crash, kill signal) mid-write?
- How are extremely large torrents (>1TB) handled with respect to memory?
- What happens when two torrents share the same file (cross-seeding scenario)?

## Requirements *(mandatory)*

### Functional Requirements

#### Core Protocol

- **FR-001**: System MUST parse and validate .torrent files (bencode format) extracting info hash, piece hashes, file metadata, and tracker URLs
- **FR-002**: System MUST implement the BitTorrent peer wire protocol including handshake, choke/unchoke, interested/not-interested, have, bitfield, request, piece, and cancel messages
- **FR-003**: System MUST verify each downloaded piece against its SHA-1 hash before accepting it
- **FR-004**: System MUST announce to HTTP and UDP trackers to discover peers and report progress
- **FR-005**: System MUST maintain persistent state of download progress to enable resume after restart

#### Architecture (Crash-Only Design)

- **FR-006**: System MUST structure all domain logic (piece selection, verification, state transitions) as pure, deterministic functions with no side effects
- **FR-007**: System MUST isolate all side effects (network I/O, disk I/O, timers, randomness) behind explicit interfaces that can be replaced with test doubles
- **FR-008**: System MUST implement explicit supervision where worker failures are expected and handled by supervisors via restart policies
- **FR-009**: System MUST ensure no worker performs its own retries unless explicitly designed to do so; retry logic belongs to supervisors
- **FR-010**: System MUST ensure all concurrent processes have explicit owners/supervisors responsible for their lifecycle
- **FR-011**: System MUST avoid hidden global state; all state must be explicitly passed or managed through supervised components

#### Peer Management

- **FR-012**: System MUST maintain connections to multiple peers simultaneously (configurable limit, default: 50 per torrent)
- **FR-013**: System MUST implement choking/unchoking algorithm to optimize upload to reciprocating peers
- **FR-014**: System MUST track piece availability across peers to enable rarest-first selection
- **FR-015**: System MUST handle peer disconnections by reassigning pending piece requests to other peers

#### Disk Management

- **FR-016**: System MUST write completed pieces to disk atomically to prevent corruption on crashes
- **FR-017**: System MUST pre-allocate disk space for the complete download or handle allocation incrementally with graceful disk-full handling
- **FR-018**: System MUST support downloads to configurable directory locations

#### Testability

- **FR-019**: System MUST be testable without actual network I/O by providing injectable network ports
- **FR-020**: System MUST be testable without actual disk I/O by providing injectable storage ports
- **FR-021**: System MUST support deterministic testing by providing injectable time and randomness sources

### Key Entities

- **Torrent**: Represents a torrent session including info hash, file metadata, piece hashes, tracker URLs, and current download state
- **Piece**: A fixed-size chunk of the torrent with an index, expected hash, download status, and data (when downloaded)
- **Peer**: A remote BitTorrent client with connection state, pieces it has (bitfield), choke/interest state, and transfer statistics
- **Block**: A sub-piece unit of transfer (typically 16KB) used for requesting data from peers
- **Tracker**: An announce endpoint (HTTP or UDP) that provides peer discovery for a torrent
- **Supervisor**: A process responsible for managing the lifecycle of worker processes, implementing restart strategies on failure

## Assumptions

- The client targets the BitTorrent v1 protocol (BEP 3) as the baseline; extensions (DHT, PEX, magnet links) can be added later
- Single-file and multi-file torrents are both supported
- The client operates as a command-line application initially; GUI can be added as a separate layer
- Standard piece selection strategy (rarest-first) is used unless endgame mode is active
- Default block size is 16KB as per BitTorrent convention
- Tracker announce intervals follow tracker-specified values with reasonable defaults (30 minutes)
- The client will gracefully degrade when trackers are unavailable (using cached peer lists)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can download a 1GB torrent from a healthy swarm to completion with full verification
- **SC-002**: All domain logic achieves 90% unit test coverage without requiring actual I/O
- **SC-003**: The client recovers from any single component failure (peer, disk writer, tracker) within 30 seconds without data loss
- **SC-004**: Forceful termination at any point results in no corruption; restart resumes from last verified state
- **SC-005**: The client sustains 50 concurrent peer connections per torrent while maintaining responsive user feedback
- **SC-006**: Integration tests run deterministically with simulated time, network, and disk without flaky failures
- **SC-007**: Download speed reaches at least 80% of available bandwidth when sufficient peers are available
- **SC-008**: Memory usage stays below 100MB for single-torrent downloads regardless of torrent size
