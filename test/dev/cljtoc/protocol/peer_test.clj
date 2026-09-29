(ns dev.cljtoc.protocol.peer-test
  "Tests for peer wire protocol functions.
   
   Includes example-based tests for specific cases and generative tests
   for round-trip properties and spec compliance."
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as tc-gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.test-utils :as test-utils]
            [dev.cljtoc.utils :as utils]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn make-random-bytes
  "Create a byte array of given length with random values."
  [len]
  (byte-array (repeatedly len #(unchecked-byte (- (rand-int 256) 128)))))

;; ============================================================================
;; PeerHandshake Record Tests
;; ============================================================================

(deftest peer-handshake-creation-test
  (testing "Can create PeerHandshake record"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (test-utils/make-bytes 0 0 0 0 0 0 0 0)
          handshake (peer/->peer-handshake info-hash peer-id reserved)]
      (is (some? (:ok handshake)) "Should return ok key")
      (let [hs (:ok handshake)]
        (is (= "BitTorrent protocol" (:protocol hs)))
        (is (utils/bytes-equal? reserved (:reserved hs)))
        (is (utils/bytes-equal? info-hash (:info-hash hs)))
        (is (utils/bytes-equal? peer-id (:peer-id hs)))))))

(deftest peer-handshake-default-reserved-test
  (testing "PeerHandshake uses default reserved bytes"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          result (peer/->peer-handshake info-hash peer-id)]
      (is (some? (:ok result)) "Should return ok key")
      (let [hs (:ok result)]
        (is (= 8 (count (:reserved hs))))
        (is (every? zero? (:reserved hs)))))))

(deftest peer-handshake-validation-test
  (testing "Invalid info-hash length returns error"
    (let [info-hash (make-random-bytes 19)  ; Wrong length
          peer-id (make-random-bytes 20)
          result (peer/->peer-handshake info-hash peer-id)]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result)))))

  (testing "Invalid peer-id length returns error"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 21)  ; Wrong length
          result (peer/->peer-handshake info-hash peer-id)]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result)))))

  (testing "Invalid reserved length returns error"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (make-random-bytes 7)  ; Wrong length
          result (peer/->peer-handshake info-hash peer-id reserved)]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result))))))

;; ============================================================================
;; Handshake Parsing Tests
;; ============================================================================

(deftest parse-handshake-valid-test
  (testing "Valid 68-byte handshake parses correctly"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (test-utils/make-bytes 0 0 0 0 0 0 0 0)
          built (peer/build-handshake info-hash peer-id reserved)
          handshake-bytes (:ok built)
          parsed (peer/parse-handshake handshake-bytes)]
      (is (some? (:ok parsed)) "Should parse successfully")
      (let [hs (:ok parsed)]
        (is (= "BitTorrent protocol" (:protocol hs)))
        (is (utils/bytes-equal? info-hash (:info-hash hs)))
        (is (utils/bytes-equal? peer-id (:peer-id hs)))
        (is (utils/bytes-equal? reserved (:reserved hs)))))))

(deftest parse-handshake-incomplete-test
  (testing "Incomplete handshake returns error"
    (let [short-bytes (make-random-bytes 67)
          result (peer/parse-handshake short-bytes)]
      (is (some? (:error result)) "Should have error key")
      (is (= :incomplete-handshake (:error result)))
      (is (re-find #"must be 68 bytes" (:message result))))))

(deftest parse-handshake-wrong-protocol-test
  (testing "Wrong protocol string returns error"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (test-utils/make-bytes 0 0 0 0 0 0 0 0)
          ;; Build valid handshake then corrupt protocol
          built (peer/build-handshake info-hash peer-id reserved)
          handshake-bytes (:ok built)
          _ (aset handshake-bytes 1 (byte 0x41))  ; Corrupt protocol with 'A'
          result (peer/parse-handshake handshake-bytes)]
      (is (some? (:error result)) "Should have error key")
      (is (= :unsupported-protocol (:error result))))))

(deftest parse-handshake-preserves-reserved-test
  (testing "Non-zero reserved bytes are preserved"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (test-utils/make-bytes 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01)
          built (peer/build-handshake info-hash peer-id reserved)
          handshake-bytes (:ok built)
          parsed (peer/parse-handshake handshake-bytes)]
      (is (some? (:ok parsed)))
      (is (utils/bytes-equal? reserved (:reserved (:ok parsed)))))))

;; ============================================================================
;; Handshake Building Tests
;; ============================================================================

(deftest build-handshake-length-test
  (testing "Built handshake is exactly 68 bytes"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          result (peer/build-handshake info-hash peer-id)]
      (is (some? (:ok result)) "Should return ok key")
      (is (= 68 (count (:ok result)))))))

