(ns dev.cljtoc.ports.network-impl
  "Real network I/O implementation for download orchestration.

   Provides functions for TCP peer connections and tracker communication."
  (:require [clojure.string :as str]
            [dev.cljtoc.domain.peer-address :as peer-address]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]
            [dev.cljtoc.utils :as utils])
  (:import [java.net DatagramPacket DatagramSocket InetSocketAddress Socket
            HttpURLConnection URI URL]
           [java.io ByteArrayOutputStream InputStream]
           [java.security SecureRandom]))

;; Byte arrays flow through socket reads and tracker datagrams here; fail the
;; compile on reflective calls so boxing never hides in the hot path.
(set! *warn-on-reflection* true)

(def ^:private ^java.security.SecureRandom random (SecureRandom.))

(def ^:private default-timeouts
  "Historical socket timeout literals, now overridable via create opts:
   :connect-timeout-ms, :socket-timeout-ms, :udp-timeout-ms,
   :http-timeout-ms."
  {:connect-timeout-ms 5000
   :socket-timeout-ms 10000
   :udp-timeout-ms 5000
   :http-timeout-ms 10000})

(defn- timeout-ms
  "Read a timeout from the adapter config, falling back to the default."
  [network timeout-key]
  (get (:config network) timeout-key (get default-timeouts timeout-key)))

(defn- log!
  "Private delegate for network/log!: the network alias is shadowed by
   the port arg inside every method body, so call sites use this."
  [port message]
  (network/log! port message))

(defn- generate-peer-id
  "Generate a random 20-byte peer ID for tracker announcements."
  []
  (let [bytes (byte-array 20)]
    (.nextBytes random bytes)
    bytes))

(defn- read-fully
  "Read exactly n bytes from an InputStream. Returns byte array or throws on EOF."
  [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [offset 0]
      (if (= offset n)
        buf
        (let [read-count (.read in buf offset (- n offset))]
          (if (= read-count -1)
            (throw (java.io.EOFException. (str "EOF after " offset " of " n " bytes")))
            (recur (+ offset read-count))))))))

(defn- make-http-request
  "Make HTTP GET request and return response body as byte array."
  [url-str timeout-ms]
  (let [conn (.openConnection (URL. url-str))
        ^HttpURLConnection httpConn conn]
    (.setRequestMethod httpConn "GET")
    (.setConnectTimeout httpConn timeout-ms)
    (.setReadTimeout httpConn timeout-ms)
    (.setRequestProperty httpConn "User-Agent" "cljtoc/0.1.0")
    (.connect httpConn)
    (let [response-code (.getResponseCode httpConn)]
      (if (= 200 response-code)
        (let [stream (.getInputStream httpConn)
              baos (ByteArrayOutputStream.)
              buffer (byte-array 4096)]
          (loop []
            (let [len (.read stream buffer)]
              (when (pos? len)
                (.write baos buffer 0 len)
                (recur))))
          (.close stream)
          {:ok (.toByteArray baos)})
        {:error (str "HTTP " response-code)}))))

(defn- udp-exchange
  "Send a UDP datagram and wait for a response. Returns byte array or throws."
  [^DatagramSocket socket ^bytes send-data ^InetSocketAddress addr timeout-ms]
  (let [send-pkt (DatagramPacket. send-data (alength send-data) addr)]
    (.send socket send-pkt)
    (let [recv-buf (byte-array 65536)
          recv-pkt (DatagramPacket. recv-buf (alength recv-buf))]
      (.setSoTimeout socket timeout-ms)
      (.receive socket recv-pkt)
      (java.util.Arrays/copyOf recv-buf (.getLength recv-pkt)))))

(defn- tracker-peer->address
  "Render a tracker {:ip :port} peer map as a canonical address string.
   IPv6 hosts are bracketed so the port survives parsing downstream."
  [{:keys [ip port]}]
  (peer-address/format-address {:host ip :port port}))

