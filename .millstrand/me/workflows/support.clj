(ns me.workflows.support
  "Shared script helpers for the repo's independently loaded workflow definitions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn script
  "Return the frozen source of a named workspace script."
  [name]
  (let [resource-name (str "workflows/scripts/" name)
        resource (io/resource resource-name)]
    (when-not resource
      (throw (ex-info "Workspace workflow script is missing"
                      {:resource resource-name})))
    (slurp resource)))

(defn sh-gate
  "Return shell argv that runs script with name as `$0` and args as positionals."
  [script name & args]
  (into ["sh" "-c" script name] args))

(def land-quality-gate-script
  "POSIX script that validates and runs the target repository's quality contract."
  (script "land-quality-gate.sh"))

(def release-preflight-script
  "POSIX script that validates the release worktree before mutation."
  (script "release-preflight.sh"))

(def release-identity-script
  "POSIX script that validates release commits, pins, builds, and worktree state."
  (script "release-identity.sh"))

(def land-merge-script
  "Idempotently ready and squash-merge the feature PR."
  (script "land-merge.sh"))

(def land-pull-main-script
  "Fast-forward the canonical main checkout to origin/main.

  This stays inline as the small-script exemplar: eight lines of shell and no
  data-shaping logic do not earn a separate file."
  (str "set -eu\n"
       "root=$(dirname \"$(git rev-parse --path-format=absolute --git-common-dir)\")\n"
       "branch=$(git -C \"$root\" branch --show-current)\n"
       "if [ \"$branch\" != main ]; then\n"
       "  echo \"refusing to update canonical checkout: expected main, found $branch\" >&2\n"
       "  exit 1\n"
       "fi\n"
       "git -C \"$root\" pull --ff-only origin main\n"))

(def land-cleanup-script
  "Clean up the landed feature branch and worktree."
  (script "land-cleanup.sh"))
