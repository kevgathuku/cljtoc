(ns dev.cljtoc.orchestration.manager
  "Download manager - coordinates all downloads and holds configuration.
   
   This namespace provides the DownloadManager record that holds
   all dependencies (ports) and manages multiple concurrent downloads."
  (:require [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.orchestration.download :as download])
  (:require [clojure.spec.alpha :as s]))

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

(s/def ::max-peers nat-int?)
(s/def ::min-peers nat-int?)
(s/def ::request-queue-size nat-int?)
(s/def ::piece-timeout-ms nat-int?)
(s/def ::tracker-announce-interval-ms nat-int?)
(s/def ::output-dir string?)

(s/def ::config
  (s/keys :opt-un [::max-peers
                   ::min-peers
                   ::request-queue-size
                   ::piece-timeout-ms
                   ::tracker-announce-interval-ms
                   ::output-dir]))

(s/def ::network-port any?)
(s/def ::disk-port any?)
(s/def ::time-port any?)
(s/def ::downloads map?)

(s/def ::download-manager
  (s/keys :req-un [::network-port ::disk-port ::time-port ::downloads ::config]))

(s/fdef manager
  :args (s/cat :network-port any?
               :disk-port any?
               :time-port any?
               :config (s/? ::config))
  :ret ::download-manager)

(s/fdef add-download
  :args (s/cat :manager ::download-manager :download map?)
  :ret ::download-manager)

(s/fdef get-download
  :args (s/cat :manager ::download-manager :id any?)
  :ret (s/or :download map? :nil nil?))

(s/fdef remove-download
  :args (s/cat :manager ::download-manager :id any?)
  :ret ::download-manager)

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
