(ns millstrand.test.alpha-test
  "Tests for the blessed millstrand.test.alpha weaver-world helpers."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.clock.alpha :as clock]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.test.alpha :as t])
  (:import [java.time Duration Instant]))

(s/def ::widget map?)

(deftest manual-clock-advances-while-uninstalled
  (let [start (Instant/parse "2026-01-01T00:00:00Z")
        manual (t/manual-clock start)]
    (is (= start (clock/now manual)))
    (is (nil? (clock/sleep! manual (Duration/ofMillis 250))))
    (is (= (.plusMillis start 250) (clock/now manual)))
    (is (nil? (clock/sleep! manual Duration/ZERO)))
    (is (= (.plusMillis start 250) (clock/now manual)))))

(deftest installed-manual-clock-drives-runtime-time-and-pumps
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          start (Instant/parse "2026-01-01T00:00:00Z")
          manual (t/manual-clock start)
          pump-count (atom 0)]
      (weaver-runtime/register-clock-pump! rt ::test-pump
                                           (fn [_] (swap! pump-count inc)))
      (is (nil? (t/set-clock! rt manual)))
      (is (identical? manual (runtime/clock rt)))
      (is (= start (runtime/now rt)))
      (is (nil? (clock/sleep! manual Duration/ZERO)))
      (is (= 1 @pump-count) "zero sleep still gives due consumers a pump")
      (is (nil? (clock/sleep! manual (Duration/ofSeconds 2))))
      (is (= (.plusSeconds start 2) (runtime/now rt)))
      (is (= 2 @pump-count))
      (is (= (.plusSeconds start 5) (t/advance! rt (Duration/ofSeconds 3))))
      (is (= 3 @pump-count)))))

(deftest manual-clock-installation-and-advance-fail-loudly
  (t/with-weaver-world [outer {:storage :sqlite-memory}]
    (t/with-weaver-world [inner {:storage :sqlite-memory}]
      (let [outer-rt (:runtime outer)
            inner-rt (:runtime inner)
            manual (t/manual-clock Instant/EPOCH)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Clock"
                              (t/set-clock! outer-rt (constantly Instant/EPOCH))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"manual Clock"
                              (t/advance! outer-rt (Duration/ofSeconds 1))))
        (t/set-clock! outer-rt manual)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only one runtime"
                              (t/set-clock! inner-rt manual)))
        (doseq [duration [nil Duration/ZERO (Duration/ofSeconds -1)]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strictly positive"
                                (t/advance! outer-rt duration))))))))

(deftest replacing-a-manual-clock-detaches-the-old-clock
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          old-clock (t/manual-clock Instant/EPOCH)
          new-clock (t/manual-clock (Instant/ofEpochSecond 10))
          pump-count (atom 0)]
      (weaver-runtime/register-clock-pump! rt ::test-pump
                                           (fn [_] (swap! pump-count inc)))
      (t/set-clock! rt old-clock)
      (t/set-clock! rt new-clock)
      (clock/sleep! old-clock (Duration/ofSeconds 1))
      (is (zero? @pump-count))
      (is (= (Instant/ofEpochSecond 10) (runtime/now rt)))
      (clock/sleep! new-clock Duration/ZERO)
      (is (= 1 @pump-count)))))

(deftest await-quiescent-default-is-self-contained
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (is (= rt (t/await-quiescent! rt))))))