(deftest build-handshake-layout-test
  (testing "Handshake has correct byte layout"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 20)
          reserved (test-utils/make-bytes 0 0 0 0 0 0 0 0)
          result (peer/build-handshake info-hash peer-id reserved)
          bytes (:ok result)]
      ;; Byte 0: pstrlen = 19
      (is (= 19 (bit-and (aget bytes 0) 0xFF)))
      ;; Bytes 1-19: "BitTorrent protocol"
      (is (= "BitTorrent protocol" (String. bytes 1 19 "US-ASCII")))
      ;; Bytes 20-27: reserved
      (is (= 0 (bit-and (aget bytes 20) 0xFF)))
      ;; Bytes 28-47: info-hash
      (is (utils/bytes-equal? info-hash (byte-array (take 20 (drop 28 bytes)))))
      ;; Bytes 48-67: peer-id
      (is (utils/bytes-equal? peer-id (byte-array (take 20 (drop 48 bytes))))))))

(deftest build-handshake-validation-test
  (testing "Invalid info-hash length returns error"
    (let [info-hash (make-random-bytes 19)
          peer-id (make-random-bytes 20)
          result (peer/build-handshake info-hash peer-id)]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result)))))

  (testing "Invalid peer-id length returns error"
    (let [info-hash (make-random-bytes 20)
          peer-id (make-random-bytes 21)
          result (peer/build-handshake info-hash peer-id)]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result)))))

  (testing "Invalid reserved length returns error"
    (let [result (peer/build-handshake (make-random-bytes 20)
                                       (make-random-bytes 20)
                                       (make-random-bytes 7))]
      (is (some? (:error result)) "Should have error key")
      (is (= :invalid-input (:error result))))))

(deftest validate-handshake-fields-test
  (testing "Wrong protocol string returns :invalid-input without throwing"
    (let [result (peer/validate-handshake-fields "Wrong protocol"
                                                 (byte-array 8)
                                                 (byte-array 20)
                                                 (byte-array 20))]
      (is (= :invalid-input (:error result)))))
  (testing "Valid fields return the handshake record"
    (let [result (peer/validate-handshake-fields "BitTorrent protocol"
                                                 (byte-array 8)
                                                 (byte-array 20)
                                                 (byte-array 20))]
      (is (some? (:ok result)))
      (is (= "BitTorrent protocol" (:protocol (:ok result)))))))

;; ============================================================================
;; Peer Message Parsing Tests
;; ============================================================================

(deftest parse-keep-alive-test
  (testing "Keep-alive message parses correctly"
    (let [message-bytes (utils/int32-to-bytes 0)  ; Length = 0
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.KeepAlive (:ok result))))))

(deftest parse-simple-messages-test
  (testing "Choke, Unchoke, Interested, Not-interested messages parse correctly"
    (doseq [[id expected-type] [[0 dev.cljtoc.protocol.peer.Choke]
                                [1 dev.cljtoc.protocol.peer.Unchoke]
                                [2 dev.cljtoc.protocol.peer.Interested]
                                [3 dev.cljtoc.protocol.peer.NotInterested]]]
      (let [message-bytes (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte id)]))
            result (peer/parse-message message-bytes)]
        (is (some? (:ok result)) (str "Should parse " expected-type " successfully"))
        (is (instance? expected-type (:ok result)))))))

