(ns dev.cljtoc.protocol.tracker-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as stest]
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

;; ---------------------------------------------------------------------------
;; GROUP 4: URL Encoding
;; ---------------------------------------------------------------------------

(deftest url-encode-binary-empty-test
  (testing "url-encode-binary handles empty byte array"
    (is (= "" (tracker/url-encode-binary (byte-array []))))))

(deftest url-encode-binary-ascii-test
  (testing "url-encode-binary preserves unreserved ASCII characters"
    (is (= "abc" (tracker/url-encode-binary (to-bytes "abc"))))
    (is (= "test.file-name_v1~"
           (tracker/url-encode-binary (to-bytes "test.file-name_v1~"))))))

(deftest url-encode-binary-hex-test
  (testing "url-encode-binary percent-encodes binary data"
    (is (= "%12%AB%FF"
           (tracker/url-encode-binary (byte-array [(unchecked-byte 0x12)
                                                   (unchecked-byte 0xAB)
                                                   (unchecked-byte 0xFF)]))))
    (is (= "%00%01%FE"
           (tracker/url-encode-binary (byte-array [(unchecked-byte 0x00)
                                                   (unchecked-byte 0x01)
                                                   (unchecked-byte 0xFE)]))))))

(defspec url-encode-binary-generative-test 100
  (testing "url-encode-binary is consistent and deterministic"
    (prop/for-all [data (gen/fmap byte-array
                                  (gen/vector (gen/choose -128 127) 1 50))]
      ;; Property: encoding same data twice produces same result
                  (= (tracker/url-encode-binary data)
                     (tracker/url-encode-binary data)))))

;; ---------------------------------------------------------------------------
;; GROUP 5: HTTP Tracker Request Building
;; ---------------------------------------------------------------------------

(deftest build-http-announce-url-required-params-test
  (testing "build-http-announce-url with required parameters only"
    (let [info-hash (byte-array 20)  ; All zeros
          peer-id (byte-array 20)
          result (tracker/build-http-announce-url
                  "http://tracker.example.com/announce"
                  {:info-hash info-hash
                   :peer-id peer-id
                   :port 6881
                   :uploaded 1024
                   :downloaded 2048
                   :left 4096})]
      (is (contains? result :ok))
      (let [url (:ok result)]
        (is (.startsWith url "http://tracker.example.com/announce?"))
        (is (.contains url "info_hash="))
        (is (.contains url "peer_id="))
        (is (.contains url "port=6881"))
        (is (.contains url "uploaded=1024"))
        (is (.contains url "downloaded=2048"))
        (is (.contains url "left=4096"))))))

(deftest build-http-announce-url-event-test
  (testing "build-http-announce-url with event parameter"
    (let [info-hash (byte-array 20)
          peer-id (byte-array 20)
          result (tracker/build-http-announce-url
                  "http://tracker.example.com/announce"
                  {:info-hash info-hash
                   :peer-id peer-id
                   :port 6881
                   :uploaded 0
                   :downloaded 0
                   :left 1000000
                   :event :started})]
      (is (contains? result :ok))
      (is (.contains (:ok result) "event=started")))))

(deftest build-http-announce-url-existing-query-test
  (testing "build-http-announce-url appends to existing query parameters"
    (let [info-hash (byte-array 20)
          peer-id (byte-array 20)
          result (tracker/build-http-announce-url
                  "http://tracker.example.com/announce?passkey=abc123"
                  {:info-hash info-hash
                   :peer-id peer-id
                   :port 6881
                   :uploaded 0
                   :downloaded 0
                   :left 0})]
      (is (contains? result :ok))
      (let [url (:ok result)]
        (is (.contains url "passkey=abc123"))
        (is (.contains url "&info_hash="))  ; Appended with &
        (is (not (.contains url "?info_hash=")))))))  ; Not with ?

(deftest build-http-announce-url-validation-test
  (testing "build-http-announce-url validates input"
    (let [invalid-hash (byte-array 10)  ; Wrong length!
          peer-id (byte-array 20)
          result (tracker/build-http-announce-url
                  "http://tracker.example.com/announce"
                  {:info-hash invalid-hash
                   :peer-id peer-id
                   :port 6881
                   :uploaded 0
                   :downloaded 0
                   :left 0})]
      (is (contains? result :error))
      (is (= :invalid-input (:error result))))))

