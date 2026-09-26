(ns dev.cljtoc.ports.disk-impl
  "Real disk I/O implementation for download orchestration.
   
   Implements the IDiskPort protocol for actual file system operations.
   Includes disk space checking and state persistence."
  (:require [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.domain.torrent :as torrent]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.async :as async])
  (:import [java.io File FileInputStream FileOutputStream RandomAccessFile]
           [java.nio.file Files Paths]
           [java.util Arrays]))

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
            (clojure.java.io/copy bytes piece-file)
            (async/>! ch {:ok :written}))
          (catch Exception e
            (async/>! ch {:error :write-error :message (.getMessage e)}))))
      ch))

  (write-output-piece [this info output-dir piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (try
          (let [spans-result (torrent/piece-file-spans info piece-index (alength ^bytes bytes))
                sizes-result (torrent/output-file-sizes info)]
            (if (:error spans-result)
              (async/>! ch {:error :invalid-info
                            :message (str "Cannot map piece " piece-index ": "
                                          (:message spans-result))})
              (do
                ;; One handle per touched file: write this piece's spans,
                ;; then truncate to the declared length so a longer file
                ;; left by an earlier run cannot leave stale trailing bytes.
                (doseq [[file-path file-spans] (group-by :path (:ok spans-result))]
                  (let [out-file (apply io/file output-dir file-path)
                        parent (.getParentFile out-file)]
                    (when parent
                      (.mkdirs parent))
                    (let [raf (RandomAccessFile. out-file "rw")]
                      (try
                        (doseq [{file-offset :file-offset
                                 data-offset :data-offset
                                 span-length :length} file-spans]
                          (let [slice (Arrays/copyOfRange ^bytes bytes
                                                          (int data-offset)
                                                          (int (+ data-offset span-length)))]
                            (.seek raf file-offset)
                            (.write raf slice)))
                        (when-let [declared (get (:ok sizes-result) file-path)]
                          (.setLength raf declared))
                        (finally (.close raf))))))
                (async/>! ch {:ok :written}))))
          (catch Exception error
            (async/>! ch {:error :write-error :message (.getMessage error)}))))
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
            (spit state-file (pr-str (disk/encode-state download)))
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
              (let [data (disk/decode-state (edn/read-string (slurp state-file)))]
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

(defn ensure-directory
  "Ensure a directory exists, creating it if necessary."
  [path]
  (let [file (io/file path)]
    (when-not (.exists file)
      (.mkdirs file))
    file))

(defn check-disk-space
  "Check if there's enough disk space for the torrent.
   Returns {:ok true} if space is sufficient, {:error :insufficient-space} if not."
  [path required-bytes]
  (let [_ (ensure-directory path)
        available (available-space path)]
    (if (and available (pos? available) (< available required-bytes))
      {:error :insufficient-space
       :message (format "Insufficient disk space: need %s bytes, have %s bytes"
                        required-bytes available)}
      {:ok true})))

(defn get-torrent-size
  "Get total size of torrent from metadata."
  [torrent-metadata]
  (torrent/total-size torrent-metadata))