(deftest parse-have-message-test
  (testing "Have message parses with correct piece index"
    (let [piece-index 123
          message-bytes (utils/concat-bytes (utils/int32-to-bytes 5)
                                            (byte-array [(unchecked-byte 4)])
                                            (utils/int32-to-bytes piece-index))
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Have (:ok result)))
      (is (= piece-index (:piece-index (:ok result)))))))

(deftest parse-bitfield-message-test
  (testing "Bitfield message parses with accessible bytes"
    (let [bitfield-payload (test-utils/make-bytes 0xFF 0x00 0x80)  ; Example bitfield
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count bitfield-payload)))
                                            (byte-array [(unchecked-byte 5)])
                                            bitfield-payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Bitfield (:ok result)))
      (is (utils/bytes-equal? bitfield-payload (:bytes (:ok result)))))))

(deftest parse-request-message-test
  (testing "Request message parses with all three fields"
    (let [piece-index 10
          begin 16384
          length 16384
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin)
                                      (utils/int32-to-bytes length))
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 6)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Request (:ok result)))
      (is (= piece-index (:piece-index (:ok result))))
      (is (= begin (:begin (:ok result))))
      (is (= length (:length (:ok result)))))))

(deftest parse-piece-message-test
  (testing "Piece message parses with index, begin, and data"
    (let [piece-index 10
          begin 16384
          data (make-random-bytes 1024)
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin)
                                      data)
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 7)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Piece (:ok result)))
      (is (= piece-index (:piece-index (:ok result))))
      (is (= begin (:begin (:ok result))))
      (is (utils/bytes-equal? data (:data (:ok result)))))))

(deftest parse-cancel-message-test
  (testing "Cancel message parses with all three fields"
    (let [piece-index 10
          begin 16384
          length 16384
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin)
                                      (utils/int32-to-bytes length))
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 8)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Cancel (:ok result)))
      (is (= piece-index (:piece-index (:ok result))))
      (is (= begin (:begin (:ok result))))
      (is (= length (:length (:ok result)))))))

(deftest parse-unknown-message-id-test
  (testing "Unknown message id returns :unknown-message-type error"
    (let [message-bytes (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 99)])) ; ID 99
          result (peer/parse-message message-bytes)]
      (is (some? (:error result)) "Should return error key")
      (is (= :unknown-message-type (:error result))))))

(deftest parse-incomplete-message-test
  (testing "Incomplete message (declared length > available) returns :incomplete-message error"
    (let [message-bytes (utils/int32-to-bytes 100) ; Declares length 100, but only 4 bytes given
          result (peer/parse-message message-bytes)]
      (is (some? (:error result)) "Should return error key")
      (is (= :incomplete-message (:error result))))))

(deftest parse-message-malformed-payload-test
  (testing "Zero-length message types reject nonzero payloads with :invalid-message"
    (doseq [[id type-name] [[0 "Choke"] [1 "Unchoke"] [2 "Interested"] [3 "NotInterested"]]]
      (let [message-bytes (utils/concat-bytes (utils/int32-to-bytes 2) ; id + 1 payload byte
                                              (byte-array [(unchecked-byte id) 0x00]))
            result (peer/parse-message message-bytes)]
        (is (some? (:error result)) (str type-name " should return error key"))
        (is (= :invalid-message (:error result)) (str type-name " should be :invalid-message")))))
  (testing "Fixed-size messages reject short payloads with :incomplete-message"
    (doseq [{:keys [id payload-len type-name]} [{:id 4 :payload-len 2 :type-name "Have"}
                                                {:id 5 :payload-len 0 :type-name "Bitfield"}
                                                {:id 6 :payload-len 4 :type-name "Request"}
                                                {:id 7 :payload-len 4 :type-name "Piece"}
                                                {:id 8 :payload-len 4 :type-name "Cancel"}]]
      (let [message-bytes (utils/concat-bytes (utils/int32-to-bytes (inc payload-len))
                                              (byte-array (cons (unchecked-byte id)
                                                                (repeat payload-len 0x00))))
            result (peer/parse-message message-bytes)]
        (is (some? (:error result)) (str type-name " should return error key"))
        (is (= :incomplete-message (:error result)) (str type-name " should be :incomplete-message"))))))

