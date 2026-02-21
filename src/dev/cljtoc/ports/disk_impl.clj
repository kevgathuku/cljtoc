(ns dev.cljtoc.ports.disk-impl
  "Real disk I/O implementation for download orchestration.
   
   Implements the IDiskPort protocol for actual file system operations.
   Includes disk space checking and state persistence."
  (:require [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.domain.torrent :as torrent]
            [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import [java.io File FileInputStream FileOutputStream]
           [java.nio.file Files Paths]))

(defrecord DiskPortImpl
  [state-dir
   piece-cache-dir
   config]
  
  disk/IDiskPort
  (read-torrent-file [this path]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [file (io/file path)]
            (if (.exists file)
              (let [bytes (Files/readAllBytes (.toPath file))
                    parse-result (torrent/parse-torrent bytes)]
                (if (:error parse-result)
                  (async/>! ch {:error :invalid-torrent :message (str "Failed to parse: " (get-in parse-result [:error :message]))})
                  (async/>! ch {:ok (:ok parse-result)})))
              (async/>! ch {:error :file-not-found :message (str "File not found: " path)})))
          (catch Exception e
            (async/>! ch {:error :read-error :message (.getMessage e)}))))
      ch))
  
  (read-piece [this piece-index]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [piece-file (io/file piece-cache-dir (str "piece-" piece-index ".dat"))]
            (if (.exists piece-file)
              (async/>! ch {:ok (Files/readAllBytes (.toPath piece-file))})
              (async/>! ch {:ok nil})))
          (catch Exception e
            (async/>! ch {:error :read-error :message (.getMessage e)}))))
      ch))
  
  (write-piece [this piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [piece-file (io/file piece-cache-dir (str "piece-" piece-index ".dat"))
                parent (.getParentFile piece-file)]
            (when-not (.exists parent)
              (.mkdirs parent))
            (Files/write (.toPath piece-file) bytes)
            (async/>! ch {:ok :written}))
          (catch Exception e
            (async/>! ch {:error :write-error :message (.getMessage e)}))))
      ch))
  
  (ensure-directory [this path]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [dir (io/file path)]
            (when-not (.exists dir)
              (.mkdirs dir))
            (async/>! ch {:ok :created}))
          (catch Exception e
            (async/>! ch {:error :mkdir-error :message (.getMessage e)}))))
      ch))
  
  (save-state [this download]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str (:id download) ".edn"))]
            (when-not (.exists state-dir)
              (.mkdirs state-dir))
            (spit state-file (pr-str download))
            (async/>! ch {:ok :saved}))
          (catch Exception e
            (async/>! ch {:error :save-error :message (.getMessage e)}))))
      ch))
  
  (load-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str id ".edn"))]
            (if (.exists state-file)
              (let [data (read-string (slurp state-file))]
                (async/>! ch {:ok data}))
              (async/>! ch {:ok nil})))
          (catch Exception e
            (async/>! ch {:error :load-error :message (.getMessage e)}))))
      ch))
  
  (delete-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [state-file (io/file state-dir (str id ".edn"))]
            (when (.exists state-file)
              (.delete state-file))
            (async/>! ch {:ok :deleted}))
          (catch Exception e
            (async/>! ch {:error :delete-error :message (.getMessage e)}))))
      ch)))

(defn create
  "Create a DiskPortImpl instance.
   
   Options:
   - :state-dir - directory for download state (default: ./torrent-state)
   - :piece-cache-dir - directory for cached pieces (default: ./torrent-cache)"
  ([]
   (create {}))
  ([{:keys [state-dir piece-cache-dir config]
     :or {state-dir "./torrent-state"
          piece-cache-dir "./torrent-cache"}}]
   (->DiskPortImpl (io/file state-dir)
                   (io/file piece-cache-dir)
                   config)))

(defn available-space
  "Get available disk space in bytes for the given path."
  [path]
  (try
    (let [file (io/file path)]
      (.getUsableSpace file))
    (catch Exception _
      nil)))

(defn check-disk-space
  "Check if there's enough disk space for the torrent.
   Returns {:ok true} if space is sufficient, {:error :insufficient-space} if not."
  [path required-bytes]
  (let [available (available-space path)]
    (if (and available (< available required-bytes))
      {:error :insufficient-space
       :message (format "Insufficient disk space: need %s bytes, have %s bytes"
                        required-bytes available)}
      {:ok true})))

(defn get-torrent-size
  "Get total size of torrent from metadata."
  [torrent-metadata]
  (or (:length torrent-metadata)
      (reduce + (map :length (:files torrent-metadata)))
      0))
