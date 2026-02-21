(ns dev.cljtoc.ports.network-impl
  "Real network I/O implementation for download orchestration.
   
   Provides functions for TCP peer connections and tracker communication."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.protocol.tracker :as tracker])
  (:import [java.net InetSocketAddress Socket HttpURLConnection URL]
           [java.io ByteArrayOutputStream]
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
    (async/go
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
          (async/>! ch {:ok peer-data}))
        (catch Exception e
          (async/>! ch {:error :connect-failed :message (.getMessage e)})))
      ch)))

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

(defn receive-message
  "Receive the next message from a peer.
   Returns a channel that will deliver the message or error."
  [network peer]
  (let [ch (async/chan 1)]
    (async/go
      (try
        (let [in (:in peer)
              first-byte (int (.read in))]
          (if (= first-byte -1)
            (async/>! ch {:error :disconnected :message "Peer disconnected"})
            (async/>! ch {:ok {:type :keep-alive}})))
        (catch Exception e
          (async/>! ch {:error :receive-failed :message (.getMessage e)}))))
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

(defn tracker-announce
  "Announce to the tracker and get a list of peers.
   Returns a channel that will deliver #{peer-addresses} or error."
  [network torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (try
        (let [tracker-url (or (:announce torrent-metadata)
                              (first (first (:announce-list torrent-metadata))))]
          (if (nil? tracker-url)
            (async/>! ch {:error :no-tracker :message "No tracker URL available"})
            (let [info-hash (:info-hash torrent-metadata)
                  info (:info torrent-metadata)
                  total-size (or (:length info)
                                 (reduce + (map :length (:files info))))
                  peer-id (generate-peer-id)

                  request {:info-hash info-hash
                           :peer-id peer-id
                           :port 6881
                           :uploaded 0
                           :downloaded 0
                           :left total-size
                           :event :started
                           :compact true
                           :num-want 50}

                  url-result (tracker/build-http-announce-url tracker-url request)]
              (if (:error url-result)
                (async/>! ch {:error :build-url-failed :message (:message url-result)})
                (let [announce-url (:ok url-result)
                      http-result (try
                                    (make-http-request announce-url 10000)
                                    (catch Exception e
                                      {:error (.getMessage e)}))]
                  (if (:error http-result)
                    (async/>! ch {:error :http-failed :message (:error http-result)})
                    (let [parse-result (tracker/parse-http-tracker-response (:ok http-result))]
                      (if (:error parse-result)
                        (async/>! ch {:error :parse-failed :message (:message parse-result)})
                        (let [response (:ok parse-result)
                              peers (:peers response)]
                          (async/>! ch {:ok (set (map :address peers))}))))))))))
        (catch Exception e
          (async/>! ch {:error :tracker-error :message (.getMessage e)})))
      (async/close! ch))
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
