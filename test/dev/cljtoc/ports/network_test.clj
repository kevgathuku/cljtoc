(ns dev.cljtoc.ports.network-test
  "Contract tests for the peer and tracker ports.
   Seam: INetworkPort / ITrackerPort hand back their envelope directly, so
   no caller has to know about core.async to learn a result.

   The five INetworkPort methods are all driven without touching the
   network: connect-peer gets a blank address, which is refused before any
   socket work, and the read and write methods get byte-array streams.
   announce is exercised on the mock only -- the real one always reaches
   for a tracker, because pick-tracker-order appends well-known public
   fallbacks, so there is no offline input that stops it short. The
   per-URL announce-to-url has an offline error path on the real port
   (a malformed URL fails before any socket work)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [dev.cljtoc.domain.bencode :as bencode]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.protocol.tracker :as tracker]
            [dev.cljtoc.test-utils :refer [an-envelope? channel?] :as test-utils]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.test-doubles.network :as mock-network])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            InputStream OutputStream]
           [java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress
            SocketException SocketTimeoutException]
           [java.nio ByteBuffer]
           [java.util Arrays]))

(def ^:private valid-handshake-bytes
  (:ok (peer/build-handshake (byte-array 20) (byte-array 20))))

(def ^:private keep-alive-prefix (byte-array [0 0 0 0]))

(defn- peer-in [bytes]
  {:id "p" :in (ByteArrayInputStream. bytes)})

