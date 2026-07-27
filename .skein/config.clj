(ns config
  "Repo-local Skein runtime configuration for skein-src: named queries and the
  validation helpers used by workspace policy.

  Thin glue only: `ct.spools.devflow` owns the feature lifecycle,
  `skein.spools.workflow` is its generic CLI, `ct.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern (all activated from
  init.clj). This file registers named queries. Sibling init.clj modules hold
  the rest of the repo policy: hand-authored workflows in workflows.clj,
  harness seats in harnesses.clj, chime attention rules in attention.clj, the
  NVD scan cron job in nvd_scan.clj, and reviewer rosters in reviewers.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skein.macros.queries :refer [defquery]]))

;; ---------------------------------------------------------------------------
;; Named queries
;; ---------------------------------------------------------------------------

(defquery feature-active-query
  "Parameterized query for all active strands carrying a feature attribute."
  {:usage "strand list --query feature-active --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]]})

(defquery feature-work-query
  "Parameterized query for active task/review strands in a feature."
  {:usage "strand ready --query feature-work --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-owner-work-query
  "Parameterized query for active task/review strands in a feature owned by one actor."
  {:usage "strand ready --query feature-owner-work --param feature=<feature> --param owner=<owner>"}
  {:params [:feature :owner]
   :where [:and
           [:= :state "active"]
           [:= [:attr :feature] [:param :feature]]
           [:= [:attr :owner] [:param :owner]]
           [:in [:attr :kind] ["task" "review"]]]})

(defquery feature-run-query
  "Parameterized query for the active strands of one workflow run/feature."
  {:usage "strand list --query feature-run --param feature=<feature>"}
  {:params [:feature]
   :where [:and
           [:= :state "active"]
           [:= [:attr "workflow/run-id"] [:param :feature]]]})

(defquery workflow-runs-query
  "Query for active workflow roots (any family)."
  {:usage "strand list --query workflow-runs --limit 500"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]])

(defquery devflow-runs-query
  "Query for active devflow lifecycle roots."
  {:usage "strand list --query devflow-runs"}
  [:and
   [:= :state "active"]
   [:= [:attr "workflow/role"] "root"]
   [:= [:attr "workflow/family"] "devflow"]])

(defquery merge-lock-query
  "Query for the active singleton landing lock."
  {:usage "strand list --query merge-lock"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-lock"]])

(defquery work-query
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

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [arg value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value value})))
  value)

(defn parse-json-object-arg
  "Parse a CLI JSON-object argument into a keywordized map, failing loudly.

  Public: workflows.clj (loaded after this file) reuses it for the land op."
  [op raw]
  (let [value (json/read-str raw :key-fn keyword)]
    (when-not (map? value)
      (throw (ex-info (str op " JSON input must be an object")
                      {:input raw})))
    value))

;; The blessed arg-spec parser (skein.api.cli.alpha) binds positionals strictly
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
  blank id. Public: workflows.clj (loaded after this file) reuses it for the
  land op's tail convention."
  [op tail]
  (let [{steps true others false} (group-by #(str/starts-with? % "step=") tail)]
    (when (> (count steps) 1)
      (throw (ex-info (str op " accepts at most one step=<id> selector")
                      {:op op :help (str "strand help " op) :tail (vec tail)})))
    (when-let [step (first steps)]
      (require-non-blank! :step (subs step (count "step="))))
    [(vec others) (some-> (first steps) (subs (count "step=")))]))
