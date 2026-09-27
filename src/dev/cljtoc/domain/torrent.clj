(ns dev.cljtoc.domain.torrent
  "Torrent metadata parser for .torrent files.

  Transforms raw .torrent file bytes into structured TorrentMetainfo maps.
  Computes the info hash from original bencoded bytes (never re-encoded)
  to ensure correctness. Supports single-file and multi-file torrents
  with comprehensive validation.

  All errors are returned as data maps, never thrown as exceptions."
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [clojure.spec.alpha :as s])
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

(s/fdef extract-info-dict-bytes
  :args (s/cat :torrent-bytes bytes?)
  :ret map?
  :fn #(or (bytes? (-> % :ret :ok))
           (keyword? (-> % :ret :error))))

(defn compute-info-hash
  "Computes the 20-byte SHA-1 info hash from torrent bytes.
  The hash is computed over the original bencoded info dict bytes,
  not a re-encoded version. Returns {:ok byte-array} or an error map."
  [^bytes torrent-bytes]
  (let [info-result (extract-info-dict-bytes torrent-bytes)]
    (if (:error info-result)
      info-result
      {:ok (bencode/sha1-hash ^bytes (:ok info-result))})))

(s/fdef compute-info-hash
  :args (s/cat :torrent-bytes bytes?)
  :ret map?
  :fn #(or (and (bytes? (-> % :ret :ok))
                (= 20 (alength ^bytes (-> % :ret :ok))))
           (keyword? (-> % :ret :error))))

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

(s/fdef extract-announce-urls
  :args (s/cat :decoded-dict map?)
  :ret map?
  :fn #(= #{:announce :announce-list} (-> % :ret keys set)))

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

(s/fdef parse-pieces
  :args (s/cat :piece-data bytes?)
  :ret vector?
  :fn #(every? bytes? (:ret %)))

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

(s/fdef parse-info-dict
  :args (s/cat :info-map map?)
  :ret map?
  :fn #(let [parsed (-> % :ret :ok)
             keys-found (set (keys parsed))]
         (and (map? parsed)
              (every? keys-found [:name :piece-length :pieces])
              (every? #{:name :piece-length :pieces :length :files :private}
                      keys-found))))

(defn total-size
  "Total content bytes described by an info dict: :length for single-file
   torrents, the sum of contained file lengths for multi-file ones."
  [info]
  (or (:length info)
      (reduce + 0 (map :length (:files info)))))

