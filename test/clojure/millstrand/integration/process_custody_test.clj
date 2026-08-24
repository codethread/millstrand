(ns millstrand.integration.process-custody-test
  "Disposable owner-reconciliation fixture for Mill process custody semantics."
  (:require [clojure.test :as t]
            [millstrand.api.process.alpha :as process]
            [millstrand.spools.test-support :as test-support]))

(defn- record [handle key phase]
  (cond-> {:handle handle
           :owner "fixture/owner"
           :key key
           :phase phase
           :output {:stdout_ref "/tmp/custody.stdout"
                    :stderr_ref "/tmp/custody.stderr"}}
    (= phase "terminal") (assoc :exit {:code 0 :signal nil})))

(defn- throws-message? [pattern thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find pattern (ex-message error))))))

(t/deftest guarded-owner-reconciliation-is-resumable-and-idempotent
  (let [workspace (test-support/temp-config-dir {:prefix "millstrand-process-custody-"})
        rows (atom {})
        tombstones (atom #{})
        applied (atom {})
        control (fn [operation arguments]
                  (case operation
                    "process.launch"
                    (let [key (get arguments "key")]
                      (when (contains? @tombstones key)
                        (throw (ex-info "custody key is tombstoned" {:code "process/conflicting-key"})))
                      (if-let [row (get @rows key)]
                        row
                        (let [row (record (str "handle-" key) key "terminal")]
                          (swap! rows assoc key row)
                          row)))
                    "process.list-owned" (vec (vals @rows))
                    "process.get" (let [handle (get arguments "handle")]
                                    (some (fn [[_ row]]
                                            (when (= handle (:handle row)) row))
                                          @rows))
                    "process.acknowledge"
                    (let [handle (get arguments "handle")
                          key (some (fn [[key row]] (when (= handle (:handle row)) key)) @rows)]
                      (swap! rows dissoc key)
                      (swap! tombstones conj key)
                      {:acknowledged true :handle handle})
                    "process.cancel" (get @rows "run-long")))
        runtime {:process-control control}
        replacement-runtime {:process-control control}
        guarded? (and workspace (.isDirectory workspace))]
    (try
      (t/is guarded?)
      (t/testing "a long native claim remains visible across Weaver replacement"
        (let [long-running (record "handle-run-long" "run-long" "running")
              _ (swap! rows assoc "run-long" long-running)]
          (t/is (= :running (:phase (process/get replacement-runtime "handle-run-long"))))
          (t/is (= ["run-long"] (mapv :key (process/list-owned replacement-runtime
                                                               :fixture/owner))))))
      (t/testing "terminal reconciliation applies idempotently, then acknowledges"
        (let [short (process/launch! runtime :fixture/owner "run-short"
                                     {:argv ["true"] :cwd "/tmp" :env {}})]
          (doseq [_ (range 2)]
            (swap! applied update "run-short" (fnil inc 0)))
          (t/is (= :terminal (:phase short)))
          (t/is (= 2 (get @applied "run-short")))
          (process/acknowledge! runtime (:handle short))
          (t/is (not-any? #(= "run-short" (:key %))
                          (process/list-owned runtime :fixture/owner)))
          (t/is (throws-message? #"tombstoned"
                                 #(process/launch! runtime :fixture/owner "run-short"
                                                   {:argv ["true"] :cwd "/tmp" :env {}})))))
      (finally
        (test-support/delete-tree! workspace)))))
