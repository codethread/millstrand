;; SPIKE — agent-harness.spool's .skein/init.clj consuming a devcycles subset,
;; usage code INLINE for shape (same liberty as skein-src.init.clj: really a
;; :file module ordered :after [:devcycles/workflows]).
;;
;; This world keeps its own feature-iteration workflow and runs no devflow, so
;; it takes fix/land + attention only: no tracker module (nothing to track),
;; and codethread/devflow is simply never approved — :requires floors bite only
;; for roots a consumer approves.
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime]
         '[ct.spools.devcycles.workflows :as devcycles]
         '[skein.spools.workflow :as workflow])

(def runtime (current/runtime))

;; --- shared dev cycle, subset ------------------------------------------------
(runtime/module! runtime :devcycles/workflows
                 {:ns 'ct.spools.devcycles.workflows
                  :spools ['ct.spools/devcycles 'skein.spools/workflow 'codethread/kanban]
                  :after [:workflow :kanban]
                  :required? true})
(runtime/module! runtime :devcycles/attention
                 {:ns 'ct.spools.devcycles.attention
                  :spools ['ct.spools/devcycles 'skein.spools/chime]
                  :after [:chime]
                  :required? true})

;; =============================================================================
;; Below: this repo's usage (really config/devcycles_local.clj).
;; =============================================================================

;; 1. This repo's one validation style: make quality.
(workflow/defworkflow make-quality
  "make quality in the fix worktree."
  {:entrypoints #{:call}
   :param-spec ::devcycles/fix-params}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Quality: " branch))
   (workflow/gate :quality "make quality" :shell
                  :attributes {"shell/argv" ["make" "quality"]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "workflow/instruction" "Terse: green closes this."})))

;; 2. Shadow :fix, binding validation to it (same :override? GAP as skein-src:
;; blessed collector supports the intent, defworkflow doesn't forward it).
(workflow/defworkflow fix
  "fix validated by make quality."
  {:entrypoints #{:start}
   :param-spec ::devcycles/fix-params}
  (workflow/bind-defers devcycles/fix-template {:validate #{:make-quality}}))

;; 3. Land: NOTHING. The loom's gh-based defaults, mainline "main", and no
;; roster step customization fit this repo as shipped — the payoff of
;; commands-as-bindings with sane defaults. A run that wants a roster passes
;; {:roster "…"} in params; the world registers no shadow.
