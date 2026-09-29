(ns dev.cljtoc.ports.disk-impl-test
  "Tests for DiskPortImpl state persistence.
   Seam: IDiskPort save-state / load-state through real temp dirs.
   Byte arrays must round-trip EDN-safe (hex) through the single port seam.
   Also pins the port contract itself: every method returns its envelope
   directly, so no caller needs to know about core.async."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.cljtoc.test-utils :as test-utils :refer [an-envelope? channel?]]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.ports.disk-impl :as disk-impl]
            [dev.cljtoc.ports.disk :as disk]
            [dev.cljtoc.ports.time :as time]
            [dev.cljtoc.domain.torrent :as torrent]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.cli.state :as cli-state])
  (:import [java.io File]))

(defn- compile-layout
  "Compile an info dict to the output layout the port methods take."
  [info]
  (:ok (torrent/compile-output-layout info)))

(defn- make-port [state-dir]
  (disk-impl/create {:state-dir state-dir
                     :piece-cache-dir (test-utils/temp-dir "piece-cache-")}))

(deftest disk-port-returns-envelopes-not-channels-test
  (testing "every IDiskPort method hands back its envelope, not a channel,
            on the real port and on the mock alike"
    (let [torrent-dir (test-utils/temp-dir "torrent-")
          output-dir (test-utils/temp-dir "output-contract-")
          torrent-path (str torrent-dir "/contract.torrent")
          layout (compile-layout {:name "t" :piece-length 4 :length 4})
          piece-bytes (byte-array [0 1 2 3])
          calls {:read-torrent-file #(disk/read-torrent-file % torrent-path)
                 :read-piece #(disk/read-piece % (byte-array 20) 0)
                 :write-piece #(disk/write-piece % (byte-array 20) 0 piece-bytes)
                 :write-output-piece #(disk/write-output-piece % layout output-dir 0 piece-bytes)
                 :initialize-output-layout #(disk/initialize-output-layout % layout output-dir)
                 :prepare-output-layout #(disk/prepare-output-layout % layout output-dir)
                 :write-prepared-piece #(let [prepared (:ok (disk/prepare-output-layout
                                                             % layout output-dir))]
                                          (disk/write-prepared-piece % prepared output-dir 0 piece-bytes))
                 :ensure-directory #(disk/ensure-directory % output-dir)
                 :save-state #(disk/save-state % {:id "contract"})
                 :load-state #(disk/load-state % "contract")
                 :delete-state #(disk/delete-state % "contract")}
          ports {:real (make-port (test-utils/temp-dir "disk-state-"))
                 :mock (mock-disk/create {})}]
      (doseq [[port-name port] ports
              [method call] calls]
        (let [result (call port)]
          (is (not (channel? result))
              (str port-name " " method " returned a channel, not an envelope"))
          (is (an-envelope? result)
              (str port-name " " method " returned neither :ok nor :error: "
                   (pr-str result))))))))

(deftest piece-cache-scoped-by-torrent-content-test
  (testing "distinct torrents share no cache entries: same piece index, distinct bytes, both intact"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          hash-a (bencode/sha1-hash (test-utils/to-bytes "torrent-a"))
          hash-b (bencode/sha1-hash (test-utils/to-bytes "torrent-b"))
          piece-a (byte-array [0 1 2 3])
          piece-b (byte-array [4 5 6 7])]
      (is (= {:ok :written} (disk/write-piece port hash-a 0 piece-a)))
      (is (= {:ok :written} (disk/write-piece port hash-b 0 piece-b)))
      (let [read-a (:ok (disk/read-piece port hash-a 0))
            read-b (:ok (disk/read-piece port hash-b 0))]
        (is (java.util.Arrays/equals piece-a read-a))
        (is (java.util.Arrays/equals piece-b read-b))))))

(deftest identical-torrents-share-cache-entries-test
  (testing "same info-hash, different names: one entry, last write wins"
    ;; Content-addressing is the point: equal hashes mean equal bytes,
    ;; so sharing is correct, not a collision.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          shared-hash (bencode/sha1-hash (test-utils/to-bytes "same-bytes"))]
      (is (= {:ok :written} (disk/write-piece port shared-hash 0 (byte-array [1 1 1 1]))))
      (is (= {:ok :written} (disk/write-piece port shared-hash 0 (byte-array [2 2 2 2]))))
      (is (java.util.Arrays/equals (byte-array [2 2 2 2])
                                   (:ok (disk/read-piece port shared-hash 0)))))))

