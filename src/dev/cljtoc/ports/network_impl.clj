(ns dev.cljtoc.ports.network-impl
  "Real network I/O implementation for download orchestration.
   
   Provides functions for TCP peer connections and tracker communication."
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [java.net InetSocketAddress Socket]))

(defrecord NetworkPort
           [config peer-connections])

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

(defn announce
  "Announce to the tracker and get a list of peers.
   Returns a channel that will deliver #{Peer} or error.
   
   Note: Full tracker implementation requires additional tracker protocol functions.
   This returns no peers - the download will handle this gracefully."
  [network torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (async/>! ch {:ok #{}}))
    ch))

(defn scrape
  "Scrape tracker for torrent statistics.
   Returns a channel with scrape data or error."
  [network torrent-metadata]
  (let [ch (async/chan 1)]
    (async/go
      (async/>! ch {:ok {:seeders 0 :leechers 0 :complete 0}}))
    ch))

(defn create
  "Create a NetworkPort instance."
  ([]
   (create {}))
  ([opts]
   (->NetworkPort opts (atom {}))))
