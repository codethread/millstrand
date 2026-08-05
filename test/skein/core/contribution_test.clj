(ns skein.core.contribution-test
  "Core authoring and contribution-collection integration tests."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [skein.api.skein.alpha :as skein]
            [skein.core.contribution :as contribution]
            [skein.core.weaver.module-graph :as module-graph]
            [skein.spools.chime :as chime]
            [skein.spools.cron :as cron]
            [skein.spools.workflow :as workflow]))

(s/def ::pattern-input (s/keys))

(def ^:private test-ns (the-ns 'skein.core.contribution-test))

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
                           'skein.core.contribution-test/sample-op)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defquery options are invalid"
                          (contribution/query-declaration 'sample {:unknown true} [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defpattern options are invalid"
                          (contribution/pattern-declaration
                           'sample "Sample." {:spec ::pattern-input :unknown true}
                           'skein.core.contribution-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhook options are invalid"
                          (contribution/hook-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'skein.core.contribution-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhandler options are invalid"
                          (contribution/handler-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'skein.core.contribution-test/sample)))
    (let [error (try
                  (contribution/bin-declaration
                   'sample "Sample." {:executable [:spool "bin/x"]}
                   'skein.core.contribution-test)
                  nil
                  (catch clojure.lang.ExceptionInfo throwable
                    throwable))]
      (is (instance? clojure.lang.ExceptionInfo error))
      (is (re-find #":spool.*:family.*:root" (ex-message error)))
      (is (= {:anchor :spool :allowed [:family :root]}
             (select-keys (ex-data error) [:anchor :allowed]))))))

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
              'skein.core.contribution-test/sample))))]
    (is (= query (get-in contribution [:queries :entries "sample"])))
    (is (= #{"sample"} (get-in contribution [:queries :overrides])))
    (is (= 'skein.core.contribution-test/sample
           (get-in contribution [:patterns :entries "sample" :fn])))))

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
                {:types #{:strand/added}} [_] nil)
              (skein/defbin sample-bin "Sample executable."
                {:executable "sample-bin" :build ["make" "sample-bin"]}))))]
    (is (= #{:ops :queries :patterns :hooks :events :bins} (set (keys contribution))))
    (is (= [:= :state "active"]
           (get-in contribution [:queries :entries "sample-query"])))
    (is (= #{"sample"} (get-in contribution [:ops :overrides])))
    (is (= 'skein.core.contribution-test/sample-op
           (get-in contribution [:ops :entries "sample" :fn])))
    (is (= {:name "sample-bin"
            :doc "Sample executable."
            :executable "sample-bin"
            :build ["make" "sample-bin"]
            :provenance 'skein.core.contribution-test
            :source/file (:source/file collection-context)}
           (get-in contribution [:bins :entries "sample-bin"])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-op sample-query sample-pattern sample-hook sample-handler
                       sample-bin])))))

(deftest defbin-rejects-the-closed-option-grammar
  (doseq [[label opts expected]
          [["missing executable" {} #"defbin options are invalid"]
           ["unknown option" {:executable "x" :cwd "."} #"defbin options are invalid"]
           ["absolute anchored path" {:executable [:root "/bin/x"]}
            #"defbin options are invalid"]
           ["empty build" {:executable "x" :build []} #"defbin options are invalid"]
           ["blank build argument" {:executable "x" :build [" "]}
            #"defbin options are invalid"]]]
    (testing label
      (is (thrown-with-msg? clojure.lang.ExceptionInfo expected
                            (contribution/bin-declaration
                             'sample "Sample." opts
                             'skein.core.contribution-test)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defbin doc is invalid"
                        (contribution/bin-declaration
                         'sample 42 {:executable "x"}
                         'skein.core.contribution-test))))

(deftest domain-forms-define-callables-and-collect-override-intent
  (let [contribution
        (collect
         #(eval
           '(do
              (workflow/defexecutor sample-executor "Sample."
                {:override? true} [_] nil)
              (cron/defjob :sample-job {:override? true}
                {:interval-ms 1000
                 :handler 'skein.core.contribution-test/sample-handler})
              (chime/defrule sample-rule "Sample."
                {:override? true} [_] nil))))]
    (is (= #{"sample-executor"}
           (get-in contribution [workflow/executor-kind :overrides])))
    (is (= #{:sample-job} (get-in contribution [cron/job-kind :overrides])))
    (is (= #{:sample-rule} (get-in contribution [chime/rule-kind :overrides])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample-executor-stalled? sample-rule-rule])))))
