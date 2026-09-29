(ns dev.cljtoc.utils
  "Shared byte helpers with no layer dependencies: any layer may require
   this namespace. Equality over raw bytes and byte-array generation for
   specs and tests live here instead of being repeated per namespace."
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]))

;; Every hash comparison bottoms out in the byte helpers here; fail the compile
;; on reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

(defn bytes-equal?
  "True when two byte arrays hold the same bytes. Thin wrapper over
   java.util.Arrays/equals, shared so every hash comparison spells it
   once."
  [^bytes left ^bytes right]
  (java.util.Arrays/equals left right))

(s/fdef bytes-equal?
  :args (s/cat :left bytes? :right bytes?)
  :ret boolean?)

(defn gen-byte-array
  "Generate a generator for byte arrays of specific length."
  [len]
  (gen/fmap byte-array
            (gen/vector (gen/choose -128 127) len)))

;; ============================================================================
;; Primitive specs — wire-encoding shapes shared by every layer.
;; Moved from dev.cljtoc.protocol.peer so the byte utilities below
;; carry their contracts with them; peer composes its message specs
;; on these.
;; ============================================================================

(s/def ::byte-array-2
  (s/and bytes? #(= 2 (count %))))

(s/def ::byte-array-4
  (s/and bytes? #(= 4 (count %))))

(s/def ::byte-array-8
  (s/and bytes? #(= 8 (count %))))

;; Generation fixed centrally: with-gen changes generation only, never
;; conformance, so every consumer drawing this spec gets feasible
;; 20-byte arrays instead of a starved such-that filter.
(s/def ::byte-array-20
  (s/with-gen (s/and bytes? #(= 20 (count %)))
    #(gen-byte-array 20)))

(s/def ::int32
  (s/int-in -2147483648 2147483648))

(s/def ::int16
  (s/int-in -32768 32768))

;; ============================================================================
;; Byte utilities — moved intact from dev.cljtoc.protocol.peer so every
;; layer shares one home for wire encoding. Each fn carries its fdef
;; directly below it.
;; ============================================================================

(defn bytes-to-int32
  "Convert 4 bytes (big-endian) to signed 32-bit integer.
   
   Args:
     bytes - byte array of exactly 4 bytes
     offset - starting position (default 0)
   
   Returns:
     32-bit signed integer"
  ([^bytes b]
   (bytes-to-int32 b 0))
  ([^bytes b offset]
   {:pre [(>= (count b) (+ offset 4))]}
   (let [unsigned (bit-or (bit-shift-left (bit-and (aget b offset) 0xFF) 24)
                          (bit-shift-left (bit-and (aget b (+ offset 1)) 0xFF) 16)
                          (bit-shift-left (bit-and (aget b (+ offset 2)) 0xFF) 8)
                          (bit-and (aget b (+ offset 3)) 0xFF))]
     ;; Convert unsigned to signed using bit-shift-left/right trick
     (bit-shift-right (bit-shift-left unsigned 32) 32))))

(s/fdef bytes-to-int32
  :args (s/cat :b bytes? :offset (s/? nat-int?))
  :ret  ::int32)

(defn int32-to-bytes
  "Convert signed 32-bit integer to 4 bytes (big-endian).
   
   Args:
     value - 32-bit signed integer
   
   Returns:
     Byte array of exactly 4 bytes"
  [value]
  {:pre [(s/valid? ::int32 value)]}
  (let [b (byte-array 4)]
    (aset b 0 (unchecked-byte (bit-and (bit-shift-right value 24) 0xFF)))
    (aset b 1 (unchecked-byte (bit-and (bit-shift-right value 16) 0xFF)))
    (aset b 2 (unchecked-byte (bit-and (bit-shift-right value 8) 0xFF)))
    (aset b 3 (unchecked-byte (bit-and value 0xFF)))
    b))

(s/fdef int32-to-bytes
  :args (s/cat :value ::int32)
  :ret  ::byte-array-4
  :fn   #(= (:value (:args %)) (bytes-to-int32 (:ret %))))

(defn bytes-to-int16
  "Convert 2 bytes (big-endian) to signed 16-bit integer.
   
   Args:
     bytes - byte array of at least 2 bytes
     offset - starting position (default 0)
   
   Returns:
     16-bit signed integer"
  ([^bytes b]
   (bytes-to-int16 b 0))
  ([^bytes b offset]
   {:pre [(>= (count b) (+ offset 2))]}
   (let [unsigned (bit-or (bit-shift-left (bit-and (aget b offset) 0xFF) 8)
                          (bit-and (aget b (+ offset 1)) 0xFF))]
     ;; Convert unsigned to signed
     (if (>= unsigned 32768)
       (- unsigned 65536)
       unsigned))))

(s/fdef bytes-to-int16
  :args (s/cat :b bytes? :offset (s/? nat-int?))
  :ret  ::int16)

(defn int16-to-bytes
  "Convert signed 16-bit integer to 2 bytes (big-endian).
   
   Args:
     value - 16-bit signed integer
   
   Returns:
     Byte array of exactly 2 bytes"
  [value]
  {:pre [(s/valid? ::int16 value)]}
  (let [b (byte-array 2)]
    (aset b 0 (unchecked-byte (bit-and (bit-shift-right value 8) 0xFF)))
    (aset b 1 (unchecked-byte (bit-and value 0xFF)))
    b))

(s/fdef int16-to-bytes
  :args (s/cat :value ::int16)
  :ret  ::byte-array-2
  :fn   #(= (:value (:args %)) (bytes-to-int16 (:ret %))))

(defn concat-bytes
  "Concatenate multiple byte arrays into one.
   
   Args:
     & byte-arrays - variable number of byte arrays
   
   Returns:
     Single byte array containing all input bytes in order"
  [& byte-arrays]
  (let [total-len (reduce + (map count byte-arrays))
        result (byte-array total-len)]
    (loop [offset 0
           arrays byte-arrays]
      (when (seq arrays)
        (let [arr (first arrays)
              len (count arr)]
          (System/arraycopy arr 0 result offset len)
          (recur (+ offset len) (rest arrays)))))
    result))

;; ============================================================================

(s/fdef concat-bytes
  :args (s/cat :byte-arrays (s/* bytes?))
  :ret  bytes?
  :fn   #(= (count (:ret %))
            (reduce + 0 (map count (-> % :args :byte-arrays)))))
