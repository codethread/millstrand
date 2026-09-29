(ns millstrand.ct.consumer-fixture
  "Build disposable worlds from this repository's checked-in consumer config."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private workspace-root
  (.getCanonicalFile (io/file ".millstrand")))

(defn deps-edn
  "Return the current consumer dependencies with usable local coordinates."
  []
  (let [deps (:deps (edn/read-string
                     (slurp (io/file workspace-root "deps.edn"))))]
    (pr-str
     {:deps (update-vals deps
                         #(if-let [root (:local/root %)]
                            (let [root-file (io/file root)
                                  resolved-root (if (.isAbsolute root-file)
                                                  root-file
                                                  (io/file workspace-root root))]
                              (assoc % :local/root
                                     (.getCanonicalPath resolved-root)))
                            %))})))
