(ns millstrand.large-attr-benchmark-test
  "Structural smoke for the large-attr load harness.

  Boots the disposable `:publish? false` world and paired fixtures at tiny `N`,
  runs one iteration of every scenario against the real shipped read paths, and
  asserts each is wired and returns a well-formed sample. Deliberately carries no
  wall-clock assertion (`PLAN-LargeAttrScaling-001.A5`/`TC4`): every timing number
  is informational and lives in the report, never in a gate, so this test cannot
  flake under concurrent suites."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.core.db-test :as db-test]
            [millstrand.large-attr-benchmark :as bench]))

(defn- assert-out-rejected! [out]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                        (bench/run-all (assoc bench/smoke-options :out out)))))

(defn- temp-out-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "large-attr-benchmark-smoke"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-fixtures! [out-dir]
  (doseq [name ["document.sqlite" "eav.sqlite"]]
    (db-test/delete-sqlite-family! (.getPath (io/file out-dir name))))
  (.delete out-dir))

(defn- well-formed-measurement? [m]
  (and (number? (:median-ms m))
       (vector? (:samples-ms m))
       (seq (:samples-ms m))))

(deftest run-all-fails-loudly-on-missing-or-blank-out
  (testing "nil :out"
    (assert-out-rejected! nil))
  (testing "blank :out"
    (assert-out-rejected! "")
    (assert-out-rejected! "   ")))

(deftest harness-wires-every-scenario-at-tiny-n
  (let [out-dir (temp-out-dir)]
    (try
      (let [result (bench/run-all (assoc bench/smoke-options :out (.getPath out-dir)))
            gate (:gate-reproduction result)
            residual (:residual-paths result)]

        (testing "family (a) gate reproduction is wired"
          (let [workloads (:workloads gate)]
            (is (every? workloads [:write-amp :filtered-scan :ready :list-assembly-500])
                "all four gate workloads ran")
            (is (well-formed-measurement? (get-in workloads [:filtered-scan :eav])))
            (is (well-formed-measurement? (get-in workloads [:list-assembly-500 :eav])))
            (is (= #{:write-amp-payload-ge-16kb :write-amp-payload-independence
                     :filtered-scan :ready :list-assembly-500}
                   (set (keys (:checks gate))))
                "informational baseline checks are computed, not gating")))

        (testing "family (b) point read includes archived rows"
          (let [pr (:point-read residual)]
            (is (well-formed-measurement? pr))
            (is (= :point-read-archived-included (:scenario pr)))
            (is (seq (:sample-attribute-keys pr)) "point read returns a full-fidelity attribute map")
            (is (seq (:archived-sample-attribute-keys pr))
                "a fully-archived strand still resolves its attributes on the point-read path")))

        (testing "family (b) lean list/ready assembly applies the 1024-byte floor"
          (doseq [scenario [:lean-list :lean-ready]]
            (let [lean (get residual scenario)]
              (is (well-formed-measurement? lean))
              (is (= (:n bench/smoke-options) (:rows lean)) "lean assembly returned every strand")
              (is (some? (:omitted-descriptor lean))
                  "an over-floor value was replaced by an omission descriptor")
              (is (seq (:sample-attribute-keys lean))))))

        (testing "family (b) unsafe-text-search LIKE spool is wired for hot and archived rows"
          (let [hot (:unsafe-text-search-hot residual)
                archived (:unsafe-text-search-archived residual)]
            (is (well-formed-measurement? hot))
            (is (well-formed-measurement? archived))
            (is (pos? (:rows hot)) "hot unsafe-text-search matched the seeded corpus")
            (is (contains? (:sample hot) :snippet) "unsafe-text-search rows carry the matched snippet")
            (is (false? (:archived? hot)))
            (is (true? (:archived? archived)))
            (is (>= (:rows archived) (:rows hot))
                "the archived branch also scans cold rows the query language cannot see"))))
      (finally
        (delete-fixtures! out-dir)))))
