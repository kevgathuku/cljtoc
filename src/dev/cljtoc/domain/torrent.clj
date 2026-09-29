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

;; Byte arrays flow through info-hash and layout math here; fail the compile on
;; reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

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
  :fn #(or (keyword? (-> % :ret :error))
           (let [input (-> % :args :torrent-bytes)
                 extracted (-> % :ret :ok)]
             ;; A subrange copy: bytes out, never more bytes than went in.
             (and (bytes? extracted)
                  (<= (alength ^bytes extracted) (alength ^bytes input))))))

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
  :fn #(or (and (bytes? (:ok (:ret %)))
                (= 20 (let [digested ^bytes (:ok (:ret %))] (alength digested))))
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
  ;; Count, split positions, and round-trip together pin the chunking: the
  ;; count forces ceil(len/20) chunks, all-but-last at exactly 20 forces
  ;; where each split falls, and the round-trip pins content and the final
  ;; short chunk. A non-canonical split (say 19 + 6 for 25 bytes) fails the
  ;; all-but-last clause.
  :fn #(let [input (-> % :args :piece-data)
             chunks (:ret %)
             input-length (alength ^bytes input)]
         (and (every? bytes? chunks)
              (= (count chunks) (long (Math/ceil (/ input-length 20.0))))
              (every? (fn [^bytes chunk] (= 20 (alength chunk)))
                      (butlast chunks))
              (= (vec input) (vec (mapcat seq chunks))))))

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
   Always returns {:ok info-map}; absent fields surface as nils for
   validate-torrent to report."
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

(defn- prefix-collision?
  "True when one declared path properly contains another: no declaration
   order repairs that layout, since the parent path cannot be both the file
   one entry claims and the directory the other needs. Sorted order puts a
   prefix immediately before everything it prefixes — anything between
   would itself extend it and sort between — so checking adjacent pairs
   suffices, O(n log n) rather than O(n²) per piece."
  [paths]
  (let [sorted (sort paths)]
    (boolean (some (fn [[parent child]]
                     (and (< (count parent) (count child))
                          (= (vec parent)
                             (subvec (vec child) 0 (count parent)))))
                   (map vector sorted (rest sorted))))))

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
  "The one layout guard behind compile-output-layout: the
   layout needs a :name, a positive :piece-length, carries exactly one of
   :length (single-file) or :files (multi-file), every declared path component must stay inside the
   output directory, every present length must be a natural integer, and no
   two entries may claim the same path. A duplicate is fatal: two entries
   would write distinct byte ranges into the same physical file, so no
   single layout can honor both. So is carrying both fields: total-size
   prefers :length while file-layout prefers :files, and the two
   representations silently cover different bytes. So is a non-integer
   length: spans crash comparing against it while sizes hand the string
   downstream to explode later, far from the lie. So is a nested pair: a
   path inside another cannot be both the file one entry claims and the
   directory the other needs, in any declaration order. So is a pathless
   entry: a missing or empty :path collapses to [name] and silently writes
   the torrent root instead of failing. Returns the error
   map, or nil when the layout is usable.
   ponytail: paths are compared as declared, so a case-insensitive filesystem
   can still map two differently-spelled paths onto one file."
  [info]
  ;; Checked before the layout is derived below: map/mapcat seq :files
  ;; and every entry :path, so a non-collection there throws instead of
  ;; answering, and a missing or empty :path collapses to [name] and
  ;; silently writes the torrent root. info is torrent-controlled, and
  ;; the namespace contract is errors as data. Non-empty strings stay
  ;; permissible here: they seq without throwing and the component guard
  ;; below refuses them as hostile.
  (cond
    (and (some? (:files info)) (not (sequential? (:files info))))
    (bencode/torrent-error "info :files must be a collection of file entries" {})

    (and (sequential? (:files info))
         (some (fn [entry]
                 (or (not (map? entry))
                     (let [entry-path (:path entry)]
                       (not (or (and (string? entry-path) (seq entry-path))
                                (and (sequential? entry-path) (seq entry-path)))))))
               (:files info)))
    (bencode/torrent-error "info file entries must be maps with a non-empty path" {})

    ;; A compiled layout without a positive piece length is consumable by
    ;; nothing: layout-spans and the port contract refuse it while sizes
    ;; alone would report success. Refuse it here so compile cannot hand
    ;; out layouts the port cannot accept.
    (let [nominal (:piece-length info)]
      (or (not (integer? nominal)) (not (pos? nominal))))
    (bencode/torrent-error "info must carry a positive :piece-length" {})

    :else
    (let [components (cons (:name info) (mapcat :path (:files info)))
          ;; Top-level :length is legitimately absent on multi-file infos;
          ;; file entries always carry theirs, so only the top slot tolerates
          ;; nil. (Single-file infos without :length are caught by the
          ;; no-layout branch above, before this runs.)
          layout (file-layout info)]
      (cond
        (and (some? (:length info)) (some? (:files info)))
        (bencode/torrent-error "info must not carry both :length and :files" {})

        (and (nil? (:length info)) (nil? (:files info)))
        (bencode/torrent-error "info must carry either :length or :files" {})

        (not (and (or (nil? (:length info)) (nat-int? (:length info)))
                  (every? nat-int? (map :length (:files info)))))
        (bencode/torrent-error "info carries a file length that is not a natural integer" {})

        (nil? (:name info))
        (bencode/torrent-error "info must carry :name for output paths" {})

        (not (every? safe-path-component? components))
        (bencode/torrent-error "info carries a path component that escapes the output directory" {})

        (not= (count layout) (count (distinct (map :path layout))))
        (bencode/torrent-error "info declares the same output path twice" {})

        (prefix-collision? (map :path layout))
        (bencode/torrent-error "info declares an output path inside another" {})

        :else
        nil))))

