(ns dev.cljtoc.ports.network
  "Network port protocol for peer communication effects.
  
   This protocol defines the contract for all network I/O operations
   needed by the download orchestration layer. Implementations can be
   swapped for testing (test doubles) or different network backends.

   Every method returns its result directly, by return value, and every
   method blocks until it has one. Nothing here runs work on a pool, so a
   caller that wants two calls in flight has to put them on threads of its
   own -- that choice belongs above this seam, not inside it."
  (:require [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]))

(defprotocol INetworkPort
  "Abstraction for network operations needed by download orchestration."

  (connect-peer [this address]
    "Open TCP connection to a peer at the given address.
     Returns {:ok peer-data} or {:error reason :message msg}.")

  (send-message [this peer message]
    "Send a peer wire message to the connected peer.
     Returns {:ok :sent} or {:error reason :message msg}.")

  (receive-message [this peer]
    "Receive the next message from a peer.
     Returns {:ok peer-message} or {:error reason :message msg}.")

  (receive-handshake [this peer]
    "Read a 68-byte peer handshake from the connection.
     Returns {:ok peer-handshake} or {:error reason :message msg}.")

  (close-peer [this peer]
    "Close the connection to a peer gracefully.
     Returns nil."))

(defprotocol ITrackerPort
  "Abstraction for tracker communication operations."

  (announce [this torrent-metadata progress]
    "Announce to the tracker and get a list of peers.
     progress is {:downloaded bytes-on-disk :left bytes-remaining}: the
     tracker's leecher accounting depends on it, so a resume must report
     verified bytes rather than zero.
     Returns {:ok #{peer-address}} or {:error reason :message msg}."))
