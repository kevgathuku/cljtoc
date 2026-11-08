(ns live-connect
  "Manual end-to-end smoke probe: parse a torrent file, announce to its
   tracker, shake hands with peers until one verifies, close. Hits the real internet,
   takes seconds, and results vary with tracker and peer availability —
   call it from the REPL, never from src/ or tests.

   REPL use:
     (require 'live-connect :reload)
     (live-connect/probe-connect! \"tmp/debian-13.3.0-amd64-netinst.iso.torrent\")"
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.coordination.peer-connection :as peer-connection]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]))

(defn- total-length
  "Total payload bytes: single-file info carries :length, multi-file
   info sums its :files entries."
  [torrent-map]
  (let [info (:info torrent-map)]
    (or (:length info)
        (reduce + 0 (map :length (:files info))))))

(defn- total-pieces
  "Piece count for connect's initial posture: bytes over piece-length,
   rounded up, at least one so the fdef's pos-int holds."
  [torrent-map]
  (max 1 (long (Math/ceil (/ (double (total-length torrent-map))
                             (get-in torrent-map [:info :piece-length]))))))

(defn- try-peers
  "Connect to each address in order until one handshake verifies.
   Returns [result attempts]: result is connect's envelope, attempts
   is how many peers were tried. Every attempt closes its socket."
  [net info-hash our-peer-id addresses total-pieces-count]
  (loop [remaining addresses
         attempts 0
         last-error nil]
    (if (empty? remaining)
      [(or last-error {:error :no-peers :message "Tracker named no peers"})
       attempts]
      (let [address (first remaining)
            conn (peer-connection/connect net info-hash our-peer-id
                                          address total-pieces-count)
            ;; recur cannot cross try, so the try computes an outcome
            ;; value and the recur below it acts on it.
            outcome (try
                      (if (:error conn)
                        ::next
                        {:ok {:address address
                              :peer-count (count addresses)
                              :attempts (inc attempts)
                              :interested (-> conn :ok :peer-state :am-interested)}})
                      (finally
                        (when-let [peer-data (get-in conn [:ok :peer-data])]
                          (peer-connection/close net peer-data))))]
        (if (= ::next outcome)
          (recur (rest remaining) (inc attempts) conn)
          [outcome (inc attempts)])))))

(defn probe-connect!
  "Announce torrent-path's torrent and shake hands with peers until
   one verifies, trying up to 5 in tracker order. Returns {:ok {:address _
   :peer-count _ :attempts _ :interested _}} when
   the whole path works, or the first {:error ...} envelope when a step
   fails (an error is data: unreachable trackers and refusing peers are
   normal on the public internet, not probe bugs). The peer closes
   before returning, success or failure."
  [torrent-path]
  (try
    (let [raw (java.nio.file.Files/readAllBytes (.toPath (io/file torrent-path)))
          parsed (torrent/parse-torrent raw)]
      (if (:error parsed)
        parsed
        (let [torrent-map (:ok parsed)
              net (network-impl/create)
              our-peer-id (network/peer-id net)
              announced (network/announce-to-url
                         net (:announce torrent-map)
                         {:info-hash (:info-hash torrent-map)
                          :peer-id our-peer-id
                          :port 6881
                          :uploaded 0
                          :downloaded 0
                          :left (total-length torrent-map)})]
          (if (:error announced)
            announced
            (let [peers (vec (take 5 (:ok announced)))
                  [result _attempts] (try-peers net (:info-hash torrent-map)
                                                our-peer-id peers
                                                (total-pieces torrent-map))]
              result)))))
    (catch Exception probe-error
      {:error :probe-failed :message (.getMessage probe-error)})))

(s/fdef probe-connect!
  :args (s/cat :torrent-path string?)
  :ret map?)
