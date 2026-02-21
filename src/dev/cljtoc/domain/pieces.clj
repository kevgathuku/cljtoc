(ns dev.cljtoc.domain.pieces
  "Pure domain functions for BitTorrent piece management.

  Provides an immutable piece-status state machine, rarest-first piece
  selection, 16 KiB block decomposition, SHA-1 integrity verification,
  and endgame mode detection.

  All functions are pure — no I/O, no network, no side effects.
  State transitions return new PieceState records without mutating input.
  All fallible functions return {:ok value} or {:error :keyword :message string}.

  Usage examples (REPL):

    ;; Initialize state for a 512-piece torrent
    (def state (initial-piece-state 512))
    (needed-count state)    ;; => 512
    (complete? state)       ;; => false

    ;; Transition a piece through the state machine
    (let [state2 (:ok (mark-in-flight state 7))
          state3 (:ok (mark-verified state2 7))]
      (verified-count state3))  ;; => 1

    ;; Select rarest piece for a peer
    (select-piece state #{0 3 7} [#{0 3 7} #{3 7} #{7}])
    ;; => {:ok 0}  (piece 0 is rarest — only 1 peer has it)

    ;; Decompose piece 0 into blocks (512 KiB piece, 1 GiB torrent)
    (piece-blocks 0 524288 1073741824)
    ;; => {:ok [#Block{:piece-index 0 :offset 0 :length 16384} ...]}  ; 32 blocks

    ;; Verify assembled bytes against expected SHA-1 hash
    (verify-piece 7 assembled-bytes expected-hash)
    ;; => {:ok 7}  or  {:error :hash-mismatch ...}"
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.bencode :as bencode]))

;; ============================================================================
;; Records
;; ============================================================================

(defrecord PieceState
           [total-pieces   ; pos-int — total pieces in this torrent
            needed         ; #{nat-int} — piece indices not yet requested
            in-flight      ; #{nat-int} — piece indices currently being fetched
            verified])     ; #{nat-int} — piece indices complete and verified

(defrecord Block
           [piece-index   ; nat-int — which piece (0-based)
            offset        ; nat-int — byte offset within the piece
            length])      ; pos-int — number of bytes (1 to 16384 inclusive)

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private block-size
  "Fixed block size per BitTorrent convention (BEP 3): 16 KiB."
  16384)

;; ============================================================================
;; Specs — primitive types
;; ============================================================================

(s/def ::piece-index nat-int?)
(s/def ::total-pieces pos-int?)
(s/def ::piece-index-set (s/coll-of nat-int? :kind set?))
;; ::length validates the :length field on Block records (1 to 16384 bytes)
(s/def ::length (s/int-in 1 (inc block-size)))

;; ============================================================================
;; Specs — composite types
;; ============================================================================

(s/def ::needed ::piece-index-set)
(s/def ::in-flight ::piece-index-set)
(s/def ::verified ::piece-index-set)

(s/def ::piece-state
  (s/keys :req-un [::total-pieces ::needed ::in-flight ::verified]))

(s/def ::offset nat-int?)

(s/def ::block
  (s/keys :req-un [::piece-index ::offset ::length]))

;; ============================================================================
;; Error helpers
;; ============================================================================

(defn- piece-error
  "Constructs a piece management error map."
  [error-kw message]
  {:error error-kw :message message})
