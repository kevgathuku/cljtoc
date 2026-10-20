(ns dev.cljtoc.rpc
  "The RPC surface of the daemon session: one request per connection.

   The client connects, writes one EDN map, and reads one EDN map back.
   A request is {:command :start/:status/:pause/:resume/:stop :args {...}};
   a response is {:ok ...} or {:error reason :message}.

   The server owns the session and hands each request to an injected
   handler, so this namespace carries no download logic: it is the
   transport and the envelope. The daemon wires the handlers, and the
   CLI is a client."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s])
  (:import [java.io PushbackReader PrintWriter]
           [java.net ServerSocket]))

(s/def ::command keyword?)

(s/def ::args map?)

(s/def ::request (s/keys :req-un [::command ::args]))

(s/def ::ok any?)

(s/def ::reason keyword?)

(s/def ::message string?)

(s/def ::error-response (s/keys :req-un [::reason ::message]))

(def no-daemon
  "The response a client gets when nothing is listening on the daemon's
   port: the CLI reports it instead of blocking on a dead socket."
  {:error :no-daemon
   :message "No daemon is running on that port"})

(declare serve-connection)

(defn- serve-connection
  "Read one request from the socket, answer it, close the socket."
  [socket handle]
  (try
    (with-open [reader (PushbackReader. (java.io.InputStreamReader.
                                         (.getInputStream ^java.net.Socket socket)))
                writer (PrintWriter. (.getOutputStream ^java.net.Socket socket) true)]
      (let [request (edn/read reader)]
        (.println writer (pr-str (try
                                   (handle request)
                                   (catch Exception error
                                     {:error :handler-failed
                                      :message (or (.getMessage ^Exception error)
                                                   (.getName (class error)))}))))
        (.flush writer)))
    (catch Exception _ nil)
    (finally
      (.close ^java.net.Socket socket))))

(defn start-server
  "Start the RPC server on `port`.

   Every connection reads one request and answers it through `handle`, a
   fn from a request map to a response map. A request that cannot be read
   or a handler that throws is answered with an error response, so one
   bad connection never takes the server down.

   Returns {:stop (fn [])}: the stop closes the listening socket, which
   ends the accept loop."
  [{:keys [port handle]}]
  (let [running (atom true)
        server (ServerSocket. port)
        accept (Thread.
                (fn []
                  (while @running
                    (try
                      (let [socket (.accept ^ServerSocket server)]
                        (future (serve-connection socket handle)))
                      (catch Exception _ nil)))))]
    (.setDaemon accept true)
    (.start accept)
    {:stop (fn []
             (reset! running false)
             (.close ^ServerSocket server))}))

(s/fdef start-server
  :args (s/cat :options (s/keys :req-un [::port ::handle]))
  :ret map?)

(defn request
  "Send one request to the daemon on `port` and return its response.

   A daemon that is not there answers with `no-daemon` rather than
   throwing, so the CLI can report it. The connection is one request per
   connection: the server closes it after the answer."
  [port command args]
  (try
    (with-open [socket (java.net.Socket. "127.0.0.1" port)
                reader (PushbackReader. (java.io.InputStreamReader.
                                         (.getInputStream ^java.net.Socket socket)))
                writer (PrintWriter. (.getOutputStream ^java.net.Socket socket) true)]
      (.println writer (pr-str {:command command :args args}))
      (.flush writer)
      (edn/read reader))
    (catch Exception _
      no-daemon)))

(s/fdef request
  :args (s/cat :port (s/int-in 1 65536) :command ::command :args ::args)
  :ret map?)

(defn error-response
  "The response for a refused request."
  [reason message]
  {:error reason :message message})

(s/fdef error-response
  :args (s/cat :reason ::reason :message ::message)
  :ret ::error-response)
