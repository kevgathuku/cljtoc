# Feature Specification: Piece Management

**Feature Branch**: `005-piece-management`
**Created**: 2026-02-21
**Status**: Draft
**Parent Architecture**: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md)
**Depends On**: [002-bencode-parser](../002-bencode-parser/spec.md)
**Required By**: [006-download-orchestration](../006-download-orchestration/spec.md)
**Input**: User description: "Pure domain logic for piece management"

## Overview

This feature provides the pure domain logic that sits at the heart of the BitTorrent download engine: tracking which pieces have been downloaded, selecting which piece to fetch next, decomposing pieces into network-sized blocks, and verifying that received data is uncorrupted. All functions are deterministic and free of side effects — no network, disk, or time operations occur here.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Track Piece Download Status (Priority: P1)

The download engine maintains a live picture of every piece in a torrent: which ones still need to be downloaded, which are currently being fetched from a peer, and which have been verified as complete. The engine queries and updates this picture after every significant event (block received, peer disconnected, piece verified) without mutating prior snapshots.

**Why this priority**: Every other user story depends on knowing the current state of piece progress. Without accurate state tracking the engine cannot decide what to request, detect completion, or recover from failures.

**Independent Test**: Create a piece-state for a torrent of N pieces; apply a sequence of status transitions (mark pieces in-flight, mark pieces verified, requeue a failed piece); verify that each new state is correct and that prior states are unchanged.

**Acceptance Scenarios**:

1. **Given** torrent metadata with N pieces, **When** piece-state is initialised, **Then** all N pieces are in "needed" status and none are in-flight or complete
2. **Given** a piece in "needed" status, **When** it is marked in-flight, **Then** the new state shows it as in-flight and the count of needed pieces decreases by one
3. **Given** a piece in "in-flight" status, **When** it is marked complete, **Then** the new state shows it as complete and the count of in-flight pieces decreases by one
4. **Given** a piece in "in-flight" status that fails verification, **When** it is re-queued, **Then** the new state returns it to "needed" status
5. **Given** a prior piece-state snapshot, **When** a later transition is applied, **Then** the prior snapshot is unmodified (pure, no mutation)
6. **Given** all pieces are complete, **When** the engine queries completion, **Then** the state reports the torrent as fully downloaded

---

### User Story 2 - Select Next Piece to Download (Priority: P2)

Given the current download state and the set of pieces available from one or more connected peers (expressed as bitfields), the engine computes which piece to request next. The selection uses a rarest-first strategy: prioritise pieces that fewer connected peers have, to maximise the variety of data in the swarm and improve long-term download health.

**Why this priority**: Piece selection directly determines download efficiency and swarm contribution. A correct rarest-first strategy is required for the engine to achieve good throughput and play well with other BitTorrent clients.

**Independent Test**: Provide a piece-state with several needed pieces and a collection of peer bitfields; verify the selected piece is one the requesting peer has and is among the rarest available. Can be tested entirely with synthetic state data.

**Acceptance Scenarios**:

1. **Given** a piece-state with needed pieces and a peer that has some of them, **When** a piece is selected for that peer, **Then** the selected piece is one the peer actually has
2. **Given** multiple needed pieces where one is held by fewer peers than others, **When** a piece is selected, **Then** the rarest piece is preferred
3. **Given** a peer whose bitfield contains no needed pieces, **When** a piece is selected, **Then** no piece is returned (nothing to request from that peer)
4. **Given** all needed pieces are equally rare, **When** a piece is selected, **Then** any needed piece held by the peer is a valid selection (tie-breaking is acceptable)
5. **Given** a piece that is already in-flight, **When** pieces are selected for a different peer, **Then** in-flight pieces are not selected again (unless in endgame mode)
6. **Given** no peers are connected, **When** a piece is selected, **Then** no piece is returned

---

### User Story 3 - Decompose Piece into Blocks (Priority: P3)

Once the engine knows which piece to download, it must produce the exact set of block requests to send to a peer via the peer wire protocol. Each block covers a contiguous range of bytes within the piece, is at most 16 KiB in size, and together the blocks cover the entire piece without overlap or gap.

