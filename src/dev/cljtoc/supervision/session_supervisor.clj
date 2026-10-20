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
            [dev.cljtoc.supervision.download-supervisor :as download-supervisor]
            [dev.cljtoc.supervision.restart :as restart]))

(defrecord SessionSupervisor [children])

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
   unsaved torrent does not abort its siblings."
  [manager on-complete]
  (fn [download-record]
    (let [finished (download-supervisor/supervise-download manager download-record {})
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
   child that crashed outright — a bug in the supervisor itself — and
   the session restarts that download alone."
  [manager child download-record budget]
  (let [outcome (restart/supervise (fn [_attempt] (child download-record))
                                   {:limit (:limit budget)
                                    :window-ms (:window-ms budget)
                                    :now (fn [] (restart/now-ms (:time-port manager)))})]
    (if (contains? outcome :ok)
      (:ok outcome)
      (assoc download-record
             :state :failed
             :error {:reason :restart-budget-exhausted
                     :message (str "The download supervisor crashed "
                                   (:restarts outcome)
                                   " times within " (:window-ms budget)
                                   "ms and was not restarted again")}))))

(defn start
  "Start one download supervisor per download, each on its own thread, and
   return the session supervisor without blocking.

   `on-complete` sees each download's terminal record as it finishes, so
   a result cannot sit unsaved behind a stalled torrent.

   `child` is the seam that runs one download: it takes the download
   record and returns its terminal record. It defaults to the download
   supervisor, and a test drives the session's own restart path through
   it."
  [manager downloads {:keys [on-complete budget child]
                      :or {on-complete (fn [_])
                           budget download-supervisor/default-budget}}]
  (let [child (or child (default-child manager on-complete))
        children (into {}
                       (map (fn [download-record]
                              (let [record (atom nil)
                                    run (future
                                          (let [finished (if (terminal-states (:state download-record))
                                                           download-record
                                                           (run-child manager
                                                                      child
                                                                      download-record
                                                                      budget))]
                                            (reset! record finished)
                                            finished))]
                                [(:id download-record)
                                 {:id (:id download-record)
                                  :download download-record
                                  :record record
                                  :run run}])))
                       downloads)]
    (->SessionSupervisor children)))

(s/fdef start
  :args (s/cat :manager map?
               :downloads (s/coll-of ::download-record)
               :options (s/? map?))
  :ret map?)

(defn await-all
  "Block until every child reached a terminal state. Returns the session
   supervisor, whose records now hold the final ones."
  [session-supervisor]
  (doseq [[_ child] (:children session-supervisor)]
    @(:run child))
  session-supervisor)

(s/fdef await-all
  :args (s/cat :session-supervisor map?)
  :ret map?)

(defn records
  "The download records that have landed so far, ordered by id. A download
   still running has no record yet, so it is not listed."
  [session-supervisor]
  (->> (:children session-supervisor)
       (keep (fn [[_ child]] @(:record child)))
       (sort-by :id)
       vec))

(s/fdef records
  :args (s/cat :session-supervisor map?)
  :ret (s/coll-of map?))
