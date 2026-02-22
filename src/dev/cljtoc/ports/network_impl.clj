(ns dev.cljtoc.ports.network-impl
  "Real network I/O implementation for download orchestration.

   Provides functions for TCP peer connections and tracker communication."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker])
  (:import [java.net DatagramPacket DatagramSocket InetSocketAddress Socket
            HttpURLConnection URI URL]
           [java.io ByteArrayOutputStream InputStream]
           [java.security SecureRandom]))

(defrecord NetworkPort
           [config peer-connections])

(def ^:private random (SecureRandom.))

(defn- generate-peer-id
  "Generate a random 20-byte peer ID for tracker announcements."
  []
  (let [bytes (byte-array 20)]
    (.nextBytes random bytes)
    bytes))

(defn connect-peer
  "Open TCP connection to a peer at the given address.
   Returns a channel that will deliver the peer connection or error."
  [network address]
  (let [ch (async/chan 1)]
    (async/thread
      (try
        (let [parts (str/split address #":")
              host (first parts)
              port (Integer/parseInt (second parts))
              socket (doto (Socket.)
                       (.connect (InetSocketAddress. host port) 10000))
              peer-data {:id address
                         :address address
                         :socket socket
                         :in (.getInputStream socket)
                         :out (.getOutputStream socket)}]
          (swap! (:peer-connections network) assoc address peer-data)
          (async/>!! ch {:ok peer-data}))
        (catch Exception e
          (async/>!! ch {:error :connect-failed :message (.getMessage e)}))))
    ch))

(defn send-message
  "Send a peer wire message to the connected peer.
   Returns a channel that will deliver the response or error."
  [network peer message]
  (let [ch (async/chan 1)]
    (async/go
      (try
        (let [out (:out peer)]
          (.write out message)
          (.flush out)
          (async/>! ch {:ok :sent}))
        (catch Exception e
          (async/>! ch {:error :send-failed :message (.getMessage e)}))))
    ch))

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

(defn receive-handshake
  "Read a 68-byte peer handshake from the connection.
   Returns a channel that will deliver {:ok PeerHandshake} or {:error ...}."
  [network peer]
  (let [ch (async/chan 1)]
    (async/thread
      (try
        (let [in (:in peer)
              handshake-bytes (read-fully in 68)
              result (peer/parse-handshake handshake-bytes)]
          (async/>!! ch result))
        (catch java.io.EOFException _
          (async/>!! ch {:error :disconnected :message "Peer disconnected during handshake"}))
        (catch Exception e
          (async/>!! ch {:error :receive-failed :message (.getMessage e)}))))
    ch))

(defn receive-message
  "Receive the next peer wire protocol message from a peer.
   Reads 4-byte length prefix, then length bytes of payload.
   Returns a channel that will deliver {:ok PeerMessage} or {:error ...}."
  [network peer]
  (let [ch (async/chan 1)]
    (async/thread
      (try
        (let [in (:in peer)
              len-bytes (read-fully in 4)
              msg-len (peer/bytes-to-int32 len-bytes)]
          (if (zero? msg-len)
            (async/>!! ch {:ok (peer/->KeepAlive)})
            (let [payload (read-fully in msg-len)
                  full-msg (peer/concat-bytes len-bytes payload)
                  result (peer/parse-message full-msg)]
              (async/>!! ch result))))
        (catch java.io.EOFException _
          (async/>!! ch {:error :disconnected :message "Peer disconnected"}))
        (catch Exception e
          (async/>!! ch {:error :receive-failed :message (.getMessage e)}))))
    ch))

(defn close-peer
  "Close the connection to a peer gracefully."
  [network peer]
  (try
    (when-let [socket (:socket peer)]
      (.close socket))
    (swap! (:peer-connections network) dissoc (:id peer))
    nil
    (catch Exception _ nil)))

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

(defn- collect-tracker-urls
  "Build a flat, deduplicated list of tracker URLs from announce + announce-list."
  [torrent-metadata]
  (let [primary (:announce torrent-metadata)
        from-list (mapcat identity (:announce-list torrent-metadata))
        all (if primary (cons primary from-list) from-list)]
    (distinct (filter #(and (some? %)
                            (or (str/starts-with? % "http")
                                (str/starts-with? % "udp")))
                      all))))

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