(deftest case-variant-names-with-different-content-stay-isolated-test
  (testing "Foo.torrent vs foo.torrent: distinct ids, distinct content, no clobber on any filesystem"
    ;; The ids stay case-distinct (that is id-from-path's contract), yet the
    ;; cache must not follow: on a case-insensitive filesystem raw names
    ;; would resolve to one directory. Hex scopes have no case variants.
    (is (= "Foo" (disk/id-from-path "/dl/Foo.torrent")))
    (is (= "foo" (disk/id-from-path "/dl/foo.torrent")))
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          hash-foo (bencode/sha1-hash (test-utils/to-bytes "FOO-content"))
          hash-foo-lower (bencode/sha1-hash (test-utils/to-bytes "foo-content"))]
      (is (= {:ok :written} (disk/write-piece port hash-foo 0 (byte-array [0 1 2 3]))))
      (is (= {:ok :written} (disk/write-piece port hash-foo-lower 0 (byte-array [4 5 6 7]))))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (:ok (disk/read-piece port hash-foo 0))))
      (is (java.util.Arrays/equals (byte-array [4 5 6 7])
                                   (:ok (disk/read-piece port hash-foo-lower 0)))))))

(def hostile-info-hashes
  "Info hashes that must never name a cache entry: missing, empty, or not bytes at all."
  [nil "" 42 ["a"] (byte-array 0)])

(deftest hostile-info-hashes-are-refused-test
  (testing "a missing or malformed info hash fails closed on both ports, real and mock alike"
    (let [ports [(make-port (test-utils/temp-dir "disk-state-"))
                 (mock-disk/create)]]
      (doseq [port ports
              hostile-hash hostile-info-hashes]
        (is (:error (disk/write-piece port hostile-hash 0 (byte-array [0])))
            (str "write accepted hostile hash " (pr-str hostile-hash)))
        (is (:error (disk/read-piece port hostile-hash 0))
            (str "read accepted hostile hash " (pr-str hostile-hash)))))))

(deftest hostile-info-hash-writes-nothing-test
  (testing "a refused cache write leaves no file behind"
    (let [cache-dir (test-utils/temp-dir "cache-escape-")
          port (disk-impl/create {:state-dir (test-utils/temp-dir "disk-state-")
                                  :piece-cache-dir cache-dir})]
      (doseq [hostile-hash [nil "" (byte-array 0)]]
        (is (:error (disk/write-piece port hostile-hash 0 (byte-array [0])))))
      (is (empty? (seq (.listFiles (io/file cache-dir))))))))

(defspec malformed-info-hashes-are-refused-or-accepted-spec 100
  ;; The guard's vocabulary, generated: byte arrays of any length are usable
  ;; scopes, everything else fails closed on both ports. The literal doseq
  ;; above pins each hostile shape; this pins the combinations.
  (prop/for-all [info-hash (gen/one-of [(gen/fmap #(byte-array %) (gen/vector (gen/choose 0 255) 1 20))
                                        (gen/elements [nil "" 42 (byte-array 0)])])]
                (let [ports [(make-port (test-utils/temp-dir "disk-state-"))
                             (mock-disk/create)]
                      expect-ok? (some? (disk/cache-scope info-hash))]
                  ;; boolean, not true?: refusal yields the :invalid-info-hash
                  ;; keyword, which is truthy but not literally true.
                  (every? boolean
                          (for [port ports]
                            (if expect-ok?
                              (= {:ok :written}
                                 (disk/write-piece port info-hash 0 (byte-array [1])))
                              (:error (disk/write-piece port info-hash 0 (byte-array [1])))))))))

(deftest save-load-round-trip-with-bytes-test
  (testing "a download containing byte arrays round-trips through the disk port"
    (let [state-dir (test-utils/temp-dir "disk-state-")
          port (make-port state-dir)
          download {:id "bytes-torrent"
                    :torrent {:info-hash (byte-array [0 1 15 16 127 -1])}
                    :state :paused}]
      (is (= {:ok :saved} (disk/save-state port download)))
      (let [result (disk/load-state port "bytes-torrent")
            loaded-hash (get-in result [:ok :torrent :info-hash])]
        (is (contains? result :ok))
        (is (= "bytes-torrent" (get-in result [:ok :id])))
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals (byte-array [0 1 15 16 127 -1]) loaded-hash))))))

(deftest save-invalid-tag-returns-save-error-test
  (testing "saving a download with an invalid :cljtoc/bytes tag fails fast
            with :save-error on the real port too (parity with the mock)"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))]
      (is (= :save-error
             (:error (disk/save-state port {:id "bad"
                                            :torrent {:info-hash {:cljtoc/bytes "zz"}}})))))))