(deftest parse-messages-test
  (testing "`parse-messages` handles multiple complete messages"
    (let [msg1-bytes (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 0)])) ; Choke
          msg2-bytes (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 1)])) ; Unchoke
          buffer (utils/concat-bytes msg1-bytes msg2-bytes)
          result (peer/parse-messages buffer)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (= 2 (count (:ok result))))
      (is (instance? dev.cljtoc.protocol.peer.Choke (first (:ok result))))
      (is (instance? dev.cljtoc.protocol.peer.Unchoke (second (:ok result))))
      (is (zero? (count (:remaining result)))))))

(deftest parse-messages-with-incomplete-tail-test
  (testing "`parse-messages` returns remaining bytes for incomplete final message"
    (let [msg1-bytes (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 0)])) ; Choke
          incomplete-msg-bytes (utils/int32-to-bytes 100) ; Declares length 100, but only 4 bytes given
          buffer (utils/concat-bytes msg1-bytes incomplete-msg-bytes)
          result (peer/parse-messages buffer)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (= 1 (count (:ok result))))
      (is (instance? dev.cljtoc.protocol.peer.Choke (first (:ok result))))
      (is (= (count incomplete-msg-bytes) (count (:remaining result)))))))

(deftest parse-messages-error-test
  (testing "An invalid message mid-stream fails the batch, not a partial ok"
    (let [good-message (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 0)]))
          bad-message (utils/concat-bytes (utils/int32-to-bytes 1) (byte-array [(unchecked-byte 99)]))
          result (peer/parse-messages (utils/concat-bytes good-message bad-message))]
      (is (some? (:error result)) "Should return error key")
      (is (= :unknown-message-type (:error result))))))

(deftest parse-messages-only-incomplete-test
  (testing "`parse-messages` returns only remaining bytes if first message is incomplete"
    (let [incomplete-msg-bytes (utils/int32-to-bytes 100) ; Declares length 100, but only 4 bytes given
          result (peer/parse-messages incomplete-msg-bytes)]
      (is (some? (:ok result)) "Should return ok key even if no messages parsed")
      (is (zero? (count (:ok result))))
      (is (= (count incomplete-msg-bytes) (count (:remaining result)))))))

(deftest parse-request-oversize-length-test
  (testing "Request with length > max-block-size returns :invalid-input error"
    (let [piece-index 10
          begin 0
          length (+ peer/max-block-size 1) ; > 16384
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin)
                                      (utils/int32-to-bytes length))
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 6)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:error result)) "Should return error key")
      (is (= :invalid-input (:error result))))))

(deftest parse-piece-oversize-data-test
  (testing "Piece with data length > max-block-size returns :invalid-input error"
    (let [piece-index 10
          begin 0
          data (make-random-bytes (+ peer/max-block-size 1)) ; > 16384
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin)
                                      data)
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 7)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:error result)) "Should return error key")
      (is (= :invalid-input (:error result))))))

;; ============================================================================
;; Round-trip Generative Tests
;; ============================================================================

