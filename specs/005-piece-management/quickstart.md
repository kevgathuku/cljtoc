# Quickstart: Piece Management

**Feature**: 005-piece-management
**Get started in 5 minutes**

---

## Installation

Part of the core `cljtoc` library. Require the namespace directly:

```clojure
(ns my-app
  (:require [dev.cljtoc.domain.pieces :as pieces]))
```

---

## Quick Examples

### 1. Initialize Piece State

```clojure
;; Create state for a torrent with 512 pieces (all "needed")
(def state (pieces/initial-piece-state 512))

;; Query status
(pieces/needed-count state)    ;; => 512
(pieces/in-flight-count state) ;; => 0
(pieces/verified-count state)  ;; => 0
(pieces/complete? state)       ;; => false
```

### 2. State Transitions

```clojure
;; Mark piece 7 as in-flight when we start downloading it
(let [{:keys [ok]} (pieces/mark-in-flight state 7)]
  ;; ok is the new PieceState (original state is unchanged)
  (pieces/needed-count ok)    ;; => 511
  (pieces/in-flight-count ok) ;; => 1
  )

;; After successful verification: mark complete
(let [state2 (:ok (pieces/mark-in-flight state 7))
      state3 (:ok (pieces/mark-verified state2 7))]
  (pieces/verified-count state3)) ;; => 1

;; On failure: requeue for retry
(let [state2 (:ok (pieces/mark-in-flight state 7))
      state3 (:ok (pieces/requeue-piece state2 7))]
  (pieces/needed-count state3))   ;; => 512 (back to start)
```

### 3. Select Next Piece (Rarest-First)

```clojure
;; Convert peer_state bitfields to sets (coordination layer's job)
(defn bitfield->set [^java.util.BitSet bf]
  (set (iterator-seq (.iterator (.stream bf)))))

(let [peer-a-has  #{0 2 5 10}
      all-peers   [#{0 2 5 10} #{2 3 5} #{0 1 2 3}]
      result      (pieces/select-piece state peer-a-has all-peers)]
  (:ok result))  ;; => 10  (rarest piece peer-a has that we still need)

;; No selectable piece
(pieces/select-piece state #{100 200 300} [#{100 200 300}])
;; => {:ok nil}  (those pieces are already verified)
```

### 4. Decompose Piece into Blocks

```clojure
;; Get blocks to request via peer wire protocol
;; Torrent: 512 KiB pieces, 1 GB total
(let [piece-idx          7
      standard-pl        524288   ; from torrent-info :piece-length
      total-length       1073741824
      {:keys [ok]}       (pieces/piece-blocks piece-idx standard-pl total-length)]
  (count ok)            ;; => 32 blocks
  (first ok)            ;; => #Block{:piece-index 7 :offset 0 :length 16384}
  (last ok))            ;; => #Block{:piece-index 7 :offset 507904 :length 16384}

;; Last piece is shorter
(let [{:keys [ok]} (pieces/piece-blocks 2047 524288 1073750016)]
  ok)  ;; => [#Block{:piece-index 2047 :offset 0 :length 8192}]
```

### 5. Verify Piece Integrity

```clojure
;; After assembling all blocks into a complete byte sequence:
(let [expected-hash  (nth (:pieces torrent-info) 7)  ; 20-byte SHA-1 from .torrent
      assembled      (assemble-blocks received-blocks)
      result         (pieces/verify-piece 7 assembled expected-hash)]
  (if (:ok result)
    (pieces/mark-verified state 7)         ; pass — save to disk and advance state
    (pieces/requeue-piece state 7)))       ; fail — discard and retry
```

### 6. Endgame Mode

```clojure
;; Check if we're near the end
(pieces/endgame? state 20)  ;; true when <= 20 pieces remain

;; In endgame: request remaining pieces from ALL peers simultaneously
(when (pieces/endgame? state 20)
  (doseq [peer connected-peers]
    (let [{:keys [ok]} (pieces/select-pieces-endgame state (peer-available peer))]
      (doseq [piece-idx ok]
        (request-piece peer piece-idx)))))
```

---

## Complete Download Loop Pattern

