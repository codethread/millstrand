(ns workflows-land
  "The coordinator landing policy: the merge train and the narrow `land` op.

  The land WORKFLOW definitions stay in workflows.clj — live runs carry
  `workflows/...` symbols on their persisted gates — and this module owns the
  policy the engine has no business knowing: the singleton merge lock, the
  first-in first-out merge queue in front of it, and the kanban lane moves that
  ride along. Nothing here is referenced by workflows.clj, so the two modules
  stay independent despite splitting one concern's file."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.skein.alpha :as skein]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.spool.alpha :refer [attr-get entity-projection poll-until!]]
            [skein.spools.workflow :as workflow]))

;; The op's own boundary contracts. The land WORKFLOW params keep their
;; `:workflows/...` twins beside the definitions they belong to; these govern
;; what arrives on the CLI.
(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::run-id ::non-blank)
(s/def ::reason ::non-blank)
(s/def ::pr-number pos-int?)

(def ^:private merge-lock-kind
  "Singleton strand kind for the repo-wide land merge sentinel."
  "merge-lock")

(def ^:private merge-queue-kind
  "Strand kind for one land run's reservation in the merge queue.

  Awaiting the lock is a declaration of intent to merge, so `land await` enqueues
  rather than polling a free/busy flag. Entries are ordered by `queue/queued-at`
  and only the head may acquire the lock, which turns the old race — where every
  losing coordinator rebased and re-ran the ten required checks, then raced again
  — into a train each run rebases exactly once at the front of.

  Main's ruleset sets `strict_required_status_checks_policy`, so a queued run
  still rebases onto the commits that landed ahead of it; the queue removes the
  repetition, not the rebase."
  "merge-queue-entry")

(def ^:private default-await-timeout-secs
  "Default `land await` block before returning the unlanded queue picture.

  Short by design: every return carries the train's liveness evidence, so a
  coordinator re-issuing `await` gets a heartbeat rather than a silent block."
  300)

(def ^:private await-poll-ms
  "Queue re-read interval while `land await` blocks."
  1000)

(def ^:private merge-lock-monitor
  "JVM-local half of merge-lock acquisition serialisation.

  The file lock handles other weaver processes; this monitor prevents overlapping
  file-lock attempts inside one JVM from raising OverlappingFileLockException."
  (Object.))

(defn- with-merge-lock-acquisition
  "Call f while holding the selected workspace's cross-process acquisition lock."
  [rt f]
  (let [config-dir (get-in rt [:metadata :config-dir])]
    (when-not (and (string? config-dir) (not (str/blank? config-dir)))
      (throw (ex-info "runtime has no selected config directory for merge-lock acquisition"
                      {:config-dir config-dir})))
    (with-open [file (java.io.RandomAccessFile.
                      (io/file config-dir ".land-merge-lock.acquire")
                      "rw")
                channel (.getChannel file)
                lock (.lock channel)]
      (f))))

(defn- attr-value
  "Return strand attribute k using the shared fail-loud attribute reader."
  [strand k]
  (when strand
    (attr-get strand k)))

(defn- require-land-input!
  "Return value when it satisfies spec, otherwise fail with spec evidence."
  [spec key value]
  (when-not (s/valid? spec value)
    (throw (ex-info (str (name key) " must be a non-blank string")
                    {:key key
                     :value value
                     :spec spec
                     :explain (s/explain-data spec value)})))
  value)

(defn- active-merge-locks
  "Return active merge-lock strands."
  []
  (weaver/list (current/runtime) [:and [:= :state "active"] [:= [:attr "kind"] merge-lock-kind]] {}))

(defn- land-root
  "Return the active land root for feature, failing loudly when absent."
  [feature]
  (or (workflow/current-root feature)
      (throw (ex-info "land run not found" {:feature feature}))))

(defn- require-sane-merge-locks!
  "Return active locks, refusing a corrupt multiple-lock state."
  []
  (let [locks (active-merge-locks)]
    (when (> (count locks) 1)
      (throw (ex-info "multiple active merge locks found; inspect and repair manually"
                      {:locks (mapv :id locks)})))
    locks))

