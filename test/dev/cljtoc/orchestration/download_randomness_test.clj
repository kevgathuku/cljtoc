(ns dev.cljtoc.orchestration.download-randomness-test
  "Slice 4 of #29 (legacy file, see `network-peer-id-test` for the
   protocol-driven version after PR #74 review).

   These tests originally drove `#'handshake-peer-id` directly. That
   private fn now routes through `INetworkPort/peer-id` instead of a
   record-field access; the same assertions now drive the protocol
   method via the same helper. The old surface is kept here so the
   diff against main is minimal; new assertions about protocol
   compliance live in `network-peer-id-test`."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.orchestration.download]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness :as randomness]
            [dev.cljtoc.test-doubles.network :as mock-network]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.utils :as utils]))

(defn- scripted-peer-id
  [bytes]
  (atom [bytes]))

(deftest handshake-peer-id-uses-network-port-randomness-test
  (testing "real NetworkPort: handshake peer-id comes from injected randomness"
    ;; Slice 4 originally drove `#'handshake-peer-id` directly. The fn
    ;; now routes through the protocol method, so drive that.
    (let [scripted-bytes [10 20 30 40 50 60 70 80 90 100
                          111 122 133 144 155 166 177 188 199 210]
          network-port (network-impl/create {:randomness-port
                                             (mock-randomness/->MockRandomness
                                              (scripted-peer-id scripted-bytes))})]
      (is (utils/bytes-equal?
           (byte-array scripted-bytes)
           (network/peer-id network-port)))))

  (testing "real NetworkPort: two calls produce different bytes (real randomness works)"
    ;; Compare contents, not Java identity — two freshly-allocated
    ;; `(byte-array 20)` calls are different references even when both
    ;; hold zeros, so `not=` would pass vacuously.
    (let [network-port (network-impl/create)
          a (network/peer-id network-port)
          b (network/peer-id network-port)]
      (is (not (utils/bytes-equal? a b)))))

  (testing "mock NetworkPort: handshake peer-id comes through the port"
    ;; The mock network port carries the injected randomness port too,
    ;; via the same :randomness-port constructor arg. This pins the
    ;; wiring on both real and mock sides.
    (let [scripted-bytes (vec (range 20))
          mock-port (mock-network/create
                     {:randomness-port
                      (mock-randomness/->MockRandomness
                       (scripted-peer-id scripted-bytes))})]
      (is (utils/bytes-equal?
           (byte-array scripted-bytes)
           (network/peer-id mock-port)))))

  (testing "a network port without an injected randomness port defaults to a real one"
    ;; network-impl/create defaults :randomness-port to SecureRandomRandomness
    ;; when not supplied. This means peer-id works without explicit
    ;; injection — the production call site in core.clj relies on this.
    (let [network-port (network-impl/create)]
      (is (satisfies? randomness/IRandomnessPort (:randomness-port network-port)))
      (is (= 20 (count (network/peer-id network-port)))))))