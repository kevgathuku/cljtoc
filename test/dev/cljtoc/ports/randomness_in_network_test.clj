(ns dev.cljtoc.ports.randomness-in-network-test
  "Slice 3 of #29 (legacy file, see `network-peer-id-test` for the
   protocol-driven version after PR #74 review).

   These tests originally drove `#'network-impl/generate-peer-id`
   directly. That private fn was inlined into the `INetworkPort/peer-id`
   method when the protocol was extended; the same tests now drive
   `(network/peer-id port)` instead. The old test surface is kept here
   so the diff against main is minimal, but new assertions about
   protocol compliance live in `network-peer-id-test`."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness :as randomness]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.utils :as utils]))

(defn- scripted-peer-id
  "Build a single-element script queue holding the 20-byte peer-id."
  [bytes]
  (atom [bytes]))

(deftest peer-id-comes-from-injected-port-test
  (testing "the real port's peer-id comes from the injected port, on the real port"
    ;; Drives the protocol method directly. The old test surface accessed
    ;; `#'network-impl/generate-peer-id` (a private helper that was
    ;; inlined into `peer-id` after PR #74 review); the protocol method
    ;; is now the seam, so drive that.
    (let [scripted (scripted-peer-id [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20])
          port (mock-randomness/->MockRandomness scripted)
          network-port (network-impl/create {:randomness-port port})]
      (is (utils/bytes-equal?
           (byte-array [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20])
           (network/peer-id network-port)))))

  (testing "two consecutive peer-ids are different (real randomness works through the port)"
    ;; Compare contents, not Java identity — two freshly-allocated
    ;; `(byte-array 20)` calls are different references even when both
    ;; hold zeros, so `not=` would pass vacuously.
    (let [network-port (network-impl/create)
          a (network/peer-id network-port)
          b (network/peer-id network-port)]
      (is (not (utils/bytes-equal? a b))))))

(deftest real-network-port-uses-injected-randomness-port-test
  (testing "the real NetworkPort accepts :randomness-port in adapter opts"
    (let [port (network-impl/create {:randomness-port
                                     (mock-randomness/->MockRandomness
                                      (scripted-peer-id (repeat 20 42)))})]
      (is (satisfies? network/INetworkPort port))))

  (testing "the real port's peer-id comes from the injected port, not SecureRandom"
    (let [scripted (scripted-peer-id [99 99 99 99 99 99 99 99 99 99
                                      99 99 99 99 99 99 99 99 99 99])
          port (network-impl/create {:randomness-port
                                     (mock-randomness/->MockRandomness scripted)})]
      (is (utils/bytes-equal?
           (byte-array (repeat 20 99))
           (network/peer-id port)))))

  (testing "the real NetworkPort defaults :randomness-port to a fresh SecureRandomRandomness"
    ;; Per constitution II: randomness is an injected port. The default
    ;; value still uses the port — it just constructs it internally so the
    ;; production call site (`network-impl/create`) does not have to.
    (let [port (network-impl/create)]
      (is (satisfies? randomness/IRandomnessPort (:randomness-port port))))))

(deftest adapter-config-validates-randomness-port-test
  (testing "a present :randomness-port must satisfy IRandomnessPort"
    (let [non-port "not a port"]
      (is (thrown? Throwable (network-impl/create {:randomness-port non-port})))))

  (testing "an absent :randomness-port is fine (default constructed)"
    (is (some? (network-impl/create)))))