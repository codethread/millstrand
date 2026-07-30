;; SPIKE — candidate primitive: NAMESPACED RUN CONTEXT.
;;
;; The observation: the engine already HAS an accreting run map —
;; `complete!` `:context` shallow-merges into the root's workflow/context, and
;; continuations carry it — but it dies at two boundaries: defer targets
;; (PROP-Dfr-001.NG6: own defaults + explicit params only) and key clashes
;; (unqualified :branch means last-write-wins is unsafe, so nothing dares
;; accrete). Qualify the keys and both boundaries soften: shallow merge over
;; qualified keys IS safe accretion, and a target's own param-spec can say
;; which keys it reads.
;;
;; The fork that decides whether this solves :forward — two namespace tiers:
;;
;;   1. Per-definition namespaces (:docs-check/*) for private params. If
;;      cross-cutting values ALSO live here, forwarding is reborn as mapping
;;      (:fix/worktree -> :docs-check/worktree at every boundary) and nothing
;;      was solved.
;;   2. Shared vocabulary namespaces (:vcs/branch, :vcs/worktree, :run/card)
;;      for cross-cutting values, declared with an owner like attribute
;;      namespaces are (`strand vocab` discipline, applied to params). fix
;;      writes :vcs/* once; docs-check reads :vcs/* by spec; NO forwarding
;;      exists because there is nothing to forward — the context flows whole
;;      and each spec names what it consumes.
;;
;; Isolation survives as a *visible* contract: NG6's "no silent caller
;; coupling" becomes "a target reads only what its qualified param-spec
;; declares, validated at fill" — the spec is the declaration, on the target
;; where it belongs, instead of :forward lists on every defer point.

(ns ct.spools.devcycles.namespaced-context-sketch
  "Pseudo-namespace; engine changes required (see cost note at bottom)."
  (:require [clojure.spec.alpha :as s]
            [skein.spools.workflow :as workflow]))

;; Shared run vocabulary — declared once, owner-checked like attribute
;; namespaces; the loom is the natural owner of :vcs/* and :run/*.
(s/def :vcs/branch string?)
(s/def :vcs/worktree string?)
(s/def :run/card (s/nilable string?))

;; Private params keep the definition's namespace.
(s/def :docs-check/strict? boolean?)
(s/def ::docs-check-params (s/keys :req [:vcs/branch :vcs/worktree]
                                   :opt [:docs-check/strict?]))

(workflow/defworkflow docs-check
  "Default fix validation; reads the shared vcs vocabulary, owns its
  private keys."
  {:entrypoints #{:call}
   :param-spec ::docs-check-params}
  (workflow/workflow
   (fn [{:vcs/keys [branch]}] (str "Docs gate: " branch))
   (workflow/gate :check "Docs gate" :shell
                  :attributes {"shell/argv" ["make" "docs-check"]
                               "shell/cwd" (fn [{:vcs/keys [worktree]}] worktree)
                               "workflow/instruction" "Terse: green closes this."})))

;; fix writes the shared keys once at start; the :validate defer needs NO
;; :forward and the worker filling it passes only genuinely-new input —
;; docs-check's spec pulls :vcs/* from the accreted context and fails loudly
;; at fill if the run never wrote them.
(s/def ::fix-params (s/keys :req [:vcs/branch :vcs/worktree] :opt [:run/card]))

;; What this subsumes if adopted:
;;   - defer :forward — never needs to exist (this file replaces that GAP).
;;   - the bindings prefix predicate (workflows.clj owned-prefixes hack):
;;     bindings become :land/bindings, :docs-check/bindings — the key's
;;     namespace IS the scope, each spec validates its own, flow-through free.
;;   - most context-mapping prose in instructions ("re-pass the worktree").
;;
;; Costs, named:
;;   - Reversing NG6 (context flows into defer targets) is a workflow ENGINE
;;     contract change, and PROP-Dfr-001.S12 is explicit that those are cold
;;     cutovers, never live-refresh pickups. :forward by contrast is additive
;;     per-definition data. Cheap-now vs right-later.
;;   - JSON round-trip: qualified keywords must survive the wire as
;;     "vcs/branch" both ways. Card xf1vb records a namespace-dropping wire
;;     bug on the error path — the params path needs the same audit before
;;     any of this is trusted.
;;   - Last-write-wins is safe across STAGES because keys are qualified; two
;;     parallel sibling molecules writing the same shared key remains a race
;;     the vocabulary tier must warn about (shared keys should be written by
;;     the root/claim steps, not by parallel branches).
;;   - Every existing definition uses :req-un/unqualified keys; migrating a
;;     published definition's param contract is a break under the accretion
;;     rule. Pre-v1 loom definitions can ship qualified from day one.
