(ns millstrand.runtime.integration-test
  "Runtime release-marker, module, reload, and config integration coverage."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.core.specs :as specs]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.module-graph :as module-graph]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.spool-sync :as spool-sync]
            [millstrand.spools.test-support :as test-support]))

(defn- git-source! [tag]
  (let [root (test-support/temp-dir "millstrand-marker-source")]
    (test-support/run-git! root "init" "--quiet")
    (test-support/run-git! root "config" "user.email" "millstrand-test@example.invalid")
    (test-support/run-git! root "config" "user.name" "Millstrand Test")
    (spit (io/file root "source.txt") "source\n")
    (test-support/run-git! root "add" "source.txt")
    (test-support/run-git! root "commit" "--quiet" "-m" "fixture")
    (when tag
      (test-support/run-git! root "tag" "-a" tag "-m" tag))
    root))

(defn- compiled-runtime-resource! [source-root]
  (let [resource (io/file source-root
                          "target/classes/millstrand/core/weaver/runtime.clj")]
    (.mkdirs (.getParentFile resource))
    (spit resource "compiled fixture\n")
    (.toURL (.toURI resource))))

(defn- test-world [root]
  (weaver-config/world (str (io/file root "config"))
                       (str (io/file root "state"))
                       (str (io/file root "data"))))

(defn- with-source-root [source-root f]
  (with-redefs-fn {(ns-resolve 'millstrand.core.weaver.runtime 'source-checkout-root)
                   (constantly source-root)}
    f))

(defn- with-runtime-resource [resource-url f]
  (let [source-checkout-root (deref (ns-resolve 'millstrand.core.weaver.runtime
                                                'source-checkout-root))]
    (with-redefs-fn {(ns-resolve 'millstrand.core.weaver.runtime 'source-checkout-root)
                     #(source-checkout-root resource-url)}
      f)))

(defn- with-started-runtime [source-root opts f]
  (let [root (test-support/temp-dir "millstrand-marker-world")
        world (test-world root)]
    (try
      (let [rt (with-source-root
                 source-root
                 #(weaver-runtime/start! nil (merge {:world world
                                                     :publish? false
                                                     :storage :sqlite-memory}
                                                    opts)))]
        (try
          (f rt world)
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (test-support/delete-tree! root)))))

(deftest spool-state-opts-spec-owns-public-shape
  (is (s/valid? ::runtime/spool-state-opts nil))
  (is (s/valid? ::runtime/spool-state-opts {:version :v2 :migrate-fn identity}))
  (doseq [opts [{:versoin 2}
                {:version nil}
                {:version 1.5}
                {:version 1 :migrate-fn 5}
                {:migrate-fn identity}]]
    (is (not (s/valid? ::runtime/spool-state-opts opts)))))

(deftest release-marker-resolution
  (let [source-root (git-source! "v3")]
    (try
      (testing "an explicit claim beats an annotated tag on source HEAD"
        (with-started-runtime
          source-root
          {:release-marker "v7"}
          (fn [rt _]
            (is (= {:marker "v7" :provenance :claimed}
                   (runtime/release-marker rt))))))
      (testing "an annotated release tag on source HEAD is used without a claim"
        (with-started-runtime
          source-root
          {}
          (fn [rt _]
            (is (= {:marker "v3" :provenance :tag}
                   (runtime/release-marker rt))))))
      (finally
        (test-support/delete-tree! source-root))))
  (testing "an untagged source checkout has an explicit none shape"
    (let [source-root (git-source! nil)]
      (try
        (with-started-runtime
          source-root
          {}
          (fn [rt _]
            (is (= {:marker nil :provenance :none}
                   (runtime/release-marker rt)))))
        (finally
          (test-support/delete-tree! source-root))))))

(deftest compiled-classpath-release-marker-resolution
  (let [source-root (git-source! "v4")
        world-root (test-support/temp-dir "millstrand-compiled-marker-world")
        resource-url (compiled-runtime-resource! source-root)]
    (try
      (with-runtime-resource
        resource-url
        #(let [rt (weaver-runtime/start! nil {:world (test-world world-root)
                                              :publish? false
                                              :storage :sqlite-memory})]
           (try
             (is (= {:marker "v4" :provenance :tag}
                    (runtime/release-marker rt)))
             (finally
               (weaver-runtime/stop! rt)))))
      (finally
        (test-support/delete-tree! world-root)
        (test-support/delete-tree! source-root)))))

