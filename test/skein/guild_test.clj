(ns skein.guild-test
  "Tests for the guild reference spool's op declaration and deprecation API."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [skein.api.registry.alpha :as registry]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.guild :as guild]
            [skein.spools.test-support :as test-support :refer [assert-state-shape with-runtime]]
            [skein.test.alpha :as t]))

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

(deftest declarations-are-owned-and-delete-by-omission
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (let [handle (#'guild/declarations-handle rt)
            kind :skein.spools.guild/declarations
            owner :skein.spools.guild/defaults]
        (is (= #{"gate.close.v1"}
               (set (keys (registry/effective handle kind)))))
        (registry/remove-owner! handle kind owner)
        (is (empty? (registry/effective handle kind))
            "removing guild's complete owner partition deletes its declarations")
        (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                               :hook-class :mutating :deadline-class :standard}
                            'skein.guild-test/close-handler)
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
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/set-fallback-guild-name! rt "coverage-guild")
      (guild/register-op! rt 'gate.close.v1 {:doc "Close" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (let [entries (filterv #(= 'skein.spools.guild (:provenance %)) (weaver/ops rt))
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
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (is (= {:op "gate.close.v1" :input {:task "T-1"}}
             (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])))
      (guild/register-op! rt 'ping.v1 {:doc "Ping" :returns close-return
                                       :hook-class :read :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (is (= [:read :standard]
             ((juxt :hook-class :deadline-class)
              (:arg-spec (weaver/resolve-op rt 'ping.v1)))))
      (is (= {:op "ping.v1" :input {}}
             (weaver/op! rt 'ping.v1 []))))))

(deftest input-spec-invalid-input-fails-loudly-with-structured-data
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:wrong "x"})])
        (is false "expected spec validation failure")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :operation/input-invalid (:code data)))
            (is (= "gate.close.v1" (:operation data)))
            (testing "the failure speaks the shared projection vocabulary"
              (is (= "skein.guild-test/close-input" (:spec data)))
              (is (= "map" (get-in data [:contract "kind"])))
              (is (= ["task"]
                     (mapv #(get % "key") (get-in data [:contract "required"]))))
              (is (contains? (:template data) "task"))
              (is (vector? (:spec-forms data)))
              (is (string? (:explain data))))))))))

(deftest input-spec-projects-through-help
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close a peer gate" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (testing "strand help projects the declared input contract (SPEC-003.C70)"
        (let [input (-> (weaver/op! rt 'help ["gate.close.v1"])
                        (get-in [:node :invocation :positionals])
                        first)]
          (is (= "skein.guild-test/close-input" (:spec input)))
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
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/set-fallback-guild-name! rt "fallback-guild")
      (is (= "fallback-guild" (:guild (guild/ops {:op/runtime rt}))))
      (guild/register-op! rt 'gate.close.v1
                          {:doc "Close v1" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (guild/register-op! rt 'gate.close.v2
                          {:doc "Close v2" :input-spec ::close-input :returns close-return
                           :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2" :since "2026-07-02"})
      (let [listing (weaver/op! rt 'guild ["list"])]
        (is (string? (:guild listing)))
        (is (= "guild list" (:operation listing)))
        (is (= [{:name "gate.close.v2"
                 :doc "Close v2"
                 :input-spec "skein.guild-test/close-input"}]
               (:active listing)))
        (is (= [{:name "gate.close.v1"
                 :replacement "gate.close.v2"
                 :doc "Close v1"
                 :since "2026-07-02"}]
               (:deprecated listing)))))))

(deftest deprecated-op-throws-structured-error-and-never-succeeds
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (guild/register-op! rt 'gate.close.v1 {:doc "Close v1" :returns close-return
                                             :hook-class :mutating :deadline-class :standard}
                          'skein.guild-test/close-handler)
      (guild/deprecate! rt 'gate.close.v1 {:replacement "gate.close.v2"})
      (try
        (weaver/op! rt 'gate.close.v1 [(json-arg {:task "T-1"})])
        (is false "deprecated op must not succeed")
        (catch clojure.lang.ExceptionInfo e
          (is (= {:code :operation/deprecated
                  :operation "gate.close.v1"
                  :replacement "gate.close.v2"}
                 (ex-data e))))))))

(deftest malformed-guild-declarations-fail-loudly
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :skein/spools-guild 'skein.spools.guild)
      (testing "unknown register-op! opts"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"guild/register-op! received unknown keys"
                              (guild/register-op! rt 'gate.close.v1 {:doc "x" :extra true
                                                                     :hook-class :read :deadline-class :standard}
                                                  'skein.guild-test/close-handler))))
      (testing "leaf classes are required by the owning opts spec"
        (let [err (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts failed spec validation"
                                        (guild/register-op! rt 'missing.hook.v1 {:doc "x" :deadline-class :standard}
                                                            'skein.guild-test/close-handler)))]
          (is (= "skein.spools.guild/register-op-opts" (:spec (ex-data err))))
          (is (str/includes? (:explain (ex-data err)) ":hook-class")))
        (let [err (is (thrown-with-msg? clojure.lang.ExceptionInfo #"opts failed spec validation"
                                        (guild/register-op! rt 'missing.deadline.v1 {:doc "x" :hook-class :read}
                                                            'skein.guild-test/close-handler)))]
          (is (str/includes? (:explain (ex-data err)) ":deadline-class"))))
      (testing "namespaced registry names are rejected by the public registry"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"simple symbols or keywords"
                              (guild/register-op! rt 'gate/close.v1 {:doc "x"
                                                                     :hook-class :read :deadline-class :standard}
                                                  'skein.guild-test/close-handler))))
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

(deftest contribute-entry-passes-publication-validation
  (testing "guild's module contribution validates through the canonical publication seam"
    (let [entry (get-in (guild/contribute {}) [:ops :entries "guild"])]
      (is (map? (weaver/validate-op-entry! entry)))
      (is (not (contains? entry :hook-class)))
      (is (not (contains? entry :deadline-class))))))
