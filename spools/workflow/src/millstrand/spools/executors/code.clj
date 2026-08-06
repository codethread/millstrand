(ns millstrand.spools.executors.code
  "Fulfil workflow `:code` gates by invoking trusted Clojure functions.

  The code executor resolves a gate's fully qualified `code/fn` through the
  runtime spool classloader, invokes it with the poured `code/params` map on a
  bounded worker pool, and owns the gate's terminal transition. Successful
  non-nil returns are recorded as `code/result`; exceptions and timeouts stamp
  `gate/error`. Claim tokens prevent an abandoned invocation from publishing a
  late result."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.vocab.alpha :as vocab]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.workflow :as workflow])
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

(def ^:private ^:dynamic *runtime*
  "Runtime captured for asynchronous code-executor threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version
  "Shape version for the code executor's runtime spool-state map."
  2)

(defn- qualified-symbol-string?
  "String spelling a fully qualified symbol."
  [value]
  (and (string? value) (qualified-symbol? (symbol value))))

(defn- json-safe?
  "JSON-image value: nil, boolean, number, string, or a composition of them."
  [value]
  (or (nil? value)
      (boolean? value)
      (number? value)
      (string? value)
      (and (map? value)
           (every? #(or (string? %) (keyword? %)) (keys value))
           (every? json-safe? (vals value)))
      (and (sequential? value) (every? json-safe? value))))

(s/def :code/fn qualified-symbol-string?)
(s/def :code/params (s/and map? json-safe?))
(s/def :code/timeout-secs pos-int?)
(s/def ::request
  (s/keys :req [:code/fn :code/params] :opt [:code/timeout-secs]))
(s/def ::result json-safe?)
(s/def ::id string?)
(s/def ::gate-view (s/keys :req-un [::id]))
(s/def ::gate string?)
(s/def ::error any?)
(s/def ::stall-detail (s/nilable (s/keys :req-un [::gate ::error])))

(declare attr stamped? scan! declare-code-vocab! register-code-handler!
         ensure-resources! state)

(defn on-event
  "Scan for newly ready code gates after a graph mutation."
  [_event]
  (scan!))

(workflow/defexecutor code
  "Return durable stall detail for a ready `:code` gate view, or nil."
  {:request-spec ::request}
  [gate-view]
  (require-valid! ::gate-view gate-view "Invalid code gate view")
  (let [gate (weaver/show (rt) (:id gate-view))
        result (when (stamped? gate :gate/error)
                 {:gate (:id gate) :error (attr gate :gate/error)})]
    (require-valid! ::stall-detail result "Invalid code gate stall detail")))

(millstrand/defquery stalled-code-gates
  "Return active code gates carrying a durable error stamp."
  {}
  [:and [:= :state "active"]
   [:= [:attr "workflow/gate"] "code"]
   [:exists [:attr "gate/error"]]])

(s/def ::runtime some?)
(s/def ::resource map?)
(s/def ::open-context (s/keys :req-un [::runtime]))
(s/def ::close-context (s/keys :req-un [::runtime ::resource]))
(s/def ::engine-handle
  #(= #{:scan-monitor :resources :close-fn} (set (keys %))))
(s/def ::closed #{:code/engine})
(s/def ::close-result (s/keys :req-un [::closed]))

(defn open-code-engine!
  "Open the code executor handler and worker resources."
  [ctx]
  (require-valid! ::open-context ctx "Invalid code engine open context")
  (let [runtime (:runtime ctx)
        result (current/with-runtime runtime
                 (binding [*runtime* runtime]
                   (declare-code-vocab! runtime)
                   (register-code-handler! runtime)
                   (ensure-resources!)
                   (scan!)
                   (state)))]
    (require-valid! ::engine-handle result "Invalid code engine handle")))

(defn close-code-engine!
  "Close code executor resources and unregister its event handler."
  [ctx]
  (require-valid! ::close-context ctx "Invalid code engine close context")
  (events/unregister-handler! (:runtime ctx) :code/engine)
  ((:close-fn (:resource ctx)))
  (require-valid! ::close-result {:closed :code/engine}
                  "Invalid code engine close result"))

(lifecycle/defresource code-engine
  "Own the code executor handler and worker resources."
  {:open 'millstrand.spools.executors.code/open-code-engine!
   :close 'millstrand.spools.executors.code/close-code-engine!})

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

(defn- new-resources []
  (let [workers (ThreadPoolExecutor. pool-size pool-size
                                     0 TimeUnit/MILLISECONDS
                                     (SynchronousQueue.)
                                     (daemon-thread-factory "code-worker")
                                     (ThreadPoolExecutor$AbortPolicy.))
        timeouts (Executors/newSingleThreadScheduledExecutor
                  (daemon-thread-factory "code-timeout"))]
    {:worker-executor workers
     :timeout-executor timeouts}))

(defn- close-resources! [{:keys [worker-executor timeout-executor]}]
  (when worker-executor
    (await-stop! worker-executor "Code executor worker pool did not stop"))
  (when timeout-executor
    (await-stop! timeout-executor "Code executor timeout scheduler did not stop")))

(defn- new-state []
  (let [resources (atom nil)]
    {:scan-monitor (Object.)
     :resources resources
     :close-fn #(locking resources
                  (when-let [owned @resources]
                    (reset! resources nil)
                    (close-resources! owned)))}))

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor []
  (:scan-monitor (state)))

(defn- resources []
  (or @(:resources (state))
      (fail! "Code executor resources are not active" {})))

(defn- ensure-resources! []
  (let [resources (:resources (state))]
    (locking resources
      (or @resources
          (reset! resources (new-resources))))))

(defn- worker-executor ^ThreadPoolExecutor []
  (or (:worker-executor (resources))
      (fail! "Code executor worker pool is missing from spool state" {})))

(defn- timeout-executor ^ScheduledExecutorService []
  (or (:timeout-executor (resources))
      (fail! "Code executor timeout scheduler is missing from spool state" {})))

(defn- attr [strand k]
  (attr-get strand k))

(defn- stamped? [gate k]
  (some? (attr gate k)))

(defn- stamp! [id attributes]
  (weaver/update! (rt) id {:attributes attributes}))

(defn- require-request! [gate]
  (let [request (cond-> {:code/fn (attr gate :code/fn)
                         :code/params (attr gate :code/params)}
                  (stamped? gate :code/timeout-secs)
                  (assoc :code/timeout-secs (attr gate :code/timeout-secs)))]
    (when-not (s/valid? ::request request)
      (fail! "code gate request must satisfy code/fn, code/params, and code/timeout-secs"
             {:gate (:id gate) :value request :spec ::request
              :explain (s/explain-str ::request request)}))))

(defn- parse-fn-symbol [gate]
  (let [value (attr gate :code/fn)
        sym (when (string? value) (symbol value))]
    (when-not (s/valid? :code/fn value)
      (fail! "code/fn must be a fully qualified symbol"
             {:gate (:id gate) :value value :spec :code/fn
              :explain (s/explain-str :code/fn value)}))
    sym))

(defn- parse-params [gate]
  (let [value (attr gate :code/params)]
    (when-not (s/valid? :code/params value)
      (fail! "code/params must be a JSON object with JSON-safe values"
             {:gate (:id gate) :value value :spec :code/params
              :explain (s/explain-str :code/params value)}))
    value))

(defn- parse-timeout [gate]
  (let [value (attr gate :code/timeout-secs)]
    (cond
      (nil? value) nil
      (s/valid? :code/timeout-secs value) (long value)
      :else (fail! "code/timeout-secs must be a positive integer"
                   {:gate (:id gate) :value value :spec :code/timeout-secs
                    :explain (s/explain-str :code/timeout-secs value)}))))

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

(declare fail-gate!)

(defn- pass! [run-id gate-id token result]
  (if (s/valid? ::result result)
    (with-live-claim!
      gate-id token
      #(workflow/complete!
        run-id
        {:step gate-id
         :by "code"
         :attributes (cond-> {"code/running" nil}
                       (some? result) (assoc "code/result" result))}))
    (fail-gate! gate-id token
                (str "code result is not JSON-safe: "
                     (s/explain-str ::result result)))))

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
              _ (require-request! gate)
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

(defn- scan!
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

(defn- declare-code-vocab! [runtime]
  (vocab/declare! runtime
                  {:kind :attr-namespace
                   :name "code"
                   :owner :millstrand/spools-code
                   :keys ["code/fn" "code/params" "code/timeout-secs"
                          "code/running" "code/result"]
                   :doc "Code-gate function inputs, claim token, and result stamped by the code executor."}))

(defn- register-code-handler! [runtime]
  (events/register-handler! runtime :code/engine event-types
                            'millstrand.spools.executors.code/on-event
                            {:spool "code"}))
