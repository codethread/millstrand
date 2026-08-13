(ns ct.jobs.nvd-scan
  "The scheduled NVD deep scan (millhouse.spools.cron job :nvd-scan).

  `make deps-report` runs the clj-watson NVD deep scan + govulncheck locally
  (the fast github-advisory gate stays in CI). This job runs that scan on every
  maintainer's weaver every ~6 days. Because every weaver runs it, a `scan-lock
  running` GitHub issue is a best-effort lock — an OPEN one means another
  maintainer is scanning right now — and +/-1h jitter keeps concurrent weavers
  from all firing at once. Coordination is best-effort (a double-scan is
  harmless); silent failure is not, so a missing API key or a gh/scan error
  lands loudly in `(cron/failures)`.

  Every side effect (gh, the login-shell scan, the kanban card) is injected
  into `run-nvd-scan!` so the lock flow is unit-testable without shelling out —
  see test/clojure/millstrand/nvd_scan_test.clj. This is its own init.clj module (not part of
  ct/policy/config.clj) so config_test's direct ct/policy/config.clj load never registers the job."
  (:require [clojure.data.json :as json]
            [millhouse.spools.cron :as cron]))

(def ^:private nvd-scan-interval-ms
  "Base cadence: 6 days between NVD deep scans."
  (* 6 24 60 60 1000))

(def ^:private nvd-scan-jitter-ms
  "Uniform +/-1 hour jitter applied to each scheduled scan start."
  (* 60 60 1000))

(def ^:private scan-lock-title
  "Exact title of the coordination issue; matched exactly, not by gh search."
  "scan-lock running")

(def ^:private nvd-card-body-limit
  "Cap the scan output commented on the issue / carried on a card, keeping gh
  argv and the strand attribute bounded."
  60000)

(defn- run-command
  "Run `argv` as a subprocess from the weaver's cwd (the repo root), returning
  `{:exit int :out string}` with stderr merged into stdout.

  The single argv-shaped side-effect seam for the NVD job; tests inject a fake."
  [argv]
  (let [^ProcessBuilder pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
                             (.redirectErrorStream true))
        ^Process proc (.start pb)
        out (slurp (.getInputStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out}))

(defn- lock-issues
  "Return the `scan-lock running` issues (exact-title match) in `state`
  (\"all\" or \"open\") as maps, via the injected `run-cmd`."
  [run-cmd state fields]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "list"
                                     "--search" (str "\"" scan-lock-title "\" in:title")
                                     "--state" state
                                     "--json" fields
                                     "--limit" "50"])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue list failed" {:state state :exit exit :out out})))
    (->> (json/read-str out :key-fn keyword)
         (filter #(= scan-lock-title (:title %))))))

(defn- nvd-key-present?
  "Return true when CLJ_WATSON_NVD_API_KEY is set in the login shell env."
  [run-cmd]
  (zero? (:exit (run-cmd ["zsh" "-lc" "[ -n \"$CLJ_WATSON_NVD_API_KEY\" ]"]))))

(defn- create-lock-issue!
  "Create the lock issue and return its number, via the injected `run-cmd`."
  [run-cmd]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "create"
                                     "--title" scan-lock-title
                                     "--body" "Automated NVD deep-scan lock created by the millstrand cron :nvd-scan job; closed when the scan finishes."])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue create failed" {:exit exit :out out})))
    (or (some-> (re-find #"/issues/(\d+)" out) second parse-long)
        (throw (ex-info "Could not parse created lock issue number" {:out out})))))

(defn- comment-issue!
  "Comment `body` on issue `number`, via the injected `run-cmd`."
  [run-cmd number body]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "comment" (str number)
                                     "--body" (subs body 0 (min (count body) nvd-card-body-limit))])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue comment failed" {:issue number :exit exit :out out})))))

(defn- close-issue!
  "Close issue `number`, via the injected `run-cmd`."
  [run-cmd number]
  (let [{:keys [exit out]} (run-cmd ["gh" "issue" "close" (str number)])]
    (when-not (zero? exit)
      (throw (ex-info "gh issue close failed" {:issue number :exit exit :out out})))))

(defn- clj-watson-vuln-count
  "Return N from clj-watson's 'Vulnerable dependencies found: N', or nil when
  the summary line is absent (the scan did not run to completion)."
  [out]
  (some-> (re-find #"Vulnerable dependencies found:\s*(\d+)" out) second parse-long))

(defn- govulncheck-findings?
  "Return true when govulncheck reported at least one vulnerability."
  [out]
  (boolean (re-find #"Vulnerability #\d+" out)))

(defn- govulncheck-completed?
  "Return true when govulncheck ran to a verdict: explicit findings or an
  explicit clean report."
  [out]
  (boolean (or (re-find #"Vulnerability #\d+" out)
               (re-find #"No vulnerabilities found" out))))

(defn run-nvd-scan!
  "Run one NVD deep-scan tick with every side effect injected. Returns an
  inspectable outcome map; throws (fail loudly) on a missing key or gh error.

  seams:
  - `:run-cmd`     (fn [argv] -> {:exit :out}) — gh + login-shell subprocesses.
  - `:raise-card!` (fn [{:keys [title body]}]) — raise a p1 kanban card.

  Flow: skip when another maintainer holds an OPEN lock issue; fail loudly if
  the NVD API key is absent in the login shell (no keyless fallback); else
  acquire the lock (create the issue), run `make deps-report`, verify both
  scanners' completion markers (a marker-less output is a failed scan, never a
  clean one), raise a p1 card when the scan reports vulnerable dependencies,
  comment the findings, and release the lock in a `finally`. The key is checked
  before the lock is acquired so a purely local misconfiguration never churns a
  GitHub issue; the card is raised before the comment so a gh failure cannot
  drop the alert."
  [{:keys [run-cmd raise-card!]}]
  (cond
    (seq (lock-issues run-cmd "open" "number,title"))
    {:outcome :skipped-locked}

    (not (nvd-key-present? run-cmd))
    (throw (ex-info "NVD scan aborted: CLJ_WATSON_NVD_API_KEY absent in the login shell" {}))

    :else
    (let [number (create-lock-issue! run-cmd)]
      (try
        (let [{:keys [out]} (run-cmd ["zsh" "-lc" "make deps-report"])
              watson-n (clj-watson-vuln-count out)
              govuln? (govulncheck-findings? out)]
          ;; deps-report deliberately masks recipe exits (it is a report, not a
          ;; gate), so completion is judged by the scanners' own summary
          ;; markers: marker-less output means the scan crashed or was garbled
          ;; and must land in cron failures, never read as a clean result.
          (when-not (and watson-n (govulncheck-completed? out))
            (throw (ex-info "NVD scan output lacks completion markers; treating scan as failed"
                            {:clj-watson-summary? (some? watson-n)
                             :govulncheck-verdict? (govulncheck-completed? out)
                             :out (subs out 0 (min (count out) nvd-card-body-limit))})))
          ;; The card is the alert of record: raise it on the local weaver
          ;; before any further gh call so a failed comment cannot drop it.
          (when (or (pos? watson-n) govuln?)
            (raise-card! {:title (str "NVD scan: vulnerable dependencies found"
                                      (when (pos? watson-n) (str " (clj-watson: " watson-n ")"))
                                      (when govuln? " (govulncheck)"))
                          :body (str "The scheduled NVD deep scan (make deps-report) reported "
                                     "vulnerable dependencies.\n\n"
                                     "clj-watson vulnerable dependencies: " watson-n "\n"
                                     "govulncheck findings: " (if govuln? "yes" "no") "\n\n"
                                     "Full scan output:\n\n```\n"
                                     (subs out 0 (min (count out) nvd-card-body-limit))
                                     "\n```")}))
          (comment-issue! run-cmd number out)
          {:outcome :scanned :clj-watson watson-n :govulncheck govuln?})
        (finally
          (close-issue! run-cmd number))))))

(defn nvd-scan-tick
  "cron `:handler`: run one NVD deep scan with the real gh, login-shell, and kanban
  seams. `runtime` scopes the kanban card write so it lands in the right world."
  [runtime]
  (run-nvd-scan! {:run-cmd run-command
                  :raise-card! (fn [{:keys [title body]}]
                                 ((requiring-resolve 'ct.spools.kanban/add!)
                                  runtime title {"--body" body "--priority" "p1"}))}))

(cron/defjob :nvd-scan
  {:interval-ms nvd-scan-interval-ms
   :jitter-ms nvd-scan-jitter-ms
   :handler 'ct.jobs.nvd-scan/nvd-scan-tick})
