(ns millstrand.test.bare-runtime-test
  "Exercise the classpath fixture's real storage, activation, and owned cleanup."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.test.alpha :as t])
  (:import [java.util.concurrent TimeUnit]))

(deftest bare-runtimes-isolate-state-and-leave-ambient-selection-alone
  (let [ambient (current/runtime-or-nil)
        contexts (atom [])
        closed (atom 0)
        result
        (t/run-with-bare-runtime
         {}
         (fn [{outer :runtime :as ctx}]
           (swap! contexts conj ctx)
           (is (= :sqlite-file (:storage ctx)))
           (is (.isFile (io/file (:db-path ctx))))
           (is (identical? ambient (current/runtime-or-nil)))
           (doseq [file ["deps.edn" "deps.local.edn"
                         "init.clj" "init.local.clj"]]
             (is (not (.exists (io/file (:config-dir ctx) file)))))
           (let [strand (weaver/add! outer {:title "Outer"})
                 state (runtime/spool-state outer ::state #(atom :outer))]
             (runtime/spool-state outer ::resource
                                  #(hash-map :close-fn (fn [] (swap! closed inc))))
             (t/activate-module! outer ::fixture
                                 'millstrand.test.hyphen-source-fixture)
             (is (contains? (graph/queries outer) "loaded-query"))
             (is (= :unchanged
                    (:status (runtime/refresh! outer {:only [::fixture]}))))
             (current/with-runtime outer
               (t/run-with-bare-runtime
                {:storage :sqlite-memory}
                (fn [{inner :runtime :as inner-ctx}]
                  (swap! contexts conj inner-ctx)
                  (is (identical? outer (current/runtime)))
                  (is (nil? (:db-path inner-ctx)))
                  (is (nil? (weaver/show inner (:id strand))))
                  (is (not (contains? (graph/queries inner) "loaded-query")))
                  (is (empty? (:modules (runtime/status inner))))
                  (is (= :inner @(runtime/spool-state inner ::state #(atom :inner))))
                  (is (= :outer @state)))))
             (is (= "Outer" (:title (weaver/show outer (:id strand))))))
           :result))]
    (is (= :result result))
    (is (= 1 @closed))
    (is (= 2 (count (distinct (map :config-dir @contexts)))))
    (doseq [ctx @contexts]
      (is (not (.exists (io/file (:config-dir ctx)))))))
  (is (nil? (t/run-with-bare-runtime {} (constantly nil)))))

(deftest bare-runtime-preserves-body-error-and-still-closes-everything
  (let [captured (atom nil)
        closed (atom 0)
        body-error (ex-info "Body failed" {})
        close-error (ex-info "Resource close failed" {})
        error (try
                (t/run-with-bare-runtime
                 {:storage :sqlite-memory}
                 (fn [{:keys [runtime] :as ctx}]
                   (reset! captured ctx)
                   (runtime/spool-state
                    runtime ::resource
                    #(hash-map :close-fn (fn []
                                           (swap! closed inc)
                                           (throw close-error))))
                   (throw body-error)))
                (catch Throwable t t))]
    (is (identical? body-error error))
    (is (= 1 @closed) "A failed close is not retried")
    (is (= 1 (count (.getSuppressed ^Throwable error))))
    (is (not (.exists (io/file (:config-dir @captured)))))
    (testing "the memory store was closed despite the resource error"
      (is (thrown? Exception (weaver/list (:runtime @captured)))))))

(deftest bare-runtime-reports-cleanup-error-after-successful-body
  (let [captured (atom nil)
        closed (atom 0)
        error (try
                (t/run-with-bare-runtime
                 {}
                 (fn [{:keys [runtime] :as ctx}]
                   (reset! captured ctx)
                   (runtime/spool-state
                    runtime ::resource
                    #(hash-map :close-fn (fn []
                                           (swap! closed inc)
                                           (throw (ex-info "Close failed" {})))))
                   :success))
                (catch Throwable t t))]
    (is (= "Weaver teardown step failed" (ex-message error)))
    (is (= 1 @closed))
    (is (not (.exists (io/file (:config-dir @captured)))))))

(deftest bare-runtime-cleans-up-after-module-activation-fails
  (let [captured (atom nil)]
    (is (thrown? Exception
                 (t/run-with-bare-runtime
                  {}
                  (fn [{:keys [runtime] :as ctx}]
                    (reset! captured ctx)
                    (t/activate-module! runtime ::missing
                                        'millstrand.test.no-such-module)))))
    (is (not (.exists (io/file (:config-dir @captured)))))))

(deftest bare-runtime-context-and-callback-have-closed-public-contracts
  (let [result (Object.)]
    (is (identical?
         result
         (t/run-with-bare-runtime
          {:name "contract-test"}
          (fn [{:keys [runtime] :as ctx}]
            (is (s/valid? :millstrand.test.alpha/bare-runtime-context ctx))
            (is (= #{:config-dir :state-dir :data-dir :db-path :storage
                     :runtime}
                   (set (keys ctx))))
            (is (= "contract-test" (get-in runtime [:metadata :name])))
            result)))))
  (t/run-with-bare-runtime
   {:storage :sqlite-memory}
   (fn [ctx]
     (is (s/valid? :millstrand.test.alpha/bare-runtime-context ctx))
     (is (= #{:config-dir :state-dir :data-dir :storage :runtime}
            (set (keys ctx))))))
  (doseq [spec [:millstrand.test.alpha/bare-runtime-options
                :millstrand.test.alpha/bare-runtime-callback
                :millstrand.test.alpha/bare-runtime-context]]
    (is (some? (s/get-spec spec))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bare runtime callback"
                        (t/run-with-bare-runtime {} :not-a-function))))

(deftest bare-runtime-refuses-full-workspace-refresh-and-written-dependencies
  (t/run-with-bare-runtime
   {}
   (fn [{:keys [config-dir runtime]}]
     (spit (io/file config-dir "deps.edn")
           "{:deps {example/replacement {:local/root \"elsewhere\"}}}\n")
     (doseq [operation [#(runtime/refresh! runtime)
                        #(runtime/plan runtime)]]
       (let [error (is (thrown-with-msg?
                        clojure.lang.ExceptionInfo
                        #"Full workspace refresh is unsupported"
                        (operation)))]
         (is (= :bare-runtime/full-refresh-unsupported
                (:reason (ex-data error)))))))))

(deftest bare-runtime-cleans-up-after-startup-failure
  (let [captured-root (atom nil)
        callback-called? (atom false)
        startup-error (ex-info "Startup failed" {})
        error
        (with-redefs [weaver-runtime/start!
                      (fn [_ {:keys [world]}]
                        (reset! captured-root (io/file (:config-dir world)))
                        (spit (io/file (:config-dir world) "partial-startup") "owned")
                        (throw startup-error))]
          (try
            (t/run-with-bare-runtime
             {}
             (fn [_]
               (reset! callback-called? true)))
            (catch Throwable t t)))]
    (is (identical? startup-error error))
    (is (false? @callback-called?))
    (is (not (.exists ^java.io.File @captured-root)))))

(deftest bare-runtime-runs-in-a-plain-java-classpath-jvm
  (let [java-bin (.getPath (io/file (System/getProperty "java.home")
                                    "bin" "java"))
        form
        (pr-str
         '(do
            (require '[clojure.java.basis :as java-basis]
                     '[millstrand.api.graph.alpha :as graph]
                     '[millstrand.test.alpha :as test-alpha])
            (when (java-basis/current-basis)
              (throw (ex-info "Plain Java child unexpectedly has tools.deps metadata"
                              {})))
            (test-alpha/run-with-bare-runtime
             {}
             (fn [{:keys [runtime]}]
               (test-alpha/activate-module!
                runtime :test/plain-java
                'millstrand.test.hyphen-source-fixture)
               (when-not (contains? (graph/queries runtime) "loaded-query")
                 (throw (ex-info "Fresh classpath module did not activate" {})))))
            (println "plain-java-bare-runtime-ok")
            (shutdown-agents)))
        output-file (java.io.File/createTempFile
                     "millstrand-plain-java-bare-runtime-" ".log")
        command [java-bin "--enable-native-access=ALL-UNNAMED"
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-e" form]
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    (.redirectOutput output-file)
                    (.start))]
    (try
      (when-not (.waitFor process 90 TimeUnit/SECONDS)
        (.destroyForcibly process)
        (.waitFor process)
        (throw (ex-info "Plain Java bare-runtime child timed out"
                        {:output (slurp output-file)})))
      (let [output (slurp output-file)]
        (is (zero? (.exitValue process)) output)
        (is (re-find #"plain-java-bare-runtime-ok" output) output))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process))
        (java.nio.file.Files/deleteIfExists (.toPath output-file))))))

(deftest bare-runtime-rejects-world-options-and-invalid-storage
  (doseq [opts [{:storage :unknown}
                {:name ""}
                {:root "/not-a-fixture"}
                {:publish? true}
                {:deps-edn "{:deps {}}"}
                {:init-clj "(throw (Exception.))"}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"bare runtime options"
         (t/run-with-bare-runtime
          opts (fn [_] (throw (AssertionError. "Body must not run"))))))))