(deftest saved-bytes-decode-to-bytes-test
  (testing "byte arrays come back as byte arrays, so a resumed download can handshake and verify"
    (let [state-dir (test-utils/temp-dir "disk-state-")
          port (make-port state-dir)
          info-hash (byte-array [0 1 15 16 127 -1])
          piece-hash (byte-array (repeat 20 (byte 7)))
          download {:id "bytes-torrent"
                    :torrent {:info-hash info-hash
                              :info {:pieces [piece-hash]}}
                    :state :paused}]
      (disk/save-state port download)
      (let [loaded (:ok (disk/load-state port "bytes-torrent"))
            loaded-hash (get-in loaded [:torrent :info-hash])
            loaded-piece (first (get-in loaded [:torrent :info :pieces]))]
        (is (bytes? loaded-hash))
        (is (java.util.Arrays/equals info-hash loaded-hash))
        (is (bytes? loaded-piece))
        (is (java.util.Arrays/equals piece-hash loaded-piece))))))

(deftest saved-file-is-plain-edn-test
  (testing "the state file parses with edn/read-string (no reader tags, no #object)"
    (let [state-dir (test-utils/temp-dir "disk-state-")
          port (make-port state-dir)
          download {:id "edn-safe"
                    :torrent {:info-hash (byte-array [1 2 3])}
                    :state :paused}]
      (disk/save-state port download)
      (let [content (slurp (io/file state-dir "edn-safe.edn"))
            parsed (edn/read-string content)]
        (is (= "edn-safe" (:id parsed)))
        (is (= {:cljtoc/bytes "010203"} (get-in parsed [:torrent :info-hash])))))))

(deftest port-and-keeper-share-one-filename-scheme-test
  (testing "the port writes where state-file-path points and reads what lands there"
    (let [state-dir (test-utils/temp-dir "disk-state-")
          port (make-port state-dir)]
      (disk/save-state port {:id "scheme" :state :paused})
      (is (.exists (io/file (cli-state/state-file-path "scheme" state-dir))))
      (spit (io/file (cli-state/state-file-path "scheme2" state-dir))
            (pr-str (disk/encode-state {:id "scheme2" :state :downloading})))
      (is (= :downloading (get-in (disk/load-state port "scheme2") [:ok :state]))))))

(deftest load-missing-returns-nil-ok-test
  (testing "loading an unknown id returns {:ok nil}"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))]
      (is (= {:ok nil} (disk/load-state port "nope"))))))

(deftest pause-resume-cycle-through-disk-port-test
  (testing "pause persists and resume restores the same download id"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
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
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-single-")
          info {:name "data.bin" :piece-length 4 :length 10}
          piece-bytes [(byte-array [0 1 2 3]) (byte-array [4 5 6 7]) (byte-array [8 9])]]
      (doseq [[piece-index piece-data] (map-indexed vector piece-bytes)]
        (is (= {:ok :written}
               (disk/write-output-piece port (compile-layout info) output-dir piece-index piece-data))))
      (is (java.util.Arrays/equals (byte-array (range 10))
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "data.bin"))))))))

(deftest write-output-piece-spans-file-boundary-test
  (testing "a piece crossing a file boundary lands split across both files"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-multi-")
          info {:name "t" :piece-length 6
                :files [{:path ["a"] :length 4} {:path ["b"] :length 6}]}]
      (is (= {:ok :written}
             (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [0 1 2 3 4 5]))))
      (is (= {:ok :written}
             (disk/write-output-piece port (compile-layout info) output-dir 1 (byte-array [6 7 8 9]))))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "a")))))
      (is (java.util.Arrays/equals (byte-array [4 5 6 7 8 9])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "b"))))))))

(deftest write-output-piece-truncates-stale-file-test
  (testing "a longer file left by an earlier run comes out byte-identical"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-stale-")
          info {:name "data.bin" :piece-length 4 :length 10}
          stale (io/file output-dir "data.bin")]
      (.mkdirs (.getParentFile stale))
      (java.nio.file.Files/write (.toPath stale) (byte-array (repeat 20 (byte 9)))
                                 (into-array java.nio.file.OpenOption []))
      (doseq [[piece-index piece-data] (map-indexed vector [(byte-array [0 1 2 3])
                                                            (byte-array [4 5 6 7])
                                                            (byte-array [8 9])])]
        (is (= {:ok :written}
               (disk/write-output-piece port (compile-layout info) output-dir piece-index piece-data))))
      (is (java.util.Arrays/equals (byte-array (range 10))
                                   (java.nio.file.Files/readAllBytes (.toPath stale)))))))

