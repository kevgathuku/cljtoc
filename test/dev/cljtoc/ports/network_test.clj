(ns dev.cljtoc.ports.network-test
  "Contract tests for the peer and tracker ports.
   Seam: INetworkPort / ITrackerPort hand back their envelope directly, so
   no caller has to know about core.async to learn a result.

   The five INetworkPort methods are all driven without touching the
   network: connect-peer gets a blank address, which is refused before any
   socket work, and the read and write methods get byte-array streams.
   announce is exercised on the mock only -- the real one always reaches
   for a tracker, because collect-tracker-urls appends well-known public
   fallbacks, so there is no offline input that stops it short."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.protocol.peer :as peer]
            [dev.cljtoc.test-utils :refer [an-envelope? channel?] :as test-utils]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.test-doubles.network :as mock-network])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            InputStream OutputStream]))

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

;; check-timeout-opts throws by design, so it is excluded from stest/check
;; (generated invalid configs would fail the check by construction); the
;; mutation table below is its generative-equivalent coverage.
(deftest invalid-timeout-opts-are-rejected-at-creation-test
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
          (is (re-find #"Invalid network timeout" (ex-message err)))
          (is (= timeout-key (:key (ex-data err))))
          (is (= bad (:value (ex-data err))))))))
  (testing "valid timeouts (including the int boundary) and absent keys pass through"
    (let [opts {:connect-timeout-ms 1
                :socket-timeout-ms 2
                :udp-timeout-ms 3
                :http-timeout-ms Integer/MAX_VALUE}]
      (is (= opts (:config (network-impl/create opts))))
      (is (= opts (:config (mock-network/create opts))))
      (is (= {} (:config (network-impl/create)))))))

(deftest fdef-specs-hold-generatively-test
  (testing "log-fn fdef holds over generated inputs"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.ports.network/log-fn]
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
