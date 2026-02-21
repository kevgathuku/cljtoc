(ns dev.cljtoc.domain.pieces-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.domain.pieces :as pieces]))

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
      (is (= :invalid-transition (:error result))))))

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
