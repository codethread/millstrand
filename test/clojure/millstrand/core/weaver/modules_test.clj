(ns millstrand.core.weaver.modules-test
  "Tests for module refresh, source loading, and owner lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.dispatch :as dispatch]
            [millstrand.core.weaver.module-refresh :as module-refresh]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.spool-sync :as spool-sync]
            [millstrand.core.db-test :as db-test]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as t]))

(def delete-tree! test-support/delete-tree!)

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(def delivered-events (atom []))
(def module-deliveries (atom []))
(def handler-started (atom (promise)))
(def handler-release (atom (promise)))
(def module-contributions (atom {}))
(def bad-lifecycle-callable
  "Malformed lifecycle value used to prove resolution requires a function."
  :not-a-function)

(s/def ::module-item map?)

(defn module-contribute
  "Return the test contribution selected by the stable module key."
  [{key :module/key}]
  (let [contribution (get @module-contributions key)]
    (case contribution
      ::throw (throw (ex-info "contribution boom" {:module/key key}))
      ::malformed [:not-a-contribution]
      contribution)))

(defn capture-event [event]
  (swap! delivered-events conj event))

(defn slow-capture-event [event]
  (deliver @handler-started true)
  @@handler-release
  (swap! delivered-events conj event))

(defn failing-event [event]
  (throw (ex-info "handler failed" {:event event})))

(defn test-event [type id]
  {:event/type type
   :event/id id
   :event/at "2026-06-27T00:00:00Z"
   :event/source :test})

(use-fixtures :each
  (fn [f]
    (reset! delivered-events [])
    (reset! module-deliveries [])
    (reset! handler-started (promise))
    (reset! handler-release (promise))
    (reset! module-contributions {})
    (f)))
(defn test-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(defn context-echo-op
  "Return the handler context so tests can inspect threaded envelope fields."
  [ctx]
  ctx)

(defn envelope-echo-op
  "Return only the JSON-safe envelope fields (the full context carries the
  runtime, which cannot cross the JSON socket)."
  [ctx]
  {:cwd (:op/cwd ctx)
   :worktree-root (:op/worktree-root ctx)
   :timeout (:op/timeout ctx)
   :payloads (:op/payloads ctx)})

