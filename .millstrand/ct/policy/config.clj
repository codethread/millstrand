(ns ct.policy.config
  "Repo-local Millstrand runtime configuration for millstrand-src: named queries and the
  validation helpers used by workspace policy.

  Thin glue only: `ct.spools.devflow` owns the feature lifecycle,
  `millhouse.spools.workflow` is its generic CLI, `ct.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern (all activated from
  init.clj). This file registers named queries. Sibling init.clj modules hold
  the rest of the repo policy: hand-authored modules under ct/workflows/,
  reviewer rosters in ct/agents/reviewers.clj, chime attention rules
  in ct/notifications/attention.clj, and the NVD scan cron job in
  ct/jobs/nvd_scan.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [millstrand.api.millstrand.alpha :as millstrand]))

;; ---------------------------------------------------------------------------
;; Named queries
;; ---------------------------------------------------------------------------

(millstrand/defquery! run-active
  "Parameterized query for the active strands of one workflow run."
  {:usage "strand list --query run-active --param run-id=<run-id>"}
  {:params [:run-id]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :run-id]]]})

(millstrand/defquery! kanban-feature-work
  "Parameterized query for active direct task children of one feature card."
  {:usage "strand ready --query kanban-feature-work --param feature=<feature-id>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "kanban/task"] "true"]
           [:edge/in "parent-of" [:= :id [:param :feature]]]]})

(millstrand/defquery! workflow-runs
  "Query for active workflow roots (any family)."
  {:usage "strand list --query workflow-runs --limit 500"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]])

(millstrand/defquery! merge-lock
  "Query for the active singleton landing lock."
  {:usage "strand list --query merge-lock"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-lock"]])

(millstrand/defquery! merge-queue
  "Query for the runs queued to merge, including the one holding the lock."
  {:usage "strand list --query merge-queue"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-queue-entry"]])

(millstrand/defquery! work
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

;; ---------------------------------------------------------------------------
;; devflow ops: thin CLI wrappers over ct.spools.devflow
;; ---------------------------------------------------------------------------

(defn require-non-blank!
  "Return value when it is a non-blank string, otherwise fail with arg context.

  Public: the workflow modules (loaded after this file) reuse it for policy."
  [arg value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value value})))
  value)

(defn parse-json-object-arg
  "Parse a CLI JSON-object argument into a keywordized map, failing loudly.

  Public: the workflow modules (loaded after this file) reuse it for policy."
  [op raw]
  (let [value (json/read-str raw :key-fn keyword)]
    (when-not (map? value)
      (throw (ex-info (str op " JSON input must be an object")
                      {:input raw})))
    value))

;; The blessed arg-spec parser (millstrand.api.cli.alpha) binds positionals strictly
;; by order, so it cannot express the position-independent `step=<id>` selector
;; these ops accept, nor disambiguate the optional json-input/notes slots it can
;; sit among (docs/reference.md "Discovery tiers"). We therefore declare the fixed
;; positionals in each arg-spec — which drives generated `strand help <op>` — and
;; collect the optional tail into one variadic positional, then split `step=<id>`
;; out of that tail here. Fail-loud errors reference `strand help <op>` in their
;; data instead of a hand-written usage string.
(defn pop-step-selector
  "Split one optional `step=<id>` selector out of variadic tail tokens.

  `step=` is a whole token rather than a positional slot so it never collides
  with the other optional args (notes, JSON input) sharing the tail. Returns
  `[other-tokens step-id-or-nil]`, failing loudly on a duplicate selector or a
  blank id. Public: the workflow modules (loaded after this file) reuse it for
  the land op's tail convention."
  [op tail]
  (let [{steps true others false} (group-by #(str/starts-with? % "step=") tail)]
    (when (> (count steps) 1)
      (throw (ex-info (str op " accepts at most one step=<id> selector")
                      {:op op :help (str "strand help " op) :tail (vec tail)})))
    (when-let [step (first steps)]
      (require-non-blank! :step (subs step (count "step="))))
    [(vec others) (some-> (first steps) (subs (count "step=")))]))
