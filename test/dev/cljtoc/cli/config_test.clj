(ns dev.cljtoc.cli.config-test
  "Tests for the client settings file.
   Seam: default-settings merged with an EDN file; missing file means
   defaults, corrupt file means an error envelope, never silent defaults."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [dev.cljtoc.cli.config :as config]
            [dev.cljtoc.test-utils :as test-utils]))

(deftest parse-settings-merges-over-defaults-test
  (testing "a partial file overrides only the keys it names"
    (let [settings (:ok (config/parse-settings "{:max-peers 10}"))]
      (is (= 10 (:max-peers settings)))
      (is (= (:output-dir config/default-settings) (:output-dir settings)))
      (is (= (:peer-port config/default-settings) (:peer-port settings)))))
  (testing "an empty map means all defaults"
    (is (= config/default-settings (:ok (config/parse-settings "{}"))))))

(deftest parse-settings-rejects-test
  (testing "non-EDN text is an error, not silent defaults"
    (let [result (config/parse-settings "{:max-peers")]
      (is (= :invalid-config (:error result)))
      (is (string? (:message result)))))
  (testing "a non-map document is an error"
    (let [result (config/parse-settings "[1 2 3]")]
      (is (= :invalid-config (:error result)))))
  (testing "a mistyped value is an error naming the key"
    (let [result (config/parse-settings "{:max-peers \"lots\"}")]
      (is (= :invalid-config (:error result)))
      (is (string? (:message result)))))
  (testing "an out-of-range peer port is an error"
    (let [result (config/parse-settings "{:peer-port 99999}")]
      (is (= :invalid-config (:error result))))))

(deftest parse-settings-ignores-unknown-keys-test
  (testing "future-slice keys pass through untouched for now"
    (let [result (config/parse-settings "{:max-peers 10 :blocklist-path \"/x\"}")]
      (is (= 10 (:max-peers (:ok result))))
      (is (not (contains? (:ok result) :blocklist-path))))))

(deftest load-settings-reads-files-test
  (testing "a missing file means defaults, not an error"
    (let [missing (str (test-utils/temp-dir "cfg-") "/absent.edn")]
      (is (= {:ok config/default-settings}
             (config/load-settings missing)))))
  (testing "a present file merges over defaults"
    (let [dir (test-utils/temp-dir "cfg-")
          path (str dir "/settings.edn")]
      (spit path "{:output-dir \"./mine\" :max-peers 7}")
      (let [result (config/load-settings path)]
        (is (= "./mine" (:output-dir (:ok result))))
        (is (= 7 (:max-peers (:ok result))))
        (is (= (:state-dir config/default-settings)
               (:state-dir (:ok result)))))))
  (testing "a corrupt file is an error"
    (let [dir (test-utils/temp-dir "cfg-")
          path (str dir "/settings.edn")]
      (spit path "{:max-peers")
      (let [result (config/load-settings path)]
        (is (= :invalid-config (:error result)))))))

(deftest default-config-path-test
  (testing "the default path lives under the user config dir"
    (is (.endsWith ^String (config/default-config-path)
                   ".config/cljtoc/config.edn"))))
