(ns ct.workflows.land-policy
  "The coordinator landing policy: the merge train and the narrow `land` op.

  The land WORKFLOW definitions live in workflows/land.clj — live
  runs carry their qualified symbols on persisted gates — and this module owns
  the policy the engine has no business knowing: the singleton merge lock, the
  first-in first-out merge queue in front of it, and the kanban lane moves that
  ride along. The two modules stay independent."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.api.spool.alpha :refer [attr-get entity-projection poll-until!]]
            [millstrand.spools.workflow :as workflow]))

;; The op's own boundary contracts. The land workflow definitions own their
;; run-param specs; these govern what arrives on the CLI.
(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::run-id ::non-blank)
(s/def ::reason ::non-blank)
(s/def ::pr-number pos-int?)
;; zero is meaningful — it asks for the queue picture without blocking — but a
;; negative budget has no reading, and the poll skeleton would reject it far
;; from the flag the operator typed.
(s/def ::timeout-secs (s/and int? (complement neg?)))

(def ^:private merge-lock-kind
  "Singleton strand kind for the repo-wide land merge sentinel."
  "merge-lock")

(def ^:private merge-queue-kind
  "Strand kind for one land run's reservation in the merge queue.

  Awaiting the lock is a declaration of intent to merge, so `land await` enqueues
  rather than polling a free/busy flag. Entries are ordered by `queue/sequence`
  and only the head may acquire the lock, which turns the old race — where every
  losing coordinator rebased and re-ran the local quality contract, then raced
  again — into a train each run rebases exactly once at the front of.

  A queued run still rebases onto the commits that landed ahead of it; the queue
  removes the repetition, not the rebase."
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
  "Call f while holding both halves of the acquisition lock.

  The monitor is taken here rather than by each caller: a caller that reached
  the file lock without it would raise OverlappingFileLockException against its
  own siblings instead of serializing with them, and every path that touches the
  lock or the queue has to hold both."
  [rt f]
  (let [config-dir (get-in rt [:metadata :config-dir])]
    (when-not (and (string? config-dir) (not (str/blank? config-dir)))
      (throw (ex-info "runtime has no selected config directory for merge-lock acquisition"
                      {:config-dir config-dir})))
    (locking merge-lock-monitor
      (with-open [file (java.io.RandomAccessFile.
                        (io/file config-dir ".land-merge-lock.acquire")
                        "rw")
                  channel (.getChannel file)
                  lock (.lock channel)]
        (f)))))

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
  "Return active locks, refusing corrupt ownership metadata or duplicates."
  []
  (let [locks (active-merge-locks)
        details (mapv #(select-keys
                        (assoc (select-keys % [:id :owner])
                               :land/run-id (attr-value % :land/run-id))
                        [:id :owner :land/run-id])
                      locks)
        malformed (filterv #(not (s/valid? ::run-id (:land/run-id %))) details)]
    (cond
      (seq malformed)
      (throw (ex-info "active merge lock has invalid ownership metadata"
                      {:code "land/merge-lock-corrupt"
                       :locks details
                       :invalid-locks malformed
                       :recovery (format-alpha/reflow
                                  "|Repair or close the malformed active merge lock,
                                   |then retry the land operation.")}))

      (> (count locks) 1)
      (throw (ex-info "multiple active merge locks found; inspect and repair manually"
                      {:code "land/multiple-merge-locks"
                       :locks details
                       :recovery (format-alpha/reflow
                                  "|Close the corrupt duplicate lock(s), then retry the
                                   |land operation.")})))
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

(defn- signoff-approval-row?
  "Return true when an updated batch row closes the land sign-off as approved."
  [{:keys [after]}]
  (and (= "closed" (:state after))
       (= "land-signed-off" (attr-value after :workflow/decision-point))
       (= "approved" (attr-value after :workflow/outcome))))

(defn- require-poured-run-id!
  "Return the sole run-id named by updated workflow root rows.

  An approved sign-off updates an existing checkpoint, so its run attribution
  belongs to the updated root row in `:batch/updated`, not only to
  continuation roots in `:batch/created`. Multiple run ids or root rows
  without a usable run-id make the batch ambiguous and stop the mutation rather
  than letting a first-match projection choose an owner. Every updated root
  row is checked before the distinct-run-id count, so a valid row cannot mask
  malformed attribution."
  [updated-rows]
  (let [root-details (->> updated-rows
                          (filter #(= "root" (attr-value (:after %) :workflow/role)))
                          (mapv (fn [{:keys [after]}]
                                  (assoc (select-keys after [:id])
                                         :workflow/run-id (attr-value after :workflow/run-id)))))
        malformed (filterv #(not (s/valid? ::run-id (:workflow/run-id %)))
                           root-details)
        run-ids (->> root-details
                     (map :workflow/run-id)
                     distinct
                     vec)
        run-id (first run-ids)]
    (when (or (seq malformed) (not= 1 (count run-ids)))
      (throw (ex-info (format-alpha/reflow
                       "|Approved land sign-off batches must update workflow rows
                       |with usable run-ids for exactly one distinct run.")
                      {:code "land/signoff-run-ambiguous"
                       :roots root-details
                       :recovery (format-alpha/reflow
                                  "|Inspect the updated sign-off rows and retry after
                                   |restoring one usable :workflow/run-id.")})))
    run-id))

(millstrand/defhook require-merge-lock-at-signoff-approval
  "Veto committing an approved land sign-off that holds no merge lock.

  `strand land choose <run> approved` acquires the singleton merge lock and a
  merge-train slot before recording the approval, so at commit time the lock
  strand already names the run. A generic `strand workflow next` or
  `strand workflow choose` approval skips that acquisition; without this gate the
  run walks the whole land-merge continuation only to be refused at
  terminal cleanup. Rejecting the batch here stops it at sign-off, before
  anything merges. The sign-off's `revise` and `abort` choices and every
  ordinary land step stay drivable through the generic verbs. It fails with
  `land/signoff-run-ambiguous` for an unattributable approval,
  `land/merge-lock-corrupt` for malformed lock ownership metadata,
  `land/multiple-merge-locks` for a malformed lock set, and
  `land/signoff-without-merge-lock` when the approved run holds no matching
  lock."
  {:types #{:batch/apply-before-commit}}
  [ctx]
  (let [approval-rows (filterv signoff-approval-row? (:batch/updated ctx))]
    (doseq [row approval-rows]
      (let [run-id (require-poured-run-id! (:batch/updated ctx))
            locks (require-sane-merge-locks!)]
        (when-not (some #(= run-id (attr-value % :land/run-id)) locks)
          (throw (ex-info
                  (format-alpha/reflow
                   (format
                    "|land sign-off approval requires the merge lock this run does
                     |not hold; approve with `strand land choose %s approved`,
                     |which acquires it — never with generic workflow verbs"
                    run-id))
                  {:code "land/signoff-without-merge-lock"
                   :land/run-id run-id
                   :checkpoint (:id row)}))))))
  nil)

(defn- all-queue-entries
  "Return every merge-queue entry ever created, in any state."
  []
  (weaver/list (current/runtime) [:= [:attr "kind"] merge-queue-kind] {}))

(defn- require-sequenced!
  "Return entries once every one carries a numeric `queue/sequence`.

  `context` names the read in the failure. Skipping a malformed value would
  hand out a number the corrupt history may already hold, so an unreadable
  history stops the train rather than quietly extending it."
  [context entries]
  (let [malformed (remove #(number? (attr-value % :queue/sequence)) entries)]
    (when (seq malformed)
      (throw (ex-info "merge queue entry carries no usable sequence; train order is unknowable"
                      {:context context
                       :entries (mapv (juxt :id #(attr-value % :queue/sequence)) malformed)
                       :expected "a number"
                       :recovery "strand land break-lock --reason \"<reason>\""}))))
  entries)

(defn- queue-entries
  "Return the active merge-queue entries in FIFO order.

  Order comes from `queue/sequence`, not from the `queue/queued-at` timestamp
  it is stamped beside: two enqueues under a coarse or manual Clock can share an
  instant, and a tie broken by strand id would silently reorder the train. The
  timestamp stays for readers; the sequence is the contract.

  An entry that cannot be placed — no sequence, or one shared with a sibling —
  fails here rather than sorting to an arbitrary position and quietly breaking
  the order this promises."
  []
  (let [entries (require-sequenced!
                 :ordering
                 (weaver/list (current/runtime)
                              [:and
                               [:= :state "active"]
                               [:= [:attr "kind"] merge-queue-kind]]
                              {}))
        sequences (mapv #(attr-value % :queue/sequence) entries)]
    (when-not (= (count sequences) (count (distinct sequences)))
      (throw (ex-info "merge queue holds duplicate sequences; train order is ambiguous"
                      {:entries (mapv (juxt :id #(attr-value % :queue/sequence)) entries)
                       :recovery "strand land break-lock --reason \"<reason>\""})))
    (vec (sort-by #(attr-value % :queue/sequence) entries))))

(defn- next-queue-sequence
  "Return the next train position, one past the highest ever issued.

  Counted over entries in every state so a closed train never lets a new run
  reuse a number an active entry still holds. Callers must hold the acquisition
  lock, which is what makes the read-then-write safe."
  []
  (->> (all-queue-entries)
       (require-sequenced! :allocation)
       (map #(attr-value % :queue/sequence))
       (reduce max -1)
       inc))

(defn- queue-entry-for
  "Return feature's entry among entries, or nil when it is not queued."
  [entries feature]
  (first (filter #(= feature (attr-value % :land/run-id)) entries)))

(defn- queue-position
  "Return feature's zero-based place in entries, or nil when it is not queued."
  [entries feature]
  (first (keep-indexed #(when (= feature (attr-value %2 :land/run-id)) %1) entries)))

(defn- require-ready-to-merge!
  "Return feature's land root once the run is actually at sign-off.

  The train is a queue of runs ready to merge, so admission is checked rather
  than assumed: a run admitted before sign-off would take the head, fail every
  approval it attempted, and wedge everyone behind it until an operator broke
  the lock."
  [feature]
  (let [root (land-root feature)
        family (attr-value root :workflow/family)
        step (first (workflow/ready feature))]
    (when-not (= "land" family)
      (throw (ex-info "the merge train admits land runs only"
                      {:land/run-id feature
                       :workflow/family family})))
    (when-not (and (= "signoff" (:checkpoint step))
                   (some #{"approved"} (:choices step)))
      (throw (ex-info "a run joins the merge train at sign-off, not before"
                      {:land/run-id feature
                       :frontier (or (:checkpoint step) (:action-ref step))
                       :choices (:choices step)})))
    root))

(defn- enqueue-for-merge!
  "Return feature's queue entry, appending one to the train when absent.

  Idempotent by land run: re-issuing `land await` after a timeout keeps a
  coordinator's original place rather than sending it to the back."
  [feature]
  (or (queue-entry-for (queue-entries) feature)
      (let [rt (current/runtime)
            root (require-ready-to-merge! feature)]
        (weaver/add! rt {:title (str "Merge queue: " feature)
                         :attributes {:kind merge-queue-kind
                                      :owner (:id root)
                                      :land/run-id feature
                                      :queue/sequence (next-queue-sequence)
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
             "|You head the merge train. `landed-since` lists only merges recorded
              |after you joined; it is not an up-to-date check. Before approval, run
              |`git fetch origin && git rev-list --count HEAD..origin/main`. If the
              |count is nonzero, rebase onto origin/main — never merge main into the
              |branch — and re-run the tracked local quality contract at the new
              |pushed HEAD; then
              |`land choose <run-id> approved`.")})

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

(defn- require-await-timeout!
  "Return the await budget in seconds, defaulting when the flag is absent."
  [timeout-secs]
  (if (nil? timeout-secs)
    default-await-timeout-secs
    (do (when-not (s/valid? ::timeout-secs timeout-secs)
          (throw (ex-info "--timeout-secs must be a non-negative integer"
                          {:argument :timeout-secs
                           :value timeout-secs
                           :spec ::timeout-secs
                           :explain (s/explain-data ::timeout-secs timeout-secs)})))
        timeout-secs)))

(defn- await-merge-turn!
  "Enqueue feature and block until it heads the merge train or time runs out.

  Timing out never dequeues: a long train would otherwise starve the runs that
  waited longest, and every return carries the liveness evidence a coordinator
  needs to decide whether to keep waiting or evict a dead head."
  [feature timeout-secs]
  (let [rt (current/runtime)
        entry (with-merge-lock-acquisition rt #(enqueue-for-merge! feature))
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
    "choose" {:doc (format-alpha/reflow
                    "|Choose an approved or aborted sign-off with lock and card
                     |rollback. For approved sign-off, use `strand land choose
                     |<run-id> approved`; generic workflow approval is rejected.")
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
                       "|A granted turn still requires a deterministic freshness check:
                        |run `git fetch origin && git rev-list --count
                        |HEAD..origin/main` regardless of `landed-since`. A nonzero
                        |count means rebase and re-run the local quality contract; the train removes the
                        |repetition, not the rebase.")
                      (format-alpha/reflow
                       "|Branches come current by rebase, never by merging main in:
                        |landing squashes, so a merge commit preserves nothing and
                        |only adds noise to the commit-by-commit review surface.")
                      (format-alpha/reflow
                       "|An ungranted return lists the runs ahead with their
                        |stage and last update. Confirm a stalled head cannot
                        |resume before breaking the lock on its behalf.")]}
             :flags {:timeout-secs {:type :int
                                    :spec ::timeout-secs
                                    :doc (format "Non-negative seconds to block before returning the queue picture (default %d)."
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

(def ^:private land-meta
  "Cross-verb narrative for `land`, projected by the `about`/`prime` meta-verbs."
  {:about (format-alpha/reflow
           "|land is the coordinator-only policy surface beside the land
            |workflow. The workflow op discovers, starts, and drives the run
            |(ready/next/await on ordinary steps and gates). This op owns the
            |boundaries the engine must not: recording the opened PR and
            |moving the card (`complete` at push-draft-pr), sign-off that
            |acquires the merge lock (`choose approved|abort` — never generic
            |`workflow choose` for approval), joining the merge train
            |(`await`), and clearing a stalled train head (`break-lock`).
            |Terminal `complete` releases the lock and closes queue
            |bookkeeping after merge or abort. Old runs retain their poured gate
            |commands: a pre-sign-off stall cannot use `revise`, be hand-closed,
            |or be followed by a second merge run.")
   :prime (format-alpha/reflow
           "|Coordinator landing discipline. Start with
            |`strand workflow start <run-id> --workflow land --params
            |'{feature,branch,worktree,review-target,review-id,change-context
            |[,card]}'`, then drive the frontier with `workflow ready` /
            |`workflow next`. `review-target` is the work's TASK strand, never
            |the kanban card. `change-context` carries a concrete
            |`<base-sha>..<head-sha>` commit range and its changed-file vector.
            |At push-draft-pr: push, open or reuse a draft PR, then `strand
            |land complete <run-id> --pr-number <n>` (not bare workflow
            |complete). The first ci-green shell gate runs the tracked
            |`.millstrand/land-quality.sh` contract from the feature worktree. The
            |workflow then fans the change-review roster into subagent gates
            |and synthesizes their notes. Resolve the synthesis, commit and
            |push fixes, and complete resolve-review; final-ci-green runs the
            |same contract against the current pushed HEAD before sign-off.
            |Old land runs retain the commands poured into their gates. Do not
            |hand-close or declare an old remote-CI gate green. A pre-sign-off run
            |stalled in that gate cannot reach `revise`; leave it active and resolve
            |the already-merged PR under this policy. Do not close the gate to force
            |migration or start a second merge run. If an old gate has `gate/error`,
            |fix its cause, push the exact branch HEAD, then clear only that gate's
            |error to retry.
            |On a gate failure, fix the cause and clear `gate/error` to retry.
            |Approve only with `strand land choose <run-id> approved
            |--input '{\"subject\":\"…\",\"body\":\"…\"}'` after
            |`git fetch origin && git rev-list --count HEAD..origin/main` is
            |zero (rebase onto origin/main if not — never merge main in). If
            |another coordinator holds the lock, `strand land await <run-id>`
            |joins the train. Finish with the tidy/cleanup frontier, then
            |terminal `land complete <run-id>` when ready asks for it. Full
            |verb shapes: `strand help land`.")})

(millstrand/defop land
  "Enforce coordinator landing policy across workflows, kanban, and merge locks.

  Use the generic `workflow` op for every operation that does not cross those
  ownership boundaries."
  (merge land-meta {:returns land-returns :arg-spec land-arg-spec})
  [ctx]
  (let [{:keys [subcommand run-id pr-number input reason choice timeout-secs]} (:op/args ctx)
        verb (first subcommand)]
    (case verb
      "await"
      (await-merge-turn! (require-land-input! ::run-id :run-id run-id)
                         (require-await-timeout! timeout-secs))

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
                      (throw t))))))

            "abort"
            (let [context (attr-value (workflow/current-root feature) :workflow/context)
                  card (or (:card context) (get context "card"))
                  changed? (move-card-to-rework! card)]
              (try
                (let [chosen (workflow/choose! feature :abort input)]
                  ;; leaving the train is a queue mutation like any other and
                  ;; races enqueues and evictions without the acquisition lock
                  (with-merge-lock-acquisition
                    (current/runtime)
                    #(close-queue-entry! feature "aborted" {}))
                  chosen)
                (catch Throwable t
                  (when changed?
                    (suppressing-rollback! t #(move-card-to-review! card)))
                  (throw t))))

            (throw (ex-info "land choose accepts only approved or abort"
                            {:run-id feature :choice choice
                             :allowed ["approved" "abort"]})))))

      "break-lock" (break-merge-lock! reason))))
