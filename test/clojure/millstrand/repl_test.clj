(ns millstrand.repl-test
  "Tests for millstrand.repl interactive convenience wrappers."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.cmdline]
            [nrepl.core :as nrepl]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.client :as client]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.repl :as repl]
            [millstrand.source-file :as source-file]
            [millstrand.spools.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha]))

(defn reset-open-state! []
  (reset! (var-get (ns-resolve 'millstrand.repl 'active-config-dir))
          (var-get (ns-resolve 'millstrand.repl 'no-connection))))

(s/def ::title string?)
(s/def ::simple-pattern-input (s/keys :req-un [::title]))

(defn simple-pattern [ctx]
  [{:ref 'created
    :title (get-in ctx [:input :title])}])

;; Registration resolves these by symbol, so they must be top-level Vars.
(defn wrapper-op
  "Op handler fixture for the registration-wrapper loop."
  [_ctx]
  :first)

(defn wrapper-op-2
  "Second op handler fixture, so replace-op! changes something observable."
  [_ctx]
  :second)

(defn wrapper-hook
  "Lifecycle hook fixture for the registration-wrapper loop."
  [ctx]
  ctx)

(defn wrapper-handler
  "Event handler fixture for the registration-wrapper loop."
  [_event]
  nil)

(def ^:private flat-read-standard
  {:arg-spec {:hook-class :read
              :deadline-class :standard
              :positionals [{:name :args :variadic? true}]}})

(defn- registered-op
  "Return the registered op entry named `op-name`, or nil.

  `weaver/ops` answers with a sorted vector rather than the registry map, so
  every op assertion here goes through this lookup."
  [rt op-name]
  (first (filter #(= op-name (:name %)) (weaver/ops rt))))

(defn with-runtime
  ([f]
   (with-runtime {} f))
  ([_opts f]
   (test-alpha/with-weaver-world [ctx {}]
     (try
       (f (:runtime ctx) (:db-path ctx))
       (finally
         (reset-open-state!))))))

(deftest connected-accessors-fail-before-connect
  (reset-open-state!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Millstrand weaver world is connected"
                        (repl/connected-config-dir)))
  (is (= {} (repl/connected-opts))
      "opts stay empty rather than throwing; the config-dir read is the guard"))

(deftest connect-without-arg-fails-loudly-without-selected-world
  (let [calls (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"connect! requires an explicit config-dir"
                          (repl/connect!)))
    (is (= [] @calls)
        "zero-arg connect! throws before reaching the status-world seam")
    (let [connected (#'repl/connect!* "/tmp/millstrand-connect-check" nil
                                      (fn [config-dir opts]
                                        (swap! calls conj {:config-dir config-dir :opts opts})
                                        {:ok true}))]
      (is (= connected (-> @calls first :config-dir)))
      (is (= [{:config-dir connected :opts {}}] @calls)
          "the seam is meaningful for config-dir connects, so the zero-arg assertion is not vacuous"))
    (reset-open-state!)))

(deftest connect-fails-without-selecting-a-daemon
  (let [config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (repl/connect! config-dir)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No Millstrand weaver world is connected"
                            (repl/connected-config-dir)))
      (finally
        (reset-open-state!)))))

(deftest failed-connect-clears-previous-selection
  (with-runtime
    {:publish? false}
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (spit db-file "not a config dir")
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"connect! expects a daemon config directory"
                              (repl/connect! db-file)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Millstrand weaver world is connected"
                              (repl/connected-config-dir)))
        (finally
          (db-test/delete-sqlite-family! db-file))))))

(deftest dev-user-namespace-loads
  (require 'user :reload)
  (is (some? (ns-resolve 'user 'demo!))))

(deftest explicit-connected-stdin-main-drives-the-weaver-over-the-client-bridge
  (with-runtime
    {:publish? false}
    (fn [rt _]
      (let [out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader.
                        (source-file/render-forms
                         ['(require '[millstrand.core.client :as client])
                          '(str *ns*)
                          '(client/call-world (repl/connected-config-dir)
                                              (repl/connected-opts)
                                              :init)
                          '(client/call-world (repl/connected-config-dir)
                                              (repl/connected-opts)
                                              :list)]))
                  *out* out
                  *err* (java.io.StringWriter.)]
          (repl/-main "--stdin" (:config-dir (:metadata rt))))
        (let [lines (str/split-lines (str out))]
          (is (= 4 (count lines)))
          (is (= "user" (read-string (second lines)))
              "a standalone session evaluates in the neutral namespace, not in millstrand.repl")
          (is (= {:database "initialized"} (read-string (nth lines 2))))
          (is (= [] (read-string (nth lines 3)))))))))

