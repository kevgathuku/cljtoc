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
            closed-peers
            responses]

  network/INetworkPort
  (connect-peer [_ address]
    (let [ch (async/chan 1)
          peer-id (str address "/" (UUID/randomUUID))]
      (async/go
        (async/>! ch {:ok {:id peer-id
                           :address address
                           :bitfield (:default-bitfield config)}}))
      ch))

  (send-message [_ peer message]
    (let [ch (async/chan 1)]
      (async/go
        (let [response (get-in @responses [(:id peer) (:type message)] {:ok :mock-response})]
          (async/>! ch response)))
      ch))

  (receive-message [_ _]
    (let [ch (async/chan 1)]
      (async/go
        (let [queued (when-let [receive-queue (:receive-responses config)]
                       (let [[queued-responses _] (swap-vals! receive-queue rest)]
                         (first queued-responses)))]
          (async/>! ch (or queued {:ok {:type :keep-alive}}))))
      ch))

  (receive-handshake [_ peer]
    (let [ch (async/chan 1)]
      (async/go
        (let [response (:handshake-response config)]
          (async/>! ch (cond
                         (fn? response) (response peer)
                         (some? response) response
                         :else {:ok {:info-hash (byte-array 20)
                                     :peer-id (byte-array 20)}}))))
      ch))

  (close-peer [_ peer]
    (swap! closed-peers conj peer)
    nil)

  network/ITrackerPort
  (announce [_ _]
    (let [ch (async/chan 1)]
      (async/go
        (if-let [announce-error (:announce-error config)]
          (async/>! ch announce-error)
          (async/>! ch {:ok (get config :mock-peers ["127.0.0.1:6881" "127.0.0.1:6882"])})))
      ch)))

(defn create
  "Create a mock network port for testing.
   
   Options:
   - :default-bitfield - set of piece indices this mock peer has (default: #{0 1 2 3 4})
   - :mock-peers - vector of peer addresses to return on announce
   - :announce-error - error map to return from announce instead of peers
   - :handshake-response - map or (fn [peer]) returning {:ok handshake}
     or {:error ...} for receive-handshake
   - :receive-responses - atom holding a seq of {:ok ...} / {:error ...}
     returned one per receive-message call; falls back to keep-alive
     once the queue is empty"
  ([]
   (create {}))
  ([config]
   (let [state (atom {:responses {}})
         closed (atom #{})]
     (->MockNetworkPort config #{} state closed state))))

(defn add-peer-response [mock-network peer-id message-type response]
  (swap! (:responses mock-network) assoc-in [peer-id message-type] response))

(defn add-connected-peer [mock-network peer]
  (swap! (:connected-peers mock-network) conj peer))

(defn closed-peers
  "Peer-data maps the mock was asked to close."
  [mock-network]
  @(:closed-peers mock-network))
