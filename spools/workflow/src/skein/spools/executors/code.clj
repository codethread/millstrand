(ns skein.spools.executors.code
  "Fulfil workflow `:code` gates by invoking trusted Clojure functions.

  The code executor resolves a gate's fully qualified `code/fn` through the
  runtime spool classloader, invokes it with the poured `code/params` map on a
  bounded worker pool, and owns the gate's terminal transition. Successful
  non-nil returns are recorded as `code/result`; exceptions and timeouts stamp
  `gate/error`. Claim tokens prevent an abandoned invocation from publishing a
  late result."
  (:require [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [attr-get fail!]]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow])
  (:import [java.util UUID]
           [java.util.concurrent CountDownLatch ExecutorService Executors
            RejectedExecutionException ScheduledExecutorService ScheduledFuture
            SynchronousQueue ThreadFactory ThreadPoolExecutor
            ThreadPoolExecutor$AbortPolicy TimeUnit]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private pool-size
  "Maximum number of code-gate invocations that may occupy worker threads."
  8)

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous code-executor threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version
  "Shape version for the code executor's runtime spool-state map."
  1)

(defn- daemon-thread-factory ^ThreadFactory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- await-stop! [^ExecutorService executor detail]
  (.shutdownNow executor)
  (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
    (fail! detail {})))

(defn- new-state []
  (let [workers (ThreadPoolExecutor. pool-size pool-size
                                     0 TimeUnit/MILLISECONDS
                                     (SynchronousQueue.)
                                     (daemon-thread-factory "code-worker")
                                     (ThreadPoolExecutor$AbortPolicy.))
        timeouts (Executors/newSingleThreadScheduledExecutor
                  (daemon-thread-factory "code-timeout"))]
    {:scan-monitor (Object.)
     :worker-executor workers
     :timeout-executor timeouts
     :close-fn (fn []
                 (await-stop! workers "Code executor worker pool did not stop")
                 (await-stop! timeouts "Code executor timeout scheduler did not stop"))}))

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor []
  (:scan-monitor (state)))

(defn- worker-executor ^ThreadPoolExecutor []
  (or (:worker-executor (state))
      (fail! "Code executor worker pool is missing from spool state" {})))

(defn- timeout-executor ^ScheduledExecutorService []
  (or (:timeout-executor (state))
      (fail! "Code executor timeout scheduler is missing from spool state" {})))

(defn- attr [strand k]
  (attr-get strand k))

(defn- stamped? [gate k]
  (some? (attr gate k)))

(defn- stamp! [id attributes]
  (weaver/update! (rt) id {:attributes attributes}))

(defn- parse-fn-symbol [gate]
  (let [value (attr gate :code/fn)
        sym (when (string? value) (symbol value))]
    (when-not (qualified-symbol? sym)
      (fail! "code/fn must be a fully qualified symbol"
             {:gate (:id gate) :value value}))
    sym))

(defn- parse-params [gate]
  (let [value (attr gate :code/params)]
    (when-not (map? value)
      (fail! "code/params must be a JSON object"
             {:gate (:id gate) :value value}))
    value))

(defn- parse-timeout [gate]
  (let [value (attr gate :code/timeout-secs)]
    (cond
      (nil? value) nil
      (and (integer? value) (pos? value)) (long value)
      :else (fail! "code/timeout-secs must be a positive integer"
                   {:gate (:id gate) :value value}))))

(defn- resolve-callable [sym]
  (let [resolved (runtime/resolve-var (rt) sym)
        value (when resolved (var-get resolved))]
    (when-not (fn? value)
      (fail! "code/fn did not resolve to a function" {:symbol sym}))
    value))

(defn- error-detail [throwable]
  (str (or (ex-message throwable) (.getName (class throwable)))
       (some->> (ex-data throwable) (str " "))))

(defn- live-claim?
  [gate-id token]
  (let [gate (weaver/show (rt) gate-id)]
    (and (= "active" (:state gate))
         (= token (attr gate :code/running)))))

(defn- with-live-claim!
  "Run `f` only while `token` is still the gate's live claim.

  Terminal paths share the scan monitor so the claim check and write are one
  executor-local critical section. A timeout and a normal completion therefore
  cannot both observe and publish the same token."
  [gate-id token f]
  #_{:splint/disable [lint/locking-object]}
  (locking (scan-monitor)
    (when (live-claim? gate-id token)
      (f))))

(defn- pass! [run-id gate-id token result]
  (with-live-claim!
    gate-id token
    #(workflow/complete!
      run-id
      {:step gate-id
       :by "code"
       :attributes (cond-> {"code/running" nil}
                     (some? result) (assoc "code/result" result))})))

