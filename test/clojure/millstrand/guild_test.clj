(ns millstrand.guild-test
  "Tests for the guild reference spool's op declaration and deprecation API."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.weaver.alpha :as weaver]
            [skein.examples.guild :as guild]
            [millstrand.spools.test-support :as test-support :refer [assert-state-shape with-runtime]]
            [millstrand.test.alpha :as t]))

(s/def ::task string?)
(s/def ::close-input (s/keys :req-un [::task]))

(defn close-handler
  "Return the parsed guild input for test assertions."
  [ctx]
  {:op (:op/name ctx)
   :input (:guild/input ctx)})

(defn- json-arg [value]
  (json/write-str value :key-fn name))

(def ^:private close-return
  {:type :map
   :required {:op :string :input {:type :map :extra :json}}})

(deftest spool-state-shape-is-pinned
  (assert-state-shape #'guild/new-state
                      #{:guild-ops :deprecated-ops :fallback-guild-name}))

(deftest source-and-image-activation-preserve-healthy-guild-state
  (with-runtime
    (fn [rt _]
      (let [source (test-support/activate-spool!
                    rt :skein/examples-guild 'skein.examples.guild)]
        (is (= :applied (get-in source [:modules :skein/examples-guild :status])))
        (is (= :applied
               (get-in source
                       [:modules :skein/examples-guild :lifecycle/outcomes
                        :guild-state :status]))))
      (guild/set-fallback-guild-name! rt "preserved-guild")
      (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                             :hook-class :mutating
                                             :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (let [image (test-support/activate-spool!
                   rt :skein/examples-guild 'skein.examples.guild :load :image)]
        (is (= :unchanged
               (get-in image [:modules :skein/examples-guild :status])))
        (is (= :image
               (get-in image [:modules :skein/examples-guild :source/status])))
        (is (= :applied
               (get-in image
                       [:modules :skein/examples-guild :lifecycle/status])))
        (is (= [:guild-state]
               (get-in image
                       [:modules :skein/examples-guild :lifecycle/plan
                        :preserve])))
        (is (= "preserved-guild"
               (:guild (guild/guild-op {:op/runtime rt}))))
        (is (= ["gate.close.v1"]
               (mapv :name (:active (guild/guild-op {:op/runtime rt})))))))))

(deftest declarations-are-owned-and-delete-by-omission
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (let [handle (#'guild/declarations-handle rt)
            kind :skein.examples.guild/declarations
            owner :skein.examples.guild/defaults]
        (is (= #{"gate.close.v1"}
               (set (keys (registry/effective handle kind)))))
        (registry/remove-owner! handle kind owner)
        (is (empty? (registry/effective handle kind))
            "removing guild's complete owner partition deletes its declarations")
        (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                               :hook-class :mutating :deadline-class :standard}
                            'millstrand.guild-test/close-handler)
        (let [entry (assoc (get (registry/effective handle kind) "gate.close.v1")
                           :doc "Workspace close")]
          (registry/replace-owner! handle kind :workspace/test
                                   {:layer :workspace
                                    :entries {"gate.close.v1" entry}
                                    :overrides #{"gate.close.v1"}})
          (is (= "Workspace close"
                 (:doc (get (registry/effective handle kind) "gate.close.v1"))))
          (registry/remove-owner! handle kind :workspace/test)
          (is (= "Close"
                 (:doc (get (registry/effective handle kind) "gate.close.v1")))
              "removing an override restores guild's stable owner entry"))))))

(deftest production-return-coverage-is-derived-from-guild-provenance
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/set-fallback-guild-name! rt "coverage-guild")
      (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (let [entries (filterv #(= 'skein.examples.guild (:provenance %)) (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (set (map (juxt :name (constantly {})) entries))
            listing (weaver/op! rt 'guild ["list"])
            closed (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])
            _ (t/check-op-return! rt 'guild {:subcommand ["list"]} listing)
            _ (t/check-op-return! rt 'gate.close.v1 closed)
            checked #{["guild" {}] ["gate.close.v1" {}]}]
        (is (= [] missing))
        (is (= #{} (set/difference required checked)))))))

(deftest register-op-registers-and-invokes-through-op-registry
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (is (= {:op "gate.close.v1" :input {:task "T-1"}}
             (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])))
      (guild/register-op! rt 'ping.v1 {:doc "Ping" :returns close-return
                                       :hook-class :read :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (is (= [:read :standard]
             ((juxt :hook-class :deadline-class)
              (:arg-spec (weaver/resolve-op rt 'ping.v1)))))
      (is (= {:op "ping.v1" :input {}}
             (weaver/op! rt 'ping.v1 []))))))

(deftest register-op-propagates-unexpected-registry-errors
  (with-runtime
    (fn [rt _]
      (with-redefs [weaver/resolve-op
                    (fn [& _]
                      (throw (ex-info "Unexpected registry failure" {:reason :unexpected})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexpected registry failure"
                              (guild/register-op! rt 'gate.close.v1
                                                  {:doc "Close"
                                                   :hook-class :read
                                                   :deadline-class :standard}
                                                  'millstrand.guild-test/close-handler)))))))

(deftest input-spec-invalid-input-fails-loudly-with-structured-data
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:wrong "x"})])
        (is false "expected spec validation failure")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :operation/input-invalid (:code data)))
            (is (= "gate.close.v1" (:operation data)))
            (testing "the failure speaks the shared projection vocabulary"
              (is (= "millstrand.guild-test/close-input" (:spec data)))
              (is (= "map" (get-in data [:contract "kind"])))
              (is (= ["task"]
                     (mapv #(get % "key") (get-in data [:contract "required"]))))
              (is (contains? (:template data) "task"))
              (is (vector? (:spec-forms data)))
              (is (string? (:explain data))))))))))

(deftest input-spec-projects-through-help
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (testing "strand help projects the declared input contract (SPEC-003.C70)"
        (let [input (-> (weaver/op! rt 'help ["gate.close.v1"])
                        (get-in [:node :invocation :positionals])
                        first)]
          (is (= "millstrand.guild-test/close-input" (:spec input)))
          (is (= "map" (get-in input [:contract "kind"])))
          (is (contains? (:template input) "task"))))
      (testing "a deprecation stub stops projecting the retired contract"
        (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2"})
        (let [input (-> (weaver/op! rt 'help ["gate.close.v1"])
                        (get-in [:node :invocation :positionals])
                        first)]
          (is (nil? (:spec input)))
          (is (not (contains? input :contract))))))))

(deftest guild-list-reports-active-and-deprecated-ops
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/set-fallback-guild-name! rt "fallback-guild")
      (is (= "fallback-guild" (:guild (guild/guild-op {:op/runtime rt}))))
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close v1" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (guild/register-op! rt 'gate.close.v2
                          {:doc "Close v2" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2" :since "2026-07-02"})
      (let [listing (weaver/op! rt 'guild ["list"])]
        (is (string? (:guild listing)))
        (is (= "guild list" (:operation listing)))
        (is (= [{:name "gate.close.v2"
                 :doc "Close v2"
                 :input-spec "millstrand.guild-test/close-input"}]
               (:active listing)))
        (is (= [{:name "gate.close.v1"
                 :replacement "gate.close.v2"
                 :doc "Close v1"
                 :since "2026-07-02"}]
               (:deprecated listing)))))))

(deftest deprecated-op-throws-structured-error-and-never-succeeds
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1 {:doc "Close v1" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2"})
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])
        (is false "deprecated op must not succeed")
        (catch clojure.lang.ExceptionInfo e
          (is (= {:code :operation/deprecated
                  :operation "gate.close.v1"
                  :replacement "gate.close.v2"}
                 (ex-data e))))))))

(deftest guild-error-codes-reach-the-socket-namespace-intact
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (guild/register-op! rt 'gate.close.v1 {:doc "Close v1" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (guild/register-op! rt 'gate.open.v1 {:doc "Open v1" :input-spec ::close-input
                                            :returns close-return
                                            :hook-class :mutating :deadline-class :standard}
                          'millstrand.guild-test/close-handler)
      (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2"})
      (testing "deprecation"
        (let [frame (test-support/socket-invoke rt 'gate.close.v1 [(json-arg {:task "T-1"})])]
          (is (false? (get frame "ok")))
          (is (= "operation/deprecated" (get-in frame ["error" "code"])))
          (is (= "gate.close.v2" (get-in frame ["error" "details" "replacement"])))))
      (testing "input spec validation"
        (let [frame (test-support/socket-invoke rt 'gate.open.v1 [(json-arg {:task 42})])]
          (is (false? (get frame "ok")))
          (is (= "operation/input-invalid" (get-in frame ["error" "code"])))
          (is (= "gate.open.v1" (get-in frame ["error" "details" "operation"]))))))))

(deftest malformed-guild-declarations-fail-loudly
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/examples-guild 'skein.examples.guild)
      (testing "unknown register-op! opts"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"guild/register-op! received unknown keys"
                              (guild/register-op! rt 'gate.close.v1 {:doc "x" :extra true
                                                                     :hook-class :read :deadline-class :standard}
                                                  'millstrand.guild-test/close-handler))))
      (testing "leaf classes are required by the owning opts spec"
        (let [err (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts failed spec validation"
                                        (guild/register-op! rt 'missing.hook.v1 {:doc "x" :deadline-class :standard}
                                                            'millstrand.guild-test/close-handler)))]
          (is (= "skein.examples.guild/register-op-opts" (:spec (ex-data err))))
          (is (str/includes? (:explain (ex-data err)) ":hook-class")))
        (let [err (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts failed spec validation"
                                        (guild/register-op! rt 'missing.deadline.v1 {:doc "x" :hook-class :read}
                                                            'millstrand.guild-test/close-handler)))]
          (is (str/includes? (:explain (ex-data err)) ":deadline-class"))))
      (testing "namespaced registry names are rejected by the public registry"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"simple symbols or keywords"
                              (guild/register-op! rt 'gate/close.v1 {:doc "x"
                                                                     :hook-class :read :deadline-class :standard}
                                                  'millstrand.guild-test/close-handler))))
      (testing "unqualified handlers fail before registration"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                              (guild/register-op! rt 'bad.handler.v1 {:doc "x"
                                                                      :hook-class :read :deadline-class :standard}
                                                  'close-handler))))
      (testing "unresolved handlers fail before public registration"
        (is (thrown? java.io.FileNotFoundException
                     (guild/register-op! rt 'bad.resolve.v1 {:doc "x"
                                                             :hook-class :read :deadline-class :standard}
                                         'missing.guild/handler)))
        (is (not-any? #(= "bad.resolve.v1" (:name %)) (weaver/ops rt))))
      (testing "deprecating an unregistered op fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not registered"
                              (guild/deprecate! rt 'missing.v1 {:replacement "missing.v2"})))))))

(deftest authored-module-declarations-use-the-public-forms
  (testing "Guild contributes its static op and owns reset as one resource"
    (is (fn? guild/guild-op))
    (is (= {:kind :resource
            :open 'skein.examples.guild/reset-guild!
            :close 'skein.examples.guild/reset-guild!
            :after #{}
            :scope :module}
           guild/guild-state)))
  (is (nil? (ns-resolve 'skein.examples.guild 'spool)))
  (is (nil? (ns-resolve 'skein.examples.guild 'contribute)))
  (is (nil? (ns-resolve 'skein.examples.guild 'reconcile))))

(deftest lifecycle-reset-rejects-an-invalid-context
  (try
    (guild/reset-guild! {})
    (is false "expected lifecycle context validation failure")
    (catch clojure.lang.ExceptionInfo error
      (is (= "Invalid Guild lifecycle context" (ex-message error)))
      (is (= {} (:value (ex-data error))))
      (is (map? (:explain (ex-data error)))))))