(deftest write-output-piece-rejects-symlink-escape-test
  (testing "a symlink inside output-dir cannot redirect a write outside it"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-link-")
          outside-dir (test-utils/temp-dir "outside-")
          target (io/file outside-dir "victim.bin")]
      (spit target "original")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "link.bin"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "link.bin" :piece-length 4 :length 4}
            result (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [1 2 3 4]))]
        (is (:error result))
        (is (= "original" (slurp target)))))))

(deftest initialize-output-layout-rejects-symlink-escape-test
  (testing "layout init refuses a symlinked declared path, so no write is redirected"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-link-")
          outside-dir (test-utils/temp-dir "outside-")
          target (io/file outside-dir "victim.bin")]
      (spit target "original")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "link.bin"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "link.bin" :piece-length 4 :length 4}
            result (disk/initialize-output-layout port (compile-layout info) output-dir)]
        (is (= :unsafe-path (:error result)))
        (is (= "original" (slurp target)))))))

(deftest initialize-output-layout-rejects-symlinked-nested-dir-test
  (testing "a symlinked parent directory cannot redirect the layout init either"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-parent-link-")
          outside-dir (test-utils/temp-dir "outside-")]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "t"))
       (.toPath (io/file outside-dir))
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "t" :piece-length 4
                  :files [{:path ["a"] :length 4} {:path ["b"] :length 0}]}
            result (disk/initialize-output-layout port (compile-layout info) output-dir)]
        (is (= :unsafe-path (:error result)))
        (is (not (.exists (io/file outside-dir "a"))))
        (is (not (.exists (io/file outside-dir "b"))))))))

(deftest initialize-output-layout-refuses-the-filesystem-root-test
  (testing "assembling a torrent into / is a mistake, not a download to attempt"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          info {:name "t" :piece-length 4 :files [{:path ["a"] :length 4}]}
          result (disk/initialize-output-layout port (compile-layout info) File/separator)]
      (is (= :unsafe-output-dir (:error result)))
      (is (re-find #"(?i)explicit output director" (:message result)))
      (is (not (.exists (io/file File/separator "t" "a")))))))

(deftest write-output-piece-refuses-the-filesystem-root-test
  (testing "the piece writer refuses / too, not just layout init"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          info {:name "t" :piece-length 4 :files [{:path ["a"] :length 4}]}
          result (disk/write-output-piece port (compile-layout info) File/separator 0 (byte-array 4))]
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
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-dup-")
        info {:name "t" :piece-length 4
              :files [{:path ["a"] :length 4} {:path ["a"] :length 4}]}]
    (is (= :invalid-info (:error (disk/initialize-output-layout port (compile-layout info) output-dir))))
    (is (= :invalid-info (:error (disk/write-output-piece port (compile-layout info) output-dir 0
                                                          (byte-array 4)))))
    (is (not (.exists (io/file output-dir "t"))))))

(deftest initialize-output-layout-creates-no-parent-dir-through-a-symlink-test
  ;; The nested-symlink test above only passes because the symlinked directory
  ;; already exists, so mkdirs has nothing to do. When the declared path digs
  ;; one level deeper, mkdirs follows the link and creates that directory
  ;; outside the output dir before any containment check runs — the write is
  ;; then correctly refused, but the directory it created is not.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-init-deep-link-")
        outside-dir (test-utils/temp-dir "outside-")]
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t"))
     (.toPath (io/file outside-dir))
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 4
                :files [{:path ["sub" "a"] :length 4}]}
          result (disk/initialize-output-layout port (compile-layout info) output-dir)]
      (is (= :unsafe-path (:error result)))
      (is (not (.exists (io/file outside-dir "sub")))))))

