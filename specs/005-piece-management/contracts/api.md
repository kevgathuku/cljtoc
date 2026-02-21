# API Contracts: Piece Management

**Feature**: 005-piece-management
**Date**: 2026-02-21
**Version**: 1.0.0

---

## Overview

All functions are pure and operate on in-memory data — no network, disk, or timer access. All fallible functions return `{:ok value}` or `{:error :keyword :message string}`. No exceptions are thrown for expected failure cases.

---

## Module: dev.cljtoc.domain.pieces

### State Initialization

#### `initial-piece-state`

**Signature**:
```clojure
(initial-piece-state total-pieces) -> PieceState
```

**Contract**:
- **Input**: `total-pieces` — positive integer, number of pieces in torrent
- **Output**: `PieceState` with all pieces in `needed`, `in-flight` and `verified` empty
- **Precondition**: `total-pieces` ≥ 1 (enforced by spec precondition)

**Invariants**:
- `(count needed)` = `total-pieces`
- `in-flight` = `#{}`
- `verified` = `#{}`

**Example**:
```clojure
(initial-piece-state 3)
;; => #PieceState{:total-pieces 3 :needed #{0 1 2} :in-flight #{} :verified #{}}
```

---

### State Transitions

#### `mark-in-flight`

**Signature**:
```clojure
(mark-in-flight piece-state piece-index) -> {:ok PieceState} | {:error keyword :message string}
```

**Contract**:
- Moves `piece-index` from `needed` → `in-flight`
- **Error**: `:invalid-transition` if piece is not in `needed`

**Invariants**:
- `total-pieces` unchanged
- `(+ needed in-flight verified)` count unchanged (= `total-pieces`)

---

#### `mark-verified`

**Signature**:
```clojure
(mark-verified piece-state piece-index) -> {:ok PieceState} | {:error keyword :message string}
```

**Contract**:
- Moves `piece-index` from `in-flight` → `verified`
- **Error**: `:invalid-transition` if piece is not in `in-flight`

---

#### `requeue-piece`

**Signature**:
```clojure
(requeue-piece piece-state piece-index) -> {:ok PieceState} | {:error keyword :message string}
```

**Contract**:
- Moves `piece-index` from `in-flight` → `needed` (on download failure or cancellation)
- **Error**: `:invalid-transition` if piece is not in `in-flight`

---

### State Queries

#### `needed-count` / `in-flight-count` / `verified-count`

**Signatures**:
```clojure
(needed-count piece-state)    -> nat-int
(in-flight-count piece-state) -> nat-int
(verified-count piece-state)  -> nat-int
```

**Contract**: Return the cardinality of the respective sets. Never return negative values.

---

#### `complete?`

**Signature**:
```clojure
(complete? piece-state) -> boolean
```

**Contract**: Returns `true` if and only if `(count verified) = total-pieces`.

---

#### `endgame?`

**Signature**:
```clojure
(endgame? piece-state threshold) -> boolean
```

**Contract**:
- Returns `true` when `(+ (count needed) (count in-flight)) <= threshold`
- `threshold` is caller-supplied; recommended default is 20
- A completed torrent (`complete? = true`) also satisfies endgame (remaining = 0 ≤ threshold)

---

### Piece Selection

#### `select-piece`

**Signature**:
```clojure
(select-piece piece-state peer-available all-peers-available) -> {:ok piece-index} | {:ok nil}
```

**Contract**:
- **Inputs**:
  - `piece-state` — current `PieceState`
  - `peer-available` — `#{nat-int}` set of piece indices this peer has
  - `all-peers-available` — collection of `#{nat-int}` sets, one per connected peer (used for rarity counting)
- **Success** (piece found): `{:ok piece-index}` where `piece-index` is in `peer-available ∩ needed`
- **Success** (nothing to request): `{:ok nil}`

**Selection Rules**:
1. Candidate pieces = intersection of `peer-available` and `(:needed piece-state)`
2. For each candidate, count occurrences across `all-peers-available`
3. Return the candidate with the minimum count (rarest); lowest piece index breaks ties

**Examples**:
```clojure
;; peer has pieces 0, 2, 5; we need pieces 0, 1, 2, 3
;; all-peers: [{0 2 5} {2 3} {0 1 2}]
;; candidates: #{0 2}
;; counts: piece 0 → 2, piece 2 → 3
;; result: piece 0 (rarest)
(select-piece state #{0 2 5} [#{0 2 5} #{2 3} #{0 1 2}])
;; => {:ok 0}

;; peer has nothing we need
(select-piece state #{6 7 8} [#{6 7 8}])
;; => {:ok nil}
```

---

#### `select-pieces-endgame`