(defspec handshake-roundtrip-generative 100
  (prop/for-all [info-hash (tc-gen/vector (tc-gen/choose -128 127) 20)
                 peer-id (tc-gen/vector (tc-gen/choose -128 127) 20)
                 reserved (tc-gen/vector (tc-gen/choose 0 255) 8)]
                (let [info-bytes (byte-array (map unchecked-byte info-hash))
                      peer-bytes (byte-array (map unchecked-byte peer-id))
                      reserved-bytes (byte-array (map unchecked-byte reserved))
                      built (peer/build-handshake info-bytes peer-bytes reserved-bytes)
                      handshake-bytes (:ok built)
                      parsed (peer/parse-handshake handshake-bytes)]
                  (and (some? (:ok parsed))
                       (utils/bytes-equal? info-bytes (:info-hash (:ok parsed)))
                       (utils/bytes-equal? peer-bytes (:peer-id (:ok parsed)))
                       (utils/bytes-equal? reserved-bytes (:reserved (:ok parsed)))))))

(defspec peer-message-parsing-generative 100
  (prop/for-all [msg-type (tc-gen/elements [:choke :unchoke :interested :not-interested :have :bitfield :request :piece :cancel])
                 piece-idx (tc-gen/choose 0 100)
                 begin (tc-gen/choose 0 1000)
                 length (tc-gen/choose 1 16384)
                 data (tc-gen/fmap make-random-bytes (tc-gen/choose 1 16384))]
                (let [msg (case msg-type
                            :choke (peer/->Choke)
                            :unchoke (peer/->Unchoke)
                            :interested (peer/->Interested)
                            :not-interested (peer/->NotInterested)
                            :have (peer/->Have piece-idx)
                            :bitfield (peer/->Bitfield (make-random-bytes 10))
                            :request (peer/->Request piece-idx begin length)
                            :piece (peer/->Piece piece-idx begin data)
                            :cancel (peer/->Cancel piece-idx begin length))
                      built-msg (peer/build-message msg)
                      parsed-result (peer/parse-message (:ok built-msg))]
                  (if (:ok built-msg)
                    (and (some? (:ok parsed-result))
                         (s/valid? ::peer/peer-message (:ok parsed-result)))
                    true))))

(defspec parse-messages-multiple-generative 100
  (prop/for-all [messages-vec (tc-gen/bind (tc-gen/choose 1 5)
                                           (fn [n]
                                             (tc-gen/vector
                                              (tc-gen/one-of [(tc-gen/fmap (fn [p] (peer/->Have p)) (tc-gen/choose 0 100))
                                                              (tc-gen/fmap (fn [l] (peer/->Request 0 0 l)) (tc-gen/choose 1 16384))])
                                              n)))]
                (let [built-bytes (peer/build-messages messages-vec)
                      parse-result (peer/parse-messages (:ok built-bytes))]
                  (and (some? (:ok parse-result))
                       (= (count messages-vec) (count (:ok parse-result)))
                       (every? (fn [[original parsed]] (s/valid? ::peer/peer-message parsed)) (map vector messages-vec (:ok parse-result)))))))

;; ============================================================================
;; Edge Case Tests (T084, T087)
;; ============================================================================

(deftest parse-piece-zero-byte-payload-test
  (testing "Piece message with 0-byte data is valid (T084)"
    (let [piece-index 5
          begin 0
          ;; Payload: 4-byte index + 4-byte begin, 0-byte data
          payload (utils/concat-bytes (utils/int32-to-bytes piece-index)
                                      (utils/int32-to-bytes begin))
          message-bytes (utils/concat-bytes (utils/int32-to-bytes (+ 1 (count payload)))
                                            (byte-array [(unchecked-byte 7)])
                                            payload)
          result (peer/parse-message message-bytes)]
      (is (some? (:ok result)) "Should parse successfully")
      (is (instance? dev.cljtoc.protocol.peer.Piece (:ok result)))
      (is (= piece-index (:piece-index (:ok result))))
      (is (= begin (:begin (:ok result))))
      (is (= 0 (count (:data (:ok result)))) "Data should be 0 bytes"))))

