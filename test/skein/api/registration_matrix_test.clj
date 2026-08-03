(ns skein.api.registration-matrix-test
  "Cross-kind lock on the live registration loop (PROP-Rgs-001.G1/S1).

  Ops, queries, patterns, hooks, and event handlers each own a suite for their
  kind-specific behavior. What lives here is the one promise none of those can
  state alone: that all five kinds answer the same three verbs the same way.
  One module publishes an entry per kind through the authoring forms, then every
  kind is driven through the identical table — claim a fresh name, collide loudly
  on a module-owned one, fail loudly on replacing an absent one, shadow the
  module entry with `replace-*!`, survive a module republication, and restore the
  original by retracting the shadow."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.events.alpha :as events]
            [skein.api.graph.alpha :as graph]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as weaver]
            [skein.test.alpha :as t])
  (:import [clojure.lang ExceptionInfo]))

(s/def ::direct-input (s/keys))

;; Registration resolves these by symbol, so they must be top-level Vars.
(defn direct-op
  "Op handler fixture for a direct registration."
  [_ctx]
  :direct)

(defn direct-pattern
  "Weave pattern fixture for a direct registration."
  [_input]
  [])

(defn direct-hook
  "Lifecycle hook fixture for a direct registration."
  [ctx]
  ctx)

(defn direct-handler
  "Event handler fixture for a direct registration."
  [_event]
  nil)

