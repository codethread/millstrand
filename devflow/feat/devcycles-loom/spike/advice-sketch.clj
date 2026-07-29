;; SPIKE — candidate primitive: workflow ADVICE.
;;
;; The question this answers: GAPs 1 and 3 exist because a template must
;; anticipate every repo-varying point as a param or bindings key. Advice
;; inverts that: the template ships only the invariant skeleton, and whoever
;; authors a REGISTRATION reshapes the value — insert a gate, merge attributes
;; — without the template predicting it.
;;
;; Why this is less meta than it sounds: definitions are plain values, and
;; `bind-defers` is ALREADY a pure definition -> definition transformation.
;; `advise` is its sibling, not a new species. The engine never sees advice:
;; composition happens at authoring time in trusted Clojure, the registered
;; definition is an ordinary compiled value, and `workflow show`/`describe`
;; report the final composed graph. No runtime weaving, no interception.
;;
;; Why fns come back: params are JSON round-tripped, so consumer bindings had
;; to be data (GAP 1's ceremony). Advice runs at authoring time in trusted
;; config, so attribute values may be render fns again — computed argv returns.
;;
;; The real cost, named: step ids and topology become published contract.
;; Advising `:push-draft-pr` couples the consumer to that id and its position;
;; renaming a step in the loom becomes a BREAK for advisers, where today only
;; param keys promise accretion. If adopted, the doc triad must list each
;; template's step ids as API, and validation must refuse an unknown target
;; loudly (`:workflow/advice-unknown-step`, mirroring `:workflow/defer-unknown`).
;;
;; The discipline that keeps it from becoming AOP soup: NO advice registry, no
;; layered advice merging, no advising another owner's registration in place.
;; Advice composes only where a registration is authored, by plain threading;
;; changing someone else's registered name remains the shadow + :overrides path.

(ns ct.spools.devcycles.advice-sketch
  "Pseudo-namespace; `workflow/advise` does not exist. Refines the user
  sketch: no `workflow/bindings` wrapper needed — threading IS the
  composition surface, and defer binding stays `bind-defers`."
  (:require [ct.spools.devcycles.workflows :as devcycles]
            [skein.spools.workflow :as workflow]))

;; The template shrinks: push-draft-pr -> signoff-review -> signoff -> cleanup
;; defer. NO ci-green gate — CI verification is not invariant, it is a style.
;; (Compare workflows.clj, where ci-green + gate-attr plumbing live in the
;; template because params were the only seam.)

;; The loom's OWN default registration uses the same combinator consumers do —
;; the seam is exercised from day one, not reserved for others:
(workflow/defworkflow land
  "Coordinator landing flow; CI style and cleanup are advice/defer points."
  {:entrypoints #{:start}
   :param-spec ::devcycles/land-params
   :defaults {:mainline "main" :roster "change-review"}}
  (-> devcycles/land-template
      (workflow/bind-defers {:cleanup-extras #{:no-extra-cleanup}})
      (workflow/advise
       {:push-draft-pr
        {:after [(workflow/gate :ci-green "Watch CI to green" :shell
                                :attributes {"shell/argv" ["gh" "pr" "checks" "--watch" "--fail-fast"]
                                             "shell/cwd" (fn [{:keys [worktree]}] worktree)
                                             "workflow/instruction" "Terse: all checks green closes this."})]}})))

;; skein-src's shadow: same shape, its own gate — script text and a render fn,
;; no bindings map, no :defaults trick, no per-start params:
(workflow/defworkflow land ; workspace layer, declared override of :land
  "land with this repo's CI watch."
  {:entrypoints #{:start} :param-spec ::devcycles/land-params}
  (-> devcycles/land-template
      (workflow/bind-defers {:cleanup-extras #{:no-extra-cleanup :warm-repl-teardown}})
      (workflow/advise
       {:push-draft-pr
        {:after [(workflow/gate :ci-green "Watch repo CI" :shell
                                :attributes {"shell/argv"
                                             (fn [{:keys [branch]}]
                                               ["sh" "-c" devcycles/ci-watch-script "land-ci-watch" branch])
                                             "shell/cwd" (fn [{:keys [worktree]}] worktree)})]}})))

;; Advice op vocabulary kept minimal on purpose (each addition widens the
;; structural contract):
;;   {:step-id {:after [step*]}}        insert, depending on step-id
;;   {:step-id {:attributes {...}}}     merge over the step's attributes
;; Deliberately absent: :before/:around/:replace/:remove — removal and
;; replacement change what an instruction upstream may reference; if a repo
;; needs them, the template was cut wrong, which is a loom bug to fix at the
;; source rather than advise around.
;;
;; What this retires if adopted: gate-attr + default-bindings + the :defaults
;; bindings trick (GAP 1 gone), and most pressure for a bindings registry kind.
;; What it does NOT retire: template var exports (advice needs the value —
;; finding 3 stands, now clearly THE published API), defer param forwarding
;; (GAP 2, orthogonal), and shadow-with-:overrides (still how a world replaces
;; a registered name — advice only shapes the value being registered).
