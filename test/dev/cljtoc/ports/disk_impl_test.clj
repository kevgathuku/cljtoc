(ns dev.cljtoc.ports.disk-impl-test
  "Tests for DiskPortImpl state persistence.
   Seam: IDiskPort save-state / load-state through real temp dirs.
   Byte arrays must round-trip EDN-safe (hex), loadable by either seam."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [<!!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.cli.state :as cli-state]))

(defn- temp-dir [prefix]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str prefix (System/nanoTime)))]
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- make-port [state-dir]
  (disk-impl/create {:state-dir state-dir
                     :piece-cache-dir (temp-dir "piece-cache-")}))

(deftest save-load-round-trip-with-bytes-test
  (testing "a download containing byte arrays round-trips through the disk port"
    (let [state-dir (temp-dir "disk-state-")
          port (make-port state-dir)
          download {:id "bytes-torrent"
                    :torrent {:info-hash (byte-array [0 1 15 16 127 -1])}
                    :state :paused}]
      (is (= {:ok :saved} (<!! (disk/save-state port download))))
      (let [result (<!! (disk/load-state port "bytes-torrent"))
            loaded-hash (get-in result [:ok :torrent :info-hash])]
        (is (contains? result :ok))
        (is (= "bytes-torrent" (get-in result [:ok :id])))
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals (byte-array [0 1 15 16 127 -1]) loaded-hash))))))

(deftest saved-bytes-decode-to-bytes-test
  (testing "byte arrays come back as byte arrays, so a resumed download can handshake and verify"
    (let [state-dir (temp-dir "disk-state-")
          port (make-port state-dir)
          info-hash (byte-array [0 1 15 16 127 -1])
          piece-hash (byte-array (repeat 20 (byte 7)))
          download {:id "bytes-torrent"
                    :torrent {:info-hash info-hash
                              :info {:pieces [piece-hash]}}
                    :state :paused}]
      (<!! (disk/save-state port download))
      (let [loaded (:ok (<!! (disk/load-state port "bytes-torrent")))
            loaded-hash (get-in loaded [:torrent :info-hash])
            loaded-piece (first (get-in loaded [:torrent :info :pieces]))]
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals info-hash loaded-hash))
        (is (bytes? loaded-piece))
        (is (java.util.Arrays/equals piece-hash loaded-piece))))))

(deftest saved-file-is-plain-edn-test
  (testing "the state file parses with edn/read-string (no reader tags, no #object)"
    (let [state-dir (temp-dir "disk-state-")
          port (make-port state-dir)
          download {:id "edn-safe"
                    :torrent {:info-hash (byte-array [1 2 3])}
                    :state :paused}]
      (<!! (disk/save-state port download))
      (let [content (slurp (io/file state-dir "edn-safe.edn"))
            parsed (edn/read-string content)]
        (is (= "edn-safe" (:id parsed)))
        (is (= {:cljtoc/bytes "010203"} (get-in parsed [:torrent :info-hash])))))))

(deftest seams-interoperate-test
  (testing "state saved via the disk port loads via cli-state and vice versa"
    (let [state-dir (temp-dir "disk-state-")
          port (make-port state-dir)
          via-disk {:id "interop" :state :paused :note "from-disk"}]
      (<!! (disk/save-state port via-disk))
      (is (= :paused (:state (cli-state/load-state "interop" state-dir))))
      (cli-state/save-state {:id "interop2" :state :downloading} state-dir)
      (let [result (<!! (disk/load-state port "interop2"))]
        (is (= :downloading (get-in result [:ok :state])))))))

(deftest load-missing-returns-nil-ok-test
  (testing "loading an unknown id returns {:ok nil}"
    (let [port (make-port (temp-dir "disk-state-"))]
      (is (= {:ok nil} (<!! (disk/load-state port "nope")))))))

(deftest pause-resume-cycle-through-disk-port-test
  (testing "pause persists and resume restores the same download id"
    (let [port (make-port (temp-dir "disk-state-"))
          started (assoc (download/initial-download {:info {:pieces ["h1" "h2"]}}
                                                    "/out" "cycle")
                         :state :downloading)
          paused (:ok (download/pause-download port started))
          resumed (:ok (download/resume-download port nil paused))]
      (is (= :paused (:state paused)))
      (is (= :downloading (:state resumed)))
      (is (= "cycle" (:id resumed))))))