(deftest build-http-announce-url-fdef-check-test
  (testing "build-http-announce-url conforms to fdef spec"
    (let [check-result (stest/check 'dev.cljtoc.protocol.tracker/build-http-announce-url
                                    {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))

;; ---------------------------------------------------------------------------
;; GROUP 6: UDP tracker response parsing (BEP 15)
;; ---------------------------------------------------------------------------

;; Binary builder helpers (test-only infrastructure)

(defn- make-connect-response-bytes [action transaction-id connection-id]
  (-> (java.nio.ByteBuffer/allocate 16)
      (.putInt action) (.putInt transaction-id) (.putLong connection-id)
      .array))

(defn- make-announce-response-bytes
  [action transaction-id interval leechers seeders peer-bytes]
  (let [buf (java.nio.ByteBuffer/allocate (+ 20 (alength peer-bytes)))]
    (.putInt buf action) (.putInt buf transaction-id)
    (.putInt buf interval) (.putInt buf leechers) (.putInt buf seeders)
    (.put buf peer-bytes)
    (.array buf)))

(defn- make-error-response-bytes [action transaction-id ^String message]
  (let [msg-bytes (.getBytes message "UTF-8")
        buf (java.nio.ByteBuffer/allocate (+ 8 (alength msg-bytes)))]
    (.putInt buf action) (.putInt buf transaction-id)
    (.put buf msg-bytes)
    (.array buf)))

(defn- make-scrape-response-bytes [action transaction-id scrape-entries]
  (let [buf (java.nio.ByteBuffer/allocate (+ 8 (* 12 (count scrape-entries))))]
    (.putInt buf action) (.putInt buf transaction-id)
    (doseq [{:keys [seeders completed leechers]} scrape-entries]
      (.putInt buf seeders) (.putInt buf completed) (.putInt buf leechers))
    (.array buf)))

;; T052: parse-udp-connect-response — valid message
(deftest parse-udp-connect-response-valid-test
  (testing "parses valid 16-byte connect response"
    (let [bytes (make-connect-response-bytes 0 42 0x41727101980)
          result (tracker/parse-udp-connect-response bytes)]
      (is (contains? result :ok))
      (is (= :connect (:action (:ok result))))
      (is (= 42 (:transaction-id (:ok result))))
      (is (= 0x41727101980 (:connection-id (:ok result))))))

  (testing "parses connect response with negative transaction-id (signed int)"
    (let [bytes (make-connect-response-bytes 0 -1 100)
          result (tracker/parse-udp-connect-response bytes)]
      (is (contains? result :ok))
      (is (= -1 (:transaction-id (:ok result))))
      (is (= 100 (:connection-id (:ok result)))))))

;; T053: parse-udp-connect-response — invalid action code
(deftest parse-udp-connect-response-invalid-action-test
  (testing "returns error for wrong action code"
    (let [bytes (make-connect-response-bytes 1 42 100)
          result (tracker/parse-udp-connect-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-action-code (:error result)))))

  (testing "returns error for wrong length (< 16 bytes)"
    (let [bytes (byte-array 8)
          result (tracker/parse-udp-connect-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-message-length (:error result)))))

  (testing "returns error for wrong length (> 16 bytes)"
    (let [bytes (byte-array 20)
          result (tracker/parse-udp-connect-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-message-length (:error result)))))

  (testing "returns error for non-bytes input"
    (let [result (tracker/parse-udp-connect-response "not bytes")]
      (is (contains? result :error))
      (is (= :invalid-input (:error result))))))

;; T054, T055: parse-udp-announce-response
(deftest parse-udp-announce-response-test
  (testing "parses announce response with no peers"
    (let [bytes (make-announce-response-bytes 1 99 1800 10 50 (byte-array 0))
          result (tracker/parse-udp-announce-response bytes)]
      (is (contains? result :ok))
      (is (= :announce (:action (:ok result))))
      (is (= 99 (:transaction-id (:ok result))))
      (is (= 1800 (:interval (:ok result))))
      (is (= 10 (:leechers (:ok result))))
      (is (= 50 (:seeders (:ok result))))
      (is (= [] (:peers (:ok result))))))

  (testing "parses announce response with IPv4 peers"
    (let [peer-bytes (byte-array [192 168 1 1 0x1A 0xE1])   ; 192.168.1.1:6881
          bytes (make-announce-response-bytes 1 7 900 3 12 peer-bytes)
          result (tracker/parse-udp-announce-response bytes)]
      (is (contains? result :ok))
      (is (= 1 (count (:peers (:ok result)))))
      (is (= "192.168.1.1" (:ip (first (:peers (:ok result))))))
      (is (= 6881 (:port (first (:peers (:ok result))))))))

  (testing "returns error for wrong action code"
    (let [bytes (make-announce-response-bytes 0 7 900 3 12 (byte-array 0))
          result (tracker/parse-udp-announce-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-action-code (:error result)))))

  (testing "returns error for too-short message"
    (let [bytes (byte-array 10)
          result (tracker/parse-udp-announce-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-message-length (:error result)))))

  (testing "returns error for non-bytes input"
    (let [result (tracker/parse-udp-announce-response nil)]
      (is (contains? result :error)))))

;; T056: parse-udp-error-response
(deftest parse-udp-error-response-test
  (testing "parses error response with message"
    (let [bytes (make-error-response-bytes 3 55 "Torrent not registered")
          result (tracker/parse-udp-error-response bytes)]
      (is (contains? result :ok))
      (is (= :error (:action (:ok result))))
      (is (= 55 (:transaction-id (:ok result))))
      (is (false? (:success (:ok result))))
      (is (= "Torrent not registered" (:failure-reason (:ok result))))))

  (testing "parses error response with empty message"
    (let [bytes (make-error-response-bytes 3 1 "")
          result (tracker/parse-udp-error-response bytes)]
      (is (contains? result :ok))
      (is (= "" (:failure-reason (:ok result))))))

  (testing "returns error for wrong action code"
    (let [bytes (make-error-response-bytes 0 1 "msg")
          result (tracker/parse-udp-error-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-action-code (:error result)))))

  (testing "returns error for too-short message"
    (let [bytes (byte-array 5)
          result (tracker/parse-udp-error-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-message-length (:error result)))))

  (testing "returns error for non-bytes input"
    (let [result (tracker/parse-udp-error-response "not bytes")]
      (is (contains? result :error))
      (is (= :invalid-input (:error result))))))

;; T057: parse-udp-scrape-response
(deftest parse-udp-scrape-response-test
  (testing "parses scrape response with one torrent"
    (let [entries [{:seeders 100 :completed 500 :leechers 20}]
          bytes (make-scrape-response-bytes 2 77 entries)
          result (tracker/parse-udp-scrape-response bytes)]
      (is (contains? result :ok))
      (is (= :scrape (:action (:ok result))))
      (is (= 77 (:transaction-id (:ok result))))
      (is (= 1 (count (:torrents (:ok result)))))
      (is (= 100 (:seeders (first (:torrents (:ok result))))))
      (is (= 500 (:completed (first (:torrents (:ok result))))))
      (is (= 20 (:leechers (first (:torrents (:ok result))))))))

  (testing "parses scrape response with multiple torrents"
    (let [entries [{:seeders 10 :completed 100 :leechers 5}
                   {:seeders 20 :completed 200 :leechers 8}]
          bytes (make-scrape-response-bytes 2 1 entries)
          result (tracker/parse-udp-scrape-response bytes)]
      (is (contains? result :ok))
      (is (= 2 (count (:torrents (:ok result)))))
      (is (= 20 (:seeders (second (:torrents (:ok result))))))
      (is (= 200 (:completed (second (:torrents (:ok result))))))))

  (testing "parses scrape response with no torrents"
    (let [bytes (make-scrape-response-bytes 2 1 [])
          result (tracker/parse-udp-scrape-response bytes)]
      (is (contains? result :ok))
      (is (= [] (:torrents (:ok result))))))

  (testing "returns error for wrong action code"
    (let [bytes (make-scrape-response-bytes 1 1 [])
          result (tracker/parse-udp-scrape-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-action-code (:error result)))))

  (testing "returns error for wrong length (not 8+12N)"
    (let [bytes (byte-array 11)   ; 11 bytes: not 8+12N for any N
          result (tracker/parse-udp-scrape-response bytes)]
      (is (contains? result :error))
      (is (= :invalid-message-length (:error result)))))

  (testing "returns error for non-bytes input"
    (let [result (tracker/parse-udp-scrape-response "not bytes")]
      (is (contains? result :error))
      (is (= :invalid-input (:error result))))))

;; T058: Generative tests for UDP parsers
(defspec parse-udp-connect-response-round-trip 100
  (prop/for-all [txid gen/int
                 cid  gen/large-integer]
                (let [bytes (make-connect-response-bytes 0 txid cid)
                      result (tracker/parse-udp-connect-response bytes)]
                  (and (contains? result :ok)
                       (= txid (:transaction-id (:ok result)))
                       (= cid  (:connection-id  (:ok result)))))))

(defspec parse-udp-announce-response-round-trip 50
  (prop/for-all [txid gen/int
                 interval (gen/fmap inc gen/nat)
                 leechers gen/nat
                 seeders  gen/nat]
                (let [bytes (make-announce-response-bytes 1 txid interval leechers seeders (byte-array 0))
                      result (tracker/parse-udp-announce-response bytes)]
                  (and (contains? result :ok)
                       (= txid     (:transaction-id (:ok result)))
                       (= interval (:interval (:ok result)))
                       (= leechers (:leechers (:ok result)))
                       (= seeders  (:seeders  (:ok result)))))))

(defspec parse-udp-scrape-response-round-trip 50
  (prop/for-all [txid gen/int
                 entries (gen/vector
                          (gen/fmap (fn [[s c l]] {:seeders s :completed c :leechers l})
                                    (gen/tuple gen/nat gen/nat gen/nat))
                          0 5)]
                (let [bytes (make-scrape-response-bytes 2 txid entries)
                      result (tracker/parse-udp-scrape-response bytes)]
                  (and (contains? result :ok)
                       (= txid (:transaction-id (:ok result)))
                       (= (count entries) (count (:torrents (:ok result))))
                       (every? (fn [[expected actual]]
                                 (and (= (:seeders expected)   (:seeders actual))
                                      (= (:completed expected) (:completed actual))
                                      (= (:leechers expected)  (:leechers actual))))
                               (map vector entries (:torrents (:ok result))))))))

(defspec parse-udp-error-response-round-trip 50
  (prop/for-all [txid gen/int
                 msg  gen/string-ascii]
                (let [bytes (make-error-response-bytes 3 txid msg)
                      result (tracker/parse-udp-error-response bytes)]
                  (and (contains? result :ok)
                       (= txid (:transaction-id (:ok result)))
                       (= msg  (:failure-reason (:ok result)))))))

;; ---------------------------------------------------------------------------
;; GROUP 7: UDP tracker request building (BEP 15) — T068-T072
;; ---------------------------------------------------------------------------

;; T068: build-udp-connect-request
(deftest build-udp-connect-request-test
  (testing "produces exactly 16 bytes"
    (let [result (tracker/build-udp-connect-request {:transaction-id 42})]
      (is (contains? result :ok))
      (is (= 16 (alength ^bytes (:ok result))))))

  (testing "encodes protocol magic at offset 0"
    (let [result (tracker/build-udp-connect-request {:transaction-id 99})
          buf    (java.nio.ByteBuffer/wrap (:ok result))]
      (is (= 0x41727101980 (.getLong buf)))))

  (testing "encodes action=0 at offset 8"
    (let [result (tracker/build-udp-connect-request {:transaction-id 1})
          buf    (java.nio.ByteBuffer/wrap (:ok result))]
      (.getLong buf)   ; skip magic
      (is (= 0 (.getInt buf)))))

  (testing "encodes transaction-id at offset 12"
    (let [txid   12345
          result (tracker/build-udp-connect-request {:transaction-id txid})
          buf    (java.nio.ByteBuffer/wrap (:ok result))]
      (.getLong buf)   ; skip magic
      (.getInt buf)    ; skip action
      (is (= txid (.getInt buf)))))

  (testing "returns error for missing transaction-id"
    (let [result (tracker/build-udp-connect-request {})]
      (is (contains? result :error))
      (is (= :invalid-input (:error result))))))

;; T069: build-udp-announce-request
(deftest build-udp-announce-request-test
  (let [info-hash (byte-array 20)
        peer-id   (byte-array 20)
        base-req  {:connection-id  0x41727101980
                   :transaction-id 7
                   :info-hash      info-hash
                   :peer-id        peer-id
                   :downloaded     1000
                   :left           500
                   :uploaded       200
                   :port           6881}]

    (testing "produces exactly 98 bytes"
      (let [result (tracker/build-udp-announce-request base-req)]
        (is (contains? result :ok))
        (is (= 98 (alength ^bytes (:ok result))))))

    (testing "encodes connection-id at offset 0"
      (let [result (tracker/build-udp-announce-request base-req)
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        (is (= 0x41727101980 (.getLong buf)))))

    (testing "encodes action=1 at offset 8"
      (let [result (tracker/build-udp-announce-request base-req)
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        (.getLong buf)  ; connection-id
        (is (= 1 (.getInt buf)))))

    (testing "encodes transaction-id at offset 12"
      (let [result (tracker/build-udp-announce-request base-req)
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        (.getLong buf)  ; connection-id
        (.getInt buf)   ; action
        (is (= 7 (.getInt buf)))))

    (testing "encodes event :started as code 2 at offset 80"
      (let [result (tracker/build-udp-announce-request (assoc base-req :event :started))
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        ;; Skip to offset 80
        (.getLong buf) (.getInt buf) (.getInt buf)  ; 0-15
        (let [ih (byte-array 20)] (.get buf ih))    ; 16-35 info-hash
        (let [pi (byte-array 20)] (.get buf pi))    ; 36-55 peer-id
        (.getLong buf) (.getLong buf) (.getLong buf) ; 56-79 downloaded/left/uploaded
        (is (= 2 (.getInt buf)))))

    (testing "defaults event code to 0 when :event is nil"
      (let [result (tracker/build-udp-announce-request base-req)
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        (.getLong buf) (.getInt buf) (.getInt buf)
        (let [ih (byte-array 20)] (.get buf ih))
        (let [pi (byte-array 20)] (.get buf pi))
        (.getLong buf) (.getLong buf) (.getLong buf)
        (is (= 0 (.getInt buf)))))

    (testing "returns error for missing required field"
      (let [result (tracker/build-udp-announce-request (dissoc base-req :port))]
        (is (contains? result :error))
        (is (= :invalid-input (:error result)))))

    (testing "returns error for invalid info-hash length"
      (let [result (tracker/build-udp-announce-request
                    (assoc base-req :info-hash (byte-array 10)))]
        (is (contains? result :error))
        (is (= :invalid-input (:error result)))))))

;; T070: build-udp-scrape-request
(deftest build-udp-scrape-request-test
  (let [cid    0x41727101980
        txid   55
        hash1  (byte-array 20)
        hash2  (byte-array 20)
        hash3  (byte-array 20)]

    (testing "1 info-hash → 36 bytes (16+20)"
      (let [result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid :info-hashes [hash1]})]
        (is (contains? result :ok))
        (is (= 36 (alength ^bytes (:ok result))))))

    (testing "3 info-hashes → 76 bytes (16+60)"
      (let [result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid
                     :info-hashes [hash1 hash2 hash3]})]
        (is (contains? result :ok))
        (is (= 76 (alength ^bytes (:ok result))))))

    (testing "encodes action=2 at offset 8"
      (let [result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid :info-hashes [hash1]})
            buf    (java.nio.ByteBuffer/wrap (:ok result))]
        (.getLong buf)  ; connection-id
        (is (= 2 (.getInt buf)))))

    (testing "info-hash bytes appear at offset 16"
      (let [marker (byte-array (map unchecked-byte (range 20)))
            result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid :info-hashes [marker]})
            bytes  (:ok result)]
        (is (= (seq marker) (seq (java.util.Arrays/copyOfRange bytes 16 36))))))

    (testing "returns error for empty info-hashes"
      (let [result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid :info-hashes []})]
        (is (contains? result :error))
        (is (= :invalid-input (:error result)))))

    (testing "returns error for missing info-hashes key"
      (let [result (tracker/build-udp-scrape-request
                    {:connection-id cid :transaction-id txid})]
        (is (contains? result :error))
        (is (= :invalid-input (:error result)))))))