(defn compile-output-layout
  "Derive the output layout of an info dict once per download: every
   declared entry with its cumulative byte :start, the sizes map,
   the content :total, and the :piece-length
   passed through for span math. :files holds only positive-length
   entries — a zero-length file overlaps no piece, so excluding it keeps
   the per-piece walk to exactly the overlapped files — while :sizes
   still lists every declared path so layout init creates the empties.
   A layout that could not be written as declared (see layout-error)
   is an error.
   Returns {:ok layout} or {:error ...}."
  [info]
  (if-let [error (layout-error info)]
    error
    (try
      (let [entries (file-layout info)
            files (loop [remaining entries
                         file-start 0
                         acc (transient [])]
                    (if (empty? remaining)
                      (persistent! acc)
                      (let [{file-path :path file-length :length} (first remaining)]
                        (recur (rest remaining)
                               (long (+ file-start file-length))
                               (if (pos? file-length)
                                 (conj! acc {:path file-path
                                             :length file-length
                                             :start file-start})
                                 acc)))))
            total (reduce + 0 (map :length entries))]
        {:ok {:files files
              :sizes (into {} (map (fn [{file-path :path file-length :length}]
                                     [file-path file-length])
                                   entries))
              :total total
              :piece-length (:piece-length info)}})
      (catch ArithmeticException _
        (bencode/torrent-error "output layout arithmetic overflowed" {})))))

(s/fdef compile-output-layout
  :args (s/cat :info ::info)
  :ret map?
  ;; The single derivation behind the sizes and span views: every searched
  ;; entry is a declared size, and the sizes sum to the total.
  :fn #(let [ret (:ret %)]
         (or (:error ret)
             (let [layout (:ok ret)]
               (and (every? (fn [{file-path :path file-length :length}]
                              (= file-length (get (:sizes layout) file-path)))
                            (:files layout))
                    (= (:total layout) (reduce + 0 (vals (:sizes layout)))))))))

(defn- first-overlap-index
  "Index of the first file a piece starting at piece-start can overlap:
   one past the last entry starting at or before it. Binary search over
   the compiled :starts, O(log files) instead of the O(files) linear
   walk the retired per-piece entry point used to pay on every piece."
  [files piece-start]
  (loop [low-idx 0 high-idx (count files)]
    (if (= low-idx high-idx)
      (max 0 (dec low-idx))
      (let [mid-idx (quot (+ low-idx high-idx) 2)]
        (if (<= (:start (nth files mid-idx)) piece-start)
          (recur (inc mid-idx) high-idx)
          (recur low-idx mid-idx))))))

