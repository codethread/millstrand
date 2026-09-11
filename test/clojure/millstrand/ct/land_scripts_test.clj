(ns millstrand.ct.land-scripts-test
  "Exercise the protected landing scripts against disposable Git repositories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.spools.test-support :as test-support]))

(def ^:private branch "feature/land-script-test")
(def ^:private script-root
  (.getCanonicalFile (io/file ".millstrand/workflows/scripts")))

(defn- script-file
  [name]
  (io/file script-root name))

(defn- write-file!
  [root path contents executable?]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file contents)
    (when executable?
      (.setExecutable file true false))
    file))

(defn- run-command!
  "Run argv in dir, returning combined output and the exit status."
  [dir argv env]
  (let [process-builder (doto (ProcessBuilder. ^java.util.List argv)
                          (.directory (io/file dir))
                          (.redirectErrorStream true))
        environment (.environment process-builder)]
    (doseq [[key value] env]
      (.put environment key value))
    (let [process (.start process-builder)
          output (slurp (.getInputStream process))]
      {:exit (.waitFor process)
       :output output})))

(defn- run-script
  [dir name args env]
  (run-command! dir
                (into ["sh" (.getPath (script-file name))] args)
                env))

(defn- commit!
  [dir message]
  (test-support/run-git! dir "add" ".")
  (test-support/run-git! dir "commit" "-m" message)
  (str/trim (test-support/run-git! dir "rev-parse" "HEAD")))

(defn- fixture
  []
  (let [root (test-support/temp-dir "millstrand-land-scripts")
        remote (io/file root "remote.git")
        seed (io/file root "seed")
        canonical (io/file root "canonical")
        worktree (io/file root "feature")
        log (io/file root "quality.log")]
    (test-support/run-git! root "init" "--bare" (.getPath remote))
    (test-support/run-git! root "init" "-b" "main" (.getPath seed))
    (test-support/run-git! seed "config" "user.name" "Millstrand Test")
    (test-support/run-git! seed "config" "user.email" "test@millstrand.invalid")
    (write-file! seed "README" "baseline\n" false)
    (write-file! seed ".millstrand/land-quality.sh"
                 "#!/bin/sh\nset -eu\nprintf '%s\\n' pass >>\"$LAND_TEST_LOG\"\n"
                 true)
    (commit! seed "baseline")
    (test-support/run-git! seed "remote" "add" "origin" (.getPath remote))
    (test-support/run-git! seed "push" "-u" "origin" "main")
    (test-support/run-git! root "clone" (.getPath remote) (.getPath canonical))
    (test-support/run-git! canonical "config" "user.name" "Millstrand Test")
    (test-support/run-git! canonical "config" "user.email" "test@millstrand.invalid")
    (test-support/run-git! canonical "worktree" "add" "-b" branch
                           (.getPath worktree) "origin/main")
    (write-file! worktree "feature.txt" "feature\n" false)
    (let [feature-head (commit! worktree "feature")]
      (test-support/run-git! worktree "push" "-u" "origin" branch)
      {:root root
       :canonical canonical
       :worktree worktree
       :log log
       :feature-head feature-head})))

(defn- quality-env
  [fixture]
  {"LAND_TEST_LOG" (.getPath (:log fixture))})

(defn- quality-source
  []
  (slurp (script-file "land-quality-gate.sh")))

(defn- prepare!
  [fixture env]
  (run-script (:worktree fixture)
              "land-prepare.sh"
              [branch (quality-source)]
              env))

(defn- marker
  [fixture]
  (let [path (str/trim (test-support/run-git! (:worktree fixture)
                                              "rev-parse"
                                              "--git-path"
                                              "millstrand-land-quality-head"))
        file (if (.isAbsolute (io/file path))
               (io/file path)
               (io/file (:worktree fixture) path))]
    file))

(defn- quality-runs
  [fixture]
  (if (.exists (:log fixture))
    (count (str/split-lines (slurp (:log fixture))))
    0))

(defn- assert-success
  [{:keys [exit output]}]
  (is (zero? exit) output))

