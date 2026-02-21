# Peer Wire Protocol — API Reference

**Namespace**: `dev.cljtoc.protocol.peer` (parsing & building)
**Namespace**: `dev.cljtoc.protocol.peer-state` (state machine)
**Spec**: [spec.md](spec.md)

---

## Overview

Pure functions for the BitTorrent peer wire protocol (BEP 3). No network I/O — all functions operate on byte arrays or immutable state records.

All functions return `{:ok value}` on success or `{:error keyword :message string}` on failure. Exceptions are never thrown for expected protocol failures.

---

## dev.cljtoc.protocol.peer

### Handshake

#### `(parse-handshake bytes)` → `{:ok PeerHandshake}` | `{:error ...}`

Parse a 68-byte handshake received from a peer.

```clojure
(peer/parse-handshake raw-bytes)
;; {:ok #PeerHandshake{:protocol "BitTorrent protocol"
;;                     :reserved <8 bytes>
;;                     :info-hash <20 bytes>
;;                     :peer-id <20 bytes>}}
```

Error keywords: `:incomplete-handshake` (< 68 bytes), `:unsupported-protocol` (wrong pstr).

#### `(build-handshake info-hash peer-id)` → `{:ok bytes}` | `{:error ...}`
#### `(build-handshake info-hash peer-id reserved)` → `{:ok bytes}` | `{:error ...}`

Build a 68-byte handshake to send to a peer. `reserved` defaults to 8 zero bytes.

```clojure
(peer/build-handshake info-hash-20 peer-id-20)
;; {:ok <byte[68]>}

(peer/build-handshake info-hash-20 peer-id-20 (byte-array 8))
;; {:ok <byte[68]>}
```

Error keyword: `:invalid-input` (wrong byte lengths).

---

### Message Parsing

#### `(parse-message bytes)` → `{:ok PeerMessage}` | `{:error ...}`

Parse a single length-prefixed peer message. Expects the full framed message (4-byte length prefix + payload).

```clojure
;; Keep-alive (4 zero bytes)
(peer/parse-message (byte-array [0 0 0 0]))
;; {:ok #KeepAlive{}}

;; Choke (length=1, id=0)
(peer/parse-message (byte-array [0 0 0 1 0]))
;; {:ok #Choke{}}
```

Error keywords: `:incomplete-message`, `:unknown-message-type`, `:invalid-input` (oversized block).

#### `(parse-messages bytes)` → `{:ok [PeerMessage] :remaining bytes}` | `{:error ...}`

Parse all complete messages from a byte buffer. Returns parsed messages and any leftover bytes (partial message).

```clojure
(peer/parse-messages buffer)
;; {:ok [#Choke{} #Unchoke{}] :remaining <byte[0]>}
```

---

### Message Building

#### `(build-message record)` → `{:ok bytes}` | `{:error ...}`

Build a length-prefixed byte sequence for a single message.

```clojure
(peer/build-message (peer/->KeepAlive))       ;; {:ok <byte[4]>}  — 4 zero bytes
(peer/build-message (peer/->Choke))           ;; {:ok <byte[5]>}  — length=1, id=0
(peer/build-message (peer/->Have 42))         ;; {:ok <byte[9]>}  — length=5, id=4, index=42
(peer/build-message (peer/->Request 0 0 16384)) ;; {:ok <byte[17]>}
(peer/build-message (peer/->Piece 0 0 data))  ;; {:ok <byte[13 + (count data)]>}
```

Error keyword: `:invalid-input` (negative index, block > 16 KiB), `:unknown-message-type`.

#### `(build-messages records)` → `{:ok bytes}` | `{:error ...}`

Build and concatenate multiple messages.

---

### Message Record Types

