(ns quality.millstrand-transition
  "Validate the coordinated dependency transition contract.

  Workspace config pins every maintained root in the coordinated release set.
  The checked-in contract carries no deferrals."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private contract-resource "quality/millstrand-transition-contract.edn")
(def ^:private expected-scopes #{})
(def ^:private allowed-scopes #{:pinned-external-spool-suite})
(def ^:private expected-pins
  {'codethread/config
   {:git/url "https://github.com/codethread/codethread.spool.git"
    :git/sha "356841d810cac6408cc4fb3cf6cca0094562d28e"
    :deps/root "spools/config"}
   'codethread/ralph
   {:git/url "https://github.com/codethread/codethread.spool.git"
    :git/sha "356841d810cac6408cc4fb3cf6cca0094562d28e"
    :deps/root "spools/ralph"}
   'codethread/devflow
   {:git/url "https://github.com/codethread/devflow.spool.git"
    :git/sha "90799b8c950b4509167137562fbf18853524d41c"
    :deps/root "."}
   'ct.spools/harness-core
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "harness-core"}
   'ct.spools/codex-harness
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "codex-harness"}
   'ct.spools/agent-run
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "agent-run"}
   'ct.spools/agent-cli
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "agent-cli"}
   'ct.spools/delegation
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "delegation"}
   'ct.spools/bench
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "fd75bf50ef823e1df520ead410780961d6313474"
    :deps/root "bench"}
   'millhouse.spools/identity
   {:git/url "https://github.com/codethread/millhouse.spool.git"
    :git/sha "f487eb42ea9523e8bd405e64a7c319013217d988"
    :deps/root "spools/identity"}
   'millhouse.spools/workflow
   {:git/url "https://github.com/codethread/millhouse.spool.git"
    :git/sha "f487eb42ea9523e8bd405e64a7c319013217d988"
    :deps/root "spools/workflow"}
   'millhouse.spools/chime
   {:git/url "https://github.com/codethread/millhouse.spool.git"
    :git/sha "f487eb42ea9523e8bd405e64a7c319013217d988"
    :deps/root "spools/chime"}
   'millhouse.spools/cron
   {:git/url "https://github.com/codethread/millhouse.spool.git"
    :git/sha "f487eb42ea9523e8bd405e64a7c319013217d988"
    :deps/root "spools/cron"}
   'millhouse.spools/kanban
   {:git/url "https://github.com/codethread/millhouse.spool.git"
    :git/sha "f487eb42ea9523e8bd405e64a7c319013217d988"
    :deps/root "spools/kanban"}})
(def ^:private expected-libs (set (keys expected-pins)))
(def ^:private maintained-git-urls (set (map :git/url (vals expected-pins))))

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- require!
  [condition message data]
  (when-not condition
    (fail! message data)))

(defn contract
  "Read and return the checked-in transition contract."
  []
  (if-let [resource (io/resource contract-resource)]
    (edn/read-string (slurp resource))
    (fail! (str "Missing transition contract resource " contract-resource) {})))

(defn- valid-sha?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{40}" value))))

(defn validate-contract!
  "Validate the transition contract shape and return it.

  This checks the allowlist itself. `validate-current!` additionally compares
  every maintained root with the repository's workspace coordinates."
  [value]
  (require! (map? value) "Transition contract must be a map" {:value value})
  (require! (= "PROP-Dns-001.S7" (:contract value))
            "Transition contract has the wrong feature clause"
            {:contract (:contract value)})
  (require! (= :coordinated-release-set-pinned (:phase value))
            "Transition contract has the wrong phase"
            {:phase (:phase value)})
  (let [pins (:pins value)
        deferrals (:deferrals value)]
    (require! (= expected-libs (set (keys pins)))
              "Transition contract must name exactly the maintained root pins"
              {:expected expected-libs :actual (set (keys pins))})
    (doseq [[lib pin] pins]
      (require! (and (map? pin)
                     (= #{:git/url :git/sha :deps/root} (set (keys pin)))
                     (string? (:git/url pin))
                     (valid-sha? (:git/sha pin))
                     (string? (:deps/root pin)))
                "Transition contract contains a malformed root pin"
                {:lib lib :pin pin}))
    (require! (= expected-pins pins)
              "Transition contract maintained pins drifted"
              {:expected expected-pins :actual pins})
    (require! (vector? deferrals)
              "Transition contract deferrals must be a vector"
              {:deferrals deferrals})
    (require! (= (count expected-scopes) (count deferrals))
              "Transition contract must have one entry for each scope"
              {:expected (count expected-scopes) :actual (count deferrals)})
    (require! (= expected-scopes (set (map :scope deferrals)))
              "Transition contract scopes drifted"
              {:expected expected-scopes :actual (set (map :scope deferrals))})
    (doseq [{:keys [scope test-namespaces]} deferrals]
      (require! (empty? test-namespaces)
                "External-suite deferral cannot hide test namespaces"
                {:scope scope :test-namespaces test-namespaces}))
    value))

(defn- workspace-deps
  [deps-file]
  (let [data (edn/read-string (slurp deps-file))]
    (require! (map? data) "Workspace dependency data must be a map" {:file deps-file})
    (require! (map? (:deps data))
              "Workspace :deps must be a map"
              {:file deps-file :deps (:deps data)})
    (:deps data)))

(defn- maintained-deps
  [dependencies]
  (into {}
        (filter (fn [[lib coordinate]]
                  (or (contains? expected-libs lib)
                      (and (map? coordinate)
                           (contains? maintained-git-urls (:git/url coordinate))))))
        dependencies))

(defn validate-current!
  "Validate the checked-in transition contract against `.millstrand/deps.edn`."
  ([] (validate-current! ".millstrand/deps.edn"))
  ([deps-file]
   (let [contract (validate-contract! (contract))
         dependencies (workspace-deps deps-file)
         actual (maintained-deps dependencies)]
     (require! (= (:pins contract) actual)
               "Workspace maintained dependency set does not match the transition contract"
               {:expected (:pins contract) :actual actual})
     contract)))

(defn deferred-test-namespaces
  "Return the exact test namespaces deferred by `scope`."
  [scope]
  (let [entry (some #(when (= scope (:scope %)) %) (:deferrals (validate-contract! (contract))))]
    (or (:test-namespaces entry)
        (fail! "Unknown transition deferral scope" {:scope scope}))))

(defn deferred?
  "Return whether `scope` is currently deferred by the transition contract."
  [scope]
  (boolean (some #(= scope (:scope %)) (:deferrals (validate-current!)))))

(defn- parse-main-args
  [args]
  (case (count args)
    0 nil
    2 (let [[flag value] args
            scope (keyword value)]
        (require! (= "--scope" flag)
                  "Transition check accepts only --scope <scope>"
                  {:args args})
        (require! (contains? allowed-scopes scope)
                  "Unknown transition deferral scope"
                  {:scope scope :expected allowed-scopes})
        scope)
    (fail! "Transition check expects no arguments or --scope <scope>"
           {:args args})))

(defn- -main*
  [args]
  (let [scope (parse-main-args args)]
    (validate-current!)
    (if scope
      (println (if (deferred? scope) "DEFERRED" "ACTIVE"))
      (println "millstrand transition contract: clean"))))

(defn -main
  "Validate the transition contract for a cold shell gate."
  [& args]
  (-main* args))