(deftest parse-message-partial-prefix-test
  (testing "Message with partial length prefix returns :incomplete-message (T087)"
    (doseq [n [0 1 2 3]]
      (let [partial-bytes (make-random-bytes n)
            result (peer/parse-message partial-bytes)]
        (is (some? (:error result))
            (str "Should return error for " n " bytes"))
        (is (= :incomplete-message (:error result))
            (str "Error should be :incomplete-message for " n " bytes"))))))

(deftest build-message-unknown-type-test
  (testing "Unknown record type returns :unknown-message-type error"
    (let [result (peer/build-message {:type :bogus})]
      (is (some? (:error result)) "Should return error key")
      (is (= :unknown-message-type (:error result)))))
  (testing "nil message returns :unknown-message-type, never throws"
    (let [result (peer/build-message nil)]
      (is (= :unknown-message-type (:error result))))))

(deftest build-messages-error-test
  (testing "An unbuildable record fails the whole batch"
    (let [result (peer/build-messages [(peer/->Choke) {:type :bogus} (peer/->Unchoke)])]
      (is (some? (:error result)) "Should return error key")
      (is (= :unknown-message-type (:error result))))))

;; ============================================================================
;; Invariant Property Tests (T090)
;; ============================================================================

(defspec build-handshake-always-68-bytes 100
  (prop/for-all [info-hash (tc-gen/vector (tc-gen/choose -128 127) 20)
                 peer-id (tc-gen/vector (tc-gen/choose -128 127) 20)]
                (let [info-bytes (byte-array (map unchecked-byte info-hash))
                      peer-bytes (byte-array (map unchecked-byte peer-id))
                      result (peer/build-handshake info-bytes peer-bytes)]
                  (= 68 (count (:ok result))))))

(defspec built-request-block-length-invariant 100
  (prop/for-all [piece-idx (tc-gen/choose 0 1000)
                 begin (tc-gen/choose 0 100000)
                 length (tc-gen/choose 1 peer/max-block-size)]
                (let [msg (peer/->Request piece-idx begin length)
                      built (peer/build-message msg)
                      parsed (peer/parse-message (:ok built))]
                  (and (some? (:ok parsed))
                       (<= (:length (:ok parsed)) peer/max-block-size)))))

(defspec built-piece-block-length-invariant 100
  (prop/for-all [piece-idx (tc-gen/choose 0 1000)
                 begin (tc-gen/choose 0 100000)
                 data-len (tc-gen/choose 0 peer/max-block-size)]
                (let [data (make-random-bytes data-len)
                      msg (peer/->Piece piece-idx begin data)
                      built (peer/build-message msg)
                      parsed (peer/parse-message (:ok built))]
                  (and (some? (:ok parsed))
                       (<= (count (:data (:ok parsed))) peer/max-block-size)))))

;; ============================================================================
;; Spec Compliance Tests
;; ============================================================================

(deftest spec-validation-test
  (testing "clojure.spec validates PeerHandshake correctly"
    (let [valid-handshake {:protocol "BitTorrent protocol"
                           :reserved (byte-array 8)
                           :info-hash (byte-array 20)
                           :peer-id (byte-array 20)}
          invalid-handshake {:protocol "Wrong protocol"
                             :reserved (byte-array 8)
                             :info-hash (byte-array 20)
                             :peer-id (byte-array 20)}]
      (is (s/valid? ::peer/peer-handshake valid-handshake))
      (is (not (s/valid? ::peer/peer-handshake invalid-handshake))))))

(deftest envelope-value-spec-test
  (testing "::ok-result accepts any success value and generates"
    (is (s/valid? ::peer/ok-result {:ok (byte-array 4)}))
    (is (s/valid? ::peer/ok-result {:ok (peer/->Choke)}))
    (is (= 5 (count (tc-gen/sample (s/gen ::peer/ok-result) 5))))))

;; gen-byte-array moved to dev.cljtoc.utils; its sampling coverage lives
;; in utils-test now.
