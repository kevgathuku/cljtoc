# API Contracts: Peer Wire Protocol

**Feature**: 004-peer-wire-protocol  
**Date**: 2026-02-17  
**Version**: 1.0.0

---

## Overview

This document defines the public API contracts for the peer wire protocol implementation. All functions are pure and operate on byte arrays - no network I/O.

---

## Module: dev.cljtoc.protocol.peer

### Handshake Functions

#### `parse-handshake`

**Signature**:
```clojure
(parse-handshake bytes) -> {:ok PeerHandshake} | {:error keyword :message string}
```

**Contract**:
- **Input**: `bytes` - byte array (minimum 68 bytes expected)
- **Success Output**: `{:ok handshake-record}` where handshake-record is a `PeerHandshake` instance
- **Error Outputs**:
  - `{:error :incomplete-handshake :message "..."}` - fewer than 68 bytes
  - `{:error :unsupported-protocol :message "..."}` - protocol string mismatch

**Invariants**:
- On success, returned handshake has exactly 68 bytes worth of data
- Protocol string is always "BitTorrent protocol" on success
- Info-hash and peer-id are always 20 bytes each

**Examples**:
```clojure
;; Success
(parse-handshake valid-68-bytes)
;; => {:ok #PeerHandshake{:protocol "BitTorrent protocol", ...}}

;; Failure - too short
(parse-handshake (byte-array 10))
;; => {:error :incomplete-handshake :message "Handshake must be 68 bytes, got 10"}
```

---

#### `build-handshake`

**Signature**:
```clojure
(build-handshake info-hash peer-id) -> {:ok byte-array} | {:error keyword :message string}
(build-handshake info-hash peer-id reserved) -> {:ok byte-array} | {:error keyword :message string}
```

**Contract**:
- **Inputs**:
  - `info-hash` - byte[20], torrent identifier
  - `peer-id` - byte[20], unique peer identifier  
  - `reserved` (optional) - byte[8], extension flags (default: all zeros)
- **Success Output**: `{:ok byte-array}` where byte array is exactly 68 bytes
- **Error Outputs**:
  - `{:error :invalid-input :message "..."}` - info-hash not 20 bytes
  - `{:error :invalid-input :message "..."}` - peer-id not 20 bytes

**Invariants**:
- Output is always exactly 68 bytes
- Bytes 0-19: protocol string + length prefix
- Bytes 20-27: reserved bytes (or zeros)
- Bytes 28-47: info-hash
- Bytes 48-67: peer-id

**Round-trip Property**:
```clojure
(for-all [info-hash (bytes-of-length 20)
          peer-id (bytes-of-length 20)]
  (let [built (build-handshake info-hash peer-id)
        parsed (parse-handshake (:ok built))]
    (= info-hash (:info-hash (:ok parsed)))))
```

---

### Message Parsing Functions

#### `parse-message`

**Signature**:
```clojure
(parse-message bytes) -> {:ok PeerMessage} | {:error keyword :message string}
```

**Contract**:
- **Input**: `bytes` - byte array containing length-prefixed message
- **Success Output**: `{:ok message-record}` where message is one of:
  - `KeepAlive`, `Choke`, `Unchoke`, `Interested`, `NotInterested`
  - `Have`, `Bitfield`, `Request`, `Piece`, `Cancel`
- **Error Outputs**:
  - `{:error :incomplete-message :message "..."}` - declared length > available bytes
  - `{:error :unknown-message-type :message "..."}` - message ID not in 0-8

**Invariants**:
- Length prefix (first 4 bytes) is parsed as big-endian uint32
- Keep-alive has length 0 and no ID byte
- All other messages have length ≥ 1 (includes ID byte)
- Payload length = declared length - 1 (excluding ID byte)

---

#### `parse-messages`

**Signature**:
```clojure
(parse-messages bytes) -> {:ok [PeerMessage] :remaining bytes} | {:error keyword :message string}
```

