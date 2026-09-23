(ns me.workflows.handoff
  "Independent workflow handoffs with deterministic launch and acceptance receipts."
  (:require [clojure.data.json :as json]
            [me.workflows.evidence :as evidence]
            [me.workflows.support :as support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn key-for
  "Correlate gates by work identity and phase; ambiguous active uses fail loudly."
  [phase params]
  (json/write-str [phase (select-keys params [:feature :card :branch :worktree :module :review-id :topic :version])]))

(defn- accept-request!
  "Launch or reuse the exact request recorded on the preparation step.

  The source step id determines the child run id. A crash after start but before
  the receipt write reuses that child's initial root, even if it has finished or
  routed. A mismatched request fails rather than silently launching more work.
  Acceptance means the workflow engine accepted custody, not that review or
  landing is finished. The named coordinator retains driving responsibility."
  [gate target]
  (let [rt (current/runtime)
        source (evidence/dependency! gate)
        request (evidence/data (attr-get source :handoff/request))
        {:keys [params owner]} request
        expected (evidence/data (attr-get source :handoff/identity))
        run-id (str "handoff-" (:id source))
        roots #(weaver/list rt [:and [:= [:attr "workflow/run-id"] run-id]
                                [:= [:attr "delivery/source"] (:id source)]] {})]
    (when-not (and (= target (:workflow request)) (map? params)
                   (support/non-blank-string? owner))
      (fail! "Prepare a workflow request with explicit params and receiving owner"
             {:source (:id source) :target target}))
    (when-not (= expected (select-keys params (keys expected)))
      (fail! "Handoff changed the recorded work identity" {:expected expected :params params}))
    (when (and (= "land" target)
               (not (support/non-blank-string? (:authorization request))))
      (fail! "Landing handoff requires a reference to actual user authorization"
             {:source (:id source)}))
    (when (empty? (roots))
      (workflow/start! run-id (keyword target) params
                       {:root-attributes {"delivery/source" (:id source)
                                          "delivery/request" request}}))
    (let [root (evidence/single! (roots) "Handoff root missing or ambiguous")]
      (when-not (= request (evidence/data (attr-get root :delivery/request)))
        (fail! "Existing handoff has a different request" {:run-id run-id}))
      (let [receipt {:status "accepted" :workflow target :run-id run-id
                     :root (:id root) :owner owner :request request}]
        (weaver/update! rt (:id source) {:attributes {:handoff/receipt receipt}})
        receipt))))

(defn launch!
  "Launch/reuse and record acceptance of a prepared independent workflow."
  [{:keys [key target]}]
  (accept-request! (evidence/gate! "me.workflows.handoff/launch!" key) target))

(defn deliver!
  "Accept the selected Land handoff or an acknowledged terminal report."
  [{:keys [key]}]
  (let [gate (evidence/gate! "me.workflows.handoff/deliver!" key)
        source (evidence/dependency! gate)
        decision (evidence/dependency! source)
        choice (attr-get decision :workflow/outcome)]
    (case choice
      "land" (accept-request! gate "land")
      "report" (let [receipt (evidence/data (attr-get source :handoff/report))]
                 (when-not (every? support/non-blank-string?
                                   ((juxt :owner :evidence :head) receipt))
                   (fail! "Terminal report needs acknowledged custody and exact HEAD"
                          {:source (:id source)}))
                 (assoc receipt :status "reported"))
      (fail! "Unknown delivery outcome" {:choice choice}))))

(defn prepare
  "Build a preparation step that records all fresh child params explicitly."
  [id dependencies target instruction]
  (workflow/step
   id (str "Prepare " target " handoff") :self :depends-on dependencies
   :attributes {"handoff/identity" #(select-keys % [:feature :card :branch :worktree])}
   (fn [params]
     (format-alpha/prose
      "
        {instruction}

        Read `strand workflow show {target}`. Record `handoff/request` on this
        step with workflow `{target}`, explicit `params`, and the receiving
        coordinator's `owner`. For Land also record `authorization`, a reference
        to the user's actual permission; a human label is not permission.
        Carry card, feature, branch and worktree from the work record. For review,
        supply review-target and a unique review-id. Commit and push first.

        The next code gate starts or reuses `handoff-<this-step-id>` and persists
        acceptance here as `handoff/receipt`. Do not launch it separately. Engine
        acceptance transfers workflow custody, not completion or merge authority.
        The named owner must be available to drive the accepted run.

        Known work identity: {identity:json}
      " {:instruction instruction :target target
         :identity (select-keys params [:feature :card :branch :worktree])}))))

(defn launch-gate
  "Build a launch/reuse gate with a durable workflow acceptance receipt."
  [id preparation target phase]
  (workflow/gate
   id (str "Accept " target " handoff") :code :depends-on [preparation]
   :attributes {"code/fn" "me.workflows.handoff/launch!"
                "delivery/key" #(key-for phase %)
                "code/params" #(hash-map :key (key-for phase %) :target target)}
   "Read code/result and the preparation step's handoff/receipt on resume. A mismatch blocks handoff; do not invent a new run id."))