(defn layout-spans
  "Map one piece to file-layout spans from a compiled output layout
   (see compile-output-layout): per overlapped file,
   {:path [name ...] :file-offset n :data-offset m :length k}.
   The first overlapped file is found by binary search over the
   compiled :starts and only overlapped files are walked, so per-piece
   cost is O(log files) instead of O(files).
   Returns {:ok spans} or {:error ...}."
  [layout piece-index piece-byte-count]
  (let [nominal (:piece-length layout)]
    (cond
      (or (not (nat-int? piece-index)) (not (pos-int? piece-byte-count)))
      (bencode/torrent-error "piece index and byte count must be valid" {})

      (or (not (integer? nominal)) (not (pos? nominal)))
      (bencode/torrent-error "info must carry a positive :piece-length" {})

      ;; O(1) shape guards only: entry shapes were validated where the
      ;; layout was compiled, and scanning them here would put the O(files)
      ;; this refactor removes right back on the per-piece path. A
      ;; hand-built layout that passes the guards but carries malformed
      ;; entries fails closed in the catch below, never a throw.
      ;; ponytail: trusts compiled entry shapes for speed; the catch is
      ;; the backstop, and direct callers with garbage get an error envelope.
      (not (and (map? layout)
                (vector? (:files layout))
                (nat-int? (:total layout))))
      (bencode/torrent-error "layout must be a compiled output layout" {})

      :else
      ;; Huge indices overflow long arithmetic, and the contract is errors
      ;; as data — never a throw.
      (try
        (let [total (:total layout)
              piece-start (* piece-index nominal)
              files (:files layout)]
          (cond
            ;; No searchable entries yet bytes declared: not a layout
            ;; compile-output-layout can produce. (Empty with zero total
            ;; falls through to the past-total error, as on an all-empty
            ;; layout with no searchable entries.)
            (and (empty? files) (pos? total))
            (bencode/torrent-error "layout must be a compiled output layout" {})

            (>= piece-start total)
            (bencode/torrent-error (str "piece " piece-index " starts past total size " total)
                                   {:piece-index piece-index})

            :else
            (let [piece-end (min total (+ piece-start piece-byte-count))
                  spans (loop [idx (first-overlap-index files piece-start)
                               acc (transient [])]
                          (if (>= idx (count files))
                            (persistent! acc)
                            (let [{file-path :path file-length :length file-start :start} (nth files idx)]
                              (if (>= file-start piece-end)
                                (persistent! acc)
                                (let [file-end (+ file-start file-length)
                                      overlap-start (max piece-start file-start)
                                      overlap-end (min piece-end file-end)]
                                  (recur (inc idx)
                                         (if (< overlap-start overlap-end)
                                           (conj! acc {:path file-path
                                                       :file-offset (- overlap-start file-start)
                                                       :data-offset (- overlap-start piece-start)
                                                       :length (- overlap-end overlap-start)})
                                           acc)))))))]
              ;; A piece starting before :total always overlaps a byte of a
              ;; well-formed layout, so empty spans mean a malformed one
              ;; (e.g. zero-length-only entries) — and the port would
              ;; report :written while dropping the bytes. Error, not :ok.
              (if (empty? spans)
                (bencode/torrent-error "layout must be a compiled output layout" {})
                {:ok spans}))))
        (catch ArithmeticException _
          (bencode/torrent-error (str "piece " piece-index " arithmetic overflowed")
                                 {:piece-index piece-index}))
        (catch Exception _
          (bencode/torrent-error "layout must be a compiled output layout" {}))))))

(s/fdef layout-spans
  :args (s/cat :layout map?
               :piece-index ::piece-span-index
               :piece-byte-count ::piece-byte-count)
  :ret map?
  ;; Span shape pinned per generated layout by the independent-oracle
  ;; property: every emitted span conforms ::file-span-list.
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
  :ret vector?
  :fn #(every? (fn [error-map] (keyword? (:error error-map))) (:ret %)))

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
  :ret vector?
  :fn #(every? (fn [error-map] (keyword? (:error error-map))) (:ret %)))

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
  :ret vector?
  :fn #(every? (fn [error-map] (keyword? (:error error-map))) (:ret %)))

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
  :ret vector?
  :fn #(every? (fn [error-map] (keyword? (:error error-map))) (:ret %)))

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
                  ;; parse-info-dict is total — it always returns {:ok ...} —
                  ;; so there is no error branch to take here.
                  (let [info-result (parse-info-dict info-map)
                        announce-urls (extract-announce-urls decoded)
                        parsed (cond-> {:announce (:announce announce-urls)
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
                      validation)))))))))))

(s/fdef parse-torrent
  :args (s/cat :torrent-bytes bytes?)
  :ret map?
  :fn #(or (map? (-> % :ret :ok))
           (keyword? (-> % :ret :error))))
