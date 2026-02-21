(ns dev.cljtoc.domain.bencode
  "Bencode encoder/decoder for the BitTorrent protocol.

  Provides pure functions for encoding Clojure data structures to bencode
  format and decoding bencode bytes back to Clojure data. All parse errors
  are returned as data maps, never thrown as exceptions.

  Bencode types map to Clojure as follows:
    bencode string  → Clojure string (UTF-8)
    bencode integer → long
    bencode list    → vector
    bencode dict    → sorted-map with string keys"
  (:require [clojure.spec.alpha :as s])
  (:import [java.security MessageDigest]
           [java.io ByteArrayOutputStream]))

;; ---------------------------------------------------------------------------
;; Error constructors
;; ---------------------------------------------------------------------------

(defn bencode-error
  "Constructs a bencode parse error map with the given message and byte position."
  [message position]
  {:error :bencode-parse-error
   :message message
   :position position})

(defn torrent-error
  "Constructs a torrent validation error map with the given message and context."
  [message context]
  {:error :invalid-torrent
   :message message
   :context context})

;; ---------------------------------------------------------------------------
;; Byte utilities
;; ---------------------------------------------------------------------------

(defn bytes->hex-string
  "Converts a byte array to a lowercase hexadecimal string."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha1-hash
  "Computes the SHA-1 hash of a byte array. Returns a 20-byte array."
  ^bytes [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (.digest md bs)))

;; ---------------------------------------------------------------------------
;; Internal decoders — signature: (bytes, position) → [value, next-pos] | error-map
;; ---------------------------------------------------------------------------

(defn- digit? [^long b]
  (and (>= b 0x30) (<= b 0x39)))

(defn decode-string
  "Decodes a bencode string starting at pos. Returns [byte-array, next-pos] or error map."
  [^bytes bs ^long pos]
  (let [len (alength bs)]
    (if (or (>= pos len) (not (digit? (aget bs pos))))
      (bencode-error "expected digit at start of string length" pos)
      (loop [i pos]
        (cond
          (>= i len)
          (bencode-error "unexpected end of input looking for ':'" pos)

          (= (aget bs i) (byte 0x3a)) ;; ':'
          (let [len-str (String. bs (int pos) (int (- i pos)) "UTF-8")
                str-len (Long/parseLong len-str)
                start (inc i)
                end (+ start str-len)]
            (if (> end len)
              (bencode-error (str "string content truncated, expected " str-len " bytes") pos)
              (let [content (java.util.Arrays/copyOfRange bs (int start) (int end))]
                [content end])))

          (digit? (aget bs i))
          (recur (inc i))

          :else
          (bencode-error (str "unexpected byte in string length: " (aget bs i)) pos))))))

(defn decode-integer
  "Decodes a bencode integer starting at pos. Returns [long, next-pos] or error map."
  [^bytes bs ^long pos]
  (let [len (alength bs)]
    (if (or (>= pos len) (not= (aget bs pos) (byte 0x69))) ;; 'i'
      (bencode-error "expected 'i' at start of integer" pos)
      (let [start (inc pos)]
        (if (>= start len)
          (bencode-error "unexpected end of input in integer" pos)
          (loop [i start]
            (cond
              (>= i len)
              (bencode-error "unexpected end of input looking for 'e'" pos)

              (= (aget bs i) (byte 0x65)) ;; 'e'
              (let [num-str (String. bs (int start) (int (- i start)) "UTF-8")]
                (cond
                  (= num-str "")
                  (bencode-error "empty integer" pos)

                  (and (> (count num-str) 1) (= (.charAt num-str 0) \0))
                  (bencode-error "leading zeros in integer" pos)

                  (= num-str "-0")
                  (bencode-error "negative zero not allowed" pos)

                  (and (> (count num-str) 1) (= (.charAt num-str 0) \-) (= (.charAt num-str 1) \0))
                  (bencode-error "leading zeros in negative integer" pos)

                  :else
                  (try
                    [(Long/parseLong num-str) (inc i)]
                    (catch NumberFormatException _
                      (bencode-error (str "invalid integer: " num-str) pos)))))

              :else
              (recur (inc i)))))))))

;; ---------------------------------------------------------------------------
;; Recursive decoder — decode-value dispatches by first byte
;; ---------------------------------------------------------------------------

(declare decode-value)

