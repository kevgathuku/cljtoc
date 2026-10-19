(ns dev.cljtoc.orchestration.session
  "A session owns several downloads running in one process.

   Transmission-style multi-torrent use: one CLI invocation starts many
   downloads and keeps acting on all of them. The session holds the
   downloads and the start failures, so a bad path never strands its
   siblings. `run-downloads` gives every download its own thread, so one
   torrent's stall or failure does not serialize the rest.

   Effects stay in the download manager's ports; this namespace only
   composes them."
  (:require [clojure.spec.alpha :as s]
            [dev.cljtoc.orchestration.download :as download]))

(set! *warn-on-reflection* true)

(defrecord Session [manager downloads failures])

;; ============================================================================
;; Specs
;; ============================================================================

(s/def ::manager map?)

;; The id is the torrent basename, so it is the key the session and the
;; on-disk state file agree on. Requiring it as a string pins `downloads`'
;; sort and keeps a mixed-type id out of the generative check.
(s/def ::id string?)

(s/def ::download-record (s/keys :req-un [::id]))

(s/def ::downloads (s/map-of string? ::download-record))

(s/def ::torrent-path string?)

(s/def ::error keyword?)

(s/def ::start-failure
  (s/keys :req-un [::torrent-path ::error]))

(s/def ::failures (s/coll-of ::start-failure))

(s/def ::session
  (s/keys :req-un [::manager ::downloads ::failures]))

;; ============================================================================
;; Session
;; ============================================================================

(defn session
  "An empty session over one download manager."
  [manager]
  (->Session manager {} []))

(s/fdef session
  :args (s/cat :manager ::manager)
  :ret ::session)

(defn downloads
  "The session's download records, ordered by id."
  [session]
  (into [] (sort-by :id (vals (:downloads session)))))

(s/fdef downloads
  :args (s/cat :session ::session)
  :ret (s/coll-of map?))

(defn failures
  "Start failures, in the order the paths were given."
  [session]
  (:failures session))

(s/fdef failures
  :args (s/cat :session ::session)
  :ret ::failures)

(defn start-torrents
  "Start one download per torrent path in a single session. A path that
   refuses to start lands in :failures and the rest still start, so one
   bad torrent never strands the others. Two paths whose torrents share a
   basename derive the same id — they would collide on one map entry and
   one state file — so the later one lands in :failures as :duplicate-id
   instead of silently replacing the first."
  [session torrent-paths output-dir]
  (reduce (fn [acc torrent-path]
            (let [result (download/start-download (:manager acc)
                                                  torrent-path
                                                  output-dir)]
              (cond
                (:error result)
                (update acc :failures conj {:torrent-path torrent-path
                                            :error (:error result)
                                            :message (:message result)})
                (contains? (:downloads acc) (:id result))
                (update acc :failures conj
                        {:torrent-path torrent-path
                         :error :duplicate-id
                         :message (str "id " (:id result)
                                       " already started by another path")})
                :else
                (update acc :downloads assoc (:id result)
                        (assoc result :torrent-path torrent-path)))))
          session
          torrent-paths))

;; Excluded from stest/check: announces to the tracker, so the generator
;; would have to conjure a live network port.
(s/fdef start-torrents
  :args (s/cat :session ::session
               :torrent-paths (s/coll-of ::torrent-path)
               :output-dir string?)
  :ret ::session)

(defn- run-one
  "Run one download on its own thread, folding an ordinary exception into a
   failed record so one torrent cannot take the others down. Errors such as
   OutOfMemoryError are not a torrent's failure and are left to propagate."
  [manager download]
  (future
    (try
      (download/run-download manager download)
      (catch Exception error
        (assoc download
               :state :failed
               :error {:reason :run-exception
                       :message (.getMessage error)})))))

(defn run-downloads
  "Run every download to a terminal state, each on its own thread. Blocks
   until all have finished and returns the session holding the final
   records."
  [session]
  (let [manager (:manager session)
        runs (mapv (fn [download]
                     [(:id download) (run-one manager download)])
                   (downloads session))]
    (assoc session :downloads
           (into {} (map (fn [[id run]] [id @run])) runs))))

;; Excluded from stest/check: dials peers and blocks on the swarm, so the
;; generator cannot produce the ports it runs against.
(s/fdef run-downloads
  :args (s/cat :session ::session)
  :ret ::session)
