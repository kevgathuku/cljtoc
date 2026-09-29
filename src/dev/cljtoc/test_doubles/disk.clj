(ns dev.cljtoc.test-doubles.disk
  "Mock disk port for testing download orchestration.

   Provides predictable responses for testing without actual disk I/O.
   Mirrors DiskPortImpl's envelopes exactly, so a test that passes here
   is testing the same contract the real port honours."
  (:require [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.domain.torrent :as torrent]
            [clojure.spec.alpha :as s]))

(defrecord MockDiskPort
           [config
            torrent-data
            piece-cache
            output-pieces
            output-layouts
            layouts-initialized
            state-files
            directories-created]

  disk/IDiskPort
  (read-torrent-file [_ path]
    (if-let [data (get @torrent-data path)]
      {:ok data}
      {:error :file-not-found :message (str "File not found: " path)}))

  (read-piece [_ info-hash piece-index]
    ;; Mirrors DiskPortImpl: a cache hit and a miss are both {:ok ...},
    ;; the miss carrying nil, and an unreadable cache file is an
    ;; {:error :read-error}. Entries are keyed [hash-scope piece-index]
    ;; on both ports, and a missing hash is refused on both ports — a mock
    ;; that stored flat or accepted hashless reads would pass tests the
    ;; real port fails.
    (if-let [err (:read-error config)]
      err
      (if-let [scope (disk/cache-scope info-hash)]
        {:ok (get @piece-cache [scope piece-index])}
        disk/invalid-info-hash-error)))

  (write-piece [_ info-hash piece-index bytes]
    (if-let [err (:write-error config)]
      err
      (if-let [scope (disk/cache-scope info-hash)]
        (do
          (swap! piece-cache assoc [scope piece-index] bytes)
          {:ok :written})
        disk/invalid-info-hash-error)))

  (write-output-piece [_ layout _output-dir piece-index bytes]
    ;; Mirrors DiskPortImpl's per-piece gates exactly: span derivation,
    ;; the O(1) shape gate, then touched-path membership. The full
    ;; invariant check (consistent-output-layout?) runs once at init on
    ;; both ports, so tests drive exactly the refusal paths the real
    ;; port produces — no stricter, no looser. The try/catch mirrors
    ;; the real port too: a bad bytes argument (alength throws) comes
    ;; back as {:error :write-error}, never an uncaught throw.
    (try
      (if-let [err (or (:output-write-error config) (:write-error config))]
        err
        (let [spans-result (torrent/layout-spans layout piece-index (alength ^bytes bytes))]
          (if-let [err (cond
                         (:error spans-result)
                         {:error :invalid-info
                          :message (str "Cannot map piece " piece-index ": "
                                        (:message spans-result))}

                         (not (disk/valid-output-layout? layout))
                         disk/invalid-output-layout-error

                         (not (every? #(contains? (:sizes layout) (:path %))
                                      (:ok spans-result)))
                         disk/invalid-output-layout-error

                         :else nil)]
            err
            (do
              (swap! output-layouts conj layout)
              (swap! output-pieces assoc piece-index bytes)
              {:ok :written}))))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (initialize-output-layout [_ layout output-dir]
    (if-let [err (or (:output-init-error config)
                     (when-not (disk/consistent-output-layout? layout)
                       disk/invalid-output-layout-error))]
      err
      (do
        (swap! layouts-initialized conj {:layout layout :output-dir output-dir})
        {:ok :initialized})))

  (prepare-output-layout [_ layout output-dir]
    ;; Mirrors DiskPortImpl's once-per-download gates without touching a
    ;; filesystem: the full invariant check plus an explicit prepared value
    ;; carrying one resolved entry per declared path, so orchestration
    ;; tests thread exactly what the real port hands the fast write path.
    (if (not (disk/consistent-output-layout? layout))
      disk/invalid-output-layout-error
      {:ok {:layout layout
            :output-dir output-dir
            :resolved (into {} (map (fn [declared-path]
                                      [declared-path {:file (pr-str declared-path)
                                                      :canonical (pr-str declared-path)
                                                      :identity nil}]))
                            (keys (:sizes layout)))}}))

  (write-prepared-piece [_ prepared output-dir piece-index bytes]
    ;; Mirrors DiskPortImpl's per-piece gates minus the filesystem: span
    ;; derivation, both shape gates, the prepared-for-this-dir binding,
    ;; then touched-path membership. The try/catch mirrors the real port
    ;; too: a bad bytes argument (alength throws) comes back as
    ;; {:error :write-error}, never an uncaught throw.
    (try
      (if-let [err (or (:output-write-error config) (:write-error config))]
        err
        (let [layout (:layout prepared)
              spans-result (torrent/layout-spans layout piece-index (alength ^bytes bytes))]
          (if-let [err (cond
                         (:error spans-result)
                         {:error :invalid-info
                          :message (str "Cannot map piece " piece-index ": "
                                        (:message spans-result))}

                         (not (disk/valid-output-layout? layout))
                         disk/invalid-output-layout-error

                         (not (disk/valid-prepared-layout? prepared))
                         disk/invalid-prepared-layout-error

                         (not= output-dir (:output-dir prepared))
                         disk/invalid-prepared-layout-error

                         (not (every? #(contains? (:sizes layout) (:path %))
                                      (:ok spans-result)))
                         disk/invalid-output-layout-error

                         :else nil)]
            err
            (do
              (swap! output-layouts conj layout)
              (swap! output-pieces assoc piece-index bytes)
              {:ok :written}))))
      (catch Exception error
        {:error :write-error :message (.getMessage error)})))

  (ensure-directory [_ path]
    (swap! directories-created conj path)
    {:ok :created})

  (save-state [_ download]
    ;; Mirrors DiskPortImpl: an encode refusal (e.g. an invalid pre-existing
    ;; {:cljtoc/bytes ...} tag) comes back as {:error :save-error}, never an
    ;; uncaught throw — fail fast at the write, identically on both ports.
    (try
      (swap! state-files assoc (:id download) (disk/encode-state download))
      {:ok :saved}
      (catch Exception error
        {:error :save-error :message (.getMessage error)})))

  (load-state [_ id]
    {:ok (disk/decode-state (get @state-files id))})

  (delete-state [_ id]
    (swap! state-files dissoc id)
    {:ok :deleted}))

(defn create
  "Create a mock disk port for testing.
   
   Options:
   - :torrent-data - map of path -> torrent metadata to return
   - :write-error - error map returned from write-piece instead of storing
   - :read-error - error map returned from read-piece (an unreadable cache)
   - :output-write-error - error map from write-output-piece (falls back to :write-error)"
  ([]
   (create {}))
  ([config]
   (->MockDiskPort config
                   (atom (or (:torrent-data config) {}))
                   (atom {})
                   (atom {})
                   (atom [])
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

(defn get-piece [mock-disk info-hash piece-index]
  (get @(:piece-cache mock-disk) [(disk/cache-scope info-hash) piece-index]))

(s/fdef get-piece
  :args (s/cat :mock-disk any? :info-hash any? :piece-index nat-int?)
  :ret any?)

(defn get-output-piece [mock-disk piece-index]
  (get @(:output-pieces mock-disk) piece-index))

(s/fdef get-output-piece
  :args (s/cat :mock-disk any? :piece-index nat-int?)
  :ret any?)

(defn get-output-layouts [mock-disk]
  @(:output-layouts mock-disk))

(s/fdef get-output-layouts
  :args (s/cat :mock-disk any?)
  :ret vector?)

(defn get-state [mock-disk id]
  (get @(:state-files mock-disk) id))

(s/fdef get-state
  :args (s/cat :mock-disk any? :id any?)
  :ret any?)
