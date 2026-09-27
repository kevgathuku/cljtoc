(ns dev.cljtoc.ports.disk-impl-test
  "Tests for DiskPortImpl state persistence.
   Seam: IDiskPort save-state / load-state through real temp dirs.
   Byte arrays must round-trip EDN-safe (hex), loadable by either seam."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [<!!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.cli.state :as cli-state])
  (:import [java.io File]))

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
          started (assoc (download/initial-download (time/->RealTimePort)
                                                    {:info {:pieces ["h1" "h2"]}}
                                                    "/out" "cycle")
                         :state :downloading)
          paused (:ok (download/pause-download port started))
          resumed (:ok (download/resume-download port nil paused))]
      (is (= :paused (:state paused)))
      (is (= :downloading (:state resumed)))
      (is (= "cycle" (:id resumed))))))

(deftest write-output-piece-assembles-single-file-test
  (testing "N pieces written through the port assemble byte-identical under output-dir"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-single-")
          info {:name "data.bin" :piece-length 4 :length 10}
          piece-bytes [(byte-array [0 1 2 3]) (byte-array [4 5 6 7]) (byte-array [8 9])]]
      (doseq [[piece-index piece-data] (map-indexed vector piece-bytes)]
        (is (= {:ok :written}
               (<!! (disk/write-output-piece port info output-dir piece-index piece-data)))))
      (is (java.util.Arrays/equals (byte-array (range 10))
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "data.bin"))))))))

(deftest write-output-piece-spans-file-boundary-test
  (testing "a piece crossing a file boundary lands split across both files"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-multi-")
          info {:name "t" :piece-length 6
                :files [{:path ["a"] :length 4} {:path ["b"] :length 6}]}]
      (is (= {:ok :written}
             (<!! (disk/write-output-piece port info output-dir 0 (byte-array [0 1 2 3 4 5])))))
      (is (= {:ok :written}
             (<!! (disk/write-output-piece port info output-dir 1 (byte-array [6 7 8 9])))))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "a")))))
      (is (java.util.Arrays/equals (byte-array [4 5 6 7 8 9])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "b"))))))))

(deftest write-output-piece-truncates-stale-file-test
  (testing "a longer file left by an earlier run comes out byte-identical"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-stale-")
          info {:name "data.bin" :piece-length 4 :length 10}
          stale (io/file output-dir "data.bin")]
      (.mkdirs (.getParentFile stale))
      (java.nio.file.Files/write (.toPath stale) (byte-array (repeat 20 (byte 9)))
                                 (into-array java.nio.file.OpenOption []))
      (doseq [[piece-index piece-data] (map-indexed vector [(byte-array [0 1 2 3])
                                                            (byte-array [4 5 6 7])
                                                            (byte-array [8 9])])]
        (is (= {:ok :written}
               (<!! (disk/write-output-piece port info output-dir piece-index piece-data)))))
      (is (java.util.Arrays/equals (byte-array (range 10))
                                   (java.nio.file.Files/readAllBytes (.toPath stale)))))))

(deftest write-output-piece-rejects-symlink-escape-test
  (testing "a symlink inside output-dir cannot redirect a write outside it"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-link-")
          outside-dir (temp-dir "outside-")
          target (io/file outside-dir "victim.bin")]
      (spit target "original")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "link.bin"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "link.bin" :piece-length 4 :length 4}
            result (<!! (disk/write-output-piece port info output-dir 0 (byte-array [1 2 3 4])))]
        (is (:error result))
        (is (= "original" (slurp target)))))))

(deftest initialize-output-layout-rejects-symlink-escape-test
  (testing "layout init refuses a symlinked declared path, so no write is redirected"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-link-")
          outside-dir (temp-dir "outside-")
          target (io/file outside-dir "victim.bin")]
      (spit target "original")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "link.bin"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "link.bin" :piece-length 4 :length 4}
            result (<!! (disk/initialize-output-layout port info output-dir))]
        (is (= :unsafe-path (:error result)))
        (is (= "original" (slurp target)))))))

