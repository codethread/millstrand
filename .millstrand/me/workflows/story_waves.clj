(ns me.workflows.story-waves
  "Serial materialization and completion receipts for finite Story module waves."
  (:require [clojure.spec.alpha :as s]
            [me.workflows.evidence :as evidence]
            [me.workflows.handoff :as handoff]
            [me.workflows.support :as support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::modules (s/coll-of support/non-blank-string? :kind vector? :distinct true))
(s/def ::feature support/non-blank-string?)
(s/def ::branch support/non-blank-string?)
(s/def ::worktree support/non-blank-string?)
(s/def ::reviewer-harness support/non-blank-string?)
(s/def ::params (s/keys :req-un [::modules ::feature ::branch ::worktree ::reviewer-harness]))

(defn- completed? [{:keys [run-id root]}]
  (let [rt (current/runtime)
        strand (weaver/show rt root)]
    (and (= run-id (attr-get strand :workflow/run-id))
         (= "closed" (:state strand)) (workflow/done? run-id)
         (every? #(not= "active" (:state %)) (:strands (graph/subgraph rt [root]))))))

(defn launch!
  "Materialize one ready wave slot and block its successors on the child root.

  The next slot stays blocked by this active gate while the child is started and
  its completion dependency is installed. Only then may the executor close this
  gate. Thus a later child cannot exist before its predecessor finishes. Retry
  reuses the deterministic child even after an interrupted receipt write."
  [{:keys [key params]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.story-waves/launch!" key)
        run-id (str "wave-" (:id gate))
        roots #(weaver/list rt [:and [:= [:attr "workflow/run-id"] run-id]
                                [:= [:attr "story/source"] (:id gate)]] {})]
    (when-not (seq (weaver/ready rt [:= :id (:id gate)] {}))
      (fail! "Previous module wave has not completed" {:gate (:id gate)}))
    (when (empty? (roots))
      (workflow/start! run-id :story-wave params
                       {:root-attributes {"story/source" (:id gate) "story/request" params}}))
    (let [root (evidence/single! (roots) "Expected the accepted wave root")
          receipt {:run-id run-id :root (:id root) :module (:module params)}]
      (when-not (= params (evidence/data (attr-get root :story/request)))
        (fail! "Module wave request changed" {:run-id run-id}))
      ;; Successors are still blocked by this gate until its executor returns.
      (doseq [edge (graph/incoming-edges rt [(:id gate)] "depends-on")]
        (workflow/bond! (:id root) (:from_strand_id edge)))
      (weaver/update! rt (:id gate) {:attributes {:story/child receipt}})
      receipt)))

(defn join!
  "Require the complete finite collection's actual child roots to finish."
  [{:keys [key modules]}]
  (let [rt (current/runtime)
        gate (evidence/gate! "me.workflows.story-waves/join!" key)
        parents (mapv :from_strand_id (graph/incoming-edges rt [(:id gate)] "parent-of"))
        slots (filter #(and (= key (attr-get % :story/sequence))
                            (= "me.workflows.story-waves/launch!" (attr-get % :code/fn)))
                      (:strands (graph/subgraph rt parents)))
        receipts (into {} (map #(let [child (evidence/data (attr-get % :story/child))]
                                  [(:module child) child])) slots)]
    (when-not (and (= (count slots) (count modules))
                   (= (set modules) (set (keys receipts)))
                   (every? #(= "closed" (:state %)) slots)
                   (every? completed? (vals receipts)))
      (fail! "Module waves have not completed" {:modules modules :children receipts}))
    {:status "joined" :children (mapv receipts modules)}))

(defn- sequence-key [params]
  (handoff/key-for "story-waves" (dissoc params :module)))

(defn- slot-key [params]
  (handoff/key-for "story-wave" (assoc params :module (:item params))))

(workflow/defworkflow story-waves
  "Materialize each module only after its predecessor finishes, then join."
  {:entrypoints #{:call} :param-spec ::params :defaults {}}
  (workflow/workflow
   "Serial Story module waves"
   (workflow/step
    :drive "Drive and await the recorded module waves" :self :depends-on []
    (format-alpha/prose
     "
       Read story/child on this procedure's launch slots in the parent subgraph.
       Each receipt names the accepted child run. Drive that child and await its
       completion; later children do not exist until their predecessors finish.
       If the next receipt is absent, await its launch gate rather than starting
       a child yourself. Keep this step open and the task claimed while waiting.

       Record each child's final revision and result. Complete this step only
       after every module in the finite collection has a completed child receipt.
       An empty collection needs no children. The final join checks actual roots.
     "))
   (workflow/gate
    :launch "Launch or reuse the next module wave" :code
    :depends-on [] :loop {:each :modules :chain true}
    :attributes {"code/fn" "me.workflows.story-waves/launch!"
                 "delivery/key" slot-key
                 "story/sequence" sequence-key
                 "code/params" #(hash-map :key (slot-key %)
                                          :params (assoc (select-keys % [:feature :branch :worktree :reviewer-harness :card])
                                                         :module (:item %)))}
    "Read story/child here and drive that child run. Later children do not exist until their predecessors complete.")
   (workflow/gate
    :join "Join completed module waves" :code :depends-on [:launch :drive]
    :attributes {"code/fn" "me.workflows.story-waves/join!"
                 "delivery/key" sequence-key
                 "code/params" #(hash-map :key (sequence-key %) :modules (:modules %))}
    "Verify every recorded child completed and return their ordered receipts to the parent.")))
