(ns millstrand.api.runtime.alpha-test
  "Public result and option-shape pins for the explicit runtime API."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.runtime.alpha :as runtime]))

(deftest spool-state-opts-spec-owns-public-shape
  (is (s/valid? ::runtime/spool-state-opts nil))
  (is (s/valid? ::runtime/spool-state-opts {:version :v2 :migrate-fn identity}))
  (doseq [opts [{:versoin 2}
                {:version nil}
                {:version 1.5}
                {:version 1 :migrate-fn 5}
                {:migrate-fn identity}]]
    (is (not (s/valid? ::runtime/spool-state-opts opts)))))

(deftest basis-result-specs-own-public-shapes
  (let [a (str "sha256:" (apply str (repeat 64 "a")))
        b (str "sha256:" (apply str (repeat 64 "b")))]
    (is (s/valid? ::runtime/basis-fingerprint a))
    (is (s/valid? ::runtime/basis-change
                  {:running-fingerprint a :candidate-fingerprint b}))
    (is (not (s/valid? ::runtime/basis-change
                       {:running-fingerprint a :candidate-fingerprint a})))
    (is (s/valid? ::runtime/refresh-result
                  {:status :restart-required
                   :reason :dependency-basis-changed
                   :basis {:running-fingerprint a
                           :candidate-fingerprint b}}))))

(def ^:private applied-refresh-result
  {:status :applied
   :mode :full
   :modules {:demo {:module/key :demo :status :applied}}
   :residuals []
   :conflicts []
   :remedies []
   :declaration/shadows {}
   :publication/kinds [:queries]})

(deftest live-module-result-specs-own-public-shapes
  (testing "refresh and plan results keep their public envelopes"
    (is (s/valid? ::runtime/refresh-result applied-refresh-result))
    (is (not (s/valid? ::runtime/refresh-result
                       (assoc applied-refresh-result :status :bogus))))
    (let [planned (assoc applied-refresh-result :dry-run? true :caveat "loads recorded")]
      (is (s/valid? ::runtime/plan-result planned))
      (is (not (s/valid? ::runtime/plan-result applied-refresh-result)))
      (is (not (s/valid? ::runtime/plan-result (assoc planned :caveat ""))))))
  (testing "status and reload results reject missing or extra fields"
    (let [status {:basis-fingerprint (str "sha256:" (apply str (repeat 64 "a")))
                  :modules {} :resources {} :loaded-namespaces []
                  :last-refresh nil}
          reload {:lib 'demo/root :status :reloaded
                  :namespaces ['demo.core]}]
      (is (s/valid? ::runtime/status-result status))
      (is (not (s/valid? ::runtime/status-result (dissoc status :last-refresh))))
      (is (s/valid? ::runtime/reload-code-result reload))
      (is (not (s/valid? ::runtime/reload-code-result (dissoc reload :status))))))
  (testing "module declarations keep the public file/image grammar"
    (let [image {:ns 'millstrand.api.runtime.alpha-test :load :image
                 :after [] :required? false}]
      (is (s/valid? ::runtime/module-declaration image))
      (is (not (s/valid? ::runtime/module-declaration (assoc image :load :classpath))))
      (is (not (s/valid? ::runtime/module-declaration (assoc image :extra :unsupported))))
      (is (s/valid? ::runtime/module-opts {:file "modules/demo.clj"}))
      (is (not (s/valid? ::runtime/module-opts {:file ""})))
      (is (not (s/valid? ::runtime/module-opts {:file "/absolute/demo.clj"})))))
  (testing "refresh and collect options are closed"
    (is (s/valid? ::runtime/refresh-opts {}))
    (is (s/valid? ::runtime/refresh-opts {:only [:demo]}))
    (is (not (s/valid? ::runtime/refresh-opts {:only []})))
    (is (not (s/valid? ::runtime/refresh-opts {:only ["demo"]})))
    (is (s/valid? ::runtime/collect-entry-opts {:override? true}))
    (is (not (s/valid? ::runtime/collect-entry-opts {:override? :yes})))))
