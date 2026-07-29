;; SPIKE — skein-src's .skein/init.clj after the lift, with the usage code
;; written INLINE for shape. Liberty taken: a real init.clj stages module
;; declarations only (no imperative effects, DELTA-OlrDrt-001.CC1); everything
;; below the module! block would live in the devcycles_local.clj :file module.
;;
;; Gone from this workspace: workflows.clj (land/fix/explore/spool-bump,
;; ~1800 lines), kanban_tracker.clj, attention.clj, config.clj's queries.
;; Staying: harnesses.clj, reviewers.clj, nvd_scan.clj, module_adapters.clj,
;; scripts/, dash.
;;
;; spools.edn gains:
;;   codethread/devcycles {:git/url "…/devcycles.loom.git" :git/tag "v1"
;;                         :git/sha "…" :roots {ct.spools/devcycles "."}}
;;   (dev override: spools.local.edn
;;     {codethread/devcycles {:local/root "../devcycles.loom" :claims "v1"}})
(require '[clojure.java.io :as io]
         '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime]
         '[ct.spools.devcycles.workflows :as devcycles]
         '[skein.spools.workflow :as workflow])

(def runtime (current/runtime))

;; --- shared dev cycle, one module per concern --------------------------------
(runtime/module! runtime :devcycles/workflows
                 {:ns 'ct.spools.devcycles.workflows
                  :spools ['ct.spools/devcycles 'skein.spools/workflow 'codethread/kanban]
                  :after [:skein/spools-workflow :skein/spools-kanban]
                  :required? true})
(runtime/module! runtime :devcycles/tracker
                 {:ns 'ct.spools.devcycles.tracker
                  :spools ['ct.spools/devcycles 'codethread/kanban 'codethread/devflow]
                  :after [:skein/spools-kanban :skein/spools-devflow]
                  :required? true})
(runtime/module! runtime :devcycles/attention
                 {:ns 'ct.spools.devcycles.attention
                  :spools ['ct.spools/devcycles 'skein.spools/chime]
                  :after [:skein/spools-chime]
                  :required? true})

;; =============================================================================
;; Below: the repo-local usage (really devcycles_local.clj, ordered
;; :after [:devcycles/workflows :harnesses]).
;; =============================================================================

;; 1. This repo's heavyweight validation style: a second :call target for the
;; fix :validate defer, beside the loom's docs-check.
(workflow/defworkflow quality-suite
  "Full locked suite in the fix worktree."
  {:entrypoints #{:call}
   :param-spec ::devcycles/fix-params}
  (workflow/workflow
   (fn [{:keys [branch]}] (str "Quality suite: " branch))
   (workflow/gate :suite "Locked full suite" :shell
                  :attributes {"shell/argv" ["flock" "-w" "3600" "/tmp/skein-test.lock"
                                             "clojure" "-M:test"]
                               "shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "workflow/instruction" "Terse: green suite closes this."})))

;; 2. Widen the fix validation allowlist: the driving worker now picks the
;; style per run — loom docs-check for a doc fix, the full suite otherwise.
;; This SHADOWS the loom's :fix registration from the workspace layer.
;; GAP (sharpened): publication requires the shadow to declare :overrides
;; #{:fix}. The blessed collect-entry! already accepts {:override? true}
;; (alpha.clj 4-arity, closed opts spec) but defworkflow calls the 3-arity and
;; exposes no opt — a thin workflow-spool accretion closes it today.
;; PROP-Auf-001.P6.2/AC2 lands the same intent across every authoring form in
;; its first stage (Draft, unsigned); either route works, the accretion is
;; smaller and unblocks this feature.
(workflow/defworkflow fix
  "fix with this repo's validation styles."
  {:entrypoints #{:start}
   :param-spec ::devcycles/fix-params}
  (workflow/bind-defers devcycles/fix-template
                        {:validate #{:docs-check :quality-suite}}))

;; 3. Land keeps scripts local: shadow :land, overriding only :defaults so the
;; world's bindings ride under every run's params — no per-start ceremony, no
;; engine change. Script TEXT is embedded at load time (sh-gate precedent), so
;; the file needs to exist only here, at config load.
(def ^:private ci-watch-script (slurp (io/file ".skein/scripts/feature-ci-watch.sh")))

(workflow/defworkflow land
  "land with this repo's CI watch and roster."
  {:entrypoints #{:start}
   :param-spec ::devcycles/land-params
   :defaults {:mainline "main"
              :roster "change-review"
              :bindings {"land.ci.green"
                         {"shell/argv" ["sh" "-c" ci-watch-script "land-ci-watch"]}}}}
  (workflow/bind-defers devcycles/land-template
                        ;; warm-REPL teardown target elided; registered beside
                        ;; quality-suite above in the real file.
                        {:cleanup-extras #{:no-extra-cleanup :warm-repl-teardown}}))

;; 4. Cutover shim: gates poured BEFORE the lift persisted the code/fn symbol
;; "workflows/main-ci-watch". Keep that name resolving until no active land
;; run remains, then delete these two lines.
(intern (create-ns 'workflows) 'main-ci-watch devcycles/main-ci-watch)