(def ^:private peer-calls
  "The five INetworkPort methods, each drivable with no network at all:
   connect-peer gets a blank address, which is refused before any socket
   work, and the read and write methods get byte-array streams."
  {:connect-peer #(network/connect-peer % "")
   :send-message #(network/send-message % {:id "p" :out (ByteArrayOutputStream.)} valid-handshake-bytes)
   :receive-message #(network/receive-message % (peer-in keep-alive-prefix))
   :receive-handshake #(network/receive-handshake % (peer-in valid-handshake-bytes))
   :close-peer #(network/close-peer % {:id "p" :socket nil})})

(deftest network-port-returns-envelopes-not-channels-test
  (testing "every INetworkPort method hands back its result directly, not a channel"
    (doseq [[method call] peer-calls]
      (let [result (call (network-impl/create))]
        (is (not (channel? result))
            (str method " returned a channel, not a result"))
        ;; close-peer is the one method documented to return nil rather
        ;; than an envelope, so it is exempt from the shape check.
        (when-not (= :close-peer method)
          (is (an-envelope? result)
              (str method " returned neither :ok nor :error: " (pr-str result)))))))
  (testing "and the mock mirrors the real port's contract, method for method"
    (doseq [[method call] (assoc peer-calls :announce #(network/announce % {} {:downloaded 0 :left 0}))]
      (let [result (call (mock-network/create))]
        (is (not (channel? result))
            (str "mock " method " returned a channel, not a result"))
        (when-not (= :close-peer method)
          (is (an-envelope? result)
              (str "mock " method " returned neither :ok nor :error: " (pr-str result))))))))

(deftest the-mock-agrees-with-the-real-port-on-the-sent-value-test
  (testing "send-message's documented success value is {:ok :sent}; the real
            port and mock should agree so this contract cannot drift."
    (let [peer-data {:id "p" :out (ByteArrayOutputStream.)}]
      (is (= {:ok :sent}
             (network/send-message (network-impl/create) peer-data valid-handshake-bytes)))
      (is (= {:ok :sent}
             (network/send-message (mock-network/create) peer-data valid-handshake-bytes))))))

(deftest refused-connect-yields-an-error-envelope-test
  (testing "a bad peer address is an {:error :invalid-address} envelope, not a throw
            and not a channel"
    (let [real (network/connect-peer (network-impl/create) "")]
      (is (= :invalid-address (:error real)))
      (is (string? (:message real))))))

;; The send and receive methods were the only ones whose try/catch wrapped a
;; channel put rather than a return. Each catch is pinned here so moving the
;; body out of the go block cannot be a silent loss of an error path.

(defn- failing-input-stream
  "A stream whose every read throws, so the enclosing catch is the only thing
   standing between the call and a stack trace."
  [ex]
  (proxy [InputStream] []
    (read
      ([] (throw ex))
      ([_] (throw ex))
      ([_ _ _] (throw ex)))))

(defn- failing-output-stream
  "The write-side counterpart of failing-input-stream."
  [ex]
  (proxy [OutputStream] []
    (write
      ([_] (throw ex))
      ([_ _] (throw ex)))
    (flush [] nil)))

(deftest a-truncated-read-is-an-envelope-not-a-throw-test
  (testing "a peer that stops mid-read yields :disconnected, not an EOF escaping the port"
    (let [net (network-impl/create)]
      (is (= :disconnected
             (:error (network/receive-handshake net (peer-in (byte-array 10))))))
      (is (= :disconnected
             (:error (network/receive-message net (peer-in (byte-array 2)))))))))

(deftest a-hostile-length-prefix-is-an-envelope-not-a-throw-test
  (testing "a negative declared length yields :receive-failed: the framing
            allocation throws where every byte was delivered, so only the
            port's catch stands between the call and a stack trace"
    (let [result (network/receive-message
                  (network-impl/create)
                  ;; 0xFFFFFFFF big-endian: bytes-to-int32 reads -1.
                  (peer-in (byte-array [-1 -1 -1 -1])))]
      (is (= :receive-failed (:error result)) (pr-str result)))))

(deftest a-valid-handshake-comes-back-parsed-test
  (testing "the port's success return is what this exercises. 68 zero bytes
            parse to :unsupported-protocol, and an envelope check would have
            passed on that error branch without ever reaching the success
            path this issue changed."
    (let [result (network/receive-handshake
                  (network-impl/create)
                  (peer-in valid-handshake-bytes))]
      (is (some? (:ok result)) (pr-str result))
      (is (instance? dev.cljtoc.protocol.peer.PeerHandshake (:ok result))))))

(deftest a-failing-stream-is-mapped-to-its-reason-test
  (testing "each catch clause still fires, now that it wraps a return instead of a put"
    (let [net (network-impl/create)]
      (is (= :send-failed
             (:error (network/send-message
                      net
                      {:id "p" :out (failing-output-stream (java.io.IOException. "boom"))}
                      valid-handshake-bytes))))
      (is (= :receive-failed
             (:error (network/receive-handshake
                      net
                      {:id "p" :in (failing-input-stream (java.io.IOException. "boom"))}))))
      (is (= :receive-failed
             (:error (network/receive-message
                      net
                      {:id "p" :in (failing-input-stream (java.io.IOException. "boom"))}))))))
  (testing "a read timeout is reported as :timeout, not swallowed into a generic failure"
    (is (= :timeout
           (:error (network/receive-handshake
                    (network-impl/create)
                    {:id "p" :in (failing-input-stream
                                  (java.net.SocketTimeoutException. "slow"))}))))))

(deftest timeouts-come-from-adapter-config-test
  (testing "defaults match the historical literals when no config is given"
    (let [timeout-ms @#'network-impl/timeout-ms
          net (network-impl/create)]
      (is (= 5000 (timeout-ms net :connect-timeout-ms)))
      (is (= 10000 (timeout-ms net :socket-timeout-ms)))
      (is (= 5000 (timeout-ms net :udp-timeout-ms)))
      (is (= 10000 (timeout-ms net :http-timeout-ms)))))
  (testing "explicit config overrides the defaults"
    (let [timeout-ms @#'network-impl/timeout-ms
          net (network-impl/create {:connect-timeout-ms 1
                                    :socket-timeout-ms 2
                                    :udp-timeout-ms 3
                                    :http-timeout-ms 4})]
      (is (= 1 (timeout-ms net :connect-timeout-ms)))
      (is (= 2 (timeout-ms net :socket-timeout-ms)))
      (is (= 3 (timeout-ms net :udp-timeout-ms)))
      (is (= 4 (timeout-ms net :http-timeout-ms)))))
  (testing "a configured connect timeout reaches the socket connect"
    ;; 192.0.2.1 is TEST-NET-1 (RFC 5737): unroutable, so the connect can
    ;; only return via timeout or immediate refusal. With 50ms configured,
    ;; a run honoring the default 5000ms would take ~5000ms where a route
    ;; exists; bounding at 4000ms discriminates the two.
    (let [net (network-impl/create {:connect-timeout-ms 50})
          start (System/currentTimeMillis)
          result (network/connect-peer net "192.0.2.1:6881")
          elapsed (- (System/currentTimeMillis) start)]
      (is (= :connect-failed (:error result)))
      (is (< elapsed 4000) (str "took " elapsed "ms; configured timeout was not honored")))))

(deftest logging-goes-through-adapter-config-test
  (testing "log-fn defaults to println and honors :log-fn on both ports"
    (is (= println (network/log-fn (network-impl/create))))
    (is (= println (network/log-fn (mock-network/create))))
    (let [capture (fn [_] nil)]
      (is (= capture (network/log-fn (network-impl/create {:log-fn capture}))))
      (is (= capture (network/log-fn (mock-network/create {:log-fn capture}))))))
  (testing "the real port logs connects through the configured fn"
    (let [logged (atom [])
          net (network-impl/create {:log-fn (fn [msg] (swap! logged conj msg))})
          result (network/connect-peer net "")]
      (is (= :invalid-address (:error result)))
      (is (= 1 (count @logged)))
      (is (re-find #"Attempting to connect" (first @logged))))))

(deftest configured-timeouts-land-on-the-socket-test
  (testing "connect-peer applies :socket-timeout-ms to the live socket"
    ;; 4321 matches no literal in the implementation, so equality proves
    ;; the configured value (not a default) reached the socket object.
    (with-open [server (java.net.ServerSocket. 0)]
      (let [address (str "127.0.0.1:" (.getLocalPort server))
            net (network-impl/create {:socket-timeout-ms 4321
                                      :connect-timeout-ms 2000})
            result (network/connect-peer net address)]
        (if (:error result)
          (is (nil? (:error result)) (str "loopback connect failed: " (pr-str result)))
          (try
            (is (= 4321 (.getSoTimeout ^java.net.Socket (:socket (:ok result)))))
            (finally (network/close-peer net (:ok result))))))))
  (testing "and the default is the historical 10000ms"
    (with-open [server (java.net.ServerSocket. 0)]
      (let [address (str "127.0.0.1:" (.getLocalPort server))
            result (network/connect-peer (network-impl/create) address)]
        (if (:error result)
          (is (nil? (:error result)) (str "loopback connect failed: " (pr-str result)))
          (try
            (is (= 10000 (.getSoTimeout ^java.net.Socket (:socket (:ok result)))))
            (finally (network/close-peer (network-impl/create) (:ok result)))))))))

;; check-adapter-config throws by design, so it is excluded from stest/check
;; (generated invalid configs would fail the check by construction); the
;; mutation tables plus the spec-conformance test below are its coverage.
(deftest invalid-adapter-opts-are-rejected-at-creation-test
  (testing "present-but-invalid timeouts throw instead of reaching the socket APIs"
    ;; Mutation vocabulary is shape-diverse by construction: zero (the
    ;; infinite-timeout hole), negative, string, double, nil, and
    ;; beyond-Java-int-range each exercise a different subform of the guard.
    (doseq [bad [0 -1 "5000" 1.5 nil (inc Integer/MAX_VALUE)]
            timeout-key [:connect-timeout-ms :socket-timeout-ms
                         :udp-timeout-ms :http-timeout-ms]
            [label make] [["real" network-impl/create]
                          ["mock" mock-network/create]]]
      (let [err (try (make {timeout-key bad}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? err)
            (str label " port accepted " timeout-key "=" (pr-str bad)))
        (when (some? err)
          (is (re-find #"Invalid network adapter opt" (ex-message err)))
          (is (= timeout-key (:key (ex-data err))))
          (is (= bad (:value (ex-data err))))))))
  (testing "valid timeouts (including the int boundary) and absent keys pass through"
    (let [opts {:connect-timeout-ms 1
                :socket-timeout-ms 2
                :udp-timeout-ms 3
                :http-timeout-ms Integer/MAX_VALUE}]
      (is (= opts (:config (network-impl/create opts))))
      (is (= opts (:config (mock-network/create opts))))
      (is (= {} (:config (network-impl/create))))))
  (testing ":log-fn must be a fn when present"
    (doseq [bad ["x" 42 nil 0]
            [label make] [["real" network-impl/create]
                          ["mock" mock-network/create]]]
      (let [err (try (make {:log-fn bad}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? err)
            (str label " port accepted :log-fn=" (pr-str bad)))
        (when (some? err)
          (is (re-find #"Invalid network adapter opt" (ex-message err)))
          (is (= :log-fn (:key (ex-data err)))))))
    (let [capture (fn [_] nil)]
      (is (= {:log-fn capture} (:config (network-impl/create {:log-fn capture}))))
      (is (= {:log-fn capture} (:config (mock-network/create {:log-fn capture})))))))

(deftest logging-failures-never-break-the-effect-path-test
  (testing "a throwing :log-fn is swallowed at every log site"
    (let [throwing (fn [_] (throw (ex-info "boom" {})))]
      (testing "connect still parses instead of misreporting :connect-failed"
        (let [result (network/connect-peer
                      (network-impl/create {:log-fn throwing}) "")]
          (is (= :invalid-address (:error result))
              (str "throwing logger escaped connect: " (pr-str result)))))
      (testing "the shared log! returns nil instead of throwing"
        (is (nil? (network/log! (network-impl/create {:log-fn throwing}) "msg"))))
      (testing "the mock honors the same contract"
        (is (nil? (network/log! (mock-network/create {:log-fn throwing}) "msg")))))))

(deftest adapter-config-spec-matches-the-checker-test
  (testing "s/def shape and check-adapter-config agree on fixed batteries"
    (let [accepts? (fn [cfg]
                     (try (network/check-adapter-config cfg) true
                          (catch clojure.lang.ExceptionInfo _ false)))
          valid-cfgs [{} {:connect-timeout-ms 1} {:log-fn println}
                      {:connect-timeout-ms 1 :socket-timeout-ms 2
                       :udp-timeout-ms 3 :http-timeout-ms Integer/MAX_VALUE}
                      {:unrelated-key "ignored"}]
          invalid-cfgs (concat (for [bad [0 -1 "5000" 1.5 nil (inc Integer/MAX_VALUE)]
                                     timeout-key [:connect-timeout-ms :socket-timeout-ms
                                                  :udp-timeout-ms :http-timeout-ms]]
                                 {timeout-key bad})
                               (for [bad ["x" 42 nil 0]] {:log-fn bad}))]
      (doseq [cfg valid-cfgs]
        (is (s/valid? ::network/adapter-config cfg)
            (str "spec rejected " (pr-str cfg)))
        (is (accepts? cfg)
            (str "checker rejected " (pr-str cfg))))
      (doseq [cfg invalid-cfgs]
        (is (not (s/valid? ::network/adapter-config cfg))
            (str "spec accepted " (pr-str cfg)))
        (is (not (accepts? cfg))
            (str "checker accepted " (pr-str cfg))))))
  (testing "every generated valid config passes the checker"
    (doseq [cfg (gen/sample (s/gen ::network/adapter-config) 50)]
      (is (= cfg (network/check-adapter-config cfg))
          (str "checker rejected generated " (pr-str cfg))))))

(deftest udp-retry-opts-are-validated-at-creation-test
  (testing "present-but-invalid retry opts throw instead of silently changing retry behavior"
    ;; :udp-retry-base-delay-ms shares the timeout shape (positive int ms
    ;; within Java int range); :udp-max-attempts is a loop count, so any
    ;; positive int retries and only non-positive/non-int is refused.
    (doseq [bad [0 -1 "5000" 1.5 nil (inc Integer/MAX_VALUE)]
            [label make] [["real" network-impl/create]
                          ["mock" mock-network/create]]]
      (let [err (try (make {:udp-retry-base-delay-ms bad}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? err)
            (str label " port accepted :udp-retry-base-delay-ms=" (pr-str bad)))
        (when (some? err)
          (is (re-find #"Invalid network adapter opt" (ex-message err)))
          (is (= :udp-retry-base-delay-ms (:key (ex-data err)))))))
    (doseq [bad [0 -1 "3" 2.5 nil]
            [label make] [["real" network-impl/create]
                          ["mock" mock-network/create]]]
      (let [err (try (make {:udp-max-attempts bad}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? err)
            (str label " port accepted :udp-max-attempts=" (pr-str bad)))
        (when (some? err)
          (is (re-find #"Invalid network adapter opt" (ex-message err)))
          (is (= :udp-max-attempts (:key (ex-data err))))))))
  (testing "valid retry opts pass through on both ports"
    (let [opts {:udp-retry-base-delay-ms 10 :udp-max-attempts 2}]
      (is (= opts (:config (network-impl/create opts))))
      (is (= opts (:config (mock-network/create opts)))))))

(deftest udp-backoff-delay-doubles-per-retry-test
  (testing "BEP 15 backoff: base * 2^n for the nth retry"
    ;; The oracle is arithmetic written out by hand, not the fn itself.
    (is (= 15000 (#'network-impl/backoff-delay-ms 15000 0)))
    (is (= 30000 (#'network-impl/backoff-delay-ms 15000 1)))
    (is (= 60000 (#'network-impl/backoff-delay-ms 15000 2)))
    (is (= 120000 (#'network-impl/backoff-delay-ms 15000 3)))
    (is (= 10 (#'network-impl/backoff-delay-ms 10 0))))
  (testing "astronomical shifts saturate instead of wrapping negative"
    (is (= Long/MAX_VALUE (#'network-impl/backoff-delay-ms Integer/MAX_VALUE 100)))
    (is (pos? (#'network-impl/backoff-delay-ms 15000 62)))))

(deftest fdef-specs-hold-generatively-test
  (testing "log-fn fdef holds over generated inputs"
    ;; announce-to-url is excluded on principle, like log!: the generator
    ;; cannot conjure a live tracker URL, so a check would die in socket
    ;; I/O before its :ret is even reached. try-udp-step shares the
    ;; exclusion (it needs a live socket and a answering peer), and
    ;; sleep-retry-delay! is excluded because generated delays would
    ;; block the test thread for unbounded real time.
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.ports.network/log-fn
                      dev.cljtoc.ports.network-impl/backoff-delay-ms]
                    50)]
      (is (empty? failures)
          (str "fdef check failures: " (pr-str failures))))))

(deftest a-full-length-message-is-parsed-test
  (testing "a non-zero length prefix reads exactly its declared payload and parses it.
            Per BEP 3 a 1-byte body of 2 is an Interested, so the oracle is the
            wire spec, not whatever the parser happens to return."
    (let [result (network/receive-message
                  (network-impl/create)
                  (peer-in (byte-array [0 0 0 1 2])))]
      (is (nil? (:error result)) (pr-str result))
      (is (instance? dev.cljtoc.protocol.peer.Interested (:ok result))))))

;; Issue #44: the per-URL seam streaming consumes. announce-to-url queries
;; ONE tracker URL so a coordinator loop can emit :tracker-peers
;; incrementally; whole-list announce keeps combining over it.

(defn- valid-announce-request
  "A well-shaped announce request: 20-byte hashes, so any failure below
   comes from the URL/transport, never from request validation."
  []
  {:info-hash (byte-array 20)
   :peer-id (byte-array 20)
   :port 6881
   :uploaded 0
   :downloaded 0
   :left 1000})

(deftest per-url-announce-is-a-scriptable-seam-test
  (testing "mock announce-to-url returns its mock peers by default"
    (let [result (network/announce-to-url
                  (mock-network/create) "http://a.example.com" (valid-announce-request))]
      (is (= {:ok #{"127.0.0.1:6881" "127.0.0.1:6882"}} result))))
  (testing "per-URL scripts override the default for that URL only"
    (let [mock (mock-network/create
                {:announce-to-url-responses
                 {"http://a.example.com" {:ok #{"10.0.0.1:1111"}}}})]
      (is (= {:ok #{"10.0.0.1:1111"}}
             (network/announce-to-url mock "http://a.example.com" (valid-announce-request))))
      (is (= {:ok #{"127.0.0.1:6881" "127.0.0.1:6882"}}
             (network/announce-to-url mock "http://b.example.com" (valid-announce-request))))))
  (testing "announce combines peers across the ordered URLs"
    ;; Two scripted URLs far apart in the order: a single-URL
    ;; implementation could never produce both peer sets at once.
    (let [mock (mock-network/create
                {:mock-peers []
                 :announce-to-url-responses
                 {"http://a.example.com" {:ok #{"10.0.0.1:1111"}}
                  "udp://tracker.opentrackr.org:1337" {:ok #{"10.0.0.2:2222"}}}})
          result (network/announce mock {:announce "http://a.example.com"}
                                   {:downloaded 0 :left 1000})]
      (is (nil? (:error result)) (pr-str result))
      (is (contains? (:ok result) "10.0.0.1:1111") (pr-str result))
      (is (contains? (:ok result) "10.0.0.2:2222") (pr-str result))))
  (testing "announce reports the failure when every URL fails"
    (let [mock (mock-network/create {:announce-to-url-error {:error :boom :message "down"}})
          result (network/announce mock {:announce "http://a.example.com"}
                                   {:downloaded 0 :left 1000})]
      (is (= :boom (:error result)))))
  (testing "with distinct per-URL failures, the LAST one wins"
    ;; Every URL in the order fails, each differently; exodus is the
    ;; final fallback URL, so its error must surface. Unscripted URLs
    ;; would answer successful-empty and dominate failures, so all seven
    ;; are scripted. An implementation returning the first failure
    ;; answers :first.
    (let [mock (mock-network/create
                {:announce-to-url-responses
                 {"http://a.example.com" {:error :first :message "1"}
                  "udp://tracker.opentrackr.org:1337" {:error :e2 :message "2"}
                  "udp://open.demonii.com:1337" {:error :e3 :message "3"}
                  "udp://open.stealth.si:80" {:error :e4 :message "4"}
                  "udp://tracker.torrent.eu.org:451" {:error :e5 :message "5"}
                  "udp://explodie.org:6969" {:error :e6 :message "6"}
                  "udp://exodus.desync.com:6969" {:error :last :message "7"}}})
          result (network/announce mock {:announce "http://a.example.com"}
                                   {:downloaded 0 :left 1000})]
      (is (= :last (:error result)) (pr-str result))))
  (testing "queried URLs follow the pure order, captured in sequence"
    (let [captured (atom [])
          mock (mock-network/create {:mock-peers []
                                     :announce-to-url-capture captured})
          result (network/announce mock
                                   {:announce "http://a.example.com"
                                    :announce-list [["http://b.example.com"]]}
                                   {:downloaded 0 :left 1000})]
      ;; Every URL answers empty but successfully, so the swarm is
      ;; genuinely empty -- not a tracker outage.
      (is (= {:ok #{}} result) (pr-str result))
      (is (= ["http://a.example.com" "http://b.example.com"]
             (take 2 (map :url @captured))))
      (is (= 8 (count @captured)) "2 declared + 6 public fallbacks")
      ;; The request is built once upstream and forwarded per URL,
      ;; not reconstructed (or nil) at each call.
      (is (every? #(= {:downloaded 0 :left 1000}
                      (select-keys (:request %) [:downloaded :left]))
                  @captured))
      (is (every? #(= 200 (:num-want (:request %))) @captured)))))

(deftest real-announce-to-url-never-throws-test
  (testing "a malformed URL is an error envelope, with no socket touched"
    (let [result (network/announce-to-url
                  (network-impl/create) "not-a-url" (valid-announce-request))]
      (is (an-envelope? result))
      (is (some? (:error result)) (pr-str result)))))

(defn- with-loopback-tracker
  "Run f against a loopback HTTP tracker serving one fixed body."
  [body f]
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/announce"
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ exchange]
                        (let [bytes body]
                          (.sendResponseHeaders exchange 200 (alength bytes))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out bytes))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.. server getAddress getPort) "/announce"))
      (finally (.stop server 0)))))

(deftest http-tracker-rejection-is-an-error-test
  (testing "a tracker failure reason is :tracker-rejected, not a successful empty set"
    (with-loopback-tracker
      (bencode/encode-bencode {"failure reason" "unregistered torrent"
                               "interval" 1800})
      (fn [url]
        (let [result (network/announce-to-url
                      (network-impl/create) url (valid-announce-request))]
          (is (= :tracker-rejected (:error result)) (pr-str result))
          (is (re-find #"unregistered torrent" (:message result)) (pr-str result))))))
  (testing "a successful announce still resolves peers (the server works)"
    (with-loopback-tracker
      (bencode/encode-bencode {"interval" 1800
                               "peers" (byte-array [127 0 0 1 0x1A (unchecked-byte 0xE1)])})
      (fn [url]
        (is (= {:ok #{"127.0.0.1:6881"}}
               (network/announce-to-url
                (network-impl/create) url (valid-announce-request))))))))

(def ^:private udp-protocol-magic
  "BEP 15 connect magic, spelled out here rather than read from the
   builder: the double is an independent oracle, so a shared wrong
   constant must not make both sides agree."
  0x41727101980)

(def ^:private udp-test-connection-id 123456789)

(defn- answer-udp-double
  "The double's whole protocol. A 16-byte connect (magic + action 0)
   yields a 16-byte connect response stamping a fixed connection id; a
   98-byte announce (action 1 + that connection id) yields interval plus
   one compact peer. Both echo the request's transaction id, like a real
   tracker. Anything else throws with the offending bytes described."
  ([^bytes data]
   (answer-udp-double data {}))
  ([^bytes data {:keys [connect-txn announce-txn connect-action announce-action]
                 :or {connect-action 0 announce-action 1}}]
   (let [in (ByteBuffer/wrap data)]
     (cond
       (= 16 (alength data))
       (let [magic (.getLong in 0)
             action (.getInt in 8)
             txn-id (.getInt in 12)]
         (when (or (not= udp-protocol-magic magic) (not= 0 action))
           (throw (ex-info "double: not a BEP 15 connect"
                           {:magic magic :action action})))
         (let [out (ByteBuffer/allocate 16)]
           (.putInt out connect-action)
           (.putInt out (or connect-txn txn-id))
           (.putLong out udp-test-connection-id)
           (.array out)))

       (= 98 (alength data))
       (let [conn-id (.getLong in 0)
             action (.getInt in 8)
             txn-id (.getInt in 12)]
         (when (or (not= udp-test-connection-id conn-id) (not= 1 action))
           (throw (ex-info "double: not a BEP 15 announce for this connection"
                           {:connection-id conn-id :action action})))
         (let [out (ByteBuffer/allocate 26)]
           (.putInt out announce-action)
           (.putInt out (or announce-txn txn-id))
           (.putInt out 1800)
           (.putInt out 0)
           (.putInt out 0)
           (.put out (byte-array [127 0 0 1 0x1A (unchecked-byte 0xE1)]))
           (.array out)))

       :else
       (throw (ex-info "double: unexpected datagram length"
                       {:length (alength data)}))))))

(defn- with-loopback-udp-tracker
  "Run run-with-url against a loopback UDP tracker double (see answer-udp-double),
   answering the connect + announce datagrams at an ephemeral port.
   Returns {:result (run-with-url's value) :responder-error (the double's
   failure, if any) :received-count (datagrams the double saw)}.
   Double opts: :drop-first-n silently swallows the first N datagrams
   (lossy mode: nothing is sent back, so the client must retry);
   :drop-indices swallows exactly those zero-based datagram indices
   (e.g. #{1 2 3} answers the connect, then drops three announces);
   :expect-datagrams bounds how many datagrams the responder waits for
   (2 covers one connect + one announce; a retrying client sends more).
   The rest of answer-udp-double's misbehavior modes pass through.
   Generous timeouts bound both sides without making the suite wait on
   them: the double answers in microseconds, so any wait past them is
   scheduler stall, and a stuck exchange still fails the assertion
   instead of hanging the suite."
  ([run-with-url] (with-loopback-udp-tracker run-with-url {}))
  ([run-with-url double-opts]
   (let [socket (doto (DatagramSocket. 0 (InetAddress/getByName "127.0.0.1"))
                  (.setSoTimeout 30000))
         errors (atom nil)
         received-count (atom 0)
         drop-first-n (:drop-first-n double-opts 0)
         drop-indices (:drop-indices double-opts #{})
         expect-datagrams (:expect-datagrams double-opts 2)
         responder (future
                     (try
                       (dotimes [receive-index expect-datagrams]
                         (let [buf (byte-array 65536)
                               pkt (DatagramPacket. buf (alength buf))]
                           (.receive socket pkt)
                           (let [data (Arrays/copyOf buf (.getLength pkt))
                                 from (.getSocketAddress pkt)]
                             (swap! received-count inc)
                             (when-not (or (< receive-index drop-first-n)
                                           (contains? drop-indices receive-index))
                               (let [reply (answer-udp-double data double-opts)]
                                 (.send socket (DatagramPacket. reply (alength reply) from)))))))
                       (catch SocketTimeoutException _quiet
                        ;; Production stopped sending: the expected end on
                        ;; refusal paths. Caller-side bounds still catch a
                        ;; genuinely stuck exchange.
                         nil)
                       (catch SocketException closed
                         (reset! errors closed))
                       (catch Exception protocol-error
                         (reset! errors protocol-error))))]
     (try
       (let [result (run-with-url (str "udp://127.0.0.1:" (.getLocalPort socket) "/announce"))]
         (deref responder 45000 ::stuck)
         {:result result :responder-error @errors :received-count @received-count})
       (finally
         (future-cancel responder)
         (.close socket))))))

(defn- fast-retry-opts
  "Test retry knobs: production socket waits, millisecond backoffs, two
   attempts. Suite time stays flat while still exercising one real retry."
  [socket-timeout-ms]
  {:udp-timeout-ms socket-timeout-ms
   :udp-retry-base-delay-ms 10
   :udp-max-attempts 2})

(deftest udp-loopback-announce-returns-double-peer-test
  (testing "connect + announce through the real port code returns the double's peer"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              ;; Generous per-attempt timeout: the double
                              ;; answers in microseconds, so this only
                              ;; absorbs scheduler stalls, never slowness.
                              (network-impl/create {:udp-timeout-ms 15000})
                              url
                              ;; :num-want present here covers the
                              ;; build's num-want subform; the other
                              ;; tests cover its absence.
                              (assoc (valid-announce-request) :num-want 10)))
                     60000 :timed-out)))]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 2 received-count)
          (str "green path sends exactly connect + announce, no resends; got " received-count))
      (is (= {:ok #{"127.0.0.1:6881"}} result) (pr-str result)))))

(deftest udp-loopback-connect-txn-mismatch-is-an-error-test
  (testing "a connect response for another transaction fails the attempt instead of connecting"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              (network-impl/create (fast-retry-opts 15000))
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            {:connect-txn 424242 :expect-datagrams 2})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 2 received-count)
          (str "the mismatch consumed an attempt and retried once; got " received-count))
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (nil? (:ok result)) (pr-str result)))))

(deftest udp-loopback-announce-txn-mismatch-is-an-error-test
  (testing "an announce response for another transaction fails the attempt instead of returning peers"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              (network-impl/create (fast-retry-opts 15000))
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            {:announce-txn 424243 :expect-datagrams 3})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 3 received-count)
          (str "expected connect + two mismatched announces; got " received-count))
      (is (= :udp-announce-failed (:error result)) (pr-str result))
      (is (nil? (:ok result)) (pr-str result)))))

(deftest udp-loopback-connect-action-mismatch-is-an-error-test
  (testing "a connect response carrying another action fails the attempt instead of connecting"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              (network-impl/create (fast-retry-opts 15000))
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            {:connect-action 1 :expect-datagrams 2})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 2 received-count)
          (str "the mismatch consumed an attempt and retried once; got " received-count))
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (nil? (:ok result)) (pr-str result)))))

(deftest udp-loopback-lossy-tracker-recovers-with-retries-test
  (testing "a dropped first datagram is recovered: the retry re-sends and the announce returns the double's peer"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              ;; Fast retry knobs, not production timing:
                              ;; 300 ms waits plus 25/50 ms backoffs keep a
                              ;; red run near one second instead of minutes.
                              (network-impl/create {:udp-timeout-ms 300
                                                    :udp-retry-base-delay-ms 25
                                                    :udp-max-attempts 3})
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            {:drop-first-n 1 :expect-datagrams 3})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 3 received-count)
          (str "expected dropped connect, retried connect, announce; got " received-count))
      (is (= {:ok #{"127.0.0.1:6881"}} result) (pr-str result)))))

(deftest udp-retry-sleep-restores-interrupt-flag-test
  (testing "an interrupted backoff returns false and keeps the interrupt visible"
    ;; Self-interrupt: sleep clears the flag when it throws, so a false
    ;; below with a cleared flag would prove the restore missing. Flag
    ;; state is captured into locals BEFORE any `is`: lein's report
    ;; binding blocks and clears a set flag as a side effect, so an
    ;; assertion across the boundary reads the runner, not the code.
    (.interrupt (Thread/currentThread))
    (let [slept? (#'network-impl/sleep-retry-delay! 15000)
          flag-kept? (.isInterrupted (Thread/currentThread))]
      ;; Clear the flag so it cannot leak into other tests.
      (Thread/interrupted)
      (is (false? slept?))
      (is (true? flag-kept?)
          "the interrupt flag was swallowed instead of restored"))))

(deftest udp-step-fails-fast-on-a-dead-socket-test
  (testing "a non-timeout socket error fails the step at once, spending no retries"
    ;; try-udp-step is driven directly here: killing the port's own
    ;; socket mid-flight is the only deterministic way to raise a
    ;; non-timeout exchange error, and it is timing-race-free.
    (let [socket (doto (DatagramSocket.) (.close))
          addr (InetSocketAddress. "127.0.0.1" 1)
          started-at (System/currentTimeMillis)
          result (#'network-impl/try-udp-step
                  (network-impl/create {:udp-timeout-ms 200
                                        :udp-retry-base-delay-ms 25
                                        :udp-max-attempts 3})
                  socket addr :udp-connect-failed "connect" :connect
                  (fn [txn-id] (tracker/build-udp-connect-request {:transaction-id txn-id}))
                  tracker/parse-udp-connect-response
                  "udp://127.0.0.1:1/announce")
          elapsed-ms (- (System/currentTimeMillis) started-at)]
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (re-find #"exchange failed" (:message result)) (pr-str result))
      (is (< elapsed-ms 5000)
          (str "a dead socket must not burn retries: took " elapsed-ms " ms")))))

(deftest udp-step-interrupted-backoff-fails-the-step-test
  (testing "interrupting a backed-off retry fails the step instead of sleeping on"
    ;; Self-interrupt on the test thread (never a pooled future thread:
    ;; a restored flag left on a pool thread would outlive the test and
    ;; fail unrelated sleeps later). The datagram receive ignores the
    ;; flag and still waits out its timeout, so the backoff sleep is the
    ;; first thing that can throw -- deterministically, no race. The
    ;; flag is cleared before leaving run-with-url: the double's deref
    ;; afterwards blocks, and blocking under a set flag throws.
    (.interrupt (Thread/currentThread))
    (let [{:keys [responder-error received-count] :as outer}
          (with-loopback-udp-tracker
            (fn [url]
              (let [result (network/announce-to-url
                            (network-impl/create {:udp-timeout-ms 500
                                                  :udp-retry-base-delay-ms 30000
                                                  :udp-max-attempts 3})
                            url
                            (valid-announce-request))
                    flag-kept? (.isInterrupted (Thread/currentThread))]
                ;; Clear the flag so it cannot leak into other tests.
                (Thread/interrupted)
                {:result result :flag-kept? flag-kept?}))
            ;; One dropped connect: the client burns its 500 ms wait,
            ;; then the 30 s backoff throws at once on the set flag.
            {:drop-first-n 1 :expect-datagrams 1})
          {:keys [result flag-kept?]} (:result outer)]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 1 received-count)
          (str "the interrupt struck during the first backoff; got " received-count))
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (re-find #"interrupted" (:message result)) (pr-str result))
      (is (true? flag-kept?)
          "the step swallowed the interrupt instead of restoring it"))))

(deftest udp-announce-defaults-missing-port-to-6969-test
  (testing "a UDP tracker URL without a port attempts the BEP 15 default instead of failing to build"
    ;; Nothing listens on 6969 here, so the attempt must fail -- but as
    ;; a connect failure after a bounded wait, never as a build error or
    ;; an IllegalArgumentException from a -1 port reaching the socket.
    (let [started-at (System/currentTimeMillis)
          result (network/announce-to-url
                  (network-impl/create {:udp-timeout-ms 200
                                        :udp-retry-base-delay-ms 25
                                        :udp-max-attempts 1})
                  "udp://127.0.0.1/announce"
                  (valid-announce-request))
          elapsed-ms (- (System/currentTimeMillis) started-at)]
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (nil? (:ok result)) (pr-str result))
      (is (< elapsed-ms 5000)
          (str "a defaulted port must fail bounded, took " elapsed-ms " ms")))))

(deftest udp-loopback-silent-tracker-fails-after-max-attempts-test
  (testing "a tracker that never answers fails after exactly max-attempts, never hanging"
    (let [started-at (System/currentTimeMillis)
          {:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              ;; 200 ms waits plus 25/50 ms backoffs: a ~700 ms
                              ;; run against a 10 s bound proves exhaustion
                              ;; terminates instead of hanging.
                              (network-impl/create {:udp-timeout-ms 200
                                                    :udp-retry-base-delay-ms 25
                                                    :udp-max-attempts 3})
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            ;; The double swallows everything: connect never succeeds, so
            ;; the announce step never runs and every datagram is a connect.
            {:drop-first-n 3 :expect-datagrams 3})
          elapsed-ms (- (System/currentTimeMillis) started-at)]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 3 received-count)
          (str "the client sent all three attempts, neither giving up early nor resending past the cap; got " received-count))
      (is (= :udp-connect-failed (:error result)) (pr-str result))
      (is (re-find #"3 attempts" (:message result)) (pr-str result))
      (is (< elapsed-ms 10000)
          (str "exhaustion took " elapsed-ms " ms: retries must stay bounded, never hang")))))

(deftest udp-loopback-silent-announce-retries-past-connect-test
  (testing "retries apply to the announce step too, not just the connect"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              (network-impl/create {:udp-timeout-ms 200
                                                    :udp-retry-base-delay-ms 25
                                                    :udp-max-attempts 3})
                              url
                              (valid-announce-request)))
                     60000 :timed-out))
            ;; Datagram 0 (connect) is answered; 1-3 (announces) vanish.
            {:drop-indices #{1 2 3} :expect-datagrams 4})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 4 received-count)
          (str "expected connect + three dropped announces; got " received-count))
      (is (= :udp-announce-failed (:error result)) (pr-str result))
      (is (re-find #"3 attempts" (:message result)) (pr-str result)))))

(deftest udp-loopback-announce-build-failure-is-an-envelope-test
  (testing "a request the builder refuses comes back as an error envelope naming validation, never a throw"
    (let [{:keys [result responder-error received-count]}
          (with-loopback-udp-tracker
            (fn [url]
              (deref (future (network/announce-to-url
                              (network-impl/create {:udp-timeout-ms 15000})
                              url
                              (assoc (valid-announce-request) :info-hash (byte-array 4))))
                     60000 :timed-out))
            ;; Only the connect ever goes out: the refused announce build
            ;; fails fast with no retry, so the double waits for exactly
            ;; one datagram instead of idling on its socket timeout.
            {:expect-datagrams 1})]
      (is (nil? responder-error) (str "double raised: " (pr-str responder-error)))
      (is (= 1 received-count) (str "the refused build sent nothing further; got " received-count))
      (is (= :udp-announce-failed (:error result)) (pr-str result))
      (is (re-find #"validation" (:message result)) (pr-str result)))))
