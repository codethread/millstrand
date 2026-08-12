(ns millstrand.api.runtime.alpha-test
  "Public result and option-shape pins for the explicit runtime API."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.core.specs :as specs])
  (:import [java.io File]))

(deftest spool-state-opts-spec-owns-public-shape
  (is (s/valid? ::runtime/spool-state-opts nil))
  (is (s/valid? ::runtime/spool-state-opts {:version :v2 :migrate-fn identity}))
  (doseq [opts [{:versoin 2}
                {:version nil}
                {:version 1.5}
                {:version 1 :migrate-fn 5}
                {:migrate-fn identity}]]
    (is (not (s/valid? ::runtime/spool-state-opts opts)))))

(deftest runtime-result-specs-own-public-shapes
  (is (s/valid? ::specs/release-marker-syntax "v0"))
  (is (not (s/valid? ::specs/release-marker-syntax "v01")))
  (is (s/valid? ::specs/release-marker-claim "v12"))
  (is (not (s/valid? ::specs/release-marker-claim "v0")))
  (is (s/valid? ::specs/release-marker-result
                {:marker nil :provenance :none}))
  (is (not (s/valid? ::specs/release-marker-result
                     {:marker "v2" :provenance :none})))
  (is (s/valid? ::specs/config-dir-result "/tmp/config"))
  (is (s/valid? ::specs/spools-file-result (File. "/tmp/config/spools.edn"))))

(def ^:private applied-refresh-result
  {:status :applied
   :mode :full
   :modules {:demo {:module/key :demo :status :applied}}
   :roots {}
   :residuals []
   :conflicts []
   :remedies []
   :declaration/shadows {}
   :publication/kinds [:queries]})

(deftest live-module-result-specs-own-public-shapes
  (testing "refresh and plan results keep their public envelopes"
    (is (s/valid? ::runtime/refresh-result applied-refresh-result))
    (is (s/valid? ::runtime/refresh-result
                  {:status :refused :mode :targeted :modules {} :roots {}
                   :residuals [] :conflicts [{:reason :boom}] :remedies []}))
    (is (not (s/valid? ::runtime/refresh-result
                       (assoc applied-refresh-result :status :bogus))))
    (let [planned (assoc applied-refresh-result :dry-run? true :caveat "loads recorded")]
      (is (s/valid? ::runtime/plan-result planned))
      (is (not (s/valid? ::runtime/plan-result applied-refresh-result)))
      (is (not (s/valid? ::runtime/plan-result (assoc planned :caveat ""))))))
  (testing "status and reload results reject missing or extra fields"
    (let [status {:modules {} :declaration/layers {} :declaration/shadows {}
                  :contributions {} :module/outcomes {} :resource/outcomes {}
                  :root/outcomes {} :loaded {} :pending-generation nil
                  :last-refresh nil}
          reload {:root-lib 'demo/root :root "/tmp/root"
                  :namespaces [{:ns 'demo.core :file "/tmp/root/demo/core.clj"}]
                  :residuals [] :hard-conflicts []}]
      (is (s/valid? ::runtime/status-result status))
      (is (not (s/valid? ::runtime/status-result (dissoc status :last-refresh))))
      (is (s/valid? ::runtime/reload-code-result reload))
      (is (not (s/valid? ::runtime/reload-code-result (dissoc reload :residuals))))))
  (testing "module declarations keep the public file/image grammar"
    (let [image {:ns 'millstrand.api.runtime.alpha-test :load :image
                 :spools [] :after [] :required? false}]
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

(deftest spool-config-write-specs-own-public-shapes
  (let [entry {:git/url "https://example.invalid/demo.git"
               :git/sha (str/join (repeat 40 "a"))
               :git/tag "v1"}]
    (is (s/valid? ::runtime/spool-family 'demo/family))
    (is (s/valid? ::runtime/spool-entry entry))
    (is (s/valid? ::runtime/spool-write-result
                  {:status :inserted :lib 'demo/family :entry entry
                   :file (File. "/tmp/spools.edn")}))
    (is (not (s/valid? ::runtime/spool-entry (assoc entry :git/sha "short"))))
    (is (not (s/valid? ::runtime/spool-write-result
                       {:status :inserted :lib 'demo/family :entry entry})))))