(deftest initialize-output-layout-rejects-filesystem-alias-test
  ;; Two DISTINCT declared paths resolving to one file: t/a is a pre-existing
  ;; in-tree symlink to t/b, so each path passes containment on its own —
  ;; but truncating and writing both lands two independent torrent ranges in
  ;; the same target, the aliasing twin of the duplicate-path collapse.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-alias-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (disk/initialize-output-layout port (compile-layout info) output-dir)]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest write-output-piece-rejects-filesystem-alias-test
  ;; Same alias through the piece writer: without init, both spans of one
  ;; piece resolve contained and the second range overwrites the first in
  ;; the shared target.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-alias-write-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (disk/write-output-piece port (compile-layout info) output-dir 0
                                          (byte-array [1 2 3 4 5 6 7 8]))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest initialize-output-layout-rejects-hardlink-alias-test
  ;; Canonical strings cannot see hard links: t/a and t/b resolve
  ;; differently yet share one inode, so both pass containment and the
  ;; canonical collision check — then two ranges overwrite one file.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-hardlink-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createLink (.toPath (io/file output-dir "t" "a"))
                                    (.toPath target))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (disk/initialize-output-layout port (compile-layout info) output-dir)]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest write-output-piece-rejects-hardlink-alias-test
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-hardlink-write-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createLink (.toPath (io/file output-dir "t" "a"))
                                    (.toPath target))
    (let [info {:name "t" :piece-length 8
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (disk/write-output-piece port (compile-layout info) output-dir 0
                                          (byte-array [1 2 3 4 5 6 7 8]))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest write-output-piece-rejects-alias-with-untouched-target-test
  ;; The alias check must see the whole layout, not just the piece's paths:
  ;; with piece-length 4, piece 0 touches only a, but a is a symlink to b —
  ;; validating [a] alone finds no collision, and the write corrupts b.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-alias-partial-")
        target (io/file output-dir "t" "b")]
    (.mkdirs (.getParentFile target))
    (spit target "SENTINEL")
    (java.nio.file.Files/createSymbolicLink
     (.toPath (io/file output-dir "t" "a"))
     (.toPath target)
     (into-array java.nio.file.attribute.FileAttribute []))
    (let [info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          result (disk/write-output-piece port (compile-layout info) output-dir 0
                                          (byte-array [1 2 3 4]))]
      (is (:error result))
      (is (= "SENTINEL" (slurp target))))))

(deftest initialize-output-layout-rejects-layout-without-lengths-test
  ;; A layout with neither :length nor :files must be refused as
  ;; :invalid-info before anything is created — not attempted until
  ;; setLength explodes on nil and reports :write-error.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-no-length-")
        info {:name "t" :piece-length 4}
        result (disk/initialize-output-layout port (compile-layout info) output-dir)]
    (is (= :invalid-info (:error result)))
    (is (not (.exists (io/file output-dir "t"))))))

(deftest initialize-output-layout-rejects-prefix-collision-test
  ;; t/a cannot be both a file and t/a/b's directory. Pre-fix this created
  ;; the file, then failed opening the child through it as :write-error;
  ;; refuse the layout as :invalid-info with nothing created instead.
  (let [port (make-port (test-utils/temp-dir "disk-state-"))
        output-dir (test-utils/temp-dir "output-prefix-")
        info {:name "t" :piece-length 4
              :files [{:path ["a"] :length 4} {:path ["a" "b"] :length 4}]}
        result (disk/initialize-output-layout port (compile-layout info) output-dir)]
    (is (= :invalid-info (:error result)))
    (is (not (.exists (io/file output-dir "t"))))))

(deftest resolve-contained-rejects-the-empty-path-test
  ;; Unreachable through the guarded derivations (every layout path carries
  ;; the root), but the primitive itself must refuse: [] resolves to the
  ;; output dir, which is not contained under itself.
  (let [output-dir (test-utils/temp-dir "output-empty-path-")
        result (#'disk-impl/resolve-contained output-dir [])]
    (is (= :unsafe-path (:error result)))
    (is (empty? (seq (.listFiles (io/file output-dir)))))))

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
      (is (false? (root? (test-utils/temp-dir "not-a-root-"))))
      (is (false? (root? nil)))))
  (testing "the guard still declines the Unix root end to end"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          result (disk/initialize-output-layout port
                                                {:name "t" :piece-length 4
                                                 :files [{:path ["a"] :length 4}]}
                                                File/separator)]
      (is (= :unsafe-output-dir (:error result))))))

(deftest initialize-output-layout-rejects-escaping-component-test
  (testing "layout init refuses the hostile components the piece write refuses"
    ;; The escape is now reported where the layout is derived (compile),
    ;; and the port refuses the uncompilable info with :invalid-info.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-escape-")
          info {:name "a/b" :piece-length 4 :length 4}
          compiled (torrent/compile-output-layout info)
          result (disk/initialize-output-layout port (compile-layout info) output-dir)]
      (is (:error compiled))
      (is (re-find #"escape" (:message compiled)))
      (is (= :invalid-info (:error result)))
      (is (not (.exists (io/file output-dir "a"))))))

  (testing "a nested multi-file path is still accepted"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-nested-")
          info {:name "t" :piece-length 4
                :files [{:path ["sub" "deep" "a"] :length 4}]}]
      (is (= {:ok :initialized} (disk/initialize-output-layout port (compile-layout info) output-dir)))
      (is (.exists (io/file output-dir "t" "sub" "deep" "a"))))))

