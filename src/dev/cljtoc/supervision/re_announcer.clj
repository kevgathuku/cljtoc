(ns dev.cljtoc.supervision.re-announcer
  "Re-announces to the trackers when a download's swarm runs dry.

   A download whose peers all disconnected would otherwise fail at once,
   even though the trackers may well know peers the swarm has not asked
   yet. The re-announcer is a child of the download supervisor: it asks
   the trackers once more, and the download carries on with the peers
   they name. When they name none, the download fails.

   The re-announcer owns the announce. The coordinator asks it for peers
   and waits for the answer. A failed announce is a crash: the answer
   carries the failure and the request throws it, so the download
   supervisor restarts the subtree (ADR-0011)."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]))

(s/def ::peers (s/coll-of string?))

(def default-max-announces
  "How often the re-announcer asks the trackers before it gives up.

   The trackers may keep naming peers the swarm cannot reach; without a
   bound the download would ask forever and never give up either."
  3)

(s/def ::announce
  (s/fspec :args [] :ret map?))

(defn start
  "Start the re-announcer.

   `announce` is the announce seam: it returns {:ok peers} with the peer
   addresses the trackers know about, or {:error reason :message}.

   Returns {:request (fn [] peers) :stop (fn [])}: the request fn asks
   for a re-announce and waits for the answer, and the stop fn ends the
   re-announcer."
  [{:keys [announce max-announces]}]
  (let [max-announces (or max-announces default-max-announces)
        asked (atom 0)
        requests (async/chan 1)
        answers (async/chan 1)
        thread (async/thread
                 (loop []
                   (let [request (async/<!! requests)]
                     (if (nil? request)
                       nil
                       (do (async/>!! answers (if (< @asked max-announces)
                                                (do (swap! asked inc) (announce))
                                                {:ok #{}}))
                           (recur))))))]
    {:request (fn []
                (async/>!! requests :re-announce)
                (let [answer (async/<!! answers)]
                  (if (:error answer)
                    (throw (ex-info "The re-announce failed"
                                    {:type :cljtoc/re-announce-failed
                                     :reason (:reason answer)
                                     :message (:message answer)}))
                    (:ok answer))))
     :stop (fn []
             (async/close! requests)
             (async/<!! thread))}))

;; Excluded from stest/check: the re-announcer owns a channel and a
;; thread, and no generator can conjure an announce seam.
(s/fdef start
  :args (s/cat :options (s/keys :req-un [::announce]))
  :ret map?)
