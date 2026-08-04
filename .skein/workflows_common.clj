(ns workflows-common
  "Shared shell helpers and basic specs for hand-authored workflow modules.

  Land and spool-bump both reuse the frozen POSIX scripts under
  `.skein/scripts/`; this module owns the script resolution and `sh-gate`
  argv builder so sibling workflow files stay focused on their definitions."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::title ::non-blank-string)
(s/def ::owner ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::branch ::non-blank-string)
(s/def ::worktree ::non-blank-string)
(s/def ::card ::non-blank-string)
(s/def ::feature ::non-blank-string)
(s/def ::subject ::non-blank-string)

(def ^:private scripts-dir
  "Directory containing this workspace's standalone workflow scripts."
  (.getParentFile (io/file *file*)))

(defn script
  "Return the frozen source of named workspace script."
  [name]
  (slurp (io/file scripts-dir "scripts" name)))

(defn sh-gate
  "Return shell argv that runs script with name as `$0` and args as positionals."
  [script name & args]
  (into ["sh" "-c" script name] args))

(def feature-ci-watch-script
  "POSIX script for the feature ci-green shell gate: wait up to the supplied
  startup budget for the PR head to match local HEAD and report at least one
  check, then replace the poller with `gh pr checks --watch --fail-fast`.

  Successful lookups with stale head metadata or zero checks are the only
  retryable states. Command failures and malformed successful output fail
  immediately. The gate's `shell/timeout-secs` bounds the whole startup and
  check watch."
  (script "feature-ci-watch.sh"))

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
