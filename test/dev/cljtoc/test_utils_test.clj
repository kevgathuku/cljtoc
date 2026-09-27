(ns dev.cljtoc.test-utils-test
  "Tests for the shared test helpers themselves. A helper the contract tests
   rely on is worth pinning: an over-permissive predicate there reports a
   non-conforming port as valid, and every test built on it inherits the
   blind spot."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [dev.cljtoc.test-utils :refer [an-envelope? channel?]]))

(deftest an-envelope-accepts-exactly-one-result-key-test
  (testing "a map carrying exactly one of :ok or :error is an envelope"
    (is (an-envelope? {:ok 1}))
    (is (an-envelope? {:error :nope})))
  (testing "both keys at once is not an envelope, whatever the payload.
            A map that answers ok and error simultaneously cannot be
            destructured without the caller picking a winner by accident."
    (is (not (an-envelope? {:ok 1 :error :nope}))))
  (testing "neither key is not an envelope either: an empty result map
            forces every caller to re-check before trusting it"
    (is (not (an-envelope? {})))
    (is (not (an-envelope? {:message "no reason given"}))))
  (testing "a non-map is never an envelope"
    (is (not (an-envelope? nil)))
    (is (not (an-envelope? :sent)))
    (is (not (an-envelope? [:ok 1])))))

(deftest channel-predicate-sees-core-async-channels-test
  (testing "channel? recognises something a blocking take could read from,
            which is what the port contract tests are asserting against"
    (let [ch (async/chan 1)]
      (is (channel? ch))
      (is (not (channel? {:ok 1})))
      (is (not (channel? nil))))))
