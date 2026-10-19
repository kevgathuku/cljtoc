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

(defn load-most-recent
  "Load the most recently started download. Dir-scanning by recency lives
   here (the port has no equivalent); the bytes move through the port."
  ([disk-port state-dir]
   (let [dir (io/file state-dir)]
     (when (.exists dir)
       (let [files (sort-by #(.lastModified ^java.io.File %) > (.listFiles dir))]
         (when (seq files)
           (:ok (disk/load-state disk-port (state-id (first files))))))))))

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
        (into [] (keep #(:ok (disk/load-state disk-port (state-id %)))) files)))))

(s/fdef load-all
  :args (s/cat :disk-port any? :state-dir string?)
  :ret vector?)