(deftest initialize-output-layout-rejects-symlinked-nested-dir-test
  (testing "a symlinked parent directory cannot redirect the layout init either"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-parent-link-")
          outside-dir (temp-dir "outside-")]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "t"))
       (.toPath (io/file outside-dir))
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "t" :piece-length 4
                  :files [{:path ["a"] :length 4} {:path ["b"] :length 0}]}
            result (<!! (disk/initialize-output-layout port info output-dir))]
        (is (= :unsafe-path (:error result)))
        (is (not (.exists (io/file outside-dir "a"))))
        (is (not (.exists (io/file outside-dir "b"))))))))

(deftest initialize-output-layout-refuses-the-filesystem-root-test
  (testing "assembling a torrent into / is a mistake, not a download to attempt"
    (let [port (make-port (temp-dir "disk-state-"))
          info {:name "t" :piece-length 4 :files [{:path ["a"] :length 4}]}
          result (<!! (disk/initialize-output-layout port info File/separator))]
      (is (= :unsafe-output-dir (:error result)))
      (is (re-find #"(?i)explicit output director" (:message result)))
      (is (not (.exists (io/file File/separator "t" "a")))))))

(deftest write-output-piece-refuses-the-filesystem-root-test
  (testing "the piece writer refuses / too, not just layout init"
    (let [port (make-port (temp-dir "disk-state-"))
          info {:name "t" :piece-length 4 :files [{:path ["a"] :length 4}]}
          result (<!! (disk/write-output-piece port info File/separator 0 (byte-array 4)))]
      (is (= :unsafe-output-dir (:error result)))
      (is (not (.exists (io/file File/separator "t" "a")))))))

(deftest containment-prefix-does-not-double-the-filesystem-root-test
  (testing "a canonical dir that is itself the filesystem root keeps its single separator"
    ;; Independent of the policy guard above: resolve-contained must stay
    ;; correct for any output-dir it is handed, so the containment primitive
    ;; is pinned on its own rather than only through paths that reach it.
    (let [containment-prefix #'disk-impl/containment-prefix]
      (is (= File/separator (containment-prefix File/separator)))
      (is (= (str "tmp" File/separator "out" File/separator)
             (containment-prefix (str "tmp" File/separator "out"))))
      (is (= (str "tmp" File/separator "out" File/separator)
             (containment-prefix (str "tmp" File/separator "out" File/separator)))))))

(deftest initialize-output-layout-rejects-duplicate-declared-path-test
  ;; Two entries for one path: the size map keeps one length, the spans hand
  ;; out two ranges for it, and both land in the same physical file. The
  ;; layout must be refused before anything is created.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-dup-")
        info {:name "t" :piece-length 4
              :files [{:path ["a"] :length 4} {:path ["a"] :length 4}]}]
    (is (= :invalid-info (:error (<!! (disk/initialize-output-layout port info output-dir)))))
    (is (= :invalid-info (:error (<!! (disk/write-output-piece port info output-dir 0
                                                               (byte-array 4))))))
    (is (not (.exists (io/file output-dir "t"))))))

(deftest initialize-output-layout-creates-no-parent-dir-through-a-symlink-test
  ;; The nested-symlink test above only passes because the symlinked directory
  ;; already exists, so mkdirs has nothing to do. When the declared path digs
  ;; one level deeper, mkdirs follows the link and creates that directory
  ;; outside the output dir before any containment check runs — the write is
  ;; then correctly refused, but the directory it created is not.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-init-deep-link-")
        outside-dir (temp-dir "outside-")]
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t"))
     (.toPath (io/file outside-dir))
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 4
                :files [{:path ["sub" "a"] :length 4}]}
          result (<!! (disk/initialize-output-layout port info output-dir))]
      (is (= :unsafe-path (:error result)))
      (is (not (.exists (io/file outside-dir "sub")))))))