(defn- decode-list [^bytes bs ^long pos]
  (let [len (alength bs)
        start (inc pos)]
    (loop [i start
           items (transient [])]
      (if (>= i len)
        (bencode-error "unexpected end of input in list" pos)
        (if (= (aget bs i) (byte 0x65)) ;; 'e'
          [(persistent! items) (inc i)]
          (let [result (decode-value bs i)]
            (if (map? result)
              result
              (let [[val next-pos] result]
                (recur next-pos (conj! items val))))))))))

(defn- decode-dict [^bytes bs ^long pos]
  (let [len (alength bs)
        start (inc pos)]
    (loop [i start
           entries (transient [])]
      (if (>= i len)
        (bencode-error "unexpected end of input in dict" pos)
        (if (= (aget bs i) (byte 0x65)) ;; 'e'
          (let [m (apply sorted-map (mapcat identity (persistent! entries)))]
            [m (inc i)])
          (let [key-result (decode-string bs i)]
            (if (map? key-result)
              key-result
              (let [[key-bytes key-next] key-result
                    key-str (String. ^bytes key-bytes "UTF-8")]
                (if (>= key-next len)
                  (bencode-error "unexpected end of input in dict value" key-next)
                  (let [val-result (decode-value bs key-next)]
                    (if (map? val-result)
                      val-result
                      (let [[val val-next] val-result]
                        (recur val-next (conj! entries [key-str val]))))))))))))))

(defn- decode-value [^bytes bs ^long pos]
  (let [len (alength bs)]
    (if (>= pos len)
      (bencode-error "unexpected end of input" pos)
      (let [b (aget bs pos)]
        (cond
          (digit? b) (decode-string bs pos)
          (= b (byte 0x69)) (decode-integer bs pos)
          (= b (byte 0x6c)) (decode-list bs pos)
          (= b (byte 0x64)) (decode-dict bs pos)
          :else (bencode-error (str "unknown type byte: " (char b)) pos))))))

;; ---------------------------------------------------------------------------
;; Post-processing: convert byte-array strings to Clojure strings
;; ---------------------------------------------------------------------------

(defn- bytes->string-tree [val]
  (cond
    (instance? (Class/forName "[B") val) (String. ^bytes val "UTF-8")
    (vector? val) (mapv bytes->string-tree val)
    (map? val) (into (sorted-map)
                     (map (fn [[k v]] [k (bytes->string-tree v)]))
                     val)
    :else val))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- decode-top-level [^bytes bs post-process]
  (if (zero? (alength bs))
    (bencode-error "empty input" 0)
    (let [result (decode-value bs 0)]
      (if (map? result)
        result
        (let [[val next-pos] result]
          (if (< next-pos (alength bs))
            (bencode-error (str "trailing data at position " next-pos) next-pos)
            {:ok (post-process val)}))))))

(defn decode-bencode
  "Decodes a bencode-encoded byte array into Clojure data structures.
  Returns {:ok value} on success, or an error map with :error, :message,
  and :position keys on failure. Byte-array strings are converted to
  UTF-8 Clojure strings. Dicts become sorted-maps with string keys."
  [^bytes bs]
  (decode-top-level bs bytes->string-tree))

(defn decode-bencode-raw
  "Like decode-bencode, but string values remain as byte arrays instead of
  being converted to UTF-8 strings. Dict keys are still converted to strings.
  Use this when you need lossless access to binary string fields (e.g. pieces)."
  [^bytes bs]
  (decode-top-level bs identity))

(defn bencode-type
  "Returns the bencode type at the given position: :string, :integer, :list, :dict, or :unknown."
  [^bytes bs ^long pos]
  (if (>= pos (alength bs))
    :unknown
    (let [b (aget bs pos)]
      (cond
        (digit? b)         :string
        (= b (byte 0x69))  :integer
        (= b (byte 0x6c))  :list
        (= b (byte 0x64))  :dict
        :else               :unknown))))

;; ---------------------------------------------------------------------------
;; Encoder
;; ---------------------------------------------------------------------------

