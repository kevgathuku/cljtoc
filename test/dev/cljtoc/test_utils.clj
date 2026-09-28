(ns dev.cljtoc.test-utils
  "Shared test utilities and helper functions."
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.core.async.impl.protocols :as chan]
            [dev.cljtoc.domain.torrent :as torrent]))

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

(defn check-fdefs
  "Run stest/check over syms, each naming a var with an fdef.
   Returns {sym failure} for failures only, so one deftest can pin a whole
   namespace's check-grade specs while still naming the culprit and its
   shrunk counterexample."
  [syms num-tests]
  (into {}
        (comp (map #(first (stest/check [%] {:clojure.spec.test.check/opts
                                             {:num-tests num-tests}})))
              (keep (fn [{:keys [sym failure]}]
                      (when failure [sym failure]))))
        syms))

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
