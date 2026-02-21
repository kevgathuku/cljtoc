(ns dev.cljtoc.ports.disk
  "Disk I/O port protocol for file operations.
  
   This protocol defines the contract for all disk I/O operations
   needed by the download orchestration layer: reading .torrent files,
   writing piece data, and persisting download state."
  (:require [dev.cljtoc.domain.torrent :as torrent]))

(defprotocol IDiskPort
  "Abstraction for disk operations needed by download orchestration."
  
  (read-torrent-file [this path]
    "Read and parse a .torrent file from disk.
     Returns a channel that will deliver TorrentMetadata or error.
     
     Side effects: reads file from filesystem")
  
  (read-piece [this piece-index]
    "Read cached piece data from disk.
     Returns a channel that will deliver bytes or nil if not cached.
     
     Side effects: reads from piece cache")
  
  (write-piece [this piece-index bytes]
    "Write verified piece data to disk in the correct file layout.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: writes to filesystem")
  
  (ensure-directory [this path]
    "Ensure a directory exists, creating it if necessary.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: creates directories")
  
  (save-state [this download]
    "Persist download state to disk for pause/resume support.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: writes state file")
  
  (load-state [this id]
    "Load persisted download state from disk.
     Returns a channel that will deliver Download or nil if not found.
     
     Side effects: reads from filesystem")
  
  (delete-state [this id]
    "Delete persisted download state.
     Returns a channel that will deliver :ok or {:error reason}.
     
     Side effects: deletes file"))
