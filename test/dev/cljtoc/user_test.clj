(ns dev.cljtoc.user-test
  "Tests for the REPL helper namespace: the printer the welcome text
   promises must actually be the one spec failures use."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [expound.alpha :as expound]
            [user :as repl-user]))

(deftest installing-the-explain-printer-takes-effect-in-the-current-thread-test
  (testing "the welcome setup swaps spec's explain output for expound where the REPL evaluates"
    ;; A REPL runs with thread bindings; alter-var-root alone is shadowed
    ;; by them, which is the bug this covers.
    (binding [s/*explain-out* s/explain-printer]
      (repl-user/install-explain-printer!)
      (is (identical? expound/printer s/*explain-out*)
          "spec failures in this thread now print through expound"))))

(deftest installing-the-explain-printer-takes-effect-in-other-threads-test
  (testing "a thread with no binding still gets expound, so worker failures read the same"
    (repl-user/install-explain-printer!)
    (is (identical? expound/printer
                    (deref (future s/*explain-out*) 5000 :timed-out)))))
