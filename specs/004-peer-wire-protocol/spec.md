# Feature Specification: Peer Wire Protocol

**Feature Branch**: `004-peer-wire-protocol`
**Created**: 2026-02-17
**Status**: Draft
**Parent Architecture**: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md)
**Depends On**: [002-bencode-parser](../002-bencode-parser/spec.md), [003-tracker-protocol](../003-tracker-protocol/spec.md)
**Input**: User description: "BitTorrent peer message protocol implementation"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Parse Peer Handshake (Priority: P1)

A developer provides raw bytes from a peer TCP connection and receives a structured handshake record confirming the peer speaks the BitTorrent protocol and shares the expected torrent.

**Why this priority**: The handshake is the mandatory first message on every peer connection. Without it no other messages can be exchanged. All subsequent user stories depend on a successful handshake.

**Independent Test**: Provide 68-byte handshake bytes (pstrlen + "BitTorrent protocol" + 8 reserved + 20-byte info_hash + 20-byte peer_id); verify the parsed record contains the correct info_hash and peer_id. Delivers the ability to authenticate peer connections independently.

**Acceptance Scenarios**:

1. **Given** a valid 68-byte handshake from a peer, **When** parsed, **Then** returns a record with protocol string, reserved bytes, info_hash, and peer_id
2. **Given** a handshake with a wrong protocol string (not "BitTorrent protocol"), **When** parsed, **Then** returns an error indicating an unsupported protocol
3. **Given** a handshake with a mismatched info_hash, **When** compared to the expected torrent's info_hash, **Then** the mismatch is detectable from the parsed record
4. **Given** bytes shorter than 68 bytes, **When** parsed, **Then** returns an error indicating an incomplete handshake
5. **Given** a handshake with non-zero reserved bytes, **When** parsed, **Then** the reserved bytes are preserved for extension detection

---

### User Story 2 - Parse Peer Messages (Priority: P2)

A developer provides raw bytes received from an established peer connection and receives structured message records (choke, unchoke, interested, not-interested, have, bitfield, request, piece, cancel).

**Why this priority**: Parsing incoming peer messages is necessary to understand peer state and receive piece data. This is the core of the download protocol and enables all reactive behaviour.

**Independent Test**: Provide length-prefixed message bytes for each message type; verify the parsed record contains the correct message type and payload fields. Each message type can be tested individually.

**Acceptance Scenarios**:

1. **Given** a 4-byte keep-alive message (length = 0), **When** parsed, **Then** returns a keep-alive record
2. **Given** a choke message (id = 0), **When** parsed, **Then** returns a choke record
3. **Given** an unchoke message (id = 1), **When** parsed, **Then** returns an unchoke record
4. **Given** an interested message (id = 2), **When** parsed, **Then** returns an interested record
5. **Given** a not-interested message (id = 3), **When** parsed, **Then** returns a not-interested record
6. **Given** a have message (id = 4) with a piece index, **When** parsed, **Then** returns a have record with the correct piece index
7. **Given** a bitfield message (id = 5) with N bytes, **When** parsed, **Then** returns a bitfield record with accessible bit positions
8. **Given** a request message (id = 6) with index, begin, and length, **When** parsed, **Then** returns a request record with all three fields
9. **Given** a piece message (id = 7) with index, begin, and block data, **When** parsed, **Then** returns a piece record with correct index, offset, and data bytes
10. **Given** a cancel message (id = 8) with index, begin, and length, **When** parsed, **Then** returns a cancel record with all three fields
11. **Given** bytes with an unknown message id, **When** parsed, **Then** returns an error indicating an unrecognised message type
12. **Given** bytes where the declared length exceeds the available bytes, **When** parsed, **Then** returns an error indicating an incomplete message

---

### User Story 3 - Build Peer Handshake (Priority: P3)

A developer provides the info_hash and local peer_id and receives a correctly formatted 68-byte handshake byte sequence ready to send to a remote peer.

**Why this priority**: To initiate connections the client must send a handshake first. Building well-formed handshakes is required before any outgoing peer connection can proceed.

