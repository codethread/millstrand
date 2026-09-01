(ns me.workflows.support
  "Shared script helpers for the repo's independently loaded workflow definitions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(def scripts-dir
  "Directory containing this workspace's standalone workflow scripts."
  (io/file (-> (io/file *file*)
               .getParentFile
               .getParentFile
               .getParentFile)
           "workflows" "scripts"))

(defn script
  "Return the frozen source of named workspace script."
  [name]
  (slurp (io/file scripts-dir name)))

(defn sh-gate
  "Return shell argv that runs script with name as `$0` and args as positionals."
  [script name & args]
  (into ["sh" "-c" script name] args))

(def land-quality-gate-script
  "POSIX script that validates and runs the target repository's quality contract."
  (script "land-quality-gate.sh"))

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