(defn encode-bencode
  "Encodes a Clojure value to a bencode byte array. Supported types:
  strings, integers (long), byte arrays, vectors (lists), and maps (dicts).
  Map keys are sorted lexicographically. Throws IllegalArgumentException
  for unsupported types."
  ^bytes [value]
  (let [out (ByteArrayOutputStream.)]
    (letfn [(encode-val [v]
              (cond
                (string? v)
                (let [bs (.getBytes ^String v "UTF-8")
                      prefix (.getBytes (str (alength bs) ":") "UTF-8")]
                  (.write out prefix 0 (alength prefix))
                  (.write out bs 0 (alength bs)))

                (instance? (Class/forName "[B") v)
                (let [^bytes barr v
                      prefix (.getBytes (str (alength barr) ":") "UTF-8")]
                  (.write out prefix 0 (alength prefix))
                  (.write out barr 0 (alength barr)))

                (integer? v)
                (let [bs (.getBytes (str "i" v "e") "UTF-8")]
                  (.write out bs 0 (alength bs)))

                (vector? v)
                (do
                  (.write out (int 0x6c))
                  (doseq [item v]
                    (encode-val item))
                  (.write out (int 0x65)))

                (map? v)
                (do
                  (.write out (int 0x64))
                  (doseq [[k val] (sort-by key v)]
                    (encode-val (if (string? k) k (str k)))
                    (encode-val val))
                  (.write out (int 0x65)))

                :else
                (throw (IllegalArgumentException.
                        (str "unsupported bencode type: " (type v))))))]
      (encode-val value)
      (.toByteArray out))))

(defn bencode-roundtrip?
  "Returns true if encoding then decoding value produces the original value."
  [value]
  (= value (:ok (decode-bencode (encode-bencode value)))))

;; ---------------------------------------------------------------------------
;; Dict value span — for extracting raw bytes of a dict value by key
;; ---------------------------------------------------------------------------

;; ============================================================================
;; Function Specs
;; ============================================================================

(s/fdef sha1-hash
  :args (s/cat :bs bytes?)
  :ret  (s/and bytes? #(= 20 (alength %))))

(s/fdef bytes->hex-string
  :args (s/cat :bs bytes?)
  :ret  string?
  :fn   #(= (* 2 (alength (-> % :args :bs))) (count (:ret %))))

(s/fdef decode-bencode
  :args (s/cat :bs bytes?)
  :ret  map?
  :fn   #(or (contains? (:ret %) :ok)
             (= :bencode-parse-error (-> % :ret :error))))

(s/fdef decode-bencode-raw
  :args (s/cat :bs bytes?)
  :ret  map?
  :fn   #(or (contains? (:ret %) :ok)
             (= :bencode-parse-error (-> % :ret :error))))

(s/fdef bencode-type
  :args (s/cat :bs bytes? :pos nat-int?)
  :ret  #{:string :integer :list :dict :unknown})

(s/fdef encode-bencode
  :args (s/cat :value (s/or :str   string?
                            :int   integer?
                            :bytes bytes?
                            :vec   vector?
                            :map   map?))
  :ret  bytes?)

(s/fdef bencode-roundtrip?
  :args (s/cat :value (s/or :str string? :int integer? :vec vector? :map map?))
  :ret  boolean?)

(defn find-dict-value-span
  "Walks a bencoded dict to find the byte range [start, end) of the value
  for the given key. Returns {:ok [start end]} or an error map.
  Operates on raw bytes using internal decoders to preserve original encoding."
  [^bytes bs ^String key-str]
  (let [len (alength bs)]
    (if (or (zero? len) (not= (aget bs 0) (byte 0x64)))
      (bencode-error "expected dict at top level" 0)
      (loop [i 1]
        (if (>= i len)
          (bencode-error "unexpected end of input in dict" 0)
          (if (= (aget bs i) (byte 0x65))
            (bencode-error (str "key not found: " key-str) 0)
            (let [key-result (decode-string bs i)]
              (if (map? key-result)
                key-result
                (let [[key-bytes key-next] key-result
                      k (String. ^bytes key-bytes "UTF-8")]
                  (if (= k key-str)
                    (let [val-result (decode-value bs key-next)]
                      (if (map? val-result)
                        val-result
                        (let [[_ val-end] val-result]
                          {:ok [key-next val-end]})))
                    (let [val-result (decode-value bs key-next)]
                      (if (map? val-result)
                        val-result
                        (let [[_ val-end] val-result]
                          (recur val-end))))))))))))))

(s/fdef find-dict-value-span
  :args (s/cat :bs bytes? :key-str string?)
  :ret  map?
  :fn   #(or (vector? (-> % :ret :ok))
             (= :bencode-parse-error (-> % :ret :error))))
