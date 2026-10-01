(ns dev.cljtoc.ports.network-peer-id-test
  "Regression test for the protocol-level `peer-id` method.

   Before this slice, the call sites read `(:randomness-port network-port)`
   — a record-field access that breaks for any `reify` implementation of
   `INetworkPort` that does not happen to expose the same field. The
   protocol method `peer-id` is the contract; this test asserts that a
   custom reify impl is reachable through `INetworkPort`.

   Per PR #39's lesson (mock-only coverage never proves the real port
   dispatches): the reify impl here is its own thing — neither real nor
   mock — and proves the protocol is the boundary."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.orchestration.download]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness :as randomness]
            [dev.cljtoc.test-doubles.network :as mock-network]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.utils :as utils]))

(defn- reify-randomness
  "Build a reify IRandomnessPort that returns `bytes` for any call."
  [bytes]
  (reify randomness/IRandomnessPort
    (random-bytes [_ n]
      (byte-array (take n bytes)))))

(defn- reify-network
  "Build a minimal reify INetworkPort whose peer-id delegates to the
   supplied randomness port. Other methods are stubs."
  [custom-randomness]
  (reify network/INetworkPort
    (peer-id [_] (randomness/random-bytes custom-randomness 20))
    (connect-peer [_ _] {:ok {}})
    (send-message [_ _ _] {:ok :sent})
    (receive-message [_ _] {:ok {:type :keep-alive}})
    (receive-handshake [_ _] {:ok {}})
    (close-peer [_ _] nil)))

(deftest inetworkport-peer-id-contract-test
  (testing "real NetworkPort: peer-id comes from the injected randomness port"
    (let [scripted (atom [[99 99 99 99 99 99 99 99 99 99
                           99 99 99 99 99 99 99 99 99 99]])
          port (network-impl/create {:randomness-port
                                     (mock-randomness/->MockRandomness scripted)})
          generated (network/peer-id port)]
      (is (utils/bytes-equal? (byte-array (repeat 20 99)) generated))))

  (testing "mock NetworkPort: peer-id comes from the injected randomness port"
    (let [scripted (atom [[11 22 33 44 55 66 77 88 99 0
                           11 22 33 44 55 66 77 88 99 0]])
          port (mock-network/create
                {:randomness-port
                 (mock-randomness/->MockRandomness scripted)})
          generated (network/peer-id port)]
      (is (utils/bytes-equal? (byte-array [11 22 33 44 55 66 77 88 99 0
                                           11 22 33 44 55 66 77 88 99 0])
                              generated))))

  (testing "a custom reify INetworkPort can satisfy peer-id without a :randomness-port field"
    ;; The whole point of the protocol method: a port implementation
    ;; that does not expose :randomness-port (because it draws randomness
    ;; from somewhere else entirely) is still usable by callers. The
    ;; caller never reaches for a record field.
    (let [scripted [42 42 42 42 42 42 42 42 42 42
                    42 42 42 42 42 42 42 42 42 42]
          custom-randomness (reify-randomness scripted)
          custom-port (reify-network custom-randomness)]
      (is (utils/bytes-equal? (byte-array (repeat 20 42))
                              (network/peer-id custom-port))))))

(deftest handshake-peer-id-routes-through-protocol-test
  (testing "orchestration/download/handshake-peer-id uses the protocol method"
    ;; This pins the wiring at the run-download call site: the helper
    ;; reads through INetworkPort/peer-id, not a record field.
    (let [scripted [42 42 42 42 42 42 42 42 42 42
                    42 42 42 42 42 42 42 42 42 42]
          custom-randomness (reify-randomness scripted)
          custom-port (reify-network custom-randomness)
          helper (#'dev.cljtoc.orchestration.download/handshake-peer-id custom-port)]
      (is (utils/bytes-equal? (byte-array (repeat 20 42)) helper)))))