(ns dev.cljtoc.coordination.peer-connection-test
  "Peer connection seam tests (issue #64): one module owning handshake,
   availability, choke/interest posture, and block traffic over
   INetworkPort. Pure core first; effectful connect/close in later slices."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.coordination.peer-connection :as peer-connection]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]
            [dev.cljtoc.test-doubles.network :as mock-net]
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

(deftest connect-test
  (testing "matching handshake connects: ready posture, Interested sent"
    (let [info-hash (test-info-hash)
          sent (atom [])
          net (mock-net/create {:handshake-response {:ok {:info-hash info-hash
                                                          :peer-id (test-peer-id)}}
                                :on-send (fn [_peer message] (swap! sent conj message))})
          result (peer-connection/connect net info-hash (test-peer-id)
                                          "127.0.0.1:6881" 4)]
      (is (some? (:ok result)))
      (let [{:keys [peer-data peer-state]} (:ok result)]
        (is (= "127.0.0.1:6881" (:address peer-data)))
        (is (true? (:am-interested peer-state)))
        (is (true? (:peer-choking peer-state))))
      (is (= 2 (count @sent)) "handshake bytes, then Interested")
      (is (instance? dev.cljtoc.protocol.peer.Interested
                     (:ok (peer/parse-message (second @sent))))
          "second send parses back as Interested")))
  (testing "mismatched info-hash refuses the connection"
    (let [net (mock-net/create {:handshake-response {:ok {:info-hash (byte-array (repeat 20 (byte 9)))
                                                          :peer-id (test-peer-id)}}})
          result (peer-connection/connect net (test-info-hash) (test-peer-id)
                                          "127.0.0.1:6881" 4)]
      (is (= :info-hash-mismatch (:error result)))))
  (testing "a failed handshake read surfaces the port error"
    (let [net (mock-net/create {:handshake-response {:error :timeout
                                                     :message "Handshake read timed out"}})
          result (peer-connection/connect net (test-info-hash) (test-peer-id)
                                          "127.0.0.1:6881" 4)]
      (is (= :timeout (:error result))))))

(deftest close-test
  (testing "close releases the connection through the port"
    (let [info-hash (test-info-hash)
          net (mock-net/create {:handshake-response {:ok {:info-hash info-hash
                                                          :peer-id (test-peer-id)}}})
          peer-data (:peer-data (:ok (peer-connection/connect net info-hash (test-peer-id)
                                                              "127.0.0.1:6881" 4)))]
      (is (nil? (peer-connection/close net peer-data)))
      (is (contains? (mock-net/closed-peers net) peer-data)))))

(deftest request-test
  (testing "a choked connection refuses block traffic"
    (let [result (peer-connection/request (peer-connection/initial-state 4)
                                          [{:piece-index 0 :offset 0 :length 16384}])]
      (is (= :not-requestable (:error result)))))
  (testing "an unchoked connection yields per-block request bytes"
    (let [ready (peer-connection/on-message
                 (peer-connection/initial-state 4) (peer/->Unchoke))
          blocks [{:piece-index 0 :offset 0 :length 16384}
                  {:piece-index 0 :offset 16384 :length 16384}]
          result (peer-connection/request ready blocks)]
      (is (= 2 (count (:ok result))))
      (let [first-message (:ok (peer/parse-message (first (:ok result))))]
        (is (instance? dev.cljtoc.protocol.peer.Request first-message))
        (is (= 0 (:piece-index first-message)))
        (is (= 0 (:begin first-message)))
        (is (= 16384 (:length first-message)))))))

;; Only verify-handshake is pinned generatively: initial-state takes a
;; plain pos-int but on-message/can-request?/request take a PeerState
;; whose :bitfield is a java.util.BitSet with no generator (same reason
;; peer_state_test never stest/checks those fns). Posture branches stay
;; covered by the example tests above.
(deftest fdef-specs-hold-generatively-test
  (testing "verify-handshake fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.coordination.peer-connection/verify-handshake]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))
