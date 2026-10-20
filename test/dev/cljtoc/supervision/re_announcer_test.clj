(ns dev.cljtoc.supervision.re-announcer-test
  "Tests for the re-announcer that asks the trackers for peers again.

   Seam: the announce is injected, so no tracker is contacted."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.supervision.re-announcer :as re-announcer]))

(deftest the-request-returns-the-announced-peers-test
  (testing "the re-announcer answers with the peers the trackers named"
    (let [asked (atom 0)
          re-announcer (re-announcer/start
                        {:announce (fn []
                                     (swap! asked inc)
                                     {:ok #{"10.0.0.9:6881" "10.0.0.9:6882"}})})
          request (:request re-announcer)]
      (try
        (is (= #{"10.0.0.9:6881" "10.0.0.9:6882"} (request)))
        (is (= 1 @asked) "the trackers were asked once")
        (finally
          ((:stop re-announcer)))))))

(deftest a-failed-announce-crashes-the-request-test
  (testing "a failed announce is a crash: the download supervisor restarts the subtree"
    (let [re-announcer (re-announcer/start
                        {:announce (fn []
                                     {:error :tracker-error
                                      :message "the tracker refused"})})
          request (:request re-announcer)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"re-announce failed"
                              (request)))
        (finally
          ((:stop re-announcer)))))))

(deftest the-re-announcer-gives-up-after-its-budget-test
  (testing "the trackers cannot be asked forever"
    (let [asked (atom 0)
          re-announcer (re-announcer/start
                        {:announce (fn []
                                     (swap! asked inc)
                                     {:ok #{"10.0.0.9:6881"}})
                         :max-announces 2})
          request (:request re-announcer)]
      (try
        (is (= #{"10.0.0.9:6881"} (request)))
        (is (= #{"10.0.0.9:6881"} (request)))
        (is (empty? (request))
            "the third ask is not made: the download gives up instead")
        (is (= 2 @asked) "the trackers were asked twice")
        (finally
          ((:stop re-announcer)))))))

(deftest stopping-the-re-announcer-ends-it-test
  (testing "the stop fn ends the re-announcer"
    (let [asked (atom 0)
          re-announcer (re-announcer/start
                        {:announce (fn []
                                     (swap! asked inc)
                                     {:ok []})})]
      ((:stop re-announcer))
      (is (zero? @asked) "the re-announcer stopped without announcing"))))
