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
      (spit path (str "{:output-dir \"" dir "/mine\" :max-peers 7}"))
      (let [result (config/load-settings path)]
        (is (= (str dir "/mine") (:output-dir (:ok result))))
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

(deftest paths-pass-through-verbatim-test
  (testing "separator spelling is the filesystem's business, not the config's"
    (let [settings (:ok (config/parse-settings
                         "{:output-dir \"/tmp//a/./b\"}"))]
      (is (= "/tmp//a/./b" (:output-dir settings))
          "no separator or segment rewriting happens here"))))

(deftest config-paths-must-be-absolute-test
  (testing "relative directory values are refused rather than resolved against the cwd"
    ;; A relative dir means `lein run` and a jar invocation download to
    ;; different places, which is why the config rejects them instead.
    (doseq [value ["downloads" "./downloads" "../out" "~/Downloads" ""]]
      (let [result (config/parse-settings (str "{:output-dir \"" value "\"}"))]
        (is (= :invalid-config (:error result))
            (str "accepted relative :output-dir " (pr-str value)))
        (is (re-find #":output-dir" (:message result))))))
  (testing "every directory key is checked, not just the output dir"
    (doseq [key-name [":state-dir" ":piece-cache-dir" ":output-dir"]]
      (is (= :invalid-config
             (:error (config/parse-settings (str "{" key-name " \"relative/dir\"}"))))
          (str "accepted a relative " key-name))))
  (testing "absolute paths with redundant separators still pass"
    (is (some? (:ok (config/parse-settings "{:output-dir \"/tmp//a/./b\"}"))))))

(deftest refusal-messages-name-the-reason-test
  (testing "a tilde path is refused for the reason users get wrong"
    (let [message (:message (config/parse-settings "{:output-dir \"~/Downloads\"}"))]
      (is (re-find #"~ is not expanded" message) message)))
  (testing "a plain relative path is refused for its own reason"
    (let [message (:message (config/parse-settings "{:state-dir \"./state\"}"))]
      (is (re-find #"must be an absolute path" message) message)))
  (testing "non-directory keys keep the plain message"
    (let [message (:message (config/parse-settings "{:max-peers \"lots\"}"))]
      (is (not (re-find #"absolute path" message)) message)
      (is (re-find #":max-peers" message) message))))

(deftest load-settings-read-failure-is-an-error-test
  (testing "a path that exists but cannot be read is an error naming the file"
    ;; A directory passes .exists but throws on slurp: the read-failure path.
    (let [dir (test-utils/temp-dir "cfg-dir-")
          result (config/load-settings dir)]
      (is (= :invalid-config (:error result)))
      (is (re-find #"Could not read config file" (:message result)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote dir))
                   (:message result))))))

(deftest user-dirs-content-tolerates-unreadable-files-test
  (testing "an absent file yields nil"
    (is (nil? (#'config/user-dirs-content
               (str (test-utils/temp-dir "ud-") "/nope")))))
  (testing "a readable file yields its contents"
    (let [dir (test-utils/temp-dir "ud-")
          path (str dir "/user-dirs.dirs")]
      (spit path "XDG_DOWNLOAD_DIR=\"$HOME/dl\"\n")
      (is (= "XDG_DOWNLOAD_DIR=\"$HOME/dl\"\n" (#'config/user-dirs-content path)))))
  (testing "an unreadable path (a directory) yields nil instead of throwing"
    (let [dir (test-utils/temp-dir "ud-")]
      (is (nil? (#'config/user-dirs-content dir))))))

(deftest non-string-values-are-refused-test
  (testing "a non-string directory value is refused, not coerced"
    (doseq [value ["42" "nil" ":kw"]]
      (let [result (config/parse-settings (str "{:output-dir " value "}"))]
        (is (= :invalid-config (:error result)) (str value " -> " (pr-str result))))))
  (testing "a non-integer peer port is refused"
    (is (= :invalid-config (:error (config/parse-settings "{:peer-port \"6881\"}")))))
  (testing "a non-integer peer cap is refused"
    (is (= :invalid-config (:error (config/parse-settings "{:max-peers 1.5}"))))))

;; ---------------------------------------------------------------------------
;; Coverage residues (cloverage, scoped to this namespace: 100% lines,
;; 99.21% forms). Each remainder is excluded deliberately:
;;
;; - config-home / cache-home `or` branch with the XDG variable set. The JVM
;;   cannot set environment variables from inside a process, so only the
;;   fallback branch is reachable here; the variable branch is exercised by
;;   any real XDG setup and is a straight `or`, not logic worth a fixture.
;; - load-settings' `(or path (default-config-path))` nil branch: reaching it
;;   reads the developer's own ~/.config/cljtoc/config.edn, which would make
;;   the suite depend on the machine it runs on. default-config-path is
;;   asserted directly instead.
;; ---------------------------------------------------------------------------
