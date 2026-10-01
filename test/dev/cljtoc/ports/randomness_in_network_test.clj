(ns dev.cljtoc.ports.randomness-in-network-test
  "Slice 3 of #29: assert that the real NetworkPort routes peer-id generation
   through an `IRandomnessPort` rather than `SecureRandom` directly.

   Per PR #39 lesson: protocol/wiring changes must update every extend-type
   impl; mock-only coverage never proves the real port dispatches. The
   wired-through port is the same way: a test that asserts the mock port
   can script peer-ids proves nothing about the real port. These tests
   drive the real `network-impl` directly.

   Per issue #29's `decision` (ADR-0011): only the call site changes; the
   shape of the announce request is unchanged."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness :as randomness]
            [dev.cljtoc.ports.randomness-impl :as randomness-impl]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.utils :as utils]))

(defn- scripted-peer-id
  "Build a single-element script queue holding the 20-byte peer-id."
  [bytes]
  (atom [bytes]))

(deftest generate-peer-id-uses-injected-port-test
  (testing "generate-peer-id returns bytes from the injected port, on the real port"
    ;; Access the private fn via #' so this stays an internal-seam test
    ;; that does not widen the public surface for the sake of testing.
    (let [scripted (scripted-peer-id [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20])
          port (mock-randomness/->MockRandomness scripted)]
      (is (utils/bytes-equal?
           (byte-array [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20])
           (#'network-impl/generate-peer-id port)))))

  (testing "two consecutive peer-ids are different (real randomness works through the port)"
    (let [port (randomness-impl/create)]
      (is (not= (#'network-impl/generate-peer-id port)
                (#'network-impl/generate-peer-id port))))))

(deftest real-network-port-uses-injected-randomness-port-test
  (testing "the real NetworkPort accepts :randomness-port in adapter opts"
    (let [scripted (scripted-peer-id [42 42 42 42 42 42 42 42 42 42
                                      42 42 42 42 42 42 42 42 42 42])
          port (network-impl/create {:randomness-port
                                     (mock-randomness/->MockRandomness scripted)})]
      (is (satisfies? network/INetworkPort port))))

  (testing "the real port's generate-peer-id comes from the injected port, not SecureRandom"
    (let [scripted (scripted-peer-id [99 99 99 99 99 99 99 99 99 99
                                      99 99 99 99 99 99 99 99 99 99])
          randomness-port (mock-randomness/->MockRandomness scripted)
          port (network-impl/create {:randomness-port randomness-port})]
      ;; The real NetworkPort carries the injected randomness port on the
      ;; record (not in :config, so the user's opts round-trip exactly).
      ;; Drive generate-peer-id directly via the private fn rather than a
      ;; full HTTP round-trip — announce always reaches a tracker (PR #44
      ;; finding), and the integration test for the wiring belongs to
      ;; issue #28's supervisor slice.
      (is (utils/bytes-equal?
           (byte-array [99 99 99 99 99 99 99 99 99 99
                        99 99 99 99 99 99 99 99 99 99])
           (#'network-impl/generate-peer-id (:randomness-port port))))))

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