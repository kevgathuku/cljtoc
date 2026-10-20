(ns dev.cljtoc.test-utils
  "Shared test utilities and helper functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [expound.alpha :as expound]
            [clojure.core.async.impl.protocols :as chan]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.test-doubles.randomness :as mock-randomness]
            [dev.cljtoc.test-doubles.time :as mock-time]))

(defn temp-dir
  "A fresh directory under the system temp dir, returned as a path string.
   Lives here rather than in each test namespace so the real-port tests
   share one shape."
  [prefix]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str prefix (System/nanoTime)))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn channel?
  "True when x is something a blocking take could read from. A port method
   that returns one of these has wrapped a plain function in a channel, so
   the caller has to know about core.async to learn the result."
  [x]
  (satisfies? chan/ReadPort x))

(defn an-envelope?
  "True when x is a result envelope: a map carrying exactly one of :ok or
   :error, the shape every port method returns directly."
  [x]
  (and (map? x) (not= (contains? x :ok) (contains? x :error))))

(defn to-bytes
  "Convert string to UTF-8 byte array."
  [^String s]
  (.getBytes s "UTF-8"))

(defn saved-record
  "A record shaped like the ones the client persists. Progress and status
   read piece state and stats, so a fixture without them is not a download.
   Shared so the CLI and command tests validate the same record shape."
  [id state]
  {:id id
   :state state
   :piece-state {:total-pieces 1 :needed #{} :in-flight #{} :verified #{}}
   :stats {:bytes-downloaded 0}})

(defn scripted-randomness
  "Build a `MockRandomness` whose next draw is `bytes-seqs`. Each draw
   pulls one entry off the queue and returns it as a byte array of the
   requested length; short scripts throw (see `MockRandomness`).

   Shared across every randomness-port test instead of four private
   defns in each namespace (ponytail: same logic, fewer lines)."
  [bytes-seqs]
  (mock-randomness/->MockRandomness (atom bytes-seqs)))

(defn- report-check-failures
  "Print each failed check result through expound; returns results unchanged.
   Binds expound as the explain printer because explain-result refuses the
   default printer. Test-stdout only, so green runs stay byte-identical."
  [check-results]
  (let [failures (filter :failure check-results)]
    (when (seq failures)
      (binding [s/*explain-out* expound/printer]
        (println (expound/explain-results-str failures)))))
  check-results)

(defn check-fdefs
  "Run stest/check over syms, each naming a var with an fdef.
   Returns {sym failure} for failures only, so one deftest can pin a whole
   namespace's check-grade specs while still naming the culprit and its
   shrunk counterexample. Failures also print through expound."
  [syms num-tests]
  (let [check-results (report-check-failures
                       (mapcat #(stest/check [%] {:clojure.spec.test.check/opts
                                                  {:num-tests num-tests}})
                               syms))]
    (into {}
          (keep (fn [{:keys [sym failure]}]
                  (when failure [sym failure])))
          check-results)))

(defn layout-test-info
  "Build an info dict for the given piece length and file lengths:
   single-file when one length, multi-file otherwise. Shared by layout
   tests that need valid input to mutate (ports.disk) or span (domain)."
  [piece-length file-lengths]
  (let [total (reduce + 0 file-lengths)
        file-count (count file-lengths)]
    (if (= 1 file-count)
      {:name "f" :piece-length piece-length :length total}
      {:name "t" :piece-length piece-length
       :files (mapv (fn [file-index file-length]
                      {:path [(str "f" file-index)] :length file-length})
                    (range file-count) file-lengths)})))

(defn compiled-test-layout
  "The compiled output layout for layout-test-info: the valid shape that
   mutation-based specs break one field at a time."
  [piece-length file-lengths]
  (:ok (torrent/compile-output-layout (layout-test-info piece-length file-lengths))))

(defn make-bytes
  "Create a byte array from a sequence of byte values."
  [& vals]
  (byte-array (map unchecked-byte vals)))

(defn ip-string-to-parts
  "Convert an IP address string to byte parts for compact-peer fixtures.
   Test-only: Integer/parseInt throws on non-numeric input, which is fine
   for fixtures built from known-good strings but must never run on
   hostile input — production parsing needs its own guarded version."
  [ip-str]
  (mapv #(Integer/parseInt %) (string/split ip-str #"\.")))

(defn download-manager
  "A download manager over the given ports: the shape the orchestration
   and supervision layers take. The clock is a mock, so a run against it
   is deterministic."
  [network-port disk-port]
  {:network-port network-port
   :disk-port disk-port
   :time-port (mock-time/create)
   :config {}})