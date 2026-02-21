(ns dev.cljtoc.core
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.orchestration.manager :as manager]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.time :as time-port]
            [dev.cljtoc.cli.state :as cli-state]
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
  [args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.parse <path-to-torrent-file>")
      (System/exit 1))
    (let [path (first args)
          bs (read-torrent-file path)]
      (when bs
        (print-torrent (torrent/parse-torrent bs))))))

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
                  required-size (or (:length info)
                                    (reduce + (map :length (:files info))))
                  _ (println (str "Torrent: " (:name info)))
                  _ (println (str "Size: " (format-bytes required-size)))
                  _ (println (str "Piece length: " (:piece-length info)))
                  _ (println (str "Pieces type: " (type (:pieces info))))
                  piece-count (count (:pieces info))
                  _ (println (str "Pieces: " piece-count))
                  space-check (disk-impl/check-disk-space output-dir required-size)]
              (if (:error space-check)
                (do
                  (println "WARNING: " (:message space-check))
                  (println "Continuing anyway...")))
              (let [disk-port (disk-impl/create {:state-dir "./torrent-state"
                                                 :piece-cache-dir "./torrent-cache"})
                    network-port (network-impl/create)
                    time-port (time-port/->RealTimePort)
                    m (manager/manager network-port disk-port time-port {})
                    _ (println "Starting download to: " output-dir)
                    result (download/start-download m torrent-path output-dir)]
                (if (:error result)
                  (do
                    (println "Failed to start download:")
                    (println "  " (:message result))
                    (System/exit 1))
                  (let [download-id (cli-state/id-from-path torrent-path)]
                    (cli-state/save-state (assoc result :id download-id
                                                 :torrent-path torrent-path
                                                 :output-dir output-dir))
                    (println (str "Download started: " download-id))
                    (println)
                    (print-progress (download/progress result))))))))))))

(defn- cmd-torrent-pause
  [args]
  (let [state (if (seq args)
                (cli-state/load-state (first args))
                (cli-state/load-most-recent))]
    (if (nil? state)
      (do
        (println "No active download found.")
        (System/exit 1))
      (let [result (download/pause-download state)]
        (if (:error result)
          (do
            (println "Failed to pause: " (get-in result [:error :message]))
            (System/exit 1))
          (do
            (cli-state/save-state (get result :ok))
            (println "Download paused.")
            (print-progress (download/progress (get result :ok)))))))))

(defn- cmd-torrent-resume
  [args]
  (let [state (if (seq args)
                (cli-state/load-state (first args))
                (cli-state/load-most-recent))]
    (if (nil? state)
      (do
        (println "No paused download found.")
        (System/exit 1))
      (let [result (download/resume-download state)]
        (if (:error result)
          (do
            (println "Failed to resume: " (get-in result [:error :message]))
            (System/exit 1))
          (do
            (cli-state/save-state (get result :ok))
            (println "Download resumed.")
            (print-progress (download/progress (get result :ok)))))))))

(defn- cmd-torrent-status
  [args]
  (let [state (if (seq args)
                (cli-state/load-state (first args))
                (cli-state/load-most-recent))]
    (if (nil? state)
      (do
        (println "No download found.")
        (System/exit 1))
      (do
        (println (str "Download: " (:id state)))
        (when (:torrent-path state)
          (println (str "Torrent: " (:torrent-path state))))
        (print-progress (download/progress state))))))

(defn- cmd-torrent-stop
  [args]
  (let [state (if (seq args)
                (cli-state/load-state (first args))
                (cli-state/load-most-recent))]
    (if (nil? state)
      (do
        (println "No download found.")
        (System/exit 1))
      (let [stopped (download/stop-download state)]
        (cli-state/delete-state (:id state))
        (println "Download stopped.")
        (print-progress (download/progress stopped))))))

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
                     :desc "Resume a paused download"}
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