(deftest initialize-output-layout-rejects-filesystem-alias-test
  ;; Two DISTINCT declared paths resolving to one file: t/a is a pre-existing
  ;; in-tree symlink to t/b, so each path passes containment on its own —
  ;; but truncating and writing both lands two independent torrent ranges in
  ;; the same target, the aliasing twin of the duplicate-path collapse.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-alias-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (<!! (disk/initialize-output-layout port info output-dir))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest write-output-piece-rejects-filesystem-alias-test
  ;; Same alias through the piece writer: without init, both spans of one
  ;; piece resolve contained and the second range overwrites the first in
  ;; the shared target.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-alias-write-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (<!! (disk/write-output-piece port info output-dir 0
                                               (byte-array [1 2 3 4 5 6 7 8])))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest write-output-piece-rejects-alias-with-untouched-target-test
  ;; The alias check must see the whole layout, not just the piece's paths:
  ;; with piece-length 4, piece 0 touches only a, but a is a symlink to b —
  ;; validating [a] alone finds no collision, and the write corrupts b.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-alias-partial-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (<!! (disk/write-output-piece port info output-dir 0
                                               (byte-array [1 2 3 4])))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest initialize-output-layout-rejects-layout-without-lengths-test
  ;; A layout with neither :length nor :files must be refused as
  ;; :invalid-info before anything is created — not attempted until
  ;; setLength explodes on nil and reports :write-error.
  (let [port (make-port (temp-dir "disk-state-"))
        output-dir (temp-dir "output-no-length-")
        info {:name "t" :piece-length 4}
        result (<!! (disk/initialize-output-layout port info output-dir))]
    (is (= :invalid-info (:error result)))
    (is (not (.exists (io/file output-dir "t"))))))

(deftest filesystem-root-is-detected-by-shape-not-spelling-test
  ;; The root policy must not depend on how a platform spells a root.
  ;; Comparing against File/separator matches the Unix root only: a Windows
  ;; drive root canonicalizes to "C:\" and would slip through. A root is the
  ;; one canonical path with no parent. Pinned on the helper because macOS
  ;; has no drive root to hand declined-output-dir — "C:\" canonicalizes
  ;; here to an ordinary file under the cwd.
  (let [root? #'disk-impl/filesystem-root?]
    (testing "a drive root is a root the way / is"
      (is (true? (root? "C:\\")))
      (is (true? (root? File/separator))))
    (testing "an ordinary canonical directory is not a root, and neither is an unresolvable path"
      (is (false? (root? (str "tmp" File/separator "out"))))
      (is (false? (root? (temp-dir "not-a-root-"))))
      (is (false? (root? nil)))))
  (testing "the guard still declines the Unix root end to end"
    (let [port (make-port (temp-dir "disk-state-"))
          result (<!! (disk/initialize-output-layout port
                                                     {:name "t" :piece-length 4
                                                      :files [{:path ["a"] :length 4}]}
                                                     File/separator))]
      (is (= :unsafe-output-dir (:error result))))))

(deftest initialize-output-layout-rejects-escaping-component-test
  (testing "layout init refuses the hostile components the piece write refuses"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-escape-")
          info {:name "a/b" :piece-length 4 :length 4}
          result (<!! (disk/initialize-output-layout port info output-dir))]
      (is (= :invalid-info (:error result)))
      (is (re-find #"escape" (:message result)))
      (is (not (.exists (io/file output-dir "a"))))))

  (testing "a nested multi-file path is still accepted"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-nested-")
          info {:name "t" :piece-length 4
                :files [{:path ["sub" "deep" "a"] :length 4}]}]
      (is (= {:ok :initialized} (<!! (disk/initialize-output-layout port info output-dir))))
      (is (.exists (io/file output-dir "t" "sub" "deep" "a"))))))

