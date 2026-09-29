(ns dev.cljtoc.protocol.peer
  "BitTorrent peer wire protocol message parsing and building.

   Pure functions for encoding/decoding handshake and peer messages.
   All functions operate on byte arrays - no network I/O.

   Key design decisions:
   - Big-endian byte order per BEP 3 spec
   - All functions return {:ok value} or {:error keyword :message string}
   - clojure.spec for validation and generative testing

   Usage examples (REPL):

     ;; Build and parse a handshake
     (def info-hash (byte-array 20))  ; real SHA-1 hash
     (def peer-id   (byte-array 20))  ; 20-byte peer identifier

     (def raw (:ok (build-handshake info-hash peer-id)))
     ;; => byte[68]  (0x13 \"BitTorrent protocol\" <8 reserved> <info-hash> <peer-id>)

     (parse-handshake raw)
     ;; => {:ok #PeerHandshake{:protocol \"BitTorrent protocol\"
     ;;                        :reserved <byte[8]>
     ;;                        :info-hash <byte[20]>
     ;;                        :peer-id   <byte[20]>}}

     ;; Build individual messages
     (build-message (->KeepAlive))            ;; {:ok <byte[4]>}
     (build-message (->Choke))                ;; {:ok <byte[5]>}
     (build-message (->Have 42))              ;; {:ok <byte[9]>}
     (build-message (->Request 5 0 16384))    ;; {:ok <byte[17]>}
     (build-message (->Piece 5 0 some-data))  ;; {:ok <byte[13 + n]>}

     ;; Parse a stream buffer with multiple messages and an incomplete tail
     (let [result (parse-messages stream-bytes)]
       (:ok        result)  ; => [#Choke{} #Have{:piece-index 7} ...]
       (:remaining result)) ; => leftover bytes for next read

     ;; Validation errors are data, not exceptions
     (parse-handshake (byte-array 20))
     ;; => {:error :incomplete-handshake :message \"Handshake must be 68 bytes, got 20\"}

     (build-message (->Request 0 0 99999))
     ;; => {:error :invalid-input :message \"Request length exceeds max block size (99999 > 16384)\"}"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [dev.cljtoc.utils :as utils])
  (:import [java.util BitSet]))

;; ============================================================================
;; Specs
;; ============================================================================

;; Byte array specs
(s/def ::bytes bytes?)

;; Sized byte arrays and wire integers live in dev.cljtoc.utils now —
;; the byte utilities carry their contracts with them. The composites
;; below compose on utils' specs.

;; Protocol string spec
(s/def ::protocol-string
  #(= "BitTorrent protocol" %))

;; PeerHandshake specs
(s/def ::protocol ::protocol-string)
(s/def ::reserved ::utils/byte-array-8)
(s/def ::info-hash ::utils/byte-array-20)
(s/def ::peer-id ::utils/byte-array-20)

(s/def ::peer-handshake
  (s/keys :req-un [::protocol ::reserved ::info-hash ::peer-id]))

;; Error result specs
(s/def ::error keyword?)
(s/def ::message string?)

(s/def ::error-result
  (s/keys :req-un [::error ::message]))

(s/def ::ok-result
  (s/keys :req-un [::ok]))

;; Shared fdef shapes — the handshake input tuple, the raw-bytes input,
;; and the ok/error envelope recur across the fdefs below; named once.
;; Args stay loose (plain bytes?): the parsers and builders are total
;; over invalid inputs and report them as error envelopes.
(s/def ::handshake-args
  (s/cat :info-hash bytes? :peer-id bytes? :reserved (s/? bytes?)))

(s/def ::bytes-arg
  (s/cat :b bytes?))

(s/def ::envelope
  (s/or :ok ::ok-result :error ::error-result))

;; parse-messages returns {:ok [messages] :remaining bytes}
(s/def ::remaining bytes?)

;; NOTE: byte utilities (bytes-to-int32, int32-to-bytes, bytes-to-int16,
;; int16-to-bytes, concat-bytes) live in dev.cljtoc.utils now.

;; PeerHandshake Record
;; ============================================================================

;; The opening message of a BitTorrent peer connection.
;; Fields:
;;   :protocol - String, always "BitTorrent protocol"
;;   :reserved - byte[8], extension flags (preserved, not acted upon)
;;   :info-hash - byte[20], SHA-1 hash of torrent info
;;   :peer-id - byte[20], unique peer identifier
(defrecord PeerHandshake [protocol reserved info-hash peer-id])

;; Handshake constants, defined before first use (the ->peer-handshake
;; fdef quotes protocol-string in its :fn invariant).
(def ^:const handshake-length 68)
(def ^:const protocol-string "BitTorrent protocol")
(def protocol-string-bytes
  "Byte array of protocol string (computed at runtime)"
  (.getBytes ^String protocol-string "US-ASCII"))

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

(s/fdef ->peer-handshake
  :args ::handshake-args
  :ret  ::envelope
  :fn   (s/or
         :ok    #(= protocol-string (-> % :ret second :ok :protocol))
         :error #(= :error (-> % :ret first))))

;; ============================================================================
;; Handshake Parsing
;; ============================================================================

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

(s/fdef parse-handshake
  :args ::bytes-arg
  :ret  ::envelope
  :fn   (s/or
         :ok    #(= protocol-string (-> % :ret second :ok :protocol))
         :error #(= :error (-> % :ret first))))

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
     {:ok (utils/concat-bytes
           (byte-array [(byte 0x13)])  ; pstrlen = 19
           protocol-string-bytes       ; pstr = "BitTorrent protocol"
           reserved                    ; 8 reserved bytes
           info-hash                   ; 20-byte info hash
           peer-id)})))               ; 20-byte peer id

(s/fdef build-handshake
  :args ::handshake-args
  :ret  ::envelope
  :fn   (s/or
         :ok    #(= 68 (count (-> % :ret second :ok)))
         :error #(= :error (-> % :ret first))))

;; ============================================================================
;; Peer Message Records
;; ============================================================================

(defrecord KeepAlive [])
(defrecord Choke [])
(defrecord Unchoke [])
(defrecord Interested [])
(defrecord NotInterested [])
(defrecord Have [piece-index])
(defrecord Bitfield [bytes])
(defrecord Request [piece-index begin length])
(defrecord Piece [piece-index begin data])
(defrecord Cancel [piece-index begin length])

;; ============================================================================
;; Peer Message Specs
;; ============================================================================

;; Shared message fields
(s/def ::piece-index nat-int?)
(s/def ::begin nat-int?)
(s/def ::length (s/int-in 0 16385)) ;; Max 16 KiB + 1 for upper bound
(s/def ::data ::bytes)

(s/def ::keep-alive (s/keys))
(s/def ::choke (s/keys))
(s/def ::unchoke (s/keys))
(s/def ::interested (s/keys))
(s/def ::not-interested (s/keys))
(s/def ::have (s/keys :req-un [::piece-index]))
(s/def ::bitfield (s/keys :req-un [::bytes]))
(s/def ::request (s/keys :req-un [::piece-index ::begin ::length]))
(s/def ::piece (s/keys :req-un [::piece-index ::begin ::data]))
(s/def ::cancel (s/keys :req-un [::piece-index ::begin ::length]))

(s/def ::peer-message
  (s/or :keep-alive ::keep-alive
        :choke ::choke
        :unchoke ::unchoke
        :interested ::interested
        :not-interested ::not-interested
        :have ::have
        :bitfield ::bitfield
        :request ::request
        :piece ::piece
        :cancel ::cancel))

;; ============================================================================
;; Peer Message Parsing
;; ============================================================================

(def ^:const max-block-size 16384)

(defn- parse-keep-alive [^bytes b]
  (if (= 0 (count b))
    {:ok (->KeepAlive)}
    {:error :invalid-message :message "Keep-alive message must have 0 length"}))

(defn- parse-choke [^bytes b]
  (if (= 0 (count b))
    {:ok (->Choke)}
    {:error :invalid-message :message "Choke message must have 0 length"}))

(defn- parse-unchoke [^bytes b]
  (if (= 0 (count b))
    {:ok (->Unchoke)}
    {:error :invalid-message :message "Unchoke message must have 0 length"}))

(defn- parse-interested [^bytes b]
  (if (= 0 (count b))
    {:ok (->Interested)}
    {:error :invalid-message :message "Interested message must have 0 length"}))

(defn- parse-not-interested [^bytes b]
  (if (= 0 (count b))
    {:ok (->NotInterested)}
    {:error :invalid-message :message "Not-interested message must have 0 length"}))

(defn- parse-have [^bytes b]
  (if (= 4 (count b))
    (let [piece-index (utils/bytes-to-int32 b)]
      {:ok (->Have piece-index)})
    {:error :incomplete-message :message "Have message must have 4-byte payload"}))

(defn- parse-bitfield [^bytes b]
  (if (> (count b) 0)
    {:ok (->Bitfield b)}
    {:error :incomplete-message :message "Bitfield message must have non-empty payload"}))

(defn- parse-request [^bytes b]
  (if (= 12 (count b))
    (let [piece-index (utils/bytes-to-int32 b 0)
          begin (utils/bytes-to-int32 b 4)
          length (utils/bytes-to-int32 b 8)]
      (if (<= length max-block-size)
        {:ok (->Request piece-index begin length)}
        {:error :invalid-input :message (format "Request length exceeds max block size (%d > %d)" length max-block-size)}))
    {:error :incomplete-message :message "Request message must have 12-byte payload"}))

(defn- parse-piece [^bytes b]
  (if (>= (count b) 8)
    (let [piece-index (utils/bytes-to-int32 b 0)
          begin (utils/bytes-to-int32 b 4)
          data-len (- (count b) 8)
          data (byte-array data-len)]
      (System/arraycopy b 8 data 0 data-len)
      (if (<= data-len max-block-size)
        {:ok (->Piece piece-index begin data)}
        {:error :invalid-input :message (format "Piece data length exceeds max block size (%d > %d)" data-len max-block-size)}))
    {:error :incomplete-message :message "Piece message must have at least 8-byte header"}))

(defn- parse-cancel [^bytes b]
  (if (= 12 (count b))
    (let [piece-index (utils/bytes-to-int32 b 0)
          begin (utils/bytes-to-int32 b 4)
          length (utils/bytes-to-int32 b 8)]
      (if (<= length max-block-size)
        {:ok (->Cancel piece-index begin length)}
        {:error :invalid-input :message (format "Cancel length exceeds max block size (%d > %d)" length max-block-size)}))
    {:error :incomplete-message :message "Cancel message must have 12-byte payload"}))

(defmulti parse-message-payload (fn [id _] id))

(defmethod parse-message-payload 0 [_ b] (parse-choke b))
(defmethod parse-message-payload 1 [_ b] (parse-unchoke b))
(defmethod parse-message-payload 2 [_ b] (parse-interested b))
(defmethod parse-message-payload 3 [_ b] (parse-not-interested b))
(defmethod parse-message-payload 4 [_ b] (parse-have b))
(defmethod parse-message-payload 5 [_ b] (parse-bitfield b))
(defmethod parse-message-payload 6 [_ b] (parse-request b))
(defmethod parse-message-payload 7 [_ b] (parse-piece b))
(defmethod parse-message-payload 8 [_ b] (parse-cancel b))
(defmethod parse-message-payload :default [_ _]
  {:error :unknown-message-type :message "Unknown message ID"})

(defn parse-message
  "Parse a single BitTorrent peer wire message from a byte array.
   
   Args:
     bytes - byte array containing the full length-prefixed message
   
   Returns:
     {:ok PeerMessage} on success
     {:error keyword :message string} on failure"
  [^bytes b]
  (if (< (count b) 4)
    {:error :incomplete-message :message "Message too short for length prefix"}
    (let [message-length (utils/bytes-to-int32 b 0)]
      (cond
        (= 0 message-length)
        (parse-keep-alive (byte-array 0))

        (< (count b) (+ 4 message-length))
        {:error :incomplete-message
         :message (format "Declared message length %d exceeds available bytes %d"
                          message-length (- (count b) 4))}

        :else
        (let [message-id (aget b 4)
              payload-bytes (byte-array (- message-length 1))]
          (System/arraycopy b 5 payload-bytes 0 (- message-length 1))
          (parse-message-payload (bit-and message-id 0xFF) payload-bytes))))))

(s/fdef parse-message
  :args ::bytes-arg
  :ret  ::envelope)

(defn parse-messages
  "Parse multiple BitTorrent peer wire messages from a byte buffer.
   
   Args:
     bytes - byte array containing one or more full or partial messages
   
   Returns:
     {:ok [PeerMessage] :remaining bytes} on success
     {:error keyword :message string} on the first parsing failure"
  [^bytes b]
  (loop [offset 0
         messages []]
    (if (>= (- (count b) offset) 4) ;; At least 4 bytes for length prefix
      (let [message-length (utils/bytes-to-int32 b offset)
            full-message-len (+ 4 message-length)]
        (if (<= full-message-len (- (count b) offset))
          (let [message-bytes (byte-array full-message-len)
                _ (System/arraycopy b offset message-bytes 0 full-message-len)
                parse-result (parse-message message-bytes)]
            (if (:ok parse-result)
              (recur (+ offset full-message-len) (conj messages (:ok parse-result)))
              parse-result)) ;; Propagate error
          {:ok messages :remaining (byte-array (take-last (- (count b) offset) (vec b)))})) ;; Incomplete message
      {:ok messages :remaining (byte-array (take-last (- (count b) offset) (vec b)))}))) ;; No full message or too short for length prefix

(s/fdef parse-messages
  :args ::bytes-arg
  :ret  (s/or :ok    (s/keys :req-un [::ok ::remaining])
              :error (s/keys :req-un [::error ::message]))
  :fn   (s/or
         :ok    #(bytes? (-> % :ret second :remaining))
         :error #(= :error (-> % :ret first))))

;; ============================================================================
;; Peer Message Building
;; ============================================================================

(defmulti build-message-payload
  "Build the payload bytes for a peer message.
   
   Dispatches on the type of the message record.
   
   Args:
     msg - A PeerMessage record (KeepAlive, Choke, Have, etc.)
   
   Returns:
     {:ok byte-array} on success
     {:error :invalid-input :message string} on validation failure"
  (fn [msg] (type msg)))

(defmethod build-message-payload dev.cljtoc.protocol.peer.KeepAlive [_]
  {:ok (byte-array 0)})

(defmethod build-message-payload dev.cljtoc.protocol.peer.Choke [_]
  {:ok (byte-array 0)})

(defmethod build-message-payload dev.cljtoc.protocol.peer.Unchoke [_]
  {:ok (byte-array 0)})

(defmethod build-message-payload dev.cljtoc.protocol.peer.Interested [_]
  {:ok (byte-array 0)})

(defmethod build-message-payload dev.cljtoc.protocol.peer.NotInterested [_]
  {:ok (byte-array 0)})

(defmethod build-message-payload dev.cljtoc.protocol.peer.Have [msg]
  (if (s/valid? ::piece-index (:piece-index msg))
    {:ok (utils/int32-to-bytes (:piece-index msg))}
    {:error :invalid-input :message (format "Invalid piece index for Have message: %s" (:piece-index msg))}))

(defmethod build-message-payload dev.cljtoc.protocol.peer.Bitfield [msg]
  (if (s/valid? ::bytes (:bytes msg))
    {:ok (:bytes msg)}
    {:error :invalid-input :message "Bitfield payload must be a byte array"}))

(defmethod build-message-payload dev.cljtoc.protocol.peer.Request [msg]
  (let [{:keys [piece-index begin length]} msg]
    (cond
      (not (s/valid? ::piece-index piece-index))
      {:error :invalid-input :message (format "Invalid piece index for Request message: %s" piece-index)}
      (not (s/valid? ::begin begin))
      {:error :invalid-input :message (format "Invalid begin offset for Request message: %s" begin)}
      (not (s/valid? ::length length))
      {:error :invalid-input :message (format "Invalid length for Request message: %s" length)}
      (> length max-block-size)
      {:error :invalid-input :message (format "Request length exceeds max block size (%d > %d)" length max-block-size)}
      :else
      {:ok (utils/concat-bytes (utils/int32-to-bytes piece-index)
                               (utils/int32-to-bytes begin)
                               (utils/int32-to-bytes length))})))

(defmethod build-message-payload dev.cljtoc.protocol.peer.Piece [msg]
  (let [{:keys [piece-index begin data]} msg]
    (cond
      (not (s/valid? ::piece-index piece-index))
      {:error :invalid-input :message (format "Invalid piece index for Piece message: %s" piece-index)}
      (not (s/valid? ::begin begin))
      {:error :invalid-input :message (format "Invalid begin offset for Piece message: %s" begin)}
      (not (s/valid? ::data data))
      {:error :invalid-input :message "Piece data must be a byte array"}
      (> (count data) max-block-size)
      {:error :invalid-input :message (format "Piece data length exceeds max block size (%d > %d)" (count data) max-block-size)}
      :else
      {:ok (utils/concat-bytes (utils/int32-to-bytes piece-index)
                               (utils/int32-to-bytes begin)
                               data)})))

(defmethod build-message-payload dev.cljtoc.protocol.peer.Cancel [msg]
  (let [{:keys [piece-index begin length]} msg]
    (cond
      (not (s/valid? ::piece-index piece-index))
      {:error :invalid-input :message (format "Invalid piece index for Cancel message: %s" piece-index)}
      (not (s/valid? ::begin begin))
      {:error :invalid-input :message (format "Invalid begin offset for Cancel message: %s" begin)}
      (not (s/valid? ::length length))
      {:error :invalid-input :message (format "Invalid length for Cancel message: %s" length)}
      (> length max-block-size)
      {:error :invalid-input :message (format "Cancel length exceeds max block size (%d > %d)" length max-block-size)}
      :else
      {:ok (utils/concat-bytes (utils/int32-to-bytes piece-index)
                               (utils/int32-to-bytes begin)
                               (utils/int32-to-bytes length))})))

(defmethod build-message-payload :default [msg]
  {:error :unknown-message-type :message (str "Unknown message type for building: " (type msg))})

(defn build-message
  "Build a single BitTorrent peer wire message into a byte array.
   
   Args:
     message-record - A PeerMessage record (e.g., ->Choke, ->Have)
   
   Returns:
     {:ok byte-array} on success
     {:error keyword :message string} on validation or unknown type failure"
  [message-record]
  (if-let [payload-result (build-message-payload message-record)]
    (if (:ok payload-result)
      (let [payload (:ok payload-result)
            message-id (condp instance? message-record
                         KeepAlive -1
                         Choke 0
                         Unchoke 1
                         Interested 2
                         NotInterested 3
                         Have 4
                         Bitfield 5
                         Request 6
                         Piece 7
                         Cancel 8
                         nil)]
        (if (= -1 message-id)
          {:ok (utils/int32-to-bytes 0)}
          (if (nil? message-id)
            {:error :unknown-message-type :message (str "Cannot build unknown message type: " (type message-record))}
            {:ok (utils/concat-bytes
                  (utils/int32-to-bytes (+ 1 (count payload)))
                  (byte-array [(unchecked-byte message-id)])
                  payload)})))
      payload-result)
    {:error :unknown-message-type :message (str "No builder for message type: " (type message-record))}))

(s/fdef build-message
  :args (s/cat :msg any?)
  :ret  ::envelope
  :fn   (s/or
         ;; A keep-alive is exactly 4 bytes; all others are >= 5 bytes.
         :ok    #(>= (count (-> % :ret second :ok)) 4)
         :error #(= :error (-> % :ret first))))

(defn build-messages
  "Build a collection of PeerMessage records into a single concatenated byte array.
   
   Args:
     message-records - A collection of PeerMessage records
   
   Returns:
     {:ok byte-array} on success
     {:error keyword :message string} on the first building failure"
  [message-records]
  (loop [remaining-messages (seq message-records)
         acc-bytes []]
    (if remaining-messages
      (let [msg (first remaining-messages)
            build-result (build-message msg)]
        (if (:ok build-result)
          (recur (next remaining-messages) (conj acc-bytes (:ok build-result)))
          build-result)) ;; Propagate error
      {:ok (apply utils/concat-bytes acc-bytes)})))

(s/fdef build-messages
  :args (s/cat :message-records (s/coll-of any?))
  :ret  ::envelope
  :fn   (s/or
         :ok    #(bytes? (-> % :ret second :ok))
         :error #(= :error (-> % :ret first))))

;; NOTE: byte-array generation lives in dev.cljtoc.utils
;; (utils/gen-byte-array for direct draws, with-gen on ::byte-array-20
;; for spec-integrated ones).
