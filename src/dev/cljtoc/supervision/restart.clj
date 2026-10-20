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
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.ports.time :as time]))

(s/def ::limit pos-int?)

(s/def ::window-ms pos-int?)

(s/def ::restart-time nat-int?)

;; The child the supervisor runs: it returns the child's terminal result.
(s/def ::child (s/fspec :args [] :ret any?))

;; The clock the window slides on: the current time in milliseconds.
(s/def ::now (s/fspec :args [] :ret nat-int?))

(s/def ::budget
  (s/keys :req-un [::limit ::window-ms ::now]))

(s/def ::ok any?)

(s/def ::error keyword?)

(s/def ::restarts nat-int?)

(s/def ::outcome
  (s/or :ok (s/keys :req-un [::ok])
        :error (s/keys :req-un [::error ::restarts])))

(def default-budget
  "How often a supervisor may restart a child inside the window. Both
   supervisor levels use it: a child that crashes three times inside the
   window is failing for good, not flapping.

   The window is five minutes so it still bounds the watchdog, whose
   deadline has to outlast an optimistic-unchoke rotation: the window
   holds ceil(window/deadline) - 1 restarts, so a shorter window would
   let a download that never moves restart forever. The two numbers are
   chosen together."
  {:limit 3
   :window-ms 300000})

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
   restarts happened inside the window. `window-times` are the restarts
   already pruned to the window."
  [window-times limit]
  (< (count window-times) limit))

(s/fdef restart-allowed?
  :args (s/cat :window-times (s/coll-of ::restart-time)
               :limit ::limit)
  :ret boolean?)

(defn now-ms
  "The current time in milliseconds, or 0 when there is no clock: without
   one the restart window never slides, and the budget reads as a plain
   counter."
  [time-port]
  (if (some? time-port)
    (long (time/now time-port))
    0))

(s/fdef now-ms
  :args (s/cat :time-port (s/? any?))
  :ret nat-int?)

(defn budget-for
  "The restart budget a supervisor runs with: `limit` restarts inside
   `window-ms`, measured on the manager's clock."
  [manager {:keys [limit window-ms]}]
  {:limit limit
   :window-ms window-ms
   :now (fn [] (now-ms (:time-port manager)))})

(s/fdef budget-for
  :args (s/cat :manager map?
               :budget (s/keys :req-un [::limit ::window-ms]))
  :ret ::budget)

(defn supervise
  "Run `child` and restart it when it crashes, while the budget holds.

   `child` returns the child's terminal result; an Exception it throws is
   a crash. `budget` is {:limit n :window-ms ms :now (fn [] ms)} — the
   clock is a fn so the window slides on whatever time source the caller
   injects.

   An interrupt is a stop, never a crash: InterruptedException passes
   straight through instead of spending the budget, so the supervisor
   that owns the stop decision sees it. Callers that stop runs catch it
   around this call and suspend instead.

   Returns {:ok result} when the child returned, or
   {:error :restart-budget-exhausted :restarts n :last-error error} when
   the budget ran out. A child that returns is never restarted: only a
   crash is."
  [child {:keys [limit window-ms now]}]
  (loop [restart-times []]
    (let [outcome (try
                    {:returned (child)}
                    (catch InterruptedException interrupted
                      (throw interrupted))
                    (catch Exception error {:crashed error}))]
      (if (contains? outcome :returned)
        {:ok (:returned outcome)}
        (let [crashed-at (long (now))
              window-times (prune restart-times window-ms crashed-at)]
          (if (restart-allowed? window-times limit)
            (recur (conj window-times crashed-at))
            {:error :restart-budget-exhausted
             :restarts (count window-times)
             :last-error (:crashed outcome)}))))))

;; Excluded from stest/check: the child is a fn, which no generator can
;; conjure, so a check cannot build the argument the fn is called with.
(s/fdef supervise
  :args (s/cat :child ::child :budget ::budget)
  :ret ::outcome)
