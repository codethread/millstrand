(ns millstrand.core.weaver.bins-test
  "Tests for deps-native bin planning and executable publication."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [millstrand.core.weaver.bins :as bins]
            [millstrand.core.weaver.core-registry :as core-registry]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "bins" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [root]
  (when (.exists (io/file root))
    (doseq [file (reverse (file-seq (io/file root)))]
      (java.nio.file.Files/deleteIfExists (.toPath ^java.io.File file)))))

(defn- runtime [workspace libs]
  {:bin-store (core-registry/backed-registry :bins)
   :generation-basis {:basis {:libs libs}}
   :metadata {:config-dir (.getCanonicalPath (io/file workspace))}})

(defn- publish! [runtime entries]
  (core-registry/replace-owner!
   (:bin-store runtime) :workspace
   {:layer :workspace :entries entries :overrides #{}})
  runtime)

(deftest bins-list-does-not-resolve-anchors
  (let [rt (publish! (runtime "/tmp" {})
                     {"agent" {:name "agent"
                               :doc "Run an agent."
                               :executable [:family "bin/agent"]
                               :provenance 'demo/bins
                               :source/file "/tmp/missing/module.clj"}})
        listed (bins/list-bins rt)]
    (is (= "[:family \"bin/agent\"]" (get-in listed [:bins 0 :executable])))
    (is (s/valid? :millstrand.core.weaver.bins/list-result listed))))

(deftest bins-plan-resolves-both-retained-anchors-from-one-basis-library
  (let [root (temp-dir)]
    (try
      (let [source-root (io/file root "src")
            source (io/file source-root "demo/module.clj")
            executable (io/file root "bin/agent")
            _ (io/make-parents source)
            _ (spit source "(ns demo.module)\n")
            _ (io/make-parents executable)
            _ (spit executable "#!/bin/sh\n")
            _ (.setExecutable executable true false)
            coordinate {:local/root (.getCanonicalPath root)
                        :paths [(.getCanonicalPath source-root)]}
            rt (publish!
                (runtime root {'demo/bins coordinate})
                {"family" {:name "family" :doc "Family anchor."
                            :executable [:family "bin/agent"]
                            :provenance 'demo/bins
                            :source/file (.getCanonicalPath source)}
                 "root" {:name "root" :doc "Root anchor."
                          :executable [:root "bin/agent"]
                          :provenance 'demo/bins
                          :source/file (.getCanonicalPath source)}})]
        (doseq [name ["family" "root"]]
          (let [plan (bins/plan rt name)]
            (is (= (.getCanonicalPath executable) (get-in plan [:exec :path])))
            (is (true? (:runnable plan)))
            (is (s/valid? :millstrand.core.weaver.bins/plan-result plan)))))
      (finally
        (delete-tree! root)))))

(deftest bins-plan-fails-on-ambiguous-or-missing-source-root
  (let [root (temp-dir)]
    (try
      (let [source-root (io/file root "src")
            source (io/file source-root "demo/module.clj")
            coordinate {:local/root (.getCanonicalPath root)
                        :paths [(.getCanonicalPath source-root)]}
            entry {:name "agent" :doc "Ambiguous anchor."
                   :executable [:root "bin/agent"]
                   :provenance 'demo/bins
                   :source/file (.getCanonicalPath source)}]
        (io/make-parents source)
        (spit source "(ns demo.module)\n")
        (doseq [libs [{} {'demo/a coordinate 'demo/b coordinate}]]
          (let [rt (publish! (runtime root libs) {"agent" entry})
                error (try (bins/plan rt "agent") nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (= :bin/anchor-unresolved (-> error ex-data :reason)))
            (is (instance? clojure.lang.ExceptionInfo error)))))
      (finally
        (delete-tree! root)))))

(deftest bins-plan-rejects-invalid-selectors
  (let [rt (runtime "/tmp" {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"bins plan bin selector is invalid"
                          (bins/plan rt 42)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"bins plan bin selector is invalid"
                          (bins/plan rt nil)))))
