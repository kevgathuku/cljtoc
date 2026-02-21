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

;; ============================================================================
;; State Initialization (US1)
;; ============================================================================

(defn initial-piece-state
  "Creates a PieceState for a torrent with total-pieces pieces.
  All pieces start in 'needed' status; in-flight and verified are empty."
  [total-pieces]
  (->PieceState total-pieces
                (set (range total-pieces))
                #{}
                #{}))

;; ============================================================================
;; State Queries (US1)
;; ============================================================================

(defn needed-count
  "Returns the number of pieces still needed (not yet requested)."
  [piece-state]
  (count (:needed piece-state)))

(defn in-flight-count
  "Returns the number of pieces currently being fetched from peers."
  [piece-state]
  (count (:in-flight piece-state)))

(defn verified-count
  "Returns the number of pieces that have been downloaded and verified."
  [piece-state]
  (count (:verified piece-state)))

(defn complete?
  "Returns true if and only if all pieces have been verified."
  [piece-state]
  (= (count (:verified piece-state)) (:total-pieces piece-state)))

;; ============================================================================
;; State Transitions (US1)
;; ============================================================================

(defn mark-in-flight
  "Moves piece-index from needed → in-flight.
  Returns {:ok new-state} or {:error :invalid-transition :message string}
  if piece-index is not in needed."
  [piece-state piece-index]
  (if (contains? (:needed piece-state) piece-index)
    {:ok (-> piece-state
             (update :needed disj piece-index)
             (update :in-flight conj piece-index))}
    (piece-error :invalid-transition
                 (str "Piece " piece-index " is not in needed; cannot mark in-flight"))))

(defn mark-verified
  "Moves piece-index from in-flight → verified.
  Returns {:ok new-state} or {:error :invalid-transition :message string}
  if piece-index is not in-flight."
  [piece-state piece-index]
  (if (contains? (:in-flight piece-state) piece-index)
    {:ok (-> piece-state
             (update :in-flight disj piece-index)
             (update :verified conj piece-index))}
    (piece-error :invalid-transition
                 (str "Piece " piece-index " is not in-flight; cannot mark verified"))))

(defn requeue-piece
  "Moves piece-index from in-flight → needed (on download failure or cancellation).
  Returns {:ok new-state} or {:error :invalid-transition :message string}
  if piece-index is not in-flight."
  [piece-state piece-index]
  (if (contains? (:in-flight piece-state) piece-index)
    {:ok (-> piece-state
             (update :in-flight disj piece-index)
             (update :needed conj piece-index))}
    (piece-error :invalid-transition
                 (str "Piece " piece-index " is not in-flight; cannot requeue"))))

;; ============================================================================
;; Function Specs (US1)
;; ============================================================================

(s/fdef initial-piece-state
  :args (s/cat :total-pieces ::total-pieces)
  :ret  ::piece-state
  :fn   #(let [{:keys [total-pieces needed in-flight verified]} (:ret %)]
           (and (= total-pieces (count needed))
                (empty? in-flight)
                (empty? verified))))

(s/fdef needed-count
  :args (s/cat :piece-state ::piece-state)
  :ret  nat-int?
  :fn   #(= (:ret %) (count (-> % :args :piece-state :needed))))

(s/fdef in-flight-count
  :args (s/cat :piece-state ::piece-state)
  :ret  nat-int?
  :fn   #(= (:ret %) (count (-> % :args :piece-state :in-flight))))

(s/fdef verified-count
  :args (s/cat :piece-state ::piece-state)
  :ret  nat-int?
  :fn   #(= (:ret %) (count (-> % :args :piece-state :verified))))

(s/fdef complete?
  :args (s/cat :piece-state ::piece-state)
  :ret  boolean?
  :fn   #(= (:ret %) (= (-> % :args :piece-state :total-pieces)
                        (count (-> % :args :piece-state :verified)))))

(defn- partition-sum-valid?
  "Returns true if the partition invariant holds: needed + in-flight + verified = total-pieces."
  [state]
  (= (:total-pieces state)
     (+ (count (:needed state))
        (count (:in-flight state))
        (count (:verified state)))))

(defn- transition-fn-valid?
  "Validates the :fn contract for mark-* and requeue-piece:
  on success, total-pieces is unchanged and partition invariant holds."
  [%]
  (let [result (:ret %)
        original-total (-> % :args :piece-state :total-pieces)]
    (or (keyword? (:error result))
        (and (= (:total-pieces (:ok result)) original-total)
             (partition-sum-valid? (:ok result))))))

(s/fdef mark-in-flight
  :args (s/cat :piece-state ::piece-state :piece-index ::piece-index)
  :ret  map?
  :fn   transition-fn-valid?)

(s/fdef mark-verified
  :args (s/cat :piece-state ::piece-state :piece-index ::piece-index)
  :ret  map?
  :fn   transition-fn-valid?)

(s/fdef requeue-piece
  :args (s/cat :piece-state ::piece-state :piece-index ::piece-index)
  :ret  map?
  :fn   transition-fn-valid?)
