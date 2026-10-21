(ns dev.cljtoc.core
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.ports.randomness-impl :as randomness-impl]
            [dev.cljtoc.ports.time :as time-port]
            [dev.cljtoc.cli.config :as cli-config]
            [dev.cljtoc.rpc :as rpc]
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

(defn make-ports
  "Build the ports the CLI and the daemon run with.

   The daemon and any local run read the same state dir and the same piece
   cache: resume loads the record a start saved and materializes the pieces
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

(defn- cmd-torrent-start
  [settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.start <path-to-torrent-file> [more.torrent ...]")
      (System/exit 1))
    (let [response (rpc/request (:rpc-port settings) :start {:paths (vec args)})]
      (if (:error response)
        (do
          (println (str "Failed to start: " (:message response)))
          (System/exit 1))
        (let [{:keys [downloads failures]} (:ok response)]
          (doseq [{:keys [torrent-path error message]} failures]
            (println (str "Failed to start " torrent-path ": " (or message (name error)))))
          (doseq [id downloads]
            (println (str "Started: " id)))
          (when (or (empty? downloads) (seq failures))
            (System/exit 1)))))))

(defn- cmd-torrent-pause
  [settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.pause <download-id>")
      (System/exit 1))
    (let [response (rpc/request (:rpc-port settings) :pause {:id (first args)})]
      (if (:error response)
        (do
          (println (str "Failed to pause: " (:message response)))
          (System/exit 1))
        (do
          (println "Download paused.")
          (print-progress (get-in response [:ok :record])))))))

(defn- cmd-torrent-resume
  [settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.resume <download-id>")
      (System/exit 1))
    (let [response (rpc/request (:rpc-port settings) :resume {:id (first args)})]
      (if (:error response)
        (do
          (println (str "Failed to resume: " (:message response)))
          (System/exit 1))
        (do
          (println "Download resumed.")
          (print-progress (get-in response [:ok :record])))))))

(defn- cmd-torrent-status
  [settings args]
  (let [response (rpc/request (:rpc-port settings) :status
                              (if (seq args) {:id (first args)} {}))]
    (if (:error response)
      (do
        (println (str "Failed to get status: " (:message response)))
        (System/exit 1))
      (let [records (get-in response [:ok :records])]
        (if (empty? records)
          (do
            (println "No download found.")
            (System/exit 1))
          (doseq [record records]
            (println (str "Download: " (:id record)))
            (when (:torrent-path record)
              (println (str "Torrent: " (:torrent-path record))))
            (print-progress record)
            (println)))))))

(defn- cmd-torrent-stop
  [settings args]
  (if (empty? args)
    (do
      (println "Usage: lein run torrent.stop <download-id>")
      (System/exit 1))
    (let [response (rpc/request (:rpc-port settings) :stop {:id (first args)})]
      (if (:error response)
        (do
          (println (str "Failed to stop: " (:message response)))
          (System/exit 1))
        (do
          (println "Download stopped.")
          (print-progress (get-in response [:ok :record])))))))

(defn- cmd-torrent-seed
  [_settings _args]
  (println "torrent.seed - Not yet implemented")
  (println "Seeding will be available in a future version.")
  (System/exit 1))

(def ^:private commands
  {"torrent.parse" {:fn cmd-torrent-parse
                    :desc "Parse and display torrent file metadata"}
   "torrent.start" {:fn cmd-torrent-start
                    :desc "Start one or more torrents via the daemon"}
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
  (println "  lein run torrent.start path/to/file.torrent [more.torrent ...]")
  (println "  lein run --config ./my.edn torrent.status"))

(defn -main
  "BitTorrent client command-line interface.

   Usage: lein run <command> [args...]

   Available commands:
     torrent.parse <file>     Parse and display torrent metadata
     torrent.start <file>...    Start one or more torrents via the daemon
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
              (do
                ((:fn handler) settings cmd-args)
                ;; `future` runs on Clojure's non-daemon agent pool; without
                ;; this the JVM lingers ~60s after the command succeeds.
                ;; The CLI is the lifecycle boundary the session API must
                ;; not own.
                (shutdown-agents))
              (do
                (println (str "Unknown command: " cmd))
                (println)
                (print-usage)
                (System/exit 1)))))))))
