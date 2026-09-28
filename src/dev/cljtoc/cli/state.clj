(ns dev.cljtoc.cli.state
  "CLI state management for torrent downloads.
   
   Manages persistence of download state between CLI invocations.
   Uses human-readable filenames as download IDs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dev.cljtoc.ports.disk :as disk]))

(def default-state-dir "./torrent-state")

;; Encoding and ID scheme live in dev.cljtoc.ports.disk (the single
;; persistence seam); this namespace is a sync adapter over the same layout.

(defn state-file-path [id state-dir]
  (str state-dir "/" id ".edn"))

(defn- state-id
  "The download id for a state file: the filename without the .edn suffix."
  [file]
  (str/replace (.getName ^java.io.File file) #"\.edn$" ""))

(defn load-most-recent
  "Load the most recently started download. Dir-scanning by recency lives
   here (the port has no equivalent); the bytes move through the port."
  ([disk-port]
   (load-most-recent disk-port default-state-dir))
  ([disk-port state-dir]
   (let [dir (io/file state-dir)]
     (when (.exists dir)
       (let [files (sort-by #(.lastModified %) > (.listFiles dir))]
         (when (seq files)
           (:ok (disk/load-state disk-port (state-id (first files))))))))))



