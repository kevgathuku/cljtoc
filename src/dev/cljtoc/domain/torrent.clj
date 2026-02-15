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

;; ---------------------------------------------------------------------------
;; Piece parsing
;; ---------------------------------------------------------------------------

(defn parse-pieces [^bytes piece-data]
  (let [len (alength piece-data)]
    (if (zero? len)
      []
      (vec (for [i (range 0 len 20)]
             (Arrays/copyOfRange piece-data (int i) (int (min (+ i 20) len))))))))

;; ---------------------------------------------------------------------------
;; Info dict parsing
;; ---------------------------------------------------------------------------

(defn- parse-file-entry [file-map]
  {:path (get file-map "path")
   :length (get file-map "length")})

(defn parse-info-dict [info-map]
  (let [name-val (get info-map "name")
        piece-length (get info-map "piece length")
        pieces-raw (get info-map "pieces")
        pieces (if (string? pieces-raw)
                 (parse-pieces (.getBytes ^String pieces-raw "ISO-8859-1"))
                 (if (instance? (Class/forName "[B") pieces-raw)
                   (parse-pieces pieces-raw)
                   []))
        length (get info-map "length")
        files (get info-map "files")
        private (get info-map "private")]
    {:ok (cond-> {:name name-val
                  :piece-length piece-length
                  :pieces pieces}
           length (assoc :length length)
           files (assoc :files (mapv parse-file-entry files))
           (some? private) (assoc :private (= 1 private)))}))

;; ---------------------------------------------------------------------------
;; Main torrent parser
;; ---------------------------------------------------------------------------

(defn parse-torrent [^bytes torrent-bytes]
  (let [decode-result (bencode/decode-bencode torrent-bytes)]
    (if (:error decode-result)
      decode-result
      (let [decoded (:ok decode-result)]
        (if-not (map? decoded)
          (bencode/torrent-error "torrent file must be a dict" {})
          (let [info-hash-result (compute-info-hash torrent-bytes)]
            (if (:error info-hash-result)
              info-hash-result
              (let [info-map (get decoded "info")]
                (if-not info-map
                  (bencode/torrent-error "missing info dict" {:keys-found (keys decoded)})
                  (let [info-result (parse-info-dict info-map)
                        announce-urls (extract-announce-urls decoded)]
                    (if (:error info-result)
                      info-result
                      {:ok (cond-> {:announce (:announce announce-urls)
                                    :announce-list (:announce-list announce-urls)
                                    :info (:ok info-result)
                                    :info-hash (:ok info-hash-result)}
                             (get decoded "comment")
                             (assoc :comment (get decoded "comment"))
                             (get decoded "created by")
                             (assoc :created-by (get decoded "created by"))
                             (get decoded "creation date")
                             (assoc :creation-date (get decoded "creation date"))
                             (get decoded "encoding")
                             (assoc :encoding (get decoded "encoding")))})))))))))))

