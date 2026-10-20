(ns dev.cljtoc.supervision.restart
  "The restart policy the supervisor layers share.

   A supervisor runs a child and, when the child crashes, restarts it
   while the restart budget holds. The budget is a sliding window: at
   most `limit` restarts inside `window-ms`. Running out of budget is the
   supervisor's give-up point — the child is reported as failed instead
   of being restarted again, so a child that keeps crashing cannot spin
   forever.

   A crash is an Exception. Errors — an OutOfMemoryError, a
   StackOverflowError — are not crashes and are left to propagate: a
   broken JVM is not a flaky child, and folding it into a restart would
   hide it."
  (:require [clojure.spec.alpha :as s]))

(s/def ::limit pos-int?)

(s/def ::window-ms pos-int?)

(s/def ::restart-time nat-int?)

;; The child the supervisor runs: it takes the attempt number and
;; returns the child's terminal result.
(s/def ::child (s/fspec :args (s/cat :attempt nat-int?) :ret any?))

(s/def ::budget
  (s/keys :req-un [::limit ::window-ms]))

(s/def ::ok any?)

(s/def ::error keyword?)

(s/def ::restarts nat-int?)

(s/def ::outcome
  (s/or :ok (s/keys :req-un [::ok])
        :error (s/keys :req-un [::error ::restarts])))

(defn prune
  "The restart times still inside the sliding window: the ones that
   happened less than `window-ms` before `now-ms`."
  [restart-times window-ms now-ms]
  (filterv #(< (- now-ms %) window-ms) restart-times))

(s/fdef prune
  :args (s/cat :restart-times (s/coll-of ::restart-time)
               :window-ms ::window-ms
               :now-ms nat-int?)
  :ret (s/coll-of ::restart-time))

(defn restart-allowed?
  "True when the budget still permits a restart: fewer than `limit`
   restarts happened inside the window."
  [restart-times limit window-ms now-ms]
  (< (count (prune restart-times window-ms now-ms)) limit))

(s/fdef restart-allowed?
  :args (s/cat :restart-times (s/coll-of ::restart-time)
               :limit ::limit
               :window-ms ::window-ms
               :now-ms nat-int?)
  :ret boolean?)

(defn supervise
  "Run `child` and restart it when it crashes, while the budget holds.

   `child` takes the attempt number and returns the child's terminal
   result; an Exception it throws is a crash. `budget` is
   {:limit n :window-ms ms :now (fn [] ms)} — the clock is a fn so the
   window slides on whatever time source the caller injects.

   Returns {:ok result} when the child returned, or
   {:error :restart-budget-exhausted :restarts n :last-error error} when
   the budget ran out. A child that returns is never restarted: only a
   crash is."
  [child {:keys [limit window-ms now]}]
  (loop [attempt 0
         restart-times []
         restarts 0]
    (let [outcome (try
                    {:returned (child attempt)}
                    (catch Exception error {:crashed error}))]
      (if (contains? outcome :returned)
        {:ok (:returned outcome)}
        (let [now-ms (long (now))
              window-times (prune restart-times window-ms now-ms)]
          (if (< (count window-times) limit)
            (recur (inc attempt) (conj window-times now-ms) (inc restarts))
            {:error :restart-budget-exhausted
             :restarts (count window-times)
             :last-error (:crashed outcome)}))))))

;; Excluded from stest/check: the child is a fn, which no generator can
;; conjure, so a check cannot build the argument the fn is called with.
(s/fdef supervise
  :args (s/cat :child ::child :budget ::budget)
  :ret ::outcome)
