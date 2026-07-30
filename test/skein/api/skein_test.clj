(ns skein.api.skein-test
  "Production-boundary tests for Skein's core authoring forms."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.skein.alpha :as skein]
            [skein.core.contribution :as contribution]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.spools.chime :as chime]
            [skein.spools.cron :as cron]
            [skein.spools.workflow :as workflow]))

(s/def ::pattern-input (s/keys))

(def ^:private test-ns (the-ns 'skein.api.skein-test))

(def ^:private collection-context
  {:module/key :test/contribution
   :source/file (.getCanonicalPath (java.io.File. *file*))
   :source/namespace (ns-name test-ns)})

(defn- collect [f]
  (binding [*ns* test-ns
            *file* (:source/file collection-context)]
    (:contribution
     (module-graph/with-contribution-collection collection-context f))))

(deftest declaration-constructors-enforce-closed-kind-grammars
  (testing "unknown options fail at the authoring boundary"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defop options are invalid"
                          (contribution/op-declaration
                           'sample "Sample." {:arg-spec {} :unknown true}
                           'skein.api.skein-test/sample-op)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defquery options are invalid"
                          (contribution/query-declaration 'sample {:unknown true} [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defpattern options are invalid"
                          (contribution/pattern-declaration
                           'sample "Sample." {:spec ::pattern-input :unknown true}
                           'skein.api.skein-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhook options are invalid"
                          (contribution/hook-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'skein.api.skein-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhandler options are invalid"
                          (contribution/handler-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'skein.api.skein-test/sample)))))

(deftest generated-declarations-collect-values-and-override-intent
  (let [query [:= :state "active"]
        contribution
        (collect
         #(do
            (module-graph/collect-entry!
             :queries "sample"
             (contribution/query-declaration 'sample {:override? true} query)
             {:override? true})
            (module-graph/collect-entry!
             :patterns "sample"
             (contribution/pattern-declaration
              'sample "Sample." {:spec ::pattern-input}
              'skein.api.skein-test/sample))))]
    (is (= query (get-in contribution [:queries :entries "sample"])))
    (is (= #{"sample"} (get-in contribution [:queries :overrides])))
    (is (= 'skein.api.skein-test/sample
           (get-in contribution [:patterns :entries "sample" :fn])))))

(deftest public-namespace-exposes-only-core-authoring-forms
  (is (= '#{defhandler defhook defop defpattern defquery}
         (set (keys (ns-publics 'skein.api.skein.alpha))))))

(deftest source-forms-define-vars-and-collect-every-core-kind
  (let [contribution
        (collect
         #(eval
           '(do
              (skein/defop sample "Sample."
                {:arg-spec {:op "sample" :doc "Sample."
                            :hook-class :read :deadline-class :standard}
                 :override? true}
                [_] :ok)
              (skein/defquery sample-query "Sample." {}
                [:= :state "active"])
              (skein/defpattern sample-pattern "Sample."
                {:spec ::pattern-input} [_] [])
              (skein/defhook sample-hook "Sample."
                {:types #{:strand/added}} [_] nil)
              (skein/defhandler sample-handler "Sample."
                {:types #{:strand/added}} [_] nil))))]
    (is (= #{:ops :queries :patterns :hooks :events} (set (keys contribution))))
    (is (= #{"sample"} (get-in contribution [:ops :overrides])))
    (is (= 'skein.api.skein-test/sample-op
           (get-in contribution [:ops :entries "sample" :fn])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-op sample-query sample-pattern sample-hook sample-handler])))))

(deftest domain-forms-define-callables-and-collect-override-intent
  (let [contribution
        (collect
         #(eval
           '(do
              (workflow/defexecutor sample-executor "Sample."
                {:override? true} [_] nil)
              (cron/defjob :sample-job {:override? true}
                {:interval-ms 1000
                 :handler 'skein.api.skein-test/sample-handler})
              (chime/defrule sample-rule "Sample."
                {:override? true} [_] nil))))]
    (is (= #{"sample-executor"}
           (get-in contribution [workflow/executor-kind :overrides])))
    (is (= #{:sample-job} (get-in contribution [cron/job-kind :overrides])))
    (is (= #{:sample-rule} (get-in contribution [chime/rule-kind :overrides])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-executor-stalled? sample-rule-rule])))))