**Why this priority**: The peer wire protocol operates at block granularity, not piece granularity. Without block decomposition the engine cannot form valid requests. This is the bridge between piece selection (US2) and the wire protocol (feature 004).

**Independent Test**: Provide a piece index, the torrent's standard piece size, and the total torrent size; verify the returned block list covers exactly the right byte range with no gaps, no overlaps, and no block exceeding 16 KiB.

**Acceptance Scenarios**:

1. **Given** a piece whose size is an exact multiple of 16 KiB, **When** decomposed into blocks, **Then** each block is exactly 16 KiB and the last block is also 16 KiB
2. **Given** a piece whose size is not a multiple of 16 KiB, **When** decomposed into blocks, **Then** all blocks except the last are 16 KiB and the final block contains the remaining bytes
3. **Given** the last piece of a torrent (which may be shorter than the standard piece size), **When** decomposed into blocks, **Then** the blocks cover exactly the shorter piece size
4. **Given** any piece, **When** all blocks are assembled in order, **Then** their combined byte count equals the exact piece size
5. **Given** a piece of exactly 1 byte, **When** decomposed, **Then** exactly one block of 1 byte is returned
6. **Given** an invalid piece index (outside the torrent), **When** decomposed, **Then** an error is returned

---

### User Story 4 - Verify Piece Integrity (Priority: P4)

After the engine has collected all blocks for a piece and assembled them into a complete byte sequence, it verifies that the data matches the expected SHA-1 cryptographic hash recorded in the .torrent metadata. A piece that passes verification is safe to write to disk; one that fails must be discarded and re-downloaded.

**Why this priority**: Without integrity verification, corrupted or malicious data could be written to disk silently. Verification is a mandatory safety gate between network reception and persistent storage.

**Independent Test**: Provide assembled piece bytes and the expected SHA-1 hash (taken from torrent metadata); verify that correct data passes and intentionally corrupted data fails, independently of any network or disk operation.

**Acceptance Scenarios**:

1. **Given** assembled piece bytes that match the expected SHA-1 hash, **When** verified, **Then** the result is a pass
2. **Given** assembled piece bytes where even a single byte differs from expected, **When** verified, **Then** the result is a failure
3. **Given** assembled piece bytes of incorrect length, **When** verified, **Then** the result is a failure
4. **Given** an empty byte sequence, **When** verified against any hash, **Then** the result is a failure
5. **Given** a verification pass result, **When** the result is inspected, **Then** the piece index is identifiable from the result

---

### User Story 5 - Detect and Enter Endgame Mode (Priority: P5)

When only a small number of pieces remain to be downloaded (all others are complete or verified), the engine switches to endgame mode: it requests the remaining in-flight blocks from every peer that has them simultaneously, rather than one peer at a time. This eliminates the "long tail" problem where the last few pieces slow down an otherwise complete download.

**Why this priority**: Endgame mode is a standard BitTorrent optimisation that prevents downloads from stalling near completion. It is low complexity but high impact on perceived download completion time.

**Independent Test**: Provide a piece-state with a small number of remaining pieces and a threshold; verify the function correctly reports whether endgame conditions are met, independently of any network operations.

**Acceptance Scenarios**:

1. **Given** a piece-state where fewer than the endgame threshold of pieces remain (needed + in-flight combined), **When** endgame is checked, **Then** the function returns true
2. **Given** a piece-state where more pieces remain than the threshold, **When** endgame is checked, **Then** the function returns false
3. **Given** endgame mode is active, **When** pieces are selected, **Then** in-flight pieces may also be selected (to request from additional peers)
4. **Given** endgame mode is active and a piece completes, **When** other in-flight requests for the same piece exist, **Then** the now-redundant requests are identifiable so the orchestrator can cancel them

---

### Edge Cases

