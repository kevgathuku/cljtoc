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

;; ---------------------------------------------------------------------------
;; Error constructors
;; ---------------------------------------------------------------------------

(defn tracker-error
  "Constructs a tracker protocol error map."
  [error-type message & {:as context}]
  (merge {:error error-type
          :message message}
         context))

;; ---------------------------------------------------------------------------
;; Binary utilities
;; ---------------------------------------------------------------------------

(defn unsigned-short
  "Convert Java signed short to unsigned integer (0-65535).
  Java shorts are signed (-32768 to 32767), but network protocols
  use unsigned shorts. This function performs the conversion."
  [s]
  (bit-and s 0xFFFF))

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
  :fn #(let [input-len (alength (-> % :args :data))
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
       :success #(let [input-len (alength (-> % :args :peers-bytes))
                       peers (-> % :ret second :ok)]
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
       :success #(let [input-len (alength (-> % :args :peers-bytes))
                       peers (-> % :ret second :ok)]
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
                       peers (-> % :ret second :ok)]
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
       :success #(let [response (-> % :ret second :ok)]
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
  [tracker-url request]
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
       :success #(let [tracker-url (-> % :args :tracker-url)
                       result-url (-> % :ret second :ok)]
                   (or (not= :success (first (:ret %)))
                       (.startsWith result-url tracker-url)))
        ;; If error, return value should match error pattern
       :error #(= :error (first (:ret %)))))
