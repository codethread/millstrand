(ns millstrand.core.specs-test
  "Tests for shared Millstrand specs that define boundary data contracts."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.specs :as specs]))

(deftest attribute-archive-result-spec-pins-archive-shape
  (is (s/valid? ::specs/attribute-archive-result
                {:strand-id "abc123" :archived? true :changed 2 :keys ["owner" "note"]}))
  (is (s/valid? ::specs/attribute-archive-result
                {:strand-id "abc123" :archived? false :changed 0 :keys []}))
  (doseq [result [{:strand-id "abc123" :archived? "true" :changed 2 :keys ["owner"]}
                  {:strand-id "abc123" :archived? true :changed -1 :keys ["owner"]}
                  {:strand-id "abc123" :archived? true :changed 2 :keys [:owner]}
                  {:strand-id "abc123" :archived? true :changed 2}]]
    (is (not (s/valid? ::specs/attribute-archive-result result)) (pr-str result))))

(deftest omitted-attribute-descriptor-discriminates-typed-descriptor
  (testing "the descriptor shape conforms"
    (is (specs/omitted-attribute-descriptor? {:millstrand/omitted true :bytes 1025})))
  (testing "plain attribute values never conform as descriptors"
    (doseq [value ["large string" 42 true false nil ["x"] {:bytes 1025} {:millstrand/omitted false :bytes 1025}]]
      (is (not (specs/omitted-attribute-descriptor? value)) (pr-str value)))))

(deftest archive-result-validator-fails-on-invalid-result-shape
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Attribute archive result is invalid"
                        (#'weaver/require-archive-result!
                         {:strand-id "abc123" :archived? "true" :changed 1 :keys ["owner"]}))))

(deftest transform-hook-return-spec-pins-wrapper-shape
  (is (s/valid? ::specs/hook-transform-return {:hook/value {}}))
  (is (not (s/valid? ::specs/hook-transform-return nil)))
  (is (not (s/valid? ::specs/hook-transform-return {:value {}}))))

(deftest restart-and-admission-contracts-are-closed
  (is (s/valid? :millstrand.core.specs/restart-result
                {:operation :restart
                 :workspace "/tmp/world"
                 :state :running
                 :generation-id "generation-1"}))
  (is (s/valid? :millstrand.core.specs/restart-result
                {:operation :restart
                 :workspace "/tmp/world"
                 :state :failed
                 :transition-id "transition-1"
                 :diagnostics [{:stage "probe"
                                :status :failed}]}))
  (is (not (s/valid? :millstrand.core.specs/restart-result
                     {:operation :restart
                      :workspace "/tmp/world"
                      :state :running
                      :generation-id "generation-1"
                      :unknown true})))
  (is (s/valid? :millstrand.core.specs/admission-state
                {:state :open :generation-id "generation-1"}))
  (is (s/valid? :millstrand.core.specs/admission-state
                {:state :closed :transition-id "transition-1"})))

(deftest probe-diagnostics-have-one-closed-shape
  (is (s/valid? :millstrand.restart/probe-diagnostic
                {:stage "probe/workspace"
                 :status :completed
                 :data {:workspace "/tmp/probe"}
                 :at "2026-08-24T00:00:00Z"}))
  (doseq [diagnostic [{:stage " " :status :completed}
                      {:stage "probe/workspace" :status :unknown}
                      {:stage "probe/workspace" :status :completed :extra true}
                      {:stage "probe/workspace" :status :completed :at " "}]]
    (is (not (s/valid? :millstrand.restart/probe-diagnostic diagnostic))
        (pr-str diagnostic))))

(deftest mill-protocol-envelopes-reject-unknown-wire-fields
  (is (s/valid? :millstrand.core.mill-protocol/request
                {"protocol_version" 1
                 "request_id" "request-1"
                 "weaver_id" "weaver-1"
                 "operation" "process.get"
                 "arguments" {"handle" "handle-1"}}))
  (is (s/valid? :millstrand.core.mill-protocol/response
                {"protocol_version" 1
                 "request_id" "request-1"
                 "ok" true
                 "result" {}
                 "error" nil}))
  (is (not (s/valid? :millstrand.core.mill-protocol/request
                     {"protocol_version" 1
                      "request_id" "request-1"
                      "weaver_id" "weaver-1"
                      "operation" "process.get"
                      "arguments" {}
                      "options" {}}))))

(deftest successful-wire-results-use-an-explicit-json-shape
  (is (s/valid? :millstrand.core.specs/json-safe-value
                {"items" [1 true nil]}))
  (doseq [value [{:items #{:unordered}}
                 {1 "numeric key"}
                 {:items (Object.)}]]
    (is (not (s/valid? :millstrand.core.specs/json-safe-value value))
        (pr-str value))))
