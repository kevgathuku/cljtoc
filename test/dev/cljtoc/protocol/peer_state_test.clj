(ns dev.cljtoc.protocol.peer-state-test
  "Tests for peer connection state machine.
   
   Tests pure state transitions and bitfield operations."
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as tc-gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dev.cljtoc.protocol.peer-state :as peer-state])
  (:import [java.util BitSet]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn make-bitfield-bytes
  "Create a byte array representing a bitfield.
   
   Args:
     pieces - Set of piece indices that are available
     total-pieces - Total number of pieces
   
   Returns:
     Byte array suitable for bitfield message"
  [pieces total-pieces]
  (let [byte-len (int (Math/ceil (/ total-pieces 8.0)))
        result (byte-array byte-len)]
    (doseq [piece pieces]
      (when (< piece total-pieces)
        (let [byte-idx (quot piece 8)
              bit-idx (mod piece 8)
              current (aget result byte-idx)
              new-val (bit-or current (bit-shift-left 1 (- 7 bit-idx)))]
          (aset result byte-idx (unchecked-byte new-val)))))
    result))

;; ============================================================================
;; Initial State Tests
;; ============================================================================

(deftest initial-state-test
  (testing "Initial peer state has correct default values"
    (let [state (peer-state/initial-peer-state 100)]
      (is (= true (:am-choking state)))
      (is (= false (:am-interested state)))
      (is (= true (:peer-choking state)))
      (is (= false (:peer-interested state)))
      (is (nil? (:bitfield state)))
      (is (= 100 (:total-pieces state))))))

(deftest initial-state-different-sizes-test
  (testing "Initial state works with different torrent sizes"
    (doseq [size [1 10 100 1000 10000]]
      (let [state (peer-state/initial-peer-state size)]
        (is (= size (:total-pieces state)))))))

;; ============================================================================
;; State Transition Tests
;; ============================================================================

(deftest peer-choking-transitions-test
  (testing "Peer choking state transitions correctly"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initial state: peer is choking us
      (is (= true (:peer-choking initial)))

      ;; Unchoke: peer stops choking us
      (let [unchoked (peer-state/set-peer-choking initial false)]
        (is (= false (:peer-choking unchoked)))
        ;; Other fields unchanged
        (is (= true (:am-choking unchoked)))
        (is (= false (:am-interested unchoked))))

      ;; Choke: peer starts choking us again
      (let [choked (peer-state/set-peer-choking initial true)]
        (is (= true (:peer-choking choked)))))))

(deftest peer-interested-transitions-test
  (testing "Peer interested state transitions correctly"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initial state: peer is not interested
      (is (= false (:peer-interested initial)))

      ;; Peer becomes interested
      (let [interested (peer-state/set-peer-interested initial true)]
        (is (= true (:peer-interested interested)))
        ;; Other fields unchanged
        (is (= true (:am-choking interested))))

      ;; Peer becomes not interested
      (let [not-interested (peer-state/set-peer-interested initial false)]
        (is (= false (:peer-interested not-interested)))))))

(deftest am-choking-transitions-test
  (testing "Our choking state transitions correctly"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initial state: we are choking peer
      (is (= true (:am-choking initial)))

      ;; We unchoke peer
      (let [unchoked (peer-state/set-am-choking initial false)]
        (is (= false (:am-choking unchoked))))

      ;; We choke peer again
      (let [choked (peer-state/set-am-choking initial true)]
        (is (= true (:am-choking choked)))))))

(deftest am-interested-transitions-test
  (testing "Our interested state transitions correctly"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initial state: we are not interested
      (is (= false (:am-interested initial)))

      ;; We become interested
      (let [interested (peer-state/set-am-interested initial true)]
        (is (= true (:am-interested interested))))

      ;; We become not interested
      (let [not-interested (peer-state/set-am-interested initial false)]
        (is (= false (:am-interested not-interested)))))))

;; ============================================================================
;; Bitfield Tests
;; ============================================================================

(deftest mark-piece-available-test
  (testing "Can mark pieces as available"
    (let [initial (peer-state/initial-peer-state 100)
          with-piece-0 (peer-state/mark-piece-available initial 0)
          with-piece-5 (peer-state/mark-piece-available with-piece-0 5)
          with-piece-99 (peer-state/mark-piece-available with-piece-5 99)]

      (is (true? (peer-state/peer-has-piece? with-piece-99 0)))
      (is (true? (peer-state/peer-has-piece? with-piece-99 5)))
      (is (true? (peer-state/peer-has-piece? with-piece-99 99)))
      (is (false? (peer-state/peer-has-piece? with-piece-99 1)))
      (is (false? (peer-state/peer-has-piece? with-piece-99 50))))))

