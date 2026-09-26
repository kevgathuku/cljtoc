(ns dev.cljtoc.domain.peer-address-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.domain.peer-address :as addr]))

(deftest parse-host-port-test
  (testing "parses a host:port string into host and port"
    (is (= {:ok {:host "192.168.1.100" :port 5142}}
           (addr/parse "192.168.1.100:5142")))))

(deftest parse-missing-port-test
  (testing "an address without a port defaults to 6881"
    (is (= {:ok {:host "10.0.0.1" :port 6881}}
           (addr/parse "10.0.0.1")))))

(deftest parse-bracketed-ipv6-test
  (testing "bracketed IPv6 with port keeps the full host"
    (is (= {:ok {:host "::1" :port 6881}}
           (addr/parse "[::1]:6881")))))

(deftest parse-bare-ipv6-test
  (testing "bare IPv6 without brackets defaults the port"
    (is (= {:ok {:host "::1" :port 6881}}
           (addr/parse "::1")))))

(deftest parse-invalid-port-test
  (testing "a non-numeric port is an error, not an exception"
    (is (= :invalid-port (:error (addr/parse "10.0.0.1:abc")))))
  (testing "an out-of-range port is an error"
    (is (= :invalid-port (:error (addr/parse "10.0.0.1:99999"))))))

(deftest parse-blank-test
  (testing "blank input is an error"
    (is (= :invalid-address (:error (addr/parse ""))))))

(deftest format-test
  (testing "formats an address value back to host:port"
    (is (= "192.168.1.100:5142"
           (addr/format-address {:host "192.168.1.100" :port 5142}))))
  (testing "IPv6 hosts format bracketed"
    (is (= "[::1]:6881"
           (addr/format-address {:host "::1" :port 6881})))))

(deftest accessors-test
  (testing "host and port read the address value"
    (let [a {:host "10.0.0.1" :port 6881}]
      (is (= "10.0.0.1" (addr/host a)))
      (is (= 6881 (addr/port a))))))

(deftest round-trip-test
  (testing "parse then format is the identity on canonical addresses"
    (doseq [s ["1.2.3.4:6881" "[::1]:6881"]]
      (is (= s (addr/format-address (:ok (addr/parse s)))))))
  (testing "bare host formats with the default port"
    (is (= "1.2.3.4:6881" (addr/format-address (:ok (addr/parse "1.2.3.4")))))))
