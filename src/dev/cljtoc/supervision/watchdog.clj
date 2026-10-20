(ns dev.cljtoc.supervision.watchdog
  "Watches one download's progress and fires when it stalls.

   A download whose peers are connected but never deliver anything makes
   no progress: the coordinator waits for blocks that never arrive and
   the run hangs. The watchdog notices the stall and fires. Firing
   crashes the coordinator subtree, so the download supervisor restarts
   it — rest-for-one, bounded by its restart budget.

   The watchdog is a child of the download supervisor, not a peer-level
   concern: the peer that stalls is not necessarily the one at fault,
   and the recovery is a fresh subtree, not a fresh connection."
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]))

(def default-stall-deadline-ms
  "How long a download may stand still before the watchdog fires.

   A minute covers two optimistic-unchoke rotations: a fresh downloader
   has no pieces to offer, so the peers usually unchoke it only through
   optimistic unchoke, which rotates about every 30 seconds. A shorter
   deadline would fire on a healthy download whose peers have not
   unchoked it yet.

   The deadline has to fit inside the download supervisor's restart
   window too, or a download that never moves never runs out of budget:
   the window holds ceil(window/deadline) - 1 restarts, so the budget
   only gives up when the deadline is at most a quarter of the window.
   Both numbers live in one place (`restart/default-budget` and this
   value) and they are chosen together."
  60000)

(def default-poll-ms
  "How often the watchdog looks when the download stands still."
  1000)

(s/def ::marker (s/coll-of any? :count 2))

(s/def ::deadline-ms pos-int?)

;; The options the watchdog takes. The specs are declared before the fdef
;; that references them, because the spec registry resolves at load time.
(s/def ::state any?)

(s/def ::now (s/fspec :args [] :ret nat-int?))

(s/def ::fire fn?)

(defn progress-marker
  "The value that changes when the download makes progress.

   Three signals, each one moving on real progress:
   the blocks the download received, the pieces it verified, and the
   peers it is connected to.

   The verified pieces matter: the buffered blocks are not monotonic.
   Verifying a piece clears that peer's buffer, so a torrent whose
   pieces fit in one block would otherwise show `[0 n]` while it
   verifies steadily, and the watchdog would fire on a healthy
   download. A peer that connects also changes the marker, so the
   handshake is not counted as a stall: the watchdog waits for the
   blocks of a peer that is already there."
  [state]
  [(+ (reduce + 0 (map count (vals (:blocks-received state))))
      (count (get-in state [:download :piece-state :verified] #{})))
   (count (:active-peers state))])

(s/fdef progress-marker
  :args (s/cat :state map?)
  :ret ::marker)

(defn stalled?
  "True when the download's progress has not moved for longer than the
   deadline. Pure."
  [marker last-marker last-progress-ms now-ms deadline-ms]
  (and (= marker last-marker)
       (>= (- now-ms last-progress-ms) deadline-ms)))

(s/fdef stalled?
  :args (s/cat :marker ::marker
               :last-marker ::marker
               :last-progress-ms nat-int?
               :now-ms nat-int?
               :deadline-ms ::deadline-ms)
  :ret boolean?)

(defn- watch!
  "Watch the download until it stalls, the watchdog is stopped, or it
   fires."
  [state-atom now deadline-ms poll-ms fire stop-ch]
  (loop [last-marker nil
         last-progress-ms (long (now))]
    (let [[_ port] (async/alts!! [stop-ch (async/timeout poll-ms)])]
      (if (= port stop-ch)
        nil
        (let [now-ms (long (now))
              marker (progress-marker @state-atom)
              last-progress-ms (if (= marker last-marker) last-progress-ms now-ms)]
          (if (stalled? marker last-marker last-progress-ms now-ms deadline-ms)
            (do (fire) nil)
            (recur marker last-progress-ms)))))))

(defn start
  "Start the watchdog for one download's subtree.

   `state` is the atom the coordinator's state lands in: the download
   supervisor publishes it as the download moves. `now` is the clock the
   deadline is measured on. `deadline-ms` is the stall that fires the
   watchdog, `poll-ms` is how often it looks. `fire` is called when the
   watchdog fires.

   Returns the stop fn that ends the watchdog."
  [{:keys [state now deadline-ms poll-ms fire]}]
  (let [stop-ch (async/chan 1)
        thread (async/thread (watch! state now deadline-ms poll-ms fire stop-ch))]
    (fn []
      (async/offer! stop-ch :stop)
      (async/<!! thread))))

;; Excluded from stest/check: the watchdog owns a channel and a thread,
;; and no generator can conjure a clock or an atom to watch.
(s/fdef start
  :args (s/cat :options (s/keys :req-un [::state ::now ::fire]))
  :ret (s/fspec :args [] :ret any?))
