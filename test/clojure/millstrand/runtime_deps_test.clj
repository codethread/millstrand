(ns millstrand.runtime-deps-test
  "Full-suite checks for the runtime dependency-basis boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.core.weaver.basis :as basis]
            [millstrand.core.weaver.runtime :as runtime]))

(def ^:private running-fingerprint
  (str "sha256:" (str/join (repeat 64 "a"))))

(def ^:private candidate-fingerprint
  (str "sha256:" (str/join (repeat 64 "b"))))

(defn- runtime-map []
  {:source-config-dir "/tmp/workspace"
   :generation-basis
   {:fingerprint running-fingerprint
    :reserved-deps
    {'io.millstrand/millstrand {:local/root "/tmp/millstrand"}}}})

(deftest dependency-change-is-a-restart-boundary
  (with-redefs [basis/create-generation-basis
                (fn [_workspace _coordinate]
                  {:fingerprint candidate-fingerprint})]
    (is (= {:status :restart-required
            :reason :dependency-basis-changed
            :basis {:running-fingerprint running-fingerprint
                    :candidate-fingerprint candidate-fingerprint}}
           (runtime/refresh-modules! (runtime-map))))))

(deftest invalid-dependency-data-is-returned-exactly
  (let [diagnostic {:status :invalid-dependency-config
                    :stage :deps-read
                    :source-path "/tmp/workspace/deps.edn"
                    :message "cannot read dependency source"
                    :cause "malformed EDN"
                    :coordinate nil}]
    (with-redefs [basis/create-generation-basis
                  (fn [_workspace _coordinate]
                    (throw (ex-info "invalid" diagnostic)))]
      (is (= diagnostic (runtime/refresh-modules! (runtime-map)))))))
