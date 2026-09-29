(ns dev.cljtoc.coordination.peer-connection-test
  "Peer connection seam tests (issue #64): one module owning handshake,
   availability, choke/interest posture, and block traffic over
   INetworkPort. Pure core first; effectful connect/close in later slices."
  (:require [clojure.test :refer :all]
            [dev.cljtoc.coordination.peer-connection :as peer-connection]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
            [dev.cljtoc.test-utils :as test-utils]))

(defn- test-info-hash
  []
  (byte-array (repeat 20 (byte 1))))

(defn- test-peer-id
  []
  (byte-array (repeat 20 (byte 2))))

(deftest verify-handshake-test
  (testing "matching info-hash returns {:ok peer-handshake}"
    (let [info-hash (test-info-hash)
          peer-handshake {:info-hash info-hash :peer-id (test-peer-id)}]
      (is (= {:ok peer-handshake}
             (peer-connection/verify-handshake info-hash peer-handshake)))))
  (testing "mismatched info-hash returns {:error :info-hash-mismatch}"
    (let [result (peer-connection/verify-handshake
                  (test-info-hash)
                  {:info-hash (byte-array (repeat 20 (byte 9)))
                   :peer-id (test-peer-id)})]
      (is (= :info-hash-mismatch (:error result))))))

(deftest initial-state-test
  (testing "a fresh connection starts choked-by-peer, interested, nothing known"
    (let [state (peer-connection/initial-state 4)]
      (is (true? (:am-choking state)))
      (is (true? (:am-interested state)))
      (is (true? (:peer-choking state)))
      (is (false? (:peer-interested state)))
      (is (nil? (:bitfield state)))
      (is (= 4 (:total-pieces state))))))

(deftest on-message-test
  (testing "Unchoke opens the peer; Choke closes it again"
    (let [state (peer-connection/initial-state 4)
          unchoked (peer-connection/on-message state (peer/->Unchoke))]
      (is (false? (:peer-choking unchoked)))
      (is (true? (:peer-choking (peer-connection/on-message unchoked (peer/->Choke)))))))
  (testing "Have marks one piece available"
    (let [state (peer-connection/on-message
                 (peer-connection/initial-state 4) (peer/->Have 2))]
      (is (true? (peer-state/peer-has-piece? state 2)))
      (is (false? (peer-state/peer-has-piece? state 0)))))
  (testing "Bitfield replaces availability wholesale"
    (let [state (peer-connection/on-message
                 (peer-connection/initial-state 8)
                 (peer/->Bitfield (byte-array [(unchecked-byte 0xA0)])))]
      (is (= 2 (peer-state/peer-piece-count state)))))
  (testing "data-plane messages leave posture untouched"
    (let [state (peer-connection/on-message
                 (peer-connection/initial-state 4) (peer/->Unchoke))]
      (is (= state (peer-connection/on-message state (peer/->KeepAlive))))
      (is (= state (peer-connection/on-message
                    state (peer/->Request 0 0 16384)))))))

(deftest request-gate-test
  (testing "a fresh connection may not request; an unchoked one may"
    (let [fresh (peer-connection/initial-state 4)]
      (is (false? (peer-connection/can-request? fresh)))
      (is (true? (-> fresh
                     (peer-connection/on-message (peer/->Unchoke))
                     (peer-connection/can-request?)))))))

;; Only verify-handshake is pinned generatively: initial-state takes a
;; plain pos-int but on-message/can-request? take a PeerState whose
;; :bitfield is a java.util.BitSet with no generator (same reason
;; peer_state_test never stest/checks those fns). Posture branches stay
;; covered by the example tests above.
(deftest fdef-specs-hold-generatively-test
  (testing "verify-handshake fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.coordination.peer-connection/verify-handshake]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))