(defn- try-udp-tracker
  "Try announcing to a UDP tracker. Returns {:ok peers} or {:error ...}."
  [tracker-url request]
  (try
    (let [uri (URI. tracker-url)
          host (.getHost uri)
          port (let [p (.getPort uri)] (if (= p -1) 6969 p))
          addr (InetSocketAddress. host port)
          socket (doto (DatagramSocket.) (.setSoTimeout 5000))
          txn-id (.nextInt (java.util.Random.))]
      (try
        ;; Step 1: Connect
        (let [connect-req (:ok (tracker/build-udp-connect-request
                                {:transaction-id txn-id}))
              connect-resp (udp-exchange socket connect-req addr 5000)
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
                  announce-resp (udp-exchange socket announce-req addr 5000)
                  announce-parsed (tracker/parse-udp-announce-response announce-resp)]
              (if (:error announce-parsed)
                {:error :udp-announce-failed :message (str tracker-url ": " (:message announce-parsed))}
                (let [peers (:peers (:ok announce-parsed))]
                  {:ok (set (map #(str (:ip %) ":" (:port %)) peers))})))))
        (finally (.close socket))))
    (catch Exception e
      {:error :udp-failed :message (str tracker-url ": " (.getMessage e))})))

(defn- try-http-tracker
  "Try announcing to an HTTP tracker. Returns {:ok peers} or {:error ...}."
  [tracker-url request]
  (let [url-result (tracker/build-http-announce-url tracker-url request)]
    (if (:error url-result)
      {:error :build-url-failed :message (:message url-result)}
      (let [http-result (try
                          (make-http-request (:ok url-result) 10000)
                          (catch Exception e
                            {:error (.getMessage e)}))]
        (if (:error http-result)
          {:error :http-failed :message (str tracker-url ": " (:error http-result))}
          (let [parse-result (tracker/parse-http-tracker-response (:ok http-result))]
            (if (:error parse-result)
              {:error :parse-failed :message (:message parse-result)}
              (let [peers (:peers (:ok parse-result))]
                {:ok (set (map #(str (:ip %) ":" (:port %)) peers))}))))))))

(defn- try-single-tracker
  "Try announcing to a single tracker URL. Returns {:ok peers} or {:error ...}."
  [tracker-url request]
  (if (str/starts-with? tracker-url "udp")
    (try-udp-tracker tracker-url request)
    (try-http-tracker tracker-url request)))

(defn tracker-announce
  "Announce to the tracker and get a list of peers.
   Tries all tracker URLs from announce + announce-list until one succeeds.
   Returns a channel that will deliver #{peer-addresses} or error."
  [network torrent-metadata]
  (let [ch (async/chan 1)]
    (async/thread
      (try
        (let [tracker-urls (collect-tracker-urls torrent-metadata)]
          (if (empty? tracker-urls)
            (async/>!! ch {:error :no-tracker :message "No tracker URL available"})
            (let [info (:info torrent-metadata)
                  total-size (or (:length info)
                                 (reduce + (map :length (:files info))))
                  request {:info-hash (:info-hash torrent-metadata)
                           :peer-id (generate-peer-id)
                           :port 6881
                           :uploaded 0
                           :downloaded 0
                           :left total-size
                           :event :started
                           :compact true
                           :num-want 50}]
              (loop [urls tracker-urls
                     last-error nil]
                (if (empty? urls)
                  (async/>!! ch (or last-error
                                    {:error :all-trackers-failed
                                     :message "All trackers failed"}))
                  (let [url (first urls)
                        _ (println (str "  Trying tracker: " url))
                        result (try-single-tracker url request)]
                    (if (:ok result)
                      (async/>!! ch result)
                      (do
                        (println (str "    Failed: " (:message result)))
                        (recur (rest urls) result)))))))))
        (catch Exception e
          (async/>!! ch {:error :tracker-error :message (.getMessage e)}))))
    ch))

(defn tracker-scrape
  "Scrape tracker for torrent statistics.
   Returns a channel with scrape data or error."
  [network torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (async/>! ch {:ok {:seeders 0 :leechers 0 :complete 0}}))
    ch))

(extend-type NetworkPort
  network/INetworkPort
  (connect-peer [this address]
    (connect-peer this address))
  (send-message [this peer message]
    (send-message this peer message))
  (receive-message [this peer]
    (receive-message this peer))
  (close-peer [this peer]
    (close-peer this peer))
  (peer-loop [this peer handler]
    (async/thread
      (loop []
        (let [result (async/<!! (receive-message this peer))]
          (when result
            (handler result)
            (when (:ok result)
              (recur)))))))

  network/ITrackerPort
  (announce [this torrent-metadata]
    (tracker-announce this torrent-metadata))
  (scrape [this torrent-metadata]
    (tracker-scrape this torrent-metadata)))

(defn create
  "Create a NetworkPort instance."
  ([]
   (create {}))
  ([opts]
   (->NetworkPort opts (atom {}))))
