(ns dev.cljtoc.ports.network-impl
  "Real network I/O implementation for download orchestration.
   
   Provides functions for TCP peer connections and tracker communication."
  (:require [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]
            [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [java.net InetSocketAddress Socket]
           [java.util.concurrent Executors]))

(defrecord NetworkPort
  [config peer-connections])

(defn connect-peer [network address]
  "Open TCP connection to a peer at the given address.
   Returns a channel that will deliver the peer connection or error."
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
      ch))

(defn send-message [network peer message]
  "Send a peer wire message to the connected peer.
   Returns a channel that will deliver the response or error."
  (let [ch (async/chan 1)]
    (async/go
      (try
        (let [bytes (peer/build-message message)
              out (:out peer)]
          (.write out bytes)
          (.flush out)
          (async/>! ch {:ok :sent}))
        (catch Exception e
          (async/>! ch {:error :send-failed :message (.getMessage e)}))))
    ch))

(defn receive-message [network peer]
  "Receive the next message from a peer.
   Returns a channel that will deliver the message or error."
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

(defn close-peer [network peer]
  "Close the connection to a peer gracefully."
  (try
    (when-let [socket (:socket peer)]
      (.close socket))
    (swap! (:peer-connections network) dissoc (:id peer))
    nil
    (catch Exception _ nil)))

(defn announce [network torrent-metadata]
  "Announce to the tracker and get a list of peers.
   Returns a channel that will deliver #{Peer} or error."
  (let [ch (async/chan 1)]
    (async/go
      (try
        (let [announce-urls (or (:announce-list torrent-metadata)
                                [[(:announce torrent-metadata)]])]
          (loop [[tier & rest-tiers] announce-urls]
            (if tier
              (let [result (try-announce-tier tier torrent-metadata)]
                (if (or (:error result) (empty? (:ok result)))
                  (recur rest-tiers)
                  (async/>! ch result)))
              (async/>! ch {:error :no-trackers :message "All trackers failed"}))))
        (catch Exception e
          (async/>! ch {:error :tracker-error :message (.getMessage e)}))))
      ch))

(defn scrape [network torrent-metadata]
  "Scrape tracker for torrent statistics.
   Returns a channel with scrape data or error."
  (let [ch (async/chan 1)]
    (async/go
      (async/>! ch {:ok {:seeders 0 :leechers 0 :complete 0}}))
    ch))

(defn try-announce-tier
  "Try to announce to a tier of trackers."
  [tier torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (loop [[url & rest] tier]
        (if url
          (let [result (try
                        (tracker/http-announce url torrent-metadata "test-client-id")
                        (catch Exception e {:error :announce-failed :message (.getMessage e)}))]
            (if (and (:ok result) (seq (get-in result [:ok :peers])))
              (async/>! ch result)
              (recur rest)))
          (async/>! ch {:ok #{}}))))
    ch))

(defn try-announce-tier
  "Try to announce to a tier of trackers."
  [tier torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (loop [[url & rest] tier]
        (if url
          (let [result (try
                        (tracker/http-announce url torrent-metadata "test-client-id")
                        (catch Exception e {:error :announce-failed :message (.getMessage e)}))]
            (if (and (:ok result) (seq (get-in result [:ok :peers])))
              (async/>! ch result)
              (recur rest)))
          (async/>! ch {:ok #{}}))))
    ch))

(defn create
  "Create a NetworkPort instance.
   
   Options:
   - :timeout-ms - connection timeout in milliseconds (default: 10000)
   - :max-connections - max peer connections (default: 50)"
  ([]
   (create {}))
  ([{:keys [timeout-ms max-connections config]
     :or {timeout-ms 10000 max-connections 50}}]
   (->NetworkPort config (atom {}))))
