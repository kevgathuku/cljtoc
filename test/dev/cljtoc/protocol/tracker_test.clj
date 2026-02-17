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