(defn- fail-gate! [gate-id token detail]
  (with-live-claim!
    gate-id token
    #(stamp! gate-id {"code/running" nil "gate/error" detail})))

(defn- timeout! [gate-id token ^Thread worker timeout-secs]
  (with-live-claim!
    gate-id token
    #(do
       (.interrupt worker)
       (stamp! gate-id
               {"code/running" nil
                "gate/error" (str "code function timed out after " timeout-secs "s")}))))

(defn- schedule-timeout
  ^ScheduledFuture [gate-id token worker timeout-secs]
  (when timeout-secs
    (let [runtime (rt)]
      (.schedule (timeout-executor)
                 ^Runnable #(current/with-runtime runtime
                              (binding [*runtime* runtime]
                                (timeout! gate-id token worker timeout-secs)))
                 (long timeout-secs)
                 TimeUnit/SECONDS))))

(defn- run-gate!
  "Invoke one claimed code gate and publish only a token-guarded outcome."
  [run-id gate-id token]
  (let [timeout-task (atom nil)]
    (try
      (when (live-claim? gate-id token)
        (let [gate (weaver/show (rt) gate-id)
              fn-symbol (parse-fn-symbol gate)
              params (parse-params gate)
              timeout-secs (parse-timeout gate)
              callable (resolve-callable fn-symbol)]
          (reset! timeout-task
                  (schedule-timeout gate-id token (Thread/currentThread) timeout-secs))
          (pass! run-id gate-id token (callable params))))
      (catch Throwable throwable
        (fail-gate! gate-id token (error-detail throwable)))
      (finally
        (when-let [^ScheduledFuture task @timeout-task]
          (.cancel task false))))))

(defn- claim-and-dispatch!
  "Offer one gate to the zero-queue pool, then stamp and release its task.

  Rejection means saturation: the gate stays ready and unclaimed for a later
  scan. The start latch prevents the accepted task from running before its
  unique claim token is durable."
  [runtime run-id gate-view]
  (let [gate (weaver/show (rt) (:id gate-view))]
    (when (and (= "active" (:state gate))
               (not (stamped? gate :gate/error))
               (not (stamped? gate :code/running)))
      (let [token (str (UUID/randomUUID))
            start (CountDownLatch. 1)
            task (fn []
                   (.await start)
                   (current/with-runtime runtime
                     (binding [*runtime* runtime]
                       (run-gate! run-id (:id gate) token))))]
        (try
          (.execute (worker-executor) ^Runnable task)
          (try
            (stamp! (:id gate) {"code/running" token})
            (finally
              (.countDown start)))
          (catch RejectedExecutionException _
            nil))))))

(defn scan!
  "Dispatch every ready `:code` gate not already claimed or errored."
  []
  (let [runtime (rt)]
    (binding [*runtime* runtime]
      #_{:splint/disable [lint/locking-object]}
      (locking (scan-monitor)
        (doseq [root (workflow/active-runs)
                :let [run-id (attr root :workflow/run-id)]
                step (workflow/ready run-id)
                :when (= "code" (:gate step))]
          (claim-and-dispatch! runtime run-id step))
        {:scanned true}))))

(defn on-event
  "Scan for newly ready code gates after a graph mutation."
  [_event]
  (scan!))

(defn gate-stalled?
  "Return durable stall detail for a ready `:code` gate view, or nil."
  [gate-view]
  (let [gate (weaver/show (rt) (:id gate-view))]
    (when (stamped? gate :gate/error)
      {:gate (:id gate) :error (attr gate :gate/error)})))

(def gate-stalled-symbol
  "The `:code` executor's stall predicate symbol."
  'skein.spools.executors.code/gate-stalled?)

(def stalled-code-gates-query
  "Named query behind `stalled-code-gates`."
  [:and [:= :state "active"]
   [:= [:attr "workflow/gate"] "code"]
   [:exists [:attr "gate/error"]]])

(defn- declare-code-vocab! [runtime]
  (vocab/declare! runtime
                  {:kind :attr-namespace
                   :name "code"
                   :owner :skein/spools-code
                   :keys ["code/fn" "code/params" "code/timeout-secs"
                          "code/running" "code/result"]
                   :doc "Code-gate function inputs, claim token, and result stamped by the code executor."}))

(defn- register-code-handler! [runtime]
  (events/register-handler! runtime :code/engine event-types
                            'skein.spools.executors.code/on-event
                            {:spool "code"}))

(defn contribute
  "Contribute the `:code` workflow executor and its stalled-gates query."
  [_ctx]
  {workflow/executor-kind {"code" gate-stalled-symbol}
   :queries {"stalled-code-gates" stalled-code-gates-query}})

(defn reconcile
  "Reconcile the code executor's vocabulary, handler, and runtime-owned pools."
  [{:keys [runtime] :as ctx}]
  (binding [*runtime* runtime]
    (let [status (get-in ctx [:module/contribution :status])]
      (case status
        :applied (do
                   (declare-code-vocab! runtime)
                   (register-code-handler! runtime)
                   (state)
                   (scan!)
                   {:reconciled :applied})
        :removed (do
                   (events/unregister-handler! runtime :code/engine)
                   {:reconciled :removed})
        (fail! "Unsupported module contribution status"
               {:status status
                :allowed #{:applied :removed}
                :module/key (:module/key ctx)
                :reconciler 'skein.spools.executors.code/reconcile})))))

(def spool
  "Entry-point declaration for the code executor module."
  {:contribute 'contribute
   :reconcile 'reconcile})
