(ns dev.cljtoc.cli.state
  "CLI state management for torrent downloads.
   
   Manages persistence of download state between CLI invocations.
   Uses human-readable filenames as download IDs."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [dev.cljtoc.ports.disk :as disk]))

;; File mtimes drive the most-recent scan here; fail the compile on
;; reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

;; Encoding and ID scheme live in dev.cljtoc.ports.disk (the single
;; persistence seam); this namespace is a sync adapter over the same layout.

(defn state-file-path [id state-dir]
  (str state-dir "/" id ".edn"))

(defn- state-id
  "The download id for a state file: the filename without the .edn suffix."
  [file]
  (str/replace (.getName ^java.io.File file) #"\.edn$" ""))

(defn download-record?
  "Whether a decoded state file is a download record the CLI can act on.
   Valid EDN of the wrong shape (a bare number, an empty map, a map missing
   piece state) is corrupt too: listing it would throw inside progress and
   hide every later record from a status listing."
  [value]
  (and (map? value)
       (string? (:id value))
       (keyword? (:state value))
       (map? (:piece-state value))
       (integer? (get-in value [:piece-state :total-pieces]))
       (map? (:stats value))))

(s/fdef download-record?
  :args (s/cat :value any?)
  :ret boolean?)

(defn load-record
  "Load one state file through the port, keeping it only when it decodes to
   a download record filed under this id. A record whose :id disagrees with
   its filename is corrupt too — returning it would let a named lookup show
   or act on another download. Nil means absent, unreadable, the wrong
   shape, or the wrong id — the CLI's single missing-state story."
  [disk-port id]
  (let [result (disk/load-state disk-port id)
        record (:ok result)]
    (when (and (download-record? record)
               (= id (:id record)))
      record)))

(s/fdef load-record
  :args (s/cat :disk-port any? :id string?)
  :ret (s/nilable map?)
  :fn #(let [ret (:ret %)] (or (nil? ret) (download-record? ret))))

(defn load-most-recent
  "Load the most recently started download. Dir-scanning by recency lives
   here (the port has no equivalent); the bytes move through the port."
  ([disk-port state-dir]
   (let [dir (io/file state-dir)]
     (when (.exists dir)
       (let [files (sort-by #(.lastModified ^java.io.File %) > (.listFiles dir))]
         (when (seq files)
           (load-record disk-port (state-id (first files)))))))))

(defn load-all
  "Load every saved download record, most recently modified first.
   Dir-scanning lives here (the port has no listing); the bytes move
   through the port. A corrupt record is skipped, not fatal: one broken
   file must not hide the rest from a status listing."
  [disk-port state-dir]
  (let [dir (io/file state-dir)]
    (if-not (.exists dir)
      []
      (let [files (sort-by (fn [^java.io.File file]
                             [(.lastModified file) (.getName file)])
                           (fn [left right] (compare right left))
                           (.listFiles dir))]
        (into [] (keep #(load-record disk-port (state-id %))) files)))))

(s/fdef load-all
  :args (s/cat :disk-port any? :state-dir string?)
  :ret vector?)



