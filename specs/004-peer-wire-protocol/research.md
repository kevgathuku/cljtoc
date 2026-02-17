# Research: Peer Wire Protocol Implementation

**Feature**: 004-peer-wire-protocol  
**Date**: 2026-02-17  
**Constitution Gates**: ✅ All Passed

---

## Phase 0: Technical Research

### Decision: Byte Array Manipulation in Clojure

**Chosen Approach**: Use Clojure's native byte-array support with `byte-array`, `bytes`, and Java interop for low-level operations.

**Rationale**:
- BitTorrent protocol requires exact byte-level control (length prefixes, fixed offsets)
- Clojure's byte-array is just a Java `byte[]` - zero overhead
- Java interop (`aget`, `aset`, `System/arraycopy`) provides necessary low-level operations
- No external dependencies needed (aligns with "pure functions only" constraint)

**Key Functions Needed**:
```clojure
;; Byte extraction
(aget bytes offset)                    ; read single byte
(bytes-to-int32 bytes offset)          ; big-endian 4-byte integer
(bytes-to-int16 bytes offset)          ; big-endian 2-byte integer

;; Byte construction
(int32-to-bytes value)                 ; 4-byte big-endian
(int16-to-bytes value)                 ; 2-byte big-endian
(concat-bytes [& byte-arrays])         ; combine multiple arrays
```

**Alternatives Considered**:
- **byte-streams library**: Rejected - adds unnecessary dependency for simple operations
- **gloss library**: Rejected - designed for complex binary protocols, overkill for BEP 3
- **Java ByteBuffer**: Considered but rejected - Clojure's functional approach cleaner for our use case

---

### Decision: Message Representation

**Chosen Approach**: Use Clojure records for typed messages with `:message-type` keyword dispatch.

**Rationale**:
- Records provide named fields (better than raw maps for documentation)
- Keyword dispatch enables multimethods for parsing/building
- Aligns with FR-005's `{:ok value}` / `{:error ...}` return pattern
- Immutable by default (satisfies FR-007)

**Message Types**:
```clojure
(defrecord PeerHandshake [protocol reserved info-hash peer-id])
(defrecord KeepAlive [])
(defrecord Choke [])
(defrecord Unchoke [])
(defrecord Interested [])
(defrecord NotInterested [])
(defrecord Have [piece-index])
(defrecord Bitfield [bitfield-bytes])
(defrecord Request [piece-index begin length])
(defrecord Piece [piece-index begin data])
(defrecord Cancel [piece-index begin length])
```

**Parsing Return Pattern** (per FR-005):
```clojure
{:ok peer-handshake}           ; success
{:error :incomplete-handshake  ; expected failure
 :message "Handshake must be 68 bytes"}
```

---

### Decision: Bitfield Representation

**Chosen Approach**: Store bitfield as Java `BitSet` wrapped in immutable abstraction.

**Rationale**:
- `BitSet` provides O(1) get/set operations for piece availability checks
- Memory efficient (1 bit per piece vs 1 byte)
- Supports required operations: check piece, set piece, count available
- Must wrap to ensure immutability (FR-007)

**Access Pattern**:
```clojure
;; Check if peer has piece 42
(peer-has-piece? peer-state 42)  ; returns boolean

;; Update from bitfield message
(update-bitfield peer-state bitfield-bytes total-pieces)
```

**Edge Cases** (from spec):
- Bitfield longer than needed: ignore extra bits (A-005)
- Bitfield shorter than needed: treat missing bits as 0 (not available)

---

### Decision: State Machine Implementation

**Chosen Approach**: Pure function `(state, event) → state` as required by constitution Principle I.

**Rationale**:
- Aligns perfectly with constitution's "State transitions MUST be expressed as `(state, event) → state)`"
- Testable without any setup (pure functions)
- Composable - can reduce over message sequence

**State Record**:
```clojure
(defrecord PeerState [am-choking          ; boolean
                      am-interested       ; boolean
                      peer-choking        ; boolean
                      peer-interested     ; boolean
                      bitfield            ; BitSet or nil
                      total-pieces])      ; int for validation
```

**Transition Function**:
```clojure
(defn apply-message
  "Pure state transition: (PeerState, PeerMessage) -> PeerState"
  [state message]
  (case (:message-type message)
    :choke (assoc state :peer-choking true)
    :unchoke (assoc state :peer-choking false)
    :interested (assoc state :peer-interested true)
    :not-interested (assoc state :peer-interested false)
    :have (mark-piece-available state (:piece-index message))
    :bitfield (update-bitfield state (:bitfield-bytes message))
    ;; keep-alive and data messages don't change state
    state))
```

---

### Decision: Testing Strategy

**Chosen Approach**: Example-based tests for edge cases + generative tests for round-trip property.

**Rationale**:
- SC-002 requires round-trip property verified by generative tests
- SC-008 requires 100+ generated inputs per message type
- Example-based tests cover edge cases (incomplete messages, invalid lengths)

**Test Structure**:
```clojure
;; Example-based tests (edge cases)
(deftest handshake-too-short-test
  (is (= {:error :incomplete-handshake ...}
         (parse-handshake (byte-array 10)))))

;; Generative tests (round-trip property)
(defspec round-trip-piece-message 100
  (prop/for-all [piece-idx gen/nat
                 offset gen/nat
                 data gen/bytes]
    (let [msg (->Piece piece-idx offset data)
          built (build-piece-message msg)
          parsed (parse-piece-message built)]
      (= msg (:ok parsed)))))
```

**Coverage Requirements** (from spec):
- 90%+ coverage without I/O (SC-005)
- All 9 message types tested (SC-001)
- 100+ generated inputs per type (SC-008)

---

### Decision: Error Handling Strategy

**Chosen Approach**: Return `{:error keyword :message string}` maps, never throw exceptions for expected failures.

**Rationale**:
- FR-005 explicitly requires: "never throw exceptions for expected failures"
- Enables composable error handling with `if-let`, `when-let`, or monadic patterns
- Allows caller to decide how to handle each error type

**Error Keywords**:
```clojure
:incomplete-handshake    ; < 68 bytes
:unsupported-protocol    ; wrong protocol string
:unknown-message-type    ; invalid message ID
:incomplete-message      ; declared length > available bytes
:invalid-input           ; validation failure (negative index, etc.)
```

**Validation Rules** (per FR-010, A-001):
- Block length must be ≤ 16 KiB (16384 bytes)
- Piece index must be non-negative
- Info hash must be exactly 20 bytes
- Peer ID must be exactly 20 bytes

---

## Open Questions Resolved

| Question | Resolution | Source |
|----------|------------|--------|
| How to handle bitfield with extra bits? | Ignore extra bits (A-005) | Spec assumption |
| Keep-alive during choke? | No state change (keep-alive is heartbeat only) | BEP 3 spec |
| Piece message with 0-byte payload? | Valid - treat as empty block | Edge case analysis |
| Reserved bytes handling? | Parse and preserve but don't act on (A-002) | Spec assumption |

---

## Phase 0 Complete

All technical decisions made. Proceed to Phase 1 design.