(deftest write-output-piece-creates-zero-length-file-test
  (testing "a declared zero-length file exists empty after its neighbors land"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-empty-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4}
                        {:path ["empty"] :length 0}
                        {:path ["b"] :length 4}]}
          empty-file (io/file output-dir "t" "empty")]
      (is (= {:ok :initialized} (disk/initialize-output-layout port (compile-layout info) output-dir)))
      (is (= {:ok :written}
             (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [0 1 2 3]))))
      (is (= {:ok :written}
             (disk/write-output-piece port (compile-layout info) output-dir 1 (byte-array [4 5 6 7]))))
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
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-empty-only-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 0} {:path ["b"] :length 0}]}]
      (is (= {:ok :initialized} (disk/initialize-output-layout port (compile-layout info) output-dir)))
      (is (.exists (io/file output-dir "t" "a")))
      (is (zero? (.length (io/file output-dir "t" "a"))))
      (is (.exists (io/file output-dir "t" "b"))))))

(deftest initialize-output-layout-write-error-test
  (testing "a regular file where a parent directory is needed surfaces :write-error"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-blocked-")
          info {:name "t" :piece-length 4
                :files [{:path ["nested" "a"] :length 4}]}]
      (spit (io/file output-dir "t") "not a directory")
      (let [result (disk/initialize-output-layout port (compile-layout info) output-dir)]
        (is (= :write-error (:error result)))
        (is (string? (:message result)))))))

(deftest write-output-piece-write-error-test
  (testing "a piece write into an unwritable path surfaces :write-error"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-write-blocked-")
          info {:name "t" :piece-length 4
                :files [{:path ["nested" "a"] :length 4}]}]
      (spit (io/file output-dir "t") "not a directory")
      (let [result (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [1 2 3 4]))]
        (is (= :write-error (:error result)))
        (is (= "not a directory" (slurp (io/file output-dir "t"))))))))

(deftest write-output-piece-invalid-info-test
  (testing "a torrent-controlled path that escapes the output dir is refused"
    ;; The escape is reported where the layout is derived (compile);
    ;; the port refuses the uncompilable info with :invalid-info.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-escape-info-")
          info {:name "t" :piece-length 4
                :files [{:path [".."] :length 4}]}
          compiled (torrent/compile-output-layout info)
          result (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [1 2 3 4]))]
      (is (:error compiled))
      (is (re-find #"escape" (:message compiled)))
      (is (= :invalid-info (:error result))))))

(deftest initialize-output-layout-invalid-info-test
  (testing "layout init refuses info that cannot produce a layout"
    ;; The missing :name is reported where the layout is derived;
    ;; the port refuses the uncompilable info with :invalid-info.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-init-no-name-")
          info {:piece-length 4 :length 4}
          compiled (torrent/compile-output-layout info)
          result (disk/initialize-output-layout port (compile-layout info) output-dir)]
      (is (:error compiled))
      (is (re-find #":name" (:message compiled)))
      (is (= :invalid-info (:error result))))))

(deftest write-output-piece-catches-non-byte-input-test
  (testing "a piece that is not a byte array surfaces the port's error envelope"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-bad-input-")
          info {:name "t" :piece-length 4 :length 4}
          result (disk/write-output-piece port (compile-layout info) output-dir 0 "not-bytes")]
      (is (= :write-error (:error result)))
      (is (string? (:message result))))))

(deftest initialize-output-layout-catches-malformed-files-test
  (testing "info whose :files is not a collection surfaces the port's error envelope"
    ;; Non-collection :files is refused where the layout is derived
    ;; (an error envelope, never a throw), and the port refuses the
    ;; uncompilable info with :invalid-info.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-bad-files-")
          info {:name "t" :piece-length 4 :files 42}
          compiled (torrent/compile-output-layout info)
          result (disk/initialize-output-layout port (compile-layout info)
                                                output-dir)]
      (is (:error compiled))
      (is (= :invalid-info (:error result)))
      (is (string? (:message result))))))