```clojure
(defn download-loop [torrent-info initial-state connected-peers]
  (loop [state initial-state]
    (if (pieces/complete? state)
      (println "Download complete!")
      (let [endgame (pieces/endgame? state 20)]
        ;; Select pieces to request from each peer
        (doseq [peer connected-peers]
          (let [peer-has (bitfield->set (peer-bitfield peer))
                all-has  (map #(bitfield->set (peer-bitfield %)) connected-peers)]
            (if endgame
              ;; Request all remaining from every peer
              (doseq [idx (:ok (pieces/select-pieces-endgame state peer-has))]
                (download-piece peer idx torrent-info))
              ;; Normal: rarest-first from one peer
              (when-let [idx (:ok (pieces/select-piece state peer-has all-has))]
                (download-piece peer idx torrent-info)))))
        ;; Handle events (piece verified, peer disconnected, etc.) → recur with new state
        (recur (handle-next-event state torrent-info))))))
```

---

## Error Handling

All fallible functions return `{:ok value}` or `{:error :keyword :message string}`:

```clojure
;; State transition error (piece not in expected status)
(let [result (pieces/mark-verified state 999)]  ; piece 999 not in-flight
  (if (:ok result)
    (use-new-state (:ok result))
    (log/warn "Transition error:" (:message result))))

;; Verification failure
(let [result (pieces/verify-piece idx bytes expected-hash)]
  (case (:error result)
    :hash-mismatch  (requeue-and-log state idx)
    :invalid-input  (close-peer-connection "bad data")
    nil             (handle-success (:ok result))))
```

Common errors:
- `:invalid-transition` — piece not in the expected state bucket (programming error)
- `:hash-mismatch` — piece data corrupted or malicious; requeue and retry
- `:invalid-input` — out-of-range index or wrong hash length (programming error or bad peer)

---

## Testing

### Pure Function Tests

```clojure
(deftest initial-state-test
  (let [state (pieces/initial-piece-state 100)]
    (is (= 100 (pieces/needed-count state)))
    (is (= 0   (pieces/in-flight-count state)))
    (is (= 0   (pieces/verified-count state)))
    (is (false? (pieces/complete? state)))))

(deftest verify-piece-test
  (let [data          (byte-array [1 2 3 4])
        expected-hash (bencode/sha1-hash data)]
    ;; Pass
    (is (= {:ok 5} (pieces/verify-piece 5 data expected-hash)))
    ;; Fail
    (is (= :hash-mismatch
           (:error (pieces/verify-piece 5 (byte-array [9 9 9]) expected-hash))))))
```

### Generative Tests

```clojure
(defspec piece-blocks-coverage 100
  (prop/for-all [n      (gen/choose 0 999)
                 pl     (gen/choose 1 1048576)
                 total  (gen/choose 1 10737418240)]
    (when (< n (Math/ceil (/ total pl)))
      (let [{:keys [ok]} (pieces/piece-blocks n pl total)
            actual-len   (- (min (* (inc n) pl) total) (* n pl))]
        (and (every? #(<= (:length %) 16384) ok)
             (= actual-len (reduce + (map :length ok))))))))

(defspec state-partition-invariant 100
  (prop/for-all [total (gen/choose 1 1000)
                 idx   gen/nat]
    (let [state   (pieces/initial-piece-state total)
          in-fl   (:ok (pieces/mark-in-flight state (mod idx total)))
          verified (:ok (pieces/mark-verified in-fl (mod idx total)))]
      (= total (+ (pieces/needed-count verified)
                  (pieces/in-flight-count verified)
                  (pieces/verified-count verified))))))
```

---

## Architecture Notes

### Layer Placement

Per the project constitution, this feature lives in the **Domain Layer**:

```
Coordination Layer  ← calls this layer (converts bitfields to sets)
       ↓
Protocol Layer      ← peer wire protocol (Block → Request message)
       ↓
Domain Layer        ← YOU ARE HERE (pieces.clj)
```

### Design Principles

1. **Pure Functions Only**: No I/O — all functions take values and return values
2. **Immutable State**: Every transition returns a new `PieceState`; the original is unchanged
3. **Explicit Errors**: Failures are returned as data maps, never thrown as exceptions
4. **Clojure-Native Types**: No Java types in the domain layer; coordination layer converts BitSets to sets

---

## Troubleshooting

**Q: `mark-in-flight` returns `:invalid-transition`**
A: The piece is not in `needed`. Check if it was already marked in-flight by another path.

**Q: `verify-piece` always fails**
A: Verify the `expected-hash` comes from `(nth (:pieces torrent-info) piece-index)` — a 20-byte array from `parse-torrent`.

**Q: `select-piece` returns `{:ok nil}` unexpectedly**
A: The peer's available pieces and the needed pieces have no intersection. The peer may not have what we need — this is normal; try another peer.

**Q: `piece-blocks` output doesn't sum to the expected piece length**
A: Ensure `total-length` is the sum of all file lengths for multi-file torrents, not just the first file.
