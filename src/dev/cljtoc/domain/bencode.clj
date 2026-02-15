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
