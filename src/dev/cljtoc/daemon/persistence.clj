(ns dev.cljtoc.daemon.persistence
  "Periodic per-torrent persistence for the daemon session.

   Resume state is otherwise written only when a download ends, so a
   daemon crash loses everything since start. This worker re-saves every
   record the daemon owns once per interval, which bounds that loss to a
   single interval.

   Failures are data: a save that fails is reported and the next tick
   tries again, so a full disk or an unencodable record never takes the
   session down. A port that throws is caught for the same reason — a
   scheduler task that throws is silently never run again."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.ports.disk :as disk])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def default-interval-ms
  "How often the daemon re-saves its torrents, in milliseconds. A minute
   bounds a crash's loss without writing more than one state file per
   torrent per tick."
  60000)

(s/def ::id string?)

(s/def ::record (s/keys :req-un [::id]))

(s/def ::records (s/coll-of ::record))

(defn save-all
  "Persist every record. Returns the failures only, each carrying the id
   it belongs to, so the caller reports them and the next tick retries."
  [disk-port records]
  (into []
        (keep (fn [record]
                (let [saved (disk/save-state disk-port record)]
                  (when (:error saved)
                    (assoc saved :id (:id record))))))
        records))

(s/fdef save-all
  :args (s/cat :disk-port any? :records ::records)
  :ret (s/coll-of map?))

(defn report-failures
  "Say which torrents a save pass could not persist. A failed save is a
   warning, never a crash: the download is still running and the next
   pass tries again."
  [failures]
  (doseq [{:keys [id error message]} failures]
    (println (str "[persist] WARNING: could not save " id ": "
                  (or message (name error)))))
  nil)

(s/fdef report-failures
  :args (s/cat :failures (s/coll-of map?))
  :ret nil?)

(defn- scheduler
  "One background thread for the save pass. Daemon, so a persister left
   running never holds the JVM open."
  ^ScheduledExecutorService []
  (Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "cljtoc-persist")
         (.setDaemon true))))))

(defn start
  "Save every record `records` returns once per `interval-ms`, until the
   returned stop fn is called.

   Returns {:stop (fn [])}; the stop is idempotent and waits for no tick:
   a save already in flight finishes, and nothing starts after it."
  [disk-port records interval-ms]
  (let [executor (scheduler)
        stopped (atom false)
        tick (fn []
               (when-not @stopped
                 (try
                   (report-failures (save-all disk-port (records)))
                   (catch Exception error
                     (println (str "[persist] WARNING: save pass failed: "
                                   (.getMessage error)))))))]
    (.scheduleWithFixedDelay executor ^Runnable tick
                             (long interval-ms) (long interval-ms)
                             TimeUnit/MILLISECONDS)
    {:stop (fn []
             (when (compare-and-set! stopped false true)
               (.shutdownNow executor)))}))

;; Excluded from stest/check: owns a scheduler thread and drives a port,
;; and a generator cannot conjure either.
(s/fdef start
  :args (s/cat :disk-port any? :records fn? :interval-ms pos-int?)
  :ret map?)