(deftest write-output-piece-creates-zero-length-file-test
  (testing "a declared zero-length file exists empty after its neighbors land"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-empty-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4}
                        {:path ["empty"] :length 0}
                        {:path ["b"] :length 4}]}
          empty-file (io/file output-dir "t" "empty")]
      (is (= {:ok :initialized} (<!! (disk/initialize-output-layout port info output-dir))))
      (is (= {:ok :written}
             (<!! (disk/write-output-piece port info output-dir 0 (byte-array [0 1 2 3])))))
      (is (= {:ok :written}
             (<!! (disk/write-output-piece port info output-dir 1 (byte-array [4 5 6 7])))))
      (is (.exists empty-file))
      (is (zero? (.length empty-file)))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "a")))))
      (is (java.util.Arrays/equals (byte-array [4 5 6 7])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "b"))))))))

(deftest initialize-output-layout-creates-empty-files-test
  (testing "a torrent with no pieces still materializes its empty files"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-empty-only-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 0} {:path ["b"] :length 0}]}]
      (is (= {:ok :initialized} (<!! (disk/initialize-output-layout port info output-dir))))
      (is (.exists (io/file output-dir "t" "a")))
      (is (zero? (.length (io/file output-dir "t" "a"))))
      (is (.exists (io/file output-dir "t" "b"))))))

(deftest initialize-output-layout-write-error-test
  (testing "a regular file where a parent directory is needed surfaces :write-error"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-blocked-")
          info {:name "t" :piece-length 4
                :files [{:path ["nested" "a"] :length 4}]}]
      (spit (io/file output-dir "t") "not a directory")
      (let [result (<!! (disk/initialize-output-layout port info output-dir))]
        (is (= :write-error (:error result)))
        (is (string? (:message result)))))))

(deftest write-output-piece-write-error-test
  (testing "a piece write into an unwritable path surfaces :write-error"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-write-blocked-")
          info {:name "t" :piece-length 4
                :files [{:path ["nested" "a"] :length 4}]}]
      (spit (io/file output-dir "t") "not a directory")
      (let [result (<!! (disk/write-output-piece port info output-dir 0 (byte-array [1 2 3 4])))]
        (is (= :write-error (:error result)))
        (is (= "not a directory" (slurp (io/file output-dir "t"))))))))

(deftest write-output-piece-invalid-info-test
  (testing "a torrent-controlled path that escapes the output dir is refused"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-escape-info-")
          info {:name "t" :piece-length 4
                :files [{:path [".."] :length 4}]}
          result (<!! (disk/write-output-piece port info output-dir 0 (byte-array [1 2 3 4])))]
      (is (= :invalid-info (:error result)))
      (is (re-find #"escape" (:message result))))))

(deftest initialize-output-layout-invalid-info-test
  (testing "layout init refuses info that cannot produce a layout"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-init-no-name-")
          result (<!! (disk/initialize-output-layout port {:piece-length 4 :length 4} output-dir))]
      (is (= :invalid-info (:error result)))
      (is (re-find #":name" (:message result))))))

(deftest write-output-piece-catches-non-byte-input-test
  (testing "a piece that is not a byte array surfaces the port's error envelope"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-bad-input-")
          info {:name "t" :piece-length 4 :length 4}
          result (<!! (disk/write-output-piece port info output-dir 0 "not-bytes"))]
      (is (= :write-error (:error result)))
      (is (string? (:message result))))))

(deftest initialize-output-layout-catches-malformed-files-test
  (testing "info whose :files is not a collection surfaces the port's error envelope"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-bad-files-")
          result (<!! (disk/initialize-output-layout port {:name "t" :piece-length 4
                                                           :files 42}
                                                     output-dir))]
      (is (= :write-error (:error result)))
      (is (string? (:message result))))))

(deftest write-output-piece-leaves-untouched-files-alone-test
  (testing "writing one piece does not re-truncate a file it does not touch"
    (let [port (make-port (temp-dir "disk-state-"))
          output-dir (temp-dir "output-untouched-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          sentinel (io/file output-dir "t" "b")]
      (<!! (disk/initialize-output-layout port info output-dir))
      (spit sentinel "sentinel")
      (is (= {:ok :written}
             (<!! (disk/write-output-piece port info output-dir 0 (byte-array [0 1 2 3])))))
      (is (= "sentinel" (slurp sentinel)))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "a"))))))))

