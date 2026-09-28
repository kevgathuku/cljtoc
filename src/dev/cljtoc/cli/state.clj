(ns dev.cljtoc.cli.state
  "CLI state management for torrent downloads.
   
   Manages persistence of download state between CLI invocations.
   Uses human-readable filenames as download IDs."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [dev.cljtoc.ports.disk :as disk]))

(def default-state-dir "./torrent-state")

;; Encoding and ID scheme live in dev.cljtoc.ports.disk (the single
;; persistence seam); this namespace is a sync adapter over the same layout.

(defn state-file-path [id state-dir]
  (str state-dir "/" id ".edn"))

(defn load-state
  "Load download state from disk.
   If id is provided, loads that specific download.
   Otherwise loads the most recent download."
  ([id]
   (load-state id default-state-dir))
  ([id state-dir]
   (let [path (state-file-path id state-dir)
         file (io/file path)]
     (if (.exists file)
       (try
         (disk/decode-state (edn/read-string (slurp path)))
         (catch Exception _
           nil))
       nil))))

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

(defn save-state
  "Save download state to disk."
  ([download]
   (save-state download default-state-dir))
  ([download state-dir]
   (let [id (:id download)
         path (state-file-path id state-dir)
         file (io/file path)]
     (io/make-parents file)
     (spit path (pr-str (disk/encode-state download))))))

(defn delete-state
  "Delete download state from disk."
  ([id]
   (delete-state id default-state-dir))
  ([id state-dir]
   (let [path (state-file-path id state-dir)
         file (io/file path)]
     (when (.exists file)
       (.delete file)))))