(deftest check-op-return-selects-declared-return-leaves
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)
          flat-shape {:type :map :required {:items {:type :collection :items :integer}}}
          flat-value {:items [1 2]}
          stream-returns {:subcommands
                          {"watch" {:stream {:emits :string
                                             :result [:nullable :boolean]}}}}]
      (weaver/register-op! rt 'flat
                           {:hook-class :mutating
                            :deadline-class :standard
                            :returns flat-shape}
                           'millstrand.test.alpha-test/unused-op)
      (weaver/register-op! rt 'subcommand
                           {:arg-spec {:op "subcommand"
                                       :subcommands {"show" {:hook-class :mutating
                                                             :deadline-class :standard}}}
                            :returns {:subcommands {"show" :integer}}}
                           'millstrand.test.alpha-test/unused-op)
      (weaver/register-op! rt 'stream
                           {:arg-spec {:op "stream"
                                       :subcommands {"watch" {:hook-class :mutating
                                                              :deadline-class :unbounded}}}
                            :stream? true
                            :returns stream-returns}
                           'millstrand.test.alpha-test/unused-op)
      (testing "flat success preserves identity"
        (is (identical? flat-value (t/check-op-return! rt 'flat flat-value))))
      (testing "subcommand result selects by path vector"
        (is (= 42 (t/check-op-return! rt 'subcommand {:subcommand ["show"]} 42))))
      (testing "a scalar subcommand context fails loudly"
        (is (thrown? clojure.lang.ExceptionInfo
                     (t/check-op-return! rt 'subcommand {:subcommand "show"} 42))))
      (testing "stream emitted item and terminal result"
        (is (= "line" (t/check-op-return! rt 'stream
                                          {:subcommand ["watch"] :channel :emits}
                                          "line")))
        (is (nil? (t/check-op-return! rt 'stream
                                      {:subcommand ["watch"] :channel :result}
                                      nil))))
      (testing "nested return trees select by the full path (DELTA-Lhc-001.CC7)"
        (weaver/register-op! rt 'deep
                             {:arg-spec {:op "deep"
                                         :subcommands
                                         {"a" {:subcommands
                                               {"b" {:hook-class :mutating
                                                     :deadline-class :standard}
                                                "c" {:hook-class :mutating
                                                     :deadline-class :standard}}}}}
                              :returns {:subcommands
                                        {"a" {:subcommands {"b" :integer
                                                            "c" :string}}}}}
                             'millstrand.test.alpha-test/unused-op)
        (is (= 7 (t/check-op-return! rt 'deep {:subcommand ["a" "b"]} 7)))
        (is (= "ok" (t/check-op-return! rt 'deep {:subcommand ["a" "c"]} "ok")))
        (doseq [[path reason] [[["a"] :missing-return-subcommand]
                               [["a" "nope"] :unknown-return-subcommand]
                               [["a" "b" "extra"] :unrouted-return-path]]]
          (let [e (is (thrown? clojure.lang.ExceptionInfo
                               (t/check-op-return! rt 'deep {:subcommand path} 7)))]
            (is (= reason (:reason (ex-data e))) (pr-str path))
            (is (= "deep" (:operation (ex-data e)))))))
      (testing "mismatch diagnostics preserve selected declaration and shape path"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"Operation return value does not match declaration"
                                      (t/check-op-return! rt 'flat {:items [1 "bad"]})))]
          (is (= "flat" (:operation (ex-data e))))
          (is (= flat-shape (:declaration (ex-data e))))
          (is (= [:items 1] (:path (ex-data e))))
          (is (= "bad" (:actual (ex-data e)))))))))

(deftest check-op-return-fails-loudly-on-absent-or-misaligned-declarations
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (:runtime ctx)]
      (weaver/register-op! rt 'undeclared
                           {:hook-class :mutating :deadline-class :standard}
                           'millstrand.test.alpha-test/unused-op)
      (weaver/register-op! rt 'flat
                           {:hook-class :mutating
                            :deadline-class :standard
                            :returns :string}
                           'millstrand.test.alpha-test/unused-op)
      (weaver/register-op! rt 'stream
                           {:stream? true
                            :hook-class :mutating
                            :deadline-class :unbounded
                            :returns {:stream {:emits :string :result :boolean}}}
                           'millstrand.test.alpha-test/unused-op)
      (doseq [[operation context value reason]
              [['undeclared {} "value" :missing-return-declaration]
               ['flat {:subcommand "show"} "value" :unexpected-return-subcommand]
               ['flat {:channel :result} "value" :unexpected-return-channel]
               ['stream {} true :missing-return-channel]
               ['stream {:channel :unknown} true :unknown-return-channel]]]
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (t/check-op-return! rt operation context value)))]
          (is (= (name operation) (:operation (ex-data e))))
          (is (= reason (:reason (ex-data e)))))))))

(defn unused-op
  "Test-only registered operation handler; output checks consume captured values."
  [_]
  nil)

(deftest with-weaver-world-runs-file-backed-world-and-cleans-up
  (let [captured (atom nil)
        result (t/with-weaver-world [ctx {}]
                 (reset! captured ctx)
                 (is (= :sqlite-file (:storage ctx)))
                 (is (.isFile (io/file (:db-path ctx))))
                 (is (map? (:metadata ctx)))
                 (is (= (:config-dir ctx) (get-in ctx [:metadata :config-dir])))
                 (testing "quoted forms are rendered and evaluated in the weaver"
                   (let [strand (t/repl! ctx
                                         '(do
                                            (require '[millstrand.api.current.alpha :as current]
                                                     '[millstrand.api.weaver.alpha :as weaver])
                                            (weaver/add! (current/runtime)
                                                         {:title "From repl"})))]
                     (is (= "From repl" (:title strand)))))
                 (is (= 1 (count (t/repl! ctx
                                          '(do
                                             (require '[millstrand.api.current.alpha :as current]
                                                      '[millstrand.api.weaver.alpha :as weaver])
                                             (weaver/list (current/runtime)))))))
                 :done)]
    (is (= :done result))
    (testing "generated workspace root is removed after the body"
      (is (false? (.exists (io/file (:config-dir @captured))))))))

(deftest with-weaver-world-writes-fixtures-and-loads-init
  (t/with-weaver-world [ctx {:config-json "{}\n"
                             :spools-edn {:spools {}}
                             :init (pr-str '(do
                                              (require '[millstrand.api.current.alpha :as current]
                                                       '[millstrand.api.graph.alpha :as graph])
                                              (graph/register-query! (current/runtime)
                                                                     'from-init
                                                                     [:= :state "active"])))
                             :files {"modules/demo.clj" "(ns demo)\n"}}]
    (is (= "{}\n" (slurp (io/file (:config-dir ctx) "config.json"))))
    (is (= "{:spools {}}" (slurp (io/file (:config-dir ctx) "spools.edn"))))
    (is (= "(ns demo)\n" (slurp (io/file (:config-dir ctx) "modules/demo.clj"))))
    (testing "init.clj ran inside the weaver runtime"
      (is (contains? (t/repl! ctx
                              '(do
                                 (require '[millstrand.api.current.alpha :as current]
                                          '[millstrand.api.graph.alpha :as graph])
                                 (graph/queries (current/runtime))))
                     "from-init")))))

(deftest weaver-world-fixture-binds-memory-storage-context
  ((t/weaver-world-fixture {:storage :sqlite-memory})
   (fn []
     (let [ctx t/*weaver-world*]
       (is (= :sqlite-memory (:storage ctx)))
       (is (nil? (:db-path ctx)))
       (is (nil? (get-in ctx [:metadata :canonical-db-path])))
       (is (false? (.exists (io/file (:data-dir ctx) "millstrand.sqlite"))))
       (is (= [] (t/repl! ctx
                          '(do
                             (require '[millstrand.api.current.alpha :as current]
                                      '[millstrand.api.weaver.alpha :as weaver])
                             (weaver/list (current/runtime))))))))))

(deftest weaver-worlds-nest-and-stay-isolated
  (t/with-weaver-world [outer {}]
    (t/with-weaver-world [inner {:storage :sqlite-memory}]
      (t/repl! outer '(do
                        (require '[millstrand.api.current.alpha :as current]
                                 '[millstrand.api.weaver.alpha :as weaver])
                        (weaver/add! (current/runtime) {:title "outer"})))
      (is (= 1 (count (t/repl! outer '(do
                                        (require '[millstrand.api.current.alpha :as current]
                                                 '[millstrand.api.weaver.alpha :as weaver])
                                        (weaver/list (current/runtime)))))))
      (is (= [] (t/repl! inner '(do
                                  (require '[millstrand.api.current.alpha :as current]
                                           '[millstrand.api.weaver.alpha :as weaver])
                                  (weaver/list (current/runtime)))))))))

(deftest with-weaver-world-declares-and-refreshes-one-module-over-new-surface
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :files {"modules/demo.clj"
                  (str "(ns demo.module\n  (:require [millstrand.core.weaver.runtime :as r]))\n"
                       "(r/collect-module-entry! :queries \"demo-q\" [:= [:attr :k] 1])\n")}}]
    (testing "declare-module! applies a default-collector module"
      (let [result (t/declare-module! ctx :demo {:file "modules/demo.clj"})]
        (is (= :applied (:status result)))))
    (testing "module-status reports the desired module offline"
      (is (contains? (:modules (t/module-status ctx)) :demo)))
    (testing "the contributed query is live and an unchanged refresh skips it"
      (is (contains? (graph/queries (:runtime ctx)) "demo-q"))
      (is (= :unchanged (:status (t/refresh-modules! ctx {:only [:demo]})))))
    (testing "plan-modules is an effect-free dry-run"
      (let [planned (t/plan-modules ctx {:only [:demo]})]
        (is (:dry-run? planned))
        (is (= :unchanged (:status planned)))))))

(deftest activate-module-activates-a-namespace-on-a-bare-runtime
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [result (t/activate-module! (:runtime ctx) :test/clock
                                     'millstrand.api.clock.alpha)]
      (is (contains? #{:applied :unchanged} (:status result)))
      (is (= 'millstrand.api.clock.alpha
             (get-in (t/module-status ctx)
                     [:modules :test/clock :ns]))))))

(deftest activate-module-fails-loudly
  (testing "options are closed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                          (t/activate-module! {} :test/module
                                              'millstrand.api.clock.alpha
                                              {:spools []}))))
  (testing "invalid tested option values are actionable"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":after"
                          (t/activate-module! {} :test/module
                                              'millstrand.api.clock.alpha
                                              {:after [:ok "bad"]}))))
  (testing "a refused outcome is preserved in ex-data"
    (let [outcome {:status :refused :remedies ["fix fixture"]}
          error (with-redefs [runtime/module! (fn [& _] outcome)]
                  (try
                    (t/activate-module! {} :test/module
                                        'millstrand.api.clock.alpha)
                    nil
                    (catch clojure.lang.ExceptionInfo throwable throwable)))]
      (is (= outcome (:outcome (ex-data error))))
      (is (= :refused (:module/status (ex-data error)))))))

(deftest collect-module-forms-returns-owner-complete-public-data
  (let [result
        (t/collect-module-forms
         :test/forms 'millstrand.test.alpha-test
         #(do
            (runtime/collect-entry! :queries "sample" [:= [:attr :state] "open"])
            (runtime/collect-lifecycle!
             :bootstrap
             {:kind :seed
              :apply 'millstrand.test.alpha-test/sample-lifecycle})
            (runtime/collect-kind!
             :test/registries
             {:id :test/widgets
              :entry-spec ::widget
              :binding-moment :test/use})
            :thunk-result))]
    (is (= #{:return :contribution :lifecycle :kind-declarations}
           (set (keys result))))
    (is (= :thunk-result (:return result)))
    (is (= [:= [:attr :state] "open"]
           (get-in result [:contribution :queries :entries "sample"])))
    (is (= #{} (get-in result [:contribution :queries :overrides])))
    (is (= :seed (get-in result [:lifecycle :bootstrap :kind])))
    (is (= [{:spool-state/key :test/registries
             :declaration {:id :test/widgets
                           :entry-spec ::widget
                           :binding-moment :test/use}}]
           (:kind-declarations result)))))

(deftest collect-module-forms-fails-loudly-on-invalid-boundaries
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"module-key"
                        (t/collect-module-forms "bad"
                                                'millstrand.test.alpha-test
                                                (constantly nil))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace does not exist"
                        (t/collect-module-forms :test/forms
                                                'missing.test.namespace
                                                (constantly nil))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"thunk must be a function"
                        (t/collect-module-forms :test/forms
                                                'millstrand.test.alpha-test
                                                :callable-but-not-a-function))))

(defn sample-lifecycle
  "Return a deterministic lifecycle marker for collection tests."
  [_]
  :applied)

(deftest spool-checkout-root-resolves-directory-checkouts-from-classpath-entry
  (let [checkout (doto (io/file (System/getProperty "java.io.tmpdir")
                                (str "millstrand-spool-checkout-" (java.util.UUID/randomUUID)))
                   (.mkdirs))
        source-root (io/file checkout "src/main/clojure")
        source-file (io/file source-root "demo/spool.clj")]
    (try
      (io/make-parents source-file)
      (spit (io/file checkout "deps.edn") "{:paths [\"src/main/clojure\"]}\n")
      (spit source-file "(ns demo.spool)\n")
      (let [resource-loader (fn [path]
                              (when (= "demo/spool.clj" path)
                                (.toURL (.toURI source-file))))]
        (is (= (.getCanonicalFile checkout)
               (.getCanonicalFile (t/spool-checkout-root "demo/spool.clj" resource-loader)))))
      (finally
        (doseq [file (reverse (file-seq checkout))]
          (.delete file))))))

(deftest spool-checkout-root-fails-loudly-for-jar-backed-resources
  (let [resource-loader (fn [path]
                          (when (= "demo/spool.clj" path)
                            (java.net.URL. "jar:file:/tmp/demo.jar!/demo/spool.clj")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Spool source is not a directory checkout"
                          (t/spool-checkout-root "demo/spool.clj" resource-loader)))))

(deftest helper-fails-loudly-on-bad-input
  (testing "unknown options"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver world options"
                          (t/run-with-weaver-world {:libs-edn {}} identity))))
  (testing "fixture files must stay inside the workspace root"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must stay inside"
                          (t/run-with-weaver-world {:files {"../escape.txt" "nope"}} identity))))
  (testing "spools-edn must be a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":spools-edn must be an EDN map"
                          (t/run-with-weaver-world {:spools-edn "{:spools {}}"} identity))))
  (testing "startup failures propagate"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed to load"
                          (t/with-weaver-world [_ {:init "(throw (ex-info \"boom\" {}))"}]
                            nil))))
  (testing "weaver-side eval failures throw with weaver context"
    (t/with-weaver-world [ctx {}]
      (let [ex (try
                 (t/repl! ctx '(throw (ex-info "weaver boom" {:detail 1})))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "weaver boom" (:weaver-message (ex-data ex))))))))