;; T072: event code encoding
(deftest build-udp-announce-event-codes-test
  (let [base {:connection-id 1 :transaction-id 1 :info-hash (byte-array 20)
              :peer-id (byte-array 20) :downloaded 0 :left 0 :uploaded 0 :port 6881}]
    (letfn [(read-event-code [req]
              (let [buf (java.nio.ByteBuffer/wrap (:ok (tracker/build-udp-announce-request req)))]
                (.getLong buf) (.getInt buf) (.getInt buf)
                (let [ih (byte-array 20)] (.get buf ih))
                (let [pi (byte-array 20)] (.get buf pi))
                (.getLong buf) (.getLong buf) (.getLong buf)
                (.getInt buf)))]
      (testing "nil event → code 0"
        (is (= 0 (read-event-code base))))
      (testing ":completed → code 1"
        (is (= 1 (read-event-code (assoc base :event :completed)))))
      (testing ":started → code 2"
        (is (= 2 (read-event-code (assoc base :event :started)))))
      (testing ":stopped → code 3"
        (is (= 3 (read-event-code (assoc base :event :stopped))))))))

;; T071: generative round-trip tests
(defspec build-udp-connect-request-round-trip 100
  (prop/for-all [txid gen/int]
                (let [result (tracker/build-udp-connect-request {:transaction-id txid})]
                  (and (contains? result :ok)
                       (let [buf (java.nio.ByteBuffer/wrap (:ok result))]
                         (and (= 16 (alength ^bytes (:ok result)))
                              (= 0x41727101980 (.getLong buf))
                              (= 0 (.getInt buf))
                              (= txid (.getInt buf))))))))

