(ns dev.cljtoc.protocol.tracker
  "Tracker protocol implementation for BitTorrent peer discovery.

  Provides pure functions for parsing and building tracker protocol messages
  (HTTP and UDP). All functions return {:ok value} or {:error ...} maps.
  Network I/O is handled by injectable ports (not part of this namespace)."
  (:require [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.protocol.tracker.spec :as spec]
            [clojure.spec.alpha :as s])
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
