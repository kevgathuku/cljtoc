(ns dev.cljtoc.test-doubles.disk
  "Mock disk port for testing download orchestration.
   
   Provides predictable responses for testing without actual disk I/O."
  (:require [dev.cljtoc.ports.disk :as disk]
            [clojure.core.async :as async]))

(defrecord MockDiskPort
  [config
   torrent-data
   piece-cache
   state-files
   directories-created]
  
  disk/IDiskPort
  (read-torrent-file [this path]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [data (get @torrent-data path)]
          (async/>! ch {:ok data})
          (async/>! ch {:error :file-not-found :message (str "File not found: " path)})))
      ch))
  
  (read-piece [this piece-index]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch (get @piece-cache piece-index)))
      ch))
  
  (write-piece [this piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (swap! piece-cache assoc piece-index bytes)
        (async/>! ch {:ok :written}))
      ch))
  
  (ensure-directory [this path]
    (let [ch (async/chan 1)]
      (async/go
        (swap! directories-created conj path)
        (async/>! ch {:ok :created}))
      ch))
  
  (save-state [this download]
    (let [ch (async/chan 1)]
      (async/go
        (swap! state-files assoc (:id download) download)
        (async/>! ch {:ok :saved}))
      ch))
  
  (load-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch {:ok (get @state-files id)}))
      ch))
  
  (delete-state [this id]
    (let [ch (async/chan 1)]
      (async/go
        (swap! state-files dissoc id)
        (async/>! ch {:ok :deleted}))
      ch)))

(defn create
  "Create a mock disk port for testing.
   
   Options:
   - :torrent-data - map of path -> torrent metadata to return"
  ([]
   (create {}))
  ([config]
   (->MockDiskPort config
                   (atom (or (:torrent-data config) {}))
                   (atom {})
                   (atom {})
                   (atom #{}))))

(defn add-torrent [mock-disk path torrent-metadata]
  (swap! (:torrent-data mock-disk) assoc path torrent-metadata))

(defn get-piece [mock-disk piece-index]
  (get @(:piece-cache mock-disk) piece-index))

(defn get-state [mock-disk id]
  (get @(:state-files mock-disk) id))
