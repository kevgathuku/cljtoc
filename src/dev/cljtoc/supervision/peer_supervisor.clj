(ns dev.cljtoc.supervision.peer-supervisor
  "One-for-one supervision of a download's peer workers.

   The peer supervisor owns the event channel its workers write to and
   watches every worker it spawned. A worker that crashes is restarted
   on its own, while the download's other workers keep running: a
   flaky peer never restarts the coordinator. A worker that exits
   cleanly is not restarted — the peer is gone and the coordinator
   already heard about it.

   The restart budget is per connection. The crashes of one address
   inside the window bound how often that address may be restarted;
   once the budget is spent the address is dropped, so a flapping peer
   cannot spin the supervisor forever. The window slides on the
   caller's clock.

   Per ADR-0011 the supervisor owns the event channel: the workers get
   the write end, the coordinator gets the read end, and the supervisor
   closes the channel once no worker is left."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [dev.cljtoc.domain.backoff :as backoff]
            [dev.cljtoc.supervision.restart :as restart]))

(s/def ::exit #{:clean :crash})

(s/def ::outcome (s/keys :req-un [::exit]))

(s/def ::restart-time nat-int?)

(s/def ::budget (s/keys :req-un [::limit ::window-ms]))

(s/def ::address string?)

(s/def ::spawn
  (s/fspec :args (s/cat :events-ch any? :address ::address) :ret any?))

(s/def ::addresses (s/coll-of ::address))

(def default-connection-budget
  "How often one peer connection may be restarted inside the window. A
   peer that crashes three times inside a minute is failing for good,
   not flapping: it gets dropped instead of restarted forever."
  {:limit 3
   :window-ms 60000})

(def default-restart-delay
  "The wait before a crashed peer is restarted: an exponential backoff
   from the shared pure module, so a peer that keeps crashing does not
   spin the supervisor. No jitter yet — the manager carries no
   randomness port, and a deterministic delay already breaks the loop."
  (fn [attempt] (backoff/exponential-backoff attempt 200 3200)))

(defn decide
  "What the supervisor does about a worker that just finished.

   `outcome` is the worker's exit outcome: {:exit :clean} for a peer
   that disconnected, {:exit :crash :error e} for a worker that hit an
   unexpected failure. `restart-times` are the crashes this address
   already charged inside the window.

   Returns {:action :none} for a clean exit — the peer is gone and
   nothing is restarted — {:action :restart :attempt n :window times}
   while the budget allows another restart, and {:action :drop} once
   the budget is spent. Pure."
  [outcome restart-times {:keys [limit window-ms]} now-ms]
  (if (= :clean (:exit outcome))
    {:action :none}
    (let [window (restart/prune restart-times window-ms now-ms)]
      (if (restart/restart-allowed? window limit)
        {:action :restart :attempt (count window) :window window}
        {:action :drop :window window}))))

(s/fdef decide
  :args (s/cat :outcome ::outcome
               :restart-times (s/coll-of ::restart-time)
               :budget ::budget
               :now-ms nat-int?)
  :ret map?)

(defn- crash-reason
  "The reason a crashed worker carries to the coordinator."
  [outcome]
  (let [error (:error outcome)]
    (or (when (instance? Throwable error) (.getMessage ^Throwable error))
        "peer worker crashed")))

(defn- spawn-worker
  "Spawn a worker for an address and return the channel it finishes on.

   Returns nil when the spawn failed: a spawn that throws must not take
   the supervisor down, or the event channel would never close and the
   coordinator would wait forever."
  [spawn events-ch address]
  (try
    (spawn events-ch address)
    (catch Exception _ nil)))

(defn- supervise-workers!
  "Watch every worker and act on the ones that finished.

   `live` maps a worker's finishing channel to its address. A crashed
   worker is restarted while its budget holds, and the coordinator is
   told the peer is gone with :redial set — the address stays dialed
   because the restart is coming. A clean exit resolves the dial for
   good: the peer is not coming back. A crashed worker whose budget is
   spent is dropped, which resolves the dial too."
  [events-ch spawn live restart-times budget delay-fn stop-ch]
  (loop [live live
         restart-times restart-times]
    (if (empty? live)
      (async/close! events-ch)
      (let [ports (conj (mapv (fn [[done _]] done) live) stop-ch)
            [outcome port] (async/alts!! ports)
            address (get-in live [port :address])
            remaining (dissoc live port)]
        (if (= port stop-ch)
          (async/close! events-ch)
          (let [now-ms (long ((:now budget)))
                decision (decide (or outcome {:exit :crash})
                                 (get restart-times address [])
                                 budget
                                 now-ms)]
            (case (:action decision)
              :none
              (recur remaining (assoc restart-times address []))

              :restart
              (do
                (async/>!! events-ch {:type :peer-disconnected
                                      :address address
                                      :reason (crash-reason outcome)
                                      :redial true})
                (let [delay (delay-fn (:attempt decision))]
                  (when (pos? delay)
                    (Thread/sleep delay)))
                (let [restarted (spawn-worker spawn events-ch address)]
                  (if (nil? restarted)
                    (do
                      (async/>!! events-ch {:type :peer-disconnected
                                            :address address
                                            :reason "peer worker could not be restarted"})
                      (recur remaining restart-times))
                    (recur (assoc remaining restarted {:address address})
                           (assoc restart-times address (conj (:window decision) now-ms))))))

              :drop
              (do
                (async/>!! events-ch {:type :peer-disconnected
                                      :address address
                                      :reason "peer restart budget exhausted"})
                (recur remaining restart-times)))))))))

(defn start
  "Start the peer supervisor for one download's addresses.

   `spawn` is the worker seam: it takes the event channel and an
   address and returns the channel the worker finishes on. The
   supervisor hands every worker the channel it owns, so a worker never
   has to know who owns it.

   `addresses` are the peers to dial. `budget` bounds how often one
   address may be restarted and carries the clock the window slides on
   (:limit, :window-ms, :now). `delay-fn` is the wait before a restart.

   Returns {:events-ch ch :stop (fn [])}: the channel the coordinator
   reads, and the stop fn that ends the supervisor."
  [{:keys [spawn addresses budget delay-fn]}]
  (let [budget (merge {:now (fn [] 0)} default-connection-budget budget)
        events-ch (async/chan 256)
        stop-ch (async/chan 1)
        live (into {}
                   (keep (fn [address]
                           (if-let [done (spawn-worker spawn events-ch address)]
                             [done {:address address}]
                             ;; A worker that never started leaves no
                             ;; channel to watch, so the coordinator is told
                             ;; the peer is gone: its dial has to resolve, or
                             ;; the download would wait for it forever.
                             (do (async/>!! events-ch {:type :peer-disconnected
                                                       :address address
                                                       :reason "peer worker could not be started"})
                                 nil))))
                   addresses)
        monitor (async/thread (supervise-workers! events-ch
                                                  spawn
                                                  live
                                                  {}
                                                  budget
                                                  (or delay-fn default-restart-delay)
                                                  stop-ch))]
    {:events-ch events-ch
     ;; offer! never blocks: stopping a supervisor twice must not hang
     ;; the caller on a channel nobody reads any more.
     :stop (fn []
             (async/offer! stop-ch :stop)
             (async/<!! monitor))}))

;; Excluded from stest/check: the supervisor spawns threads and channels,
;; and no generator can conjure a worker seam or a channel to watch.
(s/fdef start
  :args (s/cat :options (s/keys :req-un [::spawn ::addresses]))
  :ret map?)
