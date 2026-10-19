(ns dev.cljtoc.core
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.orchestration.session :as session]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness-impl :as randomness-impl]
            [dev.cljtoc.ports.time :as time-port]
            [dev.cljtoc.cli.state :as cli-state]
            [dev.cljtoc.cli.config :as cli-config]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:gen-class))

(defn- read-torrent-file [path]
  (let [f (io/file path)]
    (if (.exists f)
      (.readAllBytes (java.io.FileInputStream. f))
      (do (println (str "Error: file not found: " path))
          nil))))

(defn- format-pieces [pieces]
  (str (count pieces) " pieces"))

(defn- format-info-hash [^bytes info-hash]
  (bencode/bytes->hex-string info-hash))

(defn- printable-torrent
  [t]
  (-> t
      (update :info-hash format-info-hash)
      (update-in [:info :pieces] format-pieces)))

(defn- print-torrent [result]
  (if (:error result)
    (do
      (println "Failed to parse torrent file:")
      (if (vector? (:error result))
        (doseq [err (:error result)]
          (println (str "  - " (:message err))))
        (println (str "  " (:message result)))))
    (pp/pprint (printable-torrent (:ok result)))))

(defn- format-bytes [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (str (format "%.1f" (/ bytes 1024.0)) " KB")
    (< bytes (* 1024 1024 1024)) (str (format "%.1f" (/ bytes (* 1024.0 1024))) " MB")
    :else (str (format "%.2f" (/ bytes (* 1024.0 1024 1024))) " GB")))

(defn- print-progress [progress]
  (println (str "  Progress: " (format "%.1f" (:percent progress)) "%"))
  (println (str "  Pieces: " (:pieces-complete progress) "/" (:pieces-total progress)))
  (println (str "  Downloaded: " (format-bytes (:bytes-downloaded progress))))
  (println (str "  Speed: " (format-bytes (:rate-bytes-per-sec progress)) "/s"))
  (println (str "  Peers: " (:peers-connected progress)))
  (println (str "  State: " (name (:state progress)))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- cmd-torrent-parse
  [_settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.parse <path-to-torrent-file>")
      (System/exit 1))
    (let [path (first args)
          bs (read-torrent-file path)]
      (when bs
        (print-torrent (torrent/parse-torrent bs))))))

(def ^:private manager-config-keys
  "Settings keys forwarded to download/manager as config overrides. Only
   :max-peers has an effect there; the manager's other knobs are reserved
   and stay owned by download/default-config rather than being offered as
   settings that do nothing."
  [:max-peers])

(defn- make-ports
  "Build the ports both torrent.download and torrent.resume run with.

   Both commands read the same state dir and the same piece cache: resume
   loads the record cmd-torrent-download saved and materializes the pieces
   its cache holds, so a drifting path here would resume against a cache
   that is not the one the download wrote.

   The randomness port is shared between the network port (peer-id for
   tracker announce) and the download manager (peer-id for handshake);
   per ADR-0011 every randomness effect sits behind one injected port,
   constructed once and threaded where it is needed."
  [settings]
  (let [disk-port (disk-impl/create {:state-dir (:state-dir settings)
                                     :piece-cache-dir (:piece-cache-dir settings)})
        randomness-port (randomness-impl/create)
        network-port (network-impl/create {:randomness-port randomness-port
                                           :peer-port (:peer-port settings)})
        time-port (time-port/->RealTimePort)]
    {:manager (download/manager network-port disk-port time-port
                                (select-keys settings manager-config-keys))
     :time-port time-port}))

(defn- split-global-flags
  "Pull --config <path> out of the raw CLI args. Returns
   [config-path remaining-args]; a dangling --config with no value
   passes through for the command to reject."
  [args]
  (loop [remaining args
         config-path nil
         kept []]
    (cond
      (empty? remaining)
      [config-path kept]

      (and (= "--config" (first remaining)) (second remaining))
      (recur (drop 2 remaining) (second remaining) kept)

      :else
      (recur (rest remaining) config-path (conj kept (first remaining))))))

