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
