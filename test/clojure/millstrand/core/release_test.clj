(ns millstrand.core.release-test
  "Tests for product release identity validation."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millstrand.core.internal.release :as release]
            [millstrand.spools.test-support :as test-support]))

(defn- generation-basis
  [source]
  {:reserved-deps
   {'io.millstrand/millstrand {:local/root (.getCanonicalPath source)}}})

(deftest version-reads-the-release-retained-with-source
  (let [source (io/file (test-support/temp-dir "millstrand-release"))]
    (try
      (spit (io/file source "VERSION") "0.5.1\n")
      (is (= "0.5.1" (release/version (generation-basis source))))
      (finally
        (test-support/delete-tree! source)))))

(deftest version-fails-loudly-on-invalid-release-files
  (doseq [[label content] [["prefix" "v0.5.1\n"]
                           ["leading zero" "00.5.1\n"]
                           ["missing newline" "0.5.1"]
                           ["trailing form" "0.5.1\nnext\n"]]]
    (testing label
      (let [source (io/file (test-support/temp-dir "millstrand-release"))]
        (try
          (spit (io/file source "VERSION") content)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"one MAJOR.MINOR.PATCH line"
               (release/version (generation-basis source))))
          (finally
            (test-support/delete-tree! source)))))))

(deftest version-requires-a-regular-release-file
  (let [source (io/file (test-support/temp-dir "millstrand-release"))]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"VERSION is not a regular file"
           (release/version (generation-basis source))))
      (finally
        (test-support/delete-tree! source)))))

(deftest version-requires-the-mill-and-source-release-to-match
  (let [source (io/file (test-support/temp-dir "millstrand-release"))]
    (try
      (spit (io/file source "VERSION") "0.5.1\n")
      (is (= "0.5.1"
             (release/version (generation-basis source) "0.5.1")))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"does not match Mill product version"
           (release/version (generation-basis source) "0.5.0")))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Mill product version is invalid"
           (release/version (generation-basis source) "next")))
      (finally
        (test-support/delete-tree! source)))))
