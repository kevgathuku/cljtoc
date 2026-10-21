(ns dev.cljtoc.supervision.restart-test
  "Tests for the restart policy the supervisor layers share.

   Seam: the child is a plain fn and the clock is a plain fn, so the
   whole policy runs with no threads and no ports."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.supervision.restart :as restart]
            [dev.cljtoc.test-utils :as test-utils]))

(def ^:private window-ms
  60000)

(defn- budget
  ([limit]
   (budget limit 0))
  ([limit now-ms]
   {:limit limit
    :window-ms window-ms
    :now (fn [] now-ms)}))

(deftest supervise-returns-the-child-result-test
  (testing "a child that returns is never restarted"
    (let [calls (atom 0)
          result (restart/supervise (fn []
                                      (swap! calls inc)
                                      :done)
                                    (budget 3))]
      (is (= {:ok :done} result))
      (is (= 1 @calls) "the child ran once"))))

(deftest supervise-reraises-interrupt-instead-of-restarting-test
  (testing "an interrupt passes through: it is a stop, never a crash"
    (let [calls (atom 0)]
      (is (thrown? InterruptedException
                   (restart/supervise (fn []
                                        (swap! calls inc)
                                        (throw (InterruptedException. "stop")))
                                      (budget 3))))
      (is (= 1 @calls) "the child ran once: no restart, no budget spent"))))

(deftest supervise-restarts-a-crashing-child-test
  (testing "a crashed child runs again while the budget holds"
    (let [calls (atom 0)
          result (restart/supervise (fn []
                                      (swap! calls inc)
                                      (if (< @calls 3)
                                        (throw (ex-info "crash" {:call @calls}))
                                        :done))
                                    (budget 3))]
      (is (= {:ok :done} result)
          "the child ran until it returned")
      (is (= 3 @calls) "two crashes, then a return"))))

(deftest supervise-gives-up-when-the-budget-runs-out-test
  (testing "a child that keeps crashing is reported as failed, not restarted forever"
    (let [calls (atom 0)
          result (restart/supervise (fn []
                                      (swap! calls inc)
                                      (throw (ex-info "crash" {})))
                                    (budget 2))]
      (is (= :restart-budget-exhausted (:error result)))
      (is (= 2 (:restarts result))
          "the budget allowed two restarts")
      (is (= 3 @calls) "the initial attempt plus the two restarts")
      (is (instance? Exception (:last-error result))
          "the crash that ended the budget travels with the failure"))))

(deftest supervise-lets-errors-propagate-test
  (testing "an Error is not a crash: it is left to propagate"
    (let [result (try
                   (restart/supervise (fn []
                                        (throw (OutOfMemoryError. "heap")))
                                      (budget 3))
                   (catch Throwable error error))]
      (is (instance? OutOfMemoryError result)
          "a broken JVM is not a flaky child, and a restart would hide it"))))

(deftest supervise-slides-the-window-test
  (testing "a restart outside the window does not count against the budget"
    (let [now-ms (atom 0)
          calls (atom 0)
          sliding-budget {:limit 1
                          :window-ms window-ms
                          :now (fn [] @now-ms)}
          result (restart/supervise (fn []
                                      (swap! calls inc)
                                      (if (= 1 @calls)
                                        (do (swap! now-ms + (* 2 window-ms))
                                            (throw (ex-info "crash" {})))
                                        :done))
                                    sliding-budget)]
      (is (= {:ok :done} result)
          "the first restart fell outside the window, so the budget was whole")
      (is (= 2 @calls)))))

(deftest prune-drops-restarts-outside-the-window-test
  (testing "only the restarts inside the window survive the prune"
    (is (= [150] (restart/prune [0 100 150] 100 200))
        "a restart exactly a window old is outside it")))

(deftest restart-allowed-follows-the-limit-test
  (testing "the budget permits a restart while fewer than the limit happened"
    (is (true? (restart/restart-allowed? [] 3)))
    (is (true? (restart/restart-allowed? [0 1] 3)))
    (is (false? (restart/restart-allowed? [0 1 2] 3))
        "three restarts inside the window exhaust a limit of three")
    (is (true? (restart/restart-allowed? (restart/prune [0 1 2] window-ms (* 10 window-ms)) 3))
        "the same restarts fall out of the window as time passes")))

(deftest fdef-specs-hold-generatively-test
  ;; supervise is excluded from the check on principle: its child
  ;; argument is a fn, which no generator can conjure.
  (testing "every check-grade restart fdef holds over generated inputs"
    (is (= {}
           (test-utils/check-fdefs
            '[dev.cljtoc.supervision.restart/prune
              dev.cljtoc.supervision.restart/restart-allowed?]
            50)))))
