(ns dev.cljtoc.protocol.peer-state
  "Peer connection state machine.

   Pure state transitions for peer protocol state management.
   All functions are pure: (state, event) -> new-state

   Usage examples (REPL):

     (require '[dev.cljtoc.protocol.peer :as peer])
     (require '[dev.cljtoc.protocol.peer-state :as peer-state])

     ;; Start from a fresh connection (choked, not-interested, no bitfield)
     (def state (initial-peer-state 512))
     ;; => {:am-choking true :am-interested false
     ;;     :peer-choking true :peer-interested false
     ;;     :bitfield nil :total-pieces 512}

     ;; Apply incoming messages as events
     (def s1 (apply-message state (peer/->Unchoke)))
     (:peer-choking s1)  ;; => false

     (def s2 (apply-message s1 (peer/->Have 42)))
     (peer-has-piece? s2 42)   ;; => true
     (peer-has-piece? s2 0)    ;; => false

     ;; Duplicate have messages are idempotent
     (def s3 (apply-message s2 (peer/->Have 42)))
     (peer-piece-count s3)  ;; => 1  (not 2)

     ;; Apply a full bitfield
     (def s4 (apply-message state (peer/->Bitfield (byte-array [0xFF 0x00]))))
     (peer-piece-count s4)  ;; => 8  (first 8 bits of 0xFF)

     ;; Check if we can request pieces
     (def ready (-> state
                    (apply-message (peer/->Unchoke))
                    (set-am-interested true)))
     (can-request? ready)  ;; => true  (unchoked AND interested)

     ;; Keep-alive and data messages (Request/Piece/Cancel) are no-ops
     (= state (apply-message state (peer/->KeepAlive)))  ;; => true

     ;; State transitions never mutate the original
     (let [a (apply-message state (peer/->Have 3))
           b (apply-message a    (peer/->Have 9))]
       (peer-has-piece? a 9))  ;; => false  (a is independent of b)"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import [java.util BitSet]))

;; Bitfields arrive as byte arrays here; fail the compile on reflective calls
;; so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

;; ============================================================================
;; Specs
;; ============================================================================

;; Boolean specs
(s/def ::am-choking boolean?)
(s/def ::am-interested boolean?)
(s/def ::peer-choking boolean?)
(s/def ::peer-interested boolean?)

;; BitSet or nil
(s/def ::bitfield (s/nilable #(instance? BitSet %)))

;; Total pieces count
(s/def ::total-pieces pos-int?)

;; PeerState spec
;; Generation: `:bitfield` is a `BitSet` which has no `s/gen`. Without a
;; generator, `s/keys` cannot construct an instance, so any fdef that
;; takes `::peer-state` dies in the generative check (stest/check never
;; reaches the function body). We pin a constructor-backed gen here so
;; the spec stays the contract for real callers while still being
;; generatable for tests. Conformance is unchanged — `(s/conform
;; ::peer-state …)` still runs the key-check below.
(declare initial-peer-state)

(s/def ::peer-state
  (s/with-gen (s/keys :req-un [::am-choking ::am-interested
                               ::peer-choking ::peer-interested
                               ::bitfield ::total-pieces])
    #(gen/fmap
      (fn [total-pieces] (initial-peer-state total-pieces))
      (gen/choose 1 1024))))

;; Piece index
(s/def ::piece-index nat-int?)

;; ============================================================================
;; PeerState Record
;; ============================================================================

;; Immutable record of peer connection state.
;; Tracks choking/interest state and piece availability.
(defrecord PeerState [am-choking
                      am-interested
                      peer-choking
                      peer-interested
                      bitfield
                      total-pieces])

;; ============================================================================
;; State Initialization
;; ============================================================================

(defn initial-peer-state
  "Create initial peer state for a new connection.
   
   Initial state:
     - am-choking: true (we start by choking peer)
     - am-interested: false (we don't know what they have yet)
     - peer-choking: true (they start by choking us)
     - peer-interested: false (they don't know what we have yet)
     - bitfield: nil (unknown until bitfield message received)
   
   Args:
     total-pieces - Number of pieces in the torrent
   
   Returns:
     PeerState record"
  [total-pieces]
  {:pre [(s/valid? ::total-pieces total-pieces)]}
  (map->PeerState
   {:am-choking true
    :am-interested false
    :peer-choking true
    :peer-interested false
    :bitfield nil
    :total-pieces total-pieces}))

(s/fdef initial-peer-state
  :args (s/cat :total-pieces ::total-pieces)
  :ret  ::peer-state
  :fn   #(let [s (:ret %)]
           (and (true?  (:am-choking s))
                (false? (:am-interested s))
                (true?  (:peer-choking s))
                (false? (:peer-interested s))
                (nil?   (:bitfield s))
                (= (-> % :args :total-pieces) (:total-pieces s)))))

;; ============================================================================
;; Bitfield Operations
;; ============================================================================

(defn bitfield-from-bytes
  "Create a BitSet from a byte array.
   
   Args:
     bytes - byte array containing bitfield data
     total-pieces - Number of pieces (to validate size)
   
   Returns:
     BitSet representing piece availability"
  [^bytes b total-pieces]
  (let [expected-len (Math/ceil (/ total-pieces 8.0))]
    (when (>= (count b) expected-len)
      (let [bitset (BitSet. total-pieces)]
        (dotimes [i total-pieces]
          (let [byte-idx (quot i 8)
                bit-idx (mod i 8)
                byte-val (aget b byte-idx)
                bit-val (bit-and (bit-shift-right (bit-and byte-val 0xFF) (- 7 bit-idx)) 1)]
            (when (= 1 bit-val)
              (.set bitset i))))
        bitset))))

(defn bitfield-to-bytes
  "Convert a BitSet to a byte array.
   
   Args:
     bitfield - BitSet representing piece availability
     total-pieces - Number of pieces (determines output size)
   
   Returns:
     Byte array"
  [^BitSet bitset total-pieces]
  (let [byte-len (int (Math/ceil (/ total-pieces 8.0)))
        result (byte-array byte-len)]
    (dotimes [i total-pieces]
      (when (.get bitset i)
        (let [byte-idx (quot i 8)
              bit-idx (mod i 8)
              current (aget result byte-idx)
              new-val (bit-or current (bit-shift-left 1 (- 7 bit-idx)))]
          (aset result byte-idx (unchecked-byte new-val)))))
    result))

(defn peer-has-piece?
  "Check if peer has a specific piece.
   
   Args:
     peer-state - Current PeerState
     piece-index - Index of piece to check
   
   Returns:
     true if peer has piece, false otherwise (or if unknown)"
  [peer-state piece-index]
  {:pre [(s/valid? ::peer-state peer-state)]}
  (if (and (>= piece-index 0)
           (< piece-index (:total-pieces peer-state)))
    (if-let [bitfield (:bitfield peer-state)]
      (.get ^BitSet bitfield piece-index)
      false)
    false))

(s/fdef peer-has-piece?
  :args (s/cat :peer-state ::peer-state :piece-index nat-int?)
  :ret  boolean?
  :fn   #(if (nil? (-> % :args :peer-state :bitfield))
           (false? (:ret %))
           true))

(defn mark-piece-available
  "Mark a piece as available in peer's bitfield.

   Returns a new PeerState without mutating the original.

   Args:
     peer-state - Current PeerState
     piece-index - Index of piece now available

   Returns:
     New PeerState with updated bitfield"
  [peer-state piece-index]
  {:pre [(s/valid? ::peer-state peer-state)
         (s/valid? ::piece-index piece-index)]}
  (if (< piece-index (:total-pieces peer-state))
    (let [existing (:bitfield peer-state)
          new-bitfield (if existing
                         (doto (BitSet.) (.or ^BitSet existing))
                         (BitSet. (:total-pieces peer-state)))]
      (.set ^BitSet new-bitfield piece-index)
      (assoc peer-state :bitfield new-bitfield))
    peer-state))

(s/fdef mark-piece-available
  :args (s/cat :peer-state ::peer-state :piece-index nat-int?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :peer-state :total-pieces)
            (-> % :ret :total-pieces)))

(defn update-bitfield
  "Update peer's bitfield from a bitfield message.
   
   Args:
     peer-state - Current PeerState
     bitfield-bytes - Byte array from bitfield message
   
   Returns:
     New PeerState with updated bitfield (extra bits ignored per BEP 3)"
  [peer-state bitfield-bytes]
  {:pre [(s/valid? ::peer-state peer-state)
         (bytes? bitfield-bytes)]}
  (if-let [new-bitfield (bitfield-from-bytes bitfield-bytes (:total-pieces peer-state))]
    (assoc peer-state :bitfield new-bitfield)
    peer-state))

(s/fdef update-bitfield
  :args (s/cat :peer-state ::peer-state :bitfield-bytes bytes?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :peer-state :total-pieces)
            (-> % :ret :total-pieces)))

(defn peer-piece-count
  "Count how many pieces peer has.
   
   Args:
     peer-state - Current PeerState
   
   Returns:
     Number of pieces peer has available"
  [peer-state]
  {:pre [(s/valid? ::peer-state peer-state)]}
  (if-let [bitfield (:bitfield peer-state)]
    (.cardinality ^BitSet bitfield)
    0))

(s/fdef peer-piece-count
  :args (s/cat :peer-state ::peer-state)
  :ret  nat-int?
  :fn   #(if (nil? (-> % :args :peer-state :bitfield))
           (zero? (:ret %))
           (<= (:ret %) (-> % :args :peer-state :total-pieces))))

(defn available-pieces
  "Set of piece indices the peer has available.

   Args:
     peer-state - Current PeerState

   Returns:
     #{nat-int} of available piece indices (empty when unknown)"
  [peer-state]
  {:pre [(s/valid? ::peer-state peer-state)]}
  (if-let [bitfield (:bitfield peer-state)]
    (let [total-pieces (:total-pieces peer-state)]
      (loop [piece-index (.nextSetBit ^BitSet bitfield 0)
             acc (transient #{})]
        (if (or (= piece-index -1) (>= piece-index total-pieces))
          (persistent! acc)
          (recur (.nextSetBit ^BitSet bitfield (inc piece-index)) (conj! acc piece-index)))))
    #{}))

(s/fdef available-pieces
  :args (s/cat :peer-state ::peer-state)
  :ret (s/coll-of ::piece-index :kind set?)
  :fn #(every? (fn [idx] (< idx (-> % :args :peer-state :total-pieces)))
               (:ret %)))

;; ============================================================================
;; State Transitions
;; ============================================================================

(defn set-peer-choking
  "Set peer choking state.
   
   Args:
     peer-state - Current PeerState
     choking - true if peer is choking us, false otherwise
   
   Returns:
     New PeerState"
  [peer-state choking]
  {:pre [(s/valid? ::peer-state peer-state)
         (boolean? choking)]}
  (assoc peer-state :peer-choking choking))

(s/fdef set-peer-choking
  :args (s/cat :peer-state ::peer-state :v boolean?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :v) (-> % :ret :peer-choking)))

(defn set-peer-interested
  "Set peer interested state.
   
   Args:
     peer-state - Current PeerState
     interested - true if peer is interested, false otherwise
   
   Returns:
     New PeerState"
  [peer-state interested]
  {:pre [(s/valid? ::peer-state peer-state)
         (boolean? interested)]}
  (assoc peer-state :peer-interested interested))

(s/fdef set-peer-interested
  :args (s/cat :peer-state ::peer-state :v boolean?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :v) (-> % :ret :peer-interested)))

(defn set-am-choking
  "Set our choking state.
   
   Args:
     peer-state - Current PeerState
     choking - true if we are choking peer, false otherwise
   
   Returns:
     New PeerState"
  [peer-state choking]
  {:pre [(s/valid? ::peer-state peer-state)
         (boolean? choking)]}
  (assoc peer-state :am-choking choking))

(s/fdef set-am-choking
  :args (s/cat :peer-state ::peer-state :v boolean?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :v) (-> % :ret :am-choking)))

(defn set-am-interested
  "Set our interested state.
   
   Args:
     peer-state - Current PeerState
     interested - true if we are interested, false otherwise
   
   Returns:
     New PeerState"
  [peer-state interested]
  {:pre [(s/valid? ::peer-state peer-state)
         (boolean? interested)]}
  (assoc peer-state :am-interested interested))

(s/fdef set-am-interested
  :args (s/cat :peer-state ::peer-state :v boolean?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :v) (-> % :ret :am-interested)))

