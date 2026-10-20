(ns dev.cljtoc.supervision.watchdog-test
  "Tests for the watchdog that fires when a download stops moving.

   Seam: the clock is injected, so the stall deadline is reached without
   waiting for it."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.supervision.restart :as restart]
            [dev.cljtoc.supervision.watchdog :as watchdog]
            [dev.cljtoc.test-utils :as test-utils]))

(defn- wait-for
  "Wait until pred holds, or give up after timeout-ms."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest progress-marker-counts-blocks-and-peers-test
  (testing "the marker moves with the blocks received and the peers connected"
    (let [state {:blocks-received {"a" [1 2] "b" [3]}
                 :active-peers {"a" {} "b" {} "c" {}}}]
      (is (= [3 3] (watchdog/progress-marker state)))
      (is (= [0 0] (watchdog/progress-marker {}))
          "an empty state has neither blocks nor peers"))))

(deftest progress-marker-counts-verified-pieces-test
  (testing "a piece that verifies moves the marker, even when its buffer is cleared"
    (let [state {:blocks-received {"a" []}
                 :active-peers {"a" {}}
                 :download {:piece-state {:verified #{0}}}}]
      (is (= [1 1] (watchdog/progress-marker state))
          "the verified piece counts, not the buffer that verification cleared"))))

(deftest the-watchdog-stays-quiet-while-pieces-verify-test
  (testing "a download whose pieces fit in one block keeps the marker moving"
    (let [clock (atom 0)
          state (atom {:blocks-received {"a" []}
                       :active-peers {"a" {}}
                       :download {:piece-state {:verified #{}}}})
          fired (atom false)
          stop (watchdog/start
                {:state state
                 :now (fn [] (swap! clock + 1))
                 :deadline-ms 10000
                 :poll-ms 10
                 :fire (fn [] (reset! fired true))})]
      (try
        ;; Each poll verifies one piece and clears the buffer, the way a
        ;; one-block torrent does.
        (dotimes [piece 30]
          (swap! state assoc :download {:piece-state {:verified (set (range piece))}})
          (Thread/sleep 5))
        (is (not @fired) "the watchdog stayed quiet while the pieces verified")
        (finally
          (stop))))))

(deftest the-deadline-fits-inside-the-restart-window-test
  (testing "the restart budget still bounds a download that never moves"
    (let [deadline watchdog/default-stall-deadline-ms
          budget restart/default-budget
          capacity (quot (:window-ms budget) deadline)]
      (is (>= capacity (inc (:limit budget)))
          (str "the window holds " capacity " restarts per deadline, and the "
               "budget gives up only when it holds at least "
               (inc (:limit budget)) ", so the deadline fits the window")))))

(deftest stalled?-needs-a-stall-past-the-deadline-test
  (testing "the download has to stand still for longer than the deadline"
    (is (not (watchdog/stalled? [0 1] [0 1] 0 5000 15000))
        "the stall is shorter than the deadline")
    (is (watchdog/stalled? [0 1] [0 1] 0 20000 15000)
        "the stall is longer than the deadline")
    (is (not (watchdog/stalled? [0 2] [0 1] 0 20000 15000))
        "the progress moved, so the download is not stalled")))

(deftest watchdog-specs-hold-generatively-test
  (testing "the generative check holds over the watchdog's pure fdefs"
    (is (= {} (test-utils/check-fdefs [`watchdog/progress-marker
                                       `watchdog/stalled?]
                                      100)))))

(deftest the-watchdog-fires-when-the-download-stands-still-test
  (testing "a download that stops moving fires the watchdog"
    (let [clock (atom 0)
          state (atom {:blocks-received {} :active-peers {"a" {}}})
          fired (atom false)
          stop (watchdog/start
                {:state state
                 ;; Every look moves the clock a second, so the deadline is
                 ;; reached on the first poll.
                 :now (fn [] (swap! clock + 1000))
                 :deadline-ms 100
                 :poll-ms 10
                 :fire (fn [] (reset! fired true))})]
      (try
        (is (wait-for (fn [] @fired) 2000) "the watchdog fired")
        (finally
          (stop))))))

(deftest the-watchdog-stays-quiet-while-the-download-moves-test
  (testing "a download that keeps receiving blocks does not fire the watchdog"
    (let [clock (atom 0)
          state (atom {:blocks-received {} :active-peers {"a" {}}})
          fired (atom false)
          stop (watchdog/start
                {:state state
                 :now (fn [] (swap! clock + 1))
                 :deadline-ms 10000
                 :poll-ms 10
                 :fire (fn [] (reset! fired true))})]
      (try
        (dotimes [block 50]
          (reset! state {:blocks-received {"a" (vec (range block))}
                         :active-peers {"a" {}}})
          (Thread/sleep 5))
        (is (not @fired) "the watchdog stayed quiet while the download moved")
        (finally
          (stop))))))

(deftest stopping-the-watchdog-ends-it-test
  (testing "the stop fn ends the watchdog without firing it"
    (let [clock (atom 0)
          fired (atom false)
          stop (watchdog/start
                {:state (atom {:blocks-received {} :active-peers {"a" {}}})
                 :now (fn [] (swap! clock + 1000))
                 :deadline-ms 100
                 :poll-ms 10
                 :fire (fn [] (reset! fired true))})]
      (stop)
      (Thread/sleep 50)
      (is (not @fired) "the watchdog did not fire after it was stopped"))))
