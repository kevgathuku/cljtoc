(ns dev.cljtoc.supervision.session-supervisor
  "One session supervisor owns one download-supervisor subtree per torrent.

   Each download runs on its own thread under its own download
   supervisor, so a crash or a restart stays inside that download's
   subtree: the session supervisor restarts the child that crashed and
   leaves its siblings running. That is the fault isolation ADR-0012
   asks for — one flaky torrent never restarts every torrent.

   The session supervisor starts without blocking. A caller that wants
   the terminal records awaits them; a caller that wants to know how a
   running session is doing reads the records that have landed so far."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.supervision.download-supervisor :as download-supervisor]
            [dev.cljtoc.supervision.restart :as restart]))

(defrecord SessionSupervisor [children live-records])

(s/def ::id string?)

(s/def ::download-record (s/keys :req-un [::id]))

(s/def ::child
  (s/fspec :args (s/cat :download-record ::download-record) :ret map?))

(def ^:private terminal-states
  "The download states that need no run: the download already reached its
   end. A refused announce leaves a :failed record, and only a later
   torrent.resume re-announces it."
  #{:completed :failed})

(defn- default-child
  "Run one download under its download supervisor, then persist the
   terminal record. A failing persister lands on the record as
   :session/persist-error instead of taking the session down, so one
   unsaved torrent does not abort its siblings. `publish` carries the
   record as the download progresses, so the session keeps the progress
   even if the download supervisor itself crashes."
  [manager on-complete]
  (fn [download-record publish]
    (let [finished (download-supervisor/supervise-download
                    manager
                    download-record
                    {:on-progress publish})
          persist-error (try
                          (on-complete finished)
                          (catch Exception error
                            {:reason :on-complete-exception
                             :message (.getMessage error)}))]
      (if persist-error
        (assoc finished :session/persist-error persist-error)
        finished))))

(defn- run-child
  "Run one download's child under the session supervisor's own restart
   policy: a child that crashes is restarted while the budget holds.

   The download's own subtree restarts happen inside the download
   supervisor, so they never reach this level. What reaches it is a
   child that crashed outright — the download supervisor could not even
   prepare its run — and the session restarts that download alone.

   The record the child published is what a give-up fails from, so the
   pieces the download verified before the crash survive it."
  [manager child download-record budget publish-record]
  (let [published (atom nil)
        report (fn [record]
                 (reset! published record)
                 (when publish-record (publish-record (:id record) record)))]
    (try
      (let [outcome (restart/supervise (fn [] (child download-record report))
                                       (restart/budget-for manager budget))
            ;; The live view holds the freshest record either way, so a
            ;; later stop acts on what the run actually ended with
            ;; instead of a stale in-progress snapshot.
            result (if (contains? outcome :ok)
                     (:ok outcome)
                     (download-supervisor/budget-exhausted-record (or @published download-record)
                                                                  (:restarts outcome)
                                                                  (:window-ms budget)))]
        (report result)
        result)
      ;; An interrupt is a stop, not a crash: the download keeps the
      ;; pieces it verified and comes back :paused, without spending
      ;; the restart budget. This only fires outside the download
      ;; supervisor, which pauses the same way one level down.
      (catch InterruptedException _
        (let [record (or @published download-record)
              paused (if (= :downloading (:state record))
                       (or (:ok (download/pause-download
                                 (:time-port manager) nil record))
                           record)
                       record)]
          (report paused)
          paused)))))

(defn start
  "Start one download supervisor per download, each on its own thread, and
   return the session supervisor without blocking.

   `on-complete` sees each download's terminal record as it finishes, so
   a result cannot sit unsaved behind a stalled torrent.

   A download that already reached a terminal state is kept as it is: it
   is neither run nor persisted again. A refused announce leaves a
   :failed record, and only a later `torrent.resume` re-announces it.

   The session keeps every record that has landed, including the ones
   still running: `live-records` answers what a caller that is not
   waiting for completion sees — the RPC's status reads it, so a torrent
   that is mid-download is visible before it finishes.

   `child` is the seam that runs one download: it takes the download
   record and a publish fn, and returns its terminal record. It defaults
   to the download supervisor, and a test drives the session's own
   restart path through it."
  [manager downloads {:keys [on-complete budget child]
                      :or {on-complete (fn [_])
                           budget restart/default-budget}}]
  (let [child (or child (default-child manager on-complete))
        live (atom {})
        publish-record (fn [id record] (swap! live assoc id record))
        children (into {}
                       (map (fn [download-record]
                              (let [done (promise)]
                                [(:id download-record)
                                 {:download download-record
                                  :done done
                                  :run (future
                                         (try
                                           (if (terminal-states (:state download-record))
                                             (do (publish-record (:id download-record) download-record)
                                                 download-record)
                                             (run-child manager
                                                        child
                                                        download-record
                                                        budget
                                                        publish-record))
                                           (finally
                                             (deliver done true))))}])))
                       downloads)]
    (->SessionSupervisor children live)))

(s/fdef start
  :args (s/cat :manager map?
               :downloads (s/coll-of ::download-record)
               :options (s/? map?))
  :ret map?)

(defn live-records
  "The records the session has seen so far, running ones included.

   `records` answers the finished ones, which is what a caller that waits
   for completion wants; a caller that is not waiting — the RPC's status
   — reads this instead, so a torrent that is mid-download is visible
   before it finishes. A download that has not published yet is absent."
  [session-supervisor]
  (sort-by :id (vals @(:live-records session-supervisor))))

(s/fdef live-records
  :args (s/cat :session-supervisor map?)
  :ret (s/coll-of map?))

(defn await-all
  "Block until every child reached a terminal state. Returns the session
   supervisor, whose records now hold the final ones. A child stopped
   with cancel holds no value to await — deref would throw — so stopped
   children are skipped: their records already landed in the live view."
  [session-supervisor]
  (doseq [[_ child] (:children session-supervisor)]
    (try
      @(:run child)
      (catch java.util.concurrent.CancellationException _ nil)))
  session-supervisor)

(s/fdef await-all
  :args (s/cat :session-supervisor map?)
  :ret map?)

(def ^:private join-timeout-ms
  "How long stop waits for all children to unwind, in milliseconds.
   Unwinding is local work — an interrupt out of a channel park, peer
   closes, supervisor stops — so anything slower is wedged; shutdown
   must not hang behind it. One deadline covers every child, because
   they are cancelled together."
  30000)

(defn- join-run
  "Wait for a cancelled run to unwind. Returns :done when it finished,
   :timeout when it didn't. Waits on the run's own done signal — delivered
   by the run's thread after its final report landed — and never on the
   future: cancel marks the future done synchronously while its thread is
   still unwinding, so neither `future-done?` nor deref on the future
   waits at all. A run cancelled before its thread started never delivers,
   so stopping one waits out the bound; callers only stop runs that have
   started, so that case does not come up."
  [child timeout-ms]
  (if (= :timeout (deref (:done child) timeout-ms :timeout))
    :timeout
    :done))

(defn stop
  "Stop every running child and wait for each to unwind, so the download
   supervisors suspend instead of crashing and the daemon can persist
   every torrent before the process exits. A child that already finished
   is left alone; one that will not stop in time is left behind with a
   warning while shutdown continues with the rest.

   Every child is cancelled before any is joined, and the joins share one
   deadline: cancelling and joining one child at a time would multiply
   the bound by the number of wedged children, and a signal handler has
   only the supervisor's grace period to finish."
  [session-supervisor]
  (doseq [[_ child] (:children session-supervisor)]
    (future-cancel (:run child)))
  (let [deadline (+ (System/currentTimeMillis) join-timeout-ms)]
    (doseq [[id child] (:children session-supervisor)]
      (let [remaining (max 0 (- deadline (System/currentTimeMillis)))]
        (when (= :timeout (join-run child remaining))
          (println (str "Download would not stop, continuing shutdown: " id))))))
  session-supervisor)

(s/fdef stop
  :args (s/cat :session-supervisor map?)
  :ret map?)

(defn stop-child
  "Stop one download's run and wait for it to unwind, so pause and stop
   act on a record no run is still moving. Returns the record the run
   ended with, or nil when the session holds no such download. Throws
   when the run would not stop inside the join bound: pausing a runaway
   run would lie, so the caller fails loudly instead. The bound is
   injectable so tests need not wait out the production one."
  ([supervisor id]
   (stop-child supervisor id join-timeout-ms))
  ([supervisor id timeout-ms]
   (if-let [child (get (:children supervisor) id)]
     (do (future-cancel (:run child))
         (if (= :timeout (join-run child timeout-ms))
           (throw (ex-info (str "Download would not stop: " id)
                           {:type ::stop-timeout :id id}))
           (get @(:live-records supervisor) id)))
     nil)))

(s/fdef stop-child
  :args (s/cat :supervisor map? :id ::id :timeout-ms (s/? nat-int?))
  :ret (s/nilable map?))

(defn- live-child?
  "True when a child's run has not finished. The done promise, not the
   future, is the signal: cancel marks a future done while its thread is
   still unwinding."
  [child]
  (not (realized? (:done child))))

(defn remove-child
  "Stop one download's run and forget it: cancels, waits for it to
   unwind, and drops the child and its live record. Returns the session
   value the caller keeps.

   Dropping the live record matters for anything that persists the
   session: leaving it behind would let a later save resurrect a
   download the user just stopped. A run that will not stop inside the
   bound is left behind with a warning, like `stop`."
  ([supervisor id]
   (remove-child supervisor id join-timeout-ms))
  ([supervisor id timeout-ms]
   (if-let [child (get (:children supervisor) id)]
     (do (future-cancel (:run child))
         (when (= :timeout (join-run child timeout-ms))
           (println (str "Download would not stop, forgetting it anyway: " id)))
         (swap! (:live-records supervisor) dissoc id)
         (update supervisor :children dissoc id))
     supervisor)))

(s/fdef remove-child
  :args (s/cat :supervisor map? :id ::id :timeout-ms (s/? nat-int?))
  :ret map?)

(defn add
  "Start one more download under the session and return the session with
   it.

   The session is a value, so the caller keeps the returned one: the
   daemon holds its session in an atom, so a start request can join the
   torrents the session already owns without restarting them. The new
   download's records land in the same live view.

   An id whose run is still live is refused: replacing the child would
   orphan the running future, which no later stop could reach, and two
   runs would publish to the same live record. A finished run is fine to
   replace — that is what resume after completion does."
  [manager session-supervisor download-record
   {:keys [on-complete budget child]
    :or {on-complete (fn [_])
         budget restart/default-budget}}]
  (let [child (or child (default-child manager on-complete))
        id (:id download-record)
        live (:live-records session-supervisor)]
    (when (some-> (get (:children session-supervisor) id) live-child?)
      (throw (ex-info (str "Download is already running: " id)
                      {:type ::duplicate-child :id id})))
    (let [report (fn [published-id record] (swap! live assoc published-id record))]
      ;; Seed the live view directly: the atom stays an atom — an `update`
      ;; over it would swap the value and store the plain map back.
      (swap! live assoc id download-record)
      (let [done (promise)]
        (update session-supervisor :children assoc id
                {:download download-record
                 :done done
                 :run (future (try (run-child manager child download-record
                                              budget report)
                                   (finally (deliver done true))))})))))

(s/fdef add
  :args (s/cat :manager map? :session-supervisor map? :download-record ::download-record
               :options (s/? map?))
  :ret map?)

(defn records
  "The download records that have landed so far, ordered by id. A download
   still running has no record yet, so it is not listed. Same for one
   stopped with cancel: its future holds no value, and its record lives
   in the live view instead."
  [session-supervisor]
  (->> (:children session-supervisor)
       (keep (fn [[_ child]] (try (deref (:run child) 0 nil)
                                  (catch java.util.concurrent.CancellationException _ nil))))
       (sort-by :id)
       vec))

(s/fdef records
  :args (s/cat :session-supervisor map?)
  :ret (s/coll-of map?))
