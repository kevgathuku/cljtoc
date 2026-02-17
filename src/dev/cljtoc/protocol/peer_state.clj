(ns dev.cljtoc.protocol.peer-state
  "Peer connection state machine.
   
   Pure state transitions for peer protocol state management.
   All functions are pure: (state, event) -> new-state"
  (:require [clojure.spec.alpha :as s])
  (:import [java.util BitSet]))

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
(s/def ::peer-state
  (s/keys :req-un [::am-choking ::am-interested
                   ::peer-choking ::peer-interested
                   ::bitfield ::total-pieces]))

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

(defn mark-piece-available
  "Mark a piece as available in peer's bitfield.
   
   Args:
     peer-state - Current PeerState
     piece-index - Index of piece now available
   
   Returns:
     New PeerState with updated bitfield"
  [peer-state piece-index]
  {:pre [(s/valid? ::peer-state peer-state)
         (s/valid? ::piece-index piece-index)]}
  (if (< piece-index (:total-pieces peer-state))
    (let [bitfield (or (:bitfield peer-state) (BitSet. (:total-pieces peer-state)))]
      (.set ^BitSet bitfield piece-index)
      (assoc peer-state :bitfield bitfield))
    peer-state))

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