(defspec build-udp-announce-request-round-trip 50
  (prop/for-all [cid    gen/large-integer
                 txid   gen/int
                 port   (gen/choose 1 65535)
                 dld    gen/nat
                 lft    gen/nat
                 upl    gen/nat]
                (let [req    {:connection-id cid :transaction-id txid
                              :info-hash (byte-array 20) :peer-id (byte-array 20)
                              :downloaded dld :left lft :uploaded upl :port port}
                      result (tracker/build-udp-announce-request req)]
                  (and (contains? result :ok)
                       (= 98 (alength ^bytes (:ok result)))
                       (let [buf (java.nio.ByteBuffer/wrap (:ok result))]
                         (and (= (long cid) (.getLong buf))
                              (= 1 (.getInt buf))
                              (= txid (.getInt buf))))))))

(defspec build-udp-scrape-request-round-trip 50
  (prop/for-all [cid      gen/large-integer
                 txid     gen/int
                 n-hashes (gen/choose 1 5)]
                (let [hashes (vec (repeatedly n-hashes #(byte-array 20)))
                      req    {:connection-id cid :transaction-id txid :info-hashes hashes}
                      result (tracker/build-udp-scrape-request req)]
                  (and (contains? result :ok)
                       (= (+ 16 (* 20 n-hashes)) (alength ^bytes (:ok result)))
                       (let [buf (java.nio.ByteBuffer/wrap (:ok result))]
                         (and (= (long cid) (.getLong buf))
                              (= 2 (.getInt buf))
                              (= txid (.getInt buf))))))))

;; ---------------------------------------------------------------------------
;; GROUP 8: US5 Error Handling (T080-T083)
;; ---------------------------------------------------------------------------

;; T080: Verify distinct error keywords across parsers
(deftest error-type-classification-test
  (testing "validate-input produces :invalid-input"
    (let [result (tracker/validate-input bytes? "not bytes")]
      (is (= :invalid-input (:error result)))))

  (testing "parse-compact-peers-ipv4 produces :invalid-peer-data for bad length"
    (let [result (tracker/parse-compact-peers-ipv4 (byte-array 3))]
      (is (= :invalid-peer-data (:error result)))))

  (testing "parse-udp-connect-response produces :invalid-action-code for wrong action"
    (let [result (tracker/parse-udp-connect-response (make-connect-response-bytes 1 42 100))]
      (is (= :invalid-action-code (:error result)))))

  (testing "parse-udp-connect-response produces :invalid-message-length for wrong size"
    (let [result (tracker/parse-udp-connect-response (byte-array 8))]
      (is (= :invalid-message-length (:error result)))))

  (testing "parse-dictionary-peers produces :invalid-peer-format for missing ip"
    (let [result (tracker/parse-dictionary-peers [{"port" 6881}])]
      (is (= :invalid-peer-format (:error result))))))

;; T081/T082: Verify all error maps have required :error and :message keys
;; and satisfy ::tracker-error spec
(deftest error-map-structure-test
  (let [error-cases [(tracker/validate-input bytes? "not bytes")
                     (tracker/parse-compact-peers-ipv4 (byte-array 3))
                     (tracker/parse-udp-connect-response (byte-array 8))
                     (tracker/parse-udp-connect-response (make-connect-response-bytes 1 0 0))
                     (tracker/parse-dictionary-peers [{"port" 6881}])]]
    (doseq [err error-cases]
      (testing (str "error map has :error and :message: " err)
        (is (contains? err :error))
        (is (contains? err :message))
        (is (s/valid? ::spec/tracker-error err))))))

;; T083: Verify :spec-explain is present in validate-input errors
(deftest validate-input-spec-explain-test
  (testing "validate-input includes :spec-explain for registered spec failures"
    (let [result (tracker/validate-input ::spec/info-hash (byte-array 10))]
      (is (= :invalid-input (:error result)))
      (is (contains? result :spec-explain))
      (is (some? (:spec-explain result))))))

;; T083 generative: all validate-input error maps satisfy ::tracker-error
(defspec error-maps-satisfy-spec 50
  (prop/for-all [bad-len (gen/choose 1 19)]
                (let [result (tracker/validate-input ::spec/info-hash (byte-array bad-len))]
                  (s/valid? ::spec/tracker-error result))))

;; ---------------------------------------------------------------------------
;; GROUP 9: US6 Re-Announce Timing (T090-T096)
;; ---------------------------------------------------------------------------

;; T090-T092: calculate-next-announce
(deftest calculate-next-announce-test
  (testing "T090: uses interval-seconds when min-interval is nil"
    (let [result (tracker/calculate-next-announce 1000000 1800 nil)]
      (is (= {:ok 2800000} result))))

  (testing "T091: uses min-interval-seconds when present (prefers min-interval)"
    (let [result (tracker/calculate-next-announce 1000000 1800 300)]
      (is (= {:ok 1300000} result))))

  (testing "T092: uses default 1800 when both interval and min-interval are nil"
    (let [result (tracker/calculate-next-announce 1000000 nil nil)]
      (is (= {:ok 2800000} result)))))

;; T093-T094: calculate-exponential-backoff
(deftest calculate-exponential-backoff-test
  (testing "T093: exponential growth (1s, 2s, 4s, 8s)"
    (is (= {:ok 1000} (tracker/calculate-exponential-backoff 1)))
    (is (= {:ok 2000} (tracker/calculate-exponential-backoff 2)))
    (is (= {:ok 4000} (tracker/calculate-exponential-backoff 3)))
    (is (= {:ok 8000} (tracker/calculate-exponential-backoff 4))))

  (testing "T094: capped at max 1 hour (3600000 ms)"
    (let [result (tracker/calculate-exponential-backoff 100)]
      (is (= {:ok 3600000} result)))))

;; T095: update-schedule-success
(deftest update-schedule-success-test
  (testing "T095: resets retry-attempt to 0 and updates schedule"
    (let [schedule {:next-announce-time 0
                    :interval-seconds 1800
                    :retry-attempt 3
                    :backoff-delay-ms 4000}
          result (tracker/update-schedule-success schedule 900 1000000)]
      (is (contains? result :ok))
      (let [new-sched (:ok result)]
        (is (= 0 (:retry-attempt new-sched)))
        (is (= 0 (:backoff-delay-ms new-sched)))
        (is (= 1900000 (:next-announce-time new-sched)))
        (is (= 900 (:interval-seconds new-sched)))))))

;; T096: update-schedule-failure
(deftest update-schedule-failure-test
  (testing "T096: increments retry-attempt and applies exponential backoff"
    (let [schedule {:next-announce-time 0
                    :interval-seconds 1800
                    :retry-attempt 2
                    :backoff-delay-ms 2000}
          result (tracker/update-schedule-failure schedule 5000)]
      (is (contains? result :ok))
      (let [new-sched (:ok result)]
        (is (= 3 (:retry-attempt new-sched)))
        (is (= 4000 (:backoff-delay-ms new-sched)))
        (is (= 9000 (:next-announce-time new-sched)))))))

;; Generative: result :ok > current-time-ms
(defspec calculate-next-announce-round-trip 100
  (prop/for-all [t        gen/nat
                 interval (gen/fmap inc gen/nat)]
                (let [result (tracker/calculate-next-announce t interval nil)]
                  (> (:ok result) t))))

;; Generative: failure always increments retry-attempt by 1
(defspec update-schedule-failure-increments-attempt 50
  (prop/for-all [schedule (s/gen ::spec/announce-schedule)]
                (let [result (tracker/update-schedule-failure schedule 0)]
                  (and (contains? result :ok)
                       (= (inc (:retry-attempt schedule))
                          (:retry-attempt (:ok result)))))))

;; ---------------------------------------------------------------------------
;; GROUP 10: Integration Tests (T109-T110)
;; ---------------------------------------------------------------------------

;; T109: HTTP round-trip — build URL then parse a simulated response
(deftest http-round-trip-integration-test
  (testing "T109: build HTTP announce URL and parse simulated tracker response"
    (let [info-hash  (byte-array 20)
          peer-id    (byte-array 20)
          url-result (tracker/build-http-announce-url
                      "http://tracker.example.com/announce"
                      {:info-hash info-hash :peer-id peer-id
                       :port 6881 :uploaded 0 :downloaded 0 :left 1000})]
      (is (contains? url-result :ok))
      (is (.startsWith (:ok url-result) "http://tracker.example.com/announce"))
      ;; Simulate tracker response
      (let [response-data  {"interval" 900
                            "complete" 5
                            "incomplete" 10
                            "peers" (byte-array [192 168 1 1 0x1A 0xE1])}
            response-bytes (bencode/encode-bencode response-data)
            parse-result   (tracker/parse-http-tracker-response response-bytes)]
        (is (contains? parse-result :ok))
        (is (= :http (:protocol (:ok parse-result))))
        (is (= :announce (:response-type (:ok parse-result))))
        (is (= 900 (:interval (:ok parse-result))))
        (is (= 1 (count (:peers (:ok parse-result)))))
        (is (= "192.168.1.1" (:ip (first (:peers (:ok parse-result))))))
        (is (= 6881 (:port (first (:peers (:ok parse-result))))))))))

;; T110: UDP round-trip — build request, parse simulated response
(deftest udp-round-trip-integration-test
  (testing "T110: build UDP connect request, parse simulated connect response"
    (let [txid       12345
          conn-id    0x41727101980
          req-result (tracker/build-udp-connect-request {:transaction-id txid})]
      (is (contains? req-result :ok))
      (is (= 16 (alength ^bytes (:ok req-result))))
      (let [resp-bytes   (make-connect-response-bytes 0 txid conn-id)
            parse-result (tracker/parse-udp-connect-response resp-bytes)]
        (is (contains? parse-result :ok))
        (is (= :connect (:action (:ok parse-result))))
        (is (= txid (:transaction-id (:ok parse-result))))
        (is (= conn-id (:connection-id (:ok parse-result)))))))

  (testing "UDP announce request/response cycle"
    (let [info-hash  (byte-array 20)
          peer-id    (byte-array 20)
          txid       99
          conn-id    0x41727101980
          req-result (tracker/build-udp-announce-request
                      {:connection-id conn-id :transaction-id txid
                       :info-hash info-hash :peer-id peer-id
                       :downloaded 0 :left 1000 :uploaded 0 :port 6881})]
      (is (contains? req-result :ok))
      (is (= 98 (alength ^bytes (:ok req-result))))
      (let [peer-bytes   (byte-array [10 0 0 1 0x1F 0x90])  ; 10.0.0.1:8080
            resp-bytes   (make-announce-response-bytes 1 txid 1800 5 20 peer-bytes)
            parse-result (tracker/parse-udp-announce-response resp-bytes)]
        (is (contains? parse-result :ok))
        (is (= :announce (:action (:ok parse-result))))
        (is (= txid (:transaction-id (:ok parse-result))))
        (is (= 1800 (:interval (:ok parse-result))))
        (is (= 1 (count (:peers (:ok parse-result)))))
        (is (= "10.0.0.1" (:ip (first (:peers (:ok parse-result))))))))))

;; ---------------------------------------------------------------------------
;; GROUP 11: Tracker fan-out policy (issue #44)
;; ---------------------------------------------------------------------------
;; Seam: pure ordering + merge policy. No sockets: ordering derives from
;; declared metadata, merging is set-union so late arrivals fold in.

(deftest pick-tracker-order-test
  (testing "primary announce comes first, then announce-list tiers flattened"
    (let [order (tracker/pick-tracker-order
                 {:announce "http://primary.example.com/announce"
                  :announce-list [["http://tier1a.example.com" "http://tier1b.example.com"]
                                  ["udp://tier2.example.com:6969"]]})]
      (is (= "http://primary.example.com/announce" (first order)))
      (is (= ["http://primary.example.com/announce"
              "http://tier1a.example.com"
              "http://tier1b.example.com"
              "udp://tier2.example.com:6969"]
             (take 4 order)))))

  (testing "deduplicates while keeping first occurrence"
    (let [order (tracker/pick-tracker-order
                 {:announce "http://a.example.com"
                  :announce-list [["http://a.example.com" "http://b.example.com"]]})]
      (is (= 1 (count (filter #(= "http://a.example.com" %) order))))
      (is (= "http://b.example.com" (second order)))))

  (testing "drops non-http/non-udp and nil entries"
    (let [order (tracker/pick-tracker-order
                 {:announce nil
                  :announce-list [[nil "ftp://files.example.com" "http://ok.example.com"]]})]
      (is (not (some #(= "ftp://files.example.com" %) order)))
      (is (some #(= "http://ok.example.com" %) order))))

  (testing "appends well-known public fallbacks after declared trackers"
    (let [order (tracker/pick-tracker-order {:announce "http://mine.example.com"})]
      (is (= "http://mine.example.com" (first order)))
      (is (some #(= "udp://tracker.opentrackr.org:1337" %) order)))))

(deftest combine-peers-test
  (testing "union of address sets"
    (is (= #{"a:1" "b:2" "c:3"}
             (tracker/combine-peers #{"a:1" "b:2"} #{"b:2" "c:3"}))))

  (testing "empty inputs stay empty"
    (is (= #{} (tracker/combine-peers))))

  (testing "late arrivals fold into already-dialed sets incrementally"
    (let [dialed (tracker/combine-peers #{"a:1"} #{"b:2"})
          merged (tracker/combine-peers dialed #{"c:3"})]
      (is (= #{"a:1" "b:2" "c:3"} merged))))

  (testing "single set returns itself as a set"
    (is (= #{"a:1"} (tracker/combine-peers ["a:1"])))))
