(ns skein.ct.nvd-scan-test
  "Tests for the scheduled NVD deep-scan job defined in .skein/jobs/nvd_scan.clj.

  Covers the injected-seam lock flow (skip when locked / fail loud without a key
  / clean scan / findings raise a p1 card) entirely through fakes, so the suite
  never shells out to real gh."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; jobs/nvd_scan.clj is a .skein weaver file (ns `ct.jobs.nvd-scan`), not a classpath
;; namespace, so load it once exactly as config-test does and resolve its
;; (public and private) vars by symbol. Loading it defines vars only; it never
;; activates modules or seeds against gh.
(defn- load-config-once [f]
  (load-file ".skein/jobs/nvd_scan.clj")
  (f))

(use-fixtures :once load-config-once)

(defn- cfn
  "Resolve a var in the loaded `ct.jobs.nvd-scan` namespace by unqualified name."
  [name]
  (requiring-resolve (symbol "ct.jobs.nvd-scan" name)))

(deftest parses-scan-findings
  (let [watson-count (cfn "clj-watson-vuln-count")
        govuln? (cfn "govulncheck-findings?")]
    (is (= 2 (watson-count "some preamble\nVulnerable dependencies found: 2\ntail")))
    (is (zero? (watson-count "Vulnerable dependencies found: 0")))
    (testing "a missing summary line is nil (incomplete scan), never a clean 0"
      (is (nil? (watson-count "no clj-watson summary line at all"))))
    (is (true? (boolean (govuln? "=== Symbol Results ===\nVulnerability #1: GO-2024-1\n"))))
    (is (false? (boolean (govuln? "No vulnerabilities found."))))))

(defn- argv-has?
  "Predicate: every `needle` appears in the space-joined argv."
  [& needles]
  (fn [argv]
    (let [joined (str/join " " argv)]
      (every? #(str/includes? joined %) needles))))

(defn- fake-gh
  "Return a run-cmd seam that records each argv into `calls` and replies from
  the first matching `[predicate response]` pair (default: exit 0, empty out)."
  [calls responses]
  (fn [argv]
    (swap! calls conj argv)
    (or (some (fn [[pred resp]] (when (pred argv) resp)) responses)
        {:exit 0 :out ""})))

(defn- called? [calls pred] (boolean (some pred @calls)))

(deftest run-skips-when-open-lock-held
  (let [calls (atom [])
        cards (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open")
                           {:exit 0 :out (json/write-str [{:title "scan-lock running" :number 5}])}]])
        result ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                       :raise-card! #(swap! cards conj %)})]
    (is (= {:outcome :skipped-locked} result))
    (is (empty? @cards))
    (testing "no issue is created and no scan runs while another maintainer holds the lock"
      (is (not (called? calls (argv-has? "issue" "create"))))
      (is (not (called? calls (argv-has? "make deps-report")))))))

(deftest run-fails-loudly-without-key
  (let [calls (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open") {:exit 0 :out "[]"}]
                          [(argv-has? "CLJ_WATSON_NVD_API_KEY") {:exit 1 :out ""}]])]
    (is (thrown-with-msg?
         Exception #"CLJ_WATSON_NVD_API_KEY"
         ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                 :raise-card! (fn [_] (throw (ex-info "should not raise a card" {})))})))
    (testing "the lock is never acquired for a purely local misconfiguration"
      (is (not (called? calls (argv-has? "issue" "create"))))
      (is (not (called? calls (argv-has? "make deps-report")))))))

(deftest run-clean-scan-comments-closes-no-card
  (let [calls (atom [])
        cards (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open") {:exit 0 :out "[]"}]
                          [(argv-has? "CLJ_WATSON_NVD_API_KEY") {:exit 0 :out ""}]
                          [(argv-has? "issue" "create") {:exit 0 :out "https://github.com/x/y/issues/42\n"}]
                          [(argv-has? "make deps-report")
                           {:exit 0 :out "Vulnerable dependencies found: 0\nNo vulnerabilities found.\n"}]])
        result ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                       :raise-card! #(swap! cards conj %)})]
    (is (= {:outcome :scanned :clj-watson 0 :govulncheck false} result))
    (is (empty? @cards))
    (testing "the findings are commented on the lock issue and it is closed"
      (is (called? calls (argv-has? "issue" "comment" "42")))
      (is (called? calls (argv-has? "issue" "close" "42"))))))

(deftest run-findings-raise-p1-card-and-still-close
  (let [calls (atom [])
        cards (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open") {:exit 0 :out "[]"}]
                          [(argv-has? "CLJ_WATSON_NVD_API_KEY") {:exit 0 :out ""}]
                          [(argv-has? "issue" "create") {:exit 0 :out "/issues/7"}]
                          [(argv-has? "make deps-report")
                           {:exit 0 :out "Vulnerable dependencies found: 3\nVulnerability #1: GO-2024-1\n"}]])
        result ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                       :raise-card! #(swap! cards conj %)})]
    (is (= {:outcome :scanned :clj-watson 3 :govulncheck true} result))
    (is (= 1 (count @cards)))
    (let [card (first @cards)]
      (is (str/includes? (:title card) "clj-watson: 3"))
      (is (str/includes? (:title card) "govulncheck"))
      (is (str/includes? (:body card) "Vulnerable dependencies found: 3")))
    (testing "the lock is still released even when findings are raised"
      (is (called? calls (argv-has? "issue" "close" "7"))))))

(deftest run-incomplete-scan-output-fails-loudly
  ;; deps-report masks recipe exits, so a crashed scanner surfaces only as
  ;; marker-less output; it must fail the tick, never read as a clean scan.
  (let [calls (atom [])
        cards (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open") {:exit 0 :out "[]"}]
                          [(argv-has? "CLJ_WATSON_NVD_API_KEY") {:exit 0 :out ""}]
                          [(argv-has? "issue" "create") {:exit 0 :out "/issues/9"}]
                          [(argv-has? "make deps-report")
                           {:exit 0 :out "make: *** [deps-report] Error 1 (ignored)\n"}]])]
    (is (thrown-with-msg?
         Exception #"completion markers"
         ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                 :raise-card! #(swap! cards conj %)})))
    (is (empty? @cards))
    (testing "the lock is still released after the failed scan"
      (is (called? calls (argv-has? "issue" "close" "9"))))))

(deftest run-comment-failure-still-raises-card
  ;; The p1 card is the alert of record: a gh comment failure after findings
  ;; must not drop it.
  (let [calls (atom [])
        cards (atom [])
        run-cmd (fake-gh calls
                         [[(argv-has? "issue" "list" "open") {:exit 0 :out "[]"}]
                          [(argv-has? "CLJ_WATSON_NVD_API_KEY") {:exit 0 :out ""}]
                          [(argv-has? "issue" "create") {:exit 0 :out "/issues/11"}]
                          [(argv-has? "make deps-report")
                           {:exit 0 :out "Vulnerable dependencies found: 1\nNo vulnerabilities found.\n"}]
                          [(argv-has? "issue" "comment") {:exit 1 :out "boom"}]])]
    (is (thrown-with-msg?
         Exception #"gh issue comment failed"
         ((cfn "run-nvd-scan!") {:run-cmd run-cmd
                                 :raise-card! #(swap! cards conj %)})))
    (is (= 1 (count @cards)))
    (is (str/includes? (:title (first @cards)) "clj-watson: 1"))
    (testing "the lock is still released after the failed comment"
      (is (called? calls (argv-has? "issue" "close" "11"))))))
