(ns dev.cljtoc.cli.config-test
  "Tests for the client settings file.
   Seam: default-settings merged with an EDN file; missing file means
   defaults, corrupt file means an error envelope, never silent defaults."
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest parse-settings-error-messages-name-the-fault-test
  (testing "a malformed document reports the reader's reason, not an internals dump"
    (let [message (:message (config/parse-settings "{:max-peers"))]
      (is (re-find #"Could not parse config" message) message)
      (is (not (re-find #"Reduced" message)) message)))
  (testing "a non-map document names what it got"
    (is (re-find #"must be a map"
                 (:message (config/parse-settings "[1 2 3]"))))))

(deftest parse-settings-tolerates-envelope-shaped-keys-test
  (testing "a document carrying :error is data, not a failed parse"
    (let [result (config/parse-settings "{:error \"boom\"}")]
      (is (nil? (:message result)) "not mistaken for a refusal")
      (is (= config/default-settings (:ok result)))
      (is (not (contains? (:ok result) :error)))))
  (testing "a document carrying :ok is data too"
    (let [result (config/parse-settings "{:ok 1}")]
      (is (= config/default-settings (:ok result)))
      (is (not (contains? (:ok result) :ok))))))

(deftest config-home-is-one-derivation-test
  (testing "the default config path lives under the same home the state dir uses"
    (let [settings config/default-settings
          config-path (config/default-config-path)]
      (is (.startsWith ^String (:state-dir settings) ^String config/config-home))
      (is (.startsWith ^String config-path ^String config/config-home))
      (is (.endsWith ^String config-path "cljtoc/config.edn")))))

(deftest fdef-specs-hold-generatively-test
  (testing "parse-settings is pure and total, so its fdef is check-grade"
    (let [failures (test-utils/check-fdefs
                    '[dev.cljtoc.cli.config/parse-settings
                      dev.cljtoc.cli.config/default-config-path]
                    50)]
      (is (empty? failures) (str "fdef check failures: " (pr-str failures))))))

(deftest xdg-download-dir-parse-test
  (testing "XDG_DOWNLOAD_DIR is read from user-dirs.dirs with $HOME expanded"
    (is (= "/home/ana/Descargas"
           (#'config/parse-xdg-download-dir
            "XDG_DESKTOP_DIR=\"$HOME/Escritorio\"\nXDG_DOWNLOAD_DIR=\"$HOME/Descargas\"\n"
            "/home/ana"))))
  (testing "an absolute path is used as written"
    (is (= "/mnt/store/dl"
           (#'config/parse-xdg-download-dir
            "XDG_DOWNLOAD_DIR=\"/mnt/store/dl\"\n" "/home/ana"))))
  (testing "a missing key yields nil so the caller falls back"
    (is (nil? (#'config/parse-xdg-download-dir
               "XDG_DESKTOP_DIR=\"$HOME/Escritorio\"\n" "/home/ana"))))
  (testing "absent or empty content yields nil"
    (is (nil? (#'config/parse-xdg-download-dir nil "/home/ana")))
    (is (nil? (#'config/parse-xdg-download-dir "" "/home/ana"))))
  (testing "an empty value yields nil rather than the home dir"
    (is (nil? (#'config/parse-xdg-download-dir "XDG_DOWNLOAD_DIR=\"\"\n" "/home/ana")))))

(deftest default-download-dir-test
  (testing "the XDG entry wins when the user redefined their downloads folder"
    (is (= "/home/ana/Descargas"
           (config/default-download-dir
            {:home "/home/ana"
             :user-dirs-content "XDG_DOWNLOAD_DIR=\"$HOME/Descargas\"\n"}))))
  (testing "without an XDG entry every platform falls back to <home>/Downloads"
    (is (= "/home/ana/Downloads"
           (config/default-download-dir {:home "/home/ana"})))
    (is (= "C:\\Users\\ana\\Downloads"
           (config/default-download-dir {:home "C:\\Users\\ana"
                                         :separator "\\"})))
    (is (= "/Users/ana/Downloads"
           (config/default-download-dir {:home "/Users/ana"})))))

(deftest piece-cache-lives-in-its-own-folder-test
  (testing "the cache is a named folder under the cljtoc cache root, not the root itself"
    (is (= (str config/cache-home "/cljtoc/pieces")
           (:piece-cache-dir config/default-settings))))
  (testing "state and cache are distinct directories even when both homes coincide"
    (is (not= (:state-dir config/default-settings)
              (:piece-cache-dir config/default-settings)))))