(def ^:private module-ns 'skein.api.registration-matrix-test.module)

(defn- module-source
  "Return module source publishing one entry per kind, named with `suffix`.

  Re-emitting the module with a second suffix appended is how the test forces a
  real republication: a `:file` module refresh reports `:unchanged` unless its
  contribution actually differs, and adding an entry to every kind guarantees
  every one of the module's owner partitions is rewritten."
  [suffix]
  (str "(skein/defop mop" suffix " \"Module op.\"\n"
       "  {:arg-spec {:op \"mop" suffix "\" :doc \"Module op.\"\n"
       "              :hook-class :read :deadline-class :standard}}\n"
       "  [_] :module)\n"
       "(skein/defquery mquery" suffix " \"Module query.\" {} [:= [:attr :k] 1])\n"
       "(skein/defpattern mpattern" suffix " \"Module pattern.\"\n"
       "  {:spec ::module-input} [_] [])\n"
       "(skein/defhook mhook" suffix " \"Module hook.\"\n"
       "  {:types #{:payload/received}} [_] nil)\n"
       "(skein/defhandler mhandler" suffix " \"Module handler.\"\n"
       "  {:types #{:strand/added}} [_] nil)\n"))

(def ^:private module-preamble
  (str "(ns skein.api.registration-matrix-test.module\n"
       "  (:require [clojure.spec.alpha :as s]\n"
       "            [skein.api.skein.alpha :as skein]))\n"
       "(s/def ::module-input (s/keys))\n"))

(defn- module-sym [suffix]
  (symbol (str module-ns) suffix))

(defn- direct-sym [suffix]
  (symbol "skein.api.registration-matrix-test" suffix))

(defn- op-opts
  "Return direct-registration op metadata for the op registered as `key`."
  [key]
  {:arg-spec {:op key :doc "Direct op."
              :hook-class :read :deadline-class :standard}})

(defn- entry-fn
  "Return the `:fn` of the entry `entries` holds under `key`, or nil."
  [entries key-of key entries-key]
  (some #(when (= key (key-of %)) (entries-key %)) entries))

(def ^:private matrix
  "One row per registry kind: how to name it, read it, and drive its verbs.

  `:module`, `:fresh`, and `:absent` are plain string labels; `:key` turns one
  into the registry key that kind stores, and each verb builds its own
  caller-facing name so the ops-versus-hooks naming difference stays inside the
  row rather than leaking into the test body."
  [{:label "ops"
    :key identity
    :module "mop"
    :fresh "scratch-op"
    :absent "absent-op"
    :module-tag (module-sym "mop-op")
    :direct-tag (direct-sym "direct-op")
    :effective (fn [rt key] (entry-fn (weaver/ops rt) :name key :fn))
    :register! (fn [rt label]
                 (weaver/register-op! rt (symbol label) (op-opts label)
                                      (direct-sym "direct-op")))
    :replace! (fn [rt label]
                (weaver/replace-op! rt (symbol label) (op-opts label)
                                    (direct-sym "direct-op")))
    :unregister! (fn [rt label] (weaver/unregister-op! rt (symbol label)))
    :collision #"Operation already registered"
    :replace-missing #"Operation not registered; cannot replace"}

   {:label "queries"
    :key identity
    :module "mquery"
    :fresh "scratch-query"
    :absent "absent-query"
    :module-tag [:= [:attr :k] 1]
    :direct-tag [:= [:attr :k] 2]
    :effective (fn [rt key] (get (graph/queries rt) key))
    :register! (fn [rt label] (graph/register-query! rt (symbol label) [:= [:attr :k] 2]))
    :replace! (fn [rt label] (graph/replace-query! rt (symbol label) [:= [:attr :k] 2]))
    :unregister! (fn [rt label] (graph/unregister-query! rt (symbol label)))
    :collision #"requires explicit override intent"
    :replace-missing #"Query not registered; cannot replace"}

   {:label "patterns"
    :key identity
    :module "mpattern"
    :fresh "scratch-pattern"
    :absent "absent-pattern"
    :module-tag (module-sym "mpattern")
    :direct-tag (direct-sym "direct-pattern")
    :effective (fn [rt key] (entry-fn (patterns/patterns rt) :name key :fn))
    :register! (fn [rt label]
                 (patterns/register-pattern! rt (symbol label)
                                             (direct-sym "direct-pattern") ::direct-input))
    :replace! (fn [rt label]
                (patterns/replace-pattern! rt (symbol label) nil
                                           (direct-sym "direct-pattern") ::direct-input))
    :unregister! (fn [rt label] (patterns/unregister-pattern! rt (symbol label)))
    :collision #"requires explicit override intent"
    :replace-missing #"Pattern not registered; cannot replace"}

   {:label "hooks"
    :key keyword
    :module "mhook"
    :fresh "scratch-hook"
    :absent "absent-hook"
    :module-tag (module-sym "mhook")
    :direct-tag (direct-sym "direct-hook")
    :effective (fn [rt key] (entry-fn (hooks/hooks rt) :key key :fn))
    :register! (fn [rt label]
                 (hooks/register-hook! rt (keyword label) #{:payload/received}
                                       (direct-sym "direct-hook") {}))
    :replace! (fn [rt label]
                (hooks/replace-hook! rt (keyword label) #{:payload/received}
                                     (direct-sym "direct-hook") {}))
    :unregister! (fn [rt label] (hooks/unregister-hook! rt (keyword label)))
    :collision #"requires explicit override intent"
    :replace-missing #"Hook not registered; cannot replace"}

   {:label "events"
    :key keyword
    :module "mhandler"
    :fresh "scratch-handler"
    :absent "absent-handler"
    :module-tag (module-sym "mhandler")
    :direct-tag (direct-sym "direct-handler")
    :effective (fn [rt key] (entry-fn (events/handlers rt) :key key :fn))
    :register! (fn [rt label]
                 (events/register-handler! rt (keyword label) #{:strand/added}
                                           (direct-sym "direct-handler") {}))
    :replace! (fn [rt label]
                (events/replace-handler! rt (keyword label) #{:strand/added}
                                         (direct-sym "direct-handler") {}))
    :unregister! (fn [rt label] (events/unregister-handler! rt (keyword label)))
    :collision #"requires explicit override intent"
    :replace-missing #"Event handler not registered; cannot replace"}])

(deftest every-kind-answers-the-same-register-replace-unregister-loop
  (t/with-weaver-world [ctx {:storage :sqlite-memory
                             :files {"modules/matrix.clj"
                                     (str module-preamble (module-source ""))}}]
    (let [rt (:runtime ctx)]
      (is (= :applied (:status (t/declare-module! ctx :matrix {:file "modules/matrix.clj"})))
          "one module publishes an owner-complete entry for all five kinds")

      (doseq [{:keys [label key module fresh absent module-tag direct-tag effective
                      register! replace! unregister! collision replace-missing]} matrix]
        (testing label
          (testing "the module owns the published entry"
            (is (= module-tag (effective rt (key module)))))
          (testing "registering a fresh name claims it in the direct layer"
            (register! rt fresh)
            (is (= direct-tag (effective rt (key fresh)))))
          (testing "registering over a module-owned name collides loudly"
            (is (thrown-with-msg? ExceptionInfo collision (register! rt module)))
            (is (= module-tag (effective rt (key module)))
                "the rejected registration changed nothing"))
          (testing "replacing an unregistered name fails loudly"
            (is (thrown-with-msg? ExceptionInfo replace-missing (replace! rt absent)))
            (is (nil? (effective rt (key absent)))))
          (testing "replacing a module-owned name shadows it with recorded intent"
            (replace! rt module)
            (is (= direct-tag (effective rt (key module)))))
          (testing "unregistering a fresh claim retracts it outright"
            (is (= {:unregistered (key fresh)} (unregister! rt fresh)))
            (is (nil? (effective rt (key fresh))))
            (is (= {:unregistered (key fresh)} (unregister! rt fresh))
                "retracting an absent name is an idempotent no-op"))))

      (testing "every direct shadow survives the module's republication"
        (spit (io/file (:config-dir ctx) "modules/matrix.clj")
              (str module-preamble (module-source "") (module-source "-two")))
        (is (= :applied (:status (t/refresh-modules! ctx {:only [:matrix]}))))
        (doseq [{:keys [label key module direct-tag effective]} matrix]
          (testing label
            (is (= direct-tag (effective rt (key module)))
                "the shadow outranks the republished module entry")
            (is (some? (effective rt (key (str module "-two"))))
                "the module's own partition was genuinely rewritten"))))

      (testing "retracting a shadow restores the module's own entry"
        (doseq [{:keys [label key module module-tag effective unregister!]} matrix]
          (testing label
            (is (= {:unregistered (key module)} (unregister! rt module)))
            (is (= module-tag (effective rt (key module))))))))))