- What happens when the torrent has exactly one piece?
- What happens when two peers have identical bitfields (tie-breaking in rarest-first)?
- What happens if all connected peers lack a specific needed piece (piece unavailable)?
- What happens if the final piece's size is 0 bytes (degenerate torrent)?
- What happens if a piece-state is initialised with 0 pieces?
- What happens if block decomposition is called with a piece length larger than the total torrent size?
- What happens if the same piece transitions through the same state twice (idempotency)?

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST represent the download status of every piece in a torrent as one of: needed, in-flight, or verified
- **FR-002**: The system MUST support pure state transitions between piece statuses: needed → in-flight, in-flight → verified (pass), in-flight → needed (fail/re-queue)
- **FR-003**: The system MUST expose the count of pieces in each status (needed, in-flight, verified) at any point
- **FR-004**: The system MUST select a next piece to download for a given peer using rarest-first strategy, considering only pieces the peer has and that are in "needed" status
- **FR-005**: The system MUST decompose any piece into a complete, non-overlapping, gapless sequence of blocks each at most 16 KiB, correctly handling the shorter last piece of a torrent
- **FR-006**: The system MUST verify assembled piece bytes against an expected SHA-1 hash and return a typed pass/fail result
- **FR-007**: The system MUST detect when endgame conditions are met based on a configurable remaining-piece threshold
- **FR-008**: In endgame mode, the system MUST allow already-in-flight pieces to be selected for additional peers
- **FR-009**: All functions MUST be pure: identical inputs always produce identical outputs with no side effects
- **FR-010**: All state transitions MUST return new state without mutating the original (immutable transitions)
- **FR-011**: The system MUST return a typed error (not throw an exception) for invalid inputs such as out-of-range piece indices

### Key Entities

- **PieceState**: Immutable snapshot of all piece statuses for a torrent; includes total piece count, and the set of pieces in each status (needed, in-flight, verified)
- **Block**: A specific byte range within a piece to be fetched via wire protocol; identified by piece index, byte offset within piece, and byte length (≤ 16 KiB)
- **PieceSelection**: The result of the piece selection function; includes the chosen piece index and can be absent (no selectable piece for this peer)
- **VerificationResult**: The outcome of comparing assembled piece bytes to the expected hash; typed as pass (with piece index) or fail (with piece index)

### Assumptions

- Standard block size is 16 KiB (16,384 bytes) as per BitTorrent convention; this is not configurable per torrent
- Rarest-first tie-breaking (when two pieces are equally rare) may use any deterministic strategy (e.g., lowest piece index); the specific strategy is an implementation choice
- The endgame threshold defaults to 20 remaining pieces; this must be configurable by the caller
- Piece indices are zero-based and contiguous from 0 to N−1
- SHA-1 hashes used for verification are 20 bytes each, sourced from the parsed .torrent metadata (feature 002)
- This feature does not assemble blocks into pieces (that is an orchestration concern); it only verifies already-assembled bytes
- Multi-file torrents are transparent at this layer: piece boundaries do not align with file boundaries, but piece sizes and hashes are uniform

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All piece selection, block decomposition, state transition, and verification operations execute in under 1 millisecond for torrents with up to 100,000 pieces
- **SC-002**: Rarest-first selection demonstrably chooses rarer pieces over more common ones in a controlled test scenario with known peer bitfields
- **SC-003**: Block decomposition produces byte-perfect coverage: for every piece in a 1 GB torrent, the sum of all block lengths equals the exact piece size
- **SC-004**: Piece verification correctly distinguishes passing from failing pieces with 100% accuracy across all test cases including single-byte corruptions
- **SC-005**: At least 90% of all domain logic is exercised by tests that require no network, disk, or timer access
- **SC-006**: State transitions are referentially transparent: applying the same sequence of transitions to the same initial state always produces the same final state, verified across 1,000 generated inputs

---

## Dependencies & Scope

### In Scope

- Piece status tracking (needed / in-flight / verified) and transitions
- Rarest-first piece selection against peer bitfields
- Block decomposition for any piece index in any torrent
- SHA-1 integrity verification of assembled piece bytes
- Endgame mode detection and in-flight piece selection

### Out of Scope

- Assembling received blocks into complete piece bytes (orchestration layer, feature 006)
- Writing verified pieces to disk (disk port, feature 006)
- Peer connection management or piece request sending (coordination layer)
- Choking/unchoking algorithm (feature 007)
- Piece prioritisation beyond rarest-first (e.g., sequential, random) — can be added as future enhancement