(deftest attached-stdin-session-can-reach-the-registration-verbs-through-the-repl-alias
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader.
                        (source-file/render-forms
                         ['(repl/register-query! 'session-query [:= [:attr :owner] "agent"])
                          '(repl/unregister-query! 'session-query)]))
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'millstrand.repl 'attach-stdin!)
           (:host endpoint)
           (str (:port endpoint))))
        (let [lines (str/split-lines (str out))]
          (is (= 2 (count lines)))
          (is (= {"session-query" [:= [:attr :owner] "agent"]} (read-string (first lines)))
              "the attached session bootstrap aliases millstrand.repl as repl without an explicit require")
          (is (= {:unregistered "session-query"} (read-string (second lines)))))))))

(deftest attach-stdin-evaluates-inside-weaver-jvm
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(str *ns*)\n(+ 1 2)\n(str \"a\" \"b\")\n@millstrand.core.weaver.runtime/current-runtime\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'millstrand.repl 'attach-stdin!) (:host endpoint) (str (:port endpoint))))
        (let [lines (rest (str/split-lines (str out)))]
          (is (= "\"user\"" (first (str/split-lines (str out))))
              "attached forms evaluate weaver-side in the neutral namespace")
          (is (= 3 (count lines)))
          (is (= "3" (first lines)))
          (is (= "\"ab\"" (second lines)))
          (is (str/includes? (nth lines 2) ":metadata"))
          (is (str/includes? (nth lines 2) ":query-store")))))))

(deftest attach-stdin-preserves-out-and-value-order-per-form
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(do (print \"a\") 1)\n(do (print \"b\") 2)\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'millstrand.repl 'attach-stdin!) (:host endpoint) (str (:port endpoint))))
        (is (= "a1\nb2\n" (str out)))))))

(deftest attach-repl-delegates-to-helper-ready-nrepl-client-repl
  (with-runtime
    {:publish? false}
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            calls (atom [])
            out (java.io.StringWriter.)
            run-repl-fn (fn [host port options]
                          (swap! calls conj {:host host
                                             :port port
                                             :options options})
                          (let [conn (nrepl/connect :host host :port port)
                                session (nrepl/client-session (nrepl/client conn 60000))]
                            (try
                              (swap! nrepl.cmdline/running-repl assoc :client session)
                              ((:prompt options) 'user)
                              (let [code "[(str *ns*) (= (find-ns 'millstrand.repl) (get (ns-aliases *ns*) 'repl))]"
                                    responses (doall (nrepl/message session {:op "eval" :code code}))]
                                (is (= "[\"user\" true]" (last (keep :value responses)))
                                    "the prompt bootstrap lands the session in the neutral namespace with millstrand.repl aliased"))
                              (finally
                                (swap! nrepl.cmdline/running-repl assoc :client nil)
                                (.close conn)))))]
        (binding [*in* (java.io.StringReader. "(+ 10 5)\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'millstrand.repl 'attach-repl!)
           (:host endpoint)
           (str (:port endpoint))
           {:run-repl-fn run-repl-fn}))
        (is (= [{:host (:host endpoint)
                 :port (:port endpoint)
                 :options {:prompt (:prompt (:options (first @calls)))}}]
               @calls))
        (is (fn? (get-in (first @calls) [:options :prompt])))
        (is (not (str/includes? (str out) "15")))))))

