(ns workflows-spool-bump
  "The third-party spool bump workflow (family `spool-bump`)."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.format.alpha :as format-alpha]
            [skein.spools.workflow :as workflow]
            [workflows-support :as support]))

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (support/non-blank-string? v))

(s/def ::non-blank-string non-blank-string?)

(s/def ::worktree ::non-blank-string)
(s/def ::branch ::non-blank-string)

;; spool-bump: third-party spool bump, landing, adoption, and cutover
;; ---------------------------------------------------------------------------

(s/def ::family ::non-blank-string)
(s/def ::direct-user-request boolean?)

(defn- spool-version?
  "Return true for the latest release selector or an annotated vN marker."
  [value]
  (and (string? value)
       (or (= "latest" value)
           (boolean (re-matches #"v[1-9][0-9]*" value)))))

(defn- unique-bump-families?
  "Return true when each bump record names a different spool family."
  [bumps]
  (let [families (map :family bumps)]
    (= (count families) (count (distinct families)))))

(s/def ::version spool-version?)
(s/def ::bump (s/keys :req-un [::family ::version]))
(s/def ::bumps
  (s/and (s/coll-of ::bump :kind vector? :min-count 1)
         unique-bump-families?))

(s/def ::spool-bump-params
  (s/keys :req-un [::bumps ::branch ::worktree ::direct-user-request]))

(workflow/defworkflow spool-bump
  "Bump, validate, land, and adopt a third-party spool release.

  The workflow runs the bump against the feature worktree's selected workspace,
  validates the changed world, lands the PR after green CI, pulls canonical
  `main`, and refreshes the live runtime. A Git SHA change normally leaves a
  pending generation. The final stop/start step exists only when the run
  truthfully records that the workflow was requested directly by the user;
  indirect maintenance runs end with a pending-generation handover."
  {:entrypoints #{:start}
   :param-spec ::spool-bump-params
   :defaults {}
   :example {:bumps [{:family "codethread/kanban" :version "latest"}
                     {:family "codethread/devflow" :version "v12"}]
             :branch "bump-kanban-devflow"
             :worktree "/abs/path/to/skein-src__bump-kanban-devflow"
             :direct-user-request false}
   :param-docs {:bumps
                (format-alpha/reflow
                 "|One {family, version} record per spool family; version is
                  |latest or an annotated vN release marker.")
                :branch "Bump branch to create for the change."
                :worktree
                (format-alpha/reflow
                 "|Absolute feature worktree path for the branch; its .skein
                  |directory is the selected workspace the bump runs against.")
                :direct-user-request
                (format-alpha/reflow
                 "|True only when the user asked for this bump directly; gates
                  |the final canonical weaver stop/start step.")}}
  (workflow/workflow
   (fn [{:keys [bumps]}]
     (str "Spool bump: " (str/join ", " (map :family bumps))))
   {:attributes {"workflow/family" "spool-bump"
                 "spool-bump/requests" (fn [{:keys [bumps]}] bumps)
                 "spool-bump/direct-user-request"
                 (fn [{:keys [direct-user-request]}] direct-user-request)}}
   (workflow/step :create-branch
                  (fn [{:keys [branch]}] (str "Create bump branch " branch))
                  :self
                  :attributes
                  {"workflow/action-ref" "spool-bump.branch.create"
                   "workflow/instruction"
                   (fn [{:keys [branch worktree]}]
                     (format-alpha/reflow
                      (format
                       "|From the canonical checkout, create branch `%s` in worktree
                        |`%s`. Refuse an unrelated existing branch, a dirty target, or
                        |any operation that would discard work. The new worktree's
                        |`.skein` directory is the selected workspace for the bump."
                       branch worktree)))})
   (workflow/step :start-bump-world
                  "Start the feature worktree weaver"
                  :self
                  :depends-on [:create-branch]
                  :attributes
                  {"workflow/action-ref" "spool-bump.bump-world.start"
                   "workflow/instruction"
                   (fn [{:keys [worktree]}]
                     (format-alpha/reflow
                      (format
                       "|Run `mill weaver start --workspace %s/.skein` before invoking
                        |`strand --workspace`: every selected workspace needs its own
                        |running weaver. Record the returned PID and config directory so
                        |this workflow can stop exactly the weaver it started."
                       worktree)))})
   (workflow/step :bump-spool
                  (fn [{:keys [item]}]
                    (str "Bump third-party spool " (:family item)
                         " to " (:version item)))
                  :self
                  :depends-on [:start-bump-world]
                  :loop {:each :bumps :chain true}
                  :attributes
                  {"workflow/action-ref" "spool-bump.coordinate.bump"
                   "workflow/instruction"
                   (fn [{:keys [item worktree]}]
                     (let [{:keys [family version]} item]
                       (format-alpha/reflow
                        (format
                         "|In `%s`, run `strand --workspace %s/.skein spool bump %s%s`.
                          |The explicit workspace is mandatory: the bump must update the
                          |feature branch's `.skein/spools.edn`, never the canonical
                          |coordination world. Record the old and new tag and peeled SHA."
                         worktree worktree family
                         (if (= "latest" version)
                           ""
                           (str " --to " version))))))})
   (workflow/step :stop-bump-world
                  "Stop the feature worktree weaver"
                  :self
                  :depends-on [:bump-spool]
                  :attributes
                  {"workflow/action-ref" "spool-bump.bump-world.stop"
                   "workflow/instruction"
                   (fn [{:keys [worktree]}]
                     (format-alpha/reflow
                      (format
                       "|After every requested bump has completed, run
                        |`mill weaver stop --workspace %s/.skein`. Confirm the stopped
                        |PID matches the weaver recorded by the start step; never use a
                        |pattern kill or stop the canonical weaver."
                       worktree)))})
   (workflow/step :create-test-world
                  "Create an isolated world from the bumped branch"
                  :self
                  :depends-on [:stop-bump-world]
                  :attributes
                  {"workflow/action-ref" "spool-bump.world.create"
                   "workflow/instruction"
                   (fn [{:keys [worktree]}]
                     (format-alpha/reflow
                      (format
                       "|Create a disposable directory with `validation_ws=$(mktemp -d)`.
                        |Copy the feature checkout from `%s/` into
                        |`${validation_ws:?}/` with its relative layout intact but
                        |without `.git`, then run `mill weaver start --workspace
                        |${validation_ws:?}/.skein`. Record the exact directory and PID.
                        |Never target the canonical workspace for this validation."
                       worktree)))})
   (workflow/step :smoke-test
                  (fn [{:keys [bumps]}]
                    (str "Smoke-test bumped spools "
                         (str/join ", " (map :family bumps))))
                  :self
                  :depends-on [:create-test-world]
                  :attributes
                  {"workflow/action-ref" "spool-bump.world.smoke"
                   "workflow/instruction"
                   (fn [{:keys [bumps]}]
                     (format-alpha/reflow
                      (format
                       "|In the disposable world, run `strand --workspace
                        |${validation_ws:?}/.skein spool status` and prove the effective
                        |tag and SHA for `%s` match `.skein/spools.edn` with no pending
                        |generation. Use `prime` and `help` to discover each bumped
                        |spool's available smoke surface, run the strongest non-destructive
                        |command, and record the result. Stop the disposable weaver by its
                        |workspace afterward. A failed available smoke check blocks landing."
                       (str/join "`, `" (map :family bumps)))))})
   (workflow/step :prepare-change
                  (fn [{:keys [bumps]}]
                    (str "Prepare bump change for "
                         (str/join ", " (map :family bumps))))
                  :self
                  :depends-on [:smoke-test]
                  :attributes
                  {"workflow/action-ref" "spool-bump.change.prepare"
                   "workflow/instruction"
                   (format-alpha/reflow
                    "|Inspect the diff and confirm that `.skein/spools.edn` changes only
                     |the requested family tags and peeled SHAs unless a bump deliberately
                     |opts into a new root. Remove generated SQLite and runtime metadata,
                     |run the relevant repository checks, and commit the reviewed change.")})
   (workflow/step :raise-pr
                  (fn [{:keys [branch]}] (str "Raise PR for " branch))
                  :self
                  :depends-on [:prepare-change]
                  :attributes
                  {"workflow/action-ref" "spool-bump.pr.raise"
                   "workflow/instruction"
                   (fn [{:keys [branch]}]
                     (format-alpha/reflow
                      (format
                       "|Push `%s` and open a PR against `main`. Reuse an existing open
                        |PR for the branch rather than creating a duplicate. Record its
                        |number and URL on this step."
                       branch)))})
   (workflow/gate :wait-for-green
                  (fn [{:keys [branch]}] (str "Wait for green CI on " branch))
                  :shell
                  :depends-on [:raise-pr]
                  :attributes
                  {"workflow/action-ref" "spool-bump.pr.green"
                   "shell/argv" (fn [{:keys [branch]}]
                                  (support/sh-gate support/feature-ci-watch-script
                                                   "spool-bump-ci-watch" branch "180" "5"))
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 5400
                   "workflow/instruction"
                   (fn [{:keys [branch]}]
                     (format-alpha/reflow
                      (format
                       "|Machine gate: wait for GitHub to register checks at `%s` HEAD,
                        |then watch them to completion. Red or missing checks leave the
                        |gate stalled with `gate/error`; fix the branch and clear that
                        |attribute to retry. Do not merge a PR that is behind `main`."
                       branch)))})
   (workflow/step :merge
                  (fn [{:keys [branch]}] (str "Merge green PR for " branch))
                  :self
                  :depends-on [:wait-for-green]
                  :attributes
                  {"workflow/action-ref" "spool-bump.pr.merge"
                   "workflow/instruction"
                   (fn [{:keys [branch]}]
                     (format-alpha/reflow
                      (format
                       "|Confirm the PR for `%s` is still green and up to date at its
                        |current HEAD, then squash-merge it. If `main` advanced, rebase,
                        |push, and re-establish green CI before merging."
                       branch)))})
   (workflow/gate :pull-main
                  "Fast-forward canonical main after the spool bump merges"
                  :shell
                  :depends-on [:merge]
                  :attributes
                  {"workflow/action-ref" "spool-bump.main.pull"
                   "shell/argv" ["sh" "-c" support/land-pull-main-script]
                   "shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 300
                   "workflow/instruction"
                   (format-alpha/reflow
                    "|Machine gate: locate the canonical checkout through the shared Git
                     |directory, verify that it is on `main`, and fast-forward it from
                     |`origin/main`. Never stash, reset, or discard local work.")})
   (workflow/step :cleanup
                  "Clean up the landed bump worlds and branch"
                  :self
                  :depends-on [:pull-main]
                  :attributes
                  {"workflow/action-ref" "spool-bump.resources.cleanup"
                   "workflow/instruction"
                   (fn [{:keys [branch worktree]}]
                     (format-alpha/reflow
                      (format
                       "|Stop any still-running weavers created by this run using their
                        |recorded workspace or PID. Remove only the recorded disposable
                        |workspace. Fetch and prune `origin`, confirm PR merge state, then
                        |remove worktree `%s`, remote branch `%s`, and its local branch
                        |using exact targets. Leave shared or uncertain resources alone."
                       worktree branch)))})
   (workflow/step :reload
                  "Refresh the canonical world and record generation state"
                  :self
                  :depends-on [:cleanup]
                  :attributes
                  {"workflow/action-ref" "spool-bump.runtime.refresh"
                   "workflow/instruction"
                   (format-alpha/reflow
                    "|Run `(runtime/refresh! (current/runtime))` in the canonical
                     |weaver. A changed Git SHA is non-additive, so refresh will almost
                     |always report a pending generation instead of adopting the code in
                     |the current JVM. Record the complete refresh result and confirm the
                     |pending coordinate before continuing.")})
   (workflow/step :cutover
                  "Cut over the canonical weaver to the pending generation"
                  :self
                  :depends-on [:reload]
                  :condition [:= :direct-user-request true]
                  :attributes
                  {"workflow/action-ref" "spool-bump.runtime.cutover"
                   "workflow/instruction"
                   (format-alpha/reflow
                    "|This step exists only because the workflow run records a direct
                     |user request for cutover. From outside the weaver process, stop the
                     |canonical weaver and start it again. Reconnect, verify startup, and
                     |confirm `strand spool status` reports the bumped coordinate as
                     |adopted with no pending generation. Never infer this authority for
                     |agent-initiated sibling-spool maintenance.")})
   (workflow/step :handover-pending-generation
                  "Hand over the pending weaver generation"
                  :self
                  :depends-on [:reload]
                  :condition [:= :direct-user-request false]
                  :attributes
                  {"workflow/action-ref" "spool-bump.runtime.handover"
                   "workflow/instruction"
                   (format-alpha/reflow
                    "|This run was not requested directly by the user. Do not stop or
                     |restart the canonical weaver. Record the pending-generation refresh
                     |result and hand over that a direct user request is required before
                     |cutover.")})))

;; ---------------------------------------------------------------------------
