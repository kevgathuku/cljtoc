(ns dev.cljtoc.coordination.peer-worker
  "Per-peer connection lifecycle management.

   Handles connecting to a peer, performing the BitTorrent handshake,
   sending Interested, and entering a read loop that forwards all
   received messages onto a shared events channel.

   Uses async/thread for blocking socket reads."
  (:require [clojure.core.async :as async]
            [dev.cljtoc.coordination.peer-connection :as peer-connection]
            [dev.cljtoc.ports.network :as net]))

(defn run-peer
  "Connect to peer, perform handshake, then enter read loop.
   All events are put onto events-ch for the coordinator.

   Parameters:
     network-port - NetworkPort instance
     info-hash    - 20-byte info hash for handshake verification
     our-peer-id  - 20-byte peer ID for our handshake
     address      - \"host:port\" string
     total-pieces - number of pieces in the torrent
     events-ch    - shared channel for coordinator events

   Returns a channel that closes when the peer worker exits."
  [network-port info-hash our-peer-id address total-pieces events-ch]
  (net/log! network-port (str "[run-peer] Starting peer worker for address: " address))
  (async/thread
    (try
      ;; Connect owns dial, handshake, verify, and Interested in one
      ;; seam (peer-connection/connect); the worker only carries events.
      (let [connect-result (peer-connection/connect
                            network-port info-hash our-peer-id address total-pieces)]
        (if (:error connect-result)
          (async/>!! events-ch {:type :peer-disconnected
                                :address address
                                :reason (:message connect-result)})
          (let [{:keys [peer-data peer-state]} (:ok connect-result)]
            (try
              ;; Notify coordinator of successful connection
              (async/>!! events-ch {:type :peer-connected
                                    :address address
                                    :peer-data peer-data
                                    :peer-state peer-state})
              ;; Enter read loop
              (loop []
                (let [msg-result (net/receive-message network-port peer-data)]
                  (if (or (nil? msg-result) (:error msg-result))
                    (do
                      (peer-connection/close network-port peer-data)
                      (async/>!! events-ch {:type :peer-disconnected
                                            :address address
                                            :reason (or (:message msg-result) "connection closed")}))
                    (do
                      (async/>!! events-ch {:type :peer-message
                                            :address address
                                            :message (:ok msg-result)})
                      (recur)))))
              (catch Exception e
                (peer-connection/close network-port peer-data)
                (async/>!! events-ch {:type :peer-disconnected
                                      :address address
                                      :reason (.getMessage e)}))))))
      (catch Exception e
        (async/>!! events-ch {:type :peer-disconnected
                              :address address
                              :reason (.getMessage e)})))))
