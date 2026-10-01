(ns dev.cljtoc.orchestration.download-randomness-test
  "Slice 4 of #29: assert that `run-download`'s handshake peer-id comes
   from the network port's injected `IRandomnessPort`, not `SecureRandom`
   directly.

   Per PR #39 lesson (mock-only coverage never proves the real port
   dispatches): the real `network-impl/create` carries the injected
   randomness port on the record, and `run-download` reads it from
   there. The mock port also carries it. Both paths are pinned."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.orchestration.download]
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
    ;; #29 (handshake-peer-id) reads (:randomness-port network-port).
    ;; With a scripted mock port, the bytes flow through unchanged.
    (let [scripted-bytes [10 20 30 40 50 60 70 80 90 100
                          111 122 133 144 155 166 177 188 199 210]
          network-port (network-impl/create {:randomness-port
                                             (mock-randomness/->MockRandomness
                                              (scripted-peer-id scripted-bytes))})]
      (is (utils/bytes-equal?
           (byte-array scripted-bytes)
           (#'dev.cljtoc.orchestration.download/handshake-peer-id network-port)))))

  (testing "real NetworkPort: two calls produce different bytes (real randomness works)"
    (let [network-port (network-impl/create)]
      (is (not= (#'dev.cljtoc.orchestration.download/handshake-peer-id network-port)
                (#'dev.cljtoc.orchestration.download/handshake-peer-id network-port)))))

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
           (#'dev.cljtoc.orchestration.download/handshake-peer-id mock-port)))))

  (testing "a network port without an injected randomness port defaults to a real one"
    ;; network-impl/create defaults :randomness-port to SecureRandomRandomness
    ;; when not supplied. This means handshake-peer-id works without explicit
    ;; injection — the production call site in core.clj relies on this.
    (let [network-port (network-impl/create)]
      (is (satisfies? randomness/IRandomnessPort (:randomness-port network-port)))
      (is (= 20 (count (#'dev.cljtoc.orchestration.download/handshake-peer-id network-port)))))))