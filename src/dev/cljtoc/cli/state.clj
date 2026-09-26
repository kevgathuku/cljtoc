(ns dev.cljtoc.cli.state
  "CLI state management for torrent downloads.
   
   Manages persistence of download state between CLI invocations.
   Uses human-readable filenames as download IDs."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [dev.cljtoc.ports.disk :as disk]))

(def default-state-dir "./torrent-state")

;; Encoding and ID scheme live in dev.cljtoc.ports.disk (the single
;; persistence seam); this namespace is a sync adapter over the same layout.

(defrecord DownloadState
           [id
            torrent-path
            output-dir
            state
            started-at
            error])

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

(defn load-most-recent
  "Load the most recently started download."
  ([]
   (load-most-recent default-state-dir))
  ([state-dir]
   (let [dir (io/file state-dir)]
     (when (.exists dir)
       (let [files (sort-by #(.lastModified %) > (.listFiles dir))]
         (when (seq files)
           (try
             (disk/decode-state (edn/read-string (slurp (first files))))
             (catch Exception _ nil))))))))

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

(defn id-from-path
  "Generate a human-readable ID from torrent file path."
  [torrent-path]
  (disk/id-from-path torrent-path))

(defn get-or-create-download-id
  "Get the download ID for a torrent path. The ID is stable per path, so
   calling this for a previously saved torrent resumes that same state file."
  ([torrent-path]
   (get-or-create-download-id torrent-path default-state-dir))
  ([torrent-path _state-dir]
   (id-from-path torrent-path)))

(defn has-active-download?
  "Check if there's an active download."
  ([]
   (has-active-download? default-state-dir))
  ([state-dir]
   (let [recent (load-most-recent state-dir)]
     (when recent
       (contains? #{:starting :downloading :paused} (:state recent))))))

(defn clear-state
  "Clear all download state from disk."
  ([]
   (clear-state default-state-dir))
  ([state-dir]
   (let [dir (io/file state-dir)]
     (when (.exists dir)
       (doseq [f (.listFiles dir)]
         (.delete f))))))