;; ============================================================================
;; State Transitions
;; ============================================================================

(defn apply-message
  "Apply a peer message to update the peer state.
   
   Pure function that returns a new PeerState with transitions applied.
   The original state is never mutated.
   
   Transition table:
     - Choke: peer-choking -> true
     - Unchoke: peer-choking -> false
     - Interested: peer-interested -> true
     - NotInterested: peer-interested -> false
     - Have: set bit at piece-index in bitfield
     - Bitfield: replace entire bitfield
     - KeepAlive, Request, Piece, Cancel: no change
   
   Args:
     peer-state - Current PeerState
     message - PeerMessage record from dev.cljtoc.protocol.peer
   
   Returns:
     New PeerState with transitions applied"
  [peer-state message]
  {:pre [(s/valid? ::peer-state peer-state)]}
  (cond
    ;; Choke/Unchoke - affects peer-choking
    (instance? dev.cljtoc.protocol.peer.Choke message)
    (assoc peer-state :peer-choking true)

    (instance? dev.cljtoc.protocol.peer.Unchoke message)
    (assoc peer-state :peer-choking false)

    ;; Interested/NotInterested - affects peer-interested
    (instance? dev.cljtoc.protocol.peer.Interested message)
    (assoc peer-state :peer-interested true)

    (instance? dev.cljtoc.protocol.peer.NotInterested message)
    (assoc peer-state :peer-interested false)

    ;; Have - mark piece available
    (instance? dev.cljtoc.protocol.peer.Have message)
    (mark-piece-available peer-state (:piece-index message))

    ;; Bitfield - update entire bitfield
    (instance? dev.cljtoc.protocol.peer.Bitfield message)
    (update-bitfield peer-state (:bytes message))

    ;; KeepAlive, Request, Piece, Cancel - no state change
    :else peer-state))

(s/fdef apply-message
  :args (s/cat :peer-state ::peer-state :message any?)
  :ret  ::peer-state
  :fn   #(= (-> % :args :peer-state :total-pieces)
            (-> % :ret :total-pieces)))

;; ============================================================================
;; Query Functions
;; ============================================================================

(defn can-request?
  "Check if we can request pieces from this peer.

   We can request when:
     - Peer is not choking us (peer-choking = false)
     - We are interested (am-interested = true)

   Args:
     peer-state - Current PeerState

   Returns:
     true if we can request pieces"
  [peer-state]
  {:pre [(s/valid? ::peer-state peer-state)]}
  (and (not (:peer-choking peer-state))
       (:am-interested peer-state)))

(s/fdef can-request?
  :args (s/cat :peer-state ::peer-state)
  :ret  boolean?
  :fn   #(= (:ret %)
            (boolean (and (not (-> % :args :peer-state :peer-choking))
                          (-> % :args :peer-state :am-interested)))))
