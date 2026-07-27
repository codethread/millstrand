(ns config
  "Repo-local Skein runtime configuration for skein-src: named queries and the
  thin CLI op surface over the shipped spools.

  Thin glue only: `ct.spools.devflow` owns the feature lifecycle,
  `skein.spools.workflow` is its generic CLI, `ct.spools.delegation` owns the
  `strand agent` surface plus the `agent-plan` pattern (all activated from
  init.clj). This file registers the `hitl` session op and a few named queries.
  Sibling init.clj modules hold the rest of the repo policy: hand-authored
  workflows in workflows.clj, harness seats in harnesses.clj, chime attention
  rules in attention.clj, the NVD scan cron job in nvd_scan.clj, and reviewer
  rosters in reviewers.clj."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [skein.macros.ops :refer [defop]]
            [skein.macros.queries :refer [defquery]]
            [ct.spools.agent-run :as shuttle]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]))

(def ^:private stamped-op-return
  {:type :map :required {:operation :string} :extra :json})

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

;; ---------------------------------------------------------------------------
;; hitl: interactive human-in-the-loop working sessions
;; ---------------------------------------------------------------------------

(defn- hitl-prompt
  "Compose the session prompt: coordinator-supplied context plus the tracking
  contract that makes the session self-terminating (the session agent records
  notes and an outcome on the tracking strand, then closes it, which completes
  the run and tears down the multiplexer session)."
  [tracking-id context]
  (str "You are an INTERACTIVE HITL session: the user is attached to this"
       " terminal and you work through the task together, at their direction."
       " This is a working session, not a headless task — converse, propose,"
       " and act when they agree.\n\n"
       context
       "\n\n## Tracking contract (important)\n"
       "Your tracking strand is " tracking-id ". A coordinator agent reads it"
       " after this session to learn what happened — the user will not relay"
       " details.\n"
       "- Record each significant decision as a closed note child as you go:"
       " `strand add \"note: <decision>\" --state closed` then"
       " `strand update " tracking-id " --edge parent-of:<note-id>`.\n"
       "- When the user says you are done: write the outcome —"
       " `strand update " tracking-id " --attr outcome=\"<2-5 sentence summary:"
       " decisions, commits (shas), open questions>\"` — then close the strand:"
       " `strand update " tracking-id " --state closed`.\n"
       "- Closing " tracking-id " completes your run and tears down this"
       " session — make it your very last act, after the outcome attr is"
       " written and any final commit is made.\n"))

(defop hitl
  "Open an interactive HITL working session for a human + agent pair.

  Usage: `strand hitl <parent-id> <title> --context <text> [--cwd <dir>]
  [--harness <name>] [--backend <name>]`. Creates a tracking strand under
  `parent-id` (a kanban card, plan, or work root), composes the required
  `--context` brief with the tracking contract, and spawns an interactive
  multiplexer run serving the tracking strand (default harness `hitl-fable`,
  backend `tmux`). The session ends when the session agent closes the tracking
  strand after writing its outcome; the coordinator then reads the tracking
  strand for notes and outcome. Returns the tracking id and pending run
  summary — `strand agent ps` carries the session name and attach command once
  the session is live."
  {:returns stamped-op-return :arg-spec {:op "hitl"
                                         :hook-class :mutating
                                         :deadline-class :standard
                                         :doc "Open an interactive HITL session: tracking strand + multiplexer run."
                                         :positionals [{:name :parent-id
                                                        :type :string
                                                        :required? true
                                                        :doc "Strand to hang the tracking strand under (kanban card, plan, or work root)."}
                                                       {:name :title
                                                        :type :string
                                                        :required? true
                                                        :doc "Short session title."}]
                                         :flags {:context {:type :string
                                                           :doc "Required session brief: the situation, artifacts, findings, and what to work through together."}
                                                 :cwd {:type :string
                                                       :doc "Working directory for the session (defaults to the workspace root)."}
                                                 :harness {:type :string
                                                           :doc "Interactive-capable harness (prompt-via :arg TUI, e.g. hitl-fable — the default). Headless harnesses like build die in a pane."}
                                                 :backend {:type :string
                                                           :doc "Multiplexer backend (default tmux)."}}}}
  [ctx]
  (let [{:keys [parent-id title context cwd harness backend]} (:op/args ctx)
        rt (current/runtime)]
    (require-non-blank! :context context)
    (when-not (weaver/show rt parent-id)
      (throw (ex-info "hitl parent strand not found" {:parent parent-id})))
    (let [tracking (weaver/add! rt {:title (str "HITL: " title)
                                    :attributes {"hitl" "true"
                                                 "body" (str "Tracking strand for the interactive HITL session \"" title "\"."
                                                             " The session agent appends closed note children for decisions,"
                                                             " writes a final outcome attr, then closes this strand to end its"
                                                             " run and tear down the session.")}})]
      (weaver/update! rt parent-id {:edges [{:type "parent-of" :to (:id tracking)}]})
      (let [run (shuttle/spawn-run! {:harness (or harness "hitl-fable")
                                     :prompt (hitl-prompt (:id tracking) context)
                                     :title (str "HITL: " title)
                                     :parent (:id tracking)
                                     :cwd cwd
                                     :mode :interactive
                                     :backend (or backend "tmux")})]
        {:operation "hitl"
         :tracking (:id tracking)
         :parent parent-id
         :run (shuttle/run-summary run)
         :next "strand agent ps shows the attach command once the session is live; the session ends when the tracking strand closes."}))))
