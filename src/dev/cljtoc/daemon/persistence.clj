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
   it belongs to, so the caller reports them and the next tick retries.

   A save that throws is caught per record: the failure becomes data like
   any other, and the records after it still get their turn. Letting the
   throw escape would leave every later record unsaved on every pass."
  [disk-port records]
  (into []
        (keep (fn [record]
                (try
                  (let [saved (disk/save-state disk-port record)]
                    (when (:error saved)
                      (assoc saved :id (:id record))))
                  (catch Exception error
                    {:id (:id record)
                     :error :save-error
                     :message (or (.getMessage error)
                                  (.getName (class error)))}))))
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

(def default-stop-timeout-ms
  "How long stop waits for an in-flight save to finish, in milliseconds.
   The final shutdown save runs after this, so waiting is what keeps a
   slow tick from overwriting newer state; the bound keeps a wedged disk
   from holding the process open."
  10000)

(defn start
  "Save every record `records` returns once per `interval-ms`, until the
   returned stop fn is called.

   Returns {:stop (fn [])}; the stop is idempotent and waits for a save
   already in flight, so a caller that saves once more afterwards (the
   daemon's shutdown path) cannot race a slower write onto the same file.
   A save that will not finish inside `stop-timeout-ms` is abandoned with
   a warning rather than blocking exit forever.

   Options:
   - :scheduler - creates the executor (a test injects a fake one)
   - :report - where warnings go (default: shell) "
  ([disk-port records interval-ms]
   (start disk-port records interval-ms {}))
  ([disk-port records interval-ms {:keys [scheduler report]
                                   :or {scheduler scheduler
                                        report report-failures}}]
   (let [executor (scheduler)
         stopped (atom false)
         warn (fn [message] (report [{:id "(persistence)"
                                      :error :save-error
                                      :message message}]))
         tick (fn []
                (when-not @stopped
                  (try
                    (report (save-all disk-port (records)))
                    (catch Exception error
                      (warn (str "save pass failed: " (.getMessage error)))))))]
     (.scheduleWithFixedDelay ^ScheduledExecutorService executor ^Runnable tick
                              (long interval-ms) (long interval-ms)
                              TimeUnit/MILLISECONDS)
     {:stop (fn []
              (when (compare-and-set! stopped false true)
                (.shutdown ^ScheduledExecutorService executor)
                (when-not (.awaitTermination ^ScheduledExecutorService executor
                                             (long default-stop-timeout-ms)
                                             TimeUnit/MILLISECONDS)
                  (warn "a save did not finish before shutdown; abandoning it"))))})))

;; Excluded from stest/check: owns a scheduler thread and drives a port,
;; and a generator cannot conjure either.
(s/fdef start
  :args (s/cat :disk-port any? :records fn? :interval-ms pos-int?)
  :ret map?)
