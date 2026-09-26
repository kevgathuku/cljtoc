(ns dev.cljtoc.test-doubles.network
  "Mock network port for testing download orchestration.
   
   Provides predictable responses for testing without actual network I/O."
  (:require [dev.cljtoc.ports.network :as network]
            [clojure.core.async :as async])
  (:import [java.util UUID]))

(defrecord MockNetworkPort
  [config
   peers
   connected-peers
   responses]
  
  network/INetworkPort
  (connect-peer [this address]
    (let [ch (async/chan 1)
          peer-id (str address "/" (UUID/randomUUID))]
      (async/go
        (async/>! ch {:ok {:id peer-id
                           :address address
                           :bitfield (:default-bitfield config)}}))
      ch))
  
  (send-message [this peer message]
    (let [ch (async/chan 1)]
      (async/go
        (let [response (get-in @responses [(:id peer) (:type message)] {:ok :mock-response})]
          (async/>! ch response)))
      ch))
  
  (receive-message [this peer]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch {:ok {:type :keep-alive}}))
      ch))
  
  (close-peer [this peer]
    nil)
  
  (peer-loop [this peer message-handler]
    (fn []))
  
  network/ITrackerPort
  (announce [this torrent-metadata]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [announce-error (:announce-error config)]
          (async/>! ch announce-error)
          (async/>! ch {:ok (get config :mock-peers ["127.0.0.1:6881" "127.0.0.1:6882"])})))
      ch))
  
  (scrape [this torrent-metadata]
    (let [ch (async/chan 1)]
      (async/go
        (async/>! ch {:ok {:seeders 10 :leechers 5}}))
      ch)))

(defn create
  "Create a mock network port for testing.
   
   Options:
   - :default-bitfield - set of piece indices this mock peer has (default: #{0 1 2 3 4})
   - :mock-peers - vector of peer addresses to return on announce
   - :announce-error - error map to return from announce instead of peers"
  ([]
   (create {}))
  ([config]
   (let [state (atom {:responses {}})]
     (->MockNetworkPort config #{} state state))))

(defn add-peer-response [mock-network peer-id message-type response]
  (swap! (:responses mock-network) assoc-in [peer-id message-type] response))

(defn add-connected-peer [mock-network peer]
  (swap! (:connected-peers mock-network) conj peer))