(defn- try-udp-tracker
  "Try announcing to a UDP tracker. Returns {:ok peers} or {:error ...}."
  [network tracker-url request]
  (try
    (let [uri (URI. tracker-url)
          ^String host (.getHost uri)
          port (let [p (.getPort uri)] (if (= p -1) 6969 p))
          addr (InetSocketAddress. host (int port))
          timeout (timeout-ms network :udp-timeout-ms)
          socket (doto (DatagramSocket.) (.setSoTimeout timeout))
          txn-id (.nextInt (java.util.Random.))]
      (try
        ;; Step 1: Connect
        (let [connect-req (:ok (tracker/build-udp-connect-request
                                {:transaction-id txn-id}))
              connect-resp (udp-exchange socket connect-req addr timeout)
              connect-parsed (tracker/parse-udp-connect-response connect-resp)]
          (if (:error connect-parsed)
            {:error :udp-connect-failed :message (str tracker-url ": " (:message connect-parsed))}
            (let [conn-id (:connection-id (:ok connect-parsed))
                  txn-id2 (.nextInt (java.util.Random.))
                  ;; Step 2: Announce
                  announce-req (:ok (tracker/build-udp-announce-request
                                     {:connection-id conn-id
                                      :transaction-id txn-id2
                                      :info-hash (:info-hash request)
                                      :peer-id (:peer-id request)
                                      :downloaded (:downloaded request)
                                      :left (:left request)
                                      :uploaded (:uploaded request)
                                      :event (:event request)
                                      :num-want (or (:num-want request) 50)
                                      :port (:port request)}))
                  announce-resp (udp-exchange socket announce-req addr timeout)
                  announce-parsed (tracker/parse-udp-announce-response announce-resp)]
              (if (:error announce-parsed)
                {:error :udp-announce-failed :message (str tracker-url ": " (:message announce-parsed))}
                (let [peers (:peers (:ok announce-parsed))]
                  {:ok (set (map tracker-peer->address peers))})))))
        (finally (.close socket))))
    (catch Exception e
      {:error :udp-failed :message (str tracker-url ": " (.getMessage e))})))

(defn- try-http-tracker
  "Try announcing to an HTTP tracker. Returns {:ok peers} or {:error ...}."
  [network tracker-url request]
  (let [url-result (tracker/build-http-announce-url tracker-url request)]
    (if (:error url-result)
      {:error :build-url-failed :message (:message url-result)}
      (let [http-result (try
                          (make-http-request (:ok url-result)
                                             (timeout-ms network :http-timeout-ms))
                          (catch Exception e
                            {:error (.getMessage e)}))]
        (if (:error http-result)
          {:error :http-failed :message (str tracker-url ": " (:error http-result))}
          (let [parse-result (tracker/parse-http-tracker-response (:ok http-result))]
            (if (:error parse-result)
              {:error :parse-failed :message (:message parse-result)}
              ;; A tracker rejection ({:ok {:success false}}) is an error
              ;; carrying the failure reason -- never a successful empty
              ;; set, which incremental callers would read as "no peers yet".
              (if (false? (:success (:ok parse-result)))
                {:error :tracker-rejected
                 :message (str tracker-url ": " (:failure-reason (:ok parse-result)))}
                (let [peers (:peers (:ok parse-result))
                      _ (log! network (str "[tracker] Raw peers sample: " (vec (take 3 peers))))
                      addresses (set (map tracker-peer->address peers))
                      _ (log! network (str "[tracker] Sample addresses: " (vec (take 3 addresses))))]
                  {:ok addresses})))))))))

(defn- try-single-tracker
  "Try announcing to a single tracker URL. Returns {:ok peers} or {:error ...}."
  [network tracker-url request]
  ;; Case-insensitive like the pick-tracker-order filter, so an admitted
  ;; uppercase UDP:// URL still routes to the UDP path.
  (if (str/starts-with? (str/lower-case tracker-url) "udp")
    (try-udp-tracker network tracker-url request)
    (try-http-tracker network tracker-url request)))