**Contract**:
- **Input**: `bytes` - byte array potentially containing multiple messages
- **Success Output**: `{:ok messages :remaining bytes}`
  - `messages` - vector of successfully parsed PeerMessage records
  - `remaining` - unconsumed bytes (incomplete final message or empty)
- **Error Output**: First parsing error encountered

**Use Case**: Parse a buffer containing multiple complete messages plus potentially incomplete trailing data.

---

### Message Building Functions

#### `build-message`

**Signature**:
```clojure
(build-message message-record) -> {:ok byte-array} | {:error keyword :message string}
```

**Contract**:
- **Input**: `message-record` - instance of any PeerMessage type
- **Success Output**: `{:ok byte-array}` with correct length prefix and encoding
- **Error Outputs**:
  - `{:error :invalid-input :message "..."}` - validation failure (negative index, oversized block, etc.)

**Validation Rules**:
- `Have.piece-index` ≥ 0
- `Request`/`Cancel`: all fields ≥ 0, length ≤ 16384
- `Piece.data` length ≤ 16384
- `Bitfield.bytes` not empty

---

#### `build-messages`

**Signature**:
```clojure
(build-messages message-records) -> {:ok byte-array} | {:error keyword :message string}
```

**Contract**:
- **Input**: `message-records` - vector of PeerMessage records
- **Success Output**: `{:ok byte-array}` - concatenated encoding of all messages
- **Error Output**: First building error encountered

---

### Convenience Builders

#### `build-keep-alive`

**Signature**:
```clojure
(build-keep-alive) -> {:ok byte-array}
```

**Output**: 4 zero bytes (`0x00 0x00 0x00 0x00`)

---

#### `build-choke` / `build-unchoke` / `build-interested` / `build-not-interested`

**Signatures**:
```clojure
(build-choke) -> {:ok byte-array}           ; 5 bytes, id=0
(build-unchoke) -> {:ok byte-array}         ; 5 bytes, id=1
(build-interested) -> {:ok byte-array}      ; 5 bytes, id=2
(build-not-interested) -> {:ok byte-array}  ; 5 bytes, id=3
```

---

#### `build-have`

**Signature**:
```clojure
(build-have piece-index) -> {:ok byte-array} | {:error keyword :message string}
```

**Validation**: piece-index ≥ 0

---

#### `build-bitfield`

**Signature**:
```clojure
(build-bitfield bitfield-bytes) -> {:ok byte-array} | {:error keyword :message string}
```

**Validation**: bitfield-bytes not empty

---

#### `build-request` / `build-cancel`

**Signatures**:
```clojure
(build-request piece-index begin length) -> {:ok byte-array} | {:error keyword :message string}
(build-cancel piece-index begin length) -> {:ok byte-array} | {:error keyword :message string}
```

**Validation**:
- All parameters ≥ 0
- length > 0
- length ≤ 16384

---

#### `build-piece`

**Signature**:
```clojure
(build-piece piece-index begin data) -> {:ok byte-array} | {:error keyword :message string}
```

**Validation**:
- piece-index ≥ 0
- begin ≥ 0
- data length ≤ 16384

---

## Module: dev.cljtoc.protocol.peer-state

### State Management Functions

#### `initial-peer-state`

**Signature**:
```clojure
(initial-peer-state total-pieces) -> PeerState
```

**Contract**:
- **Input**: `total-pieces` - integer, number of pieces in torrent
- **Output**: `PeerState` record with initial values:
  - `am-choking` = true
  - `am-interested` = false
  - `peer-choking` = true
  - `peer-interested` = false
  - `bitfield` = nil (unknown until bitfield message received)
  - `total-pieces` = provided value

---

#### `apply-message`

**Signature**:
```clojure
(apply-message peer-state message) -> PeerState
```

**Contract**:
- **Inputs**:
  - `peer-state` - current PeerState record
  - `message` - PeerMessage record (parsed message)
- **Output**: New PeerState record with transitions applied

**Transition Table**:

| Message Type | State Change |
|--------------|--------------|
| `Choke` | `peer-choking` → true |
| `Unchoke` | `peer-choking` → false |
| `Interested` | `peer-interested` → true |
| `NotInterested` | `peer-interested` → false |
| `Have` | Set bit at `piece-index` |
| `Bitfield` | Replace entire bitfield |
| `KeepAlive`, `Request`, `Piece`, `Cancel` | No change |

**Invariants**:
- Function is pure: same inputs always produce same output
- Original peer-state is not mutated
- Bitfield is initialized on first `Have` or `Bitfield` message if nil

---

#### `peer-has-piece?`

**Signature**:
```clojure
(peer-has-piece? peer-state piece-index) -> boolean
```

**Contract**:
- **Inputs**:
  - `peer-state` - PeerState record
  - `piece-index` - integer, piece to check
- **Output**: `true` if peer has piece, `false` otherwise

**Behavior**:
- Returns `false` if `bitfield` is nil (unknown availability)
- Returns `false` if `piece-index` out of range
- Returns bit value from bitfield otherwise

---

#### `peer-piece-count`

**Signature**:
```clojure
(peer-piece-count peer-state) -> integer
```

**Contract**:
- **Input**: `peer-state` - PeerState record
- **Output**: Number of pieces peer has (population count of bitfield)

---

### State Query Functions

#### `can-request?`

**Signature**:
```clojure
(can-request? peer-state) -> boolean
```

**Contract**:
- Returns `true` if we can send piece requests to this peer
- Conditions: `peer-choking` = false AND `am-interested` = true

---

#### `should-choke?`

**Signature**:
```clojure
(should-choke? peer-state upload-rate) -> boolean
```

**Contract**:
- Placeholder for choking algorithm (implemented in feature 007)
- Currently always returns false (optimistic unchoking)

---

## Error Response Contract

All error responses follow this format:

```clojure
{:error keyword
 :message string
 :context map?}  ; optional additional context
```

### Standard Error Keywords

| Keyword | Meaning | HTTP Equivalent |
|---------|---------|-----------------|
| `:incomplete-handshake` | Handshake bytes < 68 | 400 Bad Request |
| `:unsupported-protocol` | Wrong protocol string | 400 Bad Request |
| `:incomplete-message` | Declared length > available | 400 Bad Request |
| `:unknown-message-type` | Message ID not 0-8 | 400 Bad Request |
| `:invalid-input` | Validation failure | 422 Unprocessable |

---

## Invariants Summary

1. **Handshake Size**: All handshakes are exactly 68 bytes
2. **Message Round-trip**: `(build → parse)` returns equivalent record for valid inputs
3. **State Immutability**: `apply-message` never mutates input state
4. **Error Safety**: No exceptions thrown for expected errors (returns error maps)
5. **Bitfield Bounds**: Extra bits beyond `total-pieces` are ignored
6. **Block Size Limit**: All block lengths ≤ 16384 bytes (16 KiB)

---

## Usage Example

```clojure
(ns my-app.peer
  (:require [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]))

;; Parse handshake from connection
(let [{:keys [ok error]} (peer/parse-handshake handshake-bytes)]
  (if ok
    (do
      ;; Verify info-hash matches
      (when (= (:info-hash ok) expected-info-hash)
        ;; Build response handshake
        (let [{:keys [ok response-bytes]} (peer/build-handshake 
                                            expected-info-hash 
                                            my-peer-id)]
          ;; Send response-bytes to peer...
          )))
    (println "Handshake failed:" (:message error))))

;; Parse incoming message
(let [{:keys [ok]} (peer/parse-message message-bytes)]
  (case (:message-type ok)
    :choke (swap! peer-atom peer-state/apply-message ok)
    :piece (write-piece-to-disk (:data ok))  ; data plane
    ;; ...
    ))

;; Check what pieces peer has
(let [state @peer-atom]
  (when (peer-state/peer-has-piece? state 42)
    (request-piece-from-peer 42)))
```
