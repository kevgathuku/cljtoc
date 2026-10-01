(ns dev.cljtoc.core
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness-impl :as randomness-impl]
            [dev.cljtoc.ports.time :as time-port]
            [dev.cljtoc.cli.state :as cli-state]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
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
  [args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.parse <path-to-torrent-file>")
      (System/exit 1))
    (let [path (first args)
          bs (read-torrent-file path)]
      (when bs
        (print-torrent (torrent/parse-torrent bs))))))

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
  []
  (let [disk-port (disk-impl/create {:state-dir cli-state/default-state-dir
                                     :piece-cache-dir "./torrent-cache"})
        randomness-port (randomness-impl/create)
        network-port (network-impl/create {:randomness-port randomness-port})
        time-port (time-port/->RealTimePort)]
    {:manager (download/manager network-port disk-port time-port {})
     :time-port time-port
     :randomness-port randomness-port}))

(defn- load-command-state
  "Load the record a command should act on: the named id, else the most
   recent. Returns the record or nil when nothing is there. A corrupt file
   reads as nil — the CLI's long-standing missing-state story (commands
   print not-found); the port still reports :load-error in its own contract."
  [disk-port state-dir args]
  (if (seq args)
    (:ok (disk/load-state disk-port (first args)))
    (cli-state/load-most-recent disk-port state-dir)))

(defn- cmd-torrent-download
  [args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.download <path-to-torrent-file> [output-dir]")
      (System/exit 1))
    (let [torrent-path (first args)
          output-dir (or (second args) "./downloads")
          bs (read-torrent-file torrent-path)]
      (if (nil? bs)
        (System/exit 1)
        (let [parse-result (torrent/parse-torrent bs)]
          (if (:error parse-result)
            (do
              (println "Failed to parse torrent:")
              (println "  " (:message parse-result))
              (System/exit 1))
            (let [torrent-meta (:ok parse-result)
                  info (:info torrent-meta)
                  required-size (torrent/total-size info)
                  _ (println (str "Torrent: " (:name info)))
                  _ (println (str "Size: " (format-bytes required-size)))
                  _ (println (str "Piece length: " (:piece-length info)))
                  _ (println (str "Pieces type: " (type (:pieces info))))
                  piece-count (count (:pieces info))
                  _ (println (str "Pieces: " piece-count))
                  space-check (disk-impl/check-disk-space output-dir required-size)]
              (when (:error space-check)
                (println "WARNING: " (:message space-check))
                (println "Continuing anyway..."))
              (let [{:keys [manager time-port]} (make-ports)
                    _ (println "Starting download to: " output-dir)
                    result (download/start-download manager torrent-path output-dir)]
                (if (:error result)
                  (do
                    (println "Failed to start download:")
                    (println "  " (or (:message result)
                                      (str (:error result))))
                    (System/exit 1))
                  (let [download-id (:id result)
                        save-result (disk/save-state (:disk-port manager)
                                                     (assoc result
                                                            :torrent-path torrent-path
                                                            :output-dir output-dir))]
                    (when (:error save-result)
                      (println "Failed to save download state:")
                      (println "  " (:message save-result))
                      (System/exit 1))
                    (println (str "Download started: " download-id))
                    (println)
                    (print-progress (download/progress time-port result))
                    (println)
                    (let [final-download (download/run-download manager result)
                          save-result (disk/save-state (:disk-port manager)
                                                       (assoc final-download
                                                              :torrent-path torrent-path
                                                              :output-dir output-dir))]
                      (when (:error save-result)
                        (println "Failed to save download state:")
                        (println "  " (:message save-result))
                        (System/exit 1))
                      (println)
                      (print-progress (download/progress time-port final-download)))))))))))))

(defn- cmd-torrent-pause
  [args]
  (let [{:keys [manager]} (make-ports)
        state (load-command-state (:disk-port manager)
                                  cli-state/default-state-dir
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
  [args]
  (let [{:keys [manager time-port]} (make-ports)
        state (load-command-state (:disk-port manager)
                                  cli-state/default-state-dir
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
  [args]
  (let [{:keys [manager]} (make-ports)
        state (load-command-state (:disk-port manager)
                                  cli-state/default-state-dir
                                  args)]
    (if (nil? state)
      (do
        (println "No download found.")
        (System/exit 1))
      (do
        (println (str "Download: " (:id state)))
        (when (:torrent-path state)
          (println (str "Torrent: " (:torrent-path state))))
        (print-progress (download/progress (time-port/->RealTimePort) state))))))

(defn- cmd-torrent-stop
  [args]
  (let [{:keys [manager]} (make-ports)
        state (load-command-state (:disk-port manager)
                                  cli-state/default-state-dir
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
  [_args]
  (println "torrent.seed - Not yet implemented")
  (println "Seeding will be available in a future version.")
  (System/exit 1))

(def ^:private commands
  {"torrent.parse" {:fn cmd-torrent-parse
                    :desc "Parse and display torrent file metadata"}
   "torrent.download" {:fn cmd-torrent-download
                       :desc "Download files from a torrent"}
   "torrent.pause" {:fn cmd-torrent-pause
                    :desc "Pause an active download"}
   "torrent.resume" {:fn cmd-torrent-resume
                     :desc "Resume a paused or failed download"}
   "torrent.status" {:fn cmd-torrent-status
                     :desc "Show download status"}
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
  (println "\nExamples:")
  (println "  lein run torrent.parse path/to/file.torrent")
  (println "  lein run torrent.download path/to/file.torrent ./output")
  (println "  lein run torrent.status"))

(defn -main
  "BitTorrent client command-line interface.

   Usage: lein run <command> [args...]

   Available commands:
     torrent.parse <file>     Parse and display torrent metadata
     torrent.download <file> Download files from a torrent
     torrent.pause [id]     Pause an active download
     torrent.resume [id]    Resume a paused download
     torrent.status [id]    Show download status
     torrent.stop [id]       Stop a download
     torrent.seed <file>    Seed a torrent (not yet implemented)"
  [& args]
  (if (empty? args)
    (do
      (print-usage)
      (System/exit 1))
    (let [cmd (first args)
          cmd-args (rest args)
          handler (get commands cmd)]
      (if handler
        ((:fn handler) cmd-args)
        (do
          (println (str "Unknown command: " cmd))
          (println)
          (print-usage)
          (System/exit 1))))))