;; Stream/op transport fixtures. Namespace-level for the same by-symbol
;; registration reason as the hooks/events above; the :each fixture resets
;; `stream-gate`, `deadline-gate`, and `op-side-effects`.
(defn- write-runtime-module!
  ([workspace relative-path ns-sym body]
   (write-runtime-module! workspace relative-path ns-sym [] body))
  ([workspace relative-path ns-sym required-namespaces body]
   (let [file (io/file workspace relative-path)]
     (.mkdirs (.getParentFile file))
     (spit file
           (str "(ns " ns-sym
                "\n  (:require [millstrand.core.weaver.runtime :as runtime]"
                (str/join "" (map #(str "\n            [" % "]") required-namespaces))
                "))\n" body "\n"))
     file)))

(defn- contribution-forms
  "Return source forms that collect the test contribution for `module-key`."
  [module-key]
  (format
   (str "(let [contribution (get @millstrand.core.weaver.modules-test/module-contributions %s)]\n"
        "  (case contribution\n"
        "    :millstrand.core.weaver.modules-test/throw (throw (ex-info \"contribution boom\" {}))\n"
        "    :millstrand.core.weaver.modules-test/malformed (runtime/collect-module-entry! :queries nil nil)\n"
        "    (doseq [[kind entries] contribution [entry-key value] entries]\n"
        "      (runtime/collect-module-entry! kind entry-key value))))")
   (pr-str module-key)))

(defn- module-source!
  "Write a workspace `:file` module source carrying `body`.

  Without a body the module collects no authoring forms."
  ([workspace relative-path ns-sym]
   (module-source! workspace relative-path ns-sym "nil"))
  ([workspace relative-path ns-sym body]
   (write-runtime-module! workspace relative-path ns-sym body)))

(defn- write-local-spool-module!
  ([workspace root-lib ns-sym body]
   (write-local-spool-module! workspace root-lib ns-sym [] body))
  ([workspace root-lib ns-sym required-namespaces body]
   (let [relative-root "spools/module-root"
         root (io/file workspace relative-root)
         relative-source (-> (str ns-sym)
                             (str/replace "." "/")
                             (str/replace "-" "_"))
         source (io/file root "src" (str relative-source ".clj"))]
     (io/make-parents source)
     (spit (io/file workspace "spools.edn")
           (pr-str {:spools {root-lib {:local/root relative-root}}}))
     (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
     (spit source
           (str "(ns " ns-sym
                "\n  (:require [millstrand.core.weaver.runtime :as runtime]"
                "\n            [millstrand.api.millstrand.alpha :as millstrand]"
                (str/join "" (map #(str "\n            [" % "]") required-namespaces))
                "))\n" body "\n"))
     source)))

(defn- throwable-messages [t]
  (loop [messages []
         t t]
    (if t
      (recur (conj messages (ex-message t)) (ex-cause t))
      messages)))

(deftest startup-collects-layered-module-graph-and-full-refresh-removes-owners
  (let [world (temp-world)
        workspace (:config-dir world)
        suffix (str/replace (str (random-uuid)) "-" "")]
    (try
      (write-runtime-module!
       workspace "modules/base-shared.clj" (symbol (str "test.module.base-shared-" suffix))
       (str "(runtime/collect-module-entry! :queries \"base-shared\" [:= [:attr :owner] \"shared\"])\n"
            "nil"))
      (write-runtime-module!
       workspace "modules/base-local.clj" (symbol (str "test.module.base-local-" suffix))
       (str "(runtime/collect-module-entry! :queries \"base-local\" [:= [:attr :owner] \"local\"])\n"
            "nil"))
      (write-runtime-module!
       workspace "modules/dependent.clj" (symbol (str "test.module.dependent-" suffix))
       (str "(runtime/collect-module-entry! :queries \"dependent\" [:= [:attr :owner] \"dependent\"])\n"
            "nil"))
      (spit (io/file workspace "init.clj")
            (str "(millstrand.core.weaver.runtime/declare-module! "
                 "millstrand.core.weaver.runtime/*runtime* :base "
                 "{:file \"modules/base-shared.clj\"})\n"
                 "(millstrand.core.weaver.runtime/declare-module! "
                 "millstrand.core.weaver.runtime/*runtime* :dependent "
                 "{:file \"modules/dependent.clj\" :after [:base]})\n"))
      (spit (io/file workspace "init.local.clj")
            (str "(millstrand.core.weaver.runtime/declare-module! "
                 "millstrand.core.weaver.runtime/*runtime* :base "
                 "{:file \"modules/base-local.clj\"})\n"))
      (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
        (try
          (is (= :applied (get-in (weaver-runtime/module-status rt)
                                  [:last-refresh :status])))
          (is (= "modules/base-local.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:modules :base :file])))
          (is (= #{"base-local" "dependent"}
                 (set (keys (graph/queries rt)))))
          (is (= "init.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:declaration/shadows :base :shadowed 0 :source :name])))
          (is (= "init.local.clj"
                 (get-in (weaver-runtime/module-status rt)
                         [:declaration/shadows :base :effective :source :name])))
          (let [unchanged (weaver-runtime/refresh-modules! rt)]
            (is (= :unchanged (:status unchanged)))
            (is (every? #(= :unchanged (:status %))
                        (vals (:modules unchanged)))))
          (write-runtime-module!
           workspace "modules/new.clj" (symbol (str "test.module.new-" suffix))
           "(runtime/collect-module-entry! :queries \"new\" [:= [:attr :owner] \"new\"])")
          (spit (io/file workspace "init.clj")
                (str "(millstrand.core.weaver.runtime/declare-module! "
                     "millstrand.core.weaver.runtime/*runtime* :new "
                     "{:file \"modules/new.clj\"})\n"))
          (.delete (io/file workspace "init.local.clj"))
          (let [result (weaver-runtime/refresh-modules! rt)]
            (is (= :applied (:status result)))
            (is (= :removed (get-in result [:modules :base :status])))
            (is (= :removed (get-in result [:modules :dependent :status])))
            (is (= #{"new"} (set (keys (graph/queries rt))))
                "full-graph omission deletes only the omitted owners"))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! (io/file workspace ".."))))))

(deftest image-module-declaration-grammar-refusals
  (with-runtime
    (fn [rt _db-file]
      (letfn [(refusal-data [opts]
                (try
                  (runtime/module! rt :image-grammar opts)
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (let [data (refusal-data {:ns 'millstrand.core.weaver.modules-test
                                  :load :classpath})]
          (is (= :image-grammar (:module/key data)))
          (is (= :classpath (:load data)))
          (is (= #{:image} (:allowed data))
              ":load refusal names the allowed value set"))
        (let [data (refusal-data {:file "modules/image.clj"
                                  :load :image})]
          (is (= :image-grammar (:module/key data)))
          (is (= "modules/image.clj" (:file data)))
          (is (= [:ns] (:allowed data))
              ":load :image refusal with :file names the accepted target kind"))
        (is (nil? (refusal-data {:ns 'millstrand.core.weaver.modules-test :load :image}))
            "an image declaration is accepted before retained forms are checked")))))

(deftest image-module-activates-from-the-live-image-without-source-load
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.image-live-" suffix))
            declaration (str "(millstrand.core.weaver.runtime/declare-module! "
                             "millstrand.core.weaver.runtime/*runtime* :image-live "
                             "{:ns '" module-ns " :load :image "
                             ":spools ['" root-lib "]})\n")]
        (write-local-spool-module!
         workspace root-lib module-ns
         "(millstrand.api.runtime.alpha/collect-entry! :queries \"image-live\" [:= [:attr :owner] \"image\"])")
        (is (= :applied
               (:status (runtime/module! rt :image-live
                                         {:ns module-ns :spools [root-lib]}))))
        (spit (io/file workspace "init.clj") declaration)
        (let [ledger-before (spool-sync/namespace-load-ledger rt)
              result (runtime/refresh! rt)
              outcome (get-in result [:modules :image-live])]
          (is (= :unchanged (:status result)))
          (is (= :unchanged (:status outcome)))
          (is (= :image (:source/status outcome)))
          (is (not (contains? outcome :source/stamp)))
          (is (= ledger-before (spool-sync/namespace-load-ledger rt))
              "image activation performs no source load")
          (is (nil? (get-in @(:module-state rt) [:contribution-sources :image-live]))
              "no source stamp is recorded for an image module")
          (is (= [:= [:attr :owner] "image"] (get (graph/queries rt) "image-live"))))
        (let [status (weaver-runtime/module-status rt)]
          (is (= :image (get-in status [:modules :image-live :load]))
              "the declaration stays introspectable data")
          (is (= :image (get-in status [:module/outcomes :image-live :source/status]))))
        (let [plan (runtime/plan rt)]
          (is (true? (:dry-run? plan)))
          (is (= :image (get-in plan [:modules :image-live :source/status]))
              "plan states the module as image-owned"))
        (let [ledger (spool-sync/namespace-load-ledger rt)
              result (runtime/refresh! rt {:only [:image-live]})]
          (is (= :unchanged (:status result)))
          (is (= :image (get-in result [:modules :image-live :source/status])))
          (is (= ledger (spool-sync/namespace-load-ledger rt))
              "targeted refresh over an image module stays loadless"))))))

(deftest image-module-replays-retained-authoring-declarations
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            forms-ns (symbol (str "test.module.image-forms-" suffix))
            empty-ns (symbol (str "test.module.image-empty-" suffix))
            kind-ns (symbol (str "test.module.image-kind-" suffix))
            bad-kind-ns (symbol (str "test.module.bad-kind-" suffix))
            bad-glossary-ns (symbol (str "test.module.bad-glossary-" suffix))
            state-key (keyword (str "test.image-kind-" suffix))
            bad-state-key (keyword (str "test.bad-kind-" suffix))
            bad-glossary-state-key (keyword (str "test.bad-glossary-" suffix))]
        (write-local-spool-module!
         workspace root-lib forms-ns
         (str "(millstrand.api.runtime.alpha/collect-entry! "
              ":queries \"image-forms\" [:= [:attr :owner] \"forms\"] "
              "{:override? false})"))
        (is (= :applied
               (:status (runtime/module! rt :image-forms
                                         {:ns forms-ns :spools [root-lib]}))))
        (let [result (runtime/module! rt :image-forms
                                      {:ns forms-ns :load :image})
              outcome (get-in result [:modules :image-forms])]
          (is (= :unchanged (:status outcome)))
          (is (= :image (:source/status outcome)))
          (is (= [:= [:attr :owner] "forms"]
                 (get (graph/queries rt) "image-forms"))))
        (let [result (runtime/module! rt :other-image-forms
                                      {:ns forms-ns :load :image})
              outcome (get-in result [:modules :other-image-forms])]
          (is (= :failed (:status outcome)))
          (is (= :foreign-declaration-record
                 (get-in outcome [:error :data :reason])))
          (is (= :image-forms
                 (get-in outcome [:error :data :record/module-key]))
              "a namespace record cannot be replayed under another owner"))

        (write-local-spool-module! workspace root-lib empty-ns "")
        (is (= :applied
               (:status (runtime/module! rt :image-empty
                                         {:ns empty-ns :spools [root-lib]}))))
        (let [result (runtime/module! rt :image-empty
                                      {:ns empty-ns :load :image})]
          (is (= :unchanged (get-in result [:modules :image-empty :status]))
              "an explicitly retained empty declaration set is replayable"))

        (write-local-spool-module!
         workspace root-lib kind-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(millstrand.api.runtime.alpha/collect-kind! "
              state-key " {:id ::widgets :entry-spec ::widget "
              ":binding-moment :test/use})\n"
              "(millstrand.api.runtime.alpha/collect-entry! "
              "::widgets :one {:value 1})"))
        (spit (io/file workspace "init.clj")
              (str "(millstrand.core.weaver.runtime/declare-module! "
                   "millstrand.core.weaver.runtime/*runtime* :image-kind "
                   "{:ns '" kind-ns " :spools ['" root-lib "]})\n"))
        (let [plan (runtime/plan rt)]
          (is (= :applied (:status plan)))
          (is (nil? (get @(:spool-state rt) state-key))
              "plan validates a new kind against an isolated registry"))
        (let [result (runtime/refresh! rt)
              handle (get @(:spool-state rt) state-key)]
          (is (= :applied (:status result)))
          (is (= {:one {:value 1}}
                 (registry/effective handle (keyword (str kind-ns) "widgets")))
              "open kinds are realized before their entries stage"))
        (is (= :unchanged
               (:status (runtime/module! rt :image-kind
                                         {:ns kind-ns :spools [root-lib]})))
            "an unchanged source evaluation preserves retained open kinds")
        (let [result (runtime/module! rt :image-kind
                                      {:ns kind-ns :load :image})]
          (is (= :unchanged (:status result)))
          (is (= :image
                 (get-in result [:modules :image-kind :source/status]))))

        (write-local-spool-module!
         workspace root-lib bad-kind-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(millstrand.api.runtime.alpha/collect-kind! "
              bad-state-key " {:id ::widgets :entry-spec ::widget "
              ":binding-moment :test/use})\n"
              "(millstrand.api.runtime.alpha/collect-entry! ::widgets :bad 1)"))
        (let [result (runtime/module! rt :bad-kind
                                      {:ns bad-kind-ns :spools [root-lib]})]
          (is (= :refused (:status result)))
          (is (nil? (get @(:spool-state rt) bad-state-key))
              "failed candidate validation publishes no kind handle"))

        (write-local-spool-module!
         workspace root-lib bad-glossary-ns
         (str "(clojure.spec.alpha/def ::widget map?)\n"
              "(millstrand.api.runtime.alpha/collect-kind! "
              bad-glossary-state-key
              " {:id ::widgets :entry-spec ::widget :binding-moment :test/use})\n"
              "(millstrand.api.runtime.alpha/collect-entry! :ops \"bad-glossary\" '"
              (pr-str {:name "bad-glossary"
                       :fn 'millstrand.core.weaver.modules-test/test-op
                       :stream? false
                       :provenance 'millstrand.core.weaver.modules-test
                       :arg-spec {:op "bad-glossary"
                                  :hook-class :read
                                  :deadline-class :standard
                                  :annotations
                                  {:failure-modes [(str "publication/missing-" suffix)]}}})
              ")"))
        (let [result (runtime/module! rt :bad-glossary
                                      {:ns bad-glossary-ns :spools [root-lib]})]
          (is (= :refused (:status result)) (pr-str result))
          (is (nil? (get @(:spool-state rt) bad-glossary-state-key))
              "post-publication refusal removes a newly staged registry handle"))
        (let [result (runtime/module! rt :bad-glossary
                                      {:ns bad-glossary-ns :load :image})]
          (is (= :missing-declaration-record
                 (get-in result
                         [:modules :bad-glossary :error :data :reason]))
              "a refused source refresh leaves no replayable declaration record"))))))
(deftest source-reload-replaces-the-retained-declaration-set
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            module-ns (symbol (str "test.module.omission-" suffix))
            source "modules/omission.clj"
            file (io/file workspace source)]
        (io/make-parents file)
        (spit file
              (str "(ns " module-ns ")\n"
                   "(millstrand.api.runtime.alpha/collect-entry! "
                   ":queries \"omitted-q\" [:= [:attr :v] 1])\n"))
        (is (= :applied
               (:status (runtime/module! rt :omission {:file source}))))
        (is (contains? (graph/queries rt) "omitted-q"))

        (spit file (str "(ns " module-ns ")\n"))
        (is (= :applied
               (:status (runtime/module! rt :omission {:file source}))))
        (is (not (contains? (graph/queries rt) "omitted-q"))
            "deleting the form retracts its prior retained entry")))))

(deftest image-redeclaration-drops-the-recorded-source-stamp
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.stamp-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-sym
         "(millstrand.api.runtime.alpha/collect-entry! :queries \"stamp-q\" [:= [:attr :v] 1])")
        (is (= :applied (:status (runtime/module! rt :stamp-mod
                                                  {:ns ns-sym :spools [root-lib]}))))
        (is (some? (get-in @(:module-state rt) [:contribution-sources :stamp-mod]))
            "a source-loaded :ns module records its stamp")
        (let [result (runtime/module! rt :stamp-mod
                                      {:ns ns-sym
                                       :load :image})]
          (is (= :image (get-in result [:modules :stamp-mod :source/status])))
          (is (nil? (get-in @(:module-state rt) [:contribution-sources :stamp-mod]))
              "redeclaring as :load :image drops the recorded source stamp")
          (is (= [:= [:attr :v] 1] (get (graph/queries rt) "stamp-q"))))))))

(defn- names-image-remedy?
  "True when an image contribution failure states the source-load remedy."
  [message]
  (boolean (and message (re-find #"load or require" message))))

(deftest image-module-unloaded-namespace-fails-as-module-outcome
  (with-runtime
    (fn [rt _db-file]
      (let [suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.image-unloaded-" suffix))
            result (runtime/module! rt :image-unloaded
                                    {:ns ns-sym
                                     :load :image})
            outcome (get-in result [:modules :image-unloaded])]
        (is (= :partial (:status result)))
        (is (= :failed (:status outcome)))
        (is (= :image-unloaded (get-in outcome [:error :data :module/key])))
        (is (= ns-sym (get-in outcome [:error :data :ns]))
            "the failure names the unloaded namespace")
        (is (= :image (get-in outcome [:error :data :load])))
        (is (names-image-remedy? (get-in outcome [:error :message]))
            "the unloaded branch states the source-load remedy")))))

(deftest source-and-image-modules-reject-public-spool-vars
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.removed-spool-" suffix))]
        (write-local-spool-module!
         workspace root-lib module-ns
         "(def spool {:contribute 'removed})")
        (let [source-result (runtime/module!
                             rt :removed-source
                             {:ns module-ns :spools [root-lib]})
              source-outcome (get-in source-result [:modules :removed-source])]
          (is (= :failed (:status source-outcome)))
          (is (= :removed-def-spool
                 (get-in source-outcome [:error :data :reason]))))
        (spool-sync/sync-approved-spools rt)
        (weaver-runtime/with-runtime-and-spool-classloader rt #(require module-ns))
        (let [image-result (runtime/module!
                            rt :removed-image
                            {:ns module-ns :load :image :spools [root-lib]})
              image-outcome (get-in image-result [:modules :removed-image])]
          (is (= :failed (:status image-outcome)))
          (is (= :removed-def-spool
                 (get-in image-outcome [:error :data :reason]))))))))

(deftest source-reload-removes-a-stale-legacy-spool-var
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            root-lib 'test/module-root
            module-ns (symbol (str "test.module.migrated-spool-" suffix))
            source (write-local-spool-module!
                    workspace root-lib module-ns
                    "(def spool {:contribute 'removed})")]
        (is (= :failed
               (get-in (runtime/module! rt :migrated-source
                                        {:ns module-ns :spools [root-lib]})
                       [:modules :migrated-source :status])))
        (spit source
              (str "(ns " module-ns ")\n"
                   "(millstrand.api.runtime.alpha/collect-entry!\n"
                   " :queries \"migrated\" [:= [:attr :grammar] \"forms\"])\n"))
        (let [result (runtime/module! rt :migrated-source
                                      {:ns module-ns :spools [root-lib]})]
          (is (= :applied (get-in result [:modules :migrated-source :status])))
          (is (not (contains? (ns-publics module-ns) 'spool))
              "reload removes the legacy Var without userland ns-unmap")
          (is (= [:= [:attr :grammar] "forms"]
                 (graph/resolve-query rt :migrated))))))))

(deftest lifecycle-callables-must-resolve-to-functions
  (let [callable 'millstrand.core.weaver.modules-test/bad-lifecycle-callable
        error (try
                (#'module-refresh/resolve-lifecycle-callables!
                 (fn [f] (f))
                 {:bad-lifecycle
                  {:status :ready
                   :lifecycle {:bad-callable
                               {:kind :seed :apply callable}}}})
                nil
                (catch clojure.lang.ExceptionInfo throwable
                  throwable))]
    (is (instance? clojure.lang.ExceptionInfo error))
    (is (= callable (:effect/callable (ex-data error))))
    (is (= :not-a-function (:resolved/value (ex-data error))))
    (is (= "clojure.lang.Keyword" (:resolved/type (ex-data error))))))

(deftest file-module-rejects-multiple-namespace-owners
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-of (fn [label] (symbol (str "test.module." label "-" suffix)))
            first-ns (ns-of "file-first")
            second-ns (ns-of "file-second")
            aliased-ns (ns-of "file-aliased-keyword")
            source-for
            (fn [filename form-first?]
              (let [source (str "modules/" filename ".clj")
                    file (io/file workspace source)
                    module-form
                    "(millstrand.api.runtime.alpha/collect-entry! :queries \"owned\" [:= [:attr :v] 1])\n"]
                (io/make-parents file)
                (spit file
                      (str "(ns " first-ns ")\n"
                           (when form-first? module-form)
                           "(ns " second-ns ")\n"
                           (when-not form-first? module-form)))
                source))]
        (doseq [[key source] [[:multi-ns-spool-first
                               (source-for "multi-ns-spool-first" true)]
                              [:multi-ns-spool-second
                               (source-for "multi-ns-spool-second" false)]]]
          (let [result (runtime/module! rt key {:file source})
                outcome (get-in result [:modules key])]
            (is (= :failed (:status outcome)))
            (is (= :multiple-module-namespaces
                   (get-in outcome [:error :data :reason])))
            (is (= key (get-in outcome [:error :data :module/key])))
            (is (= [first-ns second-ns]
                   (get-in outcome [:error :data :namespaces])))))
        (let [source "modules/aliased-keyword.clj"
              file (io/file workspace source)]
          (io/make-parents file)
          (spit file
                (str "(ns " aliased-ns
                     " (:require [clojure.string :as text]))\n"
                     "(def value ::text/example)\n"))
          (let [result (runtime/module! rt :aliased-keyword-file {:file source})]
            (is (= :applied (:status result)))
            (is (= :applied
                   (get-in result [:modules :aliased-keyword-file :status])))))
        (let [source "modules/repeated-owner.clj"
              file (io/file workspace source)]
          (io/make-parents file)
          (spit file
                (str "(ns " aliased-ns ")\n"
                     "(ns " aliased-ns ")\n"
                     "(millstrand.api.runtime.alpha/collect-entry! "
                     ":queries \"repeated-owner\" [:= [:attr :v] 1])\n"))
          (let [result (runtime/module! rt :repeated-owner-file {:file source})]
            (is (= :applied (:status result)))
            (is (= [:= [:attr :v] 1]
                   (get (graph/queries rt) "repeated-owner")))))))))