**Independent Test**: Provide a 20-byte info_hash and 20-byte peer_id; verify the returned byte array is exactly 68 bytes, begins with 0x13 and the ASCII string "BitTorrent protocol", and contains the info_hash and peer_id at the correct offsets.

**Acceptance Scenarios**:

1. **Given** a valid info_hash and peer_id, **When** building a handshake, **Then** returns exactly 68 bytes with the correct layout
2. **Given** the reserved extension bytes are specified, **When** building a handshake, **Then** the reserved bytes appear at offset 20–27
3. **Given** an info_hash that is not exactly 20 bytes, **When** building a handshake, **Then** returns an input validation error
4. **Given** a peer_id that is not exactly 20 bytes, **When** building a handshake, **Then** returns an input validation error
5. **Given** a valid handshake is built and then parsed, **When** round-tripped, **Then** the parsed fields match the original inputs

---

### User Story 4 - Build Peer Messages (Priority: P4)

A developer provides structured message data (choke, request, cancel, etc.) and receives correctly length-prefixed byte sequences ready to send to a remote peer.

**Why this priority**: Sending messages to peers is required to coordinate piece exchange. This completes the bidirectional message protocol and enables the client to express interest, make requests, and send piece data.

**Independent Test**: For each message type, provide the appropriate fields and verify the returned bytes parse back to the same message. Delivers the full encode side of the peer protocol.

**Acceptance Scenarios**:

1. **Given** no payload fields, **When** building a keep-alive, **Then** returns exactly 4 zero bytes
2. **Given** a message type with no payload (choke/unchoke/interested/not-interested), **When** built, **Then** returns a 5-byte message (4-byte length=1 + 1-byte id)
3. **Given** a piece index for a have message, **When** built, **Then** returns a 9-byte message with the correct index at offset 5
4. **Given** a bitfield byte array, **When** building a bitfield message, **Then** the message length and payload are correct
5. **Given** index, begin, and length for a request or cancel message, **When** built, **Then** returns a 17-byte message with the three fields at the correct offsets
6. **Given** index, begin, and block data for a piece message, **When** built, **Then** returns a correctly framed message with data starting at offset 13
7. **Given** invalid inputs (negative index, block length exceeding 16 KiB limit), **When** building any message, **Then** returns an input validation error

---

### User Story 5 - Peer Connection State Machine (Priority: P5)

A developer applies incoming peer messages as events to a peer connection state record and receives an updated state without side effects.

**Why this priority**: Correctly tracking choked/unchoked and interested/not-interested state is required to know when to send or honour requests. Pure state transitions make the protocol logic testable and independent of I/O.

**Independent Test**: Starting from an initial state (choked, not-interested), apply a sequence of messages and verify the resulting state reflects each transition correctly. No network required.

**Acceptance Scenarios**:

1. **Given** an initial peer state (choked, not-interested), **When** an unchoke message is applied, **Then** the new state has choked = false
2. **Given** an unchoked peer state, **When** a choke message is applied, **Then** the new state has choked = true
3. **Given** a peer state, **When** an interested message is applied, **Then** the new state has peer-interested = true
4. **Given** a peer state, **When** a not-interested message is applied, **Then** the new state has peer-interested = false
5. **Given** a peer state, **When** a have message is applied, **Then** the piece index is marked as available in the peer's bitfield
6. **Given** a peer state with an empty bitfield, **When** a bitfield message is applied, **Then** the peer's available pieces are updated to match the bitfield

---

### Edge Cases