(deftest initialize-output-layout-rejects-inconsistent-layout-test
  (testing "a layout whose files are not declared in sizes is refused before creating anything"
    ;; Every entry point must agree on what a valid layout is: these shapes
    ;; span fine but have no sizes to truncate to, or starts no compile
    ;; could emit (PR #36 round 6).
    (let [port (make-port (test-utils/temp-dir "disk-state-"))]
      (doseq [layout [{:sizes {}
                       :files [{:path ["t" "a"] :length 4 :start 0}]
                       :total 4 :piece-length 4}
                      {:sizes {["t" "a"] 4}
                       :files [{:path ["t" "a"] :length 4 :start 4}]
                       :total 8 :piece-length 4}]]
        (let [output-dir (test-utils/temp-dir "output-inconsistent-")
              result (disk/initialize-output-layout port layout output-dir)]
          (is (= :invalid-info (:error result)) (str "init " (pr-str layout)))
          (is (not (.exists (io/file output-dir "t")))))))))

(deftest write-output-piece-rejects-inconsistent-layout-test
  (testing "the same shape is refused on the write path, not a :write-error"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-inconsistent-write-")
          layout {:sizes {}
                  :files [{:path ["t" "a"] :length 4 :start 0}]
                  :total 4 :piece-length 4}
          result (disk/write-output-piece port layout output-dir 0 (byte-array [1 2 3 4]))]
      (is (= :invalid-info (:error result))))))

(deftest write-output-piece-leaves-untouched-files-alone-test
  (testing "writing one piece does not re-truncate a file it does not touch"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-untouched-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          sentinel (io/file output-dir "t" "b")]
      (disk/initialize-output-layout port (compile-layout info) output-dir)
      (spit sentinel "sentinel")
      (is (= {:ok :written}
             (disk/write-output-piece port (compile-layout info) output-dir 0 (byte-array [0 1 2 3]))))
      (is (= "sentinel" (slurp sentinel)))
      (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                   (java.nio.file.Files/readAllBytes
                                    (.toPath (io/file output-dir "t" "a"))))))))

;; ---------------------------------------------------------------------------

(deftest prepare-output-layout-returns-explicit-prepared-value-test
  (testing "prepare resolves the whole layout once without creating files"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepare-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          layout (compile-layout info)
          result (disk/prepare-output-layout port layout output-dir)]
      (is (not (:error result)))
      (let [prepared (:ok result)]
        (is (disk/valid-prepared-layout? prepared))
        (is (= layout (:layout prepared)))
        (is (= #{["t" "a"] ["t" "b"]} (set (keys (:resolved prepared)))))
        (is (not (.exists (io/file output-dir "t" "a"))))
        (is (not (.exists (io/file output-dir "t" "b"))))))))

(deftest prepare-output-layout-rejects-filesystem-alias-test
  (testing "prepare refuses a pre-existing symlink alias across the whole layout"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepare-alias-")
          target (io/file output-dir "t" "b")]
      (.mkdirs (.getParentFile target))
      (spit target "SENTINEL")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "t" "a"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "t" :piece-length 4
                  :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
            result (disk/prepare-output-layout port (compile-layout info) output-dir)]
        (is (= :unsafe-path (:error result)))
        (is (= "SENTINEL" (slurp target)))))))

(deftest prepare-output-layout-rejects-hardlink-alias-test
  (testing "prepare refuses a pre-existing hardlink alias"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepare-hardlink-")
          target (io/file output-dir "t" "b")]
      (.mkdirs (.getParentFile target))
      (spit target "SENTINEL")
      (java.nio.file.Files/createLink (.toPath (io/file output-dir "t" "a"))
                                      (.toPath target))
      (let [info {:name "t" :piece-length 4
                  :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
            result (disk/prepare-output-layout port (compile-layout info) output-dir)]
        (is (= :unsafe-path (:error result)))
        (is (= "SENTINEL" (slurp target)))))))

(deftest prepare-output-layout-sees-untouched-targets-test
  (testing "prepare refuses an alias even when the piece would touch only one side"
    ;; With piece-length 4, piece 0 touches only a — but prepare still
    ;; sees b, so the per-piece path never needs the full scan to stay safe.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepare-partial-")
          target (io/file output-dir "t" "b")]
      (.mkdirs (.getParentFile target))
      (spit target "SENTINEL")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file output-dir "t" "a"))
       (.toPath target)
       (into-array java.nio.file.attribute.FileAttribute []))
      (let [info {:name "t" :piece-length 4
                  :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
            ;; Sanity: piece 0 spans only a.
            spans (:ok (torrent/layout-spans (compile-layout info) 0 4))]
        (is (= #{["t" "a"]} (set (map :path spans))))
        (let [result (disk/prepare-output-layout port (compile-layout info) output-dir)]
          (is (= :unsafe-path (:error result)))
          (is (= "SENTINEL" (slurp target))))))))

