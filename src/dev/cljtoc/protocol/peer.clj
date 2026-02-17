(ns dev.cljtoc.protocol.peer
  "BitTorrent peer wire protocol message parsing and building.
   
   Pure functions for encoding/decoding handshake and peer messages.
   All functions operate on byte arrays - no network I/O.
   
   Key design decisions:
   - Big-endian byte order per BEP 3 spec
   - All functions return {:ok value} or {:error keyword :message string}
   - clojure.spec for validation and generative testing"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str])
  (:import [java.util BitSet]))

;; ============================================================================
;; Specs
;; ============================================================================

;; Byte array specs
(s/def ::bytes bytes?)

(s/def ::byte-array-4
  (s/and bytes? #(= 4 (count %))))

(s/def ::byte-array-8
  (s/and bytes? #(= 8 (count %))))

(s/def ::byte-array-20
  (s/and bytes? #(= 20 (count %))))

(s/def ::byte-array-68
  (s/and bytes? #(= 68 (count %))))

;; Integer specs with ranges
(s/def ::uint32
  (s/int-in 0 4294967296))

(s/def ::uint16
  (s/int-in 0 65536))

(s/def ::int32
  (s/int-in -2147483648 2147483648))

(s/def ::int16
  (s/int-in -32768 32768))

;; Protocol string spec
(s/def ::protocol-string
  #(= "BitTorrent protocol" %))

;; PeerHandshake specs
(s/def ::protocol ::protocol-string)
(s/def ::reserved ::byte-array-8)
(s/def ::info-hash ::byte-array-20)
(s/def ::peer-id ::byte-array-20)

(s/def ::peer-handshake
  (s/keys :req-un [::protocol ::reserved ::info-hash ::peer-id]))

;; Error result specs
(s/def ::error-keyword keyword?)
(s/def ::message string?)

(s/def ::error-result
  (s/keys :req-un [::error-keyword ::message]))

(s/def ::ok-result
  (s/keys :req-un [::ok]))

;; ============================================================================
;; Byte Utilities
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
;; PeerHandshake Record
;; ============================================================================

;; The opening message of a BitTorrent peer connection.
;; Fields:
;;   :protocol - String, always "BitTorrent protocol"
;;   :reserved - byte[8], extension flags (preserved, not acted upon)
;;   :info-hash - byte[20], SHA-1 hash of torrent info
;;   :peer-id - byte[20], unique peer identifier
(defrecord PeerHandshake [protocol reserved info-hash peer-id])

(defn validate-handshake-fields
  "Validate handshake fields using clojure.spec.
   
   Args:
     protocol - Protocol string
     reserved - Reserved bytes (8 bytes)
     info-hash - Info hash (20 bytes)
     peer-id - Peer ID (20 bytes)
   
   Returns:
     {:ok handshake-record} on success
     {:error keyword :message string} on failure"
  [protocol reserved info-hash peer-id]
  (let [handshake (map->PeerHandshake
                   {:protocol protocol
                    :reserved reserved
                    :info-hash info-hash
                    :peer-id peer-id})]
    (if (s/valid? ::peer-handshake handshake)
      {:ok handshake}
      {:error :invalid-input
       :message (format "Invalid handshake fields: %s"
                        (str/join ", " (s/explain-data ::peer-handshake handshake)))})))

(defn ->peer-handshake
  "Create a PeerHandshake record with validation.
   
   Args:
     info-hash - byte[20], torrent identifier
     peer-id - byte[20], peer identifier
     reserved - Optional byte[8], extension flags (default: all zeros)
   
   Returns:
     {:ok PeerHandshake} on success
     {:error :invalid-input :message string} on validation failure"
  ([info-hash peer-id]
   (->peer-handshake info-hash peer-id (byte-array 8)))
  ([info-hash peer-id reserved]
   (validate-handshake-fields
    "BitTorrent protocol"
    reserved
    info-hash
    peer-id)))

;; ============================================================================
;; Handshake Parsing
;; ============================================================================

(def ^:const handshake-length 68)
(def ^:const protocol-string "BitTorrent protocol")
(def protocol-string-bytes
  "Byte array of protocol string (computed at runtime)"
  (.getBytes ^String protocol-string "US-ASCII"))

(defn parse-handshake
  "Parse a 68-byte BitTorrent handshake.
   
   Args:
     bytes - byte array containing handshake data
   
   Returns:
     {:ok PeerHandshake} on success
     {:error :incomplete-handshake :message string} if < 68 bytes
     {:error :unsupported-protocol :message string} if protocol mismatch"
  [^bytes b]
  (cond
    (< (count b) handshake-length)
    {:error :incomplete-handshake
     :message (format "Handshake must be %d bytes, got %d" handshake-length (count b))}
    
    (not= (seq (subvec (vec b) 1 20)) (seq protocol-string-bytes))
    {:error :unsupported-protocol
     :message (format "Protocol string must be '%s'" protocol-string)}
    
    :else
    (let [reserved (byte-array 8)
          info-hash (byte-array 20)
          peer-id (byte-array 20)]
      (System/arraycopy b 20 reserved 0 8)
      (System/arraycopy b 28 info-hash 0 20)
      (System/arraycopy b 48 peer-id 0 20)
      {:ok (map->PeerHandshake
            {:protocol protocol-string
             :reserved reserved
             :info-hash info-hash
             :peer-id peer-id})})))

;; ============================================================================
;; Handshake Building
;; ============================================================================

(defn build-handshake
  "Build a 68-byte BitTorrent handshake.
   
   Args:
     info-hash - byte[20], torrent identifier
     peer-id - byte[20], peer identifier
     reserved - Optional byte[8], extension flags (default: all zeros)
   
   Returns:
     {:ok byte-array} on success (exactly 68 bytes)
     {:error :invalid-input :message string} on validation failure"
  ([info-hash peer-id]
   (build-handshake info-hash peer-id (byte-array 8)))
  ([info-hash peer-id reserved]
   (cond
     (not= 20 (count info-hash))
     {:error :invalid-input
      :message (format "info-hash must be 20 bytes, got %d" (count info-hash))}
     
     (not= 20 (count peer-id))
     {:error :invalid-input
      :message (format "peer-id must be 20 bytes, got %d" (count peer-id))}
     
     (not= 8 (count reserved))
     {:error :invalid-input
      :message (format "reserved must be 8 bytes, got %d" (count reserved))}
     
     :else
     {:ok (concat-bytes
           (byte-array [(byte 0x13)])  ; pstrlen = 19
           protocol-string-bytes       ; pstr = "BitTorrent protocol"
           reserved                    ; 8 reserved bytes
           info-hash                   ; 20-byte info hash
           peer-id)})))               ; 20-byte peer id

;; ============================================================================
;; Spec Generators for Testing
;; ============================================================================

(defn gen-byte-array
  "Generate a generator for byte arrays of specific length."
  [len]
  (gen/fmap byte-array
            (gen/vector (gen/choose -128 127) len)))

(s/def ::gen-byte-array-20
  (gen-byte-array 20))

(s/def ::gen-byte-array-8
  (gen-byte-array 8))

;; Utility function to check if a byte array equals a sequence
(defn bytes-eq?
  "Compare byte array to a sequence of bytes."
  [^bytes b seq-bytes]
  (and (= (count b) (count seq-bytes))
       (every? true? (map = b seq-bytes))))
