(ns dev.cljtoc.coordination.peer-worker
  "Per-peer connection lifecycle management.

   Handles connecting to a peer, performing the BitTorrent handshake,
   sending Interested, and entering a read loop that forwards all
   received messages onto a shared events channel.

   Uses async/thread for blocking socket reads."
  (:require [clojure.core.async :as async]
            [dev.cljtoc.ports.network :as net]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.peer-state :as peer-state]))

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
  (println (str "[run-peer] Starting peer worker for address: " address))
  (async/thread
    (try
      ;; 1. Connect
      (let [connect-result (net/connect-peer network-port address)]
        (if (:error connect-result)
          (async/>!! events-ch {:type :peer-disconnected
                                :address address
                                :reason (:message connect-result)})

          (let [peer-data (:ok connect-result)]
            (try
              ;; 2. Send our handshake
              (let [handshake-bytes (:ok (peer/build-handshake info-hash our-peer-id))]
                (net/send-message network-port peer-data handshake-bytes)

                ;; 3. Read peer handshake
                (let [hs-result (net/receive-handshake network-port peer-data)]
                  (if (:error hs-result)
                    (do
                      (net/close-peer network-port peer-data)
                      (async/>!! events-ch {:type :peer-disconnected
                                            :address address
                                            :reason (:message hs-result)}))

                    (let [peer-hs (:ok hs-result)]
                      ;; 4. Verify info-hash matches
                      (if (not (java.util.Arrays/equals ^bytes info-hash
                                                        ^bytes (:info-hash peer-hs)))
                        (do
                          (net/close-peer network-port peer-data)
                          (async/>!! events-ch {:type :peer-disconnected
                                                :address address
                                                :reason "Info hash mismatch"}))

                        (do
                          ;; 5. Send Interested
                          (let [interested-bytes (:ok (peer/build-message (peer/->Interested)))]
                            (net/send-message network-port peer-data interested-bytes))

                          ;; 6. Notify coordinator of successful connection
                          (let [ps (-> (peer-state/initial-peer-state total-pieces)
                                       (peer-state/set-am-interested true))]
                            (async/>!! events-ch {:type :peer-connected
                                                  :address address
                                                  :peer-data peer-data
                                                  :peer-state ps}))

                          ;; 7. Enter read loop
                          (loop []
                            (let [msg-result (net/receive-message network-port peer-data)]
                              (if (or (nil? msg-result) (:error msg-result))
                                (do
                                  (net/close-peer network-port peer-data)
                                  (async/>!! events-ch {:type :peer-disconnected
                                                        :address address
                                                        :reason (or (:message msg-result) "connection closed")}))
                                (do
                                  (async/>!! events-ch {:type :peer-message
                                                        :address address
                                                        :message (:ok msg-result)})
                                  (recur)))))))))))
              (catch Exception e
                (net/close-peer network-port peer-data)
                (async/>!! events-ch {:type :peer-disconnected
                                      :address address
                                      :reason (.getMessage e)}))))))
      (catch Exception e
        (async/>!! events-ch {:type :peer-disconnected
                              :address address
                              :reason (.getMessage e)})))))