(deftest mark-piece-out-of-range-test
  (testing "Marking out-of-range piece is a no-op"
    (let [initial (peer-state/initial-peer-state 100)
          result (peer-state/mark-piece-available initial 100)]  ; Index 100 out of range
      ;; Should return unchanged state
      (is (= initial result))
      ;; No pieces should be available
      (is (= 0 (peer-state/peer-piece-count result))))))

(deftest update-bitfield-test
  (testing "Can update bitfield from bytes"
    (let [initial (peer-state/initial-peer-state 100)
          ;; Create bitfield with pieces 0, 5, 10 available
          bitfield-bytes (make-bitfield-bytes #{0 5 10 20 50 99} 100)
          updated (peer-state/update-bitfield initial bitfield-bytes)]

      (is (true? (peer-state/peer-has-piece? updated 0)))
      (is (true? (peer-state/peer-has-piece? updated 5)))
      (is (true? (peer-state/peer-has-piece? updated 10)))
      (is (true? (peer-state/peer-has-piece? updated 20)))
      (is (true? (peer-state/peer-has-piece? updated 50)))
      (is (true? (peer-state/peer-has-piece? updated 99)))
      (is (false? (peer-state/peer-has-piece? updated 1)))
      (is (false? (peer-state/peer-has-piece? updated 98))))))

(deftest update-bitfield-replaces-existing-test
  (testing "Updating bitfield replaces existing bitfield"
    (let [initial (peer-state/initial-peer-state 100)
          with-piece-0 (peer-state/mark-piece-available initial 0)
          ;; New bitfield only has piece 5
          bitfield-bytes (make-bitfield-bytes #{5} 100)
          updated (peer-state/update-bitfield with-piece-0 bitfield-bytes)]

      ;; Piece 0 should no longer be available
      (is (false? (peer-state/peer-has-piece? updated 0)))
      ;; But piece 5 should be
      (is (true? (peer-state/peer-has-piece? updated 5))))))

(deftest bitfield-extra-bits-ignored-test
  (testing "Extra bits beyond total-pieces are ignored (per BEP 3)"
    (let [initial (peer-state/initial-peer-state 10)  ; Only 10 pieces
          ;; Bitfield has 16 bits (2 bytes), extra 6 bits should be ignored
          bitfield-bytes (byte-array [(unchecked-byte 0xFF) (unchecked-byte 0xFF)])
          updated (peer-state/update-bitfield initial bitfield-bytes)]

      ;; All 10 pieces should be available
      (is (= 10 (peer-state/peer-piece-count updated)))
      (is (true? (peer-state/peer-has-piece? updated 0)))
      (is (true? (peer-state/peer-has-piece? updated 9)))
      ;; But we shouldn't crash or report more than 10
      (is (false? (peer-state/peer-has-piece? updated 10))))))

(deftest update-bitfield-too-short-test
  (testing "Too-short bitfield is handled gracefully"
    (let [initial (peer-state/initial-peer-state 100)
          ;; Only 1 byte for 100 pieces (need 13)
          short-bitfield (byte-array [0xFF])
          result (peer-state/update-bitfield initial short-bitfield)]
      ;; Should return unchanged state
      (is (= initial result)))))

(deftest peer-has-piece-nil-bitfield-test
  (testing "peer-has-piece? returns false when bitfield is nil"
    (let [initial (peer-state/initial-peer-state 100)]
      (is (nil? (:bitfield initial)))
      (is (false? (peer-state/peer-has-piece? initial 0)))
      (is (false? (peer-state/peer-has-piece? initial 50))))))

(deftest peer-has-piece-out-of-range-test
  (testing "peer-has-piece? returns false for out-of-range indices"
    (let [initial (peer-state/initial-peer-state 100)
          with-pieces (peer-state/mark-piece-available initial 0)]
      (is (false? (peer-state/peer-has-piece? with-pieces 100)))
      (is (false? (peer-state/peer-has-piece? with-pieces 1000)))
      (is (false? (peer-state/peer-has-piece? with-pieces -1))))))

;; ============================================================================
;; Piece Count Tests
;; ============================================================================

(deftest peer-piece-count-test
  (testing "peer-piece-count returns correct count"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initially no pieces
      (is (= 0 (peer-state/peer-piece-count initial)))

      ;; Add one piece
      (let [one-piece (peer-state/mark-piece-available initial 0)]
        (is (= 1 (peer-state/peer-piece-count one-piece))))

      ;; Add multiple pieces
      (let [many-pieces (reduce peer-state/mark-piece-available
                                initial
                                [0 5 10 20 50])]
        (is (= 5 (peer-state/peer-piece-count many-pieces)))))))

(deftest peer-piece-count-nil-bitfield-test
  (testing "peer-piece-count returns 0 when bitfield is nil"
    (let [initial (peer-state/initial-peer-state 100)]
      (is (= 0 (peer-state/peer-piece-count initial))))))

;; ============================================================================
;; Can Request Tests
;; ============================================================================

(deftest can-request-test
  (testing "can-request? returns true only when unchoked and interested"
    (let [initial (peer-state/initial-peer-state 100)]
      ;; Initial: choked and not interested - cannot request
      (is (false? (peer-state/can-request? initial)))

      ;; Unchoked but not interested - cannot request
      (let [unchoked (peer-state/set-peer-choking initial false)]
        (is (false? (peer-state/can-request? unchoked))))

      ;; Interested but choked - cannot request
      (let [interested (peer-state/set-am-interested initial true)]
        (is (false? (peer-state/can-request? interested))))

      ;; Unchoked and interested - CAN request
      (let [ready (-> initial
                      (peer-state/set-peer-choking false)
                      (peer-state/set-am-interested true))]
        (is (true? (peer-state/can-request? ready)))))))

;; ============================================================================
;; Determinism Tests
;; ============================================================================

(defspec state-transitions-deterministic 100
  (prop/for-all [total-pieces (tc-gen/choose 1 1000)
                 piece-idx (tc-gen/choose 0 999)]
                (let [state (peer-state/initial-peer-state total-pieces)
                      new-state (peer-state/mark-piece-available state (mod piece-idx total-pieces))]
      ;; Applying same transition twice should give same result
                  (= new-state (peer-state/mark-piece-available state (mod piece-idx total-pieces))))))

;; ============================================================================
;; Spec Compliance Tests
;; ============================================================================

(deftest spec-validation-test
  (testing "clojure.spec validates PeerState correctly"
    (let [valid-state {:am-choking true
                       :am-interested false
                       :peer-choking true
                       :peer-interested false
                       :bitfield nil
                       :total-pieces 100}
          invalid-state {:am-choking true
                         :am-interested false
                         :peer-choking true
                         :peer-interested false
                         :bitfield nil
                         :total-pieces -1}]
      (is (s/valid? ::peer-state/peer-state valid-state))
      (is (not (s/valid? ::peer-state/peer-state invalid-state))))))

(deftest initial-state-spec-test
  (testing "Initial state conforms to spec"
    (doseq [size [1 10 100 1000]]
      (let [state (peer-state/initial-peer-state size)]
        (is (s/valid? ::peer-state/peer-state state))))))

(deftest state-transition-spec-test
  (testing "State transitions preserve spec conformance"
    (let [initial (peer-state/initial-peer-state 100)
          states [(peer-state/set-peer-choking initial false)
                  (peer-state/set-peer-interested initial true)
                  (peer-state/set-am-choking initial false)
                  (peer-state/set-am-interested initial true)
                  (peer-state/mark-piece-available initial 0)
                  (peer-state/update-bitfield initial (make-bitfield-bytes #{0 1 2} 100))]]
      (doseq [state states]
        (is (s/valid? ::peer-state/peer-state state))))))

;; ============================================================================
;; BitSet Round-trip Tests
;; ============================================================================

(deftest bitfield-roundtrip-test
  (testing "Bitfield bytes -> BitSet -> bytes roundtrip"
    (let [total-pieces 100
          pieces #{0 5 10 50 99}
          original-bytes (make-bitfield-bytes pieces total-pieces)
          bitset (peer-state/bitfield-from-bytes original-bytes total-pieces)
          reconstructed-bytes (peer-state/bitfield-to-bytes bitset total-pieces)]
      ;; Check that we can reconstruct the same bytes
      (is (= (seq original-bytes) (seq reconstructed-bytes))
          "Bitfield roundtrip should preserve all data"))))
