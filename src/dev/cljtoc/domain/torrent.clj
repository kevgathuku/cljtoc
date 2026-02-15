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
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-required-fields [torrent]
  (let [errors (transient [])]
    (when-not (:announce torrent)
      (conj! errors (bencode/torrent-error "missing required field: announce" {})))
    (when-not (:info torrent)
      (conj! errors (bencode/torrent-error "missing required field: info" {})))
    (when (:info torrent)
      (when-not (get-in torrent [:info :name])
        (conj! errors (bencode/torrent-error "missing required field: info.name" {})))
      (when-not (get-in torrent [:info :piece-length])
        (conj! errors (bencode/torrent-error "missing required field: info.piece-length" {})))
      (when-not (get-in torrent [:info :pieces])
        (conj! errors (bencode/torrent-error "missing required field: info.pieces" {}))))
    (persistent! errors)))

(defn validate-field-types [torrent]
  (let [errors (transient [])
        info (:info torrent)]
    (when info
      (when (and (:piece-length info) (not (integer? (:piece-length info))))
        (conj! errors (bencode/torrent-error
                       "type mismatch: piece-length must be an integer"
                       {:field "piece-length" :actual (type (:piece-length info))})))
      (when (and (:length info) (not (integer? (:length info))))
        (conj! errors (bencode/torrent-error
                       "type mismatch: length must be an integer"
                       {:field "length" :actual (type (:length info))}))))
    (persistent! errors)))

(defn validate-pieces-length [torrent]
  (let [pieces (get-in torrent [:info :pieces])]
    (if (or (nil? pieces) (empty? pieces))
      []
      (vec (keep-indexed
            (fn [idx ^bytes piece]
              (when (not= 20 (alength piece))
                (bencode/torrent-error
                 (str "piece " idx " is " (alength piece) " bytes, expected 20")
                 {:piece-index idx :actual-length (alength piece)})))
            pieces)))))

(defn validate-piece-length [torrent]
  (let [pl (get-in torrent [:info :piece-length])]
    (if (and (integer? pl) (pos? pl))
      []
      [(bencode/torrent-error
        (str "piece-length must be a positive integer, got: " pl)
        {:field "piece-length" :value pl})])))

(defn validate-torrent [torrent]
  (let [errors (vec (concat (validate-required-fields torrent)
                            (validate-field-types torrent)
                            (validate-piece-length torrent)
                            (validate-pieces-length torrent)))]
    (if (empty? errors)
      {:ok true}
      {:error errors})))

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
                      (let [parsed (cond-> {:announce (:announce announce-urls)
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
                                     (assoc :encoding (get decoded "encoding")))
                            validation (validate-torrent parsed)]
                        (if (:ok validation)
                          {:ok parsed}
                          validation)))))))))))))

