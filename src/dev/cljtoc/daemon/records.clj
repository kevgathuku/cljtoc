(ns dev.cljtoc.daemon.records
  "The daemon's live view of its torrents: one record per download id.

   The session supervisor publishes records as downloads move, and the
   daemon keeps them so the RPC sees a download before it finishes. The
   map is an atom, because the RPC's handlers and the session's progress
   both update it from their own threads."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(s/def ::id string?)

(s/def ::record (s/keys :req-un [::id]))

;; The view's contents: one download record per id.
(s/def ::by-id (s/map-of ::id ::record))

;; The view itself: an atom of id to record, because the RPC handlers
;; and the session's progress both update it from their own threads.
(s/def ::records
  (s/with-gen (s/and #(instance? clojure.lang.Atom %)
                     #(s/valid? ::by-id @%))
    #(gen/return (atom {}))))

(defn create
  "An empty records view seeded with the given records."
  [records]
  (atom (into {} (map (juxt :id identity)) records)))

(s/fdef create
  :args (s/cat :records (s/coll-of ::record))
  :ret ::records)

(defn record-for
  "The record the daemon holds for an id, or nil."
  [records id]
  (get @records id))

(s/fdef record-for
  :args (s/cat :records ::records :id ::id)
  :ret (s/nilable ::record))

(defn put
  "Record a download's current state: the RPC's pause, the session's
   progress and its completion all land here."
  [records record]
  (swap! records assoc (:id record) record))

(s/fdef put
  :args (s/cat :records ::records :record ::record)
  :ret ::by-id)

(defn all
  "Every record the daemon owns, in no particular order."
  [records]
  ;; vec, not vals: vals of an empty view is nil, and the status
  ;; command maps over this to build its response.
  (into [] (vals @records)))

(s/fdef all
  :args (s/cat :records ::records)
  :ret (s/coll-of ::record))

(defn forget
  "Forget a download the daemon no longer owns."
  [records id]
  (swap! records dissoc id))

(s/fdef forget
  :args (s/cat :records ::records :id ::id)
  :ret ::by-id)
