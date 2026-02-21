# Feature Specification: End-to-End Single Torrent Download

**Feature Branch**: `006-download-orchestration`  
**Created**: 2026-02-21  
**Status**: Draft  
**Input**: User description: "End-to-end single torrent download"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Download Complete Torrent (Priority: P1)

A user provides a .torrent file and the client downloads all pieces, verifies integrity, and saves the resulting files to disk.

**Why this priority**: This is the core value proposition - a functional BitTorrent client must be able to download a complete file.

**Independent Test**: Can be tested by providing a small .torrent file and verifying all pieces download and hash correctly.

**Acceptance Scenarios**:

1. **Given** a valid .torrent file, **When** the download is started, **Then** the client connects to tracker and begins downloading pieces
2. **Given** sufficient peers with pieces, **When** all pieces download, **Then** each piece is verified against its SHA-1 hash and the complete file is assembled
3. **Given** a completed download, **When** the user checks the output, **Then** the downloaded content matches the original

---

### User Story 2 - Track and Display Download Progress (Priority: P1)

A user can observe download progress showing which pieces are downloaded, verify status, download speed, and ETA.

**Why this priority**: Users need visibility into long-running downloads to confirm the client is working and estimate completion time.

**Independent Test**: Can be tested by starting a download and querying the progress state at intervals.

**Acceptance Scenarios**:

1. **Given** an active download, **When** progress is queried, **Then** the system returns percentage complete, pieces verified count, and current download rate
2. **Given** an active download with multiple peers, **When** progress is queried, **Then** the system reports aggregate download speed across all connections

---

### User Story 3 - Handle Download Failures Gracefully (Priority: P2)

A download continues even when individual peers disconnect or provide corrupt data.

**Why this priority**: Real-world downloads face peer churn and network issues; the client must recover automatically without user intervention.

**Independent Test**: Can be tested by disconnecting peers mid-download and verifying the download continues from other peers.

**Acceptance Scenarios**:

1. **Given** a peer providing corrupt piece data, **When** verification fails, **Then** the piece is re-queued for download from a different peer
2. **Given** all peers disconnect, **When** network recovers, **Then** the client re-establishes connections and continues downloading
3. **Given** a piece fails verification after N attempts, **Then** the download reports an error and allows manual retry

---

### User Story 4 - Pause and Resume Download (Priority: P2)

A user can pause an active download and resume it later without losing progress.

**Why this priority**: Users may need to free bandwidth for other activities or restart their machine.

**Independent Test**: Can be tested by pausing mid-download, checking state, resuming, and verifying no data is lost.

**Acceptance Scenarios**:

1. **Given** an active download, **When** paused, **Then** all peer connections are closed and piece state is persisted
2. **Given** a paused download, **When** resumed, **Then** the client reconnects to tracker/peers and continues from where it left off

---

### Edge Cases

- What happens when no peers are available from the tracker?
- How does the system handle a .torrent file with no valid pieces?
- What happens when disk is full during download?
- How is downloaded data protected against corruption between sessions?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST parse .torrent files using the bencode parser (feature 002)
- **FR-002**: System MUST contact trackers to obtain peer addresses using the tracker protocol (feature 003)
- **FR-003**: System MUST connect to peers and exchange messages using the peer wire protocol (feature 004)
- **FR-004**: System MUST select pieces using rarest-first strategy from piece management (feature 005)
- **FR-005**: System MUST verify each piece against its SHA-1 hash from the torrent metadata
- **FR-006**: System MUST write verified pieces to disk in the correct file layout
- **FR-007**: System MUST maintain piece state (needed, in-flight, verified) across the download
- **FR-008**: System MUST re-download pieces when verification fails
- **FR-009**: System MUST expose download progress including percentage, speed, and peer count
- **FR-010**: System MUST support pausing and resuming downloads with state persistence
- **FR-011**: System MUST handle peer disconnection and connect to alternative peers
- **FR-012**: System MUST report errors when download cannot complete (no peers, corrupt data, disk issues)

### Key Entities

- **Torrent**: Represents a download job with metadata (info-hash, piece length, piece hashes, files) and runtime state (piece state, peers, progress)
- **PeerConnection**: Active connection to a peer with state (choked, interested, bitfield, pending requests)
- **PieceState**: Tracks which pieces are needed, in-flight, or verified (from feature 005)
- **DownloadProgress**: Snapshot of current progress (pieces complete, bytes downloaded, rate, peer count)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can download a complete single-file torrent from start to finish with verified integrity
- **SC-002**: Download progress is queryable and shows accurate percentage within 1 second of any piece completion
- **SC-003**: Corrupt pieces are automatically re-downloaded without user intervention
- **SC-004**: Downloads can be paused and resumed without data loss
- **SC-005**: The system achieves at least 80% of available bandwidth utilization when connected to 10+ peers with pieces
