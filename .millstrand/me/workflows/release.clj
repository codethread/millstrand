(ns me.workflows.release
  "The release workflow for versioned Millstrand publication."
  (:require [clojure.spec.alpha :as s]
            [millhouse.spools.land.support :as land-support]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]
            [me.workflows.support :as support]
            [me.workflows.release-evidence]
            [me.workflows.handoff :as handoff]))

(defn- semantic-version?
  "Return true when v is a MAJOR.MINOR.PATCH version string."
  [v]
  (boolean (and (string? v)
                (re-matches #"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)" v))))

(defn- absolute-path?
  "Return true when v is an absolute filesystem path."
  [v]
  (boolean (and (support/non-blank-string? v)
                (.isAbsolute (java.io.File. v)))))

(s/def ::version semantic-version?)
(s/def ::worktree absolute-path?)
(s/def ::branch (s/and support/non-blank-string? #(not= "main" %)))
(s/def ::release-params (s/keys :req-un [::version ::worktree ::branch]))

(def ^:private release-preflight-script
  (support/script "release-preflight.sh"))

(def ^:private release-identity-script
  (support/script "release-identity.sh"))

(workflow/defworkflow release
  "Prepare a two-commit candidate; publish only after accepted landing and approval."
  {:entrypoints #{:start}
   :param-spec ::release-params
   :defaults {}
   :example {:version "0.5.2"
             :worktree "/abs/path/to/millstrand"
             :branch "release/0.5.2"}}
  (workflow/workflow
   (fn [{:keys [version]}] (str "Release Millstrand " version))
   {:attributes {"workflow/family" "release"}}
   (workflow/gate :preflight
                  (fn [{:keys [version]}] (str "Check release preconditions for " version))
                  :shell
                  :attributes
                  {"shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 120
                   "shell/argv" (fn [{:keys [branch]}]
                                  (land-support/sh-gate release-preflight-script
                                                        "release-preflight" branch))}
                  (format-alpha/prose
                   "
                   Require a clean ordinary candidate branch containing current origin/main.
                   Never edit or push main. Use the card's claimed branch/worktree.
                   "))
   (workflow/step :bump-version
                  (fn [{:keys [version]}] (str "Bump VERSION to " version))
                  :self
                  :depends-on [:preflight]
                  (fn [{:keys [version]}]
                    (format-alpha/prose
                     "
                     On the clean candidate branch, write `{version}` plus a trailing
                     newline to `VERSION`. Leave the change uncommitted for the
                     changelog step.
                     "
                     {:version version})))
   (workflow/step :update-changelog
                  (fn [{:keys [version]}] (str "Write changelog for " version))
                  :self
                  :depends-on [:bump-version]
                  (fn [{:keys [version]}]
                    (format-alpha/prose
                     "
                     Add a dated `{version}` section at the top of
                     `CHANGELOG.md`. Summarize the release in concrete,
                     user-facing bullets from every change since the previous
                     release.

                     Run `make version-check`, then commit `VERSION` and
                     `CHANGELOG.md` together as `chore: release {version}`.
                     Record the resulting full commit SHA in a note; the
                     Homebrew formula pins this commit.
                     "
                     {:version version})))
   (workflow/gate :quality
                  (fn [{:keys [version]}] (str "Run release quality for " version))
                  :shell
                  :depends-on [:update-changelog]
                  :attributes {"shell/cwd" (fn [{:keys [worktree]}] worktree)
                               "shell/timeout-secs" 7200
                               "shell/argv" ["sh" ".millstrand/land-quality.sh"]}
                  (format-alpha/prose
                   "
                   Run the repository-owned release quality contract. Resolve
                   any quality repairs before rebuilding the candidate: a commit
                   containing only VERSION and CHANGELOG.md, immediately followed
                   by a formula-only commit pinned to that release commit. Do not
                   leave repair commits between them.

                   Re-run quality and identity validation for the rebuilt pair,
                   refresh the recorded release SHA, and refreeze both candidate
                   identities before approval. Clear `gate/error` only when the
                   corrected candidate is ready for its check; old receipts do
                   not validate rebuilt commits.
                   "))
   (workflow/step :pin-homebrew
                  (fn [{:keys [version]}] (str "Pin Homebrew to " version))
                  :self
                  :depends-on [:quality]
                  (fn [{:keys [version]}]
                    (format-alpha/prose
                     "
                     Update `Formula/millstrand.rb` to version `{version}`. Set
                     its Git revision and every expected build id to the full
                     release commit SHA recorded by the previous step, and
                     update its changelog assertion to `{version}`.

                     Commit only the formula as
                     `chore: pin Homebrew to {version}`. The formula deliberately
                     pins the release commit, not this later formula-only commit.
                     "
                     {:version version})))
   (workflow/gate :build-identity
                  (fn [{:keys [version]}] (str "Verify release identity " version))
                  :shell
                  :depends-on [:pin-homebrew]
                  :attributes
                  {"shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 1200
                   "shell/argv"
                   (fn [{:keys [version branch]}]
                     (land-support/sh-gate release-identity-script
                                           "release-identity"
                                           version branch))}
                  (format-alpha/prose
                   "
                   Build both CLIs and require their reported versions to match
                   the release. The worktree must remain clean.
                   "))
   (workflow/gate
    :freeze-candidate "Record the exact candidate" :code :depends-on [:build-identity]
    :attributes {"code/fn" "me.workflows.release-evidence/candidate!"
                 "code/params" #(select-keys % [:version :branch :worktree])}
    "Record version, release commit and formula commit before any landing or approval.")
   (workflow/gate
    :landing-policy "Resolve candidate-preserving shared landing" :human
    :depends-on [:freeze-candidate]
    (format-alpha/prose
     "
       STOP for the repository owner: shared Land currently squashes, whereas
       this release must preserve the adjacent release and formula commits and
       the formula's exact pin. Do not invent a direct-main push exception.
       Record the policy decision and accepted shared landing run/PR receipt
       here. Leave this boundary open until an authorized shared path preserves
       the candidate on origin/main. No publication or cleanup while unresolved.

       The authorized route must retain custody of the candidate checkout through
       publication and remote verification: publish! reads Git there. Ordinary
       shared Land cleanup must not delete it. Shared Land does not yet provide
       this candidate-preserving landing and checkout-retention contract.
     "))
   (workflow/gate
    :approve "Approve the exact landed release candidate" :human
    :depends-on [:landing-policy]
    (format-alpha/prose
     "
       Read freeze-candidate's code/result. Ask the user to approve that exact
       version and candidate SHA for publication. Record release/approval here
       with version, head and authorization (the actual conversation reference).
       A human gate label or actor string is not authorization. A changed
       candidate needs new validation and approval; do not carry approval forward.
     "))
   (workflow/gate
    :publish "Publish the approved annotated tag" :code :depends-on [:approve]
    :attributes {"code/fn" "me.workflows.release-evidence/publish!"
                 "delivery/key" #(handoff/key-for "release" %)
                 "code/params" #(assoc (select-keys % [:version :branch :worktree])
                                       :key (handoff/key-for "release" %))}
    (format-alpha/prose
     "
       Verify exact approval and candidate ancestry on origin/main, then push
       only the annotated version tag. Never push main or move an existing tag.
       An uncertain push requires inspecting the remote receipt, not blind replay.
     "))
   (workflow/gate
    :verify-remote "Verify the remote release receipt" :code :depends-on [:publish]
    :attributes {"code/fn" "me.workflows.release-evidence/verify-remote!"
                 "delivery/key" #(handoff/key-for "release" %)
                 "code/params" #(assoc (select-keys % [:version :branch :worktree])
                                       :key (handoff/key-for "release" %))}
    "Require the remote annotated tag object and peeled candidate SHA to match the publication receipt.")))
