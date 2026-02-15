(ns dev.cljtoc.core
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.torrent :as torrent]
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
  "Convert a parsed torrent map into a printable form by replacing
  byte arrays with human-readable representations."
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

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn- cmd-torrent-parse
  "Parse a .torrent file and print its metadata."
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
  "Download files from a torrent (not yet implemented)."
  [args]
  (println "torrent.download - Not yet implemented")
  (System/exit 1))

(defn- cmd-torrent-seed
  "Seed a torrent (not yet implemented)."
  [args]
  (println "torrent.seed - Not yet implemented")
  (System/exit 1))

(def ^:private commands
  {"torrent.parse" {:fn cmd-torrent-parse
                    :desc "Parse and display torrent file metadata"}
   "torrent.download" {:fn cmd-torrent-download
                       :desc "Download files from a torrent"}
   "torrent.seed" {:fn cmd-torrent-seed
                   :desc "Seed a torrent"}})

(defn- print-usage []
  (println "BitTorrent Client CLI")
  (println "\nUsage: lein run <command> [args...]")
  (println "\nAvailable commands:")
  (doseq [[cmd {:keys [desc]}] (sort-by key commands)]
    (println (format "  %-20s %s" cmd desc)))
  (println "\nExamples:")
  (println "  lein run torrent.parse path/to/file.torrent"))

(defn -main
  "BitTorrent client command-line interface.

  Usage: lein run <command> [args...]

  Available commands:
    torrent.parse <file>     Parse and display torrent metadata
    torrent.download <file>  Download files from a torrent (not yet implemented)
    torrent.seed <file>      Seed a torrent (not yet implemented)"
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
