(ns millstrand.examples-test
  "Checks for directly runnable repository examples."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest hello-world-prints-exactly-one-greeting-line
  (let [process (-> (ProcessBuilder. ^java.util.List
                     ["clojure" "-M" "examples/hello_world.clj"])
                    (.directory (io/file (System/getProperty "user.dir")))
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (is (zero? exit) output)
    (is (= "Hello, Millstrand!\n" output))))
