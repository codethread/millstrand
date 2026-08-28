(ns millstrand.core.contribution-test
  "Core authoring and contribution-collection integration tests."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.core.contribution :as contribution]
            [millstrand.core.weaver.module-graph :as module-graph]))

(s/def ::pattern-input (s/keys))

(def ^:private test-ns (the-ns 'millstrand.core.contribution-test))

(def ^:private collection-context
  {:module/key :test/contribution
   :source/file (.getCanonicalPath (java.io.File. *file*))
   :source/namespace (ns-name test-ns)})

(defn- collect [f]
  (binding [*ns* test-ns
            *file* (:source/file collection-context)]
    (:contribution
     (module-graph/with-contribution-collection collection-context f))))

(defn- collect-result [f]
  (binding [*ns* test-ns
            *file* (:source/file collection-context)]
    (module-graph/with-contribution-collection collection-context f)))

(deftest declaration-constructors-enforce-closed-kind-grammars
  (testing "unknown options fail at the authoring boundary"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defop options are invalid"
                          (contribution/op-declaration
                           'sample "Sample." {:arg-spec {} :unknown true}
                           'millstrand.core.contribution-test/sample-op)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defquery options are invalid"
                          (contribution/query-declaration 'sample {:unknown true} [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defpattern options are invalid"
                          (contribution/pattern-declaration
                           'sample "Sample." {:spec ::pattern-input :unknown true}
                           'millstrand.core.contribution-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhook options are invalid"
                          (contribution/hook-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'millstrand.core.contribution-test/sample)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defhandler options are invalid"
                          (contribution/handler-declaration
                           :sample {:types #{:strand/added} :unknown true}
                           'millstrand.core.contribution-test/sample)))
    (let [error (try
                  (contribution/bin-declaration
                   'sample "Sample." {:executable [:spool "bin/x"]}
                   'millstrand.core.contribution-test)
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
              'millstrand.core.contribution-test/sample))))]
    (is (= query (get-in contribution [:queries :entries "sample"])))
    (is (= #{"sample"} (get-in contribution [:queries :overrides])))
    (is (= 'millstrand.core.contribution-test/sample
           (get-in contribution [:patterns :entries "sample" :fn])))))

(deftest inert-and-typed-core-forms-separate-definition-from-selection
  (let [{:keys [return contribution]}
        (collect-result
         #(eval
           '(millstrand/defop inert-op "Inert."
              {:arg-spec {:op "inert-op" :doc "Inert."
                          :hook-class :read :deadline-class :standard}}
              [_] :inert)))]
    (is (var? return))
    (is (= (ns-resolve test-ns 'inert-op) return))
    (is (= :inert (@return {})))
    (is (empty? contribution)))
  (let [{:keys [return contribution]}
        (collect-result #(eval '(millstrand/use-op! inert-op)))]
    (is (= [(ns-resolve test-ns 'inert-op)] return))
    (is (= 'millstrand.core.contribution-test/inert-op
           (get-in contribution [:ops :entries "inert-op" :fn])))))

(deftest core-function-and-value-families-return-their-installed-vars
  (testing "function-backed bang forms return their installed Var"
    (let [{:keys [return contribution]}
          (collect-result
           #(eval
             '(millstrand/defop! bang-op "Bang."
                {:arg-spec {:op "bang-op" :doc "Bang."
                            :hook-class :read :deadline-class :standard}}
                [_] :bang)))]
      (is (= (ns-resolve test-ns 'bang-op) return))
      (is (= :bang (@return {})))
      (is (= 'millstrand.core.contribution-test/bang-op
             (get-in contribution [:ops :entries "bang-op" :fn])))))
  (testing "value-backed forms preserve inert, use, and bang return contracts"
    (let [{:keys [return contribution]}
          (collect-result
           #(eval '(millstrand/defquery inert-query "Inert." {}
                     [:= :state "active"])))]
      (is (= (ns-resolve test-ns 'inert-query) return))
      (is (= [:= :state "active"] @return))
      (is (empty? contribution)))
    (let [{:keys [return contribution]}
          (collect-result #(eval '(millstrand/use-query! inert-query)))]
      (is (= [(ns-resolve test-ns 'inert-query)] return))
      (is (= [:= :state "active"]
             (get-in contribution [:queries :entries "inert-query"]))))
    (let [{:keys [return contribution]}
          (collect-result
           #(eval '(millstrand/defquery! bang-query "Bang." {}
                     [:= :state "closed"])))]
      (is (= (ns-resolve test-ns 'bang-query) return))
      (is (= [:= :state "closed"] @return))
      (is (= [:= :state "closed"]
             (get-in contribution [:queries :entries "bang-query"]))))))

(deftest source-forms-define-vars-and-collect-every-core-kind
  (let [contribution
        (collect
         #(eval
           '(do
              (millstrand/defop! sample "Sample."
                {:arg-spec {:op "sample" :doc "Sample."
                            :hook-class :read :deadline-class :standard}
                 :override? true}
                [_] :ok)
              (millstrand/defquery! sample-query "Sample." {}
                [:= :state "active"])
              (millstrand/defpattern! sample-pattern "Sample."
                {:spec ::pattern-input} [_] [])
              (millstrand/defhook! sample-hook "Sample."
                {:types #{:strand/added}} [_] nil)
              (millstrand/defhandler! sample-handler "Sample."
                {:types #{:strand/added}} [_] nil)
              (millstrand/defbin! sample-bin "Sample executable."
                {:executable "sample-bin" :build ["make" "sample-bin"]}))))]
    (is (= #{:ops :queries :patterns :hooks :events :bins} (set (keys contribution))))
    (is (= [:= :state "active"]
           (get-in contribution [:queries :entries "sample-query"])))
    (is (= #{"sample"} (get-in contribution [:ops :overrides])))
    (is (= 'millstrand.core.contribution-test/sample
           (get-in contribution [:ops :entries "sample" :fn])))
    (is (= {:name "sample-bin"
            :doc "Sample executable."
            :executable "sample-bin"
            :build ["make" "sample-bin"]
            :provenance 'millstrand.core.contribution-test
            :source/file (:source/file collection-context)}
           (get-in contribution [:bins :entries "sample-bin"])))
    (is (every? var?
                (map #(ns-resolve test-ns %)
                     '[sample sample-query sample-pattern sample-hook sample-handler
                       sample-bin])))))

(deftest defbin-rejects-an-unresolvable-declaration-source
  (let [failure (try
                  (binding [*file* "missing/bin_declaration.clj"]
                    (contribution/bin-declaration
                     'sample "Sample." {:executable "sample"} 'test/bins))
                  nil
                  (catch clojure.lang.ExceptionInfo throwable
                    throwable))]
    (is (= :bin/declaration-source-unresolved (-> failure ex-data :reason)))
    (is (= "missing/bin_declaration.clj" (-> failure ex-data :source/file)))
    (is (= [:absolute-file :classloader-resource]
           (-> failure ex-data :accepted)))))

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
                             'millstrand.core.contribution-test)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defbin doc is invalid"
                        (contribution/bin-declaration
                         'sample 42 {:executable "x"}
                         'millstrand.core.contribution-test))))