(defn- totalable?
  "True when total-size can answer honestly: a present :length must be a
   nat-int, and present :files must be sequential entries each carrying a
   nat-int :length. Absent keys mean zero, so {} is totalable."
  [info]
  (and (or (nil? (:length info)) (nat-int? (:length info)))
       (let [files (:files info)]
         (or (nil? files)
             (and (sequential? files)
                  (every? #(nat-int? (:length %)) files))))))

;; Defined here, not in the data-spec block below: total-size's defn sits
;; above it, and a spec must be defined before any fdef referencing it.
;; map? alone lets {:length \"x\"} through while :ret promises nat-int? —
;; stest/check passes that pairing only until the generator emits :length.
(s/def ::totalable-info (s/and map? totalable?))

(s/fdef total-size
  :args (s/cat :info ::totalable-info)
  :ret nat-int?)

(s/def ::piece-span-index nat-int?)
(s/def ::piece-byte-count pos-int?)
(s/def ::path (s/coll-of string? :kind vector? :min-count 1))
(s/def ::file-offset nat-int?)
(s/def ::data-offset nat-int?)

(defn- span-shaped?
  "True when span has the file-span shape: a non-empty string path vector,
   nat-int offsets, positive length. A predicate rather than s/keys:
   s/keys matches keys by spec name, and :length already means the nat-int
   file length above — one key, one meaning per namespace — so the pos-int?
   span length is asserted here instead."
  [span]
  (and (map? span)
       (let [{span-path :path file-offset :file-offset
              data-offset :data-offset span-length :length} span]
         (and (vector? span-path)
              (seq span-path)
              (every? string? span-path)
              (nat-int? file-offset)
              (nat-int? data-offset)
              (pos-int? span-length)))))

(s/def ::file-span (s/and map? span-shaped?))
(s/def ::file-span-list (s/coll-of ::file-span :kind vector?))

;; Declared info shapes: single-file carries :length, multi-file carries
;; :files, never both (layout-error refuses the pair). s/keys matches keys
;; by spec name, so the nat-int file :length needs its own spec — ::length
;; used to mean the pos-int? span length, now ::span-length above.
(s/def ::name string?)
(s/def ::piece-length pos-int?)
(s/def ::pieces (s/coll-of bytes?))
(s/def ::length nat-int?)
(s/def ::file-entry
  (s/keys :req-un [::path ::length]))
(s/def ::files (s/coll-of ::file-entry :kind vector?))
(s/def ::private boolean?)
(s/def ::single-info
  (s/keys :req-un [::name ::piece-length ::length]
          :opt-un [::pieces ::private]))
(s/def ::multi-info
  (s/keys :req-un [::name ::piece-length ::files]
          :opt-un [::pieces ::private]))
(s/def ::info
  (s/or :single ::single-info :multi ::multi-info))

(defn- safe-path-component?
  "True when a torrent-declared path component cannot escape the output
   directory: a non-empty name with no parent reference, self-reference,
   or separator."
  [component]
  (and (string? component)
       (not (empty? component))
       (not= ".." component)
       (not= "." component)
       (not (re-find #"[/\\]" component))))

(defn- file-layout
  "Relative output layout of an info dict: [{:path [name ...] :length n}].
   Single-file info yields its :length under [:name]; multi-file info
   yields each entry under [name + path]. Nil when :name is missing."
  [info]
  (if (:files info)
    (let [root (:name info)]
      (when-not (nil? root)
        (mapv (fn [file-entry]
                {:path (into [root] (:path file-entry))
                 :length (:length file-entry)})
              (:files info))))
    (when (:name info)
      [{:path [(:name info)] :length (:length info)}])))

(defn- layout-error
  "The one layout guard shared by output-file-sizes and piece-file-spans: the
   layout needs a :name, carries exactly one of :length (single-file) or
   :files (multi-file), every declared path component must stay inside the
   output directory, every present length must be a natural integer, and no
   two entries may claim the same path. A duplicate is fatal because the two
   derivations disagree about it — sizes collapse the entries into one map
   entry while spans keep them as distinct byte ranges, and both ranges then
   land in the same physical file. So is carrying both fields: total-size
   prefers :length while file-layout prefers :files, and the two
   representations silently cover different bytes. So is a non-integer
   length: spans crash comparing against it while sizes hand the string
   downstream to explode later, far from the lie. Returns the error map,
   or nil when the layout is usable.
   ponytail: paths are compared as declared, so a case-insensitive filesystem
   can still map two differently-spelled paths onto one file."
  [info]
  (let [components (cons (:name info) (mapcat :path (:files info)))
        lengths (cons (:length info) (map :length (:files info)))
        layout (file-layout info)]
    (cond
      (and (some? (:length info)) (some? (:files info)))
      (bencode/torrent-error "info must not carry both :length and :files" {})

      (not (every? #(or (nil? %) (nat-int? %)) lengths))
      (bencode/torrent-error "info carries a file length that is not a natural integer" {})

      (nil? (:name info))
      (bencode/torrent-error "info must carry :name for output paths" {})

      (not (every? safe-path-component? components))
      (bencode/torrent-error "info carries a path component that escapes the output directory" {})

      (not= (count layout) (count (distinct (map :path layout))))
      (bencode/torrent-error "info declares the same output path twice" {})

      :else
      nil)))

(defn output-file-sizes
  "Declared output sizes of an info dict: {relative-path-vector length}.
   Rejects a layout that could not be written as declared (see layout-error),
   so a caller never receives a path it must not open.
   Returns {:ok sizes} or {:error ...}."
  [info]
  (if-let [error (layout-error info)]
    error
    {:ok (into {} (map (fn [{file-path :path file-length :length}]
                         [file-path file-length])
                       (file-layout info)))}))

(s/fdef output-file-sizes
  :args (s/cat :info ::info)
  :ret map?)

(defn piece-file-spans
  "Map one piece to file-layout spans: per overlapped file,
   {:path [name ...] :file-offset n :data-offset m :length k}.
   Single-file info (:length) yields one span; multi-file info (:files)
   splits pieces crossing a file boundary. The final short piece maps
   only its own bytes. A layout that could not be written as declared
   (see layout-error) is an error.
   Returns {:ok spans} or {:error ...}."
  [info piece-index piece-byte-count]
  (let [nominal (:piece-length info)
        bad-layout (layout-error info)]
    (cond
      (or (not (nat-int? piece-index)) (not (pos-int? piece-byte-count)))
      (bencode/torrent-error "piece index and byte count must be valid" {})

      (or (not (integer? nominal)) (not (pos? nominal)))
      (bencode/torrent-error "info must carry a positive :piece-length" {})

      bad-layout
      bad-layout

      :else
      ;; Legit lengths can still overflow long arithmetic (a huge nominal
      ;; times a huge index), and the contract is errors as data — never a
      ;; throw — so overflow reports like any other unmappable piece. total
      ;; is computed here, past the guard, for the same reason: summing
      ;; unvalidated lengths can throw before the guard gets its say.
      (try
        (let [total (total-size info)
              piece-start (* piece-index nominal)]
          (if (>= piece-start total)
            (bencode/torrent-error (str "piece " piece-index " starts past total size " total)
                                   {:piece-index piece-index})
            (let [piece-end (min total (+ piece-start piece-byte-count))
                  ;; layout is non-nil here: layout-error has already rejected
                  ;; the nil or unsafe :name that is the only way file-layout
                  ;; returns nil.
                  layout (file-layout info)]
              (let [spans (loop [remaining layout
                                 file-start 0
                                 acc (transient [])]
                            (if (empty? remaining)
                              (persistent! acc)
                              (let [{file-path :path file-length :length} (first remaining)
                                    file-end (+ file-start file-length)
                                    overlap-start (max piece-start file-start)
                                    overlap-end (min piece-end file-end)]
                                (recur (rest remaining)
                                       file-end
                                       (if (< overlap-start overlap-end)
                                         (conj! acc {:path file-path
                                                     :file-offset (- overlap-start file-start)
                                                     :data-offset (- overlap-start piece-start)
                                                     :length (- overlap-end overlap-start)})
                                         acc)))))]
                {:ok spans}))))
        (catch ArithmeticException _
          (bencode/torrent-error (str "piece " piece-index " arithmetic overflowed")
                                 {:piece-index piece-index}))))))

(s/fdef piece-file-spans
  :args (s/cat :info ::info
               :piece-index ::piece-span-index
               :piece-byte-count ::piece-byte-count)
  :ret map?
  ;; Every emitted span conforms ::file-span: paths survive the component
  ;; guard as string vectors, offsets are ordered differences (nat-int?),
  ;; and the overlap guard keeps lengths positive. Error envelopes take
  ;; the other branch, so this also gives ::file-span-list its first use.
  :fn #(let [ret (:ret %)]
         (or (:error ret)
             (s/valid? ::file-span-list (:ok ret)))))

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

(s/fdef validate-required-fields
  :args (s/cat :torrent map?)
  :ret vector?)

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

(s/fdef validate-field-types
  :args (s/cat :torrent map?)
  :ret vector?)

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

(s/fdef validate-pieces-length
  :args (s/cat :torrent map?)
  :ret vector?)

(defn validate-piece-length
  "Checks that piece-length is a positive integer. Returns a vector of error maps."
  [torrent]
  (let [pl (get-in torrent [:info :piece-length])]
    (if (and (integer? pl) (pos? pl))
      []
      [(bencode/torrent-error
        (str "piece-length must be a positive integer, got: " pl)
        {:field "piece-length" :value pl})])))

(s/fdef validate-piece-length
  :args (s/cat :torrent map?)
  :ret vector?)

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

(s/fdef validate-torrent
  :args (s/cat :torrent map?)
  :ret map?
  :fn #(or (true? (-> % :ret :ok))
           (vector? (-> % :ret :error))))

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

(s/fdef parse-torrent
  :args (s/cat :torrent-bytes bytes?)
  :ret map?
  :fn #(or (map? (-> % :ret :ok))
           (keyword? (-> % :ret :error))))
