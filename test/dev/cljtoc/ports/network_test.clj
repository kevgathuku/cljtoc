(ns dev.cljtoc.ports.network-test
  "Contract tests for the peer and tracker ports.
   Seam: INetworkPort / ITrackerPort hand back their envelope directly, so
   no caller has to know about core.async to learn a result.

   Every method is driven without touching the network: connect-peer is
   handed a blank address (refused before any socket work), the read and
   write methods are handed byte-array streams, and announce is handed a
   torrent that declares no tracker."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async.impl.protocols :as chan]
            [dev.cljtoc.ports.network :as network]
            [dev.cljtoc.ports.network-impl :as network-impl]
            [dev.cljtoc.test-doubles.network :as mock-network])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            InputStream OutputStream]))

(defn- channel?
  "True when x is something a blocking take could read from. A port method
   that returns one of these has wrapped a plain function in a channel, so
   the caller has to know about core.async to learn the result."
  [x]
  (satisfies? chan/ReadPort x))

(def ^:private handshake-bytes (byte-array 68))

(def ^:private keep-alive-prefix (byte-array [0 0 0 0]))

(defn- peer-in [bytes]
  {:id "p" :in (ByteArrayInputStream. bytes)})

(def ^:private peer-calls
  "The five INetworkPort methods, each drivable with no network at all:
   connect-peer gets a blank address, which is refused before any socket
   work, and the read and write methods get byte-array streams."
  {:connect-peer #(network/connect-peer % "")
   :send-message #(network/send-message % {:id "p" :out (ByteArrayOutputStream.)} handshake-bytes)
   :receive-message #(network/receive-message % (peer-in keep-alive-prefix))
   :receive-handshake #(network/receive-handshake % (peer-in handshake-bytes))
   :close-peer #(network/close-peer % {:id "p" :socket nil})})

(defn- an-envelope? [result]
  (and (map? result) (or (contains? result :ok) (contains? result :error))))

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
    (doseq [[method call] (assoc peer-calls :announce #(network/announce % {}))]
      (let [result (call (mock-network/create))]
        (is (not (channel? result))
            (str "mock " method " returned a channel, not a result"))
        (when-not (= :close-peer method)
          (is (an-envelope? result)
              (str "mock " method " returned neither :ok nor :error: " (pr-str result))))))))

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

(deftest a-failing-stream-is-mapped-to-its-reason-test
  (testing "each catch clause still fires, now that it wraps a return instead of a put"
    (let [net (network-impl/create)]
      (is (= :send-failed
             (:error (network/send-message
                      net
                      {:id "p" :out (failing-output-stream (java.io.IOException. "boom"))}
                      handshake-bytes))))
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

(deftest a-full-length-message-is-parsed-test
  (testing "a non-zero length prefix reads exactly its declared payload and parses it.
            Per BEP 3 a 1-byte body of 2 is an Interested, so the oracle is the
            wire spec, not whatever the parser happens to return."
    (let [result (network/receive-message
                  (network-impl/create)
                  (peer-in (byte-array [0 0 0 1 2])))]
      (is (nil? (:error result)) (pr-str result))
      (is (instance? dev.cljtoc.protocol.peer.Interested (:ok result))))))