;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; The property this branch exists to guarantee: a completed download is
;; byte-identical to the torrent content, whatever the layout, whatever the
;; piece length, and whatever junk an earlier run left behind.
;; ---------------------------------------------------------------------------

(defn- file-layout-oracle
  "Segments, declared length, and byte offset for each declared file, derived
   only from the declared lengths so no domain logic is reused as the oracle."
  [single? file-lengths]
  (mapv (fn [file-index file-length start]
          {:segments (if single? ["f"] ["t" (str "f" file-index)])
           :length file-length
           :start start})
        (range (count file-lengths))
        file-lengths
        (reductions + 0 file-lengths)))

(defn- generated-info
  "The same single-file/multi-file shape the port tests use, built from
   generated lengths."
  [piece-length file-lengths single?]
  (if single?
    {:name "f" :piece-length piece-length :length (reduce + 0 file-lengths)}
    {:name "t" :piece-length piece-length
     :files (mapv (fn [file-index file-length]
                    {:path [(str "f" file-index)] :length file-length})
                  (range (count file-lengths)) file-lengths)}))

(defn- generated-bytes
  [total]
  (byte-array (map #(unchecked-byte (mod (+ (* % 1103515245) 12345) 256))
                   (range total))))

(defn- write-piece-at
  "Write the piece starting at start, len bytes of source-bytes, via the port."
  [port info output-dir source-bytes piece-length piece-index total]
  (let [start (* piece-index piece-length)
        len (min piece-length (- total start))]
    (<!! (disk/write-output-piece
          port info output-dir piece-index
          (java.util.Arrays/copyOfRange source-bytes (int start) (int (+ start len)))))))

(defn- prefill-with-junk!
  [output-dir layout]
  (doseq [{:keys [segments length]} layout]
    (let [file (apply io/file output-dir segments)]
      (.mkdirs (.getParentFile file))
      (with-open [out (io/output-stream file)]
        (.write out (byte-array (repeat (+ length 37) (byte 74))))))))

(defn- assembled-content-matches?
  [output-dir source-bytes layout]
  (every? (fn [{:keys [segments length start]}]
            (let [target (apply io/file output-dir segments)
                  actual (with-open [in (io/input-stream target)]
                           (.readAllBytes ^java.io.InputStream in))]
              (and (= length (alength actual))
                   (java.util.Arrays/equals
                    actual
                    (java.util.Arrays/copyOfRange
                     source-bytes (int start) (int (+ start length)))))))
          layout))

(defspec output-assembly-is-byte-identical-spec 20
  (prop/for-all
   [piece-length (gen/choose 1 16)
    file-lengths (gen/vector (gen/choose 0 20) 1 4)
    prefill? gen/boolean]
   (let [file-count (count file-lengths)
         single? (= 1 file-count)
         total (reduce + 0 file-lengths)
         info (generated-info piece-length file-lengths single?)
         layout (file-layout-oracle single? file-lengths)
         source-bytes (generated-bytes total)
         output-dir (temp-dir "assembly-")
         port (make-port (temp-dir "disk-state-"))
         piece-count (int (Math/ceil (/ total (double piece-length))))]
     (when prefill? (prefill-with-junk! output-dir layout))
     (and (= {:ok :initialized}
             (<!! (disk/initialize-output-layout port info output-dir)))
          (every? #(= {:ok :written} %)
                  (mapv #(write-piece-at port info output-dir source-bytes
                                         piece-length % total)
                        (range piece-count)))
          (assembled-content-matches? output-dir source-bytes layout)))))
