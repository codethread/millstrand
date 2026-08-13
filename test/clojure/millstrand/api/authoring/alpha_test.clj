(ns millstrand.api.authoring.alpha-test
  "Protocol descriptor and generated registry-authoring family tests."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.authoring.alpha :as authoring]
            [millstrand.test.alpha :as t]))

(s/def ::id keyword?)
(s/def ::payload string?)
(s/def ::widgets
  (s/and (s/keys :req-un [::id ::payload])
         #(= #{:id :payload} (set (keys %)))))
(s/def ::gadgets ::widgets)

(eval
 '(authoring/defauthoring widget [mode name doc & args]
    (let [[use-options value] (if (= 2 (count args)) args [{} (first args)])
          key (keyword name)
          entry (list 'assoc value :id key)]
      {:name name
       :definition (list 'def name doc entry)
       :kind ::widgets
       :key key
       :entry entry
       :use-options use-options})))

(eval
 '(authoring/defauthoring gadget [_mode name doc value]
    (let [key (keyword name)
          entry (list 'assoc value :id key)]
      {:name name
       :definition (list 'def name doc entry)
       :kind ::gadgets
       :key key
       :entry entry
       :use-options {}})))

(eval '(defwidget library-widget "A reusable widget." {:payload "library"}))
(eval '(defwidget another-widget "Another reusable widget." {:payload "another"}))
(eval '(defgadget gadget-declaration "A different family." {:payload "gadget"}))

(def ^:private test-ns (the-ns 'millstrand.api.authoring.alpha-test))

(defn- collect-result [f]
  (t/collect-module-forms :test/authoring
                          'millstrand.api.authoring.alpha-test f))

(defn- contains-symbol? [form symbol]
  (boolean (some #{symbol} (tree-seq coll? seq form))))

(defn- expand-in-test-ns [form]
  (binding [*ns* test-ns]
    (macroexpand-1 form)))

(defn- eval-in-test-ns [form]
  (binding [*ns* test-ns]
    (eval form)))

(defn- exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest public-authoring-specs-are-registered
  (doseq [spec [:millstrand.api.authoring.alpha/declaration
                :millstrand.api.authoring.alpha/registry-use-options
                :millstrand.api.authoring.alpha/builder-bindings
                :millstrand.api.authoring.alpha/expansion-plan
                :millstrand.api.authoring.alpha/selection
                :millstrand.api.authoring.alpha/selected-vars]]
    (is (some? (s/get-spec spec)) (str spec " is registered"))))

(deftest generated-family-expands-inert-use-and-bang-modes
  (let [inert (expand-in-test-ns
               '(defwidget expansion-inert "Inert." {:payload "inert"}))
        use (expand-in-test-ns '(use-widget! library-widget))
        bang (expand-in-test-ns
              '(defwidget! expansion-bang "Bang." {:payload "bang"}))]
    (is (contains-symbol? inert
                          'millstrand.api.authoring.alpha/install-declaration!))
    (is (not (contains-symbol? inert
                               'millstrand.api.authoring.alpha/select-registry!)))
    (is (contains-symbol? use
                          'millstrand.api.authoring.alpha/select-registry!))
    (is (not (contains-symbol? use 'def)))
    (is (contains-symbol? bang
                          'millstrand.api.authoring.alpha/install-declaration!))
    (is (contains-symbol? bang
                          'millstrand.api.authoring.alpha/select-registry!))))

(deftest inert-use-and-bang-forms-return-their-promised-vars
  (testing "an inert definition installs a descriptor and collects nothing"
    (let [{:keys [return contribution]}
          (collect-result
           #(eval-in-test-ns '(defwidget inert-return "Inert return."
                                {:payload "inert"})))
          descriptor (:millstrand.api.authoring.alpha/declaration (meta return))]
      (is (var? return))
      (is (= (ns-resolve test-ns 'inert-return) return))
      (is (= {:id :inert-return :payload "inert"} @return))
      (is (empty? contribution))
      (is (= {:protocol 1
              :family ::widget
              :channel :registry
              :kind ::widgets
              :key :inert-return
              :entry {:id :inert-return :payload "inert"}
              :var 'millstrand.api.authoring.alpha-test/inert-return}
             descriptor))
      (is (s/valid? ::authoring/declaration descriptor))))

  (testing "standalone use returns selected Vars in source order"
    (let [{:keys [return contribution]}
          (collect-result
           #(eval-in-test-ns '(use-widget! library-widget another-widget)))]
      (is (= [(ns-resolve test-ns 'library-widget)
              (ns-resolve test-ns 'another-widget)]
             return))
      (is (s/valid? ::authoring/selected-vars return))
      (is (= {:id :library-widget :payload "library"}
             (get-in contribution [::widgets :entries :library-widget])))
      (is (= {:id :another-widget :payload "another"}
             (get-in contribution [::widgets :entries :another-widget])))))

  (testing "bang definition selects once but returns the installed Var"
    (let [{:keys [return contribution]}
          (collect-result
           #(eval-in-test-ns '(defwidget! bang-return "Bang return."
                                {:override? true}
                                {:payload "bang"})))]
      (is (= (ns-resolve test-ns 'bang-return) return))
      (is (= {:id :bang-return :payload "bang"} @return))
      (is (= {:id :bang-return :payload "bang"}
             (get-in contribution [::widgets :entries :bang-return])))
      (is (= #{:bang-return}
             (get-in contribution [::widgets :overrides]))))))

(deftest invalid-definition-data-fails-before-replacing-an-existing-var
  (eval-in-test-ns
   '(defwidget preserved-widget "Original." {:payload "original"}))
  (let [target (ns-resolve test-ns 'preserved-widget)
        original-root @target
        original-descriptor
        (:millstrand.api.authoring.alpha/declaration (meta target))
        error (exception
               #(eval-in-test-ns
                 '(defwidget preserved-widget "Invalid." {:payload 42})))]
    (is (= :invalid-kind-entry (:reason (ex-data error))))
    (is (= ::widgets (:kind (ex-data error))))
    (is (= original-root @target))
    (is (= original-descriptor
           (:millstrand.api.authoring.alpha/declaration
            (meta target))))))

(deftest typed-selection-rejects-the-wrong-family-with-actionable-data
  (let [error (exception
               #(eval-in-test-ns '(use-widget! gadget-declaration)))]
    (is (= :wrong-family (:reason (ex-data error))))
    (is (= 'gadget-declaration (:symbol (ex-data error))))
    (is (= ::widget (:expected-family (ex-data error))))
    (is (= ::gadget (:family (ex-data error))))))

(deftest malformed-and-stale-descriptors-fail-at-selection
  (doseq [[label mutate expected-reason expected-data]
          [["stale protocol" #(assoc % :protocol 0) :protocol-mismatch
            {:expected-protocol 1 :expected-channel :registry}]
           ["wrong channel" #(assoc % :channel :lifecycle) :wrong-channel
            {:expected-channel :registry :channel :lifecycle}]
           ["open descriptor" #(assoc % :unknown true) :invalid-declaration
            {:expected-protocol 1 :expected-channel :registry}]
           ["invalid kind entry" #(assoc % :entry {:id :library-widget
                                                   :payload 42})
            :invalid-kind-entry {:kind ::widgets :entry-spec ::widgets}]]]
    (testing label
      (let [target (ns-resolve test-ns 'library-widget)
            original (:millstrand.api.authoring.alpha/declaration (meta target))]
        (try
          (alter-meta! target assoc :millstrand.api.authoring.alpha/declaration
                       (mutate original))
          (let [error (exception
                       #(eval-in-test-ns '(use-widget! library-widget)))]
            (is (= expected-reason (:reason (ex-data error))))
            (is (= expected-data
                   (select-keys (ex-data error) (keys expected-data)))))
          (finally
            (alter-meta! target assoc
                         :millstrand.api.authoring.alpha/declaration
                         original)))))))

(deftest duplicate-selection-fails-before-collecting-any-entry
  (let [target (ns-resolve test-ns 'another-widget)
        original (:millstrand.api.authoring.alpha/declaration (meta target))]
    (try
      (alter-meta! target assoc :millstrand.api.authoring.alpha/declaration
                   (assoc original :key :library-widget))
      (let [{:keys [return contribution]}
            (collect-result
             #(exception (fn []
                           (eval-in-test-ns
                            '(use-widget! library-widget another-widget)))))]
        (is (= :duplicate-selection (:reason (ex-data return))))
        (is (= [[::widgets :library-widget]]
               (:duplicates (ex-data return))))
        (is (empty? contribution)))
      (finally
        (alter-meta! target assoc :millstrand.api.authoring.alpha/declaration
                     original)))))

(deftest generated-grammar-rejections-are-structured
  (testing "use forms accept symbols, not arbitrary expressions"
    (let [error (try
                  (expand-in-test-ns
                   '(use-widget! (identity library-widget)))
                  nil
                  (catch clojure.lang.Compiler$CompilerException throwable
                    (ex-cause throwable)))]
      (is (= :invalid-use-grammar (:reason (ex-data error))))
      (is (= '(identity library-widget) (:value (ex-data error))))
      (is (string? (:grammar (ex-data error))))))

  (testing "definition-only forms reject selection options before definition"
    (let [error (try
                  (eval-in-test-ns
                   '(defwidget options-on-inert "No options."
                      {:override? true}
                      {:payload "invalid"}))
                  nil
                  (catch clojure.lang.Compiler$CompilerException throwable
                    (ex-cause throwable)))]
      (is (= :selection-options-on-definition (:reason (ex-data error))))
      (is (nil? (ns-resolve test-ns 'options-on-inert)))))

  (testing "registry options are closed and identify their allowed keys"
    (let [error (exception
                 #(eval-in-test-ns
                   '(use-widget! {:unknown true} library-widget)))]
      (is (= :invalid-registry-use-options (:reason (ex-data error))))
      (is (= #{:override?} (:allowed-option-keys (ex-data error))))
      (is (= '(use-widget! {:unknown true} library-widget)
             (:form (ex-data error))))))

  (testing "the plan must define its exact declared name"
    (let [error (try
                  (authoring/expand-definition
                   :define ::bad-family (ns-name test-ns) '(defbad expected)
                   {:name 'expected
                    :definition '(def other 1)
                    :kind ::widgets
                    :key :expected
                    :entry {:id :expected :payload "x"}
                    :use-options {}})
                  nil
                  (catch clojure.lang.ExceptionInfo throwable throwable))]
      (is (= :definition-name-mismatch (:reason (ex-data error))))
      (is (= 'expected (:expected-name (ex-data error))))
      (is (= 'other (:defined-name (ex-data error)))))))
