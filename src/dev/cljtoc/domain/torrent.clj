(ns dev.cljtoc.domain.torrent
  (:require [dev.cljtoc.domain.bencode :as bencode])
  (:import [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Info dict extraction
;; ---------------------------------------------------------------------------

(defn extract-info-dict-bytes [^bytes torrent-bytes]
  (let [span-result (bencode/find-dict-value-span torrent-bytes "info")]
    (if (:error span-result)
      span-result
      (let [[start end] (:ok span-result)]
        {:ok (Arrays/copyOfRange torrent-bytes (int start) (int end))}))))

(defn compute-info-hash [^bytes torrent-bytes]
  (let [info-result (extract-info-dict-bytes torrent-bytes)]
    (if (:error info-result)
      info-result
      {:ok (bencode/sha1-hash (:ok info-result))})))

;; ---------------------------------------------------------------------------
;; Announce URL extraction
;; ---------------------------------------------------------------------------

(defn extract-announce-urls [decoded-dict]
  {:announce (get decoded-dict "announce")
   :announce-list (get decoded-dict "announce-list")})
