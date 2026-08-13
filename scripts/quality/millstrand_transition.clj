(ns quality.millstrand-transition
  "Validate the narrowed Millstrand publisher transition contract.

  Workspace config is active against the moved publishers. The external suite
  is deferred for its exact unmigrated downstream families; their migration
  cards and the final cutover remove the checked-in deferral."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private contract-resource "quality/millstrand-transition-contract.edn")
(def ^:private expected-scopes #{:pinned-external-spool-suite})
(def ^:private allowed-scopes #{:pinned-external-spool-suite})
(def ^:private expected-pins
  {'codethread/devflow
   {:git/url "https://github.com/codethread/devflow.spool.git"
    :git/sha "e1fed4904447f4752ea99dc27ab174c237604094"}
   'codethread/kanban
   {:git/url "https://github.com/codethread/kanban.spool.git"
    :git/tag "v25"
    :git/sha "a6b3a36cd5476ec5c36cd58a7f74bfec6b7e665e"}
   'ct.spools/agent-run
   {:git/url "https://github.com/codethread/agent-harness.spool.git"
    :git/sha "636ae9ecbb57c4fde2c79485c7ef7767aba5753b"}})
(def ^:private expected-families (set (keys expected-pins)))
(def ^:private expected-deferred-families expected-families)

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
  every named pin with the repository's approved external coordinates."
  [value]
  (require! (map? value) "Transition contract must be a map" {:value value})
  (require! (= "PROP-Msr-001.S6" (:contract value))
            "Transition contract has the wrong feature clause"
            {:contract (:contract value)})
  (require! (= :external-publishers-compatible (:phase value))
            "Transition contract has the wrong phase"
            {:phase (:phase value)})
  (let [pins (:pins value)
        deferrals (:deferrals value)]
    (require! (= expected-families (set (keys pins)))
              "Transition contract must name exactly the external family pins"
              {:expected expected-families :actual (set (keys pins))})
    (doseq [[family pin] pins]
      (require! (and (map? pin)
                     (string? (:git/url pin))
                     (valid-sha? (:git/sha pin))
                     (or (nil? (:git/tag pin))
                         (string? (:git/tag pin))))
                "Transition contract contains a malformed family pin"
                {:family family :pin pin}))
    (require! (= expected-pins pins)
              "Transition contract external pins drifted"
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
    (doseq [{:keys [scope families test-namespaces]} deferrals]
      (require! (= expected-deferred-families families)
                "Deferred scope must name only the exact incompatible family pin"
                {:scope scope :families families})
      (require! (empty? test-namespaces)
                "External-suite deferral cannot hide test namespaces"
                {:scope scope :test-namespaces test-namespaces}))
    value))

(defn- approved-pins
  [spools-file]
  (let [data (edn/read-string (slurp spools-file))]
    (require! (map? data) "Approved spool data must be a map" {:file spools-file})
    (:spools data)))

(defn validate-current!
  "Validate the checked-in transition contract against `.millstrand/spools.edn`."
  ([] (validate-current! ".millstrand/spools.edn"))
  ([spools-file]
   (let [contract (validate-contract! (contract))
         approved (approved-pins spools-file)]
     (doseq [[family expected] (:pins contract)]
       (let [actual (get approved family)]
         (require! (= (select-keys expected [:git/url :git/tag :git/sha])
                      (select-keys actual [:git/url :git/tag :git/sha]))
                   "Approved external pin does not match the transition contract"
                   {:family family :expected expected :actual actual})))
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
