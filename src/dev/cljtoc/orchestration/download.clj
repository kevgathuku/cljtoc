(ns dev.cljtoc.orchestration.download
  "Download orchestration - coordinates torrent downloads.
  
   This namespace manages the end-to-end download process:
   - Parsing .torrent files
   - Connecting to trackers to get peers
   - Managing peer connections
   - Selecting pieces using rarest-first
   - Downloading and verifying pieces
   - Writing verified pieces to disk
   
   All I/O is performed through injected port protocols, making this
   code testable with mock implementations."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time])
  (:import [java.util UUID]))

(defrecord Download
  [id
   torrent
   piece-state
   peers
   state
   output-dir
   stats
   error])

(defrecord Peer
  [id
   address
   port
   bitfield
   am-choking
   am-interested
   peer-choking
   peer-interested
   downloaded
   uploaded])

(defrecord DownloadStats
  [started-at
   completed-at
   bytes-downloaded
   bytes-uploaded
   last-update])

(defrecord ErrorInfo
  [reason
   message
   failed-piece])

(def valid-states #{:idle :starting :downloading :paused :completed :failed})

(defn- download-error
  [reason message & [failed-piece]]
  {:error reason :message message :failed-piece failed-piece})

(defn- parse-torrent [disk-port torrent-path]
  (let [ch (async/chan 1)]
    (async/go
      (let [result (disk/read-torrent-file disk-port torrent-path)]
        (if (:error result)
          (async/>! ch (download-error :invalid-torrent (:message result)))
          (async/>! ch {:ok (:ok result)}))))
    ch))

(defn- announce-to-tracker [network-port torrent]
  (let [ch (async/chan 1)]
    (async/go
      (let [result (async/<! (network/announce network-port torrent))]
        (if (:error result)
          (async/>! ch (download-error :tracker-error (:message result)))
          (async/>! ch {:ok (:ok result)}))))
    ch))

(defn- select-next-piece [piece-state peers]
  (let [peer-availables (map :bitfield peers)]
    (pieces/select-piece piece-state (first peer-availables) peer-availables)))

(defn- request-pieces [manager download peer]
  (let [piece-state (:piece-state download)
        result (select-next-piece piece-state (:peers download))]
    (if-let [piece-idx (:ok result)]
      (let [marked (pieces/mark-in-flight piece-state piece-idx)]
        (if (:error marked)
          {:ok nil}
          {:ok {:piece-index piece-idx
                :piece-state (:ok marked)}}))
      {:ok nil})))

(defn- verify-and-write-piece [disk-port piece-index bytes piece-hash]
  (let [verify-result (pieces/verify-piece piece-index bytes piece-hash)]
    (if (:error verify-result)
      {:error (:error verify-result)}
      (let [write-result (disk/write-piece disk-port piece-index bytes)]
        (if (:error write-result)
          {:error (:error write-result)}
          {:ok piece-index})))))

(defn initial-stats []
  (let [now (System/currentTimeMillis)]
    (->DownloadStats now nil 0 0 now)))

(defn update-stats-bytes [stats bytes-received]
  (let [now (System/currentTimeMillis)
        prev-bytes (:bytes-downloaded stats)
        prev-time (:last-update stats)
        elapsed-seconds (max 1 (/ (- now prev-time) 1000.0))
        new-total (+ prev-bytes bytes-received)
        rate (long (/ bytes-received elapsed-seconds))]
    (-> stats
        (assoc :bytes-downloaded new-total)
        (assoc :last-update now)
        (assoc :rate rate))))

(defn calculate-rate [stats]
  (let [now (System/currentTimeMillis)
        elapsed-seconds (/ (- now (:last-update stats)) 1000.0)
        bytes-downloaded (:bytes-downloaded stats)]
    (if (and (> elapsed-seconds 0) (> bytes-downloaded 0))
      (long (/ bytes-downloaded elapsed-seconds))
      0)))

(defn initial-download [torrent output-dir]
  (let [total-pieces (count (torrent/parse-pieces (:pieces torrent)))
        piece-state (pieces/initial-piece-state total-pieces)]
    (->Download (UUID/randomUUID)
                torrent
                piece-state
                #{}
                :starting
                output-dir
                (initial-stats)
                nil)))

(defn start-download [manager torrent-path output-dir]
  (let [{:keys [network-port disk-port]} manager
        parse-result (async/<!! (parse-torrent disk-port torrent-path))]
    (if (:error parse-result)
      parse-result
      (let [torrent (:ok parse-result)
            download (initial-download torrent output-dir)
            announce-result (async/<!! (announce-to-tracker network-port torrent))]
        (if (:error announce-result)
          (assoc download :state :failed :error (:error announce-result))
          (let [peers (:ok announce-result)]
            (assoc download
                   :state :downloading
                   :peers (set (map (fn [addr]
                                       (->Peer addr addr 6881 #{} true false true false 0 0))
                                     peers)))))))))

(defn progress [download]
  (let [piece-state (:piece-state download)
        total (pieces/verified-count piece-state)
        total-pieces (:total-pieces piece-state)
        percent (if (zero? total-pieces)
                  0.0
                  (* 100.0 (/ total total-pieces)))
        stats (:stats download)
        bytes-downloaded (:bytes-downloaded stats)
        rate (calculate-rate stats)]
    {:percent percent
     :pieces-complete total
     :pieces-total total-pieces
     :bytes-downloaded bytes-downloaded
     :rate-bytes-per-sec rate
     :peers-connected (count (:peers download))
     :state (:state download)}))

(defn pause-download [download]
  (if (= :downloading (:state download))
    {:ok {:state :paused
          :bytes-downloaded (:bytes-downloaded (:stats download))
          :pieces-complete (pieces/verified-count (:piece-state download))}}
    {:error :not-running :message "Download is not running"}))

(defn resume-download [download]
  (if (= :paused (:state download))
    {:ok {:state :downloading
          :peers-connected (count (:peers download))}}
    {:error :not-paused :message "Download is not paused"}))

(defn stop-download [download]
  (assoc download :state :idle :peers #{}))