(deftest targeted-refresh-retains-prior-contribution-and-isolates-collisions
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source-a "modules/owner-a.clj"
            source-b "modules/owner-b.clj"]
        (module-source! workspace source-a
                        (symbol (str "test.module.owner-a-" suffix))
                        (contribution-forms :owner-a))
        (module-source! workspace source-b
                        (symbol (str "test.module.owner-b-" suffix))
                        (contribution-forms :owner-b))
        (graph/register-query! rt 'unrelated [:= [:attr :owner] "unrelated"])
        (reset! module-contributions
                {:owner-a {:queries {"owned" [:= [:attr :version] 1]}}})
        (let [first-result
              (weaver-runtime/declare-module! rt :owner-a {:file source-a})]
          (is (= :applied (:status first-result)))
          (is (= [:= [:attr :version] 1] (get (graph/queries rt) "owned"))))
        (swap! module-contributions assoc :owner-a ::malformed)
        (let [failed (weaver-runtime/refresh-modules! rt {:only [:owner-a]})]
          (is (= :partial (:status failed)))
          (is (= :failed (get-in failed [:modules :owner-a :status])))
          (is (= :retained
                 (get-in failed [:modules :owner-a :contribution/status])))
          (is (= [:= [:attr :version] 1] (get (graph/queries rt) "owned")))
          (is (contains? (graph/queries rt) "unrelated")))
        (swap! module-contributions assoc
               :owner-a {:queries {"owned" [:= [:attr :version] 2]}})
        (let [updated (weaver-runtime/refresh-modules! rt {:only #{:owner-a}})]
          (is (= :applied (:status updated)))
          (is (= [:= [:attr :version] 2] (get (graph/queries rt) "owned"))
              "a valid targeted refresh replaces the owner's declarations"))
        (swap! module-contributions assoc
               :owner-b {:queries {"owned" [:= [:attr :version] :collision]}})
        (let [collision
              (weaver-runtime/declare-module! rt :owner-b {:file source-b})]
          (is (= :partial (:status collision)))
          (is (= :failed (get-in collision [:modules :owner-b :status])))
          (is (= :same-layer-duplicate
                 (get-in collision
                         [:modules :owner-b :error :data :error])))
          (is (= [:= [:attr :version] 2] (get (graph/queries rt) "owned")))
          (is (contains? (graph/queries rt) "unrelated")))))))

