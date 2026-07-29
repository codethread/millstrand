;; SPIKE — agent-harness.spool consuming a subset of devcycles.loom.
;; It keeps its own feature-iteration workflow, runs no devflow, and adopts
;; fix/land + attention. No tracker module (nothing to track), no queries yet.

;; spools.edn gains the same codethread/devcycles entry as skein-src, plus the
;; prerequisites it does not have today: skein.spools/workflow already pinned;
;; codethread/devflow NOT added — the tracker module is simply not declared, and
;; nothing else requires it. (:requires floors only bite for roots you approve.)

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

;; This repo's style: validation is `make quality`, mainline is main, no roster.
(runtime/module! runtime :devcycles/local
                 {:file "config/devcycles_local.clj"
                  :spools ['ct.spools/devcycles]
                  :after [:devcycles/workflows]
                  :required? true})
;; config/devcycles_local.clj contains, in full:
;;   1. A make-quality validation target (:call entry, shell gate ["make" "quality"]).
;;   2. A shadow of :fix re-binding {:validate #{:make-quality}}.
;;   3. Nothing for land: the loom's gh-based defaults and {:roster "…"} params
;;      already fit — the whole point of commands-as-bindings with sane defaults.
;; GAP: both consumers write the same three-part local file. If that shape
;; repeats identically in every world, the "shadow + rebind" ceremony is itself
;; a missing primitive — e.g. bind-defers accepting world-level additions
;; without re-registering the definition.
