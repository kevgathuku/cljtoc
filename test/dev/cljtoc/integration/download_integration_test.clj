(ns dev.cljtoc.integration.download-integration-test
  "Integration tests for complete download flow.
   
   These tests verify end-to-end download functionality using
   mock ports to simulate real network and disk operations."
  (:require [clojure.test :refer :all]
            [dev.cljtoc.orchestration.download :as download]
            [dev.cljtoc.orchestration.manager :as manager]
            [dev.cljtoc.test-doubles.network :as mock-net]
            [dev.cljtoc.test-doubles.disk :as mock-disk]
            [dev.cljtoc.test-doubles.time :as mock-time]))

(deftest integration-start-download-test
  (let [network (mock-net/create {:default-bitfield #{0 1 2 3 4}
                                   :mock-peers ["127.0.0.1:6881" "127.0.0.1:6882"]})
        disk (mock-disk/create {:torrent-data {"/test.torrent"
                                               {:info-hash (byte-array 20)
                                                :name "test.torrent"
                                                :piece-length 262144
                                                :pieces (byte-array (* 20 10))
                                                :length 2621440
                                                :files []}}})
        time (mock-time/create)
        m (manager/manager network disk time {})]
    
    (testing "can start a download with valid torrent"
      (let [result (download/start-download m "/test.torrent" "/output")]
        (is (not (:error result)))
        (is (= :downloading (:state result)))))))
