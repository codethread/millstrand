(ns ct.spools.devcycles.workflows
  "Shared dev-cycle workflow definitions.

  SPIKE — illustrative, not loaded; instructions deliberately terse.
  Shows the three seams: params for repo-varying values, gate commands
  as bindings data, and defer points whose default `:call` targets ship
  in this spool so nothing needs a consumer registration to load."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.spools.workflow :as workflow]))

;; --- params ------------------------------------------------------------------
;; Repo-varying values are params with loom defaults. `:bindings` is plain data
;; (it must survive the params JSON round-trip), deep-merged over the defaults.
(s/def ::branch string?)
(s/def ::worktree string?)
(s/def ::mainline string?)
(s/def ::roster string?)
;; ::bindings is defined beside `bindable` below; s/keys resolves the keyword
;; at validation time, so the forward reference is fine.
(s/def ::land-params (s/keys :req-un [::branch ::worktree]
                             :opt-un [::mainline ::roster ::bindings]))
(s/def ::fix-params (s/keys :req-un [::branch ::worktree]))

;; --- bindings ----------------------------------------------------------------
;; workflow.md's forge-agnostic pattern: action-ref -> gate attrs as data. The
;; loom ships repo-agnostic gh commands; a consumer overrides any subset.
;; RESOLVED (was a GAP): no per-start ceremony and no new primitive needed — a
;; world shadows the registration and carries its bindings in :defaults, one
;; registration per world (see consumers/skein-src.init.clj; needs only the
;; defworkflow :override? accretion). Residue that stands: bindings must stay
;; JSON-safe data because resolved params ride workflow/context, so computed
;; values exist only where THIS template's render fns compute them from that
;; data. The bindings-registry kind idea is demoted to nice-to-have; advice
;; (see ../../advice-sketch.clj) would retire gate-attr plumbing entirely.
;; One declared surface, three derivations — defaults, the ::bindings spec,
;; and the render thunks all come from `bindable`, so the contract cannot
;; drift and the authoring pain collapses to one map:
(def bindable
  "Published binding surface: action-ref -> gate attr -> loom default.

  This IS the consumer contract: ::bindings is derived from it, so
  `workflow show land` projects exactly what may be bound without the
  consumer reading source."
  {"land.ci.green" {"shell/argv" ["gh" "pr" "checks" "--watch" "--fail-fast"]
                    "shell/timeout-secs" 5400}
   "land.merge" {"shell/argv" ["gh" "pr" "merge" "--squash" "--auto"]}})

(def ^:private owned-prefixes
  "Action-ref prefixes whose validation this surface owns. Foreign-prefixed
  bindings flow through UNvalidated: a run binds every style once at start,
  and refs for a deferred/nested definition (e.g. \"cleanup.teardown\") pass
  here to be judged by the definition that owns their prefix."
  #{"land." "fix."})

(defn- binds-declared-surface?
  "Strict for owned prefixes — an owned action-ref must exist in `bindable`
  with only declared attr keys, so a typo fails at `workflow start`
  (TEN-003) — permissive for foreign prefixes (see `owned-prefixes`)."
  [bindings]
  (every? (fn [[action-ref attrs]]
            (if (some #(str/starts-with? action-ref %) owned-prefixes)
              (when-let [declared (get bindable action-ref)]
                (every? (set (keys declared)) (keys attrs)))
              true))
          bindings))
;; Trade named: a typo inside a FOREIGN prefix ("cleanp.teardown") is caught
;; by nobody until no definition claims it — the flow-through window. The
;; workflow/bound promotion closes it globally: with every definition's
;; surface registered, validation can refuse a ref no loaded definition
;; declares while still letting cross-definition bindings ride one params map.

(defn gate-attr
  "Return a render thunk for one bound gate attribute: the consumer's
  :bindings param wins over the declared default."
  [action-ref k]
  (fn [params]
    (get-in (merge-with merge bindable (:bindings params)) [action-ref k])))

(s/def ::bindings (s/and map? binds-declared-surface?))
;; Candidate workflow/* promotion: `(workflow/bound action-ref k default)`
;; with the default inline at point of use would delete `bindable` too, let
;; registration validate refs against the definition's own steps, and let
;; `describe` report the bindable surface for every registered workflow.

;; --- fix: validation style is a defer ---------------------------------------
;; The default target: a docs-check gate, registered with :call so the defer
;; can run it as a returning procedure.
(workflow/defworkflow docs-check
  "Default fix validation: prove `make docs-check` green in the worktree."
  {:entrypoints #{:call}
   :param-spec ::fix-params}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Docs gate: " branch))
   (workflow/gate :check "Docs gate" :shell
                  :attributes {"shell/argv" ["make" "docs-check"]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "workflow/instruction" "Terse: fix, commit, clear gate/error."})))

;; The template leaves HOW to validate open; it is exported so a consumer world
;; can re-bind it (see consumers/skein-src.init.clj).
(def fix-template
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Fix: " branch))
   {:attributes {"workflow/family" "fix"}}
   (workflow/step :implement "Implement with a regression lock" :self
                  :attributes {"workflow/instruction" "Terse: fix + failing-first test, commit."})
   (workflow/defer :validate "Choose how this fix is validated"
     :depends-on [:implement])
   ;; GAP (the one that still stands, advice or not): the defer target receives
   ;; only its own defaults plus explicit params (PROP-Dfr-001.NG6) — the worker
   ;; must re-pass worktree/branch by hand when filling. A declared
   ;; param-forwarding list on the defer point is the missing primitive.
   (workflow/step :handoff "Hand the branch to land" :self
                  :depends-on [:validate]
                  :attributes {"workflow/instruction" "Terse: start land with the same branch/worktree."})))

(workflow/defworkflow fix
  "Light bug-fix flow; validation style selected at run time."
  {:entrypoints #{:start}
   :param-spec ::fix-params}
  ;; Allowlist > one target: the driving worker picks the style per run with
  ;; `workflow defer <run-id> <target> --params ...`. Consumers extend the set
  ;; by shadowing this registration with their own bind-defers.
  (workflow/bind-defers fix-template {:validate #{:docs-check}}))

;; --- land: commands are bindings, cleanup extras are a defer -----------------
(workflow/defworkflow no-extra-cleanup
  "Default land cleanup extras: nothing beyond branch/worktree removal."
  {:entrypoints #{:call} :param-spec map?}
  (workflow/workflow "No extra cleanup"
                     (workflow/step :noop "Nothing to do" :self)))

(def land-template
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Land: " branch))
   {:attributes {"workflow/family" "land"}}
   (workflow/step :push-draft-pr "Push branch, open draft PR" :self
                  :attributes {"workflow/instruction"
                               (fn [{:keys [mainline] :or {mainline "main"}}]
                                 (str "Terse: push; draft PR against " mainline "."))})
   (workflow/gate :ci-green "Watch CI to green" :shell
                  :depends-on [:push-draft-pr]
                  :attributes {"shell/argv" (gate-attr "land.ci.green" "shell/argv")
                               "shell/timeout-secs" (gate-attr "land.ci.green" "shell/timeout-secs")
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "workflow/instruction" "Terse: all checks green closes this."})
   (workflow/step :signoff-review
                  (fn [{:keys [roster] :or {roster "change-review"}}]
                    (str "Roster review: " roster))
                  :self
                  :depends-on [:ci-green]
                  :attributes {"workflow/instruction" "Terse: agent review + synthesis verdict."})
   (workflow/checkpoint :signoff "Human sign-off"
                        :depends-on [:signoff-review]
                        :kind :agent
                        ;; :next routes resolve by registered name and refuse the whole
                        ;; refresh if absent — so land-merge/land-abort MUST ship in this
                        ;; spool (elided in the spike), never as consumer homework.
                        :choices [{:key :approved :label "Approve" :next :land-merge}
                                  {:key :abort :label "Abort" :next :land-abort}])
   ;; NOTE (not a gap): the merge-lock acquire/release and kanban lane moves
   ;; live in the `land` op (policy over the engine). They lift as-is; the only
   ;; consequence is the op's namespace name becomes contractual on day one.
   (workflow/defer :cleanup-extras "Repo-specific cleanup after merge"
     :depends-on [:signoff])))

(workflow/defworkflow land
  "Coordinator landing flow; commands and cleanup are consumer-shapable."
  {:entrypoints #{:start}
   :param-spec ::land-params
   :defaults {:mainline "main" :roster "change-review"}}
  (workflow/bind-defers land-template {:cleanup-extras #{:no-extra-cleanup}}))
