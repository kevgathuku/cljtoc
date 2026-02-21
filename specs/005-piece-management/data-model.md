# Data Model: Piece Management

**Feature**: 005-piece-management
**Date**: 2026-02-21

---

## Entity Overview

```
┌──────────────────────────────────────────────────────────┐
│  PieceState                                               │
│  - Immutable snapshot of all piece download statuses     │
│  - Pure state machine: transitions via pure functions    │
└──────────────────────────────────────────────────────────┘
                              │
                    select-piece uses
                              │
                              ▼
┌──────────────────────────────────────────────────────────┐
│  Peer Availability (input, not stored)                   │
│  - Set of piece indices a peer has                       │
│  - Supplied by caller from peer_state bitfields          │
└──────────────────────────────────────────────────────────┘
                              │
           piece-blocks produces
                              ▼
┌──────────────────────────────────────────────────────────┐
│  Block                                                   │
│  - One 16 KiB (or smaller) network request unit         │
│  - Identified by piece + offset + length                 │
└──────────────────────────────────────────────────────────┘
```

---

## Entity: PieceState

**Purpose**: Immutable snapshot of every piece's download status for a single torrent.

**Record Definition**:
```clojure
(defrecord PieceState
  [total-pieces   ; pos-int — total pieces in this torrent
   needed         ; #{nat-int} — piece indices not yet requested
   in-flight      ; #{nat-int} — piece indices currently being fetched
   verified])     ; #{nat-int} — piece indices complete and verified
```

**Invariants**:
- `(+ (count needed) (count in-flight) (count verified)) = total-pieces` always
- `needed`, `in-flight`, `verified` are pairwise disjoint sets
- All piece indices in any set are in range `[0, total-pieces)`

**Initial State**:
```clojure
(initial-piece-state 512)
;; => {:total-pieces 512
;;     :needed #{0 1 2 ... 511}
;;     :in-flight #{}
;;     :verified #{}}
```

**State Transitions**:

| Function | Moves piece | Precondition | Error if violated |
|----------|-------------|--------------|-------------------|
| `mark-in-flight` | `needed` → `in-flight` | piece in `needed` | `:invalid-transition` |
| `mark-verified` | `in-flight` → `verified` | piece in `in-flight` | `:invalid-transition` |
| `requeue-piece` | `in-flight` → `needed` | piece in `in-flight` | `:invalid-transition` |

**Query Functions**:
- `(needed-count state)` → count of pieces in `needed`
- `(in-flight-count state)` → count of pieces in `in-flight`
- `(verified-count state)` → count of pieces in `verified`
- `(complete? state)` → `true` when `(count verified) = total-pieces`
- `(endgame? state threshold)` → `true` when `(+ needed in-flight) <= threshold`

---

## Entity: Block

**Purpose**: A specific byte range within a piece, corresponding to one peer wire protocol `Request` message.

**Record Definition**:
```clojure
(defrecord Block
  [piece-index   ; nat-int — which piece (0-based)
   offset        ; nat-int — byte offset within the piece
   length])      ; pos-int — number of bytes (1 to 16384 inclusive)
```

**Constraints**:
- `length` ≤ 16,384 (16 KiB — BitTorrent standard block size)
- All blocks for a piece are non-overlapping, contiguous, and cover exactly the piece's byte range
- Blocks are ordered by `offset` ascending

**Block Decomposition Example** (512 KiB piece):
```
Piece 0, standard-piece-length=524288, total-length=1073741824:
  Block {:piece-index 0 :offset      0 :length 16384}
  Block {:piece-index 0 :offset  16384 :length 16384}
  ...
  Block {:piece-index 0 :offset 507904 :length 16384}
  ;; 32 blocks of 16384 bytes = 524288 bytes
```

