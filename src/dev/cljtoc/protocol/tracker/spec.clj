(ns dev.cljtoc.protocol.tracker.spec
  "Clojure spec definitions for tracker protocol data structures.

  Provides specs for all tracker entities with custom generators for
  protocol-aware generative testing."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;; ---------------------------------------------------------------------------
;; Custom generators for protocol-aware test data
;; ---------------------------------------------------------------------------

(defn- gen-bytes
  "Generate byte array of exact length n"
  [n]
  (gen/fmap byte-array
            (gen/vector (gen/choose -128 127) n)))

(def gen-info-hash
  "Generate exactly 20-byte array for info-hash"
  (gen-bytes 20))

(def gen-peer-id
  "Generate exactly 20-byte array for peer-id"
  (gen-bytes 20))

(def gen-port
  "Generate realistic port numbers (1024-65535)"
  (gen/choose 1024 65535))

(def gen-ipv4-address
  "Generate valid IPv4 address string"
  (gen/fmap (fn [[a b c d]]
              (str a "." b "." c "." d))
            (gen/tuple (gen/choose 0 255)
                       (gen/choose 0 255)
                       (gen/choose 0 255)
                       (gen/choose 0 255))))

;; ---------------------------------------------------------------------------
;; Basic field specs
;; ---------------------------------------------------------------------------

(s/def ::info-hash
  (s/with-gen
    (s/and bytes?
           #(= 20 (alength ^bytes %)))
    (constantly gen-info-hash)))

(s/def ::peer-id
  (s/with-gen
    (s/and bytes?
           #(= 20 (alength ^bytes %)))
    (constantly gen-peer-id)))

(s/def ::port
  (s/with-gen
    (s/int-in 1 65536)  ; 1-65535 inclusive
    (constantly gen-port)))

(s/def ::ip-address
  (s/with-gen
    (s/and string?
           #(or (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" %)
                (re-matches #"^[0-9a-fA-F:]+$" %)))  ; Simple IPv4/IPv6 validation
    (constantly gen-ipv4-address)))

(s/def ::event
  #{:started :completed :stopped nil})

(s/def ::protocol
  #{:http :udp})

;; ---------------------------------------------------------------------------
;; Entity specs
;; ---------------------------------------------------------------------------

(s/def ::peer
  (s/keys :req-un [::ip-address ::port]
          :opt-un [::peer-id]))

;; Tracker response fields
(s/def ::success boolean?)
(s/def ::interval pos-int?)
(s/def ::min-interval pos-int?)
(s/def ::complete nat-int?)
(s/def ::incomplete nat-int?)
(s/def ::tracker-id string?)
(s/def ::failure-reason string?)
(s/def ::warning-message string?)
(s/def ::peers (s/coll-of ::peer))
(s/def ::response-type #{:connect :announce :scrape})

(s/def ::tracker-response
  (s/keys :req-un [::success ::protocol ::response-type]
          :opt-un [::peers ::interval ::min-interval ::complete ::incomplete
                   ::tracker-id ::failure-reason ::warning-message]))
