(ns dev.cljtoc.daemon
  "The daemon process: one long-lived session that owns the torrents and
   answers RPC.

   The daemon owns the config dir, loads every saved torrent at start,
   runs them under one session supervisor, and serves the RPC surface so
   the CLI is a thin client: `torrent.download` returns while the
   download runs, and `status`, `pause` and `resume` read and change the
   live session rather than a state file written at the end.

   The handlers are the session's own operations — the RPC adds no
   download logic, it carries the request to the live supervisor."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.cli.state :as cli-state]
            [dev.cljtoc.core :as core]
            [dev.cljtoc.daemon.persistence :as persistence]
            [dev.cljtoc.daemon.records :as records]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.orchestration.session :as session]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.rpc :as rpc]
            [dev.cljtoc.supervision.session-supervisor :as session-supervisor]))

(def ^:private terminal-states
  "The states a saved torrent is not restarted from."
  #{:completed :failed})

(defn- load-torrents
  "The saved records the daemon owns: every record in the state dir, as
   the daemon's own view of the swarm."
  [disk-port state-dir]
  (vec (cli-state/load-all disk-port state-dir)))

(defn- report
  "The daemon's progress hook: a record that lands in the daemon's live
   view, and on its way out the door to the state dir, so a crash after a
   verified piece does not lose it."
  [records disk-port record]
  (records/put records record)
  (disk/save-state disk-port record))

(defn- record-status
  "What the RPC reports for one record: its progress plus the id and
   torrent path the status command prints. The progress map alone does
   not name the download, and the raw record holds byte arrays that do
   not survive the EDN wire — so the handler assembles the printable
   shape here, at the layer edge."
  [time-port record]
  (assoc (download/progress time-port record)
         :id (:id record)
         :torrent-path (:torrent-path record)))

(defn- freshest
  "The daemon's freshest view of its torrents: every record it owns with
   the session's published progress overlaid, because a running
   download's newest state is the one it last published. Records are
   merged by id, so the live view wins over a stale owned copy."
  [owned live]
  (->> (concat owned live)
       (reduce (fn [by-id record] (assoc by-id (:id record) record)) {})
       vals
       (into [])))

(defn- install-shutdown-hook!
  "Wire `stop` to JVM shutdown, so SIGTERM (and SIGINT) stops the session,
   persists every torrent, and only then lets the process exit. The stop
   is idempotent, so a signal after an explicit stop is a no-op. Returns
   the registered hook thread."
  [stop]
  (let [hook (Thread. ^Runnable (fn [] (stop)) "cljtoc-shutdown")]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn- add-to-session!
  "Join one download to the session atom.

   The read and the reset happen together under the atom's monitor, so two
   requests cannot interleave between them — and `add` runs exactly once.
   `add` starts the download's run, so letting it sit inside a `swap!`
   updater would start a duplicate run every time the swap retried.

   Returns {:added true} or, when the session is already running that id,
   {:refused :duplicate-id} instead of throwing out of the request."
  [session-supervisor manager download options]
  (locking session-supervisor
    (try
      (reset! session-supervisor
              (session-supervisor/add manager @session-supervisor download options))
      {:added true}
      (catch clojure.lang.ExceptionInfo error
        (if (= ::session-supervisor/duplicate-child
               (:type (ex-data error)))
          {:refused :duplicate-id}
          (throw error))))))

(defn handle
  "The RPC handler over a live daemon session.

   `daemon` is what `start` returns. Each command answers with the RPC
   envelope, so the CLI's client gets a map either way."
  [daemon request]
  (let [{:keys [command args]} request
        {:keys [manager session-supervisor records settings]} daemon
        {:keys [disk-port network-port time-port]} manager
        live-supervisor (deref session-supervisor)]
    (case command
      :start
      (let [paths (vec (:paths args))
            started (session/start-torrents (session/session manager) paths
                                            (:output-dir settings))
            downloads (session/downloads started)
            refused (into []
                          (keep (fn [download]
                                  (records/put records download)
                                  (when (= :refused
                                           (:refused
                                            (add-to-session! session-supervisor manager download
                                                             {:on-complete (partial report records disk-port)})))
                                    {:torrent-path (:torrent-path download)
                                     :error :duplicate-id
                                     :message (str "Download is already running: " (:id download))}))
                                downloads))]
        {:ok {:downloads (mapv :id downloads)
              :failures (into (vec (session/failures started)) refused)}})

      :status
      ;; The daemon owns the records; the session owns the telemetry.
      ;; A running download's freshest state is the progress it last
      ;; published, but a finished one never publishes again — its
      ;; terminal record lives only in the daemon's view. So the live
      ;; view wins while the download is still running, and the owned
      ;; record wins once it reached a terminal state.
      (let [lively (into {} (map (juxt :id identity))
                         (session-supervisor/live-records live-supervisor))
            current (map (fn [record]
                           (if (and (not (terminal-states (:state record)))
                                    (contains? lively (:id record)))
                             (get lively (:id record))
                             record))
                         (records/all records))
            wanted (if (:id args)
                     (filter #(= (:id args) (:id %)) current)
                     current)]
        ;; The response carries status maps, never the records: a
        ;; record holds byte arrays that do not survive the EDN wire.
        {:ok {:records (mapv #(record-status time-port %) wanted)}})

      :pause
      ;; Stop the run before the record says paused: a live run would keep
      ;; downloading and publish over the paused record on its next
      ;; progress report. The run unwinds through the same interrupt path
      ;; `stop` uses, so it keeps the pieces it verified.
      (if-let [record (records/record-for records (:id args))]
        (do
          (session-supervisor/stop-child live-supervisor (:id args))
          (let [paused (download/pause-download time-port nil record)]
            (if (:error paused)
              (rpc/error-response (:error paused) (:message paused))
              (let [ok (get paused :ok)
                    saved (disk/save-state disk-port ok)]
                (if (:error saved)
                  (rpc/error-response (:error saved) (:message saved))
                  (do (records/put records ok)
                      {:ok {:record (record-status time-port ok)}}))))))
        (rpc/error-response :unknown-download
                            (str "No download with id " (:id args))))

      :resume
      ;; The three branches of resume-and-run, without the blocking run:
      ;; a refusal fails the request, a record the cache already
      ;; completes is finalized here since its run is skipped, and
      ;; anything else rejoins the session. The run re-derives the
      ;; layout from the cache on prepare, so the envelope's :layout
      ;; is not threaded through.
      (if-let [record (records/record-for records (:id args))]
        (let [resumed (download/resume-download disk-port network-port record)]
          (if (:error resumed)
            (rpc/error-response (:error resumed) (:message resumed))
            (let [revived (download/accumulate-downtime time-port (:ok resumed))]
              (if (= :completed (:state revived))
                (let [done (download/complete-download time-port revived)]
                  (records/put records done)
                  {:ok {:record (record-status time-port done)}})
                (do (records/put records revived)
                    (if (= {:added true}
                           (add-to-session! session-supervisor manager revived
                                            {:on-complete (partial report records disk-port)}))
                      {:ok {:record (record-status time-port revived)}}
                      (rpc/error-response :duplicate-id
                                          (str "Download is already running: "
                                               (:id args)))))))))
        (rpc/error-response :unknown-download
                            (str "No download with id " (:id args))))

      :stop
      ;; Stop the run and forget its live record before the record is
      ;; deleted: a live run would publish progress for a download the
      ;; user just stopped, and a leftover live record would be written
      ;; back by the next persistence pass.
      (if-let [record (records/record-for records (:id args))]
        (let [_ (locking session-supervisor
                  (reset! session-supervisor
                          (session-supervisor/remove-child @session-supervisor (:id args))))
              stopped (download/stop-download record)
              deleted (disk/delete-state disk-port (:id record))]
          (records/forget records (:id record))
          (if (:error deleted)
            (rpc/error-response (:error deleted) (:message deleted))
            {:ok {:record (record-status time-port stopped)}}))
        (rpc/error-response :unknown-download
                            (str "No download with id " (:id args))))

      (rpc/error-response :unknown-command
                          (str "No command " (pr-str command))))))

(s/fdef handle
  :args (s/cat :daemon map? :request ::rpc/request)
  :ret map?)

(defn start
  "Start the daemon: build the ports from the settings, load every saved
   torrent, run the ones that still need to run under one session
   supervisor, and serve the RPC on the settings' port.

   The daemon keeps one live record per torrent, so the RPC sees a
   download before it finishes.

   Returns {:stop (fn [])}: the stop ends the RPC server, the persister
   and the session, which is how a signal leaves the process — the daemon
   registers that stop as a JVM shutdown hook, so SIGTERM persists every
   torrent before exiting. The stop is idempotent.

   Options:
   - :manager - ports to run with (default: built from the settings)
   - :persist-interval-ms - how often in-flight state is re-saved
     (default: persistence/default-interval-ms)
   - :shutdown-hook - installs the stop fn (default: a real JVM hook)
     and returns whatever it likes; a test injects a capture."
  [settings {:keys [manager shutdown-hook] :as options}]
  (let [manager (or manager (:manager (core/make-ports settings)))
        {:keys [disk-port]} manager
        saved (load-torrents disk-port (:state-dir settings))
        records (records/create saved)
        runnable (remove (comp terminal-states :state) saved)
        supervisor (atom
                    (session-supervisor/start
                     manager
                     runnable
                     {:on-complete (partial report records disk-port)}))
        server (rpc/start-server
                {:port (:rpc-port settings)
                 :handle (partial handle
                                  {:manager manager
                                   :session-supervisor supervisor
                                   :records records
                                   :settings settings})})
        persisted-records (fn []
                            (freshest (records/all records)
                                      (session-supervisor/live-records (deref supervisor))))
        persister (persistence/start disk-port
                                     persisted-records
                                     (or (:persist-interval-ms options)
                                         persistence/default-interval-ms))
        stopped (atom false)
        hook-installer (or shutdown-hook install-shutdown-hook!)
        stop (fn []
               (when (compare-and-set! stopped false true)
                 ;; Close the RPC first so no new work lands, then the
                 ;; persister, then unwind the runs — their last published
                 ;; records are what the final save writes.
                 ((:stop server))
                 ((:stop persister))
                 (session-supervisor/stop (deref supervisor))
                 (persistence/report-failures
                  (persistence/save-all disk-port (persisted-records)))))
        hook (hook-installer stop)]
    {:manager manager
     :session-supervisor supervisor
     :records records
     :settings settings
     :rpc-port (:rpc-port settings)
     :shutdown-hook hook
     :stop stop}))

;; Excluded from stest/check: the daemon owns ports, threads and sockets,
;; and no generator can conjure a settings map that builds them.
(s/fdef start
  :args (s/cat :settings map? :options (s/? map?))
  :ret map?)
