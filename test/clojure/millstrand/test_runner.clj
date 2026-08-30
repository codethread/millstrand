(ns millstrand.test-runner
  "Explicit test entrypoint with serial JVM-global islands, focused named
  namespaces, and per-namespace timing output."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.io StringWriter)
           (java.util.concurrent Callable Executors ExecutorService TimeUnit)))

(def parallel-namespaces
  "Test namespaces that are safe to run concurrently, one namespace per worker."
  ['millstrand.core.db-test 'millstrand.core.query-compile-test 'millstrand.core.contract-props-test 'millstrand.core.specs-test 'millstrand.core.db.scheduler.storage-test
   'millstrand.core.weaver.owner-registry-test
   'millstrand.core.weaver.basis-test
   ;; each test builds its own backing store — no shared state.
   'millstrand.core.weaver.core-registry-test
   ;; each test builds its own registries and unpublished runtimes — no shared state.
   'millstrand.api.registry.alpha-test
   'millstrand.plugin-test 'millstrand.relations-test 'millstrand.notes-test
   'millstrand.cutover.vocab-reset-test
   'millstrand.spools.unsafe-text-search-test
   'millstrand.test.alpha-test 'millstrand.warm-test 'millstrand.api.cli.alpha-test
   'millstrand.source-file-test
   ;; pure findings logic over its own temp-dir fixtures — no shared state.
   'millstrand.quality.conventions-check-test
   'millstrand.quality.kondo-export-test
   'millstrand.api.return-shape.alpha-test
   'millstrand.api.spec.alpha-test
   'millstrand.api.clock.alpha-test
   'millstrand.api.errors.alpha-test
   'millstrand.api.format.alpha-test
   'millstrand.api.authoring.alpha-test
   'millstrand.api.millstrand-test
   'millstrand.api.lifecycle.alpha-test
   'millstrand.core.contribution-test
   'millstrand.core.weaver.lifecycle-forms-test
   'millstrand.hooks-integration-test
   'millstrand.registration-matrix-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'millstrand.api.runtime.glossary.alpha-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'millstrand.api.runtime.help-transform.alpha-test
   'millstrand.api.runtime.alpha-test
   'millstrand.api.graph.alpha-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'millstrand.api.events.alpha-test
   'millstrand.api.hooks.alpha-test
   ;; drives one disposable weaver world with its own module source — no shared state.
   'millstrand.alpha-test 'millstrand.core.client-test
   'millstrand.spools.test-support-test
   'millstrand.spools.batteries-test 'millstrand.api.spool-test
   ;; large-attr load harness structural smoke: boots its own :publish? false
   ;; world and hand-SQL fixtures in temp dirs — no JVM-global or shared state.
   'millstrand.large-attr-benchmark-test
   ;; each test drives its own unpublished runtime, so the event lane it awaits
   ;; is per-runtime with no JVM-global or shared-lane state — parallel-safe.
   'millstrand.events-quiescence-test
   ;; Graduated from the serial island: each drives its own unpublished runtime
   ;; and settles work through deterministic seams — an injected runtime clock
   ;; for scheduler timers and event-lane quiescence for async dispatch — so
   ;; there is no JVM-global timer or shared-lane state.
   'millstrand.core.weaver.scheduler.runtime-test 'millstrand.api.scheduler.alpha-test 'millstrand.integration.scheduler.lifecycle-test
   'millstrand.api.process.alpha-test
   'millstrand.integration.process-custody-test
   ;; isolated pure coordinator prototype; injected callables own all effects.
   'millstrand.lifecycle-spike-test
   ;; production lifecycle transition engine is pure over injected callables.
   'millstrand.core.weaver.lifecycle-effects-test
   ;; Behavior-owned slices of the former weaver megasuite. Each owns its
   ;; disposable world and namespace-local callback state.
   'millstrand.core.weaver.bins-test
   'millstrand.core.weaver.graph-query-test
   'millstrand.core.weaver.hooks-events-test
   'millstrand.core.weaver.ops-help-test
   'millstrand.core.weaver.patterns-test
   'millstrand.core.weaver.socket-test
   ;; Uses a disposable Unix socket and no shared runtime state.
   'millstrand.integration.restart-admission-test])

(def serial-namespaces
  "JVM-global namespaces the parent runs serially."
  [;; Module and config fixtures redefine runtime internals and therefore stay
   ;; on the serial island.
   'millstrand.runtime.integration-test
   ;; ambient REPL connection atoms.
   'millstrand.repl-test
   ;; published singleton semantics.
   'millstrand.weaver-publication-test
   ;; multiple published peer runtimes verify routing semantics.
   'millstrand.peers-test
   ;; globally redefines db transaction seams while checking API guards.
   'millstrand.api.batch.alpha-test
   ;; Core batch-boundary tests redefine storage and dispatch collaborators.
   'millstrand.core.weaver.batch-boundary-test
   ;; DynamicClassLoader and retained registry snapshots stay serial until
   ;; concurrent execution has an explicit proof.
   'millstrand.core.weaver.registry-snapshots-test
   ;; Startup tests park futures across a process-local artifact claim and
   ;; redefine scheduler, storage, and metadata Vars. The claim map and
   ;; `with-redefs` are JVM-global, so this namespace cannot share the
   ;; parallel parent with runtime consumers.
   'millstrand.core.weaver.startup-test
   'millstrand.runtime-deps-test
   'millstrand.core.weaver.modules-test])

(defn- summary-zero [] test/*initial-report-counters*)
(defn- merge-summaries [& summaries] (apply merge-with + (summary-zero) (map #(dissoc % :type) summaries)))
(defn- bounded-pool-size [] (max 1 (min (count parallel-namespaces) (.availableProcessors (Runtime/getRuntime)))))

(defn- require-namespace! [ns-sym]
  (require ns-sym)
  ns-sym)

(defn- run-namespace [group ns-sym]
  (let [out (StringWriter.) started (System/nanoTime)]
    (binding [test/*report-counters* (ref (summary-zero)) test/*test-out* out]
      ;; Require inside the try so a require-time failure is reported as an
      ;; :error in this namespace's summary. run-parallel still pre-requires
      ;; serially to avoid concurrent first-load races.
      (let [summary (try (require-namespace! ns-sym)
                         (test/run-tests ns-sym)
                         (catch Throwable t
                           (test/do-report {:type :error :message (str "Uncaught exception while running " ns-sym) :expected nil :actual t})
                           @test/*report-counters*))]
        {:group group :ns ns-sym :summary summary :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000)) :output (str out)}))))

(defn- run-serial [group namespaces] (mapv #(run-namespace group %) namespaces))
(defn- run-parallel []
  (run! require-namespace! parallel-namespaces)
  (let [^ExecutorService pool (Executors/newFixedThreadPool (bounded-pool-size))]
    (try
      (->> parallel-namespaces (mapv #(.submit pool ^Callable (fn [] (run-namespace :parent/parallel %)))) (mapv #(.get %)))
      (finally (.shutdown pool) (.awaitTermination pool 1 TimeUnit/MINUTES)))))

(defn- print-result! [{:keys [group summary elapsed-ms output] ns-sym :ns}]
  (print output) (when-not (str/ends-with? output "\n") (println))
  (println "Namespace summary:" ns-sym (assoc summary :group group :elapsed-ms elapsed-ms)))

(defn- run-parent []
  (concat (run-serial :parent/serial serial-namespaces) (run-parallel)))

(defn- validate-focused! [namespaces]
  (let [in-process (set (concat serial-namespaces parallel-namespaces))
        duplicates (sort (keep (fn [[ns-sym n]] (when (< 1 n) ns-sym)) (frequencies namespaces)))]
    (when (seq duplicates)
      (throw (ex-info (str "Duplicate test namespace arguments: " (str/join " " duplicates))
                      {:duplicates (vec duplicates)})))
    (doseq [ns-sym namespaces]
      (when-not (contains? in-process ns-sym)
        (throw (ex-info (str "Unknown test namespace: " ns-sym)
                        {:ns ns-sym :known-namespaces (sort in-process)}))))))

(defn- run-focused-core
  "Non-exiting focused core shared by the cold -main wrapper and the warm REPL
  entry: validate the requested namespaces, run them in-process, print results,
  and return the aggregate summary."
  [namespaces]
  (validate-focused! namespaces)
  ;; Serial-island members first, then parallel members, each in declaration
  ;; order, all in-process on this thread. Focused runs skip the parallel pool.
  (let [requested (set namespaces)
        results (concat (run-serial :focused/serial (filter requested serial-namespaces))
                        (run-serial :focused/parallel (filter requested parallel-namespaces)))
        summary (apply merge-summaries (map :summary results))]
    (doseq [result results] (print-result! result))
    (println "Aggregate summary:" summary)
    (flush)
    summary))

(defn- run-focused [namespaces]
  (let [summary (run-focused-core namespaces)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))

(defn- parse-args [args]
  (case (first args)
    nil {:mode :parent}
    (if (some #(str/starts-with? % "--") args)
      (throw (ex-info "Unknown test-runner arguments" {:args args}))
      {:mode :focused :namespaces (mapv symbol args)})))

(defn -main [& args]
  (let [{:keys [mode namespaces]} (parse-args args)]
    (case mode
      :focused (run-focused namespaces)
      :parent (let [parent-results (run-parent)
                    summary (apply merge-summaries (map :summary parent-results))]
                ;; Print everything gathered before deciding the exit code:
                ;; failures are exactly when this output is needed for triage.
                (doseq [result parent-results] (print-result! result))
                (println "\nNamespace timings (ms):"
                         (into {} (map (juxt :ns #(select-keys % [:group :elapsed-ms]))) parent-results))
                (println "Aggregate summary:" summary)
                (flush)
                (System/exit (if (pos? (+ (:fail summary) (:error summary)))
                               1
                               0))))))
