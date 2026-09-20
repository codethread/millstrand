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
                            (assoc % :local/root
                                   (.getCanonicalPath
                                    (io/file workspace-root root)))
                            %))})))

(defn real-init-world-options
  "Return world options for the current consumer dependencies and real init."
  []
  (let [source-root (io/file workspace-root "me")]
    {:storage :sqlite-memory
     :deps-edn (deps-edn)
     :init-clj (slurp (io/file workspace-root "init.clj"))
     :files
     (into {}
           (for [file (file-seq source-root)
                 :when (.isFile file)]
             [(str "me/" (.relativize (.toPath source-root)
                                      (.toPath file)))
              (slurp file)]))}))
