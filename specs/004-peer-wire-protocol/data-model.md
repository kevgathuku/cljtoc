# Data Model: Peer Wire Protocol

**Feature**: 004-peer-wire-protocol  
**Date**: 2026-02-17

---

## Entity Overview

```
┌─────────────────────────────────────────────────────────────┐
│  PeerHandshake                                                │
│  - Fixed 68-byte structure                                    │
│  - Protocol identification + torrent verification             │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ uses
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  PeerMessage (discriminated union)                           │
│  ├── KeepAlive                                               │
│  ├── Choke / Unchoke                                         │
│  ├── Interested / NotInterested                              │
│  ├── Have (piece announcement)                               │
│  ├── Bitfield (piece availability)                           │
│  ├── Request / Cancel (block operations)                     │
│  └── Piece (block data transfer)                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ affects
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  PeerState                                                   │
│  - Immutable connection state                                │
│  - Transitions via pure functions                            │
└─────────────────────────────────────────────────────────────┘
```

---

## Entity: PeerHandshake

**Purpose**: Opening message exchanged when establishing peer connection

**Binary Format** (68 bytes fixed):
```
Byte 0:        pstrlen (1 byte) = 0x13 = 19
Bytes 1-19:    pstr = "BitTorrent protocol"
Bytes 20-27:   reserved (8 bytes) - extension flags
Bytes 28-47:   info_hash (20 bytes) - torrent identifier
Bytes 48-67:   peer_id (20 bytes) - client identifier
```

**Record Definition**:
```clojure
(defrecord PeerHandshake
  [protocol      ; String - always "BitTorrent protocol"
   reserved      ; byte[8] - extension flags (preserved, not acted upon)
   info-hash     ; byte[20] - SHA-1 hash of torrent info
   peer-id])     ; byte[20] - unique peer identifier
```

**Validation Rules**:
- `protocol` must equal "BitTorrent protocol"
- `info-hash` must be exactly 20 bytes
- `peer-id` must be exactly 20 bytes
- Full handshake must be exactly 68 bytes

**Error Cases**:
- `:incomplete-handshake` - input < 68 bytes
- `:unsupported-protocol` - protocol string mismatch

---

## Entity: PeerMessage (Discriminated Union)

All peer wire messages share a length-prefixed binary format:
```
Bytes 0-3:  length (uint32, big-endian) - includes id byte but not length field itself
Byte 4:     message-id (0-8, or absent for keep-alive)
Bytes 5+:   payload (depends on message type)
```

### Message Types

#### KeepAlive
- **ID**: N/A (length = 0, no id byte)
- **Payload**: None
- **Binary**: `0x00 0x00 0x00 0x00` (4 bytes)
- **Record**: `(defrecord KeepAlive [])`

#### Choke
- **ID**: 0
- **Payload**: None
- **Binary**: `0x00 0x00 0x00 0x01 0x00` (5 bytes)
- **Record**: `(defrecord Choke [])`
- **Purpose**: Peer stops requesting pieces

#### Unchoke
- **ID**: 1
- **Payload**: None
- **Binary**: `0x00 0x00 0x00 0x01 0x01` (5 bytes)
- **Record**: `(defrecord Unchoke [])`
- **Purpose**: Peer allows piece requests

#### Interested
- **ID**: 2
- **Payload**: None
- **Binary**: `0x00 0x00 0x00 0x01 0x02` (5 bytes)
- **Record**: `(defrecord Interested [])`
- **Purpose**: Client wants pieces peer has

#### NotInterested
- **ID**: 3
- **Payload**: None
- **Binary**: `0x00 0x00 0x00 0x01 0x03` (5 bytes)
- **Record**: `(defrecord NotInterested [])`
- **Purpose**: Client no longer wants pieces

#### Have
- **ID**: 4
- **Payload**: piece-index (4 bytes, uint32)
- **Binary**: `0x00 0x00 0x00 0x05 0x04 <4-byte index>` (9 bytes)
- **Record**: `(defrecord Have [piece-index])`
- **Purpose**: Peer announces it has a specific piece
- **Validation**: piece-index ≥ 0

#### Bitfield
- **ID**: 5
- **Payload**: bitfield (N bytes, ceil(piece-count / 8))
- **Binary**: `0x00 0x00 0x00 <len> 0x05 <N bytes>`
- **Record**: `(defrecord Bitfield [bytes])`
- **Purpose**: Initial piece availability announcement
- **Validation**: bytes.length = ceil(total-pieces / 8)
- **Edge Case**: Extra bits beyond piece-count ignored (A-005)

#### Request
- **ID**: 6
- **Payload**: index (4) + begin (4) + length (4) = 12 bytes
- **Binary**: `0x00 0x00 0x00 0x0D 0x06 <index> <begin> <length>` (17 bytes)
- **Record**: `(defrecord Request [piece-index begin length])`
- **Purpose**: Request a block of data
- **Validation**: 
  - All fields ≥ 0
  - length ≤ 16384 (16 KiB, per A-001)