(deftest non-repo-classpath-release-marker-resolution
  (let [source-root (test-support/temp-dir "millstrand-non-git-source")
        world-root (test-support/temp-dir "millstrand-git-failure-world")
        resource-url (compiled-runtime-resource! source-root)]
    (try
      (with-runtime-resource
        resource-url
        #(let [rt (weaver-runtime/start! nil {:world (test-world world-root)
                                              :publish? false
                                              :storage :sqlite-memory})]
           (try
             (is (= {:marker nil :provenance :none}
                    (runtime/release-marker rt)))
             (finally
               (weaver-runtime/stop! rt)))))
      (finally
        (test-support/delete-tree! world-root)
        (test-support/delete-tree! source-root)))))

(deftest git-inspection-command-failures-remain-loud
  (let [source-root (git-source! nil)
        run-git (deref (ns-resolve 'millstrand.core.weaver.runtime 'run-git))]
    (try
      (doseq [[args expected-exit]
              [[["rev-parse" "--verify" "refs/heads/missing"] 128]
               [["-c" "alias.noisy=!echo warning >&2" "noisy"] 0]]]
        (let [error (try
                      (apply run-git source-root args)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :git-inspection-failed (:reason data)))
          (is (= expected-exit (:exit data)))
          (is (not (str/blank? (:stderr data))))))
      (finally
        (test-support/delete-tree! source-root)))))

(deftest runtime-result-specs-own-public-shapes
  (is (s/valid? ::specs/release-marker-syntax "v0"))
  (is (not (s/valid? ::specs/release-marker-syntax "v01")))
  (is (s/valid? ::specs/release-marker-claim "v12"))
  (is (not (s/valid? ::specs/release-marker-claim "v0")))
  (is (s/valid? ::specs/release-marker-result
                {:marker nil :provenance :none}))
  (is (not (s/valid? ::specs/release-marker-result
                     {:marker "v2" :provenance :none})))
  (is (s/valid? ::specs/weaver-start-options
                {:world (test-world (io/file "/tmp/millstrand-start-options"))
                 :name nil
                 :publish? false
                 :storage :sqlite-memory
                 :release-marker "v2"}))
  (is (s/valid? ::specs/weaver-start-options
                {:world (dissoc (test-world (io/file "/tmp/millstrand-start-options"))
                                :config-file)}))
  (is (not (s/valid? ::specs/weaver-start-options
                     {:world (test-world (io/file "/tmp/millstrand-start-options"))
                      :unknown true})))
  (is (s/valid? ::specs/config-dir-result "/tmp/config"))
  (is (s/valid? ::specs/spools-file-result (io/file "/tmp/config/spools.edn"))))

(def ^:private applied-refresh-result
  {:status :applied
   :mode :full
   :modules {:demo {:module/key :demo :status :applied}}
   :roots {}
   :residuals []
   :conflicts []
   :remedies []
   :declaration/shadows {}
   :publication/kinds [:queries]})