(defrecord NetworkPort
           [config peer-connections]

  network/INetworkPort
  (connect-peer [network address]
    "Open TCP connection to a peer at the given address.
     Returns {:ok peer-data} or {:error reason :message msg}."
    (try
      (log! network (str "[connect] Attempting to connect to: " address))
      (let [parsed (peer-address/parse address)]
        (if (:error parsed)
          {:error :invalid-address :message (:message parsed)}
          (let [{:keys [host port]} (:ok parsed)
                _ (log! network (str "[connect-peer] host=" host " port=" port))
                socket (doto (Socket.)
                         (.connect (InetSocketAddress. ^String host (int port))
                                   (timeout-ms network :connect-timeout-ms))
                         (.setSoTimeout (timeout-ms network :socket-timeout-ms)))
                peer-data {:id address
                           :address address
                           :socket socket
                           :in (.getInputStream socket)
                           :out (.getOutputStream socket)}]
            (swap! (:peer-connections network) assoc address peer-data)
            {:ok peer-data})))
      (catch Exception e
        {:error :connect-failed :message (.getMessage e)})))

  (send-message [_ peer message]
    "Send a peer wire message to the connected peer.
     Returns {:ok :sent} or {:error reason :message msg}."
    (try
      (let [^java.io.OutputStream out (:out peer)]
        (.write out ^bytes message)
        (.flush out)
        {:ok :sent})
      (catch Exception e
        {:error :send-failed :message (.getMessage e)})))

  (receive-message [_ peer]
    "Receive the next peer wire protocol message from a peer.
     Reads 4-byte length prefix, then length bytes of payload.
     Returns {:ok peer-message} or {:error reason :message msg}."
    (try
      (let [in (:in peer)
            len-bytes (read-fully in 4)
            msg-len (utils/bytes-to-int32 len-bytes)]
        (if (zero? msg-len)
          {:ok (peer/->KeepAlive)}
          (let [payload (read-fully in msg-len)
                full-msg (utils/concat-bytes len-bytes payload)]
            (peer/parse-message full-msg))))
      (catch java.io.EOFException _
        {:error :disconnected :message "Peer disconnected"})
      (catch Exception e
        {:error :receive-failed :message (.getMessage e)})))

  (receive-handshake [_ peer]
    "Read a 68-byte peer handshake from the connection.
     Returns {:ok peer-handshake} or {:error reason :message msg}."
    (try
      (let [in (:in peer)
            handshake-bytes (read-fully in 68)]
        (peer/parse-handshake handshake-bytes))
      (catch java.net.SocketTimeoutException _
        {:error :timeout :message "Handshake read timed out"})
      (catch java.io.EOFException _
        {:error :disconnected :message "Peer disconnected during handshake"})
      (catch Exception e
        {:error :receive-failed :message (.getMessage e)})))

  (close-peer [network peer]
    "Close the connection to a peer gracefully."
    (try
      (when-let [^java.net.Socket socket (:socket peer)]
        (.close socket))
      (swap! (:peer-connections network) dissoc (:id peer))
      nil
      (catch Exception _ nil)))

  network/ITrackerPort
  (announce-to-url [network tracker-url request]
    "Announce to ONE tracker URL. The per-URL effect half of the fan-out
     policy: ordering (tracker/pick-tracker-order) and merging
     (tracker/combine-peers) stay pure so a coordinator loop can query
     URLs incrementally. Returns {:ok #{peer-address}} or {:error ...}."
    (try-single-tracker network tracker-url request))

  (announce [network torrent-metadata progress]
    "Announce to trackers and get a list of peers.
     Queries ALL tracker URLs and combines peers for maximum coverage.
     progress is {:downloaded bytes-on-disk :left bytes-remaining}, computed
     by the caller from the download record -- this port only transmits it.
     Returns {:ok #{peer-address}} or {:error reason :message msg}."
    (try
      (let [tracker-urls (tracker/pick-tracker-order torrent-metadata)]
        (if (empty? tracker-urls)
          {:error :no-tracker :message "No tracker URL available"}
          (let [request {:info-hash (:info-hash torrent-metadata)
                         :peer-id (generate-peer-id)
                         :port 6881
                         :uploaded 0
                         :downloaded (:downloaded progress)
                         :left (:left progress)
                         :event :started
                         :compact true
                         :num-want 200}]
            ;; Query all trackers and combine peers. Success tracks
            ;; separately from the peer count: an announce that answers
            ;; with zero peers is a genuinely empty swarm, not an outage.
            (loop [urls tracker-urls
                   all-peers #{}
                   succeeded? false
                   last-error nil]
              (if (empty? urls)
                (if succeeded?
                  (do
                    (log! network (str "  Collected " (count all-peers) " unique peers from trackers"))
                    {:ok all-peers})
                  (or last-error
                      {:error :all-trackers-failed
                       :message "All trackers failed"}))
                (let [url (first urls)
                      _ (log! network (str "  Trying tracker: " url))
                      result (try-single-tracker network url request)]
                  (if (:ok result)
                    (do
                      (log! network (str "    Got " (count (:ok result)) " peers"))
                      (recur (rest urls) (tracker/combine-peers all-peers (:ok result)) true last-error))
                    (do
                      (log! network (str "    Failed: " (:message result)))
                      (recur (rest urls) all-peers succeeded? result)))))))))
      (catch Exception e
        {:error :tracker-error :message (.getMessage e)}))))

(defn create
  "Create a NetworkPort instance. Timeout opts are validated up front;
   present-but-invalid values throw instead of reaching the socket APIs."
  ([]
   (create {}))
  ([opts]
   (->NetworkPort (network/check-adapter-config opts) (atom {}))))
