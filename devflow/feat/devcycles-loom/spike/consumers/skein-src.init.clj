;; SPIKE — what skein-src's .skein/init.clj gains/loses after the lift.
;; workflows.clj, kanban_tracker.clj, attention.clj and the query surface of
;; config.clj are gone; harnesses/reviewers/nvd_scan/module_adapters/scripts stay.

;; spools.edn gains:
;;   codethread/devcycles {:git/url "…/devcycles.loom.git" :git/tag "v1" :git/sha "…"
;;                         :roots {ct.spools/devcycles "."}}
;;   (dev: spools.local.edn {codethread/devcycles {:local/root "../devcycles.loom" :claims "v1"}})

;; --- shared dev cycle, module per concern ------------------------------------
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

;; --- repo policy over the shared definitions ---------------------------------
;; devcycles_local.clj (a :file module) is the ONLY new repo-local surface:
(runtime/module! runtime :devcycles/local
                 {:file "devcycles_local.clj"
                  :spools ['ct.spools/devcycles]
                  :after [:devcycles/workflows :harnesses]
                  :required? true})
;; devcycles_local.clj contains, in full:
;;   1. A quality-suite validation target for the fix defer, :call-entry,
;;      gating on this repo's suite.
;;   2. A shadow of :fix with declared :overrides, re-binding
;;      {:validate #{:docs-check :quality-suite}} — run-time style choice
;;      between loom docs-check and the full local suite.
;;   3. Land bindings passed at start time (scripts stay in .skein/scripts):
;;        strand workflow start <id> --workflow land --params '{"branch":…,
;;          "bindings":{"land.ci.green":{"shell/argv":["sh","-c","<script text>", …]}}}'
;;      GAP: script TEXT in params is ugly and shows in every `workflow show`;
;;      a bindings registry keyed by action-ref (world-level, not per-run)
;;      would let this be one registration instead of per-start ceremony.
;;   4. The cutover shim: (def main-ci-watch devcycles.workflows/main-ci-watch)
;;      under a `workflows` ns alias so gates poured pre-lift still resolve
;;      "workflows/main-ci-watch"; deleted when no active land run remains.
