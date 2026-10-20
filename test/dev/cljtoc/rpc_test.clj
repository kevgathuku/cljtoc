(ns dev.cljtoc.rpc-test
  "Tests for the RPC transport: one request per connection, an error
   envelope for a refusal, and a server that survives a bad request."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.rpc :as rpc])
  (:import [java.net ServerSocket]))

(defn- free-port
  "A port nothing is listening on."
  []
  (let [socket (ServerSocket. 0)]
    (try (.getLocalPort socket)
         (finally (.close socket)))))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest the-server-answers-through-its-handler-test
  (testing "a request gets the handler's response"
    (let [port (free-port)
          seen (atom [])
          server (rpc/start-server {:port port
                                    :handle (fn [request]
                                              (swap! seen conj request)
                                              {:ok (:command request)})})]
      (try
        (is (= {:ok :status} (rpc/request port :status {}))
            "the server answered the request")
        (is (wait-for #(= 1 (count @seen)) 2000)
            "the handler saw the request")
        (is (= :status (:command (first @seen))))
        (finally
          ((:stop server)))))))

(deftest a-throwing-handler-is-an-error-response-test
  (testing "a handler that throws answers with an error instead of killing the connection"
    (let [port (free-port)
          server (rpc/start-server {:port port
                                    :handle (fn [_request]
                                              (throw (ex-info "boom" {})))})]
      (try
        (let [response (rpc/request port :status {})]
          (is (= :handler-failed (:error response)))
          (is (= "boom" (:message response))))
        (finally
          ((:stop server))))))
  (testing "a message-less exception still names its class"
    (let [port (free-port)
          server (rpc/start-server {:port port
                                    :handle (fn [_request]
                                              (throw (ex-info nil {})))})]
      (try
        (let [response (rpc/request port :status {})]
          (is (= :handler-failed (:error response)))
          (is (string? (:message response))))
        (finally
          ((:stop server)))))))

(deftest a-garbage-request-does-not-take-the-server-down-test
  (testing "a connection that cannot be read leaves the server answering"
    (let [port (free-port)
          server (rpc/start-server {:port port
                                    :handle (fn [_request] {:ok :fine})})]
      (try
        ;; A bare string is not a request map; the server still answers the
        ;; next request.
        (let [socket (java.net.Socket. "127.0.0.1" port)
              writer (java.io.PrintWriter. (.getOutputStream socket) true)]
          (.println writer "\"not a request\"")
          (.flush writer)
          (.close socket))
        (is (= {:ok :fine} (rpc/request port :status {})))
        (finally
          ((:stop server)))))))

(deftest a-client-with-no-daemon-gets-a-clear-refusal-test
  (testing "a client that finds no daemon is refused, not thrown"
    (let [port (free-port)]
      (is (= rpc/no-daemon (rpc/request port :status {}))))))

(deftest stopping-the-server-closes-the-port-test
  (testing "the stop ends the server"
    (let [port (free-port)
          server (rpc/start-server {:port port
                                    :handle (fn [_request] {:ok :fine})})]
      ((:stop server))
      (is (= rpc/no-daemon (rpc/request port :status {}))))))

(deftest error-response-shapes-the-refusal-test
  (testing "the refusal envelope carries a reason and a message"
    (is (= {:error :no-peers :message "nobody"}
           (rpc/error-response :no-peers "nobody")))))