| Record | Constructor | Fields |
|--------|-------------|--------|
| `KeepAlive` | `(->KeepAlive)` | — |
| `Choke` | `(->Choke)` | — |
| `Unchoke` | `(->Unchoke)` | — |
| `Interested` | `(->Interested)` | — |
| `NotInterested` | `(->NotInterested)` | — |
| `Have` | `(->Have piece-index)` | `:piece-index` |
| `Bitfield` | `(->Bitfield bytes)` | `:bytes` |
| `Request` | `(->Request piece-index begin length)` | `:piece-index` `:begin` `:length` |
| `Piece` | `(->Piece piece-index begin data)` | `:piece-index` `:begin` `:data` |
| `Cancel` | `(->Cancel piece-index begin length)` | `:piece-index` `:begin` `:length` |

### Constants

```clojure
peer/max-block-size  ;; 16384 (16 KiB) — enforced on Request/Cancel/Piece
```

---

## dev.cljtoc.protocol.peer-state

### Initialization

#### `(initial-peer-state total-pieces)` → `PeerState`

Create the initial state for a new peer connection.

```clojure
(peer-state/initial-peer-state 512)
;; {:am-choking true, :am-interested false
;;  :peer-choking true, :peer-interested false
;;  :bitfield nil, :total-pieces 512}
```

---

### State Machine

#### `(apply-message peer-state message)` → `PeerState`

Apply a parsed peer message to produce a new state. Pure — original state is not mutated.

| Message | State change |
|---------|-------------|
| `Choke` | `:peer-choking` → `true` |
| `Unchoke` | `:peer-choking` → `false` |
| `Interested` | `:peer-interested` → `true` |
| `NotInterested` | `:peer-interested` → `false` |
| `Have` | marks piece index in bitfield |
| `Bitfield` | replaces entire bitfield |
| `KeepAlive`, `Request`, `Piece`, `Cancel` | no change |

```clojure
(-> (peer-state/initial-peer-state 100)
    (peer-state/apply-message (peer/->Unchoke))
    (peer-state/apply-message (peer/->Have 7)))
;; {:peer-choking false, ... bitfield has bit 7 set}
```

---

### Query Functions

#### `(peer-has-piece? peer-state piece-index)` → `boolean`

Check if the peer has a specific piece available.

#### `(peer-piece-count peer-state)` → `int`

Count how many pieces the peer has.

#### `(can-request? peer-state)` → `boolean`

Returns `true` when `peer-choking = false` AND `am-interested = true`.

---

### Direct State Setters

For use when sending messages (updating our own side of state):

- `(set-am-choking peer-state bool)` — we are choking the peer
- `(set-am-interested peer-state bool)` — we are interested in the peer
- `(set-peer-choking peer-state bool)` — peer is choking us
- `(set-peer-interested peer-state bool)` — peer is interested in us

---

### Bitfield Utilities

- `(bitfield-from-bytes bytes total-pieces)` → `BitSet` — decode bitfield message payload
- `(bitfield-to-bytes bitset total-pieces)` → `bytes` — encode for sending
- `(mark-piece-available peer-state piece-index)` → `PeerState`
- `(update-bitfield peer-state bitfield-bytes)` → `PeerState`

---

## Error Handling

All parsing/building functions return structured error maps — no exceptions for protocol failures:

```clojure
{:error :incomplete-handshake   :message "Handshake must be 68 bytes, got 50"}
{:error :unsupported-protocol   :message "Protocol string must be 'BitTorrent protocol'"}
{:error :incomplete-message     :message "Message too short for length prefix"}
{:error :unknown-message-type   :message "Unknown message ID"}
{:error :invalid-input          :message "Request length exceeds max block size (20000 > 16384)"}
```

---

## Key Constraints

- Handshake is always exactly **68 bytes**
- Block length in Request/Cancel/Piece is capped at **16,384 bytes** (16 KiB)
- Extra bits in a Bitfield beyond `total-pieces` are silently ignored (BEP 3 / A-005)
- No network I/O — TCP framing is the coordination layer's responsibility (A-003)