**Last-Piece Example** (remainder piece):
```
Total: 1073750016 bytes, standard-piece-length=524288
Last piece index = 2047, actual length = 8192 bytes (= 1073750016 mod 524288)
  Block {:piece-index 2047 :offset 0 :length 8192}
  ;; 1 block of 8192 bytes
```

---

## Entity: VerificationResult (as result map)

**Purpose**: Typed outcome of comparing assembled piece bytes against expected SHA-1 hash.

**Success**:
```clojure
{:ok piece-index}       ; piece-index: nat-int — same as input
```

**Failure**:
```clojure
{:error :hash-mismatch
 :piece-index piece-index
 :message "Piece N hash mismatch: expected <hex> got <hex>"}
```

---

## Entity: PieceSelection (as result map)

**Purpose**: Outcome of `select-piece` — the chosen piece for a peer, or nothing.

**Piece selectable**:
```clojure
{:ok piece-index}       ; piece-index: nat-int
```

**No selectable piece** (peer has nothing we need, or nothing needed):
```clojure
{:ok nil}
```

---

## State Machine Diagram

```
        initial-piece-state
               │
               ▼
       ┌───────────────┐
       │    NEEDED     │ ◄─────────────────────────┐
       │  #{0..N-1}    │                           │
       └───────┬───────┘                           │
               │ mark-in-flight(piece-index)       │
               ▼                                   │
       ┌───────────────┐                           │
       │   IN-FLIGHT   │ ─── requeue-piece ────────┘
       │    #{}        │     (failure/cancel)
       └───────┬───────┘
               │ mark-verified(piece-index)
               ▼
       ┌───────────────┐
       │   VERIFIED    │
       │    #{}        │
       └───────────────┘
               │
               │ complete? → true when |verified| = total-pieces
```

---

## Relationships to Other Features

| Feature | Relationship |
|---------|-------------|
| **002-bencode-parser** | Provides `bencode/sha1-hash` used by `verify-piece`; parsed torrent provides `piece-length`, `pieces` (hash vector), `length`/`files` |
| **004-peer-wire-protocol** | `Block` fields map directly to `Request` message fields (`piece-index`, `begin`, `length`) |
| **006-download-orchestration** | Consumer: calls all functions in this feature; converts `peer_state` BitSets to sets for `select-piece` input; drives state transitions on message events |

---

## Validation Matrix

| Entity | Field | Type | Constraints | Error Keyword |
|--------|-------|------|-------------|---------------|
| PieceState | total-pieces | pos-int | ≥ 1 | `:invalid-input` |
| PieceState | needed | set | indices in [0, total-pieces) | `:invalid-input` |
| PieceState | in-flight | set | indices in [0, total-pieces) | `:invalid-input` |
| PieceState | verified | set | indices in [0, total-pieces) | `:invalid-input` |
| Block | piece-index | nat-int | ≥ 0 | `:invalid-input` |
| Block | offset | nat-int | ≥ 0 | `:invalid-input` |
| Block | length | pos-int | 1 to 16384 | `:invalid-input` |
| mark-in-flight | piece-index | nat-int | must be in `needed` | `:invalid-transition` |
| mark-verified | piece-index | nat-int | must be in `in-flight` | `:invalid-transition` |
| requeue-piece | piece-index | nat-int | must be in `in-flight` | `:invalid-transition` |
| piece-blocks | piece-index | nat-int | in [0, total-pieces) | `:invalid-input` |
| verify-piece | assembled-bytes | bytes | non-empty | `:invalid-input` |
| verify-piece | expected-hash | bytes | exactly 20 bytes | `:invalid-input` |

---

## Error Response Format

All fallible functions return:

**Success**:
```clojure
{:ok value}
```

**Failure**:
```clojure
{:error :keyword
 :message "Human-readable description"}
```

**Error Keywords**:
- `:invalid-input` — out-of-range index, wrong byte length, empty bytes
- `:invalid-transition` — state transition precondition violated
- `:hash-mismatch` — piece bytes don't match expected SHA-1 hash
