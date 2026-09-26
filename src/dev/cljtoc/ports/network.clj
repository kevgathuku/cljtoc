(ns dev.cljtoc.ports.network
  "Network port protocol for peer communication effects.
  
   This protocol defines the contract for all network I/O operations
   needed by the download orchestration layer. Implementations can be
   swapped for testing (test doubles) or different network backends."
  (:require [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]))

(defprotocol INetworkPort
  "Abstraction for network operations needed by download orchestration."
  
  (connect-peer [this address]
    "Open TCP connection to a peer at the given address.
     Returns a channel that will deliver the peer connection or error.")
  
  (send-message [this peer message]
    "Send a peer wire message to the connected peer.
     Returns a channel that will deliver the response or error.")
  
  (receive-message [this peer]
    "Receive the next message from a peer.
     Returns a channel that will deliver the message or error.")

  (receive-handshake [this peer]
    "Read a 68-byte peer handshake from the connection.
     Returns a channel that will deliver {:ok PeerHandshake} or {:error ...}.")

  (close-peer [this peer]
    "Close the connection to a peer gracefully.
     Returns nil."))

(defprotocol ITrackerPort
  "Abstraction for tracker communication operations."
  
  (announce [this torrent-metadata]
    "Announce to the tracker and get a list of peers.
     Returns a channel that will deliver #{Peer} or error."))