(deftest image-replay-retains-only-successfully-published-source-set
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            collision-source "modules/image-retained-collision.clj"
            first-failed-source "modules/image-retained-first-failed.clj"
            valid-source "modules/image-retained-valid.clj"
            collision-ns (symbol (str "test.module.image-retained-collision-" suffix))
            first-failed-ns (symbol (str "test.module.image-retained-first-failed-" suffix))
            valid-ns (symbol (str "test.module.image-retained-valid-" suffix))
            collision-query [:= [:attr :version] "collision"]
            valid-query [:= [:attr :version] 1]
            changed-query [:= [:attr :version] 2]]
        (doseq [[source ns-sym module-key]
                [[collision-source collision-ns :collision-owner]
                 [first-failed-source first-failed-ns :first-failed]
                 [valid-source valid-ns :valid-owner]]]
          (module-source! workspace source ns-sym (contribution-forms module-key)))
        (reset! module-contributions
                {:collision-owner {:queries {"collision" collision-query}}
                 :first-failed {:queries {"collision" collision-query}}
                 :valid-owner {:queries {"owned" valid-query}}})
        (is (= :applied
               (:status (weaver-runtime/declare-module!
                         rt :collision-owner {:file collision-source}))))
        (let [failed (weaver-runtime/declare-module!
                      rt :first-failed {:file first-failed-source})
              outcome (get-in failed [:modules :first-failed])]
          (is (= :partial (:status failed)))
          (is (= :failed (:status outcome)))
          (is (= :same-layer-duplicate
                 (get-in outcome [:error :data :error]))))
        (let [image (runtime/module! rt :first-failed
                                     {:ns first-failed-ns :load :image})
              outcome (get-in image [:modules :first-failed])]
          (is (= :failed (:status outcome)))
          (is (= :missing-declaration-record
                 (get-in outcome [:error :data :reason]))
              "a first failed publication has no image replay record"))
        (is (= :applied
               (:status (weaver-runtime/declare-module!
                         rt :valid-owner {:file valid-source}))))
        (is (= valid-query (get (graph/queries rt) "owned")))
        (swap! module-contributions assoc
               :valid-owner {:queries {"collision" changed-query}})
        (let [failed (weaver-runtime/refresh-modules! rt {:only [:valid-owner]})
              outcome (get-in failed [:modules :valid-owner])]
          (is (= :partial (:status failed)))
          (is (= :failed (:status outcome)))
          (is (= :retained (:contribution/status outcome)))
          (is (= valid-query (get (graph/queries rt) "owned"))))
        (let [image (runtime/module! rt :valid-owner
                                     {:ns valid-ns :load :image})
              outcome (get-in image [:modules :valid-owner])]
          (is (= :image (:source/status outcome)))
          (is (= :unchanged (:status outcome)))
          (is (= valid-query (get (graph/queries rt) "owned"))
              "image replay retains the last successfully published set"))))))