(deftest prepare-output-layout-refuses-invalid-layout-test
  (testing "prepare refuses a non-compiled layout without touching the filesystem"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepare-invalid-")
          result (disk/prepare-output-layout port {:sizes {}} output-dir)]
      (is (= :invalid-info (:error result)))
      (is (not (.exists (io/file output-dir "t")))))))

(deftest write-prepared-piece-writes-touched-files-test
  (testing "init, prepare, then per-piece writes assemble the content"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepared-write-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          layout (compile-layout info)]
      (is (= {:ok :initialized} (disk/initialize-output-layout port layout output-dir)))
      (let [prepared (:ok (disk/prepare-output-layout port layout output-dir))]
        (is (disk/valid-prepared-layout? prepared))
        (is (= {:ok :written}
               (disk/write-prepared-piece port prepared output-dir 0 (byte-array [0 1 2 3]))))
        (is (= {:ok :written}
               (disk/write-prepared-piece port prepared output-dir 1 (byte-array [4 5 6 7]))))
        (is (java.util.Arrays/equals (byte-array [0 1 2 3])
                                     (java.nio.file.Files/readAllBytes
                                      (.toPath (io/file output-dir "t" "a")))))
        (is (java.util.Arrays/equals (byte-array [4 5 6 7])
                                     (java.nio.file.Files/readAllBytes
                                      (.toPath (io/file output-dir "t" "b")))))))))

(deftest write-prepared-piece-refuses-redirected-touch-test
  (testing "a touched file swapped after prepare is refused, nothing written"
    ;; The TOCTOU the prepared value narrows: prepare froze a pointing at
    ;; its own target; replacing a with a symlink to b afterwards must
    ;; fail the write even though b itself is untouched by this piece.
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepared-redirect-")
          info {:name "t" :piece-length 4
                :files [{:path ["a"] :length 4} {:path ["b"] :length 4}]}
          layout (compile-layout info)]
      (is (= {:ok :initialized} (disk/initialize-output-layout port layout output-dir)))
      (let [prepared (:ok (disk/prepare-output-layout port layout output-dir))
            target-a (io/file output-dir "t" "a")
            target-b (io/file output-dir "t" "b")]
        (spit target-b "SENTINEL")
        (.delete target-a)
        (java.nio.file.Files/createSymbolicLink
         (.toPath target-a)
         (.toPath target-b)
         (into-array java.nio.file.attribute.FileAttribute []))
        (let [result (disk/write-prepared-piece port prepared output-dir 0
                                                (byte-array [0 1 2 3]))]
          (is (= :unsafe-path (:error result)))
          (is (= "SENTINEL" (slurp target-b))))))))

(deftest write-prepared-piece-refuses-foreign-output-dir-test
  (testing "a prepared value is bound to the dir it was resolved under"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepared-home-")
          foreign-dir (test-utils/temp-dir "output-prepared-foreign-")
          info {:name "t" :piece-length 4 :length 4}
          layout (compile-layout info)
          prepared (:ok (disk/prepare-output-layout port layout output-dir))
          result (disk/write-prepared-piece port prepared foreign-dir 0 (byte-array [0 1 2 3]))]
      (is (= :invalid-info (:error result)))
      (is (not (.exists (io/file foreign-dir "t")))))))

(deftest write-prepared-piece-refuses-invalid-prepared-test
  (testing "a forged prepared value is refused before anything is written"
    (let [port (make-port (test-utils/temp-dir "disk-state-"))
          output-dir (test-utils/temp-dir "output-prepared-forged-")
          result (disk/write-prepared-piece port {:layout nil} output-dir 0 (byte-array [0 1 2 3]))]
      (is (= :invalid-info (:error result)))
      (is (not (.exists (io/file output-dir "t")))))))

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
    (disk/write-output-piece
     port (compile-layout info) output-dir piece-index
     (java.util.Arrays/copyOfRange source-bytes (int start) (int (+ start len))))))

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
         output-dir (test-utils/temp-dir "assembly-")
         port (make-port (test-utils/temp-dir "disk-state-"))
         piece-count (int (Math/ceil (/ total (double piece-length))))]
     (when prefill? (prefill-with-junk! output-dir layout))
     (and (= {:ok :initialized}
             (disk/initialize-output-layout port (compile-layout info) output-dir))
          (every? #(= {:ok :written} %)
                  (mapv #(write-piece-at port info output-dir source-bytes
                                         piece-length % total)
                        (range piece-count)))
          (assembled-content-matches? output-dir source-bytes layout)))))