- What happens when a piece message payload is 0 bytes (empty block data)?
- How does the parser handle a bitfield longer than expected for the torrent's piece count?
- What happens when a request specifies a block length larger than 16 KiB?
- How does the state machine handle duplicate have messages for the same piece index?
- What happens when a keep-alive arrives while the connection is choked?
- How is an incomplete message handled when only part of the length prefix has arrived?

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST parse all nine BEP 3 peer wire message types: keep-alive, choke, unchoke, interested, not-interested, have, bitfield, request, piece, cancel
- **FR-002**: The system MUST parse the 68-byte BEP 3 handshake and extract protocol string, reserved bytes, info_hash, and peer_id
- **FR-003**: The system MUST build valid 68-byte handshake byte sequences given an info_hash and peer_id
- **FR-004**: The system MUST build length-prefixed byte sequences for all nine message types
- **FR-005**: All parsing functions MUST return `{:ok value}` on success or `{:error keyword, :message string}` on failure — never throw exceptions for expected failures
- **FR-006**: All building functions MUST validate inputs before encoding and return a structured error for invalid data
- **FR-007**: The system MUST represent peer connection state (choked, unchoked, interested, not-interested, available pieces) as an immutable data structure
- **FR-008**: The system MUST provide pure state-transition functions that apply a parsed message event to a peer state and return a new state
- **FR-009**: Parsing and building MUST be inverse operations: parsing a built message returns the original fields for all well-formed inputs
- **FR-010**: Block requests and piece messages MUST be validated so lengths do not exceed the 16 KiB standard block size
- **FR-011**: All public functions MUST have machine-checkable specifications covering argument shapes, return shapes, and key invariants
- **FR-012**: The bitfield representation MUST support querying whether a specific piece index is available
- **FR-013**: Network I/O MUST NOT appear in this feature — byte arrays are the boundary; reading from TCP sockets belongs to the coordination layer

### Key Entities

- **PeerHandshake**: The opening message of a connection. Contains protocol string (fixed "BitTorrent protocol"), 8 reserved bytes (for future extensions), 20-byte info_hash, and 20-byte peer_id.
- **PeerMessage**: A discriminated union of all BEP 3 message types, tagged by message type keyword. Carries payload fields appropriate to the type (e.g., piece index for `have`, block data for `piece`).
- **PeerState**: Immutable record of a peer connection's current state. Tracks: am-choking, am-interested, peer-choking, peer-interested, and the peer's advertised piece availability.
- **BlockRequest**: Identifies a specific data block by piece index, byte offset within the piece, and byte length. Shared structure used in `request`, `piece`, and `cancel` messages.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All nine BEP 3 message types parse correctly from their binary representation with 100% accuracy on well-formed inputs
- **SC-002**: Round-trip property holds for all message types: building then parsing a message returns the original fields for 100% of valid inputs, verified by generative tests
- **SC-003**: All parsing functions handle malformed inputs (wrong length, unknown message id, truncated payload) without crashing, returning descriptive error maps in 100% of invalid-input cases
- **SC-004**: Peer state transitions are deterministic — applying the same sequence of messages to the same initial state always produces the same result
- **SC-005**: 90%+ of test coverage is achieved without any network I/O — all tests use in-memory byte arrays
- **SC-006**: Handshake parsing correctly rejects connections with mismatched info_hash or unsupported protocol strings in 100% of such cases
- **SC-007**: Block-level request validation rejects requests exceeding 16 KiB in 100% of such cases
- **SC-008**: Generative property tests verify round-trip and invariant properties across at least 100 generated inputs per message type

---

## Assumptions

- **A-001**: The 16 KiB (16,384 bytes) standard block size is the enforced maximum request length; larger blocks are rejected at the protocol layer.
- **A-002**: Extension protocol messages (BEP 10) and fast extension messages (BEP 6) are out of scope. Reserved bytes are parsed and preserved but not acted upon.
- **A-003**: TCP framing (reading exactly N bytes from a stream until a full message is available) is handled by the coordination layer, not this feature. This feature operates on complete byte arrays.
- **A-004**: The peer_id format (Azureus-style or Shadow-style) is not validated; any 20-byte sequence is accepted.
- **A-005**: Bitfield messages with more bits than the torrent has pieces are treated as valid; extra bits are ignored.
- **A-006**: The state machine processes one event per call (event-in → new-state-out). Coordinating both ends of the connection is the coordination layer's responsibility.
