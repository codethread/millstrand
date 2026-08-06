(ns quality.millstrand-transition
  "Validate the temporary Millstrand publisher transition contract.

  The contract is deliberately narrow: it names the exact external family pins
  and the only workspace test namespace allowed to defer while those publishers
  still require the old core identity."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private contract-resource "quality/millstrand-transition-contract.edn")
(def ^:private expected-scopes
  #{:workspace-config-integration :pinned-external-spool-suite})
(def ^:private expected-families
  #{'codethread/devflow 'codethread/kanban 'ct.spools/agent-run})
(def ^:private expected-test-namespaces
  #{'millstrand.ct.config-test 'millstrand.ct.config-ops-test})

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
  (require! (= :core-and-in-tree-first (:phase value))
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
                     (string? (:git/tag pin))
                     (valid-sha? (:git/sha pin)))
                "Transition contract contains a malformed family pin"
                {:family family :pin pin}))
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
      (require! (= families expected-families)
                "Every deferred scope must name every exact incompatible family pin"
                {:scope scope :families families})
      (require! (= test-namespaces
                   (if (= :workspace-config-integration scope)
                     expected-test-namespaces
                     #{}))
                "Transition deferral has an unexpected test namespace"
                {:scope scope :test-namespaces test-namespaces}))
    value))

(defn- approved-pins
  [spools-file]
  (let [data (edn/read-string (slurp spools-file))]
    (require! (map? data) "Approved spool data must be a map" {:file spools-file})
    (:spools data)))

(defn validate-current!
  "Validate the checked-in transition contract against `.skein/spools.edn`."
  ([] (validate-current! ".skein/spools.edn"))
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

(defn- -main*
  [args]
  (let [scope (some->> (partition 2 args)
                       (some (fn [[flag value]] (when (= flag "--scope") (keyword value)))))]
    (validate-current!)
    (if scope
      (println (if (deferred? scope) "DEFERRED" "ACTIVE"))
      (println "millstrand transition contract: clean"))))

(defn -main
  "Validate the transition contract for a cold shell gate."
  [& args]
  (-main* args))