(deftest plan-dry-run-reports-intentions-without-publishing-or-recording
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/planned.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.planned-" suffix))
                        (contribution-forms :planned))
        (reset! module-contributions
                {:planned {:queries {"planned" [:= [:attr :v] 1]}}})
        (weaver-runtime/declare-module! rt :planned {:file source})
        (let [applied-refresh (:last-refresh (weaver-runtime/module-status rt))]
          (swap! module-contributions assoc
                 :planned {:queries {"planned" [:= [:attr :v] 2]}})
          (let [planned (weaver-runtime/refresh-modules! rt {:only [:planned]
                                                             :dry-run? true})]
            (is (true? (:dry-run? planned)))
            (is (string? (:caveat planned)))
            (is (= :applied (:status planned))
                "the pending change is reported as an intended publication")
            (is (= [:queries] (:publication/kinds planned)))
            (is (= [:= [:attr :v] 1] (get (graph/queries rt) "planned"))
                "plan publishes nothing")
            (is (= applied-refresh (:last-refresh (weaver-runtime/module-status rt)))
                "plan records no coordinator state")))))))

(deftest contribution-publication-is-open-over-runtime-owned-registry-kinds
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/domain-kind.clj"
            reg (runtime/spool-state
                 rt ::module-registry {:version 1}
                 #(doto (registry/registry)
                    (registry/declare-kind!
                     {:id :test/items
                      :entry-spec ::module-item
                      :binding-moment :test/use})))]
        (module-source! workspace source
                        (symbol (str "test.module.domain-kind-" suffix))
                        (contribution-forms :domain))
        (reset! module-contributions
                {:domain {:test/items {:one {:version 1}}}})
        (is (= :applied
               (:status
                (weaver-runtime/declare-module! rt :domain {:file source}))))
        (is (= {:one {:version 1}}
               (registry/effective reg :test/items)))))))

(deftest f1-multi-kind-domain-handle-publishes-one-atomic-snapshot
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/multi-kind.clj"
            reg (runtime/spool-state
                 rt ::multi-kind-registry {:version 1}
                 #(doto (registry/registry)
                    (registry/declare-kind!
                     {:id :test/kind-a
                      :entry-spec ::module-item
                      :binding-moment :test/use})
                    (registry/declare-kind!
                     {:id :test/kind-b
                      :entry-spec ::module-item
                      :binding-moment :test/use})))]
        (module-source! workspace source
                        (symbol (str "test.module.multi-kind-" suffix))
                        (contribution-forms :multi-kind))
        (reset! module-contributions
                {:multi-kind {:test/kind-a {:a {:version 1}}
                              :test/kind-b {:b {:version 2}}}})
        (is (= :applied
               (:status (runtime/module! rt :multi-kind {:file source}))))
        (is (= {:a {:version 1}} (registry/effective reg :test/kind-a)))
        (is (= {:b {:version 2}} (registry/effective reg :test/kind-b)))))))

(deftest f2-ns-default-collector-reloads-edits-and-retracts-deleted-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.ns-collector-" suffix))
            root-lib 'test/module-root
            source (write-local-spool-module!
                    workspace root-lib ns-sym
                    (str "(runtime/collect-module-entry! :queries \"kept\" "
                         "[:= [:attr :version] 1])\n"
                         "(runtime/collect-module-entry! :queries \"deleted\" "
                         "[:= [:attr :deleted] true])"))]
        (is (= :applied
               (:status (runtime/module! rt :ns-module
                                         {:ns ns-sym :spools [root-lib]}))))
        (is (= #{"kept" "deleted"}
               (set (keys (graph/queries rt)))))
        (spit source
              (str "(ns " ns-sym
                   "\n  (:require [millstrand.core.weaver.runtime :as runtime]))\n"
                   "(runtime/collect-module-entry! :queries \"kept\" "
                   "[:= [:attr :version] 2])\n"))
        (let [result (runtime/refresh! rt {:only [:ns-module]})]
          (is (= :applied (:status result)))
          (is (= [:= [:attr :version] 2]
                 (get (graph/queries rt) "kept")))
          (is (not (contains? (graph/queries rt) "deleted"))
              "a deleted authoring form is omitted from the replacement"))))))

