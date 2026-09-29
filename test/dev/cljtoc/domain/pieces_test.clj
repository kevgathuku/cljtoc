(ns dev.cljtoc.domain.pieces-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.pieces :as pieces]
            [dev.cljtoc.test-utils :as test-utils]))

;; ---------------------------------------------------------------------------
;; GROUP 1: US1 — Track Piece Download Status
;; ---------------------------------------------------------------------------

(deftest initial-piece-state-test
  (testing "N=3 creates state with needed=#{0 1 2}, empty in-flight and verified"
    (let [state (pieces/initial-piece-state 3)]
      (is (= 3 (:total-pieces state)))
      (is (= #{0 1 2} (:needed state)))
      (is (= #{} (:in-flight state)))
      (is (= #{} (:verified state)))
      (is (= 3 (pieces/needed-count state)))
      (is (= 0 (pieces/in-flight-count state)))
      (is (= 0 (pieces/verified-count state)))))
  (testing "N=1 creates single-piece state"
    (let [state (pieces/initial-piece-state 1)]
      (is (= #{0} (:needed state)))
      (is (= 1 (pieces/needed-count state))))))

(deftest mark-in-flight-test
  (testing "piece in needed moves to in-flight"
    (let [state  (pieces/initial-piece-state 5)
          result (pieces/mark-in-flight state 2)]
      (is (some? (:ok result)))
      (let [state2 (:ok result)]
        (is (not (contains? (:needed state2) 2)))
        (is (contains? (:in-flight state2) 2))
        (is (= 4 (pieces/needed-count state2)))
        (is (= 1 (pieces/in-flight-count state2))))))
  (testing "original state is unchanged after transition"
    (let [state (pieces/initial-piece-state 5)
          _     (pieces/mark-in-flight state 2)]
      (is (contains? (:needed state) 2))
      (is (= 5 (pieces/needed-count state))))))

(deftest mark-verified-test
  (testing "in-flight piece moves to verified"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 2))
          result (pieces/mark-verified state2 2)]
      (is (some? (:ok result)))
      (let [state3 (:ok result)]
        (is (not (contains? (:in-flight state3) 2)))
        (is (contains? (:verified state3) 2))
        (is (= 0 (pieces/in-flight-count state3)))
        (is (= 1 (pieces/verified-count state3)))))))

(deftest requeue-piece-test
  (testing "failed in-flight piece returns to needed"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 2))
          result (pieces/requeue-piece state2 2)]
      (is (some? (:ok result)))
      (let [state3 (:ok result)]
        (is (not (contains? (:in-flight state3) 2)))
        (is (contains? (:needed state3) 2))
        (is (= 0 (pieces/in-flight-count state3)))
        (is (= 5 (pieces/needed-count state3)))))))

(deftest invalid-transitions-test
  (testing "mark-in-flight on already in-flight piece returns :invalid-transition"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 2))
          result (pieces/mark-in-flight state2 2)]
      (is (= :invalid-transition (:error result)))
      (is (string? (:message result)))))
  (testing "mark-in-flight on verified piece returns :invalid-transition"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 2))
          state3 (:ok (pieces/mark-verified state2 2))
          result (pieces/mark-in-flight state3 2)]
      (is (= :invalid-transition (:error result)))))
  (testing "mark-verified on needed piece returns :invalid-transition"
    (let [state  (pieces/initial-piece-state 5)
          result (pieces/mark-verified state 2)]
      (is (= :invalid-transition (:error result)))))
  (testing "requeue-piece on needed piece returns :invalid-transition"
    (let [state  (pieces/initial-piece-state 5)
          result (pieces/requeue-piece state 2)]
      (is (= :invalid-transition (:error result)))))
  (testing "requeue-verified on a piece that was never verified returns :invalid-transition"
    (let [state  (pieces/initial-piece-state 5)
          result (pieces/requeue-verified state 2)]
      (is (= :invalid-transition (:error result)))
      (is (string? (:message result))))))