(deftest live-module-result-specs-own-public-shapes
  (testing "refresh-result requires status, mode, and vectors"
    (is (s/valid? ::runtime/refresh-result applied-refresh-result))
    (is (s/valid? ::runtime/refresh-result
                  {:status :refused :mode :targeted :modules {} :roots {}
                   :residuals [] :conflicts [{:reason :boom}] :remedies []}))
    (is (not (s/valid? ::runtime/refresh-result
                       (assoc applied-refresh-result :status :bogus))))
    (is (not (s/valid? ::runtime/refresh-result
                       (dissoc applied-refresh-result :mode))))
    (is (not (s/valid? ::runtime/refresh-result
                       (assoc applied-refresh-result :residuals nil)))))
  (testing "plan-result is a refresh-result flagged dry-run with a caveat"
    (let [planned (assoc applied-refresh-result :dry-run? true :caveat "loads recorded")]
      (is (s/valid? ::runtime/plan-result planned))
      (is (not (s/valid? ::runtime/plan-result applied-refresh-result)))
      (is (not (s/valid? ::runtime/plan-result (assoc planned :caveat ""))))))
  (testing "status-result requires joined maps and a last-refresh slot"
    (let [status {:modules {} :declaration/layers {} :declaration/shadows {}
                  :contributions {} :module/outcomes {} :resource/outcomes {}
                  :root/outcomes {}
                  :loaded {} :pending-generation nil :last-refresh nil}]
      (is (s/valid? ::runtime/status-result status))
      (is (not (s/valid? ::runtime/status-result (dissoc status :last-refresh))))
      (is (not (s/valid? ::runtime/status-result (dissoc status :pending-generation))))
      (is (not (s/valid? ::runtime/status-result
                         (assoc status :pending-generation :bogus))))
      (is (not (s/valid? ::runtime/status-result (assoc status :loaded :nope))))))
  (testing "reload-code-result names the reloaded root plus residual outcomes"
    (let [result {:root-lib 'demo/root
                  :root "/tmp/root"
                  :namespaces [{:ns 'demo.core :file "/tmp/root/demo/core.clj"}]
                  :residuals []
                  :hard-conflicts []}]
      (is (s/valid? ::runtime/reload-code-result result))
      (is (not (s/valid? ::runtime/reload-code-result (assoc result :extra 1))))
      (is (not (s/valid? ::runtime/reload-code-result (dissoc result :residuals))))))
  (testing "module-result covers both staged and refreshed shapes"
    (is (s/valid? ::runtime/module-result
                  {:module/key :demo
                   :module/declaration {:file "modules/demo.clj"
                                        :spools [] :after [] :required? false}
                   :staged? true}))
    (is (s/valid? ::runtime/module-result applied-refresh-result))
    (is (not (s/valid? ::runtime/module-result {:staged? false}))))
  (testing "module-declaration accepts only :image for the optional :load key"
    (let [image-declaration {:ns 'millstrand.api.runtime.alpha-test :load :image
                             :spools [] :after [] :required? false}]
      (is (s/valid? ::runtime/module-declaration image-declaration))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration :load :classpath))))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration
                                :contribute
                                'millstrand.api.runtime.alpha-test/image-contribute))))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration
                                :reconcile
                                'millstrand.api.runtime.alpha-test/image-contribute))))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration :extra :unsupported))))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration :spools [1]))))
      (is (not (s/valid? ::runtime/module-declaration
                         (assoc image-declaration :after [:ok 2]))))))
  (testing "module-opts names the public input grammar module! consults"
    (let [image-opts {:ns 'millstrand.api.runtime.alpha-test :load :image}]
      (is (s/valid? ::runtime/module-opts image-opts)
          "image opts are a source target plus world policy, nothing else")
      (is (s/valid? ::runtime/module-opts {:file "modules/demo.clj"}))
      (is (s/valid? ::runtime/module-opts
                    {:ns 'demo.ns :spools ['demo/root] :after [:other]
                     :required? true}))
      (is (not (s/valid? ::runtime/module-opts
                         (assoc image-opts :file "modules/demo.clj"))))
      (is (not (s/valid? ::runtime/module-opts (assoc image-opts :load :classpath))))
      (is (not (s/valid? ::runtime/module-opts (assoc image-opts :unknown 1))))
      (is (not (s/valid? ::runtime/module-opts {:ns 'demo.ns :file "modules/demo.clj"})))
      (is (not (s/valid? ::runtime/module-opts {:ns (symbol "")})))
      (is (not (s/valid? ::runtime/module-opts {:file ""})))
      (is (not (s/valid? ::runtime/module-opts {:file "/absolute/demo.clj"})))
      (is (not (s/valid? ::runtime/module-opts
                         {:file "modules/demo.clj" :load :image})))
      (is (not (s/valid? ::runtime/module-opts {:ns 'demo.ns :required? :yes})))))
  (testing "module opts reject withdrawn callback keys"
    (is (not (s/valid? ::runtime/module-opts
                       {:ns 'demo.ns :contribute 'demo.ns/contribute}))
        ":contribute is no longer module grammar")
    (is (not (s/valid? ::runtime/module-opts
                       {:ns 'demo.ns :reconcile 'demo.ns/reconcile}))
        ":reconcile is no longer module grammar")
    (is (not (s/valid? ::runtime/module-opts
                       {:file "modules/demo.clj"
                        :contribute 'demo.ns/contribute
                        :reconcile 'demo.ns/reconcile}))))
  (testing "refresh-opts names the option grammar refresh!/plan consult"
    (is (s/valid? ::runtime/refresh-opts {}))
    (is (s/valid? ::runtime/refresh-opts {:only [:demo]}))
    (is (not (s/valid? ::runtime/refresh-opts {:only []})))
    (is (not (s/valid? ::runtime/refresh-opts {:only ["demo"]})))
    (is (not (s/valid? ::runtime/refresh-opts {:bogus true}))))
  (testing "collect-entry opts are closed to a boolean :override?"
    (is (s/valid? ::runtime/collect-entry-opts {}))
    (is (s/valid? ::runtime/collect-entry-opts {:override? true}))
    (is (not (s/valid? ::runtime/collect-entry-opts {:override? :yes})))
    (is (not (s/valid? ::runtime/collect-entry-opts {:unknown true})))))

(defn- refusal
  "Return the loud refusal `f` throws, or nil when it returns."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- write-module-source! [config-dir relative-path ns-sym body]
  (let [file (io/file config-dir relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file (str "(ns " ns-sym "\n  (:require [millstrand.core.weaver.runtime :as r]))\n"
                    body "\n"))
    file))

(deftest lifecycle-forms-run-through-the-production-coordinator
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            ns-sym 'millstrand.runtime.alpha-test.lifecycle-module
            source
            (str "(defonce calls (atom []))\n"
                 "(defn open! [_] (swap! calls conj :open) :handle)\n"
                 "(defn close! [{:keys [resource]}] "
                 "(swap! calls conj [:close resource]) {:closed true})\n"
                 "(require '[millstrand.api.lifecycle.alpha :as lifecycle])\n"
                 "(lifecycle/defresource worker \"Worker.\" "
                 "{:open 'millstrand.runtime.alpha-test.lifecycle-module/open! "
                 ":close 'millstrand.runtime.alpha-test.lifecycle-module/close!})")]
        (.mkdirs config-dir)
        (write-module-source! config-dir "modules/lifecycle.clj" ns-sym source)
        (let [applied
              (runtime/module! rt :lifecycle {:file "modules/lifecycle.clj"})
              calls-var (ns-resolve ns-sym 'calls)]
          (is (= :applied (:status applied)))
          (is (= [:open] @(var-get calls-var)))
          (is (= :applied
                 (get-in applied
                         [:modules :lifecycle :lifecycle/status]))))
        (let [planned (runtime/plan rt {:only [:lifecycle]})]
          (is (= [:worker]
                 (get-in planned
                         [:modules :lifecycle :lifecycle/plan :preserve])))
          (is (= [:open] @(var-get (ns-resolve ns-sym 'calls)))
              "plan runs no lifecycle effect"))
        (write-module-source! config-dir "modules/lifecycle.clj" ns-sym "")
        (runtime/refresh! rt {:only [:lifecycle]})
        (is (= [:open [:close :handle]]
               @(var-get (ns-resolve ns-sym 'calls))))))))

(deftest new-surface-declares-refreshes-plans-and-reports-one-module
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            ns-sym 'millstrand.runtime.alpha-test.demo-module]
        (.mkdirs config-dir)
        (write-module-source!
         config-dir "modules/demo.clj" ns-sym
         "(r/collect-module-entry! :queries \"demo-q\" [:= [:attr :k] 1])")
        (testing "module! on a default-collector module applies and validates"
          (let [result (runtime/module! rt :demo {:file "modules/demo.clj"})]
            (is (= :applied (:status result)))
            (is (s/valid? ::runtime/module-result result))
            (is (= [:= [:attr :k] 1] (get (graph/queries rt) "demo-q")))))
        (testing "status is offline and reports the desired module"
          (let [status (runtime/status rt)]
            (is (s/valid? ::runtime/status-result status))
            (is (contains? (:modules status) :demo))
            (is (= "modules/demo.clj" (get-in status [:modules :demo :file])))))
        (testing "an unchanged targeted refresh skips publication"
          (let [again (runtime/refresh! rt {:only [:demo]})]
            (is (= :unchanged (:status again)))
            (is (s/valid? ::runtime/refresh-result again))))
        (write-module-source!
         config-dir "modules/demo.clj" ns-sym
         "(r/collect-module-entry! :queries \"demo-q\" [:= [:attr :k] 2])")
        (testing "plan is an effect-free dry-run of the pending change"
          (let [planned (runtime/plan rt {:only [:demo]})]
            (is (:dry-run? planned))
            (is (s/valid? ::runtime/plan-result planned))
            (is (string? (:caveat planned)))
            (is (= :applied (:status planned))
                "plan reports the intended publication")
            (is (= [:= [:attr :k] 1] (get (graph/queries rt) "demo-q"))
                "plan publishes nothing")))
        (testing "refresh! applies the pending change"
          (let [applied (runtime/refresh! rt {:only [:demo]})]
            (is (= :applied (:status applied)))
            (is (= [:= [:attr :k] 2] (get (graph/queries rt) "demo-q")))))
        (testing "malformed options and declarations fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                                (runtime/refresh! rt {:only []})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                                (runtime/refresh! rt {:bogus true})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                                (runtime/refresh! rt {:only [:missing]})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                                (runtime/module! rt :bad {:file "modules/demo.clj"
                                                          :ns 'demo.ns}))))
        (testing "entry-point keys are refused outright, with no fallback"
          (let [refuse (fn [key opts] (refusal #(runtime/module! rt key opts)))
                authored (fn [key opts]
                           (refusal #(module-graph/normalize-declaration key opts)))
                qualified-opts {:file "modules/demo.clj"
                                :contribute 'millstrand.api.runtime.alpha-test/image-contribute}
                unqualified-opts {:ns ns-sym :contribute 'contribute}
                reconcile-opts {:ns ns-sym :reconcile 'reconcile}
                both-opts {:ns ns-sym :contribute 'contribute :reconcile 'reconcile}
                qualified (refuse :legacy-contribute qualified-opts)
                unqualified (refuse :legacy-unqualified unqualified-opts)
                reconcile (refuse :legacy-reconcile reconcile-opts)
                both (refuse :legacy-both both-opts)]
            (is (= {:reason :removed-module-opts-keys
                    :module/key :legacy-contribute
                    :removed [:contribute]}
                   (select-keys (ex-data qualified)
                                [:reason :module/key :removed]))
                "the refusal names the removed key and the module it came from")
            (is (= [:reconcile] (:removed (ex-data reconcile))))
            (is (= [:contribute :reconcile] (:removed (ex-data both)))
                "a declaration naming both keys names both in its refusal")
            (doseq [[module-key opts error] [[:legacy-contribute qualified-opts qualified]
                                             [:legacy-unqualified unqualified-opts unqualified]
                                             [:legacy-reconcile reconcile-opts reconcile]
                                             [:legacy-both both-opts both]]
                    :let [direct (authored module-key opts)]]
              (is (some? error) (str "expected a refusal for " module-key))
              (is (= (ex-message direct) (ex-message error))
                  "the boundary repeats the coordinator's refusal message verbatim")
              (is (= (ex-data direct) (ex-data error))
                  "and its ex-data, so one contract covers every authoring route")
              (is (str/includes? (ex-message error) "no longer name entry points")
                  "the message classes the failure as a removed key")
              (is (str/includes? (:remedy (ex-data error))
                                 "millstrand/def*")
                  "the remedy directs the author at the replacement forms"))
            (is (not (str/includes? (ex-message unqualified) "qualified"))
                "an unqualified value is never told to qualify the removed key")
            (is (empty? (select-keys (:modules (runtime/status rt))
                                     [:legacy-contribute :legacy-unqualified
                                      :legacy-reconcile :legacy-both]))
                "a refused declaration is not recorded")))))))

(defn- image-spool-namespace!
  "Intern the withdrawn public `spool` var in a fresh image namespace."
  []
  (let [ns-sym 'millstrand.api.runtime.alpha-test.image-target]
    (intern (create-ns ns-sym) 'spool {:contribute 'contribute})
    ns-sym))

(deftest image-module-declaration-activates-and-validates
  (with-started-runtime
    nil
    {}
    (fn [rt _world]
      (testing "an image declaration rejects a public spool var"
        (let [result (runtime/module! rt :image
                                      {:ns (image-spool-namespace!)
                                       :load :image})
              outcome (get-in result [:modules :image])]
          (is (= :partial (:status result)))
          (is (s/valid? ::runtime/module-result result))
          (is (= :removed-def-spool (get-in outcome [:error :data :reason])))
          (is (str/includes? (get-in outcome [:error :message])
                             "removed public spool entry point"))))
      (testing "an image namespace with no spool var fails at evaluation"
        (let [result (runtime/module! rt :image-bare
                                      {:ns 'millstrand.api.runtime.alpha-test :load :image})
              outcome (get-in result [:modules :image-bare])]
          (is (= :partial (:status result)))
          (is (= :failed (:status outcome)))
          (is (= :image (get-in outcome [:error :data :load])))))
      (testing "image grammar refusals throw from module! with actionable data"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accepts only :image"
                              (runtime/module! rt :image-bad
                                               {:ns 'millstrand.api.runtime.alpha-test
                                                :load :classpath})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"accepts only an :ns source target"
                              (runtime/module! rt :image-bad
                                               {:file "modules/demo.clj"
                                                :load :image})))))))

(deftest reload-code-composes-code-reload-and-residual-classification
  (with-started-runtime
    nil
    {}
    (fn [rt _world]
      (let [reload-calls (atom 0)]
        (with-redefs [spool-sync/reload-synced-spool!
                      (fn [_runtime root-lib]
                        (swap! reload-calls inc)
                        {:root-lib root-lib
                         :root "/tmp/demo-root"
                         :namespaces [{:ns 'demo.core
                                       :file "/tmp/demo-root/demo/core.clj"}]})
                      spool-sync/loaded-namespace-status
                      (fn [_runtime]
                        {:residuals [{:reason :changed-bytes :namespace 'demo.core}]
                         :hard-conflicts []})]
          (let [result (runtime/reload-code! rt 'demo/root)]
            (is (= 1 @reload-calls))
            (is (= 'demo/root (:root-lib result)))
            (is (= [{:reason :changed-bytes :namespace 'demo.core}]
                   (:residuals result)))
            (is (s/valid? ::runtime/reload-code-result result))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (runtime/reload-code! rt "not-a-symbol")))))))

(deftest start-options-are-validated-before-destructuring
  (doseq [opts [nil {:unknown true} {:publish? :yes}]]
    (let [error (try
                  (weaver-runtime/start! nil opts)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-start-options (:reason (ex-data error))))
      (is (= ::specs/weaver-start-options (:spec (ex-data error)))))))

(deftest malformed-release-marker-claims-fail-loudly
  (doseq [claim ["1" "v01" "v-1" :v2]]
    (let [root (test-support/temp-dir "millstrand-invalid-marker")]
      (try
        (let [error (try
                      (with-source-root
                        nil
                        #(weaver-runtime/start! nil {:world (test-world root)
                                                     :publish? false
                                                     :storage :sqlite-memory
                                                     :release-marker claim}))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid-release-marker (:reason (ex-data error))))
          (is (= claim (:marker (ex-data error)))))
        (finally
          (test-support/delete-tree! root))))))

(deftest v0-release-marker-claim-is-reserved
  (let [root (test-support/temp-dir "millstrand-v0-marker")]
    (try
      (let [error (try
                    (with-source-root
                      nil
                      #(weaver-runtime/start! nil {:world (test-world root)
                                                   :publish? false
                                                   :storage :sqlite-memory
                                                   :release-marker "v0"}))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (re-find #"v0 is reserved" (ex-message error)))
        (is (= {:reason :reserved-release-marker
                :marker "v0"
                :provenance :claimed}
               (ex-data error))))
      (finally
        (test-support/delete-tree! root)))))

(deftest spool-config-write-specs-own-public-shapes
  (let [entry {:git/url "https://example.invalid/demo.git"
               :git/sha (str/join (repeat 40 "a"))
               :git/tag "v1"}]
    (is (s/valid? ::runtime/spool-family 'demo/family))
    (is (s/valid? ::runtime/spool-entry entry))
    (is (s/valid? ::runtime/spool-write-result
                  {:status :inserted
                   :lib 'demo/family
                   :entry entry
                   :file (io/file "/tmp/spools.edn")}))
    (is (not (s/valid? ::runtime/spool-entry (assoc entry :git/sha "short"))))
    (is (not (s/valid? ::runtime/spool-write-result
                       {:status :inserted :lib 'demo/family :entry entry})))))

(deftest approved-surfaces-declared-and-effective-family-config
  (with-started-runtime
    nil
    {:release-marker "v3"}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            sha (str/join (repeat 40 "b"))
            declared {:git/url "https://example.invalid/demo.git"
                      :git/sha sha
                      :git/tag "v2"
                      :roots {'demo/root "."}
                      :requires {'demo/root "v1"}
                      :millstrand/min "v1"}]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools {'demo/family declared}}))
        (is (= :spools-edn
               (get-in (runtime/approved rt) [:spools 'demo/root :provenance])))
        (spit (io/file config-dir "spools.local.edn")
              (pr-str {:spools {'demo/family {:local/root "../demo"
                                              :claims "v3"}}}))
        (let [approved (runtime/approved rt)]
          (is (= {:provenance :local-overlay :claims "v3"}
                 (select-keys (get-in approved [:spools 'demo/root])
                              [:provenance :claims])))
          (is (= {:declared declared
                  :effective-coordinate {:kind :local :local/root "../demo"}
                  :roots-map {'demo/root "."}
                  :provenance :local-overlay
                  :claims "v3"}
                 (get-in approved [:families 'demo/family]))))))))

(deftest approved-projects-implicit-single-root-family
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            declared {:local/root "spools/demo"}]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools {'demo/family declared}}))
        (let [approved (runtime/approved rt)]
          (is (= #{'demo/family} (set (keys (:spools approved)))))
          ;; the rootless local family projects the implicit {family "."} sole-root
          (is (= {:declared declared
                  :effective-coordinate {:kind :local :local/root "spools/demo"}
                  :roots-map {'demo/family "."}
                  :provenance :spools-edn
                  :claims nil}
                 (get-in approved [:families 'demo/family]))))))))

(deftest declared-returns-family-projection-with-valid-requirements
  (with-started-runtime
    nil
    {:release-marker "v3"}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            sha (str/join (repeat 40 "c"))]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools
                       {'demo/target {:git/url "https://example.invalid/target.git"
                                      :git/sha sha
                                      :git/tag "v2"}
                        'demo/client {:git/url "https://example.invalid/client.git"
                                      :git/sha sha
                                      :git/tag "v1"
                                      :requires {'demo/target "v2"}
                                      :millstrand/min "v3"}}}))
        (let [declared (runtime/declared rt)]
          (is (= (:families (runtime/approved rt)) (:families declared)))
          (is (= {:valid? true :pending-validations []}
                 (:requirements declared)))
          (is (s/valid? ::runtime/declared-result declared)))))))

(deftest declared-preserves-families-when-release-floors-fail
  (with-started-runtime
    nil
    {:release-marker "v2"}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            sha (str/join (repeat 40 "d"))
            target {:git/url "https://example.invalid/target.git"
                    :git/sha sha
                    :git/tag "v2"}]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools
                       {'demo/target target
                        'demo/client {:git/url "https://example.invalid/client.git"
                                      :git/sha sha
                                      :git/tag "v1"
                                      :requires {'demo/target "v4"}
                                      :millstrand/min "v3"}}}))
        (let [approved-ex (is (thrown? clojure.lang.ExceptionInfo
                                       (runtime/approved rt)))
              declared (runtime/declared rt)]
          (is (= target (get-in declared [:families 'demo/target :declared])))
          (is (= (select-keys (ex-data approved-ex) [:findings :suggestions])
                 (select-keys (:requirements declared) [:findings :suggestions])))
          (is (false? (get-in declared [:requirements :valid?]))))))))

(deftest declared-without-running-marker-leaves-millstrand-floor-pending
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))
            sha (str/join (repeat 40 "e"))]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools
                       {'demo/family {:git/url "https://example.invalid/family.git"
                                      :git/sha sha
                                      :git/tag "v1"
                                      :millstrand/min "v2"}}}))
        (is (= {:valid? true
                :pending-validations [{:check :millstrand/min
                                       :spool 'demo/family
                                       :millstrand/min "v2"
                                       :status :pending
                                       :reason :running-marker-unavailable}]}
               (:requirements (runtime/declared rt nil))))))))

(deftest declared-still-fails-loudly-on-stage-one-shape-errors
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [config-dir (io/file (:config-dir world))]
        (.mkdirs config-dir)
        (spit (io/file config-dir "spools.edn")
              (pr-str {:spools
                       {'demo/malformed {:git/url "https://example.invalid/malformed.git"
                                         :git/sha "short"
                                         :requires {'demo/missing "v2"}}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (runtime/declared rt nil)))]
          (is (re-find #":git/sha" (ex-message ex)))
          (is (not (contains? (ex-data ex) :findings))))))))

(deftest upsert-spool-entry-validates-before-write-and-preserves-header-comments
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            overlay-file (io/file (:config-dir world) "spools.local.edn")
            overlay ";; hand edited\n{:spools {}}\n"
            header ";; This header explains hand-owned policy.\n;; Keep it byte-for-byte.\n"
            suffix "\n ;; top-level comment survives too\n :mvn-overrides {}}\n"
            original (str header "{:spools {}" suffix)
            entry {:local/root "spools/demo"}]
        (.mkdirs (.getParentFile file))
        (spit file original)
        (spit overlay-file overlay)
        (is (= :inserted (:status (runtime/upsert-spool-entry! rt 'demo/family entry))))
        (let [written (slurp file)]
          (is (str/starts-with? written header))
          (is (str/ends-with? written suffix))
          (is (= entry (get-in (edn/read-string written) [:spools 'demo/family]))))
        (is (= overlay (slurp overlay-file)))
        (is (= :updated
               (:status (runtime/upsert-spool-entry!
                         rt 'demo/family {:local/root "spools/demo-2"}))))
        (let [before (slurp file)
              error (try
                      (runtime/upsert-spool-entry!
                       rt 'demo/bad {:git/url "https://example.invalid/bad.git"
                                     :git/sha "short"})
                      nil
                      (catch clojure.lang.ExceptionInfo exception exception))]
          (is (= "Git spool entry requires 40 lowercase hex characters :git/sha"
                 (ex-message error)))
          (is (= 'demo/bad (:family (ex-data error))))
          (is (= before (slurp file))))))))

(deftest spool-config-writes-refuse-trailing-edn-without-changing-the-file
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            config "{:spools {demo/family {:local/root \"spools/demo\"}}}\n{:ignored true}\n"]
        (.mkdirs (.getParentFile file))
        (doseq [write! [#(runtime/upsert-spool-entry!
                          rt 'demo/family {:local/root "spools/replacement"})
                        #(runtime/remove-spool-entry! rt 'demo/family)]]
          (spit file config)
          (let [error (try
                        (write!)
                        nil
                        (catch clojure.lang.ExceptionInfo exception exception))]
            (is (re-find #"trailing content" (ex-message error)))
            (is (= (.getPath file) (:file (ex-data error))))
            (is (= "{:ignored true}" (:trailing-content (ex-data error))))
            (is (= config (slurp file)))))))))

(deftest remove-spool-entry-refuses-requirers-and-removes-unreferenced-family
  (with-started-runtime
    nil
    {}
    (fn [rt world]
      (let [file (io/file (:config-dir world) "spools.edn")
            sha-a (str/join (repeat 40 "a"))
            sha-b (str/join (repeat 40 "b"))
            target {:git/url "https://example.invalid/target.git"
                    :git/sha sha-a
                    :git/tag "v1"
                    :roots {'demo/root "."}}
            requirer {:git/url "https://example.invalid/requirer.git"
                      :git/sha sha-b
                      :git/tag "v1"
                      :requires {'demo/root "v1"}}]
        (.mkdirs (.getParentFile file))
        (spit file (pr-str {:spools {'demo/target target 'demo/requirer requirer}}))
        (let [error (try
                      (runtime/remove-spool-entry! rt 'demo/target)
                      nil
                      (catch clojure.lang.ExceptionInfo exception exception))]
          (is (= :spool-family-required (:reason (ex-data error))))
          (is (= [{:family 'demo/requirer :roots #{'demo/root}}]
                 (:requirers (ex-data error))))
          (is (= target (get-in (edn/read-string (slurp file))
                                [:spools 'demo/target]))))
        (is (= :removed (:status (runtime/remove-spool-entry! rt 'demo/requirer))))
        (is (nil? (get-in (edn/read-string (slurp file))
                          [:spools 'demo/requirer])))))))