(deftest f10-ns-module-refuses-foreign-namespace-authoring-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.foreign-a-" suffix))
            ns-b (symbol (str "test.module.foreign-b-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-a
         "(runtime/collect-module-entry! :queries \"foreign-a\" [:= [:attr :v] 1])")
        (write-local-spool-module!
         workspace root-lib ns-b [ns-a]
         "(runtime/collect-module-entry! :queries \"foreign-b\" [:= [:attr :v] 1])")
        (let [result (runtime/module! rt :foreign-ns
                                      {:ns ns-b :spools [root-lib]})
              error (get-in result [:modules :foreign-ns :error :data])]
          (is (= :partial (:status result)))
          (is (= :failed (get-in result [:modules :foreign-ns :status])))
          (is (= :foreign-contribution-namespace (:reason error)))
          (is (= :foreign-ns (:module/key error)))
          (is (= ns-a (:namespace error)))
          (is (empty? (select-keys (graph/queries rt) ["foreign-a" "foreign-b"]))))))))

(deftest f10-file-module-refuses-foreign-namespace-authoring-forms
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.file-foreign-a-" suffix))
            ns-b (symbol (str "test.module.file-foreign-b-" suffix))
            root-lib 'test/module-root
            source "modules/file-foreign.clj"]
        (write-local-spool-module!
         workspace root-lib ns-a
         "(runtime/collect-module-entry! :queries \"file-foreign-a\" [:= [:attr :v] 1])")
        (write-runtime-module!
         workspace source ns-b [ns-a]
         "(runtime/collect-module-entry! :queries \"file-foreign-b\" [:= [:attr :v] 1])")
        (let [result (runtime/module! rt :foreign-file
                                      {:file source :spools [root-lib]})
              error (get-in result [:modules :foreign-file :error :data])]
          (is (= :partial (:status result)))
          (is (= :foreign-contribution-namespace (:reason error)))
          (is (= :foreign-file (:module/key error)))
          (is (= ns-a (:namespace error)))
          (is (empty? (select-keys (graph/queries rt)
                                   ["file-foreign-a" "file-foreign-b"]))))))))
(deftest f10-per-namespace-modules-refresh-either-source-without-loss
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-a (symbol (str "test.module.scoped-a-" suffix))
            ns-b (symbol (str "test.module.scoped-b-" suffix))
            root-lib 'test/module-root
            source-a (write-local-spool-module!
                      workspace root-lib ns-a
                      "(runtime/collect-module-entry! :queries \"scoped-a\" [:= [:attr :v] 1])")
            source-b (write-local-spool-module!
                      workspace root-lib ns-b [ns-a]
                      "(runtime/collect-module-entry! :queries \"scoped-b\" [:= [:attr :v] 1])")]
        (is (= :applied
               (:status (runtime/module! rt :scoped-a
                                         {:ns ns-a :spools [root-lib]}))))
        (is (= :applied
               (:status (runtime/module! rt :scoped-b
                                         {:ns ns-b :spools [root-lib]
                                          :after [:scoped-a]}))))
        (spit source-b
              (str "(ns " ns-b
                   "\n  (:require [millstrand.core.weaver.runtime :as runtime] [" ns-a "]))\n"
                   "(runtime/collect-module-entry! :queries \"scoped-b\" [:= [:attr :v] 2])\n"))
        (is (= :applied (:status (runtime/refresh! rt {:only [:scoped-b]}))))
        (is (= [:= [:attr :v] 1] (get (graph/queries rt) "scoped-a")))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-b")))
        (spit source-a
              (str "(ns " ns-a
                   "\n  (:require [millstrand.core.weaver.runtime :as runtime]))\n"
                   "(runtime/collect-module-entry! :queries \"scoped-a\" [:= [:attr :v] 2])\n"))
        (is (= :applied (:status (runtime/refresh! rt {:only [:scoped-a]}))))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-a")))
        (is (= [:= [:attr :v] 2] (get (graph/queries rt) "scoped-b")))))))

(deftest f4-unledgered-loaded-spool-namespace-is-reacquired-through-the-ledger
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.unledgered-" suffix))
            root-lib 'test/module-root]
        (write-local-spool-module!
         workspace root-lib ns-sym
         "(runtime/collect-module-entry! :queries \"unledgered\" [:= [:attr :v] 1])")
        (spool-sync/sync-approved-spools rt)
        (weaver-runtime/with-runtime-and-spool-classloader
          rt #(require ns-sym))
        (is (empty? (filter #(= ns-sym (:namespace %))
                            (spool-sync/namespace-load-ledger rt))))
        (let [result (runtime/module! rt :unledgered
                                      {:ns ns-sym :spools [root-lib]})]
          (is (= :applied (:status result)))
          (is (= :applied (get-in result [:modules :unledgered :status])))
          (is (some #(and (= ns-sym (:namespace %))
                          (= :unledgered (:owner %)))
                    (spool-sync/namespace-load-ledger rt)))
          (is (contains? (graph/queries rt) "unledgered")))))))

(deftest f11-file-module-and-repl-requires-become-observed-residuals
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            file-required-ns (symbol (str "test.module.file-required-" suffix))
            repl-required-ns (symbol (str "test.module.repl-required-" suffix))
            module-ns (symbol (str "test.module.file-observer-" suffix))
            root-lib 'test/module-root
            source "modules/file-observer.clj"]
        (write-local-spool-module! workspace root-lib file-required-ns "(def value :file)")
        (write-local-spool-module! workspace root-lib repl-required-ns "(def value :repl)")
        (write-runtime-module!
         workspace source module-ns [file-required-ns]
         "(runtime/collect-module-entry! :queries \"file-observer\" [:= [:attr :v] 1])")
        (is (= :applied
               (:status (runtime/module! rt :file-observer
                                         {:file source :spools [root-lib]}))))
        (is (= :unledgered-loaded-namespace
               (:reason (some #(when (= file-required-ns (:namespace %)) %)
                              (:residuals (spool-sync/loaded-namespace-status rt))))))
        (is (not-any? #(= file-required-ns (:namespace %))
                      (spool-sync/namespace-load-ledger rt)))
        (weaver-runtime/with-runtime-and-spool-classloader
          rt #(require repl-required-ns))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/sync-approved-spools rt)))
              residuals (:residuals (spool-sync/loaded-namespace-status rt))]
          (is (= :non-additive-sync-diff (:reason (ex-data ex))))
          (is (= #{file-required-ns repl-required-ns}
                 (->> residuals
                      (filter #(= :unledgered-loaded-namespace (:reason %)))
                      (map :namespace)
                      set))))))))

