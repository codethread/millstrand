(ns workflow-to-ralph
  "Prepare a kanban epic for the repository's Ralph execution loops."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as format-alpha]
            [skein.spools.workflow :as workflow]))

(defn- non-blank-string?
  "Return true when value is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- distinct-values?
  "Return true when values contains no duplicates."
  [values]
  (= (count values) (count (distinct values))))

(s/def ::epic non-blank-string?)
(s/def ::features
  (s/and (s/coll-of non-blank-string? :kind vector? :min-count 1)
         distinct-values?))
(s/def ::prepare-params (s/keys :req-un [::epic]))
(s/def ::review-params (s/keys :req-un [::epic ::features]))
(s/def ::breakdown-input (s/keys :req-un [::features]))

(def ^:private breakdown-input
  "Declare the feature ids created by the epic decomposition step."
  {:spec ::breakdown-input
   :doc (format-alpha/reflow
         "|Supply every feature card id created under the epic. Each id must be
          |unique; the next stage pours one review checkpoint for each feature.")})

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
   (workflow/step
    :label-epic
    (fn [{:keys [epic]}] (str "Mark epic " epic " ready for Ralph"))
    :self
    :depends-on [:review-feature]
    :attributes
    {"workflow/action-ref" "workflow-to-ralph.epic.label"
     "workflow/instruction"
     (fn [{:keys [epic]}]
       (format-alpha/reflow
        (format
         "|Run `strand kanban label add %s ralph`, then read
          |`strand kanban card %s` and confirm the active card is an epic with
          |`kanban.label/ralph` equal to true. Do not apply the label by any
          |other path; this step is ready only after every feature review passed."
         epic epic)))})))

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
   (workflow/step
    :decompose-epic
    (fn [{:keys [epic]}] (str "Decompose epic " epic))
    :self
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
