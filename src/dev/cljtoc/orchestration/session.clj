(ns dev.cljtoc.orchestration.session
  "A session owns several downloads running in one process.

   Transmission-style multi-torrent use: one CLI invocation starts many
   downloads and keeps acting on all of them. The session holds the
   downloads and the start failures, so a bad path never strands its
   siblings.

   The session is the value, not the running: the supervision layer
   owns the running, one download-supervisor subtree per torrent (see
   dev.cljtoc.supervision.session-supervisor). The daemon session this
   record is a stepping stone toward lives across CLI invocations.

   Effects stay in the download manager's ports; this namespace only
   composes them."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [dev.cljtoc.domain.torrent :as torrent]
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

(defn- failure
  "Record a path the session refused, without disturbing the downloads it
   already accepted."
  [session torrent-path error message]
  (update session :failures conj {:torrent-path torrent-path
                                  :error error
                                  :message message}))

(defn- declared-outputs
  "The absolute output paths a started download declares, from the same
   layout derivation its writer uses. Nil when the layout is invalid — the
   run fails on that later, so the session has nothing to compare."
  [download]
  (let [layout (torrent/compile-output-layout (get-in download [:torrent :info]))
        sizes (:sizes (:ok layout))]
    (when sizes
      (into #{} (map (fn [relative] (into [(:output-dir download)] relative)))
            (keys sizes)))))

(defn- overlapping-output
  "The first output path the candidate shares with an accepted download, as
   {:id other-id :path path}, or nil. Two torrents writing one file would
   each report :completed while overwriting the other's verified bytes."
  [accepted candidate]
  (when-let [candidate-paths (declared-outputs candidate)]
    (some (fn [other]
            (when-let [other-paths (declared-outputs other)]
              (when-let [shared (some #(when (contains? other-paths %) %) candidate-paths)]
                {:id (:id other) :path shared})))
          accepted)))

(defn start-torrents
  "Start one download per torrent path in a single session. A path that
   refuses to start lands in :failures and the rest still start, so one
   bad torrent never strands the others. A record that failed its announce
   still names a resumable download, so it stays in :downloads for saving
   and a later `torrent.resume`.

   Two paths whose torrents share a basename derive the same id — they
   would collide on one map entry and one state file — so the later one
   lands in :failures as :duplicate-id instead of silently replacing the
   first. Two torrents that declare the same output path would overwrite
   each other's verified bytes, so the later one is refused as
   :overlapping-output."
  [session torrent-paths output-dir]
  (reduce (fn [acc torrent-path]
            (let [result (download/start-download (:manager acc)
                                                  torrent-path
                                                  output-dir)]
              (cond
                (and (:error result) (nil? (:state result)))
                (failure acc torrent-path (:error result) (:message result))

                (contains? (:downloads acc) (:id result))
                (failure acc torrent-path :duplicate-id
                         (str "id " (:id result)
                              " already started by another path"))

                :else
                (let [candidate (assoc result :torrent-path torrent-path)]
                  (if-let [{other-id :id shared-path :path}
                           (overlapping-output (vals (:downloads acc)) candidate)]
                    (failure acc torrent-path :overlapping-output
                             (str "output " (str/join "/" shared-path)
                                  " is also written by download " other-id))
                    (update acc :downloads assoc (:id result) candidate))))))
          session
          torrent-paths))

;; Excluded from stest/check: announces to the tracker, so the generator
;; would have to conjure a live network port.
(s/fdef start-torrents
  :args (s/cat :session ::session
               :torrent-paths (s/coll-of ::torrent-path)
               :output-dir string?)
  :ret ::session)