**Signature**:
```clojure
(select-pieces-endgame piece-state peer-available) -> {:ok [piece-index]}
```

**Contract**:
- Returns all pieces the peer has that are in `needed` OR `in-flight` (for duplicate requesting)
- Used only when `(endgame? state threshold)` returns true
- Result may be empty `{:ok []}` if peer has nothing remaining

---

### Block Decomposition

#### `piece-blocks`

**Signature**:
```clojure
(piece-blocks piece-index standard-piece-length total-length) -> {:ok [Block]} | {:error keyword :message string}
```

**Contract**:
- **Inputs**:
  - `piece-index` — nat-int, 0-based piece index
  - `standard-piece-length` — pos-int, bytes per piece from torrent metadata
  - `total-length` — pos-int, total torrent byte count
- **Success**: `{:ok blocks}` where blocks is a non-empty vector of `Block` records
- **Error**: `:invalid-input` if `piece-index` is out of range (≥ ceil(total-length / standard-piece-length))

**Invariants**:
- All blocks have `length` ≤ 16,384
- Blocks are ordered by `offset` ascending
- Sum of all block lengths = actual piece length (standard or shorter for last piece)
- Offsets are contiguous: each block's `offset` = previous block's `offset + length`

**Block size constant**: 16,384 bytes (16 KiB)

**Examples**:
```clojure
;; 512 KiB piece → 32 blocks of 16 KiB each
(piece-blocks 0 524288 1073741824)
;; => {:ok [#Block{:piece-index 0 :offset 0 :length 16384}
;;           #Block{:piece-index 0 :offset 16384 :length 16384}
;;           ...  (30 more)
;;           #Block{:piece-index 0 :offset 507904 :length 16384}]}

;; Last piece: 8192 bytes → 1 block
(piece-blocks 2 524288 1056768)
;; => {:ok [#Block{:piece-index 2 :offset 0 :length 8192}]}
```

---

### Integrity Verification

#### `verify-piece`

**Signature**:
```clojure
(verify-piece piece-index assembled-bytes expected-hash) -> {:ok piece-index} | {:error :hash-mismatch ...}
```

**Contract**:
- **Inputs**:
  - `piece-index` — nat-int, identifies the piece being verified
  - `assembled-bytes` — byte array of all blocks assembled in order
  - `expected-hash` — byte array of exactly 20 bytes (SHA-1 hash from torrent metadata)
- **Success**: `{:ok piece-index}` — bytes match expected hash
- **Failure**: `{:error :hash-mismatch :piece-index piece-index :message "..."}` — bytes do not match
- **Input error**: `{:error :invalid-input :message "..."}` — empty bytes or wrong hash length

**Invariants**:
- On success, returned `piece-index` equals the input `piece-index`
- Verification is pure: no I/O, always produces the same result for the same inputs

**Examples**:
```clojure
;; Pass
(verify-piece 42 correct-bytes expected-hash-from-torrent)
;; => {:ok 42}

;; Fail (corrupted data)
(verify-piece 42 corrupted-bytes expected-hash-from-torrent)
;; => {:error :hash-mismatch :piece-index 42 :message "Piece 42 hash mismatch"}
```

---

## Invariants Summary

1. **PieceState partition**: `needed ∪ in-flight ∪ verified = {0..total-pieces-1}` and the three sets are pairwise disjoint at all times
2. **Transition purity**: `mark-in-flight`, `mark-verified`, and `requeue-piece` never mutate their input state
3. **Block coverage**: `piece-blocks` output covers exactly the full piece byte range with no gaps or overlaps
4. **Verification purity**: `verify-piece` is referentially transparent — same inputs always produce the same result
5. **Selection safety**: `select-piece` only returns pieces from `needed`; never suggests in-flight pieces (outside endgame mode)

---

## Usage Example

```clojure
(ns my-app.downloader
  (:require [dev.cljtoc.domain.pieces :as pieces]))

;; Initialize for a 512-piece torrent
(def state (pieces/initial-piece-state 512))

;; Select piece to download from a peer
(let [result (pieces/select-piece state peer-has #{peer-has all-others})]
  (when-let [idx (:ok result)]
    ;; Get the blocks to request
    (let [{:keys [ok]} (pieces/piece-blocks idx piece-length total-length)]
      ;; Send each block as a Request message via peer wire protocol
      (doseq [block ok]
        (send-request peer (:piece-index block) (:offset block) (:length block))))))

;; After all blocks arrive: verify and transition
(let [{:keys [ok]} (pieces/verify-piece idx assembled-bytes expected-hash)]
  (if ok
    (pieces/mark-verified state idx)
    (pieces/requeue-piece state idx)))

;; Check completion
(when (pieces/complete? state)
  (println "Download complete!"))
```
