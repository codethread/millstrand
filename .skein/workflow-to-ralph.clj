(ns workflow-to-ralph
  "Prepare a kanban epic for the repository's Ralph execution loops."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when value is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- distinct-values?
  "Return true when values contains no duplicates."
  [values]
  (= (count values) (count (distinct values))))

(defn- exact-keys?
  "Return a predicate requiring a map to contain exactly allowed keys."
  [allowed]
  #(= allowed (set (keys %))))

(s/def ::epic non-blank-string?)
(s/def ::features
  (s/and (s/coll-of non-blank-string? :kind vector? :min-count 1)
         distinct-values?))
(s/def ::prepare-params
  (s/and (s/keys :req-un [::epic]) (exact-keys? #{:epic})))
(s/def ::review-params
  (s/and (s/keys :req-un [::epic ::features])
         (exact-keys? #{:epic :features})))
(s/def ::breakdown-input
  (s/and (s/keys :req-un [::features]) (exact-keys? #{:features})))

(def ^:private breakdown-input
  "Declare the feature ids created by the epic decomposition step."
  {:spec ::breakdown-input
   :doc (format-alpha/reflow
         "|Supply every feature card id created under the epic. Each id must be
          |unique; the next stage pours one review checkpoint for each feature.")})

(defn- attr-value
  "Return strand attribute key across keyword and string projections."
  [strand key]
  (or (get-in strand [:attributes key])
      (get-in strand [:attributes (name key)])))

(defn validate-epic!
  "Validate the epic boundary for a workflow-to-ralph run.

  Fails loudly unless `params` names an active kanban epic without the `ralph`
  label. Returns a short executor result string on success."
  [{:keys [epic] :as params}]
  (require-valid! ::prepare-params params
                  "Ralph epic validation parameters are invalid")
  (let [strand (weaver/show (current/runtime) epic)]
    (when-not strand
      (throw (ex-info "Ralph preparation epic does not exist" {:epic epic})))
    (when-not (= "true" (attr-value strand :kanban/card))
      (throw (ex-info "Ralph preparation target is not a kanban card"
                      {:epic epic :attributes (:attributes strand)})))
    (when-not (= "epic" (attr-value strand :kanban/type))
      (throw (ex-info "Ralph preparation target is not an epic"
                      {:epic epic :type (attr-value strand :kanban/type)})))
    (when-not (= "active" (:state strand))
      (throw (ex-info "Ralph preparation epic is not active"
                      {:epic epic :state (:state strand)})))
    (when (some? (attr-value strand :kanban.label/ralph))
      (throw (ex-info "Ralph preparation epic already carries the ralph label"
                      {:epic epic
                       :label (attr-value strand :kanban.label/ralph)})))
    (str "validated active unlabeled epic " epic)))

(defn- require-feature-breakdowns!
  "Validate direct epic membership and a non-empty task breakdown per feature."
  [runtime epic features]
  (let [children (into #{}
                       (map :to_strand_id)
                       (graph/outgoing-edges runtime [epic] "parent-of"))]
    (doseq [feature features]
      (when-not (contains? children feature)
        (throw (ex-info "Ralph feature is not a direct child of the epic"
                        {:epic epic :feature feature
                         :direct-children (vec (sort children))})))
      (let [tasks (:tasks ((requiring-resolve 'ct.spools.kanban/task-list)
                           runtime feature))]
        (when (empty? tasks)
          (throw (ex-info "Ralph feature has no task breakdown"
                          {:epic epic :feature feature})))))))

(defn label-epic!
  "Validate reviewed feature breakdowns and apply the Ralph readiness label.

  `params` carries the epic id and the exact reviewed feature ids. Revalidates
  the epic and every direct feature before the single label mutation, then
  verifies the persisted postcondition."
  [{:keys [epic features] :as params}]
  (require-valid! ::review-params params
                  "Ralph labeling parameters are invalid")
  (validate-epic! {:epic epic})
  (let [runtime (current/runtime)]
    (require-feature-breakdowns! runtime epic features)
    ((requiring-resolve 'ct.spools.kanban/label-add!) runtime epic ["ralph"])
    (let [labeled (weaver/show runtime epic)]
      (when-not (= "true" (attr-value labeled :kanban.label/ralph))
        (throw (ex-info "Ralph label mutation did not satisfy its postcondition"
                        {:epic epic
                         :label (attr-value labeled :kanban.label/ralph)})))
      (str "labeled epic " epic " for Ralph after " (count features)
           " feature reviews"))))

(workflow/defworkflow workflow-to-ralph-review
  "Review every feature breakdown before marking its epic ready for Ralph.

  This continuation pours one agent checkpoint for each feature id. The final
  labeling step fans in over every approval, so it cannot become ready while a
  feature review remains open."
  {:entrypoints #{:continue}
   :param-spec ::review-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [epic]}] (str "Review Ralph breakdown for epic " epic))
   {:attributes {"workflow/family" "workflow-to-ralph"
                 "workflow-to-ralph/epic" (fn [{:keys [epic]}] epic)}}
   (workflow/checkpoint
    :review-feature
    (fn [{:keys [item]}] (str "Review task breakdown for feature " item))
    :kind :agent
    :loop {:each :features}
    :choices [{:key :approved
               :label "Approve breakdown"
               :description
               "The feature has a coherent, executable task DAG."}]
    :attributes
    {"workflow/action-ref" "workflow-to-ralph.feature.review"
     "workflow/instruction"
     (fn [{:keys [item]}]
       (format-alpha/reflow
        (format
         "|Inspect `strand kanban card %s` and its task strands. Check that the
          |tasks cover the feature, have clear bodies and acceptance boundaries,
          |and express real blocking order with depends-on edges. Repair the
          |breakdown before choosing approved; leave this checkpoint open while
          |any task is missing, vague, oversized, or incorrectly ordered."
         item)))})
   (workflow/gate
    :label-epic
    (fn [{:keys [epic]}] (str "Mark epic " epic " ready for Ralph"))
    :code
    :depends-on [:review-feature]
    :attributes
    {"workflow/action-ref" "workflow-to-ralph.epic.label"
     "code/fn" "workflow-to-ralph/label-epic!"
     "code/params" (fn [{:keys [epic features]}]
                     {:epic epic :features features})
     "workflow/instruction"
     (fn [{:keys [epic]}]
       (format-alpha/reflow
        (format
         "|Machine gate: revalidate the active epic and every reviewed feature's
          |direct membership and non-empty task breakdown, apply the ralph label,
          |and verify the persisted postcondition. This gate is ready only after
          |every feature review passed. Target epic: %s."
         epic)))})))

(workflow/defworkflow workflow-to-ralph
  "Decompose an existing kanban epic and prepare it for a Ralph loop.

  The first stage creates feature cards and task strands without applying the
  `ralph` label. Its checkpoint records the complete feature-id set and routes
  to the per-feature review continuation."
  {:entrypoints #{:start}
   :param-spec ::prepare-params
   :defaults {}
   :example {:epic "abc12"}
   :param-docs
   {:epic (format-alpha/reflow
           "|Id of the active kanban epic to decompose. The workflow leaves its
            |ralph label absent until every feature review passes.")}}
  (workflow/workflow
   (fn [{:keys [epic]}] (str "Prepare epic " epic " for Ralph"))
   {:attributes {"workflow/family" "workflow-to-ralph"
                 "workflow-to-ralph/epic" (fn [{:keys [epic]}] epic)}}
   (workflow/gate
    :validate-epic
    (fn [{:keys [epic]}] (str "Validate Ralph epic " epic))
    :code
    :attributes
    {"workflow/action-ref" "workflow-to-ralph.epic.validate"
     "code/fn" "workflow-to-ralph/validate-epic!"
     "code/params" (fn [{:keys [epic]}] {:epic epic})})
   (workflow/step
    :decompose-epic
    (fn [{:keys [epic]}] (str "Decompose epic " epic))
    :self
    :depends-on [:validate-epic]
    :attributes
    {"workflow/action-ref" "workflow-to-ralph.epic.decompose"
     "workflow/instruction"
     (fn [{:keys [epic]}]
       (format-alpha/reflow
        (format
         "|Read `strand kanban card %s`. Confirm it is an active epic and does
          |not carry the ralph label. Create each independently deliverable slice
          |as a feature card under that epic, then add a complete task-strand DAG
          |to every feature. Keep the feature ids for the next checkpoint."
         epic)))})
   (workflow/checkpoint
    :breakdown-ready
    "Record the decomposed feature cards"
    :depends-on [:decompose-epic]
    :kind :agent
    :choices [{:key :ready-for-review
               :label "Start feature reviews"
               :description
               "Every feature and task strand exists; supply all feature ids."
               :next :workflow-to-ralph-review
               :input breakdown-input}]
    :attributes
    {"workflow/decision-point" "ralph-breakdown-ready"
     "workflow/action-ref" "workflow-to-ralph.breakdown.ready"})))
