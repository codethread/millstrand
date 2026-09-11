(ns me.policy.config
  "Named queries for the repository workspace.

  Thin glue only: `ct.spools.devflow` owns the feature lifecycle,
  `millhouse.spools.workflow` is its generic CLI, `ct.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern (all activated from
  init.clj). This file registers named queries. Sibling init.clj modules hold
  the rest of the repo policy: hand-authored modules under me/workflows/,
  reviewer rosters in me/agents/reviewers.clj, chime attention rules
  in me/notifications/attention.clj, and the NVD scan cron job in
  me/jobs/nvd_scan.clj."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

;; ---------------------------------------------------------------------------
;; Named queries
;; ---------------------------------------------------------------------------

(millstrand/defquery run-active
  "Parameterized query for the active strands of one workflow run."
  {:usage "strand list --query run-active --param run-id=<run-id>"}
  {:params [:run-id]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :run-id]]]})

(millstrand/defquery kanban-feature-work
  "Parameterized query for active direct task children of one feature card."
  {:usage "strand ready --query kanban-feature-work --param feature=<feature-id>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "kanban/task"] "true"]
           [:edge/in "parent-of" [:= :id [:param :feature]]]]})

(millstrand/defquery workflow-runs
  "Query for active workflow roots (any family)."
  {:usage "strand list --query workflow-runs --limit 500"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]])

(millstrand/defquery merge-lock
  "Query for the active singleton landing lock."
  {:usage "strand list --query merge-lock"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-lock"]])

(millstrand/defquery merge-queue
  "Query for the runs queued to merge, including the one holding the lock."
  {:usage "strand list --query merge-queue"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-queue-entry"]])

(millstrand/defquery work
  "Query for active actionable work, excluding workflow plumbing, agent run records, and inert kanban refinement cards."
  {:usage "strand ready --query work"}
  [:and
   [:= :state "active"]
   [:or [:missing [:attr "agent-run/run"]]
    [:not [:= [:attr "agent-run/run"] "true"]]]
   [:or [:missing [:attr "kanban/lane"]]
    [:not [:= [:attr "kanban/lane"] "refinement"]]]
   [:or
    [:missing [:attr "workflow/role"]]
    [:not [:in [:attr "workflow/role"] ["root" "digest" "procedure"]]]]])
