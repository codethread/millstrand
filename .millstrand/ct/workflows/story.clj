(ns ct.workflows.story
  "The module-form story workflow and its continuations (family `story`)."
  (:require [clojure.spec.alpha :as s]
            [millstrand.api.format.alpha :as format-alpha]
            [millhouse.spools.workflow :as workflow]
            [ct.workflows.support :as support]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::feature ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)

;; story: the module-form refactor workflow (family `story`)
;; ---------------------------------------------------------------------------

;; The story family's param contract. The continuations inherit the story run's
;; context, so they name the same required keys the parent does.
(s/def ::module ::non-blank-string)
(s/def ::reviewer-harness ::non-blank-string)

(s/def ::story-params (s/keys :req-un [::feature ::module ::worktree]
                              :opt-un [::card ::reviewer-harness]))
(s/def ::story-continuation-params (s/keys :req-un [::feature ::module ::worktree]
                                           :opt-un [::card ::reviewer-harness]))

(workflow/defworkflow story-fold
  "Fold a story split back into one story-ordered file.

  The continuation after the fold-decision checkpoint's `:fold-back` choice."
  {:entrypoints #{:continue}
   :param-spec ::story-continuation-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story fold: " module))
   {:attributes {"workflow/family" "story"}}
   (workflow/step :fold
                  (fn [{:keys [module]}] (str "Fold " module " into one story-ordered file"))
                  :self
                  :attributes {"workflow/action-ref" "story.fold"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Merge the concern files back into a single story-ordered
                                   |alpha.clj: publics with real bodies first, section-commented
                                   |private clusters in story order, leaf mechanics last, one
                                   |declare block up top. The public-surface tests must pass
                                   |unchanged through the fold. Then re-run the swift adversarial
                                   |pass: folding loses namespace aliases, so hunt name
                                   |collisions, misleading now-local names, surplus or stale
                                   |declare entries, and section comments that no longer match
                                   |their contents. Fix findings before completing."))})
   (workflow/step :finish-validate
                  (fn [{:keys [module]}] (str "Validate and hand " module " to landing"))
                  :self
                  :depends-on [:fold]
                  :attributes {"workflow/action-ref" "story.finish"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (format-alpha/reflow
                                  (format
                                   "|Delete `\"%s\"` from quality.api-form/pending when this is an api
                                    |conversion; run the focused cold tests and `make fmt-check lint
                                    |reflect-check docs-check`; `make api-docs` on docstring changes.
                                    |The full change-review roster runs once in the land run's
                                    |review gates: continue with `strand workflow start
                                    |<new-land-run-id> --workflow land --params <land-params-json>`.
                                    |The params name this run's existing feature id; the land run id is
                                    |new. Then close this run."
                                   module)))})))

(workflow/defworkflow story-keep
  "Keep a story split: the per-concern files are the deliverable.

  The continuation after the fold-decision checkpoint's `:keep-split` choice."
  {:entrypoints #{:continue}
   :param-spec ::story-continuation-params
   :defaults {}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story keep-split: " module))
   {:attributes {"workflow/family" "story"}}
   (workflow/step :finish-validate
                  (fn [{:keys [module]}] (str "Validate the split and hand " module " to landing"))
                  :self
                  :attributes {"workflow/action-ref" "story.finish"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (format-alpha/reflow
                                  (format
                                   "|The split stands: internal/<concern> files stay, named by meaning,
                                    |gated dependency rules apply (internal never requires alpha; only
                                    |own alpha/internal siblings/tests reach internal). Delete `\"%s\"`
                                    |from quality.api-form/pending when this is an api conversion;
                                    |focused cold tests; `make fmt-check lint reflect-check docs-check`;
                                    |`make api-docs` on docstring changes. The full roster runs in the
                                    |land run's review gates: `strand workflow start
                                    |<new-land-run-id> --workflow land --params <land-params-json>`.
                                    |The params name this run's existing feature id; the land run id is
                                    |new. Then close this run."
                                   module)))})))

(workflow/defworkflow story
  "Run the module-form STORY workflow (family \"story\").

  The forcing function for writing module code: identify the changed
  modules, make the overall changes, take an adversarial intent review
  (table stakes), then run the refactor wave per chunky module — write
  the per-concern split FIRST, test the public surface only, take a
  swift adversarial pass while the boundaries are visible, measure the
  folded size, and decide at a checkpoint: fold back to one
  story-ordered file (roughly 500 lines or less) or keep the split.
  Either branch validates and hands off to the land roster. One run
  covers one module wave; extra large modules take their own runs."
  {:entrypoints #{:start}
   :param-spec ::story-params
   ;; The engine cannot know which agent is driving, so the cross-vendor
   ;; invariant lives here: the pourer names a review seat OUTSIDE its own
   ;; model family, and this default is the one it overrides.
   :defaults {:reviewer-harness "sol-med"}}
  (workflow/workflow
   (fn [{:keys [module]}] (str "Story: " module))
   {:attributes {"workflow/family" "story"
                 "story/module" (fn [{:keys [module]}] module)}}
   (workflow/step :identify-modules
                  (fn [{:keys [feature]}] (str "Identify modules " feature " changes"))
                  :self
                  :attributes {"workflow/action-ref" "story.identify"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Name every module this feature touches and record the list
                                   |as a note on this step. For a form-conversion card this is
                                   |the card's module; for feature work it is the modules the
                                   |change will land in."))})
   (workflow/step :overall-changes
                  (fn [{:keys [feature]}] (str "Make the overall changes for " feature))
                  :self
                  :depends-on [:identify-modules]
                  :attributes {"workflow/action-ref" "story.changes"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Make the feature's behavior changes first - the refactor
                                   |wave comes after, over the changed result. A pure form
                                   |conversion records that there are none and completes."))})
   (workflow/gate :intent-review
                  (fn [{:keys [feature]}] (str "Adversarial intent review for " feature))
                  :subagent
                  :depends-on [:overall-changes]
                  :attributes {"workflow/action-ref" "story.intent-review"
                               "agent-run/harness" (fn [{:keys [reviewer-harness]}]
                                                     reviewer-harness)
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "agent-run/prompt"
                               (fn [{:keys [feature module]}]
                                 (str "Adversarial intent review for " feature ". "
                                      (format-alpha/reflow
                                       "|Read the diff (`git fetch origin && git diff
                                        |origin/main...HEAD` — three-dot merge-base
                                        |semantics, never two-dot) and the
                                        |feature intent (kanban card, proposal, or step
                                        |notes on this run). Challenge the INTENT, not
                                        |style: is the change the right change, does the
                                        |approach fit the specs it cites, what will age
                                        |badly for module")
                                      " `" module "`. "
                                      (format-alpha/reflow
                                       "|Your FINAL MESSAGE becomes the gate's outcome
                                        |notes: put the full findings there, verdict
                                        |first. Do not write to workflow strands. Never
                                        |the full roster lens - that runs once at land.")))})
   (workflow/step :resolve-intent
                  (fn [_] "Resolve intent-review findings")
                  :self
                  :depends-on [:intent-review]
                  :attributes {"workflow/action-ref" "story.resolve-intent"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Read the gate's review note and verdict. Fix or explicitly
                                   |adjudicate every finding - a reviewer run succeeds even
                                   |when it finds problems, so this step is where the findings
                                   |get faced. Record the resolution before completing."))})
   (workflow/step :identify-large
                  (fn [_] "Identify large-change modules for refactor waves")
                  :self
                  :depends-on [:resolve-intent]
                  :attributes {"workflow/action-ref" "story.identify-large"
                               "workflow/instruction"
                               (fn [{:keys [module]}]
                                 (str (format-alpha/reflow
                                       "|Separate LARGE module changes from small churn - only
                                        |large ones earn a wave. This run's wave covers")
                                      " `" module "`; "
                                      (format-alpha/reflow
                                       "|start one further `strand workflow start <id>
                                        |--workflow story` run per additional large module.
                                        |Record the classification.")))})
   (workflow/step :split-refactor
                  (fn [{:keys [module]}] (str "Write the per-concern split for " module))
                  :self
                  :depends-on [:identify-large]
                  :attributes {"workflow/action-ref" "story.split"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Write the split FIRST - the compiler exposes coupling that
                                   |imagination fudges. alpha.clj public bodies compose the
                                   |story (sequencing, fan-out, blocking joins visible; no
                                   |forwarding husks) over internal/<concern>.clj files named by
                                   |meaning. Follow the clojure skill's story-file section and
                                   |SPEC-003.C19a. Delegating this step to a tracked worker run
                                   |is encouraged; size it to one worker context."))})
   (workflow/step :public-tests
                  (fn [{:keys [module]}] (str "Test " module " through its public surface"))
                  :self
                  :depends-on [:split-refactor]
                  :attributes {"workflow/action-ref" "story.tests"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Write or keep tests against the public surface only - they
                                   |are the behavior lock that survives any later fold. Cold
                                   |run green before completing."))})
   (workflow/gate :split-review
                  (fn [{:keys [module]}] (str "Swift adversarial review of the " module " split"))
                  :subagent
                  :depends-on [:public-tests]
                  :attributes {"workflow/action-ref" "story.split-review"
                               "agent-run/harness" (fn [{:keys [reviewer-harness]}]
                                                     reviewer-harness)
                               "agent-run/cwd" (fn [{:keys [worktree]}] worktree)
                               "agent-run/prompt"
                               (fn [{:keys [module]}]
                                 (str "Adversarial review of the fresh per-concern split"
                                      " of module `" module "` "
                                      (format-alpha/reflow
                                       "|(diff: `git fetch origin && git diff
                                        |origin/main...HEAD`, three-dot merge-base
                                        |semantics, never two-dot), while the concern
                                        |boundaries are still visible: bad or arbitrary
                                        |boundaries, forwarding husks in alpha, story
                                        |helpers exiled from reading reach, tests leaning
                                        |on internals instead of the public surface,
                                        |dependency-rule breaches. Your FINAL MESSAGE
                                        |becomes the gate's outcome notes: full findings
                                        |there, verdict first. Do not write to workflow
                                        |strands.")))})
   (workflow/step :resolve-split
                  (fn [_] "Resolve split-review findings")
                  :self
                  :depends-on [:split-review]
                  :attributes {"workflow/action-ref" "story.resolve-split"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Read the gate's review note and verdict; fix or explicitly
                                   |adjudicate every finding and record the resolution before
                                   |completing."))})
   (workflow/step :measure
                  (fn [{:keys [module]}] (str "Measure the folded size of " module))
                  :self
                  :depends-on [:resolve-split]
                  :attributes {"workflow/action-ref" "story.measure"
                               "workflow/instruction"
                               (fn [_]
                                 (format-alpha/reflow
                                  "|Approximate the single-file fold: total content lines across
                                   |alpha and concern files minus per-file ns overhead. Record
                                   |the number in notes; roughly 500 lines is the tipping
                                   |point."))})
   (workflow/checkpoint :fold-decision
                        (fn [{:keys [module]}] (str "Fold " module " back, or keep the split?"))
                        :depends-on [:measure]
                        :kind :agent
                        :choices [{:key :fold-back
                                   :label "Fold back to one file"
                                   :description
                                   (format-alpha/reflow
                                    "|The measured fold fits the rough 500-line budget: merge
                                     |back into a single story-ordered alpha.clj and re-verify.")
                                   :next :story-fold}
                                  {:key :keep-split
                                   :label "Keep the split"
                                   :description
                                   (format-alpha/reflow
                                    "|The module outgrows the budget: the per-concern
                                     |internal/<concern> files are the deliverable.")
                                   :next :story-keep}]
                        :attributes {"workflow/decision-point" "story-fold-decided"})))

;; ---------------------------------------------------------------------------
