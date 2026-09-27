(ns dev.cljtoc.test-doubles.disk
  "Mock disk port for testing download orchestration.
   
   Provides predictable responses for testing without actual disk I/O."
  (:require [dev.cljtoc.ports.disk :as disk]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as async]))

(defrecord MockDiskPort
           [config
            torrent-data
            piece-cache
            output-pieces
            layouts-initialized
            state-files
            directories-created]

  disk/IDiskPort
  (read-torrent-file [_ path]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [data (get @torrent-data path)]
          (async/>! ch {:ok data})
          (async/>! ch {:error :file-not-found :message (str "File not found: " path)})))
      ch))

  (read-piece [_ piece-index]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch (get @piece-cache piece-index)))
      ch))

  (write-piece [_ piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [err (:write-error config)]
          (async/>! ch err)
          (do
            (swap! piece-cache assoc piece-index bytes)
            (async/>! ch {:ok :written}))))
      ch))

  (write-output-piece [_ _info _output-dir piece-index bytes]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [err (or (:output-write-error config) (:write-error config))]
          (async/>! ch err)
          (do
            (swap! output-pieces assoc piece-index bytes)
            (async/>! ch {:ok :written}))))
      ch))

  (initialize-output-layout [_ info output-dir]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [err (:output-init-error config)]
          (async/>! ch err)
          (do
            (swap! layouts-initialized conj {:info info :output-dir output-dir})
            (async/>! ch {:ok :initialized}))))
      ch))

  (ensure-directory [_ path]
    (let [ch (async/chan 1)]
      (async/go
        (swap! directories-created conj path)
        (async/>! ch {:ok :created}))
      ch))

  (save-state [_ download]
    (let [ch (async/chan 1)]
      (async/go
        (swap! state-files assoc (:id download) download)
        (async/>! ch {:ok :saved}))
      ch))

  (load-state [_ id]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch {:ok (get @state-files id)}))
      ch))

  (delete-state [_ id]
    (let [ch (async/chan 1)]
      (async/go
        (swap! state-files dissoc id)
        (async/>! ch {:ok :deleted}))
      ch)))

(defn create
  "Create a mock disk port for testing.
   
   Options:
   - :torrent-data - map of path -> torrent metadata to return
   - :write-error - error map returned from write-piece instead of storing
   - :output-write-error - error map from write-output-piece (falls back to :write-error)"
  ([]
   (create {}))
  ([config]
   (->MockDiskPort config
                   (atom (or (:torrent-data config) {}))
                   (atom {})
                   (atom {})
                   (atom [])
                   (atom {})
                   (atom #{}))))

(s/fdef create
  :args (s/cat :config (s/? map?))
  :ret any?)

(defn add-torrent [mock-disk path torrent-metadata]
  (swap! (:torrent-data mock-disk) assoc path torrent-metadata))

(s/fdef add-torrent
  :args (s/cat :mock-disk any? :path string? :torrent-metadata any?)
  :ret map?)

(defn get-piece [mock-disk piece-index]
  (get @(:piece-cache mock-disk) piece-index))

(s/fdef get-piece
  :args (s/cat :mock-disk any? :piece-index nat-int?)
  :ret any?)

(defn get-output-piece [mock-disk piece-index]
  (get @(:output-pieces mock-disk) piece-index))

(s/fdef get-output-piece
  :args (s/cat :mock-disk any? :piece-index nat-int?)
  :ret any?)

(defn get-state [mock-disk id]
  (get @(:state-files mock-disk) id))

(s/fdef get-state
  :args (s/cat :mock-disk any? :id any?)
  :ret any?)