(deftest immutability-test
  (testing "original PieceState is unmodified after mark-in-flight"
    (let [state  (pieces/initial-piece-state 5)
          _      (pieces/mark-in-flight state 0)
          _      (pieces/mark-in-flight state 1)
          _      (pieces/mark-in-flight state 2)]
      (is (= #{0 1 2 3 4} (:needed state)))
      (is (= #{} (:in-flight state)))))
  (testing "original PieceState is unmodified after requeue"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 3))
          _      (pieces/requeue-piece state2 3)]
      (is (contains? (:in-flight state2) 3)))))

(deftest complete?-test
  (testing "returns false when pieces remain"
    (let [state (pieces/initial-piece-state 3)]
      (is (false? (pieces/complete? state)))))
  (testing "returns false after partial completion"
    (let [state  (pieces/initial-piece-state 3)
          state2 (:ok (pieces/mark-in-flight state 0))
          state3 (:ok (pieces/mark-verified state2 0))]
      (is (false? (pieces/complete? state3)))))
  (testing "returns true when all pieces verified"
    (let [state (reduce (fn [s idx]
                          (:ok (pieces/mark-verified
                                (:ok (pieces/mark-in-flight s idx))
                                idx)))
                        (pieces/initial-piece-state 3)
                        [0 1 2])]
      (is (true? (pieces/complete? state))))))

(defspec state-partition-invariant 100
  (prop/for-all
   [total (gen/choose 1 1000)
    idx   (gen/choose 0 999)]
   (let [state0  (pieces/initial-piece-state total)
         piece   (mod idx total)
         state1  (:ok (pieces/mark-in-flight state0 piece))
         state2v (:ok (pieces/mark-verified state1 piece))
         state2r (:ok (pieces/requeue-piece state1 piece))
         sum     (fn [s] (+ (pieces/needed-count s)
                            (pieces/in-flight-count s)
                            (pieces/verified-count s)))]
     (and (= total (sum state0))
          (= total (sum state1))
          (= total (sum state2v))
          (= total (sum state2r))))))

;; ---------------------------------------------------------------------------
;; GROUP 2: US2 — Select Next Piece (Rarest-First)
;; ---------------------------------------------------------------------------

(deftest select-piece-rarest-test
  (testing "selects the rarest piece from peer's available set"
    ;; piece 5: held by 1 peer, piece 2: held by 3 peers — piece 5 is rarest
    (let [state (pieces/initial-piece-state 10)
          peer-av #{2 5}
          all-peers [#{0 2 5} #{2 3} #{0 1 2}]
          result (pieces/select-piece state peer-av all-peers)]
      (is (= {:ok 5} result)))))

(deftest select-piece-no-overlap-test
  (testing "peer with no needed pieces returns {:ok nil}"
    (let [state (reduce (fn [s i]
                          (:ok (pieces/mark-verified
                                (:ok (pieces/mark-in-flight s i)) i)))
                        (pieces/initial-piece-state 5)
                        [0 1 2 3 4])
          result (pieces/select-piece state #{0 1 2 3 4} [#{0 1 2 3 4}])]
      (is (= {:ok nil} result))))
  (testing "empty peer-available returns {:ok nil}"
    (let [state  (pieces/initial-piece-state 5)
          result (pieces/select-piece state #{} [])]
      (is (= {:ok nil} result)))))

(deftest select-piece-tie-breaking-test
  (testing "equal rarity — lowest piece index is selected"
    (let [state   (pieces/initial-piece-state 10)
          peer-av #{3 7}
          all-peers [#{3 7} #{3 7}]   ; both equally rare
          result  (pieces/select-piece state peer-av all-peers)]
      (is (= {:ok 3} result)))))

(deftest select-piece-excludes-in-flight-test
  (testing "in-flight pieces are not selected"
    (let [state  (pieces/initial-piece-state 5)
          ;; mark pieces 0 and 1 in-flight
          state2 (:ok (pieces/mark-in-flight state 0))
          state3 (:ok (pieces/mark-in-flight state2 1))
          ;; peer has all pieces but only 2 and 3 are needed
          result (pieces/select-piece state3 #{0 1 2 3} [#{0 1 2 3}])]
      (is (= 2 (:ok result)))
      (is (not= 0 (:ok result)))
      (is (not= 1 (:ok result))))))

(defspec select-piece-safety 100
  (prop/for-all
   [total    (gen/choose 2 50)
    peer-set (gen/fmap set (gen/list (gen/choose 0 49)))]
   (let [state  (pieces/initial-piece-state total)
         all    [peer-set]
         result (pieces/select-piece state peer-set all)]
     (or (nil? (:ok result))
         (and (contains? (:needed state) (:ok result))
              (contains? peer-set (:ok result)))))))

;; ---------------------------------------------------------------------------
;; GROUP 3: US3 — Decompose Piece into Blocks
;; ---------------------------------------------------------------------------

(deftest piece-blocks-standard-test
  (testing "512 KiB piece → 32 blocks of exactly 16384 bytes"
    (let [{:keys [ok]} (pieces/piece-blocks 0 524288 1073741824)]
      (is (= 32 (count ok)))
      (is (every? #(= 16384 (:length %)) ok))
      (is (= 0 (:piece-index (first ok))))
      (is (= 0 (:offset (first ok))))
      (is (= 507904 (:offset (last ok)))))))

(deftest piece-blocks-remainder-test
  (testing "piece not a multiple of 16 KiB — last block is the remainder"
    ;; 20000-byte piece: 1 full block of 16384 + 1 block of 3616
    (let [{:keys [ok]} (pieces/piece-blocks 0 20000 20000)]
      (is (= 2 (count ok)))
      (is (= 16384 (:length (first ok))))
      (is (= 3616 (:length (second ok))))
      (is (= 16384 (:offset (second ok)))))))

(deftest piece-blocks-last-piece-test
  (testing "last piece shorter than standard — blocks sum to actual shorter length"
    ;; total = 2*524288 + 8192 = 1056768, last piece is piece 2 with 8192 bytes
    (let [{:keys [ok]} (pieces/piece-blocks 2 524288 1056768)]
      (is (= 1 (count ok)))
      (is (= {:piece-index 2 :offset 0 :length 8192}
             (select-keys (first ok) [:piece-index :offset :length]))))))

(deftest piece-blocks-single-byte-test
  (testing "single-byte piece → exactly one block of length 1"
    (let [{:keys [ok]} (pieces/piece-blocks 0 16384 1)]
      (is (= 1 (count ok)))
      (is (= 1 (:length (first ok))))
      (is (= 0 (:offset (first ok)))))))

(deftest piece-blocks-out-of-range-test
  (testing "piece index >= total piece count returns :invalid-input"
    (let [result (pieces/piece-blocks 5 524288 1048576)] ; 2 pieces (0,1), idx 5 invalid
      (is (= :invalid-input (:error result)))
      (is (string? (:message result))))))

(defspec piece-blocks-coverage 100
  (prop/for-all
   [total-pieces (gen/choose 1 100)
    piece-offset (gen/choose 0 99)
    spl          (gen/choose 1 1048576)]
   (let [;; Choose a total-length that makes exactly total-pieces pieces,
         ;; with a possibly shorter last piece (1 to spl bytes)
         remainder    (gen/generate (gen/choose 1 spl))
         total-length (+ (* (dec total-pieces) spl) remainder)
         piece-index  (mod piece-offset total-pieces)
         result       (pieces/piece-blocks piece-index spl total-length)]
     (when-let [blocks (:ok result)]
       (let [piece-start (* piece-index spl)
             piece-end   (min (* (long (inc piece-index)) spl) total-length)
             expected    (- piece-end piece-start)]
         (and (every? #(<= (:length %) 16384) blocks)
              (= expected (reduce + (map :length blocks)))))))))

;; ---------------------------------------------------------------------------
;; GROUP 3b: issue #43 — Single-sourced tail-piece length
;; ---------------------------------------------------------------------------

(deftest piece-length-test
  (testing "nominal pieces report the standard length"
    (is (= 524288 (pieces/piece-length 0 524288 1056768)))
    (is (= 524288 (pieces/piece-length 1 524288 1056768))))
  (testing "the tail piece reports its real (shorter) length"
    (is (= 8192 (pieces/piece-length 2 524288 1056768))))
  (testing "an exact-multiple total has a full-length tail piece"
    (is (= 524288 (pieces/piece-length 1 524288 1048576))))
  (testing "a single-piece torrent reports the total"
    (is (= 1 (pieces/piece-length 0 16384 1))))
  (testing "an out-of-range index reports zero rather than going negative"
    (is (= 0 (pieces/piece-length 5 524288 1048576)))))

(defspec piece-length-sums-to-total 150
  (prop/for-all
   [total-pieces (gen/choose 1 25)
    spl          (gen/choose 1 65536)]
   (let [remainder    (gen/generate (gen/choose 1 spl))
         total-length (+ (* (dec total-pieces) spl) remainder)]
     (= total-length
        (reduce + (map (fn [piece-index]
                         (pieces/piece-length piece-index spl total-length))
                       (range total-pieces)))))))

;; ---------------------------------------------------------------------------
;; GROUP 4: US4 — Verify Piece Integrity
;; ---------------------------------------------------------------------------

(deftest verify-piece-pass-test
  (testing "matching bytes return {:ok piece-index}"
    (let [data          (byte-array [1 2 3 4 5])
          expected-hash (bencode/sha1-hash data)
          result        (pieces/verify-piece 5 data expected-hash)]
      (is (= {:ok 5} result)))))

(deftest verify-piece-corruption-test
  (testing "single-byte corruption returns :hash-mismatch"
    (let [data          (byte-array [1 2 3 4 5])
          expected-hash (bencode/sha1-hash data)
          corrupted     (byte-array [1 2 3 4 99])
          result        (pieces/verify-piece 5 corrupted expected-hash)]
      (is (= :hash-mismatch (:error result)))
      (is (= 5 (:piece-index result)))
      (is (string? (:message result))))))

(deftest verify-piece-empty-bytes-test
  (testing "empty byte array returns :invalid-input"
    (let [result (pieces/verify-piece 0 (byte-array 0) (byte-array 20))]
      (is (= :invalid-input (:error result)))
      (is (string? (:message result))))))

(deftest verify-piece-bad-hash-length-test
  (testing "expected hash not 20 bytes returns :invalid-input"
    (let [result (pieces/verify-piece 0 (byte-array [1 2 3]) (byte-array 19))]
      (is (= :invalid-input (:error result))))
    (let [result (pieces/verify-piece 0 (byte-array [1 2 3]) (byte-array 21))]
      (is (= :invalid-input (:error result))))))

(defspec verify-piece-referentially-transparent 100
  (prop/for-all
   [data (gen/fmap byte-array (gen/vector (gen/choose 0 255) 1 100))
    idx  (gen/choose 0 999)]
   (let [hash   (bencode/sha1-hash data)
         result1 (pieces/verify-piece idx data hash)
         result2 (pieces/verify-piece idx data hash)]
     (= result1 result2))))

;; ---------------------------------------------------------------------------
;; GROUP 5: US5 — Endgame Mode
;; ---------------------------------------------------------------------------

(deftest endgame?-true-test
  (testing "returns true when (needed + in-flight) <= threshold"
    (let [state  (pieces/initial-piece-state 5)
          ;; mark 3 verified, 1 in-flight, 1 needed → remaining = 2
          state2 (reduce (fn [s i]
                           (:ok (pieces/mark-verified
                                 (:ok (pieces/mark-in-flight s i)) i)))
                         state [0 1 2])
          state3 (:ok (pieces/mark-in-flight state2 3))]
      ;; remaining = 1 needed + 1 in-flight = 2
      (is (true? (pieces/endgame? state3 20)))
      (is (true? (pieces/endgame? state3 2))))))

(deftest endgame?-false-test
  (testing "returns false when remaining pieces exceed threshold"
    (let [state (pieces/initial-piece-state 50)]
      (is (false? (pieces/endgame? state 20))))))

(deftest endgame?-completed-test
  (testing "completed torrent satisfies endgame for any positive threshold"
    (let [state (reduce (fn [s i]
                          (:ok (pieces/mark-verified
                                (:ok (pieces/mark-in-flight s i)) i)))
                        (pieces/initial-piece-state 3)
                        [0 1 2])]
      (is (true? (pieces/endgame? state 1)))
      (is (true? (pieces/endgame? state 0))))))

(deftest select-pieces-endgame-test
  (testing "returns needed AND in-flight pieces that peer has"
    (let [state  (pieces/initial-piece-state 5)
          state2 (:ok (pieces/mark-in-flight state 0))
          ;; needed: #{1 2 3 4}, in-flight: #{0}
          ;; peer has #{0 2 4 99}
          result (pieces/select-pieces-endgame state2 #{0 2 4 99})]
      ;; should return 0 (in-flight), 2, 4 (needed) — sorted
      (is (= {:ok [0 2 4]} result)))))

(deftest select-pieces-endgame-empty-test
  (testing "returns {:ok []} when peer has nothing in needed or in-flight"
    (let [state (reduce (fn [s i]
                          (:ok (pieces/mark-verified
                                (:ok (pieces/mark-in-flight s i)) i)))
                        (pieces/initial-piece-state 3)
                        [0 1 2])
          result (pieces/select-pieces-endgame state #{0 1 2})]
      (is (= {:ok []} result)))))

;; ---------------------------------------------------------------------------
;; GROUP 6: Edge Cases (Phase 8)
;; ---------------------------------------------------------------------------

(deftest single-piece-torrent-test
  (testing "torrent with exactly 1 piece — full lifecycle works"
    (let [state  (pieces/initial-piece-state 1)
          _      (is (= 1 (pieces/needed-count state)))
          _      (is (false? (pieces/complete? state)))
          state2 (:ok (pieces/mark-in-flight state 0))
          _      (is (= 0 (pieces/needed-count state2)))
          _      (is (= 1 (pieces/in-flight-count state2)))
          state3 (:ok (pieces/mark-verified state2 0))]
      (is (= 1 (pieces/verified-count state3)))
      (is (true? (pieces/complete? state3))))))

(deftest identical-peer-bitfields-test
  (testing "two peers with identical bitfields — deterministic lowest-index result"
    (let [state   (pieces/initial-piece-state 10)
          peer-av #{3 7 9}
          ;; Both peers have identical sets — all equally rare (freq=2)
          all-peers [#{3 7 9} #{3 7 9}]
          result  (pieces/select-piece state peer-av all-peers)]
      ;; Lowest index (3) should be selected as tie-breaker
      (is (= {:ok 3} result)))))

(deftest all-peers-lack-needed-piece-test
  (testing "all peers lack a specific needed piece — select-piece returns {:ok nil}"
    (let [state   (pieces/initial-piece-state 5)
          ;; Mark pieces 0-3 verified, only piece 4 is needed
          state2  (reduce (fn [s i]
                            (:ok (pieces/mark-verified
                                  (:ok (pieces/mark-in-flight s i)) i)))
                          state [0 1 2 3])
          ;; No peer has piece 4
          peer-av #{0 1 2 3}
          result  (pieces/select-piece state2 peer-av [#{0 1 2 3}])]
      (is (= {:ok nil} result)))))

(deftest one-byte-torrent-block-decomposition-test
  (testing "piece-blocks for piece 0 in a 1-byte total torrent"
    (let [{:keys [ok]} (pieces/piece-blocks 0 16384 1)]
      (is (= 1 (count ok)))
      (is (= 0 (:piece-index (first ok))))
      (is (= 0 (:offset (first ok))))
      (is (= 1 (:length (first ok)))))))

(deftest piece-length-huge-index-test
  (testing "a Long/MAX index intersects nowhere and reports zero (total over nat-int)"
    (let [huge Long/MAX_VALUE]
      (is (= 0 (pieces/piece-length huge 16384 1000)))
      (is (= 1000 (pieces/piece-length 0 huge 1000)))
      (is (= 16384 (pieces/piece-length 0 16384 huge)))
      (is (= :invalid-input (:error (pieces/piece-blocks huge 16384 1000)))))))

(deftest piece-blocks-double-precision-test
  (testing "the final piece past 2^53 is accepted (double math rounded its count down)"
    ;; total-length 2^53+1 at 2 bytes/piece is 4503599627370497 pieces;
    ;; (double total-length) loses the +1 and the old ceiling rejected
    ;; the true last index.
    (let [total-length 9007199254740993
          {:keys [ok error]} (pieces/piece-blocks 4503599627370496 2 total-length)]
      (is (nil? error))
      (is (= 1 (count ok)))
      (is (= 1 (:length (first ok)))))))

(deftest requeue-verified-test
  (testing "a verified piece whose cache bytes are gone returns to needed"
    (let [state (:ok (pieces/mark-in-flight (pieces/initial-piece-state 5) 2))
          state2 (:ok (pieces/mark-verified state 2))
          result (pieces/requeue-verified state2 2)]
      (is (some? (:ok result)))
      (let [state3 (:ok result)]
        (is (not (contains? (:verified state3) 2)))
        (is (contains? (:needed state3) 2))
        (is (= 0 (pieces/verified-count state3)))
        (is (= 5 (pieces/needed-count state3)))))))

;; ---------------------------------------------------------------------------
;; GROUP 7: ::piece-state shape (issue #40)
;;
;; ::piece-state must admit exactly the states the domain can produce:
;; needed / in-flight / verified are disjoint and their union is
;; range(total-pieces). Anything else is unreachable, and the default
;; s/keys generator builds such shapes (e.g. two verified pieces for a
;; one-piece torrent), which no transition :fn can survive.
;; ---------------------------------------------------------------------------

(deftest piece-state-spec-rejects-unreachable-shapes-test
  (testing "over-full verified set (issue #40 repro) is invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 1 :needed #{} :in-flight #{} :verified #{0 1}}))))
  (testing "out-of-range indices are invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 2 :needed #{0 1 5} :in-flight #{} :verified #{}}))))
  (testing "overlapping sets are invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 2 :needed #{0} :in-flight #{0} :verified #{1}}))))
  (testing "a short partition (missing index) is invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 2 :needed #{0} :in-flight #{} :verified #{}}))))
  (testing "a non-positive total is invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 0 :needed #{} :in-flight #{} :verified #{}}))))
  (testing "non-set buckets are invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 2 :needed [0 1] :in-flight #{} :verified #{}}))))
  (testing "a missing bucket is invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state
                       {:total-pieces 1 :needed #{0} :in-flight #{}}))))
  (testing "a non-map is invalid"
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state nil)))
    (is (not (s/valid? :dev.cljtoc.domain.pieces/piece-state []))))
  (testing "a huge total with empty sets rejects without building the range"
    (is (false? (s/valid? :dev.cljtoc.domain.pieces/piece-state
                          {:total-pieces 1000000000000 :needed #{} :in-flight #{} :verified #{}}))))
  (testing "fail-closed branches direct (s/keys rejects these first, so the\n   predicate's own guards are reachable only by direct call)"
    (let [valid? @#'pieces/valid-partition?]
      (is (true? (valid? {:total-pieces 2 :needed #{0} :in-flight #{1} :verified #{}})))
      (is (false? (valid? nil)))
      (is (false? (valid? [])))
      (is (false? (valid? {:total-pieces 0 :needed #{} :in-flight #{} :verified #{}})))
      (is (false? (valid? {:total-pieces "2" :needed #{0 1} :in-flight #{} :verified #{}})))
      (is (false? (valid? {:total-pieces 2 :needed [0 1] :in-flight #{} :verified #{}})))
      (is (false? (valid? {:total-pieces 2 :needed #{0 1} :in-flight #{} :verified [0]})))
      (is (false? (valid? {:total-pieces 2 :needed #{0}})))
      (is (false? (valid? {:total-pieces 2 :needed #{0} :in-flight #{0} :verified #{1}})))
      (is (false? (valid? {:total-pieces 3 :needed #{0 2} :in-flight #{1} :verified #{0}})))
      (is (false? (valid? {:total-pieces 3 :needed #{2} :in-flight #{0 1} :verified #{1}})))
      (is (false? (valid? {:total-pieces 2 :needed #{0 1 5} :in-flight #{} :verified #{}})))
      (is (false? (valid? {:total-pieces 2 :needed #{0 "x"} :in-flight #{} :verified #{}})))))
  (testing "every reachable shape stays valid"
    (let [state0 (pieces/initial-piece-state 3)
          state1 (:ok (pieces/mark-in-flight state0 1))
          state2 (:ok (pieces/mark-verified state1 1))
          state3 (:ok (pieces/requeue-verified state2 1))]
      (is (s/valid? :dev.cljtoc.domain.pieces/piece-state state0))
      (is (s/valid? :dev.cljtoc.domain.pieces/piece-state state1))
      (is (s/valid? :dev.cljtoc.domain.pieces/piece-state state2))
      (is (s/valid? :dev.cljtoc.domain.pieces/piece-state state3)))))

;; fdef specs hold generatively (stest/check), mirroring
;; torrent_test.clj / download_test.clj. assemble-piece is excluded on
;; principle, not by accident: it throws on malformed blocks instead of
;; returning an error envelope (issue #41), so it is partial over its
;; declared (s/coll-of map?) args and no generator can make it
;; check-grade until that issue lands.
;; ---------------------------------------------------------------------------

(deftest fdef-specs-hold-generatively-test
  (testing "every check-grade pieces fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.domain.pieces/initial-piece-state
                      dev.cljtoc.domain.pieces/needed-count
                      dev.cljtoc.domain.pieces/in-flight-count
                      dev.cljtoc.domain.pieces/verified-count
                      dev.cljtoc.domain.pieces/complete?
                      dev.cljtoc.domain.pieces/mark-in-flight
                      dev.cljtoc.domain.pieces/mark-verified
                      dev.cljtoc.domain.pieces/requeue-piece
                      dev.cljtoc.domain.pieces/requeue-verified
                      dev.cljtoc.domain.pieces/select-piece
                      dev.cljtoc.domain.pieces/piece-length
                      dev.cljtoc.domain.pieces/piece-blocks
                      dev.cljtoc.domain.pieces/verify-piece
                      dev.cljtoc.domain.pieces/endgame?
                      dev.cljtoc.domain.pieces/select-pieces-endgame]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))

(defspec piece-state-generator-yields-partitions 100
  (prop/for-all
   [state (s/gen :dev.cljtoc.domain.pieces/piece-state)]
   (let [{:keys [total-pieces needed in-flight verified]} state]
     (and (= (set (range total-pieces))
             (set/union needed in-flight verified))
          (empty? (set/intersection needed in-flight))
          (empty? (set/intersection needed verified))
          (empty? (set/intersection in-flight verified))))))