(defn- require-owned-merge-lock!
  "Return feature's sole active lock, failing when absent or owned elsewhere."
  [feature]
  (if-let [lock (first (require-sane-merge-locks!))]
    (if (= feature (attr-value lock :land/run-id))
      lock
      (throw (ex-info "another land run holds the merge lock"
                      {:lock (:id lock)
                       :land/run-id (attr-value lock :land/run-id)
                       :expected-run-id feature})))
    (throw (ex-info "land cleanup requires its active merge lock"
                    {:land/run-id feature}))))

(defn- queue-entries
  "Return the active merge-queue entries in FIFO order.

  Ordering is the authored `queue/queued-at` stamp rather than the row's
  `updated_at`, so it comes from the runtime Clock and a manual Clock makes
  train order deterministic in tests. `:id` breaks same-instant ties."
  []
  (vec (sort-by (juxt #(attr-value % :queue/queued-at) :id)
                (weaver/list (current/runtime)
                             [:and
                              [:= :state "active"]
                              [:= [:attr "kind"] merge-queue-kind]]
                             {}))))

(defn- queue-entry-for
  "Return feature's entry among entries, or nil when it is not queued."
  [entries feature]
  (first (filter #(= feature (attr-value % :land/run-id)) entries)))

(defn- queue-position
  "Return feature's zero-based place in entries, or nil when it is not queued."
  [entries feature]
  (first (keep-indexed #(when (= feature (attr-value %2 :land/run-id)) %1) entries)))

(defn- enqueue-for-merge!
  "Return feature's queue entry, appending one to the train when absent.

  Idempotent by land run: re-issuing `land await` after a timeout keeps a
  coordinator's original place rather than sending it to the back."
  [feature]
  (or (queue-entry-for (queue-entries) feature)
      (let [rt (current/runtime)]
        (weaver/add! rt {:title (str "Merge queue: " feature)
                         :attributes {:kind merge-queue-kind
                                      :owner (:id (land-root feature))
                                      :land/run-id feature
                                      :queue/queued-at (str (runtime/now rt))}}))))

(defn- close-queue-entry!
  "Close feature's active queue entry with the outcome that removed it.

  `detail` carries any extra attributes the outcome records. Returns nil when
  feature was never queued, which is the ordinary case for an aborted run that
  never awaited its turn."
  [feature outcome detail]
  (when-let [entry (queue-entry-for (queue-entries) feature)]
    (let [rt (current/runtime)]
      (weaver/update! rt
                      (:id entry)
                      {:state "closed"
                       :attributes (merge {:queue/outcome outcome
                                           :queue/released-at (str (runtime/now rt))}
                                          detail)}))))

(defn- landed-since
  "Return the merges that completed at or after `queued-at`, oldest first.

  Read from the closed queue entries rather than from git: every merge acquires
  the lock through the train, so the entries are the record. A waiter reads this
  on grant to know which commits its single rebase has to absorb."
  [queued-at]
  (->> (weaver/list (current/runtime)
                    [:and
                     [:= :state "closed"]
                     [:= [:attr "kind"] merge-queue-kind]
                     [:= [:attr "queue/outcome"] "merged"]]
                    {})
       (filter #(not (neg? (compare (attr-value % :queue/released-at) queued-at))))
       (sort-by #(attr-value % :queue/released-at))
       (mapv #(hash-map :land/run-id (attr-value % :land/run-id)
                        :pr-number (attr-value % :queue/pr-number)
                        :subject (attr-value % :queue/subject)
                        :released-at (attr-value % :queue/released-at)))))

(defn- run-liveness
  "Return the evidence that decides whether a queued run is still moving.

  A land run whose root has gone reports `:run-state \"missing\"` and no stage:
  the absence is the finding, so it is named rather than papered over with a
  placeholder stage."
  [feature]
  (if-let [root (workflow/current-root feature)]
    (let [step (first (workflow/ready feature))]
      {:run-state "active"
       :updated-at (:updated_at root)
       ;; a checkpoint frontier carries no action-ref, and its checkpoint name is
       ;; the label a reader wants there
       :stage (or (:action-ref step) (:checkpoint step))})
    {:run-state "missing"}))

(defn- ahead-report
  "Return the runs blocking feature, head first, each with its liveness evidence.

  This is what makes an eviction decision possible: a head that has not updated
  while the train sat still is the one holding everyone up."
  [entries feature lock-holder]
  (into []
        (map-indexed (fn [index entry]
                       (let [run-id (attr-value entry :land/run-id)]
                         (merge {:land/run-id run-id
                                 :position index
                                 :queued-at (attr-value entry :queue/queued-at)
                                 :holds-lock (= run-id lock-holder)}
                                (run-liveness run-id)))))
        (take (queue-position entries feature) entries)))

(defn- acquire-merge-lock-serially!
  "Acquire the singleton merge lock inside the caller's serialization scope.

  The caller must head the merge queue. An approval that never awaited enqueues
  itself here, so every merge is recorded in the train — that record is what
  `landed-since` reads — while a coordinator still cannot step in front of runs
  already waiting. `merge-detail` is stamped on the entry so the record survives
  the run it came from."
  [feature merge-detail]
  (let [rt (current/runtime)
        root (land-root feature)
        owner (:id root)
        locks (require-sane-merge-locks!)
        owned (some #(when (and (= owner (attr-value % :owner))
                                (= feature (attr-value % :land/run-id)))
                       %)
                    locks)]
    (if owned
      {:lock owned :created? false :entry-created? false}
      (do
        (when-let [held (first locks)]
          (throw (ex-info "another land run holds the merge lock"
                          {:lock (:id held)
                           :owner (attr-value held :owner)
                           :land/run-id (attr-value held :land/run-id)})))
        (let [queued-before (queue-entries)
              entry (enqueue-for-merge! feature)
              entries (queue-entries)]
          (when-not (= (:id entry) (:id (first entries)))
            (throw (ex-info "another land run heads the merge queue"
                            {:land/run-id feature
                             :position (queue-position entries feature)
                             :head (attr-value (first entries) :land/run-id)
                             :ahead (mapv #(attr-value % :land/run-id)
                                          (take (queue-position entries feature) entries))})))
          (weaver/update! rt (:id entry) {:attributes merge-detail})
          {:lock (weaver/add! rt {:title (str "Merge lock: " feature)
                                  :attributes {:kind merge-lock-kind
                                               :owner owner
                                               :land/run-id feature}})
           :created? true
           :entry-created? (nil? (queue-entry-for queued-before feature))})))))

(defn- release-merge-lock!
  "Release the merge lock held by feature, if one exists.

  Leaving the queue is a separate decision: the caller knows which outcome
  removed the run from the train, and `close-queue-entry!` records it."
  [feature reason]
  (doseq [lock (require-sane-merge-locks!)
          :when (= feature (attr-value lock :land/run-id))]
    (weaver/update! (current/runtime)
                    (:id lock)
                    {:state "closed"
                     :attributes {:land/released-reason reason}})))

(defn- break-merge-lock!
  "Clear whatever is blocking the front of the merge train, with a reason.

  The head of the queue is the effective lock holder — it is the only run that
  may acquire — so a head that has died blocks every run behind it whether or
  not it got as far as taking the lock. Breaking therefore evicts both: the
  active lock and the head entry, or the head entry alone when the run stalled
  before acquiring. Both are recorded on the closed strands for forensics.

  Refuses only when there is nothing at the front to clear."
  [reason]
  (require-land-input! ::reason :reason reason)
  (let [rt (current/runtime)
        evict! (fn [strand attributes]
                 (entity-projection (weaver/update! rt
                                                    (:id strand)
                                                    {:state "closed"
                                                     :attributes attributes})))]
    (with-merge-lock-acquisition
      rt
      (fn []
        (let [lock (first (require-sane-merge-locks!))
              head (first (queue-entries))]
          (when-not (or lock head)
            (throw (ex-info "no active merge lock or queued run to break"
                            {:reason reason})))
          (cond-> {}
            lock (assoc :broken (evict! lock {:land/broken-reason reason}))
            head (assoc :evicted (evict! head {:queue/outcome "evicted"
                                               :queue/broken-reason reason
                                               :queue/released-at (str (runtime/now rt))}))))))))

(defn- merge-record
  "Return what a queued run leaves behind for the runs waiting on it.

  Stamped on the entry at acquisition, while the approval input is in hand, so
  `landed-since` still reads it long after the land run itself is gone."
  [feature input]
  (let [context (attr-value (land-root feature) :workflow/context)]
    {:queue/subject (:subject input)
     :queue/pr-number (or (:pr-number context) (get context "pr-number"))}))

(defn- granted-result
  "Return the payload for a run that has reached the front of the train."
  [queued-at]
  {:granted true
   :position 0
   :queued-at queued-at
   :landed-since (landed-since queued-at)
   :message (format-alpha/reflow
             "|You head the merge train. Main requires branches to be up to date,
              |so absorb `landed-since` now: rebase onto origin/main, re-establish
              |green CI at the new HEAD, then `land choose <run-id> approved`.")})

(defn- waiting-result
  "Return the payload for a run still behind others in the train."
  [entries feature queued-at]
  (let [lock-holder (attr-value (first (require-sane-merge-locks!)) :land/run-id)
        ahead (ahead-report entries feature lock-holder)
        head (first ahead)]
    {:granted false
     :position (queue-position entries feature)
     :queued-at queued-at
     :ahead ahead
     :message (format (format-alpha/reflow
                       "|Waiting behind %d run(s); %s heads the train (%s). Your
                        |place is held, so re-issue `land await` to check again. A
                        |head that has stopped updating is blocking everyone behind
                        |it: confirm its owner cannot resume, then
                        |`strand land break-lock --reason \"<reason>\"` to evict it.")
                      (count ahead)
                      (:land/run-id head)
                      (case (:run-state head)
                        "missing" "its land run is gone — evict it"
                        (str "at " (:stage head) ", last updated " (:updated-at head))))}))

(defn- await-merge-turn!
  "Enqueue feature and block until it heads the merge train or time runs out.

  Timing out never dequeues: a long train would otherwise starve the runs that
  waited longest, and every return carries the liveness evidence a coordinator
  needs to decide whether to keep waiting or evict a dead head."
  [feature timeout-secs]
  (let [rt (current/runtime)
        entry (locking merge-lock-monitor
                (with-merge-lock-acquisition rt #(enqueue-for-merge! feature)))
        queued-at (attr-value entry :queue/queued-at)
        head? (fn [entries] (= (:id entry) (:id (first entries))))]
    (poll-until!
     (runtime/clock rt)
     {:timeout-ms (* 1000 timeout-secs)
      :poll-ms await-poll-ms
      :check queue-entries
      :pred->result (fn [entries]
                      (when-not (queue-entry-for entries feature)
                        (throw (ex-info "merge queue entry was evicted while awaiting"
                                        {:land/run-id feature :entry (:id entry)})))
                      (when (head? entries)
                        (granted-result queued-at)))
      :on-timeout #(waiting-result % feature queued-at)})))

(defn- move-card-to-review!
  "Move an optional claimed card to in_review; return true only when changed."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "claimed" (do ((requiring-resolve 'ct.spools.kanban/review!) (current/runtime) card)
                      true)
        "in_review" false
        (throw (ex-info "land card must be claimed before review"
                        {:card card :lane (attr-value strand :kanban/lane)}))))))

(defn- suppressing-rollback!
  "Run f during error recovery, suppressing rollback failures on original."
  [^Throwable original f]
  (try
    (f)
    (catch Throwable rollback-error
      (.addSuppressed original rollback-error))))

(defn- move-card-to-rework!
  "Move an optional in_review card to claimed; return true only when changed."
  [card]
  (when (and (string? card) (not (str/blank? card)))
    (let [strand (weaver/show (current/runtime) card)]
      (when-not (= "true" (attr-value strand :kanban/card))
        (throw (ex-info "land card is not a kanban card" {:card card})))
      (case (attr-value strand :kanban/lane)
        "in_review" (do ((requiring-resolve 'ct.spools.kanban/rework!) (current/runtime) card)
                        true)
        "claimed" false
        (throw (ex-info "land card must be in_review before abort rework"
                        {:card card :lane (attr-value strand :kanban/lane)}))))))

(s/def ::land-policy-choice #{"approved" "abort"})

(defn- require-pr-number!
  "Return pr-number when it satisfies the land PR-number boundary spec."
  [feature pr-number]
  (when-not (s/valid? ::pr-number pr-number)
    (throw (ex-info "push-draft-pr completion requires a positive --pr-number"
                    {:argument :pr-number
                     :feature feature
                     :value pr-number
                     :spec ::pr-number
                     :explain (s/explain-data ::pr-number pr-number)})))
  pr-number)

(defn- keywordize-input!
  "Return a shallow keyword-keyed JSON object for workflow input specs."
  [verb input]
  (when-not (map? input)
    (throw (ex-info (str "land " verb " --input must be a JSON object")
                    {:verb verb :input input})))
  (into {}
        (map (fn [[k v]]
               [(if (string? k) (keyword k) k) v]))
        input))

(defn- require-choice-input-keys!
  "Return input when it contains no keys outside choice's closed contract."
  [choice input]
  (let [allowed (case choice
                  "approved" #{:subject :body}
                  "abort" #{:reason})
        unknown (vec (remove allowed (keys input)))]
    (when (seq unknown)
      (throw (ex-info "land choose input contains unsupported keys"
                      {:choice choice
                       :unknown unknown
                       :allowed (vec (sort allowed))})))
    input))
(def ^:private land-arg-spec
  "Declared policy boundaries for the coordinator-only `land` op."
  {:op "land"
   :doc (format-alpha/reflow
         "|Enforce the cross-domain policy boundaries of the coordinator landing
          |workflow. Use `strand workflow` for discovery, start, ready, ordinary
          |completion, revise, and run inspection.")
   :subcommands
   {"complete" {:doc (format-alpha/reflow
                      "|Complete a land policy boundary: record the opened PR and
                       |move its card, or close terminal bookkeeping and release
                       |the merge lock.")
                :hook-class :mutating :deadline-class :standard
                :flags {:pr-number {:type :int
                                    :doc "Positive PR number, required only at push-draft-pr."}}
                :positionals [{:name :run-id :required? true :doc "Land run id."}]}
    "choose" {:doc "Choose approved or abort sign-off with lock and card rollback."
              :hook-class :mutating :deadline-class :standard
              :annotations
              {:notes ["The choice positional is a closed enum: approved or abort."]}
              :flags {:input {:type :string
                              :parse :json
                              :required? true
                              :doc "JSON object satisfying the selected choice input spec."}}
              :positionals [{:name :run-id :required? true :doc "Land run id."}
                            {:name :choice
                             :required? true
                             :spec ::land-policy-choice
                             :doc "Closed policy enum: approved or abort."}]}
    "await" {:doc (format-alpha/reflow
                   "|Join the merge train and block until this run heads it.
                    |Awaiting is the reservation, so the queue is first-in
                    |first-out and a timeout keeps your place.")
             :hook-class :mutating :deadline-class :unbounded
             :annotations
             {:notes [(format-alpha/reflow
                       "|Main requires branches to be up to date, so a granted
                        |turn still means one rebase and one CI run — the train
                        |removes the repetition, not the rebase.")
                      (format-alpha/reflow
                       "|An ungranted return lists the runs ahead with their
                        |stage and last update. Confirm a stalled head cannot
                        |resume before breaking the lock on its behalf.")]}
             :flags {:timeout-secs {:type :int
                                    :doc (format "Seconds to block before returning the queue picture (default %d)."
                                                 default-await-timeout-secs)}}
             :positionals [{:name :run-id :required? true :doc "Land run id."}]}
    "break-lock" {:doc (format-alpha/reflow
                        "|Clear the front of the merge train with a reason: the
                         |active lock, the stalled head entry, or both.")
                  :hook-class :mutating :deadline-class :standard
                  :flags {:reason {:type :string
                                   :required? true
                                   :doc "Non-blank forensic recovery reason."}}}}})

(def ^:private land-returns
  {:subcommands
   (into {}
         (map (fn [subcommand]
                [subcommand {:type :map
                             :required {:operation :string}
                             :extra :json}]))
         (keys (:subcommands land-arg-spec)))})

(skein/defop land
  "Enforce coordinator landing policy across workflows, kanban, and merge locks.

  Use the generic `workflow` op for every operation that does not cross those
  ownership boundaries."
  {:returns land-returns :arg-spec land-arg-spec}
  [ctx]
  (let [{:keys [subcommand run-id pr-number input reason choice timeout-secs]} (:op/args ctx)
        verb (first subcommand)]
    (case verb
      "await"
      (await-merge-turn! (require-land-input! ::run-id :run-id run-id)
                         (or timeout-secs default-await-timeout-secs))

      "complete"
      (let [feature (require-land-input! ::run-id :run-id run-id)
            ready (workflow/ready feature)]
        (cond
          (some #(= "land.pr.open" (:action-ref %)) ready)
          (let [root (workflow/current-root feature)
                context (attr-value root :workflow/context)
                card (or (:card context) (get context "card"))
                changed? (move-card-to-review! card)]
            (try
              (workflow/complete!
               feature
               {:context {:pr-number (require-pr-number! feature pr-number)}})
              (catch Throwable t
                (when changed?
                  (suppressing-rollback! t #(move-card-to-rework! card)))
                (throw t))))

          (some #(contains? #{"land.cleanup" "land.abort.record"} (:action-ref %)) ready)
          (do
            (when (some? pr-number)
              (throw (ex-info "--pr-number is only accepted at push-draft-pr"
                              {:run-id feature :pr-number pr-number})))
            (with-merge-lock-acquisition
              (current/runtime)
              (fn []
                (let [merged? (some #(= "land.cleanup" (:action-ref %)) ready)]
                  (when merged?
                    (require-owned-merge-lock! feature))
                  (let [result (workflow/complete! feature)]
                    (release-merge-lock! feature "land terminal cleanup")
                    (close-queue-entry! feature (if merged? "merged" "aborted") {})
                    result)))))

          :else
          (throw (ex-info "land complete requires a PR-open or terminal policy frontier"
                          {:run-id feature :ready ready}))))

      "choose"
      (let [feature (require-land-input! ::run-id :run-id run-id)]
        (when-not (s/valid? ::land-policy-choice choice)
          (throw (ex-info "land choose accepts only approved or abort"
                          {:run-id feature
                           :choice choice
                           :allowed (vec (sort (s/describe ::land-policy-choice)))
                           :spec ::land-policy-choice
                           :explain (s/explain-data ::land-policy-choice choice)})))
        (let [input (->> input
                         (keywordize-input! "choose")
                         (require-choice-input-keys! choice))]
          (case choice
            "approved"
            (locking merge-lock-monitor
              (with-merge-lock-acquisition
                (current/runtime)
                (fn []
                  (let [{:keys [created? entry-created?]}
                        (acquire-merge-lock-serially! feature (merge-record feature input))]
                    (try
                      (workflow/choose! feature :approved input)
                      (catch Throwable t
                        (when created?
                          (suppressing-rollback!
                           t
                           #(release-merge-lock! feature "land choose failed")))
                        (when entry-created?
                          (suppressing-rollback!
                           t
                           #(close-queue-entry! feature "aborted"
                                                {:queue/broken-reason "land choose failed"})))
                        (throw t)))))))

            "abort"
            (let [context (attr-value (workflow/current-root feature) :workflow/context)
                  card (or (:card context) (get context "card"))
                  changed? (move-card-to-rework! card)]
              (try
                (let [chosen (workflow/choose! feature :abort input)]
                  (close-queue-entry! feature "aborted" {})
                  chosen)
                (catch Throwable t
                  (when changed?
                    (suppressing-rollback! t #(move-card-to-review! card)))
                  (throw t))))

            (throw (ex-info "land choose accepts only approved or abort"
                            {:run-id feature :choice choice
                             :allowed ["approved" "abort"]})))))

      "break-lock" (break-merge-lock! reason))))

