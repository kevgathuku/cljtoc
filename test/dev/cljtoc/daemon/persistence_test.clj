(ns dev.cljtoc.daemon.persistence-test
  "Tests for the daemon's periodic persistence worker."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.cljtoc.daemon.persistence :as persistence]
            [dev.cljtoc.test-doubles.disk :as mock-disk]))

(defn- wait-until
  "True once predicate holds, polling up to `timeout-ms`."
  [predicate timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest save-all-reports-only-the-failures-test
  (testing "a save that fails comes back as data carrying its id"
    (let [refusal {:error :save-error :message "nope"}
          disk-port (mock-disk/create {:save-error refusal})]
      (is (= [{:error :save-error :message "nope" :id "a"}
              {:error :save-error :message "nope" :id "b"}]
             (persistence/save-all disk-port [{:id "a"} {:id "b"}])))))
  (testing "a clean pass reports nothing and persists every record"
    (let [disk-port (mock-disk/create)]
      (is (= [] (persistence/save-all disk-port [{:id "c" :state :downloading}])))
      (is (= {:id "c" :state :downloading} (mock-disk/get-state disk-port "c"))))))

(deftest start-saves-every-record-on-its-interval-test
  (testing "the worker re-saves the records it is given, then stops on request"
    (let [disk-port (mock-disk/create)
          live (atom [{:id "a" :state :downloading}])
          worker (persistence/start disk-port (fn [] @live) 5)]
      (try
        (is (wait-until #(some? (mock-disk/get-state disk-port "a")) 2000)
            "the first tick saved the record")
        (swap! live conj {:id "b" :state :downloading})
        (is (wait-until #(some? (mock-disk/get-state disk-port "b")) 2000)
            "a record added later is saved by a later tick")
        (finally
          ((:stop worker))))
      ;; Let a tick that was already running land, then prove no new one
      ;; starts.
      (Thread/sleep 50)
      (swap! live conj {:id "c" :state :downloading})
      (Thread/sleep 100)
      (is (nil? (mock-disk/get-state disk-port "c"))
          "no tick runs after the stop"))))

(deftest a-throwing-save-does-not-stop-the-pass-test
  (testing "a record whose save throws does not stop the records after it"
    (let [disk-port (mock-disk/create {:save-throws-for #{"bad"}})
          records [{:id "bad" :state :downloading}
                   {:id "good" :state :downloading}]]
      (is (= [{:id "bad" :error :save-error}]
             (map #(select-keys % [:id :error])
                  (persistence/save-all disk-port records)))
          "the failure is reported as data, once, named by id")
      (is (some? (mock-disk/get-state disk-port "good"))
          "the healthy record after it was still saved"))))

(deftest a-throwing-save-does-not-stop-the-worker-test
  (testing "a port that throws does not kill the schedule"
    (let [disk-port (mock-disk/create {:save-throws true})
          passes (atom 0)
          worker (persistence/start disk-port
                                    (fn [] (swap! passes inc) [{:id "a"}])
                                    5)]
      (try
        (is (wait-until #(>= @passes 3) 2000)
            "later ticks still run after a throwing save")
        (finally
          ((:stop worker)))))))

(deftest stop-waits-for-a-save-in-flight-test
  (testing "stop returns only after the running save finished, so the final save cannot be overwritten"
    (let [disk-port (mock-disk/create {:save-delay-ms 300})
          worker (persistence/start disk-port (fn [] [{:id "a" :state :downloading}]) 5)]
      ;; Let the first tick enter its slow save, then stop while it runs.
      (Thread/sleep 100)
      ((:stop worker))
      (is (some? (mock-disk/get-state disk-port "a"))
          "the in-flight save completed before stop returned"))))
