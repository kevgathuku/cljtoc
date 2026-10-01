(ns dev.cljtoc.ports.randomness-test
  "Tests for the randomness port protocol and its real implementation.

   Per PR #39's lesson (mock-only coverage never proves the real port
   dispatches), every IRandomnessPort method has both a real-port dispatch
   test and a mock test."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.ports.randomness :as randomness]
            [dev.cljtoc.ports.randomness-impl :as randomness-impl]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.utils :as utils]))

(defn- scripted-mock
  [bytes-seqs]
  (mock-randomness/->MockRandomness (atom bytes-seqs)))

(deftest real-randomness-port-dispatches
  (testing "the real port satisfies IRandomnessPort"
    (let [port (randomness-impl/create)]
      (is (satisfies? randomness/IRandomnessPort port))))

  (testing "random-bytes returns a byte-array of the requested length"
    (let [port (randomness-impl/create)
          bytes (randomness/random-bytes port 20)]
      (is (bytes? bytes))
      (is (= 20 (count bytes)))))

  (testing "two calls produce different bytes (sanity check on randomness)"
    (let [port (randomness-impl/create)
          a (randomness/random-bytes port 20)
          b (randomness/random-bytes port 20)]
      ;; Bytes come from java.security.SecureRandom; collision probability
      ;; for two 20-byte draws is 2^-160. A failure here means the
      ;; implementation is not actually random. `not=` on byte arrays
      ;; compares by Java identity — two freshly-allocated `(byte-array 20)`
      ;; calls are different objects even when both hold zeros, so the
      ;; test would pass vacuously. `not (bytes-equal?)` compares content.
      (is (not (utils/bytes-equal? a b))))))

(deftest mock-randomness-port-dispatches
  (testing "the mock satisfies IRandomnessPort"
    (let [port (scripted-mock [])]
      (is (satisfies? randomness/IRandomnessPort port))))

  (testing "a scripted byte sequence is returned in order, exhausting when used up"
    (let [port (scripted-mock [[1 2 3 4] [5 6 7 8]])]
      (is (utils/bytes-equal? (byte-array [1 2 3 4]) (randomness/random-bytes port 4)))
      (is (utils/bytes-equal? (byte-array [5 6 7 8]) (randomness/random-bytes port 4)))))

  (testing "two mocks with the same script produce identical bytes (stable peer-IDs in tests)"
    ;; This is the contract that lets tests pin peer-id values without
    ;; threading a randomness port through every helper. Without it, every
    ;; assertion that involves a peer-id needs to capture and replay.
    (let [port-a (scripted-mock [[1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20]])
          port-b (scripted-mock [[1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20]])]
      (is (utils/bytes-equal? (randomness/random-bytes port-a 20)
                              (randomness/random-bytes port-b 20)))))

  (testing "exhausted script returns zeros (predictable failure beats mystery)"
    (let [port (scripted-mock [[1 2 3 4]])]
      (randomness/random-bytes port 4)
      (is (utils/bytes-equal? (byte-array [0 0 0 0 0 0 0 0])
                              (randomness/random-bytes port 8)))))

  (testing "a script shorter than the request throws (contract violation is loud)"
    ;; A test that scripts too few bytes is itself buggy: the protocol
    ;; promises exactly `n` bytes come out. Silently padding or returning
    ;; fewer bytes would let a peer-id test pass with the wrong length,
    ;; so the mock refuses the call instead.
    (let [port (scripted-mock [[1 2 3]])
          err (try (randomness/random-bytes port 4) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? err))
      (is (re-find #"under-supplied" (ex-message err)))
      (is (= 4 (:asked (ex-data err))))
      (is (= 3 (:provided (ex-data err)))))))