#### Piece
- **ID**: 7
- **Payload**: index (4) + begin (4) + data (N bytes)
- **Binary**: `0x00 0x00 0x00 <len> 0x07 <index> <begin> <data>`
- **Record**: `(defrecord Piece [piece-index begin data])`
- **Purpose**: Deliver requested block data
- **Validation**: data.length ≤ 16384

#### Cancel
- **ID**: 8
- **Payload**: index (4) + begin (4) + length (4) = 12 bytes
- **Binary**: `0x00 0x00 0x00 0x0D 0x08 <index> <begin> <length>` (17 bytes)
- **Record**: `(defrecord Cancel [piece-index begin length])`
- **Purpose**: Withdraw a pending request
- **Validation**: Same as Request

---

## Entity: PeerState

**Purpose**: Immutable snapshot of peer connection protocol state

**Record Definition**:
```clojure
(defrecord PeerState
  [am-choking        ; boolean - we are choking peer
   am-interested     ; boolean - we want peer's pieces
   peer-choking      ; boolean - peer is choking us
   peer-interested   ; boolean - peer wants our pieces
   bitfield          ; BitSet or nil - pieces peer has (nil = unknown)
   total-pieces])    ; int - for bitfield validation
```

**Initial State**:
```clojure
(initial-peer-state total-pieces)
;; => {:am-choking true
;;     :am-interested false
;;     :peer-choking true
;;     :peer-interested false
;;     :bitfield nil
;;     :total-pieces <provided>}
```

**State Transitions** (pure functions):

| Message | State Change |
|---------|--------------|
| `:choke` | `peer-choking` → true |
| `:unchoke` | `peer-choking` → false |
| `:interested` | `peer-interested` → true |
| `:not-interested` | `peer-interested` → false |
| `:have` | Set bit at `piece-index` in bitfield |
| `:bitfield` | Replace entire bitfield |
| `:keep-alive` | No change |
| `:request` | No change (data plane) |
| `:piece` | No change (data plane) |
| `:cancel` | No change (data plane) |

**Bitfield Operations**:
```clojure
;; Check if peer has piece
(peer-has-piece? peer-state piece-index) ; => boolean

;; Update from Have message
(mark-piece-available peer-state piece-index) ; => new PeerState

;; Update from Bitfield message
(update-bitfield peer-state bitfield-bytes) ; => new PeerState
```

---

## Entity: BlockRequest

**Purpose**: Shared structure for identifying data blocks

**Record Definition**:
```clojure
(defrecord BlockRequest
  [piece-index   ; int - which piece
   begin         ; int - byte offset within piece
   length])      ; int - how many bytes (≤ 16384)
```

**Used By**:
- `Request` message - request a block
- `Piece` message - deliver a block  
- `Cancel` message - cancel a request

**Validation**:
- All fields ≥ 0
- `length` ≤ 16384 (16 KiB)
- `begin + length` ≤ piece-length (enforced at coordination layer)

---

## Relationships

```
┌──────────────────────────────────────────────────────────────┐
│ PeerHandshake                                                 │
│  ├─ identifies: info-hash → Torrent                           │
│  └─ identifies: peer-id → Peer identity                       │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ PeerMessage (control plane)                                   │
│  ├─ Choke/Unchoke → affect: PeerState.peer-choking           │
│  ├─ Interested/NotInterested → affect: PeerState.peer-interested│
│  ├─ Have → affect: PeerState.bitfield (single bit)           │
│  ├─ Bitfield → affect: PeerState.bitfield (full set)         │
│  └─ Request/Cancel/Piece → data plane (no state change)      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ BlockRequest                                                  │
│  └─ identifies: piece + offset + length → specific block     │
└──────────────────────────────────────────────────────────────┘
```

---

## Validation Matrix

| Entity | Field | Type | Constraints | Error Keyword |
|--------|-------|------|-------------|---------------|
| PeerHandshake | protocol | String | = "BitTorrent protocol" | `:unsupported-protocol` |
| PeerHandshake | info-hash | byte[20] | length = 20 | `:invalid-input` |
| PeerHandshake | peer-id | byte[20] | length = 20 | `:invalid-input` |
| Have | piece-index | int | ≥ 0 | `:invalid-input` |
| Bitfield | bytes | byte[] | length = ceil(total-pieces/8) | `:invalid-input` |
| Request/Cancel/Piece | piece-index | int | ≥ 0 | `:invalid-input` |
| Request/Cancel/Piece | begin | int | ≥ 0 | `:invalid-input` |
| Request/Cancel | length | int | > 0, ≤ 16384 | `:invalid-input` |
| Piece | data | byte[] | length ≤ 16384 | `:invalid-input` |
| All messages | length prefix | uint32 | matches actual payload | `:incomplete-message` |

---

## Error Response Format

Per FR-005, all parsing functions return:

**Success**:
```clojure
{:ok record-instance}
```

**Failure**:
```clojure
{:error :keyword
 :message "Human-readable description"}
```

**Error Keywords**:
- `:incomplete-handshake` - handshake < 68 bytes
- `:unsupported-protocol` - wrong protocol string
- `:unknown-message-type` - invalid message ID (not 0-8)
- `:incomplete-message` - length prefix > available bytes
- `:invalid-input` - validation failure (negative numbers, wrong lengths)
