(ns dev.cljtoc.domain.backoff
  "Pure backoff math shared by every jitter consumer.

   Per ADR-0011: jitter math is a pure domain concern. The randomness
   port (`IRandomnessPort/random-bytes`) stays primitive; the supervisor
   slices (issue #28's peer-restart, #22's re-announce, #23's watchdog)
   consume this module, passing a random-fn produced by wrapping the
   port. This keeps the port's surface narrow and the jitter math
   deterministic in tests (close over a `(constantly 0.5)` and the
   whole pipeline is pinned without touching real entropy).

   Pure functions only — no I/O, no core.async, no time, no port imports.
   The `random-fn` argument is data (a fn), not an effect: passing it in
   means every consumer can supply its own randomness shape without
   leaking effects across the layer boundary."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; ---------------------------------------------------------------------------
;; Specs
;; ---------------------------------------------------------------------------

;; Per issue #40 lesson (Clojure 1.12 numerics): nat-int?/pos-int? are
;; LONG-RANGED — false for out-of-long-range BigInts, but BigInts are
;; unreachable through the spec at the fdef layer, so a (s/and pos-int?
;; #(<= % Long/MAX_VALUE)) is redundant. Just pos-int?; cap at Long/MAX_VALUE
;; in the body.

(s/def ::attempt nat-int?)
(s/def ::base-ms pos-int?)
(s/def ::cap-ms pos-int?)

;; A fn from no args to a double in [0, 1). The supervisor slices build
;; this by wrapping the randomness port (one byte -> [0, 1) fraction).
;; with-gen so stest/check can produce the arg — conformance is unchanged
;; (fn? still holds); only generation is filled in.
(s/def ::random-fn
  (s/with-gen fn? #(gen/return (fn [] 0.5))))

;; ---------------------------------------------------------------------------
;; exponential-backoff
;; ---------------------------------------------------------------------------

(defn exponential-backoff
  "Return the deterministic `attempt`-th wait time in ms: `base-ms * 2^attempt`,
   capped at `cap-ms`. Saturates at `cap-ms` for `attempt >= 63` so the
   shift never overflows `Long/MAX_VALUE` (per issue #18 lesson: `bit-shift-left`
   rejects BigInt; guarding the shift domain is cheaper than widening the operand).
   Pure, total over nat-int attempt and pos-int base/cap."
  [attempt base-ms cap-ms]
  ;; 2^63 already exceeds Long/MAX_VALUE for any base >= 1, so attempt
  ;; values past 62 saturate without shifting. Bigint arithmetic on the
  ;; multiply keeps the cap check honest for large attempt values a
  ;; generative check could produce; the long cast at the end is sound
  ;; because `min` against a long cap-ms narrows the result.
  (if (>= attempt 63)
    cap-ms
    (long (min (* (bigint base-ms) (bigint (bit-shift-left 1 attempt)))
               cap-ms))))

(s/fdef exponential-backoff
  :args (s/cat :attempt ::attempt :base-ms ::base-ms :cap-ms ::cap-ms)
  :ret pos-int?
  :fn #(<= (:ret %) Long/MAX_VALUE))

;; ---------------------------------------------------------------------------
;; with-jitter
;; ---------------------------------------------------------------------------

(defn with-jitter
  "Apply full jitter to `ms` using `random-fn` (a `(fn [] -> double in [0, 1))`):
   return `(long (* ms (random-fn)))`, a uniformly-distributed value in
   `[0, ms)`. The caller supplies `random-fn`; this fn does not import any
   randomness effect."
  [random-fn ms]
  (long (* (double ms) (random-fn))))

(s/fdef with-jitter
  :args (s/cat :random-fn ::random-fn :ms pos-int?)
  :ret nat-int?)

;; ---------------------------------------------------------------------------
;; schedule
;; ---------------------------------------------------------------------------

(defn schedule
  "Compose `exponential-backoff` and `with-jitter` for the `attempt`-th
   restart: compute the deterministic cap, then jitter uniformly into
   `[0, cap)`. Use this for the peer-restart, re-announce, and watchdog
   delays in issue #28's supervisor slices."
  [random-fn attempt base-ms cap-ms]
  (with-jitter random-fn (exponential-backoff attempt base-ms cap-ms)))

(s/fdef schedule
  :args (s/cat :random-fn ::random-fn :attempt ::attempt
               :base-ms ::base-ms :cap-ms ::cap-ms)
  :ret nat-int?)