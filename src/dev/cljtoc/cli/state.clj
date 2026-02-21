(ns dev.cljtoc.cli.state
  "CLI state management for torrent downloads.
   
   Manages persistence of download state between CLI invocations.
   Uses human-readable filenames as download IDs."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:import [java.util UUID]))

(def default-state-dir "./torrent-state")

(defn- bytes->hex
  "Convert byte array to hex string for serialization."
  [ba]
  (if (bytes? ba)
    (apply str (map #(format "%02x" %) ba))
    ba))

(defn- record->map
  "Recursively convert all records to plain maps for EDN serialization.
   Also converts byte arrays to hex strings."
  [x]
  (walk/postwalk
    (fn [x]
      (cond
        (record? x) (into {} x)
        (bytes? x) (bytes->hex x)
        :else x))
    x))

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
         (edn/read-string (slurp path))
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
             (edn/read-string (slurp (first files)))
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
     (spit path (pr-str (record->map download))))))

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
  (let [file (io/file torrent-path)
        name (.getName file)
        ext (second (re-find #"\.([^.]+)$" name))]
    (if (and ext (not (empty? ext)))
      (subs name 0 (- (count name) (inc (count ext))))
      name)))

(defn get-or-create-download-id
  "Get the download ID, or create one from the torrent path."
  ([torrent-path]
   (get-or-create-download-id torrent-path default-state-dir))
  ([torrent-path state-dir]
   (let [id (id-from-path torrent-path)
         existing (load-state id state-dir)]
     (if existing
       id
       id))))

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
