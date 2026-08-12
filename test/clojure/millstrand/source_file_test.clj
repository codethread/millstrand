(ns millstrand.source-file-test
  "Tests for the quoted-forms source-file rendering helper."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.source-file :as source-file]))

(deftest render-forms-round-trips-forms-one-per-line
  (let [forms ['(require '[millstrand.api.current.alpha :as current])
               '(let [rt (current/runtime)]
                  (prn rt))
               :shared]
        rendered (source-file/render-forms forms)]
    (is (= forms (read-string (str "[" rendered "]"))))
    (is (= 3 (count (str/split-lines rendered))))
    (is (str/ends-with? rendered "\n"))))

(deftest render-forms-ignores-ambient-print-truncation
  (binding [*print-length* 1
            *print-level* 1]
    (is (= "[1 2 [3 4]]\n" (source-file/render-forms ['[1 2 [3 4]]])))))

(deftest render-forms-rejects-a-bare-form
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vector of top-level forms"
                        (source-file/render-forms '(require '[millstrand.api.current.alpha :as current])))))

(deftest spit-forms!-writes-rendered-source
  (let [file (java.io.File/createTempFile "source-file-test" ".clj")]
    (try
      (source-file/spit-forms! file ['(ns demo.spit-forms)
                                     :marker])
      (is (= "(ns demo.spit-forms)\n:marker\n" (slurp file)))
      (finally
        (.delete file)))))
