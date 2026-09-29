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
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.utils :as utils]))

;; Byte arrays flow through verification and assembly here; fail the compile on
;; reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

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
;; Piece-state shape — partition invariant + index bounds (issue #40)
;; ============================================================================

(defn- valid-partition?
  "Returns true when needed / in-flight / verified partition
  range(total-pieces): pairwise disjoint, jointly total-pieces large,
  and every member a nat-int below total-pieces. Disjointness plus an
  exact count pins the union to the range without building it, so even
  a huge total with empty sets rejects in constant time. Total over
  maps; anything else answers false instead of throwing."
  [state]
  (and (map? state)
       (let [{:keys [total-pieces needed in-flight verified]} state]
         (and (pos-int? total-pieces)
              (set? needed)
              (set? in-flight)
              (set? verified)
              (empty? (set/intersection needed in-flight))
              (empty? (set/intersection needed verified))
              (empty? (set/intersection in-flight verified))
              (= total-pieces (+ (count needed) (count in-flight) (count verified)))
              (every? (fn [member] (and (nat-int? member) (< member total-pieces)))
                      (concat needed in-flight verified))))))

(def ^:private gen-piece-state
  "Generates reachable PieceState records: a total in [1, 12] plus a
  random needed / in-flight / verified partition of its range, so every
  generated state is one the transitions could actually produce."
  (gen/bind (gen/choose 1 12)
            (fn [total-pieces]
              (gen/fmap (fn [buckets]
                          (let [assigned (map vector (range total-pieces) buckets)
                                in-bucket (fn [bucket]
                                            (set (map first (filter #(= bucket (second %)) assigned))))]
                            (->PieceState total-pieces
                                          (in-bucket 0)
                                          (in-bucket 1)
                                          (in-bucket 2))))
                        (gen/vector (gen/choose 0 2) total-pieces)))))

;; ============================================================================
;; Specs — primitive types
;; ============================================================================

(s/def ::piece-index nat-int?)
;; Bounded generation only: spec's default pos-int? gen emits magnitudes
;; up to ~1e18 (measured), and initial-piece-state materializes
;; (range total-pieces), so an unbounded draw never terminates. Sized
;; generation keeps realistic scales — single-piece through
;; multi-thousand-piece torrents — with a hard cap for termination.
;; Conformance stays pos-int?: instrument/valid? reject nothing they
;; accepted before.
(s/def ::total-pieces
  (s/with-gen pos-int?
    (constantly (gen/sized
                 (fn [size] (gen/choose 1 (min 4096 (max 32 (inc size)))))))))
;; Bounded generation only: piece-blocks materializes one Block record per
;; 16 KiB of the piece, so an unbounded byte size draws planet-sized
;; vectors that never terminate (same feasibility class as ::total-pieces).
;; The 1 MiB cap matches the file's own block-coverage properties.
;; Conformance stays pos-int?.
(s/def ::byte-size
  (s/with-gen pos-int?
    (constantly (gen/sized
                 (fn [size] (gen/choose 1 (min 1048576 (max 16384 (inc size)))))))))
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
  (s/with-gen
    (s/and (s/keys :req-un [::total-pieces ::needed ::in-flight ::verified])
           valid-partition?)
    (constantly gen-piece-state)))

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

(defn- partition-sum-valid?
  "Returns true if the partition invariant holds: needed + in-flight + verified = total-pieces."
  [state]
  (= (:total-pieces state)
     (+ (count (:needed state))
        (count (:in-flight state))
        (count (:verified state)))))

(defn- transition-fn-valid?
  "Validates the :fn contract for the piece-state transitions:
  on success, total-pieces is unchanged and partition invariant holds."
  [%]
  (let [result (:ret %)
        original-total (-> % :args :piece-state :total-pieces)]
    (or (keyword? (:error result))
        (and (= (:total-pieces (:ok result)) original-total)
             (partition-sum-valid? (:ok result))))))

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

(defn requeue-verified
  "Moves piece-index from verified → needed.

   A resumed record can claim a piece is verified whose bytes the piece
   cache no longer holds, which makes the claim unwritable. Keeping it
   verified would let complete? answer true and report a finished download
   with a hole in it, so the piece returns to :needed and the swarm fetches
   it again. Returns {:ok new-state} or {:error :invalid-transition
   :message string} if piece-index is not verified."
  [piece-state piece-index]
  (if (contains? (:verified piece-state) piece-index)
    {:ok (-> piece-state
             (update :verified disj piece-index)
             (update :needed conj piece-index))}
    (piece-error :invalid-transition
                 (str "Piece " piece-index " is not verified; cannot requeue"))))

(s/fdef requeue-verified
  :args (s/cat :piece-state ::piece-state :piece-index ::piece-index)
  :ret  map?
  :fn   transition-fn-valid?)

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

;; ============================================================================
;; Piece Selection — Rarest-First (US2)
;; ============================================================================

(defn select-piece
  "Selects the next piece to download from a peer using rarest-first strategy.

  Candidates = peer-available ∩ needed. Among candidates, the piece held
  by the fewest peers in all-peers-available is returned. Lowest piece index
  breaks ties deterministically.

  Parameters:
    piece-state         — current PieceState
    peer-available      — #{nat-int} pieces this peer has
    all-peers-available — collection of #{nat-int} sets, one per connected peer

  Returns {:ok piece-index} or {:ok nil} if no selectable piece exists."
  [piece-state peer-available all-peers-available]
  (let [candidates (set/intersection (set peer-available) (:needed piece-state))]
    (if (empty? candidates)
      {:ok nil}
      (let [freq   (fn [idx] (count (filter #(contains? % idx) all-peers-available)))
            rarest (first (sort-by (fn [idx] [(freq idx) idx]) candidates))]
        {:ok rarest}))))

(s/fdef select-piece
  :args (s/cat :piece-state         ::piece-state
               :peer-available      ::piece-index-set
               :all-peers-available (s/coll-of ::piece-index-set))
  :ret  map?
  :fn   #(let [result  (:ret %)
               state   (-> % :args :piece-state)
               peer-av (-> % :args :peer-available)]
           (or (nil? (:ok result))
               (and (contains? (:needed state) (:ok result))
                    (contains? peer-av (:ok result))))))

;; ============================================================================
;; Tail-Piece Length (issue #43)
;; ============================================================================

(defn piece-length
  "Real byte length of one piece given the nominal per-piece length from
  torrent metadata and the total torrent byte count. Nominal pieces report
  standard-piece-length; the tail piece reports its shorter real length.
  A single source for the (index, nominal-length, total-length) derivation
  hand-rolled per caller before: piece-blocks, announce-progress and
  assemble-and-verify all divide through here now, so an off-by-one on the
  final piece has exactly one place to hide.

  Total over nat-int inputs: an out-of-range index intersects the torrent
  nowhere and reports zero rather than going negative. Refusing such
  indices stays with callers that own a range (piece-blocks). The span
  derivation runs promotion-safe (bigint start), so a boundary index
  like Long/MAX — which intersects nowhere — reports zero instead of
  throwing on the long cast; the clamped result always fits a long."
  [piece-index standard-piece-length total-length]
  (let [start (* (bigint piece-index) standard-piece-length)
        end (min (+ start standard-piece-length) total-length)]
    (long (max 0 (- end start)))))

(s/fdef piece-length
  :args (s/cat :piece-index           ::piece-index
               :standard-piece-length ::byte-size
               :total-length          ::byte-size)
  :ret nat-int?
  :fn #(<= (:ret %) (-> % :args :standard-piece-length)))

;; ============================================================================
;; Block Decomposition (US3)
;; ============================================================================

(defn piece-blocks
  "Decomposes a piece into an ordered sequence of Block records for peer wire
  protocol requests. Each block is at most 16,384 bytes (16 KiB). The last
  block of the last piece may be shorter.

  Parameters:
    piece-index             — 0-based piece index
    standard-piece-length   — bytes per piece from torrent metadata (pos-int)
    total-length            — total torrent byte count (pos-int)

  Returns {:ok [Block]} or {:error :invalid-input :message string} if
  piece-index is out of range (>= total piece count)."
  [piece-index standard-piece-length total-length]
  ;; Exact integer ceiling: double division loses precision past 2^53 and
  ;; rejects the true final piece. The bigint sum cannot overflow (the
  ;; quotient never exceeds total-length, so the closing long is exact).
  (let [total-pieces (long (quot (+ (bigint total-length) standard-piece-length -1)
                                 standard-piece-length))]
    (if (>= piece-index total-pieces)
      (piece-error :invalid-input
                   (str "Piece index " piece-index
                        " is out of range [0, " total-pieces ")"))
      (let [piece-length (piece-length piece-index standard-piece-length total-length)
            blocks       (loop [offset 0
                                acc    (transient [])]
                           (if (>= offset piece-length)
                             (persistent! acc)
                             (let [blk-len (min block-size (- piece-length offset))]
                               (recur (+ offset blk-len)
                                      (conj! acc (->Block piece-index offset blk-len))))))]
        {:ok blocks}))))

(s/fdef piece-blocks
  :args (s/cat :piece-index           ::piece-index
               :standard-piece-length ::byte-size
               :total-length          ::byte-size)
  :ret  map?
  :fn   #(or (keyword? (-> % :ret :error))
             (let [blocks (-> % :ret :ok)
                   pi     (-> % :args :piece-index)
                   spl    (-> % :args :standard-piece-length)
                   tl     (-> % :args :total-length)
                   expected-len (long (max 0 (- (min (+ (* (bigint pi) spl) spl) tl)
                                                (* (bigint pi) spl))))]
               (and (seq blocks)
                    (every? (fn [b] (<= (:length b) 16384)) blocks)
                    (= expected-len (reduce + (map :length blocks)))))))

;; ============================================================================
;; Integrity Verification (US4)
;; ============================================================================

(defn verify-piece
  "Verifies assembled piece bytes against the expected SHA-1 hash from torrent
  metadata. Returns {:ok piece-index} on match, {:error :hash-mismatch ...} on
  mismatch, or {:error :invalid-input ...} for empty bytes or wrong hash length."
  [piece-index assembled-bytes expected-hash]
  (cond
    (or (nil? assembled-bytes) (zero? (alength ^bytes assembled-bytes)))
    (piece-error :invalid-input "assembled-bytes must not be empty")

    (or (nil? expected-hash) (not= 20 (alength ^bytes expected-hash)))
    (piece-error :invalid-input
                 (str "expected-hash must be exactly 20 bytes, got "
                      (if (nil? expected-hash) "nil" (alength ^bytes expected-hash))))

    :else
    (let [actual-hash (bencode/sha1-hash assembled-bytes)]
      (if (utils/bytes-equal? actual-hash expected-hash)
        {:ok piece-index}
        {:error :hash-mismatch
         :piece-index piece-index
         :message (str "Piece " piece-index " hash mismatch")}))))

(s/fdef verify-piece
  :args (s/cat :piece-index      ::piece-index
               :assembled-bytes  bytes?
               :expected-hash    bytes?)
  :ret  map?
  :fn   (fn [%]
          (let [result (:ret %)
                pi     (-> % :args :piece-index)]
            (or (keyword? (:error result))
                (= pi (:ok result))))))

;; ============================================================================
;; Block Assembly (US4)
;; ============================================================================

(defn assemble-piece
  "Assemble received blocks into a single byte array for a piece.
   Blocks is a collection of {:offset n :data bytes} maps.
   Returns {:ok byte-array} or {:error ...} if blocks have gaps or are empty."
  [blocks expected-length]
  (if (empty? blocks)
    (piece-error :invalid-input "No blocks to assemble")
    (let [sorted (sort-by :offset blocks)
          result (byte-array expected-length)]
      (loop [expected-offset 0
             remaining sorted]
        (if (empty? remaining)
          (if (= expected-offset expected-length)
            {:ok result}
            (piece-error :incomplete-piece
                         (str "Blocks cover " expected-offset " of " expected-length " bytes")))
          (let [{:keys [offset data]} (first remaining)
                data-len (alength ^bytes data)]
            (if (not= offset expected-offset)
              (piece-error :gap-in-blocks
                           (str "Expected offset " expected-offset " but got " offset))
              (do
                (System/arraycopy data 0 result offset data-len)
                (recur (+ expected-offset data-len) (rest remaining))))))))))

(s/fdef assemble-piece
  :args (s/cat :blocks (s/coll-of map?) :expected-length pos-int?)
  :ret  map?
  :fn   #(or (keyword? (-> % :ret :error))
             (= (-> % :args :expected-length) (count (-> % :ret :ok)))))

;; ============================================================================
;; Endgame Mode (US5)
;; ============================================================================

(defn endgame?
  "Returns true when the number of remaining pieces (needed + in-flight)
  is at or below the given threshold. A completed torrent always satisfies
  endgame (0 remaining <= any threshold >= 0)."
  [piece-state threshold]
  (<= (+ (count (:needed piece-state))
         (count (:in-flight piece-state)))
      threshold))

(defn select-pieces-endgame
  "Returns all pieces the peer has that are in needed OR in-flight (for
  duplicate requesting in endgame mode). Returns {:ok [piece-index ...]}
  which may be empty if the peer has nothing remaining."
  [piece-state peer-available]
  (let [remaining (set/union (:needed piece-state) (:in-flight piece-state))
        selected  (set/intersection (set peer-available) remaining)]
    {:ok (vec (sort selected))}))

(s/fdef endgame?
  :args (s/cat :piece-state ::piece-state :threshold nat-int?)
  :ret  boolean?)

(s/fdef select-pieces-endgame
  :args (s/cat :piece-state    ::piece-state
               :peer-available ::piece-index-set)
  :ret  map?
  :fn   (fn [%]
          (let [result   (-> % :ret :ok)
                state    (-> % :args :piece-state)
                remaining (set/union (:needed state) (:in-flight state))]
            (every? (fn [idx] (contains? remaining idx)) result))))
