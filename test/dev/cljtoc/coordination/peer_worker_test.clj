(ns dev.cljtoc.coordination.peer-worker-test
  "Peer worker handshake tests through the INetworkPort seam (issue #4)."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [dev.cljtoc.coordination.peer-worker :as peer-worker]
            [dev.cljtoc.test-doubles.network :as mock-net]))

(defn- take-timeout
  [ch timeout-ms]
  (let [timeout-ch (async/timeout timeout-ms)]
    (async/alt!!
      ch ([event] event)
      timeout-ch :timeout)))

(defn- test-info-hash
  []
  (byte-array (repeat 20 (byte 1))))

(defn- test-peer-id
  []
  (byte-array (repeat 20 (byte 2))))

(deftest verify-handshake-test
  (testing "matching info-hash returns {:ok peer-handshake}"
    (let [info-hash (test-info-hash)
          peer-hs {:info-hash info-hash :peer-id (test-peer-id)}]
      (is (= {:ok peer-hs}
             (peer-worker/verify-handshake info-hash peer-hs)))))
  (testing "mismatched info-hash returns {:error :info-hash-mismatch}"
    (let [result (peer-worker/verify-handshake
                  (test-info-hash)
                  {:info-hash (byte-array (repeat 20 (byte 9)))
                   :peer-id (test-peer-id)})]
      (is (= :info-hash-mismatch (:error result))))))

(deftest handshake-error-emits-peer-disconnected-test
  (testing "handshake failure surfaces as :peer-disconnected via the port"
    (let [net (mock-net/create {:handshake-response {:error :timeout
                                                     :message "Handshake read timed out"}})
          events-ch (async/chan 10)]
      (peer-worker/run-peer net (test-info-hash) (test-peer-id)
                            "127.0.0.1:6881" 4 events-ch)
      (let [event (take-timeout events-ch 2000)]
        (is (not= :timeout event) "worker produced no event")
        (is (= :peer-disconnected (:type event)))
        (is (= "127.0.0.1:6881" (:address event)))))))

(deftest handshake-ok-emits-peer-connected-test
  (testing "matching info-hash surfaces as :peer-connected via the port"
    (let [info-hash (test-info-hash)
          net (mock-net/create {:handshake-response {:ok {:info-hash info-hash
                                                          :peer-id (test-peer-id)}}
                                :receive-responses (atom [{:error :disconnected
                                                           :message "peer went away"}])})
          events-ch (async/chan 10)
          done-ch (peer-worker/run-peer net info-hash (test-peer-id)
                                        "127.0.0.1:6881" 4 events-ch)
          connected-event (take-timeout events-ch 2000)
          disconnected-event (take-timeout events-ch 2000)
          worker-exit (take-timeout done-ch 2000)]
      (is (not= :timeout connected-event) "worker produced no event")
      (is (= :peer-connected (:type connected-event)))
      (is (= "127.0.0.1:6881" (:address connected-event)))
      (is (= :peer-disconnected (:type disconnected-event))
          "scripted receive error ends the read loop")
      (is (not= :timeout worker-exit) "worker thread terminated, nothing leaked"))))

(deftest handshake-info-hash-mismatch-emits-peer-disconnected-test
  (testing "mismatched info-hash surfaces as :peer-disconnected"
    (let [net (mock-net/create {:handshake-response {:ok {:info-hash (byte-array (repeat 20 (byte 9)))
                                                          :peer-id (test-peer-id)}}})
          events-ch (async/chan 10)]
      (peer-worker/run-peer net (test-info-hash) (test-peer-id)
                            "127.0.0.1:6881" 4 events-ch)
      (let [event (take-timeout events-ch 2000)]
        (is (not= :timeout event) "worker produced no event")
        (is (= :peer-disconnected (:type event)))
        (is (= "Info hash mismatch" (:reason event)))))))
