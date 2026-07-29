(ns skein.test-runner
  "Explicit test entrypoint with documented serial JVM-global islands,
  subprocess shards for add-libs suites, a focused in-process mode for named
  namespaces, and per-namespace timing output."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.io StringWriter)
           (java.util.concurrent Callable Executors ExecutorService TimeUnit)))

(def parallel-namespaces
  "Test namespaces that are safe to run concurrently, one namespace per worker."
  ['skein.core.db-test 'skein.core.query-compile-test 'skein.core.contract-props-test 'skein.core.specs-test 'skein.core.scheduler-test
   'skein.core.weaver.owner-registry-test
   ;; each test builds its own backing store — no shared state.
   'skein.core.weaver.core-registry-test
   ;; each test builds its own registries and unpublished runtimes — no shared state.
   'skein.api.registry.alpha-test
   'skein.plugin-test 'skein.relations-test 'skein.notes-test 'skein.vocab-test
   'skein.spools.unsafe-text-search-test
   'skein.guild-test 'skein.test.alpha-test 'skein.warm-test 'skein.api.cli.alpha-test
   'skein.source-file-test
   ;; pure findings logic over its own temp-dir fixtures — no shared state.
   'skein.quality.conventions-check-test
   'skein.api.return-shape.alpha-test
   'skein.api.spec.alpha-test
   'skein.api.clock.alpha-test
   'skein.api.format.alpha-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'skein.api.runtime.glossary.alpha-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'skein.api.runtime.help-transform.alpha-test
   'skein.api.graph.alpha-test
   ;; drives its own unpublished runtime per test — no JVM-global state.
   'skein.api.events.alpha-test
   'skein.api.hooks.alpha-test
   'skein.alpha-test 'skein.core.client-test 'skein.spools.workflow-test
   ;; each test drives its own disposable runtime and registry — no shared state.
   'skein.spools.workflow-cli-test 'skein.spools.workflow-run-cli-test
   'skein.spools.batteries-test 'skein.api.spool-test 'skein.config-ops-test
   'skein.macros.queries-test 'skein.macros.ops-test 'skein.macros.rules-test 'skein.macros.patterns-test
   ;; large-attr load harness structural smoke: boots its own :publish? false
   ;; world and hand-SQL fixtures in temp dirs — no JVM-global or shared state.
   'skein.large-attr-benchmark-test
   ;; one-shot cutover script (scripts/cutover): each test drives its own
   ;; disposable weaver world and migrates it over a private datasource, so
   ;; there is no JVM-global or shared-world state.
   'skein.cutover.vocab-reset-test
   ;; each test drives its own unpublished runtime, so the event lane it awaits
   ;; is per-runtime with no JVM-global or shared-lane state — parallel-safe.
   'skein.events-quiescence-test
   ;; Graduated from the serial island: each drives its own unpublished runtime
   ;; and settles work through deterministic seams — an injected runtime clock
   ;; for the scheduler and cron timers, event-lane quiescence for the async
   ;; dispatch suites — so there is no JVM-global timer or shared-lane state.
   'skein.scheduler-runtime-test 'skein.api.scheduler.alpha-test 'skein.scheduler-e2e-test
   'skein.cron-test 'skein.cron-e2e-test
   ;; isolated pure coordinator prototype; injected callables own all effects.
   'skein.lifecycle-spike-test
   'skein.spools.executors.shell-test 'skein.spools.executors.code-test
   'skein.chime-test
   'skein.weaver-test])

(def serial-namespaces
  "JVM-global namespaces the parent still runs serially outside add-libs shards."
  [;; Release-marker fixtures redefine source checkout resolution.
   'skein.api.runtime.alpha-test
   ;; source-root fixtures redefine the JVM-global source-checkout locator.
   'skein.source-root-spools-test
   ;; ambient REPL connection atoms.
   'skein.repl-test
   ;; module-local bind! is process-global and loud-failure asserts no published runtime.
   'skein.userland-test
   ;; published singleton semantics.
   'skein.weaver-publication-test
   ;; multiple published peer runtimes verify routing semantics.
   'skein.peers-test
   ;; globally redefines db transaction seams while checking API guards.
   'skein.api.batch.alpha-test])

(def add-libs-shards
  "Subprocess JVM shard groups for tests that mutate JVM-global tools.deps state."
  {;; Largest add-libs suite stands alone to balance wall time against parent work.
   "A" ['skein.spools-test]
   ;; runtime-deps intentionally mutates JVM-global tools.deps state.
   "B" ['skein.runtime-deps-test]
   ;; Medium add-libs suites share one JVM to amortize boot without exceeding shard A.
   ;; nvd-scan-test load-files .skein/nvd_scan.clj (which requires the cron
   ;; spool root via add-libs) just as config-test does, so it shares this
   ;; shard's boot.
   "C" ['skein.config-test 'skein.nvd-scan-test]})

(def shard-timeout-minutes 5)

(defn- summary-zero [] test/*initial-report-counters*)
(defn- merge-summaries [& summaries] (apply merge-with + (summary-zero) (map #(dissoc % :type) summaries)))
(defn- bounded-pool-size [] (max 1 (min (count parallel-namespaces) (.availableProcessors (Runtime/getRuntime)))))

(defn- require-namespace! [ns-sym]
  (require ns-sym)
  ns-sym)

(defn- run-namespace [group ns-sym]
  (let [out (StringWriter.) started (System/nanoTime)]
    (binding [test/*report-counters* (ref (summary-zero)) test/*test-out* out]
      ;; Require inside the try so a require-time failure (e.g. add-libs/git-dep
      ;; resolution under load) is reported as an :error in this namespace's
      ;; summary instead of propagating out and exiting the shard with no
      ;; summary at all. run-parallel still pre-requires serially to avoid
      ;; concurrent first-load races; that leaves this require a cheap no-op.
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

(defn- java-command [shard-id summary-file]
  (let [java-bin (str (System/getProperty "java.home") java.io.File/separator "bin" java.io.File/separator "java")
        ;; The add-libs shards resolve runtime Maven coordinates (e.g.
        ;; runtime-deps-test's maven-spike spool). A bare `java -cp clojure.main`
        ;; child inherits no deps basis, so add-libs sees no :mvn/repos and can
        ;; only resolve artifacts already warm in ~/.m2 — every download reports
        ;; "could not be resolved (absent)" on a cold cache. Forward the parent's
        ;; basis so the shard resolves coordinates exactly like a real weaver;
        ;; a launch path without one cannot run the shards correctly, so refuse it.
        basis (or (System/getProperty "clojure.basis")
                  (throw (ex-info (str "clojure.basis system property is missing: shard subprocesses need the "
                                       "parent's deps basis for runtime add-libs; launch the suite via the "
                                       "clojure CLI (clojure -M:test)")
                                  {:property "clojure.basis"})))]
    [java-bin "--enable-native-access=ALL-UNNAMED"
     (str "-Dclojure.basis=" basis)
     "-cp" (System/getProperty "java.class.path")
     "clojure.main" "-m" "skein.test-runner" "--shard" shard-id "--summary-file" summary-file]))

(defn- read-shard-summary [shard-id ^java.io.File summary-file]
  (let [content (when (.isFile summary-file) (slurp summary-file))]
    (when (str/blank? content)
      (throw (ex-info "Shard wrote no summary payload"
                      {:shard shard-id :summary-file (str summary-file)})))
    (edn/read-string content)))

(defn- start-shard! [[shard-id _]]
  (let [summary-file (doto (java.io.File/createTempFile (str "skein-shard-" shard-id "-") ".edn")
                       (.deleteOnExit))
        process (-> (ProcessBuilder. ^java.util.List (java-command shard-id (.getAbsolutePath summary-file)))
                    (.redirectErrorStream true)
                    (.start))]
    ;; Drain stdout from spawn: waiting to read until waitFor would block the
    ;; shard once its output exceeds the OS pipe buffer and misreport the
    ;; write stall as a hung shard at the timeout. The machine summary payload
    ;; travels through summary-file (out-of-band of stdout) so background
    ;; non-test thread chatter can never split or corrupt it.
    {:shard shard-id
     :process process
     :summary-file summary-file
     :output-future (future (with-open [reader (io/reader (.getInputStream process))]
                              (slurp reader)))}))

(defn- await-shard!
  "Wait for one shard and return its outcome map; never throws.

  Failure outcomes carry :error with shard attribution so the parent can print
  every shard's output before exiting non-zero."
  [{:keys [shard process output-future summary-file]}]
  (if (.waitFor process shard-timeout-minutes TimeUnit/MINUTES)
    (let [exit (.exitValue process)
          output @output-future
          parsed (try
                   (read-shard-summary shard summary-file)
                   (catch Throwable t
                     {:parse-error (ex-message t)}))]
      (cond
        (:parse-error parsed)
        {:shard shard :output output
         :error {:shard shard :exit exit :reason (:parse-error parsed)}}

        (not (zero? exit))
        (assoc parsed :shard shard :output output
               :error {:shard shard :exit exit :reason "shard subprocess exited non-zero"})

        :else
        (assoc parsed :shard shard :output output)))
    (do (.destroyForcibly process)
        {:shard shard
         :output (deref output-future 5000 "")
         :error {:shard shard :reason (str "timed out after " shard-timeout-minutes " minutes")}})))

(defn- run-shard-subprocesses! []
  (let [started (mapv start-shard! add-libs-shards)]
    (try
      (mapv await-shard! started)
      (finally
        (doseq [{:keys [process ^java.io.File summary-file]} started]
          (when (.isAlive process) (.destroyForcibly process))
          (.delete summary-file))))))

(defn- start-shards-thread! []
  (let [result (promise)
        thread (Thread. (fn []
                          (try
                            (deliver result {:ok (run-shard-subprocesses!)})
                            (catch Throwable t
                              (deliver result {:error t}))))
                        "skein-add-libs-shards")]
    (.start thread)
    result))

(defn- print-shard! [{:keys [shard output summary elapsed-ms error]}]
  (println "\n=== add-libs shard" shard "output ===")
  (print (or output "")) (when-not (str/ends-with? (or output "") "\n") (println))
  (println "=== add-libs shard" shard "summary ==="
           (cond-> (assoc (or summary {}) :shard shard :elapsed-ms elapsed-ms)
             error (assoc :error error))))

(defn- run-parent []
  (concat (run-serial :parent/serial serial-namespaces) (run-parallel)))

(defn- run-shard [shard-id summary-file]
  (when-not summary-file
    (throw (ex-info "Shard mode requires --summary-file" {:shard shard-id})))
  (let [namespaces (get add-libs-shards shard-id)]
    (when-not namespaces
      (throw (ex-info "Unknown add-libs shard" {:shard shard-id :known-shards (sort (keys add-libs-shards))})))
    (let [started (System/nanoTime)
          results (run-serial (keyword "shard" shard-id) namespaces)
          summary (apply merge-summaries (map :summary results))
          payload {:shard shard-id
                   :elapsed-ms (long (/ (- (System/nanoTime) started) 1000000))
                   :summary summary
                   :timings (into {} (map (juxt :ns #(select-keys % [:group :elapsed-ms]))) results)}]
      (doseq [result results] (print-result! result))
      (println "\nNamespace timings (ms):" (:timings payload))
      (println "Aggregate summary:" summary)
      ;; Machine payload goes to the parent-provided sidecar file, never
      ;; stdout: the parent reads it only after waitFor, so the fully-flushed
      ;; spit is immune to stdout interleaving by non-test background threads.
      (spit summary-file (pr-str payload))
      (flush)
      ;; Explicit exit either way: drain/agent pool threads are non-daemon and
      ;; would otherwise hold the shard JVM (and the parent's waitFor) ~60s.
      (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0)))))

(defn- shard-for-ns [ns-sym]
  (some (fn [[shard-id namespaces]] (when (some #{ns-sym} namespaces) shard-id)) add-libs-shards))

(defn- validate-focused! [namespaces]
  (let [in-process (set (concat serial-namespaces parallel-namespaces))
        duplicates (sort (keep (fn [[ns-sym n]] (when (< 1 n) ns-sym)) (frequencies namespaces)))]
    (when (seq duplicates)
      (throw (ex-info (str "Duplicate test namespace arguments: " (str/join " " duplicates))
                      {:duplicates (vec duplicates)})))
    (doseq [ns-sym namespaces]
      (when-let [shard-id (shard-for-ns ns-sym)]
        (throw (ex-info (str ns-sym " is an add-libs shard namespace (shard " shard-id
                             "); shard namespaces require the full suite in v1")
                        {:ns ns-sym :shard shard-id})))
      (when-not (contains? in-process ns-sym)
        ;; Offer only namespaces focused mode accepts; shard members are
        ;; rejected above with their own message, so listing them here would
        ;; steer an operator into a second failure.
        (throw (ex-info (str "Unknown test namespace: " ns-sym)
                        {:ns ns-sym :known-namespaces (sort in-process)}))))))

(defn- run-focused-core
  "Non-exiting focused core shared by the cold -main wrapper and the warm REPL
  entry: validate the requested namespaces, run them in-process, print results,
  and return the aggregate summary."
  [namespaces]
  (validate-focused! namespaces)
  ;; Serial-island members first, then parallel members, each in declaration
  ;; order, all in-process on this thread — focused runs skip both the parallel
  ;; pool and the add-libs subprocess shards.
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
    "--shard" (let [[_ shard & opts] args
                    opt-map (apply hash-map opts)]
                {:mode :shard :shard shard :summary-file (get opt-map "--summary-file")})
    (if (some #(str/starts-with? % "--") args)
      (throw (ex-info "Unknown test-runner arguments" {:args args}))
      {:mode :focused :namespaces (mapv symbol args)})))

(defn -main [& args]
  (let [{:keys [mode shard summary-file namespaces]} (parse-args args)]
    (case mode
      :shard (run-shard shard summary-file)
      :focused (run-focused namespaces)
      :parent (let [shards-result (start-shards-thread!)
                    parent-results (run-parent)
                    shard-outcome @shards-result
                    shard-results (:ok shard-outcome)
                    shard-failures (keep :error shard-results)
                    summary (apply merge-summaries (concat (map :summary parent-results)
                                                           (keep :summary shard-results)))]
                ;; Print everything gathered before deciding the exit code:
                ;; failures are exactly when this output is needed for triage.
                (doseq [result parent-results] (print-result! result))
                (doseq [shard-result shard-results] (print-shard! shard-result))
                (println "\nNamespace timings (ms):"
                         {:parent (into {} (map (juxt :ns #(select-keys % [:group :elapsed-ms]))) parent-results)
                          :shards (into {} (map (juxt :shard #(select-keys % [:elapsed-ms :timings]))) shard-results)})
                (println "Aggregate summary:" summary)
                (when-let [error (:error shard-outcome)]
                  (println "\nadd-libs shard runner crashed before producing outcomes:" (ex-message error))
                  (System/exit 1))
                (doseq [failure shard-failures]
                  (println "add-libs shard failure:" failure))
                (flush)
                ;; Explicit exit either way: the shard drain futures leave
                ;; non-daemon pool threads that would hold the JVM ~60s.
                (System/exit (if (or (seq shard-failures)
                                     (pos? (+ (:fail summary) (:error summary))))
                               1
                               0))))))