(defn- try-load-settings
  "Load settings from config-path (or the default location when nil).
   A corrupt file, or an explicit path that does not exist, returns nil
   after saying why, so the caller decides to stop — keeping System/exit in
   one place is what makes this branch testable at all."
  [config-path]
  (let [result (cli-config/load-settings config-path)]
    (if (:error result)
      (do
        (println (str "Failed to load config: " (:message result)))
        nil)
      (:ok result))))

(defn- dangling-config-flag?
  "True when --config survived splitting, meaning it had no value.
   Reject it before loading settings or dispatching: otherwise
   `torrent.stop existing-id --config` would silently load the default
   config and could delete the wrong download's state."
  [rest-args]
  (boolean (some #{"--config"} rest-args)))

(defn- torrent-path-arg?
  "True when a command argument looks like a torrent path rather than a
   bare download id: it carries a `.torrent` suffix or a path separator.
   A bare id never ends in `.torrent` (ids derive from torrent filenames
   minus the extension) and never carries a separator, so gating the
   `id-from-path` fallback on this keeps a missing dotted id from
   resolving to another download's state."
  [arg]
  (or (str/ends-with? arg ".torrent")
      (str/includes? arg "/")))

(defn- load-command-state
  "Load the record a command should act on: the named id, else the most
   recent. Returns the record or nil when nothing is there. A corrupt file
   reads as nil — the CLI's long-standing missing-state story (commands
   print not-found); the port still reports :load-error in its own contract.

   A torrent path resolves too: download derives its id from the torrent
   path, so users mirror `torrent.download <path>` when they resume. The
   exact id is tried first, and the path fallback runs only for arguments
   that look like torrent paths (a `.torrent` suffix or a path separator)
   — deriving unconditionally would mangle a bare id that carries dots
   (`archive.v2` would re-derive as `archive` and return the wrong
   download, so `torrent.stop archive.v2` could delete `archive`)."
  [disk-port state-dir args]
  (if (seq args)
    (let [arg (first args)]
      (or (:ok (disk/load-state disk-port arg))
          (when (torrent-path-arg? arg)
            (:ok (disk/load-state disk-port (disk/id-from-path arg))))))
    (cli-state/load-most-recent disk-port state-dir)))

(defn- status-records
  "The records a status command prints: the named download, else every
   saved record, most recent first. Always a vector, so the caller can
   test for an empty listing without distinguishing nil from []."
  [disk-port state-dir args]
  (if (seq args)
    (if-let [record (load-command-state disk-port state-dir args)]
      [record]
      [])
    (cli-state/load-all disk-port state-dir)))

(defn- save-or-exit
  "Persist one download record, or stop the command if the write fails: a
   run whose state cannot be saved cannot be resumed, so continuing would
   only lose it."
  [disk-port download]
  (let [save-result (disk/save-state disk-port download)]
    (when (:error save-result)
      (println "Failed to save download state:")
      (println "  " (:message save-result))
      (System/exit 1))))

(defn- cmd-torrent-download
  [settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.download <path-to-torrent-file> [more.torrent ...]")
      (System/exit 1))
    (let [output-dir (:output-dir settings)
          {:keys [manager time-port]} (make-ports settings)
          started (session/start-torrents (session/session manager) args output-dir)]
      (doseq [{:keys [torrent-path error message]} (session/failures started)]
        (println (str "Failed to start " torrent-path ": " (or message (name error)))))
      (let [started-downloads (session/downloads started)]
        (when (empty? started-downloads)
          (System/exit 1))
        (doseq [download started-downloads]
          (let [info (get-in download [:torrent :info])
                required-size (torrent/total-size info)
                space-check (disk-impl/check-disk-space (:output-dir download) required-size)]
            (println (str "Torrent: " (:name info)))
            (println (str "Size: " (format-bytes required-size)))
            (println (str "Piece length: " (:piece-length info)))
            (println (str "Pieces: " (count (:pieces info))))
            (when (:error space-check)
              (println "WARNING: " (:message space-check))
              (println "Continuing anyway..."))
            ;; Save the started record before the run blocks, so a crash
            ;; mid-download leaves a resumable record and a status listing
            ;; sees every torrent the session owns.
            (save-or-exit (:disk-port manager) download)))
        (println "Starting download to: " output-dir)
        (let [finished (session/run-downloads started)]
          (doseq [download (session/downloads finished)]
            (save-or-exit (:disk-port manager) download)
            (println)
            (print-progress (download/progress time-port download)))
          (when (or (seq (session/failures finished))
                    (some #(= :failed (:state %)) (session/downloads finished)))
            (System/exit 1)))))))

(defn- cmd-torrent-pause
  [settings args]
  (let [{:keys [manager]} (make-ports settings)
        state (load-command-state (:disk-port manager)
                                  (:state-dir settings)
                                  args)]
    (if (nil? state)
      (do
        (println "No active download found.")
        (System/exit 1))
      (let [result (download/pause-download (time-port/->RealTimePort) nil state)]
        (if (:error result)
          (do
            (println "Failed to pause: " (get-in result [:error :message]))
            (System/exit 1))
          (let [save-result (disk/save-state (:disk-port manager) (get result :ok))]
            (when (:error save-result)
              (println "Failed to save download state:")
              (println "  " (:message save-result))
              (System/exit 1))
            (println "Download paused.")
            (print-progress (download/progress (time-port/->RealTimePort) (get result :ok)))))))))

(defn- resume-and-run
  "Resume a saved record, then run the download to completion.

   Hands the record to run-download rather than returning the resumed
   record: a resumed record is peerless until the announce fills it in, and
   would otherwise be saved and printed as :downloading with nothing
   downloading it. A record the cache already completes skips the run: the
   swarm has nothing to fetch, and re-running it would only redo the
   materialization run-download just proved unnecessary -- its stats are
   finalized here instead, since the skipped run never finalizes them.
   The dead gap since the last verified byte (pause, failure, or crash)
   is folded out of the rate denominator on every resume, so the average
   below measures active time. Otherwise the reconciled materialization
   travels into run-download, so the cached pieces are not read, verified,
   and written a second time before the swarm is dialed. Returns the
   refusal envelope when the record cannot be resumed, else the final
   Download record."
  [manager state]
  (let [{:keys [disk-port network-port time-port]} manager
        resumed (download/resume-download disk-port network-port state)]
    (if (:error resumed)
      resumed
      (let [revived (download/accumulate-downtime time-port (:ok resumed))]
        (if (= :completed (:state revived))
          (download/complete-download time-port revived)
          (download/run-download manager revived {:materialized? true
                                                  :layout (:layout resumed)}))))))

(defn- refusal?
  "True when result is a refusal envelope rather than a Download record.

   resume-and-run returns either: a refusal carries :error but no :state,
   while even a :failed record carries both. Testing :error alone mistook
   failed records for refusals and returned without saving them."
  [result]
  (boolean (and (:error result) (nil? (:state result)))))

(defn- cmd-torrent-resume
  [settings args]
  (let [{:keys [manager time-port]} (make-ports settings)
        state (load-command-state (:disk-port manager)
                                  (:state-dir settings)
                                  args)]
    (if (nil? state)
      (do
        (println "No paused download found.")
        (System/exit 1))
      (let [result (resume-and-run manager state)]
        (if (refusal? result)
          (do
            (println "Failed to resume: " (:message result))
            (System/exit 1))
          (let [save-result (disk/save-state (:disk-port manager) result)]
            (when (:error save-result)
              (println "Failed to save download state:")
              (println "  " (:message save-result))
              (System/exit 1))
            (println (if (= :failed (:state result))
                       (str "Download failed: " (get-in result [:error :message]))
                       "Download resumed."))
            (print-progress (download/progress time-port result))))))))

(defn- cmd-torrent-status
  [settings args]
  (let [{:keys [manager]} (make-ports settings)
        records (status-records (:disk-port manager) (:state-dir settings) args)]
    (if (empty? records)
      (do
        (println "No download found.")
        (System/exit 1))
      (doseq [state records]
        (println (str "Download: " (:id state)))
        (when (:torrent-path state)
          (println (str "Torrent: " (:torrent-path state))))
        (print-progress (download/progress (time-port/->RealTimePort) state))
        (println)))))

(defn- cmd-torrent-stop
  [settings args]
  (let [{:keys [manager]} (make-ports settings)
        state (load-command-state (:disk-port manager)
                                  (:state-dir settings)
                                  args)]
    (if (nil? state)
      (do
        (println "No download found.")
        (System/exit 1))
      (let [stopped (download/stop-download state)
            delete-result (disk/delete-state (:disk-port manager) (:id state))]
        (when (:error delete-result)
          (println "Failed to delete download state:")
          (println "  " (:message delete-result))
          (System/exit 1))
        (println "Download stopped.")
        (print-progress (download/progress (time-port/->RealTimePort) stopped))))))

(defn- cmd-torrent-seed
  [_settings _args]
  (println "torrent.seed - Not yet implemented")
  (println "Seeding will be available in a future version.")
  (System/exit 1))

(def ^:private commands
  {"torrent.parse" {:fn cmd-torrent-parse
                    :desc "Parse and display torrent file metadata"}
   "torrent.download" {:fn cmd-torrent-download
                       :desc "Download one or more torrents"}
   "torrent.pause" {:fn cmd-torrent-pause
                    :desc "Pause an active download"}
   "torrent.resume" {:fn cmd-torrent-resume
                     :desc "Resume a paused or failed download"}
   "torrent.status" {:fn cmd-torrent-status
                     :desc "Show one download, or list every download"}
   "torrent.stop" {:fn cmd-torrent-stop
                   :desc "Stop a download"}
   "torrent.seed" {:fn cmd-torrent-seed
                   :desc "Seed a torrent (not yet implemented)"}})

(defn- print-usage []
  (println "BitTorrent Client CLI")
  (println "\nUsage: lein run <command> [args...]")
  (println "\nAvailable commands:")
  (doseq [[cmd {:keys [desc]}] (sort-by key commands)]
    (println (format "  %-20s %s" cmd desc)))
  (println "\nGlobal flags (any position):")
  (println "  --config <path>        Use this settings file instead of ~/.config/cljtoc/config.edn")
  (println "\nExamples:")
  (println "  lein run torrent.parse path/to/file.torrent")
  (println "  lein run torrent.download path/to/file.torrent [more.torrent ...]")
  (println "  lein run --config ./my.edn torrent.status"))

(defn -main
  "BitTorrent client command-line interface.

   Usage: lein run <command> [args...]

   Available commands:
     torrent.parse <file>     Parse and display torrent metadata
     torrent.download <file>... Download one or more torrents in one session
     torrent.pause [id]     Pause an active download
     torrent.resume [id]    Resume a paused download
     torrent.status [id]    Show one download, or list every download
     torrent.stop [id]       Stop a download
     torrent.seed <file>    Seed a torrent (not yet implemented)"
  [& args]
  (if (empty? args)
    (do
      (print-usage)
      (System/exit 1))
    (let [[config-path rest-args] (split-global-flags args)]
      (if (dangling-config-flag? rest-args)
        (do
          (println "Missing value for --config <path>.")
          (print-usage)
          (System/exit 1))
        (let [settings (try-load-settings config-path)
              cmd (first rest-args)
              cmd-args (rest rest-args)
              handler (get commands cmd)]
          (if (nil? settings)
            (System/exit 1)
            (if handler
              ((:fn handler) settings cmd-args)
              (do
                (println (str "Unknown command: " cmd))
                (println)
                (print-usage)
                (System/exit 1)))))))))
