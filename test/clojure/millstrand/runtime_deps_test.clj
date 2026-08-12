(ns millstrand.runtime-deps-test
  "Tests for runtime dependency loading against a live weaver."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.test-support :as test-support]))

(defn- write-hot-lib! [config-dir suffix]
  (let [root (io/file config-dir "spools" "runtime-spike")
        ns-sym (symbol (str "runtime-spike.hot-" suffix))
        src-dir (io/file root "src" "runtime_spike")]
    (.mkdirs src-dir)
    (spit (io/file src-dir (str "hot_" suffix ".clj"))
          (str "(ns " ns-sym ")\n(defn marker [] :daemon-hot-added)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    {:root (.getCanonicalPath root)
     :lib (symbol (str "runtime-spike/lib-" suffix))
     :ns ns-sym
     :marker (symbol (str ns-sym "/marker"))}))

(defn- write-maven-spool! [config-dir suffix]
  (let [root (io/file config-dir "spools" "maven-spike")
        ns-sym (symbol (str "maven-spike.hot-" suffix))
        src-dir (io/file root "src" "maven_spike")
        lib (symbol (str "maven-spike/lib-" suffix))]
    (.mkdirs src-dir)
    (spit (io/file src-dir (str "hot_" suffix ".clj"))
          (str "(ns " ns-sym ")\n"
               "(defn marker []\n"
               "  (binding [*use-context-classloader* true]\n"
               "    (ffirst ((requiring-resolve 'clojure.data.csv/read-csv) \"daemon-maven-added\"))))\n"))
    (spit (io/file root "deps.edn")
          (pr-str {:paths ["src"]
                   :deps {'org.clojure/data.csv {:mvn/version "1.1.0"}}}))
    (spit (io/file config-dir "spools.edn")
          (pr-str {:spools {lib {:local/root "spools/maven-spike"}}}))
    {:root (.getCanonicalPath root)
     :lib lib
     :ns ns-sym
     :marker (symbol (str ns-sym "/marker"))}))

(defn- daemon-value [rt form]
  (with-open [conn (nrepl/connect :host (get-in rt [:metadata :endpoint :host])
                                  :port (get-in rt [:metadata :endpoint :port]))]
    (let [responses (doall (nrepl/message (nrepl/client conn 90000)
                                          {:op "eval" :code (pr-str form)}))]
      (when-let [ex (some :ex responses)]
        (throw (ex-info "Daemon eval threw" {:exception ex :responses responses})))
      (if (some #(some #{"done"} (:status %)) responses)
        (some :value responses)
        (throw (ex-info "nREPL client drained without a done status (Maven resolution likely exceeded the client timeout)"
                        {:responses responses}))))))

(deftest daemon-runtime-can-hot-add-config-dir-local-root
  (let [config-dir (test-support/temp-dir "millstrand-runtime-deps-config")
        suffix (str "s" (str/replace (str (java.util.UUID/randomUUID)) "-" ""))]
    (try
      (let [world (test-support/test-world (.getCanonicalPath config-dir))
            rt (weaver-runtime/start! nil {:world world})
            {:keys [root lib marker] lib-ns :ns} (write-hot-lib! config-dir suffix)]
        (try
          (is (= ":missing"
                 (daemon-value rt `(try (require '~lib-ns)
                                        :present
                                        (catch java.io.FileNotFoundException _#
                                          :missing)))))
          (spit (io/file config-dir "spools.edn")
                (pr-str {:spools {lib {:local/root root}}}))
          (is (= ":applied"
                 (daemon-value rt `(do (require 'millstrand.api.current.alpha
                                                'millstrand.api.runtime.alpha)
                                       (:status
                                        (millstrand.api.runtime.alpha/module!
                                         (millstrand.api.current.alpha/runtime)
                                         :runtime-spike
                                         {:spools ['~lib]
                                          :ns '~lib-ns}))))))
          (is (= ":daemon-hot-added"
                 (daemon-value rt `(do (require 'millstrand.api.current.alpha
                                                'millstrand.api.runtime.alpha)
                                       ((requiring-resolve '~marker))))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (test-support/delete-tree! config-dir)))))

(deftest module-refresh-loads-maven-deps-before-activation
  (let [config-dir (test-support/temp-dir "millstrand-runtime-maven-spool-config")
        suffix (str "s" (str/replace (str (java.util.UUID/randomUUID)) "-" ""))]
    (try
      (let [world (test-support/test-world (.getCanonicalPath config-dir))
            rt (weaver-runtime/start! nil {:world world})
            {:keys [lib marker] lib-ns :ns} (write-maven-spool! config-dir suffix)]
        (try
          (is (= ":applied"
                 (daemon-value rt `(do (require 'millstrand.api.current.alpha
                                                'millstrand.api.runtime.alpha)
                                       (:status (millstrand.api.runtime.alpha/module!
                                                 (millstrand.api.current.alpha/runtime)
                                                 :maven-spike
                                                 {:spools ['~lib]
                                                  :ns '~lib-ns}))))))
          (is (= "\"daemon-maven-added\""
                 (daemon-value rt `(do (require 'millstrand.api.current.alpha
                                                'millstrand.api.runtime.alpha)
                                       ((requiring-resolve '~marker))))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (test-support/delete-tree! config-dir)))))
