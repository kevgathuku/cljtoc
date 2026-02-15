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

(defn -main
  "Parse a .torrent file and print its metadata.

  Usage: lein run <path-to-torrent-file>"
  [& args]
  (if (empty? args)
    (do
      (println "Usage: lein run <path-to-torrent-file>")
      (System/exit 1))
    (let [path (first args)
          bs (read-torrent-file path)]
      (when bs
        (print-torrent (torrent/parse-torrent bs))))))
