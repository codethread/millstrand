(ns millstrand.spools.test-support-test
  "Contract tests for repository-only filesystem and Git fixtures."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.spools.test-support :as test-support]))

(deftest temp-dir-creates-fresh-directory
  (let [dir (test-support/temp-dir "millstrand-test-support")]
    (try
      (is (.isDirectory dir))
      (is (not= (.getCanonicalPath dir)
                (.getCanonicalPath (test-support/temp-dir "millstrand-test-support"))))
      (finally
        (test-support/delete-tree! dir)))))

(deftest delete-tree-removes-nested-content-and-is-idempotent
  (let [root (test-support/temp-dir "millstrand-delete-tree")
        nested (io/file root "nested" "child.txt")]
    (try
      (.mkdirs (.getParentFile nested))
      (spit nested "content")
      (test-support/delete-tree! (.toPath root))
      (is (not (.exists root)))
      (is (nil? (test-support/delete-tree! root)))
      (finally
        (test-support/delete-tree! root)))))

(deftest delete-tree-removes-symlink-without-following-directory-target
  (let [base (test-support/temp-dir "millstrand-delete-link")
        outside (io/file base "outside")
        outside-file (io/file outside "keep.txt")
        tree (io/file base "tree")
        link (io/file tree "outside")]
    (try
      (.mkdirs outside)
      (spit outside-file "keep")
      (.mkdirs tree)
      (java.nio.file.Files/createSymbolicLink
       (.toPath link)
       (.toPath outside)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (test-support/delete-tree! tree)
      (is (not (.exists tree)))
      (is (.exists outside-file))
      (finally
        (test-support/delete-tree! base)))))

(deftest delete-tree-rejects-unsupported-input
  (let [error (try
                (test-support/delete-tree! 42)
                nil
                (catch clojure.lang.ExceptionInfo cause
                  cause))]
    (is (= 42 (:value (ex-data error))))
    (is (re-find #"Expected a file, path, or string" (ex-message error)))))

(deftest with-runtime-cleans-config-workspace-after-stop
  (let [config-dir (atom nil)
        generation-basis (atom nil)]
    (test-support/with-runtime
      (fn [runtime workspace]
        (reset! config-dir workspace)
        (reset! generation-basis (:generation-basis runtime))))
    (is (= #{:sources :aliases :reserved-deps :basis :fingerprint :classloader}
           (set (keys @generation-basis))))
    (is (not (.exists ^java.io.File @config-dir)))))

(deftest with-runtime-rejects-removed-fixture-options
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown bare runtime fixture options"
                        (test-support/with-runtime {:release-marker "legacy"}
                          (fn [& _])))))

(deftest run-git-returns-raw-output-and-describes-failure
  (let [root (test-support/temp-dir "millstrand-run-git")]
    (try
      (is (= "git-test\n"
             (test-support/run-git! root "-c" "alias.test=!printf 'git-test\\n'" "test")))
      (let [error (try
                    (test-support/run-git! root "rev-parse" "--verify" "refs/heads/missing")
                    nil
                    (catch clojure.lang.ExceptionInfo cause
                      cause))
            data (ex-data error)]
        (is (= ["rev-parse" "--verify" "refs/heads/missing"] (:args data)))
        (is (integer? (:exit data)))
        (is (string? (:stdout data)))
        (is (string? (:stderr data)))
        (is (pos? (:exit data))))
      (finally
        (test-support/delete-tree! root)))))