(deftest attached-stdin-session-exposes-the-runtime-api
  (test-alpha/with-weaver-world
    [ctx {:deps-edn "{:deps {org.clojure/tools.deps {:mvn/version \"0.31.1642\"}}}\n"}]
    (try
      (let [rt (:runtime ctx)
            {:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader.
                        (source-file/render-forms
                         ['(require '[millstrand.api.current.alpha :as current]
                                    '[millstrand.api.runtime.alpha :as runtime])
                          '(def rt (current/runtime))
                          '(runtime/status rt)
                          '(runtime/plan rt)]))
                  *out* out
                  *err* (java.io.StringWriter.)
                  *ns* (the-ns 'user)]
          ((ns-resolve 'millstrand.repl 'attach-stdin!)
           (:host endpoint)
           (str (:port endpoint))))
        (let [lines (str/split-lines (str out))
              status (read-string (nth lines 2))
              plan (read-string (nth lines 3))]
          (is (= 4 (count lines)))
          (is (= {:modules {}
                  :resources {}}
                 (select-keys status [:modules :resources])))
          (is (vector? (:loaded-namespaces status)))
          (is (string? (:basis-fingerprint status)))
          (is (= {:status :unchanged :mode :full}
                 (select-keys (:last-refresh status) [:status :mode])))
          (is (= {:status :unchanged :mode :full :dry-run? true}
                 (select-keys plan [:status :mode :dry-run?])))
          (is (str/includes? (:caveat plan) "No registry publication"))))
      (finally
        (reset-open-state!)))))

(deftest burn-tombstone-reads-use-in-process-datasource
  (with-runtime
    (fn [rt _db-file]
      (reset-open-state!)
      (weaver/init rt)
      (let [design (:id (weaver/add! rt {:title "Sketch model" :attributes {:priority "high"}}))
            docs (:id (weaver/add! rt {:title "Write docs" :attributes {:owner "agent"}}))]
        (weaver/update! rt docs {:edges [{:type "depends-on" :to design}]})
        (is (= {:burned [docs] :count 1} (repl/burn-by-ids! [docs])))
        (let [[tombstone :as history] (repl/burn-history docs)]
          (is (= 1 (count history)))
          (is (= docs (:strand_id tombstone)))
          (is (= "Write docs" (:title tombstone)))
          (is (= {:value "agent" :archived false} (get-in tombstone [:attributes :owner])))
          (is (= [{:from docs :to design :type "depends-on" :attributes {}}]
                 (:edges tombstone)))
          (is (some? (:recorded_at tombstone))))
        (is (= [] (repl/burn-history design)))
        (is (= [docs] (mapv :strand_id (repl/recent-burns 10))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Read result limit must be a positive integer"
                              (repl/recent-burns 0)))))))

(deftest burn-tombstone-reads-require-in-process-runtime
  (reset-open-state!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"mill weaver repl"
                        (repl/burn-by-ids! ["anything"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"mill weaver repl"
                        (repl/burn-history "anything")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"mill weaver repl"
                        (repl/recent-burns 5))))

(deftest registration-wrappers-drive-the-live-loop-with-the-runtime-implied
  (with-runtime
    (fn [rt _db-file]
      (reset-open-state!)
      (testing "ops"
        (is (= "wrap-op" (:name (repl/register-op! 'wrap-op flat-read-standard
                                                   'millstrand.repl-test/wrapper-op))))
        (is (= 'millstrand.repl-test/wrapper-op (:fn (registered-op rt "wrap-op"))))
        (is (= 'millstrand.repl-test/wrapper-op-2
               (:fn (repl/replace-op! 'wrap-op flat-read-standard
                                      'millstrand.repl-test/wrapper-op-2))))
        (is (= {:unregistered "wrap-op"} (repl/unregister-op! 'wrap-op)))
        (is (nil? (registered-op rt "wrap-op"))))
      (testing "queries"
        (is (= {"wrap-query" [:= [:attr :owner] "a"]}
               (repl/register-query! 'wrap-query [:= [:attr :owner] "a"])))
        (is (= {"wrap-query" [:= [:attr :owner] "b"]}
               (repl/replace-query! 'wrap-query [:= [:attr :owner] "b"])))
        (is (= [:= [:attr :owner] "b"] (get (graph/queries rt) "wrap-query")))
        (is (= {:unregistered "wrap-query"} (repl/unregister-query! 'wrap-query)))
        (is (nil? (get (graph/queries rt) "wrap-query"))))
      (testing "patterns"
        (is (= "wrap-pattern" (:name (repl/register-pattern! 'wrap-pattern
                                                             'millstrand.repl-test/simple-pattern
                                                             ::simple-pattern-input))))
        (is (= "iterated" (:doc (repl/replace-pattern! 'wrap-pattern "iterated"
                                                       'millstrand.repl-test/simple-pattern
                                                       ::simple-pattern-input))))
        (is (= {:unregistered "wrap-pattern"} (repl/unregister-pattern! 'wrap-pattern)))
        (is (empty? (patterns/patterns rt))))
      (testing "hooks"
        (is (= {:key :wrap-hook :order 0}
               (select-keys (repl/register-hook! :wrap-hook #{:strand/add-before-commit}
                                                 'millstrand.repl-test/wrapper-hook)
                            [:key :order])))
        (is (= {:key :wrap-hook :order 7}
               (select-keys (repl/replace-hook! :wrap-hook #{:strand/add-before-commit}
                                                'millstrand.repl-test/wrapper-hook {:order 7})
                            [:key :order])))
        (is (= {:unregistered :wrap-hook} (repl/unregister-hook! :wrap-hook)))
        (is (nil? (get (hooks/hooks rt) :wrap-hook))))
      (testing "event handlers"
        (is (= #{:test/wrap} (:types (repl/register-handler! :wrap-handler #{:test/wrap}
                                                             'millstrand.repl-test/wrapper-handler))))
        (is (= {:round 2} (:metadata (repl/replace-handler! :wrap-handler #{:test/wrap}
                                                            'millstrand.repl-test/wrapper-handler
                                                            {:round 2}))))
        (is (= {:unregistered :wrap-handler} (repl/unregister-handler! :wrap-handler)))
        (is (nil? (get (events/handlers rt) :wrap-handler)))))))

(deftest registered-queries-last-only-for-the-weaver-lifetime
  (with-runtime
    (fn [rt db-file]
      (reset-open-state!)
      (is (= {"mine" [:= [:attr :owner] "agent"]}
             (repl/register-query! :mine [:= [:attr :owner] "agent"])))
      (is (= {"mine" [:= [:attr :owner] "agent"]} (graph/queries rt)))
      (weaver-runtime/stop! rt)
      (let [fresh-rt (weaver-runtime/start! db-file
                                            {:world (test-support/test-world (:config-dir (:metadata rt)))
                                             :generation-basis (:generation-basis rt)})]
        (try
          (is (= {} (graph/queries fresh-rt))
              "SPEC-003.C12: the registry is weaver-lifetime, not durable")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Query not registered; cannot replace"
                                (repl/replace-query! :mine [:= [:attr :owner] "human"])))
          (finally
            (weaver-runtime/stop! fresh-rt)))))))

(deftest registration-wrappers-are-in-process-only
  (reset-open-state!)
  (let [standalone (ex-data (try (repl/register-query! 'nope [:= :id "x"])
                                 (catch clojure.lang.ExceptionInfo e e)))]
    (is (= {:helper 'register-query!
            :session-mode :standalone
            :code :millstrand.repl/no-in-process-runtime}
           standalone)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"neither a runtime nor a `connect!` selection"
                        (repl/unregister-hook! :nope)))
  (with-runtime
    {:publish? false}
    (fn [rt _db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (try
        (let [connected (try (repl/replace-op! 'nope flat-read-standard 'millstrand.repl-test/wrapper-op)
                             (catch clojure.lang.ExceptionInfo e e))]
          (is (= {:helper 'replace-op!
                  :session-mode :connected
                  :code :millstrand.repl/no-in-process-runtime}
                 (ex-data connected)))
          (is (str/includes? (ex-message connected) "`millstrand.core.client`")
              "a connected session is pointed at the client bridge it can actually use")
          (is (str/includes? (ex-message connected) "mill weaver repl")))
        (finally
          (reset-open-state!))))))

(deftest client-bridge-fails-loudly-when-the-selected-weaver-goes-away
  (with-runtime
    (fn [rt _]
      (try
        (repl/connect! (:config-dir (:metadata rt)))
        (metadata/delete! (:metadata rt))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"metadata is missing or stale"
                              (client/call-world (repl/connected-config-dir)
                                                 (repl/connected-opts)
                                                 :list)))
        (finally
          (reset-open-state!))))))