(deftest f14-informative-throwable-retains-outer-marker-context
  (let [throwable (try
                    (@#'spool-sync/validate-marker!
                     "v0" {:family 'demo/family :field :git/tag})
                    (catch clojure.lang.ExceptionInfo e e))
        error (@#'module-refresh/exception-data throwable)]
    (is (= {:family 'demo/family :field :git/tag :marker "v0"}
           (select-keys (:data error) [:family :field :marker])))))

(deftest f5-status-is-identical-after-refreshed-source-files-disappear
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.status-offline-" suffix))
            root-lib 'test/module-root
            source (write-local-spool-module!
                    workspace root-lib ns-sym
                    (str "(runtime/collect-module-entry! :queries \"offline\" "
                         "[:= [:attr :version] 1])"))]
        (is (= :applied
               (:status (runtime/module! rt :status-offline
                                         {:ns ns-sym :spools [root-lib]}))))
        (let [before (runtime/status rt)]
          (is (.delete source))
          (is (.delete (io/file workspace "spools.edn")))
          (is (= before (runtime/status rt))))))))

(deftest f6-plan-never-syncs-or-records-refused-results
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            suffix (str/replace (str (random-uuid)) "-" "")
            source "modules/plan-effects.clj"]
        (module-source! workspace source
                        (symbol (str "test.module.plan-effects-" suffix))
                        (contribution-forms :planned))
        (reset! module-contributions
                {:planned {:queries {"planned" [:= [:attr :v] 1]}}})
        (runtime/module! rt :planned {:file source})
        (let [last-refresh (:last-refresh (runtime/status rt))
              sync-calls (atom 0)
              sync-approved-spools spool-sync/sync-approved-spools]
          (swap! module-contributions assoc
                 :planned {:queries {"planned" [:= [:attr :v] 2]}})
          (with-redefs [spool-sync/sync-approved-spools
                        (fn [candidate-rt & args]
                          (if (identical? rt candidate-rt)
                            (do
                              (swap! sync-calls inc)
                              (throw (ex-info "plan synchronized" {})))
                            (apply sync-approved-spools candidate-rt args)))]
            (is (= :applied
                   (:status (runtime/plan rt {:only [:planned]}))))
            (is (zero? @sync-calls)))
          (spit (io/file workspace "init.clj")
                (str "(millstrand.core.weaver.runtime/declare-module! "
                     "millstrand.core.weaver.runtime/*runtime* :cycle-a "
                     "{:file \"modules/plan-effects.clj\" :after [:cycle-b]})\n"
                     "(millstrand.core.weaver.runtime/declare-module! "
                     "millstrand.core.weaver.runtime/*runtime* :cycle-b "
                     "{:file \"modules/plan-effects.clj\" :after [:cycle-a]})\n"))
          (is (= :refused (:status (runtime/plan rt))))
          (is (= last-refresh (:last-refresh (runtime/status rt)))
              "neither an ordinary nor refused plan records coordinator state"))))))