(deftest quality-marker-reuses-an-unchanged-head
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (is (= 1 (quality-runs fixture)))
      (assert-success (prepare! fixture env))
      (is (= 1 (quality-runs fixture))
          "prepare must reuse the successful unchanged-head marker")
      (is (= (str (:feature-head fixture) "\n") (slurp (marker fixture))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest rebase-invalidates-marker-and-reruns-quality
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (write-file! (:canonical fixture) "main.txt" "main moved\n" false)
      (commit! (:canonical fixture) "main change")
      (test-support/run-git! (:canonical fixture) "push" "origin" "main")
      (assert-success (prepare! fixture env))
      (is (= 2 (quality-runs fixture))
          "a rebase changes HEAD and must run quality again")
      (is (= (str/trim (test-support/run-git! (:worktree fixture)
                                              "rev-parse" "HEAD"))
             (str/trim (slurp (marker fixture)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest failed-quality-removes-any-cached-pass
  (let [fixture (fixture)
        env (quality-env fixture)]
    (try
      (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                  [branch] env))
      (write-file! (:worktree fixture) ".millstrand/land-quality.sh"
                   "#!/bin/sh\nexit 17\n"
                   true)
      (let [head (commit! (:worktree fixture) "make quality fail")]
        (test-support/run-git! (:worktree fixture) "push" "origin" branch)
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-quality-gate.sh" [branch] env)]
          (is (not (zero? exit)) output)
          (is (re-find  #"quality" output))
          (is (not (.exists (marker fixture))))
          (is (= head (str/trim (test-support/run-git! (:worktree fixture)
                                                       "rev-parse" "HEAD")))))
        (is (not (zero? (:exit (prepare! fixture env))))
            "prepare must not reuse the removed marker"))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest merge-requires-the-marker-and-matches-the-pr-head
  (let [fixture (fixture)
        fake-bin (io/file (:root fixture) "bin")
        gh-log (io/file (:root fixture) "gh.log")
        gh-state (io/file (:root fixture) "gh.state")
        head (:feature-head fixture)]
    (try
      (spit gh-state "OPEN\n")
      (write-file! fake-bin "gh"
                   (str "#!/bin/sh\n"
                        "printf '%s\\n' \"$*\" >>\"$GH_TEST_LOG\"\n"
                        "case \"$*\" in\n"
                        "  *'--json state'*) cat \"$GH_TEST_STATE\" ;;\n"
                        "  *'--json headRefName'*) printf '" branch "\\n' ;;\n"
                        "  *'--json headRefOid'*) printf '%s\\n' \"$GH_TEST_HEAD\" ;;\n"
                        "  *'--json isDraft'*) printf 'false\\n' ;;\n"
                        "  *'pr ready '*) exit 1 ;;\n"
                        "  *'pr merge '*)\n"
                        "    match=\n"
                        "    while [ \"$#\" -gt 0 ]; do\n"
                        "      if [ \"$1\" = --match-head-commit ]; then\n"
                        "        shift\n"
                        "        match=${1-}\n"
                        "      fi\n"
                        "      shift\n"
                        "    done\n"
                        "    [ \"$match\" = \"$GH_TEST_HEAD\" ] || exit 23\n"
                        "    printf '%s\\n' \"${GH_TEST_MERGE_STATE:-MERGED}\" >\"$GH_TEST_STATE\"\n"
                        "    printf 'merged\\n'\n"
                        "    ;;\n"
                        "esac\n")
                   true)
      (let [env (merge (quality-env fixture)
                       {"GH_TEST_LOG" (.getPath gh-log)
                        "GH_TEST_STATE" (.getPath gh-state)
                        "GH_TEST_HEAD" head
                        "PATH" (str (.getPath fake-bin) java.io.File/pathSeparator
                                    (System/getenv "PATH"))})
            result (run-script (:worktree fixture) "land-merge.sh"
                               ["42" "subject" "body" branch] env)]
        (is (not (zero? (:exit result)))
            "merge must fail before quality has produced a marker")
        (is (not (str/includes? (slurp gh-log) "pr merge")))
        (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                    [branch] env))
        (assert-success (run-script (:worktree fixture) "land-merge.sh"
                                    ["42" "subject" "body" branch] env))
        (is (str/includes? (slurp gh-log) "--json isDraft")
            "an already-ready PR is accepted when gh pr ready declines the conversion")
        (is (str/includes? (slurp gh-log) "pr merge"))
        (is (= "MERGED\n" (slurp gh-state)))
        (assert-success (run-script (:worktree fixture) "land-merge.sh"
                                    ["42" "subject" "body" branch] env))
        (is (= 1 (count (filter #(str/includes? % "pr merge")
                                (str/split-lines (slurp gh-log)))))
            "a merged PR retry must not invoke gh pr merge again"))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest merge-fails-loudly-on-state-errors-and-unmerged-command
  (let [fixture (fixture)
        fake-bin (io/file (:root fixture) "bin")
        gh-log (io/file (:root fixture) "gh.log")
        gh-state (io/file (:root fixture) "gh.state")
        gh-merged (io/file (:root fixture) "gh.merged")
        head (:feature-head fixture)]
    (try
      (spit gh-state "OPEN\n")
      (write-file! fake-bin "gh"
                   (str "#!/bin/sh\n"
                        "printf '%s\\n' \"$*\" >>\"$GH_TEST_LOG\"\n"
                        "case \"$*\" in\n"
                        "  *'--json state'*)\n"
                        "    if [ \"${GH_TEST_FAIL_STATE_READ:-}\" = 1 ] && [ -f \"$GH_TEST_MERGED\" ]; then exit 19; fi\n"
                        "    cat \"$GH_TEST_STATE\"\n"
                        "    ;;\n"
                        "  *'--json headRefName'*) printf '" branch "\\n' ;;\n"
                        "  *'--json headRefOid'*) printf '%s\\n' \"$GH_TEST_HEAD\" ;;\n"
                        "  *'--json isDraft'*) printf 'false\\n' ;;\n"
                        "  *'pr ready '*) exit 0 ;;\n"
                        "  *'pr merge '*)\n"
                        "    match=\n"
                        "    while [ \"$#\" -gt 0 ]; do\n"
                        "      if [ \"$1\" = --match-head-commit ]; then\n"
                        "        shift\n"
                        "        match=${1-}\n"
                        "      fi\n"
                        "      shift\n"
                        "    done\n"
                        "    [ \"$match\" = \"$GH_TEST_HEAD\" ] || exit 23\n"
                        "    printf '%s\\n' \"${GH_TEST_MERGE_STATE:-MERGED}\" >\"$GH_TEST_STATE\"\n"
                        "    : >\"$GH_TEST_MERGED\"\n"
                        "    printf 'merged\\n'\n"
                        "    ;;\n"
                        "esac\n")
                   true)
      (let [base-env (merge (quality-env fixture)
                            {"GH_TEST_LOG" (.getPath gh-log)
                             "GH_TEST_STATE" (.getPath gh-state)
                             "GH_TEST_MERGED" (.getPath gh-merged)
                             "GH_TEST_HEAD" head
                             "PATH" (str (.getPath fake-bin) java.io.File/pathSeparator
                                         (System/getenv "PATH"))})]
        (assert-success (run-script (:worktree fixture) "land-quality-gate.sh"
                                    [branch] base-env))
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-merge.sh"
                          ["42" "subject" "body" branch]
                          (assoc base-env "GH_TEST_FAIL_STATE_READ" "1"))]
          (is (not (zero? exit)) output)
          (is (str/includes? output "cannot verify PR 42 state after merge"))
          (is (= "MERGED\n" (slurp gh-state)))
          (is (str/includes? (slurp gh-log) "pr merge")))
        (spit gh-state "OPEN\n")
        (let [{:keys [exit output]}
              (run-script (:worktree fixture) "land-merge.sh"
                          ["42" "subject" "body" branch]
                          (assoc base-env "GH_TEST_MERGE_STATE" "OPEN"))]
          (is (not (zero? exit)) output)
          (is (str/includes? output "without reaching MERGED"))
          (is (= "OPEN\n" (slurp gh-state)))
          (is (str/includes? (slurp gh-log) "--match-head-commit"))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-is-retryable-after-worktree-removal
  (let [fixture (fixture)
        expected (:feature-head fixture)
        args [branch (.getPath (:worktree fixture)) expected]]
    (try
      (assert-success (run-command! (:canonical fixture)
                                    (into ["sh" (.getPath (script-file "land-cleanup.sh"))]
                                          args)
                                    {}))
      (is (not (.exists (:worktree fixture))))
      (assert-success (run-command! (:canonical fixture)
                                    (into ["sh" (.getPath (script-file "land-cleanup.sh"))]
                                          args)
                                    {}))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-refuses-a-subsequent-unlanded-commit
  (let [fixture (fixture)
        expected (:feature-head fixture)]
    (try
      (write-file! (:worktree fixture) "later.txt" "not landed\n" false)
      (let [later-head (commit! (:worktree fixture) "subsequent change")
            result (run-command! (:canonical fixture)
                                 ["sh" (.getPath (script-file "land-cleanup.sh"))
                                  branch (.getPath (:worktree fixture)) expected]
                                 {})]
        (is (not (zero? (:exit result))) (:output result))
        (is (re-find #"changed feature worktree" (:output result)))
        (is (.exists (:worktree fixture)))
        (is (= later-head
               (str/trim (test-support/run-git! (:worktree fixture)
                                                "rev-parse" "HEAD"))))
        (is (= later-head
               (str/trim (test-support/run-git! (:canonical fixture)
                                                "rev-parse" branch)))))
      (finally
        (test-support/delete-tree! (:root fixture))))))

(deftest cleanup-preserves-uncommitted-work
  (let [fixture (fixture)
        added (write-file! (:worktree fixture) "uncommitted.txt" "keep me\n" false)]
    (try
      (let [result (run-script (:canonical fixture) "land-cleanup.sh"
                               [branch (.getPath (:worktree fixture)) (:feature-head fixture)] {})]
        (is (not (zero? (:exit result))) (:output result))
        (is (re-find #"dirty feature worktree" (:output result)))
        (is (= "keep me\n" (slurp added))))
      (finally
        (test-support/delete-tree! (:root fixture))))))
