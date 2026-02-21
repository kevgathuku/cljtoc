# Research: End-to-End Single Torrent Download

**Feature**: 006-download-orchestration  
**Date**: 2026-02-21

## Research Questions

### 1. Effect Port Architecture

**Question**: How to structure effect ports for testability while maintaining constitution compliance?

**Decision**: Define three port protocols:
- `INetworkPort` — peer connections, message send/receive
- `IDiskPort` — piece read/write, torrent file parsing, state persistence
- `ITimePort` — timestamps, timeouts, intervals

**Rationale**: Constitution Principle II requires explicit effect boundaries. Ports enable test doubles without I/O (Principle V). Each port is a Clojure protocol with documented contracts.

**Alternatives considered**:
- Direct I/O imports: Rejected — violates Principle II
- Global effect atoms: Rejected — violates Principle IV (No Hidden State)
- Environment reading: Rejected — violates explicit state passing

### 2. core.async vs Thread Pool

**Question**: Use core.async channels or thread pool for coordinating peer connections?

**Decision**: Use core.async channels

**Rationale**:
- Composable channel operations (pipeline, merge, split)
- Built-in backpressure via buffer sizes
- Clear ownership of message lifetimes via channel ownership
- Efficient: thousands of peer connections per thread

**Alternatives considered**:
- Thread-per-peer: Rejected — overhead too high for 50+ connections
- Java NIO with callbacks: Rejected — harder to test, less idiomatic Clojure
- Manifold: Rejected — adds dependency, core.async sufficient

### 3. Download State Machine

**Question**: How to model download lifecycle for pause/resume and error handling?

**Decision**: Explicit state enumeration with documented transitions

States: `:idle` → `:starting` → `:downloading` → `:completed` | `:paused` | `:failed`

**Rationale**:
- Clear boundaries enable pause/resume (FR-010)
- Explicit error state for FR-012
- Progress reporting tied to :downloading state

**Alternatives considered**:
- Single running/idle boolean: Rejected — no pause state
- Complex state machine: Rejected — over-engineering for single torrent

### 4. Piece Assembly Strategy

**Question**: How to handle multi-file torrents and piece assembly to disk?

**Decision**: 
- For single-file torrents: write pieces directly to output file
- For multi-file torrents: map piece ranges to file offsets, write to appropriate files

**Rationale**: BitTorrent pieces can span file boundaries. Need piece-to-file mapping from torrent metadata (files list with offsets).

**Alternatives considered**:
- Buffer all pieces then write: Rejected — memory intensive for large torrents
- Write each piece as complete: Chosen — matches BitTorrent semantics

### 5. Peer Connection Supervision

**Question**: How to handle peer failures without cascading effects?

**Decision**: OTP-style supervisor per-peer-worker with one-for-one restart

**Rationale**: Constitution Principle III — crash-only design. Peers can disconnect or send corrupt data; supervisor handles restart without affecting other peers.

**Alternatives considered**:
- Retry within peer worker: Rejected — violates crash-only (Principle III)
- Global retry logic: Rejected — hides state (Principle IV)

## Integration Points

### With Feature 002 (Bencode Parser)

- Uses `bencode/parse-torrent` to get TorrentMetadata
- Reuses `bencode/sha1-hash` via domain layer for piece verification

### With Feature 003 (Tracker Protocol)

- Calls `tracker/announce` to get peer list
- Handles tracker errors (no peers, tracker down)

### With Feature 004 (Peer Wire Protocol)

- Uses `peer/connect` to establish connections
- Uses `peer/send-message` and `peer/receive-message` for piece requests
- Handles peer state machine (handshake, choking, interested)

### With Feature 005 (Piece Management)

- Uses `pieces/initial-piece-state` to create piece state
- Uses `pieces/select-piece` for rarest-first selection
- Uses `pieces/verify-piece` for SHA-1 verification
- Uses `pieces/piece-blocks` to decompose piece into requests

## Summary

All research questions resolved. Architecture decision: Use injectable port protocols for all I/O, core.async for coordination, explicit download state machine, and OTP-style supervision for peer workers.