(deftest f7-startup-accepts-an-optional-only-root-failure
  (let [world (temp-world)
        workspace (:config-dir world)
        suffix (str/replace (str (random-uuid)) "-" "")
        source "modules/optional.clj"
        rt (atom nil)]
    (try
      (module-source! workspace source
                      (symbol (str "test.module.optional-" suffix)))
      (spit (io/file workspace "spools.edn")
            (pr-str {:spools {'test/missing
                              {:local/root "spools/does-not-exist"}}}))
      (spit (io/file workspace "init.clj")
            (str "(millstrand.core.weaver.runtime/declare-module! "
                 "millstrand.core.weaver.runtime/*runtime* :optional "
                 "{:file \"modules/optional.clj\" "
                 ":spools ['test/missing]})\n"))
      (reset! rt (weaver-runtime/start! nil {:world world
                                             :publish? false
                                             :storage :sqlite-memory}))
      (let [status (runtime/status @rt)]
        (is (= :unchanged (get-in status [:last-refresh :status])))
        (is (= :skipped (get-in status [:module/outcomes :optional :status])))
        (is (= :failed (get-in status [:root/outcomes 'test/missing :status]))))
      (finally
        (when @rt (weaver-runtime/stop! @rt))
        (delete-tree! (io/file workspace ".."))))))

(deftest targeted-refresh-validates-keys-and-full-graph-refusal-preserves-state
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/valid.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.valid-" suffix))
                        (contribution-forms :valid))
        (reset! module-contributions
                {:valid {:queries {"valid" [:= [:attr :valid] true]}}})
        (weaver-runtime/declare-module! rt :valid {:file source})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                              (weaver-runtime/refresh-modules! rt {:only []})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                              (weaver-runtime/refresh-modules! rt {:only [:missing]})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                              (weaver-runtime/declare-module!
                               rt :bad {:file source :ns 'bad.ns})))
        (spit (io/file workspace "init.clj")
              (str "(millstrand.core.weaver.runtime/declare-module! "
                   "millstrand.core.weaver.runtime/*runtime* :cycle-a "
                   "{:file \"modules/valid.clj\" :after [:cycle-b]})\n"
                   "(millstrand.core.weaver.runtime/declare-module! "
                   "millstrand.core.weaver.runtime/*runtime* :cycle-b "
                   "{:file \"modules/valid.clj\" :after [:cycle-a]})\n"))
        (let [refused (weaver-runtime/refresh-modules! rt)]
          (is (= :refused (:status refused)))
          (is (contains? (graph/queries rt) "valid"))
          (is (contains? (:modules (weaver-runtime/module-status rt)) :valid)))))))

(deftest optional-skip-and-required-failure-have-structured-outcomes
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/gated.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.gated-" suffix)))
        (let [optional (weaver-runtime/declare-module!
                        rt :optional {:file source
                                      :spools ['missing/root]})]
          (is (= :unchanged (:status optional)))
          (is (= :skipped (get-in optional [:modules :optional :status])))
          (is (= :not-approved
                 (get-in optional [:modules :optional :reason]))))
        (let [required (weaver-runtime/declare-module!
                        rt :required {:file source
                                      :spools ['missing/root]
                                      :required? true})]
          (is (= :partial (:status required)))
          (is (= :failed (get-in required [:modules :required :status])))
          (is (true? (get-in required [:modules :required :required?]))))))))

(deftest hard-conflict-refuses-only-the-affected-module
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/conflicted.clj"
            suffix (str/replace (str (random-uuid)) "-" "")
            conflict {:reason :non-additive-sync-diff
                      :diff {:hard-conflicts
                             [{:reason :duplicate-provider
                               :namespace 'demo.conflict
                               :providers [{:root-lib 'demo/conflicted}
                                           {:root-lib 'demo/other}]}]}
                      :remedy "start a clean process generation"}]
        (module-source! workspace source
                        (symbol (str "test.module.conflicted-" suffix)))
        (with-redefs [spool-sync/sync-approved-spools
                      (fn [_runtime]
                        (throw (ex-info "hard conflict" conflict)))]
          (let [result (weaver-runtime/declare-module!
                        rt :conflicted {:file source
                                        :spools ['demo/conflicted]})]
            (is (= :refused (:status result)))
            (is (= :refused
                   (get-in result [:modules :conflicted :status])))
            (is (= :hard-conflict
                   (get-in result [:roots 'demo/conflicted :status])))
            (is (= ["start a clean process generation"] (:remedies result)))))))))

(deftest partial-source-reload-is-reported-without-rollback-claims
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/partial.clj"
            suffix (str/replace (str (random-uuid)) "-" "")
            records (atom [])
            diff {:redefinitions [{:lib 'demo/partial
                                   :loaded-namespaces ['demo.partial]}]
                  :namespace-residuals
                  [{:reason :changed-bytes
                    :namespace 'demo.partial
                    :binding {:root-lib 'demo/partial}}]}]
        (module-source! workspace source
                        (symbol (str "test.module.partial-" suffix)))
        (with-redefs [spool-sync/sync-approved-spools
                      (fn [_runtime]
                        (throw (ex-info "changed source"
                                        {:reason :non-additive-sync-diff
                                         :diff diff
                                         :remedy "repair source and refresh"})))
                      spool-sync/namespace-load-ledger (fn [_runtime] @records)
                      spool-sync/reload-synced-spool!
                      (fn [_runtime _root-lib]
                        (swap! records conj {:root-lib 'demo/partial})
                        (throw (ex-info "second namespace failed"
                                        {:reason :compile-failed})))]
          (let [result (weaver-runtime/declare-module!
                        rt :partial {:file source
                                     :spools ['demo/partial]
                                     :required? true})]
            (is (= :partial (:status result)))
            (is (= :partial-source-reload
                   (get-in result [:roots 'demo/partial :status])))
            (is (= 1 (get-in result
                             [:roots 'demo/partial :loaded-records])))
            (is (= :failed (get-in result [:modules :partial :status])))
            (is (empty? (:publication/kinds result)))))))))

(deftest module-refresh-preserves-event-queue-and-failure-history
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/live-event.clj"
            suffix (str/replace (str (random-uuid)) "-" "")]
        (module-source! workspace source
                        (symbol (str "test.module.live-event-" suffix))
                        (contribution-forms :live))
        (reset! module-contributions
                {:live {:queries {"live" [:= [:attr :live] true]}}})
        (events/register-handler! rt :refresh-failure #{:refresh/fail}
                                  'millstrand.core.weaver.modules-test/failing-event {})
        (dispatch/enqueue! rt (test-event :refresh/fail "before-refresh"))
        (t/await-quiescent! rt)
        (is (= 1 (count (events/recent-failures rt))))
        (events/register-handler! rt :refresh-block #{:refresh/block}
                                  'millstrand.core.weaver.modules-test/slow-capture-event {})
        (dispatch/enqueue! rt (test-event :refresh/block "in-flight"))
        (is (deref @handler-started (test-support/await-budget-ms 1000) false))
        (dispatch/enqueue! rt (test-event :refresh/block "queued"))
        (try
          (is (= 1 (.size ^java.util.concurrent.BlockingQueue
                    (get-in rt [:event-system :queue]))))
          (is (= :applied
                 (:status
                  (weaver-runtime/declare-module! rt :live {:file source}))))
          (is (= 1 (.size ^java.util.concurrent.BlockingQueue
                    (get-in rt [:event-system :queue])))
              "refresh neither drains nor clears queued work")
          (is (= 1 (count (events/recent-failures rt)))
              "refresh leaves recent failure history intact")
          (finally
            (deliver @handler-release true)))
        (t/await-quiescent! rt)
        (is (= #{"in-flight" "queued"}
               (set (map :event/id @delivered-events))))))))

(deftest module-defhandler-publishes-a-dispatchable-event-handler
  (with-runtime
    (fn [rt _db-file]
      (let [workspace (get-in rt [:metadata :config-dir])
            source "modules/defhandler.clj"
            suffix (str/replace (str (random-uuid)) "-" "")
            module-ns (symbol (str "test.module.defhandler-" suffix))
            handler-sym (symbol (str module-ns) "module-handler")]
        (module-source!
         workspace source module-ns
         (str "(millstrand.api.millstrand.alpha/defhandler! module-handler\n"
              "  \"Capture a module event.\"\n"
              "  {:types #{:module/test}}\n"
              "  [event]\n"
              "  (swap! millstrand.core.weaver.modules-test/module-deliveries\n"
              "         conj event))"))
        (is (= :applied
               (:status (weaver-runtime/declare-module! rt :defhandler {:file source}))))
        (is (= {:key :module-handler
                :types #{:module/test}
                :fn handler-sym
                :metadata {}}
               (first (events/handlers rt))))
        (is (not (contains? (first (events/handlers rt)) :fn-value)))
        (let [event (test-event :module/test "module-event")]
          (dispatch/enqueue! rt event)
          (t/await-quiescent! rt)
          (is (= [event] @module-deliveries)))))))

(deftest fresh-declarations-refuse-entry-point-keys
  (testing "the direct internal declare-module! route refuses either key"
    (with-runtime
      (fn [rt _db-file]
        (let [workspace (get-in rt [:metadata :config-dir])
              suffix (str/replace (str (random-uuid)) "-" "")
              source "modules/fresh-legacy.clj"]
          (module-source! workspace source
                          (symbol (str "test.module.fresh-legacy-" suffix))
                          (contribution-forms :fresh-legacy))
          (doseq [field [:contribute :reconcile]]
            (let [failure (try
                            (weaver-runtime/declare-module!
                             rt :fresh-legacy
                             {:file source field 'millstrand.core.weaver.modules-test/module-contribute})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
              (is failure (str "expected a refusal for " field))
              (is (= [field] (:removed (ex-data failure)))
                  "the refusal names the offending key")
              (is (= :fresh-legacy (:module/key (ex-data failure))))
              (is (re-find #"authoring forms" (ex-message failure))
                  "and points at the grammar that replaced it")))
          (is (not (contains? (:modules (weaver-runtime/module-status rt))
                              :fresh-legacy))
              "a refused declaration is not recorded")))))
  (testing "startup collection refuses a legacy declaration before any module runs"
    (let [world (temp-world)
          workspace (:config-dir world)
          suffix (str/replace (str (random-uuid)) "-" "")
          rt (atom nil)]
      (try
        (module-source! workspace "modules/startup-legacy.clj"
                        (symbol (str "test.module.startup-legacy-" suffix))
                        (contribution-forms :startup-legacy))
        (spit (io/file workspace "init.clj")
              (str "(millstrand.core.weaver.runtime/declare-module! "
                   "millstrand.core.weaver.runtime/*runtime* :startup-legacy "
                   "{:file \"modules/startup-legacy.clj\" "
                   ":contribute 'millstrand.core.weaver.modules-test/module-contribute})\n"))
        (let [failure (try
                        (reset! rt (weaver-runtime/start!
                                    nil {:world world :publish? false}))
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is failure "expected startup to fail loudly")
          (is (some #(re-find #"no longer name entry points" %)
                    (remove nil? (throwable-messages failure)))
              "the startup failure carries the declaration refusal"))
        (finally
          (when @rt (weaver-runtime/stop! @rt))
          (delete-tree! (io/file workspace "..")))))))
