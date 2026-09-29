(ns dev.cljtoc.protocol.tracker
  "Tracker protocol implementation for BitTorrent peer discovery.

  Provides pure functions for parsing and building tracker protocol messages
  (HTTP and UDP). All functions return {:ok value} or {:error ...} maps.
  Network I/O is handled by injectable ports (not part of this namespace)."
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.protocol.tracker.spec :as spec]
            [clojure.spec.alpha :as s]
            [clojure.string :as string])
  (:import [java.nio ByteBuffer]
           [java.net InetAddress]))

;; Byte arrays flow through tracker message builders and parsers here; fail the
;; compile on reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Error constructors
;; ---------------------------------------------------------------------------

(defn tracker-error
  "Constructs a tracker protocol error map."
  [error-type message & {:as context}]
  (merge {:error error-type
          :message message}
         context))

(s/fdef tracker-error
  :args (s/cat :error-type keyword? :message string? :context (s/* any?))
  :ret  (s/keys :req-un [::spec/error ::spec/message])
  :fn   #(and (= (-> % :args :error-type) (-> % :ret :error))
              (= (-> % :args :message)    (-> % :ret :message))))

;; ---------------------------------------------------------------------------
;; Binary utilities
;; ---------------------------------------------------------------------------

(defn unsigned-short
  "Convert Java signed short to unsigned integer (0-65535).
  Java shorts are signed (-32768 to 32767), but network protocols
  use unsigned shorts. This function performs the conversion."
  [s]
  (bit-and s 0xFFFF))

(s/fdef unsigned-short
  :args (s/cat :s int?)
  :ret  (s/int-in 0 65536)
  :fn   #(<= 0 (:ret %) 65535))

;; ---------------------------------------------------------------------------
;; URL Encoding
;; ---------------------------------------------------------------------------

(defn url-encode-binary
  "URL-encode binary data per RFC 3986.

  Unreserved characters (A-Z a-z 0-9 . - _ ~) are preserved.
  All other bytes are encoded as %XX where XX is uppercase hexadecimal.

  This implements percent-encoding for binary data, which is required for
  encoding info-hash and peer-id in BitTorrent announce URLs.

  Parameters:
    data - Byte array to encode

  Returns:
    URL-encoded string"
  [^bytes data]
  (let [sb (StringBuilder.)]
    (doseq [b data]
      (let [byte-val (bit-and b 0xFF)]  ; Convert to unsigned
        (if (or (and (<= 65 byte-val) (<= byte-val 90))   ; A-Z
                (and (<= 97 byte-val) (<= byte-val 122))  ; a-z
                (and (<= 48 byte-val) (<= byte-val 57))   ; 0-9
                (= byte-val 46)   ; .
                (= byte-val 45)   ; -
                (= byte-val 95)   ; _
                (= byte-val 126)) ; ~
          (.append sb (char byte-val))
          (.append sb (format "%%%02X" byte-val)))))
    (.toString sb)))

(s/fdef url-encode-binary
  :args (s/cat :data bytes?)
  :ret string?
  :fn #(let [data-bytes ^bytes (:data (:args %))
             input-len (alength data-bytes)
             output (-> % :ret)]
         ;; Output should never be longer than input * 3 (each byte -> %XX)
         (<= (count output) (* input-len 3))))

;; ---------------------------------------------------------------------------
;; Spec validation helpers
;; ---------------------------------------------------------------------------

(defn validate-input
  "Validate input data against a spec. Returns nil if valid, error map if invalid.

  Transforms spec explain-data into tracker error format for consistency."
  [spec-key data]
  (when-not (s/valid? spec-key data)
    (let [explain-data (s/explain-data spec-key data)]
      (tracker-error :invalid-input
                     (str "Input validation failed for " spec-key)
                     :spec-explain explain-data))))

(s/fdef validate-input
  :args (s/cat :spec-key any? :data any?)
  :ret  (s/nilable (s/keys :req-un [::spec/error ::spec/message]))
  :fn   #(if (s/valid? (-> % :args :spec-key) (:data (:args %)))
           (nil? (:ret %))
           (some? (:ret %))))

;; ---------------------------------------------------------------------------
;; Peer parsing functions
;; ---------------------------------------------------------------------------

(defn parse-compact-peers-ipv4
  "Parse compact IPv4 peer list (6 bytes per peer).

  Compact format: 4-byte IP address + 2-byte port (big-endian)

  Parameters:
    peers-bytes - Binary peer data (length must be multiple of 6)

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [^bytes peers-bytes]
  (let [len (alength peers-bytes)]
    (if (not= 0 (mod len 6))
      (tracker-error :invalid-peer-data
                     "Peers data length must be multiple of 6"
                     :length len)
      (try
        (let [buf (ByteBuffer/wrap peers-bytes)
              peer-count (/ len 6)
              peers (for [_ (range peer-count)]
                      (let [ip-bytes (byte-array 4)
                            _ (.get buf ip-bytes)
                            ip (.getHostAddress (InetAddress/getByAddress ip-bytes))
                            port (unsigned-short (.getShort buf))]
                        {:ip ip :port port}))]
          {:ok (vec peers)})
        (catch Exception e
          (tracker-error :invalid-ip-address
                         "Failed to parse IP address from compact format"
                         :exception (.getMessage e)))))))

(s/fdef parse-compact-peers-ipv4
  :args (s/cat :peers-bytes bytes?)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
        ;; If successful, peer count should match input length / 6
       :success #(let [peers-bytes ^bytes (:peers-bytes (:args %))
                       input-len (alength peers-bytes)
                       peers (:ok (second (:ret %)))]
                   (or (not= :success (first (:ret %)))
                       (= (count peers) (/ input-len 6))))
       :error #(= :error (first (:ret %)))))

(defn parse-compact-peers-ipv6
  "Parse compact IPv6 peer list (18 bytes per peer).

  Compact format: 16-byte IP address + 2-byte port (big-endian)

  Parameters:
    peers-bytes - Binary peer data (length must be multiple of 18)

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [^bytes peers-bytes]
  (let [len (alength peers-bytes)]
    (if (not= 0 (mod len 18))
      (tracker-error :invalid-peer-data
                     "Peers data length must be multiple of 18"
                     :length len)
      (try
        (let [buf (ByteBuffer/wrap peers-bytes)
              peer-count (/ len 18)
              peers (for [_ (range peer-count)]
                      (let [ip-bytes (byte-array 16)
                            _ (.get buf ip-bytes)
                            ip (.getHostAddress (InetAddress/getByAddress ip-bytes))
                            port (unsigned-short (.getShort buf))]
                        {:ip ip :port port}))]
          {:ok (vec peers)})
        (catch Exception e
          (tracker-error :invalid-ip-address
                         "Failed to parse IP address from compact format"
                         :exception (.getMessage e)))))))

(s/fdef parse-compact-peers-ipv6
  :args (s/cat :peers-bytes bytes?)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
        ;; If successful, peer count should match input length / 18
       :success #(let [peers-bytes ^bytes (:peers-bytes (:args %))
                       input-len (alength peers-bytes)
                       peers (:ok (second (:ret %)))]
                   (or (not= :success (first (:ret %)))
                       (= (count peers) (/ input-len 18))))
       :error #(= :error (first (:ret %)))))

(defn- bytes->string
  "Convert byte array to UTF-8 string, or return unchanged if already a string or nil"
  [x]
  (cond
    (nil? x) nil
    (bytes? x) (String. ^bytes x "UTF-8")
    (string? x) x
    :else x))

(defn parse-dictionary-peers
  "Parse dictionary-format peer list (legacy format).

  Parameters:
    peers-list - List of dictionaries with 'ip', 'port', and optional 'peer id'

  Returns:
    {:ok [peer-list]} or {:error ...}"
  [peers-list]
  (try
    (let [peers (mapv (fn [peer-dict]
                        (let [ip (get peer-dict "ip")
                              port (get peer-dict "port")
                              peer-id (get peer-dict "peer id")]
                          (cond
                            (nil? ip)
                            (throw (ex-info "Missing ip field" {:peer peer-dict}))

                            (nil? port)
                            (throw (ex-info "Missing port field" {:peer peer-dict}))

                            (not (<= 1 port 65535))
                            (throw (ex-info "Invalid port" {:port port}))

                            :else
                            (cond-> {:ip (bytes->string ip) :port port}
                              peer-id (assoc :peer-id peer-id)))))
                      peers-list)]
      {:ok peers})
    (catch Exception e
      (tracker-error :invalid-peer-format
                     (.getMessage e)
                     :context (ex-data e)))))

(s/fdef parse-dictionary-peers
  :args (s/cat :peers-list sequential?)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
        ;; If successful, peer list should match input list length
       :success #(let [input-count (count (-> % :args :peers-list))
                       peers (:ok (second (:ret %)))]
                   (or (not= :success (first (:ret %)))
                       (= (count peers) input-count)))
       :error #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; HTTP tracker response parsing
;; ---------------------------------------------------------------------------

(defn parse-http-tracker-response
  "Parse HTTP tracker announce response (bencode-encoded).

  Validates input is byte array before parsing.

  Parameters:
    response-bytes - HTTP response body (bencode dictionary)

  Returns:
    {:ok tracker-response} or {:error ...}"
  [response-bytes]
  ;; Validate input
  (if-let [validation-error (validate-input bytes? response-bytes)]
    validation-error
    (let [decode-result (bencode/decode-bencode-raw response-bytes)]
      (if (:error decode-result)
        decode-result  ; Pass through bencode parse error
        (let [response (:ok decode-result)]
          (if-let [failure-reason (get response "failure reason")]
            ;; Tracker returned failure
            {:ok {:success false
                  :protocol :http
                  :response-type :announce
                  :failure-reason (bytes->string failure-reason)}}
            ;; Success response
            (try
              (let [peers-data (get response "peers")
                    interval (get response "interval" 1800)
                    min-interval (get response "min interval")
                    complete (get response "complete" 0)
                    incomplete (get response "incomplete" 0)
                    tracker-id (bytes->string (get response "tracker id"))
                    warning (bytes->string (get response "warning message"))

                    ;; Detect peer format (compact binary or dictionary list)
                    peers-result (cond
                                   (bytes? peers-data)
                                   ;; Compact format - try IPv4 first, fallback to IPv6
                                   (if (= 0 (mod (alength ^bytes peers-data) 6))
                                     (parse-compact-peers-ipv4 peers-data)
                                     (parse-compact-peers-ipv6 peers-data))

                                   (and (sequential? peers-data) (not (string? peers-data)))
                                   ;; Dictionary format (vector or list, but not string)
                                   (parse-dictionary-peers peers-data)

                                   :else
                                   (tracker-error :invalid-peer-format
                                                  "Peers field must be binary or list"
                                                  :type (type peers-data)))]

                (if (:error peers-result)
                  peers-result  ; Pass through peer parsing error
                  {:ok (cond-> {:success true
                                :protocol :http
                                :response-type :announce
                                :peers (:ok peers-result)
                                :interval interval
                                :complete complete
                                :incomplete incomplete}
                         min-interval (assoc :min-interval min-interval)
                         tracker-id (assoc :tracker-id tracker-id)
                         warning (assoc :warning-message warning))}))
              (catch Exception e
                (tracker-error :protocol-error
                               "Failed to parse tracker response"
                               :exception (.getMessage e))))))))))

(s/fdef parse-http-tracker-response
  :args (s/cat :response-bytes bytes?)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(s/valid? ::spec/tracker-response (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
        ;; If successful, result should be a valid tracker response
       :success #(let [response (:ok (second (:ret %)))]
                   (or (not= :success (first (:ret %)))
                       (and (contains? response :success)
                            (contains? response :protocol)
                            (= :http (:protocol response)))))
       :error #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; HTTP Request Building
;; ---------------------------------------------------------------------------

(defn- build-query-string
  "Build query string from parameter map. Values should be pre-encoded."
  [params]
  (->> params
       (map (fn [[k v]] (str (name k) "=" v)))
       (string/join "&")))

(defn build-http-announce-url
  "Build HTTP tracker announce request URL.

  Constructs a properly formatted announce URL with all required and
  optional BitTorrent protocol parameters. Binary data (info-hash, peer-id)
  is URL-encoded per RFC 3986.

  Parameters:
    tracker-url - Base tracker URL (e.g., 'http://tracker.example.com/announce')
    request - Map with required and optional parameters:
      Required:
        :info-hash - 20-byte torrent identifier
        :peer-id - 20-byte client identifier
        :port - Listening port (1-65535)
        :uploaded - Total bytes uploaded this session
        :downloaded - Total bytes downloaded this session
        :left - Bytes remaining to download
      Optional:
        :event - :started | :completed | :stopped (keyword)
        :compact - Boolean (default true) - request compact peer format
        :num-want - Number of peers wanted (default 50)
        :no-peer-id - Boolean (default false) - omit peer-id in response
        :tracker-id - Tracker ID from previous response (string)

  Returns:
    {:ok url-string} or {:error ...}"
  [^String tracker-url request]
  ;; T050: Input validation
  (if-let [validation-error (validate-input ::spec/tracker-request request)]
    validation-error
    ;; T046: Build required parameters
    (let [{:keys [info-hash peer-id port uploaded downloaded left
                  event compact num-want no-peer-id tracker-id]} request
          params {:info_hash (url-encode-binary info-hash)
                  :peer_id (url-encode-binary peer-id)
                  :port (str port)
                  :uploaded (str uploaded)
                  :downloaded (str downloaded)
                  :left (str left)}
          ;; T047: Add optional parameters
          params-with-opts (cond-> params
                             ;; Event parameter
                             event
                             (assoc :event (name event))

                             ;; Compact parameter (default true)
                             (contains? request :compact)
                             (assoc :compact (if compact "1" "0"))

                             (not (contains? request :compact))
                             (assoc :compact "1")  ; Default to compact

                             ;; Num-want parameter
                             num-want
                             (assoc :numwant (str num-want))

                             ;; No-peer-id parameter
                             no-peer-id
                             (assoc :no_peer_id (if no-peer-id "1" "0"))

                             ;; Tracker ID parameter
                             tracker-id
                             (assoc :trackerid tracker-id))

          query-string (build-query-string params-with-opts)
          ;; T048: Handle existing query parameters
          separator (if (.contains tracker-url "?") "&" "?")
          full-url (str tracker-url separator query-string)]
      {:ok full-url})))

(s/fdef build-http-announce-url
  :args (s/cat :tracker-url string?
               :request ::spec/tracker-request)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(string? (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
        ;; If successful, URL should start with the tracker-url base
       :success #(let [tracker-url ^String (:tracker-url (:args %))
                       result-url ^String (:ok (second (:ret %)))]
                   (or (not= :success (first (:ret %)))
                       (.startsWith result-url tracker-url)))
        ;; If error, return value should match error pattern
       :error #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; UDP Tracker Response Parsing (BEP 15)
;; ---------------------------------------------------------------------------

(defn parse-udp-connect-response
  "Parse UDP tracker connect response (BEP 15).

  Binary layout (big-endian, 16 bytes total):
    Offset 0: action (4 bytes, must be 0)
    Offset 4: transaction_id (4 bytes)
    Offset 8: connection_id (8 bytes)

  Parameters:
    response-bytes - 16-byte byte array

  Returns:
    {:ok {:action :connect, :transaction-id int, :connection-id long}} or {:error ...}"
  [response-bytes]
  (if-let [err (validate-input bytes? response-bytes)]
    err
    (if (not= 16 (alength ^bytes response-bytes))
      (tracker-error :invalid-message-length "Connect response must be 16 bytes"
                     :length (alength ^bytes response-bytes))
      (let [buf (ByteBuffer/wrap response-bytes)
            action (.getInt buf)
            transaction-id (.getInt buf)
            connection-id (.getLong buf)]
        (if (not= 0 action)
          (tracker-error :invalid-action-code "Expected action 0 (connect)"
                         :action action)
          {:ok {:action :connect
                :transaction-id transaction-id
                :connection-id connection-id}})))))

(s/fdef parse-udp-connect-response
  :args (s/cat :response-bytes bytes?)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(s/valid? ::spec/udp-connect-response (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
       :success #(= :connect (-> % :ret second :ok :action))
       :error   #(= :error (first (:ret %)))))

(defn parse-udp-announce-response
  "Parse UDP tracker announce response (BEP 15).

  Binary layout (big-endian, 20+ bytes):
    Offset 0:  action (4 bytes, must be 1)
    Offset 4:  transaction_id (4 bytes)
    Offset 8:  interval (4 bytes)
    Offset 12: leechers (4 bytes)
    Offset 16: seeders (4 bytes)
    Offset 20: peers (6 bytes each for IPv4, 18 bytes each for IPv6)

  Parameters:
    response-bytes - 20+ byte array

  Returns:
    {:ok {:action :announce, :transaction-id, :interval, :leechers, :seeders, :peers [...]}}
    or {:error ...}"
  [response-bytes]
  (if-let [err (validate-input bytes? response-bytes)]
    err
    (let [len (alength ^bytes response-bytes)]
      (if (< len 20)
        (tracker-error :invalid-message-length "Announce response must be >= 20 bytes"
                       :length len)
        (let [buf (ByteBuffer/wrap response-bytes)
              action (.getInt buf)
              transaction-id (.getInt buf)
              interval (.getInt buf)
              leechers (.getInt buf)
              seeders (.getInt buf)]
          (if (not= 1 action)
            (tracker-error :invalid-action-code "Expected action 1 (announce)"
                           :action action)
            (let [peer-bytes (byte-array (- len 20))
                  _ (.get buf peer-bytes)
                  peers-result (if (and (pos? (alength peer-bytes))
                                        (= 0 (mod (alength peer-bytes) 18))
                                        (not= 0 (mod (alength peer-bytes) 6)))
                                 (parse-compact-peers-ipv6 peer-bytes)
                                 (parse-compact-peers-ipv4 peer-bytes))]
              (if (:error peers-result)
                peers-result
                {:ok {:action :announce
                      :transaction-id transaction-id
                      :interval interval
                      :leechers leechers
                      :seeders seeders
                      :peers (:ok peers-result)}}))))))))

(s/fdef parse-udp-announce-response
  :args (s/cat :response-bytes bytes?)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(s/valid? ::spec/udp-announce-response (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
       :success #(= :announce (-> % :ret second :ok :action))
       :error   #(= :error (first (:ret %)))))

(defn parse-udp-error-response
  "Parse UDP tracker error response (BEP 15).

  Binary layout (big-endian, 8+ bytes):
    Offset 0: action (4 bytes, must be 3)
    Offset 4: transaction_id (4 bytes)
    Offset 8: failure_reason (UTF-8 string, remainder of buffer)

  Parameters:
    response-bytes - 8+ byte array

  Returns:
    {:ok {:action :error, :transaction-id, :success false, :failure-reason \"...\"}}
    or {:error ...}"
  [response-bytes]
  (if-let [err (validate-input bytes? response-bytes)]
    err
    (let [len (alength ^bytes response-bytes)]
      (if (< len 8)
        (tracker-error :invalid-message-length "Error response must be >= 8 bytes"
                       :length len)
        (let [buf (ByteBuffer/wrap response-bytes)
              action (.getInt buf)
              transaction-id (.getInt buf)
              msg-bytes (byte-array (- len 8))
              _ (.get buf msg-bytes)
              failure-reason (String. msg-bytes "UTF-8")]
          (if (not= 3 action)
            (tracker-error :invalid-action-code "Expected action 3 (error)"
                           :action action)
            {:ok {:action :error
                  :transaction-id transaction-id
                  :success false
                  :failure-reason failure-reason}}))))))

(s/fdef parse-udp-error-response
  :args (s/cat :response-bytes bytes?)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(s/valid? ::spec/udp-error-response (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
       :success #(= :error (-> % :ret second :ok :action))
       :error   #(= :error (first (:ret %)))))

(defn parse-udp-scrape-response
  "Parse UDP tracker scrape response (BEP 15).

  Binary layout (big-endian, 8+N*12 bytes):
    Offset 0:       action (4 bytes, must be 2)
    Offset 4:       transaction_id (4 bytes)
    Offset 8+i*12:  seeders (4 bytes) per torrent i
    Offset 12+i*12: completed (4 bytes) per torrent i
    Offset 16+i*12: leechers (4 bytes) per torrent i

  Parameters:
    response-bytes - (8+N*12) byte array

  Returns:
    {:ok {:action :scrape, :transaction-id, :torrents [{:seeders, :completed, :leechers} ...]}}
    or {:error ...}"
  [response-bytes]
  (if-let [err (validate-input bytes? response-bytes)]
    err
    (let [len (alength ^bytes response-bytes)]
      (if (or (< len 8) (not= 0 (mod (- len 8) 12)))
        (tracker-error :invalid-message-length "Scrape response must be 8+N*12 bytes"
                       :length len)
        (let [buf (ByteBuffer/wrap response-bytes)
              action (.getInt buf)
              transaction-id (.getInt buf)]
          (if (not= 2 action)
            (tracker-error :invalid-action-code "Expected action 2 (scrape)"
                           :action action)
            (let [torrent-count (/ (- len 8) 12)
                  torrents (vec (for [_ (range torrent-count)]
                                  (let [seeders (.getInt buf)
                                        completed (.getInt buf)
                                        leechers (.getInt buf)]
                                    {:seeders seeders
                                     :completed completed
                                     :leechers leechers})))]
              {:ok {:action :scrape
                    :transaction-id transaction-id
                    :torrents torrents}})))))))

(s/fdef parse-udp-scrape-response
  :args (s/cat :response-bytes bytes?)
  :ret (s/or :success (s/and (s/keys :req-un [::spec/ok])
                             #(s/valid? ::spec/udp-scrape-response (:ok %)))
             :error ::spec/error-result)
  :fn (s/or
       :success #(= :scrape (-> % :ret second :ok :action))
       :error   #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; UDP Tracker Request Building (BEP 15)
;; ---------------------------------------------------------------------------

(def ^:private udp-protocol-magic 0x41727101980)

(def ^:private event->code
  {nil 0, :completed 1, :started 2, :stopped 3})

(defn build-udp-connect-request
  "Build UDP tracker connect request (BEP 15).

  Binary layout (big-endian, 16 bytes total):
    Offset 0:  protocol_magic (8 bytes, 0x41727101980)
    Offset 8:  action (4 bytes, 0 = connect)
    Offset 12: transaction_id (4 bytes)

  Parameters:
    request - Map with :transaction-id

  Returns:
    {:ok byte-array} or {:error ...}"
  [request]
  (if-let [err (validate-input ::spec/udp-connect-request request)]
    err
    (let [{:keys [transaction-id]} request
          buf (ByteBuffer/allocate 16)]
      (.putLong buf udp-protocol-magic)
      (.putInt  buf 0)
      (.putInt  buf transaction-id)
      {:ok (.array buf)})))

(s/fdef build-udp-connect-request
  :args (s/cat :request ::spec/udp-connect-request)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
       :success #(= 16 (let [resp-bytes ^bytes (:ok (second (:ret %)))]
                         (alength resp-bytes)))
       :error   #(= :error (first (:ret %)))))

(defn build-udp-announce-request
  "Build UDP tracker announce request (BEP 15).

  Binary layout (big-endian, 98 bytes total):
    Offset 0:  connection_id (8 bytes)
    Offset 8:  action (4 bytes, 1 = announce)
    Offset 12: transaction_id (4 bytes)
    Offset 16: info_hash (20 bytes)
    Offset 36: peer_id (20 bytes)
    Offset 56: downloaded (8 bytes)
    Offset 64: left (8 bytes)
    Offset 72: uploaded (8 bytes)
    Offset 80: event (4 bytes, 0=none 1=completed 2=started 3=stopped)
    Offset 84: ip_address (4 bytes, 0 = use sender IP)
    Offset 88: key (4 bytes)
    Offset 92: num_want (4 bytes, -1 = no preference)
    Offset 96: port (2 bytes)

  Parameters:
    request - Map with required/optional keys per ::spec/udp-announce-request

  Returns:
    {:ok byte-array} or {:error ...}"
  [request]
  (if-let [err (validate-input ::spec/udp-announce-request request)]
    err
    (let [{:keys [connection-id transaction-id info-hash peer-id
                  downloaded left uploaded port event num-want]} request
          buf (ByteBuffer/allocate 98)]
      (.putLong  buf (long connection-id))
      (.putInt   buf 1)
      (.putInt   buf transaction-id)
      (.put      buf ^bytes info-hash)
      (.put      buf ^bytes peer-id)
      (.putLong  buf downloaded)
      (.putLong  buf left)
      (.putLong  buf uploaded)
      (.putInt   buf (get event->code event 0))
      (.putInt   buf 0)
      (.putInt   buf 0)
      (.putInt   buf (or num-want -1))
      (.putShort buf (unchecked-short port))
      {:ok (.array buf)})))

(s/fdef build-udp-announce-request
  :args (s/cat :request ::spec/udp-announce-request)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
       :success #(= 98 (let [resp-bytes ^bytes (:ok (second (:ret %)))]
                         (alength resp-bytes)))
       :error   #(= :error (first (:ret %)))))

(defn build-udp-scrape-request
  "Build UDP tracker scrape request (BEP 15).

  Binary layout (big-endian, 16+N*20 bytes):
    Offset 0:    connection_id (8 bytes)
    Offset 8:    action (4 bytes, 2 = scrape)
    Offset 12:   transaction_id (4 bytes)
    Offset 16+:  info_hashes (20 bytes each)

  Parameters:
    request - Map with :connection-id, :transaction-id, :info-hashes

  Returns:
    {:ok byte-array} or {:error ...}"
  [request]
  (if-let [err (validate-input ::spec/udp-scrape-request request)]
    err
    (let [{:keys [connection-id transaction-id info-hashes]} request
          n   (count info-hashes)
          buf (ByteBuffer/allocate (+ 16 (* 20 n)))]
      (.putLong buf (long connection-id))
      (.putInt  buf 2)
      (.putInt  buf transaction-id)
      (doseq [h info-hashes] (.put buf ^bytes h))
      {:ok (.array buf)})))

(s/fdef build-udp-scrape-request
  :args (s/cat :request ::spec/udp-scrape-request)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
       :success #(let [req (:request (:args %))
                       n   (count (:info-hashes req))
                       resp-bytes ^bytes (:ok (second (:ret %)))]
                   (= (+ 16 (* 20 n))
                      (alength resp-bytes)))
       :error   #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; Re-Announce Timing (US6)
;; ---------------------------------------------------------------------------
;; Pending policy, kept deliberately (issue #44 decision, overlaps #22):
;; these scheduling fns have no callers yet, but phased and repeat
;; announces (streaming, paced re-announce) reuse them as the pure "when
;; to ask next" half. Do not delete.

(def ^:private default-interval-seconds 1800)  ; T102
(def ^:private max-backoff-ms 3600000)          ; 1 hour cap
(def ^:private backoff-base-ms 1000)            ; 1 second base

(defn calculate-next-announce
  "Calculate next announce timestamp (ms since epoch).

  Uses min-interval-seconds if provided, otherwise interval-seconds,
  otherwise the default of 1800 seconds.

  Parameters:
    current-time-ms       - Current timestamp in milliseconds
    interval-seconds      - Tracker-provided interval, or nil
    min-interval-seconds  - Tracker-provided min-interval, or nil

  Returns: {:ok next-announce-time-ms}"
  [current-time-ms interval-seconds min-interval-seconds]
  (let [effective (or min-interval-seconds interval-seconds default-interval-seconds)]
    {:ok (+ current-time-ms (* effective 1000))}))

(s/fdef calculate-next-announce
  :args (s/cat :current-time-ms nat-int?
               :interval-seconds (s/nilable pos-int?)
               :min-interval-seconds (s/nilable pos-int?))
  :ret (s/keys :req-un [::spec/ok])
  :fn #(> (-> % :ret :ok) (-> % :args :current-time-ms)))

(defn calculate-exponential-backoff
  "Calculate exponential backoff delay for retry attempt N.

  Formula: base * 2^(attempt-1), capped at max-backoff-ms (1 hour).
  attempt=1 → 1000ms, attempt=2 → 2000ms, attempt=3 → 4000ms, etc.

  Parameters:
    attempt - 1-based retry attempt number (positive integer)

  Returns: {:ok delay-ms}"
  [attempt]
  {:ok (long (min (* backoff-base-ms (Math/pow 2 (dec attempt)))
                  max-backoff-ms))})

(s/fdef calculate-exponential-backoff
  :args (s/cat :attempt pos-int?)
  :ret (s/keys :req-un [::spec/ok])
  :fn #(<= (-> % :ret :ok) max-backoff-ms))

(defn update-schedule-success
  "Pure state transition: update AnnounceSchedule after successful announce.

  Resets retry state and schedules next announce at current-time + interval.

  Parameters:
    schedule          - Current ::spec/announce-schedule map
    interval-seconds  - Interval from tracker response
    current-time-ms   - Current timestamp in milliseconds

  Returns: {:ok updated-schedule} or {:error ...}"
  [schedule interval-seconds current-time-ms]
  (if-let [err (validate-input ::spec/announce-schedule schedule)]
    err
    {:ok (assoc schedule
                :next-announce-time (+ current-time-ms (* interval-seconds 1000))
                :interval-seconds interval-seconds
                :retry-attempt 0
                :backoff-delay-ms 0)}))

(s/fdef update-schedule-success
  :args (s/cat :schedule ::spec/announce-schedule
               :interval-seconds pos-int?
               :current-time-ms nat-int?)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
       :success #(= 0 (-> % :ret second :ok :retry-attempt))
       :error   #(= :error (first (:ret %)))))

(defn update-schedule-failure
  "Pure state transition: update AnnounceSchedule after failed announce.

  Increments retry-attempt and schedules next attempt using exponential backoff.

  Parameters:
    schedule        - Current ::spec/announce-schedule map
    current-time-ms - Current timestamp in milliseconds

  Returns: {:ok updated-schedule} or {:error ...}"
  [schedule current-time-ms]
  (if-let [err (validate-input ::spec/announce-schedule schedule)]
    err
    (let [attempt (inc (:retry-attempt schedule))
          backoff (:ok (calculate-exponential-backoff attempt))]
      {:ok (assoc schedule
                  :next-announce-time (+ current-time-ms backoff)
                  :retry-attempt attempt
                  :backoff-delay-ms backoff)})))

(s/fdef update-schedule-failure
  :args (s/cat :schedule ::spec/announce-schedule
               :current-time-ms nat-int?)
  :ret (s/or :success (s/keys :req-un [::spec/ok])
             :error ::spec/error-result)
  :fn (s/or
       :success #(= (inc (-> % :args :schedule :retry-attempt))
                    (-> % :ret second :ok :retry-attempt))
       :error   #(= :error (first (:ret %)))))

;; ---------------------------------------------------------------------------
;; Tracker fan-out policy (issue #44)
;; ---------------------------------------------------------------------------
;; Pure ordering + merge policy for tracker queries. No sockets here: the
;; network adapter calls pick-tracker-order once, then announces to ONE
;; URL at a time so a coordinator loop can emit :tracker-peers
;; incrementally (streaming). combine-peers stays merge-shaped
;; (set-union) so late arrivals fold into already-dialed sets.

(def fallback-trackers
  "Well-known public trackers appended after declared tracker URLs."
  ["udp://tracker.opentrackr.org:1337"
   "udp://open.demonii.com:1337"
   "udp://open.stealth.si:80"
   "udp://tracker.torrent.eu.org:451"
   "udp://explodie.org:6969"
   "udp://exodus.desync.com:6969"])

(defn- tracker-url?
  "True when the string names a tracker URL this client can query: an
   http, https, or udp URI. The scheme must match completely and
   case-insensitively (RFC 3986) -- a prefix check would drop HTTP://
   yet admit udpx://, which the adapter would then misdispatch."
  [candidate]
  (and (string? candidate)
       (boolean (re-find #"(?i)\A(?:http|https|udp)://" candidate))))

(defn pick-tracker-order
  "Order tracker URLs for sequential querying.

   Primary :announce first, then :announce-list tiers flattened in order,
   then well-known public fallbacks. Deduplicated keeping the first
   occurrence; nil entries and URLs with unsupported schemes dropped.

   Parameters:
     torrent-metadata - Map with :announce and optional :announce-list

   Returns: vector of tracker URL strings."
  [torrent-metadata]
  (let [primary (:announce torrent-metadata)
        tiers (:announce-list torrent-metadata)
        ;; Tiers arrive as vectors of vectors from parsed torrents; a
        ;; non-sequential shape contributes nothing instead of throwing
        ;; out of mapcat, so this stays total over any metadata map.
        from-list (mapcat #(if (sequential? %) % [])
                          (if (sequential? tiers) tiers []))
        all (concat (if primary (cons primary from-list) from-list)
                    fallback-trackers)]
    (vec (distinct (filter tracker-url? all)))))

(s/fdef pick-tracker-order
  :args (s/cat :torrent-metadata map?)
  :ret (s/coll-of string? :kind vector?)
  :fn #(let [metadata (-> % :args :torrent-metadata)
             order (:ret %)]
         (and (if (tracker-url? (:announce metadata))
                (= (:announce metadata) (first order))
                true)
              (= order (vec (distinct order)))
              (every? tracker-url? order))))

(defn combine-peers
  "Merge peer address collections into one set.

   Set-union over address sets, so a coordinator can fold late tracker
   arrivals into the already-dialed set incrementally.

   Parameters:
     peer-sets - any number of address collections (nil counts as empty)

   Returns: a set of peer addresses."
  [& peer-sets]
  (into #{} (mapcat #(or (seq %) [])) peer-sets))

(s/fdef combine-peers
  :args (s/cat :peer-sets (s/* (s/nilable coll?)))
  :ret set?
  :fn (fn [{:keys [args ret]}]
        (= ret (into #{} (mapcat (fn [peer-set] (or (seq peer-set) []))
                                 (:peer-sets args))))))
