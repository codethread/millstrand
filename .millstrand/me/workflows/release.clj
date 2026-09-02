(ns me.workflows.release
  "The release workflow for versioned Millstrand publication."
  (:require [clojure.spec.alpha :as s]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format-alpha]
            [me.workflows.support :as support]))

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
(s/def ::release-params (s/keys :req-un [::version ::worktree]))

(def ^:private release-preflight-script
  (support/script "release-preflight.sh"))

(def ^:private release-identity-script
  (support/script "release-identity.sh"))

(workflow/defworkflow release
  "Prepare, verify, tag, and publish one Millstrand release.

  The caller supplies the semantic `version` and the absolute `worktree` for
  clean-main checks and shell gates. The workflow makes the two-commit
  release shape explicit: VERSION and changelog first, then a Homebrew formula
  pinned to that release commit. Full quality and identity checks run before a
  human publish checkpoint. Publication uses one atomic push for main and the
  annotated version tag."
  {:entrypoints #{:start}
   :param-spec ::release-params
   :defaults {}
   :example {:version "0.5.2"
             :worktree "/abs/path/to/millstrand"}}
  (workflow/workflow
   (fn [{:keys [version]}] (str "Release Millstrand " version))
   {:attributes {"workflow/family" "release"}}
   (workflow/gate :preflight
                  (fn [{:keys [version]}] (str "Check release preconditions for " version))
                  :shell
                  :attributes
                  {"shell/cwd" (fn [{:keys [worktree]}] worktree)
                   "shell/timeout-secs" 120
                   "shell/argv" (support/sh-gate release-preflight-script
                                                 "release-preflight")}
                  (format-alpha/prose
                   "
                   Require a clean worktree on `main` with no remote commits
                   missing locally before changing release files.
                   "))
   (workflow/step :bump-version
                  (fn [{:keys [version]}] (str "Bump VERSION to " version))
                  :self
                  :depends-on [:preflight]
                  (fn [{:keys [version]}]
                    (format-alpha/prose
                     "
                     On clean, current `main`, write `{version}` plus a trailing
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
                               "shell/argv" ["make" "land-quality"]}
                  (format-alpha/prose
                   "
                   Run the repository-owned release quality contract. Fix and
                   commit any failure, then clear `gate/error` to retry.
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
                   (fn [{:keys [version]}]
                     (support/sh-gate release-identity-script
                                      "release-identity"
                                      version))}
                  (format-alpha/prose
                   "
                   Build both CLIs and require their reported versions to match
                   the release. The worktree must remain clean.
                   "))
   (workflow/gate :publish
                  (fn [{:keys [version]}] (str "Publish v" version))
                  :human
                  :depends-on [:build-identity]
                  (fn [{:keys [version]}]
                    (format-alpha/prose
                     "
                     Confirm the worktree is clean. Run `git fetch origin` and
                     require `git rev-list --left-right --count
                     origin/main...HEAD` to report zero remote-only commits.

                     Create annotated tag `v{version}` at HEAD with message
                     `Millstrand {version}`, then publish branch and tag together:

                     ```sh
                     git push --atomic origin main v{version}
                     ```

                     Verify both remote refs resolve to HEAD before completing.
                     Never move an existing release tag.
                     "
                     {:version version})))))
