(ns dev.cljtoc.domain.torrent
  "Torrent metadata parser for .torrent files.

  Transforms raw .torrent file bytes into structured TorrentMetainfo maps.
  Computes the info hash from original bencoded bytes (never re-encoded)
  to ensure correctness. Supports single-file and multi-file torrents
  with comprehensive validation.

  All errors are returned as data maps, never thrown as exceptions."
  (:require [dev.cljtoc.domain.bencode :as bencode])
  (:import [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Info dict extraction
;; ---------------------------------------------------------------------------

(defn extract-info-dict-bytes
  "Extracts the raw bencoded bytes of the info dictionary from torrent bytes.
  Returns {:ok byte-array} or an error map. The bytes are copied from the
  original input to preserve the exact encoding for info hash computation."
  [^bytes torrent-bytes]
  (let [span-result (bencode/find-dict-value-span torrent-bytes "info")]
    (if (:error span-result)
      span-result
      (let [[start end] (:ok span-result)]
        {:ok (Arrays/copyOfRange torrent-bytes (int start) (int end))}))))

(defn compute-info-hash
  "Computes the 20-byte SHA-1 info hash from torrent bytes.
  The hash is computed over the original bencoded info dict bytes,
  not a re-encoded version. Returns {:ok byte-array} or an error map."
  [^bytes torrent-bytes]
  (let [info-result (extract-info-dict-bytes torrent-bytes)]
    (if (:error info-result)
      info-result
      {:ok (bencode/sha1-hash ^bytes (:ok info-result))})))

;; ---------------------------------------------------------------------------
;; Announce URL extraction
;; ---------------------------------------------------------------------------

(defn- bytes->str
  "Convert a byte array to a UTF-8 string, or return value as-is if not bytes."
  [v]
  (if (instance? (Class/forName "[B") v)
    (String. ^bytes v "UTF-8")
    v))

(defn extract-announce-urls
  "Extracts the primary announce URL and optional announce-list tiers
  from a decoded torrent dictionary. Returns a map with :announce and
  :announce-list (nil if not present)."
  [decoded-dict]
  {:announce (bytes->str (get decoded-dict "announce"))
   :announce-list (when-let [al (get decoded-dict "announce-list")]
                    (mapv (fn [tier] (mapv bytes->str tier)) al))})

;; ---------------------------------------------------------------------------
;; Piece parsing
;; ---------------------------------------------------------------------------

(defn parse-pieces
  "Splits a concatenated piece hash byte array into a vector of
  individual 20-byte SHA-1 hash arrays."
  [^bytes piece-data]
  (let [len (alength piece-data)]
    (if (zero? len)
      []
      (vec (for [i (range 0 len 20)]
             (Arrays/copyOfRange piece-data (int i) (int (min (+ i 20) len))))))))

;; ---------------------------------------------------------------------------
;; Info dict parsing
;; ---------------------------------------------------------------------------

(defn- parse-file-entry
  "Parse a file entry from a multi-file torrent info dict.
  Converts path strings from byte arrays to UTF-8."
  [file-map]
  {:path (mapv bytes->str (get file-map "path"))
   :length (get file-map "length")})

(defn parse-info-dict
  "Parses a decoded info dictionary into a structured map with keys:
  :name, :piece-length, :pieces (vector of 20-byte arrays),
  :length (single-file only), :files (multi-file only), :private (optional).
  Returns {:ok info-map} or an error map."
  [info-map]
  (let [name-val (bytes->str (get info-map "name"))
        piece-length (get info-map "piece length")
        pieces-raw (get info-map "pieces")
        pieces (if (instance? (Class/forName "[B") pieces-raw)
                 (parse-pieces pieces-raw)
                 [])
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

(defn validate-required-fields
  "Checks that required torrent fields are present: announce, info,
  info.name, info.piece-length, info.pieces. Returns a vector of error maps."
  [torrent]
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

(defn validate-field-types
  "Checks that torrent fields have correct types: piece-length and length
  must be integers. Returns a vector of error maps."
  [torrent]
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

(defn validate-pieces-length
  "Checks that every piece hash is exactly 20 bytes. Returns a vector of error maps."
  [torrent]
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

(defn validate-piece-length
  "Checks that piece-length is a positive integer. Returns a vector of error maps."
  [torrent]
  (let [pl (get-in torrent [:info :piece-length])]
    (if (and (integer? pl) (pos? pl))
      []
      [(bencode/torrent-error
        (str "piece-length must be a positive integer, got: " pl)
        {:field "piece-length" :value pl})])))

(defn validate-torrent
  "Runs all validation checks on a parsed torrent map. Returns {:ok true}
  if valid, or {:error [error-maps]} with all validation failures."
  [torrent]
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

(defn parse-torrent
  "Parses raw .torrent file bytes into a TorrentMetainfo map.
  Returns {:ok torrent-map} on success or an error map on failure.

  The torrent-map contains:
    :announce      - primary tracker URL (string)
    :announce-list - optional tracker tiers (vector of vectors of strings)
    :info          - parsed info dict (name, piece-length, pieces, length/files)
    :info-hash     - 20-byte SHA-1 hash of the original bencoded info dict
    :comment, :created-by, :creation-date, :encoding - optional fields"
  [^bytes torrent-bytes]
  (let [decode-result (bencode/decode-bencode-raw torrent-bytes)]
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
                                     (assoc :comment (bytes->str (get decoded "comment")))
                                     (get decoded "created by")
                                     (assoc :created-by (bytes->str (get decoded "created by")))
                                     (get decoded "creation date")
                                     (assoc :creation-date (get decoded "creation date"))
                                     (get decoded "encoding")
                                     (assoc :encoding (bytes->str (get decoded "encoding"))))
                            validation (validate-torrent parsed)]
                        (if (:ok validation)
                          {:ok parsed}
                          validation)))))))))))))
