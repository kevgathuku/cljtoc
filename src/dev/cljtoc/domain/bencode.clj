(ns dev.cljtoc.domain.bencode
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Error constructors
;; ---------------------------------------------------------------------------

(defn bencode-error [message position]
  {:error :bencode-parse-error
   :message message
   :position position})

(defn torrent-error [message context]
  {:error :invalid-torrent
   :message message
   :context context})

;; ---------------------------------------------------------------------------
;; Byte utilities
;; ---------------------------------------------------------------------------

(defn bytes->hex-string [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha1-hash [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (.digest md bs)))

;; ---------------------------------------------------------------------------
;; Internal decoders — signature: (bytes, position) → [value, next-pos] | error-map
;; ---------------------------------------------------------------------------

(defn- digit? [b]
  (and (>= b 0x30) (<= b 0x39)))

(defn decode-string [^bytes bs pos]
  (let [len (alength bs)]
    (if (or (>= pos len) (not (digit? (aget bs pos))))
      (bencode-error "expected digit at start of string length" pos)
      (loop [i pos]
        (cond
          (>= i len)
          (bencode-error "unexpected end of input looking for ':'" pos)

          (= (aget bs i) (byte 0x3a)) ;; ':'
          (let [len-str (String. bs pos (- i pos) "UTF-8")
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

(defn decode-integer [^bytes bs pos]
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
              (let [num-str (String. bs start (- i start) "UTF-8")]
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

(defn- decode-list [^bytes bs pos]
  (let [len (alength bs)
        start (inc pos)] ;; skip 'l'
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

(defn- decode-dict [^bytes bs pos]
  (let [len (alength bs)
        start (inc pos)] ;; skip 'd'
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

(defn- decode-value [^bytes bs pos]
  (let [len (alength bs)]
    (if (>= pos len)
      (bencode-error "unexpected end of input" pos)
      (let [b (aget bs pos)]
        (cond
          (digit? b) (decode-string bs pos)
          (= b (byte 0x69)) (decode-integer bs pos)  ;; 'i'
          (= b (byte 0x6c)) (decode-list bs pos)     ;; 'l'
          (= b (byte 0x64)) (decode-dict bs pos)     ;; 'd'
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

(defn decode-bencode [^bytes bs]
  (if (zero? (alength bs))
    (bencode-error "empty input" 0)
    (let [result (decode-value bs 0)]
      (if (map? result)
        result
        (let [[val next-pos] result]
          (if (< next-pos (alength bs))
            (bencode-error (str "trailing data at position " next-pos) next-pos)
            {:ok (bytes->string-tree val)}))))))
