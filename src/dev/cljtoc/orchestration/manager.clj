(ns dev.cljtoc.orchestration.manager
  "Download manager - coordinates all downloads and holds configuration.
  
   This namespace provides the DownloadManager record that holds
   all dependencies (ports) and manages multiple concurrent downloads."
  (:require [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.orchestration.download :as download]))

(def default-config
  {:max-peers 50
   :min-peers 5
   :request-queue-size 16
   :piece-timeout-ms 30000
   :tracker-announce-interval-ms 1800000
   :output-dir "./downloads"})

(defrecord DownloadManager
  [network-port
   disk-port
   time-port
   downloads
   config])

(defn manager
  "Create a DownloadManager with the given port implementations and config.
   
   Parameters:
   - network-port: implementation of INetworkPort
   - disk-port: implementation of IDiskPort  
   - time-port: implementation of ITimePort
   - config: optional map of configuration overrides
   
   Returns a DownloadManager record."
  ([network-port disk-port time-port]
   (manager network-port disk-port time-port {}))
  ([network-port disk-port time-port config]
   (->DownloadManager network-port
                      disk-port
                      time-port
                      {}
                      (merge default-config config))))

(defn add-download [manager download]
  (update manager :downloads assoc (:id download) download))

(defn get-download [manager id]
  (get (:downloads manager) id))

(defn remove-download [manager id]
  (update manager :downloads dissoc id))
