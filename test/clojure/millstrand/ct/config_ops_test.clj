(ns millstrand.ct.config-ops-test
  "Focused tests for pure repo-local config operation projections."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.spools.test-support :as test-support]))

(deftest embedded-spools-edn-validates-before-rewriting
  (let [source (java.io.File/createTempFile "millstrand-embedded-spools" ".edn")
        source-path (.getCanonicalPath source)
        read-config (fn [config]
                      (spit source (pr-str config))
                      (test-support/embedded-spools-edn source-path))]
    (try
      (testing "relative local roots become source-relative canonical paths"
        (is (= (.getCanonicalPath (io/file (.getParentFile source) "spools/demo"))
               (get-in (read-config {:spools {'demo/local {:local/root "spools/demo"}}})
                       [:spools 'demo/local :local/root]))))
      (testing "malformed shapes fail with actionable source and family context"
        (doseq [[config family received expected-shape]
                [[{} nil nil "a :spools map"]
                 [{:spools []} nil [] "a :spools map"]
                 [{:spools {"demo/bad" {}}} "demo/bad" "demo/bad" "a symbol family key"]
                 [{:spools {'demo/bad []}} 'demo/bad [] "a map family entry"]
                 [{:spools {'demo/bad {:local/root 42}}} 'demo/bad 42
                  "a non-blank string :local/root"]]]
          (let [error (try
                        (read-config config)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? error))
            (is (= {:source-path source-path
                    :family family
                    :received received
                    :received-type (if (nil? received) "nil" (.getName (class received)))
                    :expected-shape expected-shape}
                   (ex-data error))))))
      (finally
        (io/delete-file source true)))))
