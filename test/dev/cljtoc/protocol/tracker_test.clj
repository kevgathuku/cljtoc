(ns dev.cljtoc.protocol.tracker-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [dev.cljtoc.domain.bencode :as bencode]
   [dev.cljtoc.protocol.tracker :as tracker]
   [dev.cljtoc.protocol.tracker.spec :as spec]
   [dev.cljtoc.test-utils :refer [to-bytes]]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- make-compact-peer-bytes
  "Create compact peer format bytes (6 bytes per IPv4 peer)"
  [ip-parts port]
  (byte-array (concat ip-parts
                      [(unchecked-byte (bit-shift-right port 8))
                       (unchecked-byte (bit-and port 0xFF))])))

(defn- ip-string-to-parts
  "Convert IP address string to byte parts"
  [ip-str]
  (mapv #(Integer/parseInt %) (string/split ip-str #"\.")))

;; ---------------------------------------------------------------------------
;; GROUP 1: Compact peer parsing (IPv4)
;; ---------------------------------------------------------------------------

(deftest parse-compact-peers-ipv4-basic-test
  (testing "parses single IPv4 peer in compact format"
    (let [peer-bytes (byte-array [192 168 1 1 0x1A 0xE1])  ; 192.168.1.1:6881
          result (tracker/parse-compact-peers-ipv4 peer-bytes)]
      (is (contains? result :ok))
      (is (= 1 (count (:ok result))))
      (is (= "192.168.1.1" (:ip (first (:ok result)))))
      (is (= 6881 (:port (first (:ok result)))))))

  (testing "parses multiple IPv4 peers"
    (let [peer-bytes (byte-array [192 168 1 1 0x1A 0xE1    ; 192.168.1.1:6881
                                  10 0 0 1 0x1F 0x90])      ; 10.0.0.1:8080
          result (tracker/parse-compact-peers-ipv4 peer-bytes)]
      (is (contains? result :ok))
      (is (= 2 (count (:ok result))))
      (is (= "192.168.1.1" (:ip (first (:ok result)))))
      (is (= 6881 (:port (first (:ok result)))))
      (is (= "10.0.0.1" (:ip (second (:ok result)))))
      (is (= 8080 (:port (second (:ok result)))))))

  (testing "returns error for invalid length"
    (let [peer-bytes (byte-array [192 168 1])  ; Only 3 bytes
          result (tracker/parse-compact-peers-ipv4 peer-bytes)]
      (is (contains? result :error))
      (is (= :invalid-peer-data (:error result)))))

  (testing "handles empty peer list"
    (let [peer-bytes (byte-array [])
          result (tracker/parse-compact-peers-ipv4 peer-bytes)]
      (is (contains? result :ok))
      (is (= [] (:ok result))))))

(defspec parse-compact-peers-ipv4-generative-test 100
  (prop/for-all [peers (gen/vector (gen/tuple (s/gen ::spec/ip-address)
                                              (s/gen ::spec/port))
                                   1 10)]
    ;; Property: parse(build(peers)) = peers
                (let [bytes (byte-array
                             (apply concat
                                    (map (fn [[ip port]]
                                           (make-compact-peer-bytes
                                            (ip-string-to-parts ip)
                                            port))
                                         peers)))
                      result (tracker/parse-compact-peers-ipv4 bytes)]
                  (and (:ok result)
                       (= (count peers) (count (:ok result)))
           ;; Verify each peer matches
                       (every? (fn [[expected-peer actual-peer]]
                                 (and (= (first expected-peer) (:ip actual-peer))
                                      (= (second expected-peer) (:port actual-peer))))
                               (map vector peers (:ok result)))))))

(defspec generated-peers-satisfy-spec 100
  (testing "All generated peers satisfy the peer spec"
    (prop/for-all [peer (s/gen ::spec/peer)]
                  (s/valid? ::spec/peer peer))))

(defspec generated-peer-ids-are-valid 100
  (testing "All generated peer-ids are exactly 20 bytes"
    (prop/for-all [peer-id (s/gen ::spec/peer-id)]
                  (and (bytes? peer-id)
                       (= 20 (alength peer-id))))))

(defspec generated-info-hashes-are-valid 100
  (testing "All generated info-hashes are exactly 20 bytes"
    (prop/for-all [info-hash (s/gen ::spec/info-hash)]
                  (and (bytes? info-hash)
                       (= 20 (alength info-hash))))))

(defspec generated-ports-are-in-range 100
  (testing "All generated ports are in valid range and realistic"
    (prop/for-all [port (s/gen ::spec/port)]
                  (and (int? port)
                       (<= 1 port 65535)
                       (>= port 1024)))))  ; Our generator uses 1024-65535

;; ---------------------------------------------------------------------------
;; GROUP 2: Dictionary peer parsing
;; ---------------------------------------------------------------------------

(deftest parse-dictionary-peers-test
  (testing "parses peer list with peer IDs"
    (let [peer-id (byte-array 20)
          peers [{"peer id" peer-id
                  "ip" "192.168.1.1"
                  "port" 6881}]
          result (tracker/parse-dictionary-peers peers)]
      (is (contains? result :ok))
      (is (= 1 (count (:ok result))))
      (is (= "192.168.1.1" (:ip (first (:ok result)))))
      (is (= 6881 (:port (first (:ok result)))))
      (is (= peer-id (:peer-id (first (:ok result)))))))

  (testing "parses peer list without peer IDs"
    (let [peers [{"ip" "10.0.0.1"
                  "port" 8080}]
          result (tracker/parse-dictionary-peers peers)]
      (is (contains? result :ok))
      (is (= 1 (count (:ok result))))
      (is (= "10.0.0.1" (:ip (first (:ok result)))))
      (is (= 8080 (:port (first (:ok result)))))
      (is (nil? (:peer-id (first (:ok result)))))))

  (testing "returns error for missing ip field"
    (let [peers [{"port" 6881}]
          result (tracker/parse-dictionary-peers peers)]
      (is (contains? result :error))
      (is (= :invalid-peer-format (:error result)))))

  (testing "returns error for missing port field"
    (let [peers [{"ip" "192.168.1.1"}]
          result (tracker/parse-dictionary-peers peers)]
      (is (contains? result :error))
      (is (= :invalid-peer-format (:error result)))))

  (testing "returns error for invalid port"
    (let [peers [{"ip" "192.168.1.1"
                  "port" 70000}]
          result (tracker/parse-dictionary-peers peers)]
      (is (contains? result :error))
      (is (= :invalid-peer-format (:error result))))))

;; ---------------------------------------------------------------------------
;; GROUP 3: HTTP tracker response parsing
;; ---------------------------------------------------------------------------

(deftest parse-http-tracker-response-compact-test
  (testing "parses HTTP response with compact peers"
    (let [response-data {"interval" 1800
                         "complete" 15
                         "incomplete" 42
                         "peers" (byte-array [192 168 1 1 0x1A 0xE1])}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (true? (:success (:ok result))))
      (is (= :http (:protocol (:ok result))))
      (is (= :announce (:response-type (:ok result))))
      (is (= 1800 (:interval (:ok result))))
      (is (= 15 (:complete (:ok result))))
      (is (= 42 (:incomplete (:ok result))))
      (is (= 1 (count (:peers (:ok result)))))
      (is (= "192.168.1.1" (:ip (first (:peers (:ok result))))))
      (is (= 6881 (:port (first (:peers (:ok result))))))))

  (testing "parses HTTP response with optional fields"
    (let [response-data {"interval" 1800
                         "min interval" 900
                         "tracker id" "test-tracker-123"
                         "warning message" "You are firewalled"
                         "complete" 10
                         "incomplete" 5
                         "peers" (byte-array [])}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (= 1800 (:interval (:ok result))))
      (is (= 900 (:min-interval (:ok result))))
      (is (= "test-tracker-123" (:tracker-id (:ok result))))
      (is (= "You are firewalled" (:warning-message (:ok result)))))))

(deftest parse-http-tracker-response-dictionary-test
  (testing "parses HTTP response with dictionary peers"
    (let [response-data {"interval" 1800
                         "complete" 5
                         "incomplete" 10
                         "peers" [{"ip" "192.168.1.1"
                                   "port" 6881
                                   "peer id" (byte-array 20)}]}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (true? (:success (:ok result))))
      (is (= 1 (count (:peers (:ok result)))))
      (is (= "192.168.1.1" (:ip (first (:peers (:ok result))))))
      (is (= 6881 (:port (first (:peers (:ok result))))))
      (is (bytes? (:peer-id (first (:peers (:ok result))))))))

  (testing "parses HTTP response with dictionary peers without peer IDs"
    (let [response-data {"interval" 1800
                         "complete" 5
                         "incomplete" 10
                         "peers" [{"ip" "10.0.0.1"
                                   "port" 8080}]}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (= 1 (count (:peers (:ok result)))))
      (is (nil? (:peer-id (first (:peers (:ok result)))))))))

(deftest parse-http-tracker-response-failure-test
  (testing "parses HTTP failure response"
    (let [response-data {"failure reason" "Torrent not found"}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (false? (:success (:ok result))))
      (is (= :http (:protocol (:ok result))))
      (is (= :announce (:response-type (:ok result))))
      (is (= "Torrent not found" (:failure-reason (:ok result))))))

  (testing "failure response takes precedence over other fields"
    (let [response-data {"failure reason" "Error occurred"
                         "interval" 1800
                         "peers" (byte-array [])}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (false? (:success (:ok result))))
      (is (= "Error occurred" (:failure-reason (:ok result))))
      ;; No other fields should be present in failure response
      (is (nil? (:interval (:ok result))))
      (is (nil? (:peers (:ok result)))))))

(deftest parse-http-tracker-response-error-handling-test
  (testing "returns error for invalid bencode"
    (let [invalid-bytes (to-bytes "not bencode")
          result (tracker/parse-http-tracker-response invalid-bytes)]
      (is (contains? result :error))))

  (testing "returns error for non-bytes input"
    (let [result (tracker/parse-http-tracker-response "not bytes")]
      (is (contains? result :error))
      (is (= :invalid-input (:error result)))))

  (testing "returns error for invalid peer data"
    (let [response-data {"interval" 1800
                         "peers" "invalid"}  ; String encodes to 7 bytes (not multiple of 6 or 18)
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :error))
      (is (= :invalid-peer-data (:error result))))))

(deftest parse-http-tracker-response-defaults-test
  (testing "uses default interval when not provided"
    (let [response-data {"peers" (byte-array [])}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (= 1800 (:interval (:ok result))))))

  (testing "uses default 0 for complete/incomplete when not provided"
    (let [response-data {"interval" 900
                         "peers" (byte-array [])}
          response-bytes (bencode/encode-bencode response-data)
          result (tracker/parse-http-tracker-response response-bytes)]
      (is (contains? result :ok))
      (is (= 0 (:complete (:ok result))))
      (is (= 0 (:incomplete (:ok result)))))))
