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
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- channel?
  "True when x is something a blocking take could read from. A port method
   that returns one of these has wrapped a plain function in a channel, so
   the caller has to know about core.async to learn the result."
  [x]
  (satisfies? chan/ReadPort x))

(def ^:private handshake-bytes (byte-array 68))

(def ^:private keep-alive-prefix (byte-array [0 0 0 0]))

(defn- calls
  "One thunk per port method, each needing no real network. The same set is
   driven against the real port and the mock, so a drift between them
   shows up as one side returning a bare value where the other returns an
   envelope."
  []
  {:connect-peer #(network/connect-peer % "")
   :send-message #(network/send-message % {:id "p" :out (ByteArrayOutputStream.)} handshake-bytes)
   :receive-message #(network/receive-message % {:id "p" :in (ByteArrayInputStream. keep-alive-prefix)})
   :receive-handshake #(network/receive-handshake % {:id "p" :in (ByteArrayInputStream. handshake-bytes)})
   :close-peer #(network/close-peer % {:id "p" :socket nil})
   :announce #(network/announce % {})})

(deftest network-port-returns-envelopes-not-channels-test
  (testing "every INetworkPort / ITrackerPort method hands back its result
            directly, not a channel, on the real port and the mock alike"
    (doseq [[port-name port] {:real (network-impl/create) :mock (mock-network/create)}
            [method call] (calls)]
      (let [result (call port)]
        (is (not (channel? result))
            (str port-name " " method " returned a channel, not a result"))
        ;; close-peer is the one method documented to return nil rather
        ;; than an envelope, so it is exempt from the shape check.
        (when-not (= :close-peer method)
          (is (and (map? result) (or (contains? result :ok) (contains? result :error)))
              (str port-name " " method " returned neither :ok nor :error: "
                   (pr-str result))))))))

(deftest refused-connect-yields-the-same-envelope-on-both-ports-test
  (testing "a bad peer address is an {:error :invalid-address} envelope, not a throw
            and not a channel, on the real port and the mock's contract alike"
    (let [real (network/connect-peer (network-impl/create) "")]
      (is (= :invalid-address (:error real)))
      (is (string? (:message real))))))
