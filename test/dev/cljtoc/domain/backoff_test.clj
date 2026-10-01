(ns dev.cljtoc.domain.backoff-test
  "Tests for the pure backoff math shared by every jitter consumer
   (peer-restart, re-announce, watchdog).

   These functions are pure, total, public — pinned by `stest/check`
   over their fdefs in `fdef-specs-hold-generatively-test` below."
  (:require [clojure.test :refer [deftest testing is]]
            [dev.cljtoc.domain.backoff :as backoff]
            [dev.cljtoc.test-utils :as test-utils]))

(deftest exponential-backoff-shape
  (testing "attempt 0 returns base-ms"
    (is (= 1000 (backoff/exponential-backoff 0 1000 60000))))
  (testing "each attempt doubles the wait, capped at cap-ms"
    (is (= 1000 (backoff/exponential-backoff 0 1000 60000)))
    (is (= 2000 (backoff/exponential-backoff 1 1000 60000)))
    (is (= 4000 (backoff/exponential-backoff 2 1000 60000)))
    (is (= 32000 (backoff/exponential-backoff 5 1000 60000)))
    (is (= 60000 (backoff/exponential-backoff 6 1000 60000)))
    (is (= 60000 (backoff/exponential-backoff 100 1000 60000))))
  (testing "large attempt counts saturate at cap-ms (no Long overflow)"
    ;; Per issue #18 lesson: bit-shift-left rejects BigInt and the
    ;; cap must come before the shift domain is reached. attempt 63
    ;; would be 2^63 * 1000 = a number that overflows Long.
    (is (= 60000 (backoff/exponential-backoff 63 1000 60000)))
    (is (= 60000 (backoff/exponential-backoff 1000 1000 60000)))))

(deftest with-jitter-shape
  (testing "zero jitter returns 0"
    (is (= 0 (backoff/with-jitter (constantly 0.0) 1000))))
  (testing "max jitter returns the input value"
    ;; The random-fn's domain is [0, 1); a value approaching 1 returns
    ;; almost-but-not-quite the input. 0.9999 captures that bound.
    (is (= 999 (backoff/with-jitter (constantly 0.9999) 1000))))
  (testing "mid-range jitter is bounded"
    (is (and (>= (backoff/with-jitter (constantly 0.5) 1000) 499)
             (<= (backoff/with-jitter (constantly 0.5) 1000) 500)))))

(deftest schedule-composition
  (testing "schedule = with-jitter . exponential-backoff, by construction"
    ;; The composition property: for any random-fn f and any attempt,
    ;; schedule(f, attempt) == with-jitter(f, exponential-backoff(attempt)).
    ;; This pins the body without depending on the (ill-defined) boundary
    ;; behaviour of zero-jitter or near-max-jitter.
    (let [f (constantly 0.42)]
      (is (= (backoff/with-jitter f (backoff/exponential-backoff 2 1000 60000))
             (backoff/schedule f 2 1000 60000)))))

  (testing "schedule with zero-jitter random-fn returns 0"
    ;; Documents the contract: zero random-fn in means zero delay out.
    ;; Useful for tests that want deterministic, no-wait scheduling.
    (is (= 0 (backoff/schedule (constantly 0.0) 5 1000 60000)))))

(deftest fdef-specs-hold-generatively-test
  ;; Per AGENTS.md: every pure, total public fn gets a generative check
  ;; over its fdef. The jitter fns are effect-port-shaped (they take a
  ;; fn), so the random-fn is excluded from generation by closing over a
  ;; constant; the attempt/base/cap triplet is generated.
  (let [failures (test-utils/check-fdefs
                  `[dev.cljtoc.domain.backoff/exponential-backoff
                    dev.cljtoc.domain.backoff/with-jitter
                    dev.cljtoc.domain.backoff/schedule]
                  50)]
    (is (empty? failures)
        (str "fdef generative check failed: " (pr-str failures)))))