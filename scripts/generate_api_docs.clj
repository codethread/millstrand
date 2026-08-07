(require '[quickdoc.api :as quickdoc])

(def github-repo "https://github.com/codethread/millstrand")
(def git-branch "main")

(def spool-docs
  [{:name "batteries" :source "spools/batteries/src/millstrand/spools/batteries.clj" :outfile "spools/batteries.api.md"}
   {:name "workflow" :source "spools/workflow/src/millstrand/spools/workflow.clj" :outfile "spools/workflow.api.md"}
   {:name "guild" :source "examples/guild/src/skein/examples/guild.clj" :outfile "examples/guild.api.md"}
   {:name "unsafe-text-search" :source "spools/unsafe-text-search/src/millstrand/spools/unsafe_text_search.clj" :outfile "spools/unsafe-text-search.api.md"}
   {:name "shell" :source "spools/workflow/src/millstrand/spools/executors/shell.clj" :outfile "spools/executors/shell.api.md"}
   {:name "code" :source "spools/workflow/src/millstrand/spools/executors/code.clj" :outfile "spools/executors/code.api.md"}
   {:name "chime" :source "spools/chime/src/millstrand/spools/chime.clj" :outfile "spools/chime.api.md"}
   {:name "cron" :source "spools/cron/src/millstrand/spools/cron.clj" :outfile "spools/cron.api.md"}])

;; The blessed spool-facing API tier (SPEC-005.C2). Generated reference only —
;; the behavior contracts stay in the root specs.
(def alpha-api-docs
  (concat
   (for [nm ["batch" "cli" "clock" "current" "errors" "events" "format" "graph" "hooks"
             "lifecycle" "notes" "patterns" "peers" "registry" "relations" "return-shape"
             "runtime" "scheduler" "millstrand" "spec" "spool" "vocab" "weaver"]]
     {:name nm
      :source (str "src/millstrand/api/" (if (= nm "return-shape") "return_shape" nm) "/alpha.clj")
      :outfile (str "docs/api/" nm ".api.md")})
   [{:name "runtime-glossary"
     :source "src/millstrand/api/runtime/glossary/alpha.clj"
     :outfile "docs/api/runtime-glossary.api.md"}
    {:name "runtime-help-transform"
     :source "src/millstrand/api/runtime/help_transform/alpha.clj"
     :outfile "docs/api/runtime-help-transform.api.md"}
    {:name "test"
     :source "src/millstrand/test/alpha.clj"
     :outfile "docs/api/test.api.md"}]))

(doseq [{:keys [source outfile]} (concat spool-docs alpha-api-docs)]
  (quickdoc/quickdoc
   {:source-paths [source]
    :outfile outfile
    :github/repo github-repo
    :git/branch git-branch
    ;; quickdoc v0.2.6 links backticked var-shaped tokens even when they name
    ;; private helpers intentionally omitted from public API docs. There is no
    ;; public-only link filter, and including private vars would publish
    ;; internals, so use the wikilink detector; these docstrings use backticks,
    ;; which remain code-styled text instead of becoming dead links.
    :var-pattern :wikilinks
    ;; Suppress quickdoc's in-body "# Table of contents". It emits a leading H1
    ;; before the namespace H1, and mkdocs-material's right-hand TOC collapses to
    ;; the first H1's child headings — which for that TOC H1 are none — leaving
    ;; API pages with an empty sidebar TOC. Dropping it makes the namespace the
    ;; sole leading H1 so the sidebar lists every var, matching the other docs.
    :toc false}))

(System/exit 0)
