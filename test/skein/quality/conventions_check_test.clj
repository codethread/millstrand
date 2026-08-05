(ns skein.quality.conventions-check-test
  "Ratchet-edge coverage for the `quality.api-form`, `quality.spool-tiers`,
  `quality.spool-var`, and `quality.json-literals` checks.

  The conversion cards each edit `quality.api-form/pending`, so the edges
  that keep that set honest are the behavior worth pinning: a converted
  module with an undocumented public var or a wide line is a finding
  (privates in alpha are story-support, not findings — SPEC-003.C19a); a
  pending module is exempt even when conformant (deleting the entry is
  the card's deliberate act, never forced by an unrelated cleanup); a
  stale entry is a finding; and the internal dependency rules hold for
  every module, pending or not.

  The spool-tiers rules pin the unsafe-namespace convention: shipped
  spool sources use `skein.core.*` only from unsafe-named namespaces
  (SPEC-005.C5), an unsafe name that touches no internals is stale,
  cross-spool unsafe requires from safe namespaces are findings, and
  the `UNSAFE:` docstring lead agrees with the name in both
  directions.

  The spool-var rules reject the withdrawn public `spool` convention in
  module-loadable namespaces. Private `spool` vars are ignored, and the guard
  is structural: it reads source without resolving symbols.

  The json-literals rules pin the three narrowings that keep every
  finding mechanically convertible: only text `json/write-str` would
  reproduce character for character is in scope, quote-free JSON is not
  escape soup, and a direct argument of `=`/`not=` is the text under
  test rather than an input built from data."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [quality.api-tests :as api-tests]
   [quality.api-form :as api-form]
   [quality.json-literals :as json-literals]
   [quality.spool-tiers :as spool-tiers]
   [quality.spool-var :as spool-var]
   [quality.workspace-tests :as workspace-tests])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(def ^:private conformant-source
  "(ns m.alpha \"Doc.\")\n(defn ok \"Doc.\" [] :ok)\n")

(defn- module-dir!
  "Create module directory `name` under `root` holding an alpha.clj with
  `source`; return the directory as a File."
  [root name source]
  (let [dir (io/file root name)]
    (.mkdirs dir)
    (spit (io/file dir "alpha.clj") source)
    dir))

(defn- with-modules
  "Run `f` with {module-name dir} built from `sources` ({name source}) in a
  fresh temp root."
  [sources f]
  (let [root (.toFile (Files/createTempDirectory
                       "conventions-check-test" (make-array FileAttribute 0)))]
    (f (into {} (for [[name source] sources]
                  [name (module-dir! root name source)])))))

(defn- var-def
  "One kondo-shaped var-definition row for `dir`/alpha.clj."
  [dir attrs]
  (merge {:filename (.getPath (io/file dir "alpha.clj")) :row 2} attrs))

(defn- check-workspace-analysis
  [analysis]
  (with-modules {"empty" conformant-source}
    (fn [dirs]
      (workspace-tests/check analysis (.getPath (dirs "empty"))))))

(deftest api-test-boundary-permits-public-fixtures-and-any-suite-size
  (with-modules
    {"ok" (str "(ns skein.api.example.alpha-test\n"
               "  (:require [skein.core.specs :as specs]\n"
               "            [skein.test.alpha :as t]))\n"
               "(def cases " (pr-str (vec (range 1000))) ")\n")}
    (fn [dirs]
      (is (empty? (api-tests/findings (.getPath (dirs "ok"))))))))

(deftest api-test-boundary-rejects-core-implementation-use
  (with-modules
    {"bad" (str "(ns skein.api.example.alpha-test\n"
                "  (:require [skein.core.db :as db]))\n"
                "(defn probe [] (db/pending-wakes nil))\n")}
    (fn [dirs]
      (let [findings (api-tests/findings (.getPath (dirs "bad")))]
        (is (= 2 (count findings)))
        (is (some #(re-find #"direct skein.core implementation use.*skein.core.db" %) findings))
        (is (some #(re-find #"direct skein.core implementation use.*db/pending-wakes" %) findings))))))

(deftest api-test-boundary-reports-private-vars-redefs-and-megasuites
  (with-modules
    {"bad" (str "(ns skein.api.example.alpha-test\n"
                "  (:require [skein.test.alpha :as t]\n"
                "            [skein.spools-test :as mega]))\n"
                "(ns-resolve 'skein.core.weaver.runtime 'secret)\n"
                "(with-redefs [skein.core.db/query! identity] (t/with-weaver-world []))\n")}
    (fn [dirs]
      (let [findings (api-tests/findings (.getPath (dirs "bad")))]
        (is (= 3 (count findings)))
        (is (some #(re-find #"private core Var resolution.*skein.core.weaver.runtime" %) findings))
        (is (some #(re-find #"core collaborator redefinition.*skein.core.db/query!" %) findings))
        (is (some #(re-find #"test-megasuite require.*skein.spools-test" %) findings))))))

(deftest api-test-quality-boundary-validates-input-and-output
  (testing "invalid roots fail at the public boundary"
    (doseq [root ["" 42]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"api-tests root does not conform"
                            (api-tests/findings root)))))
  (testing "invalid findings fail before crossing the public boundary"
    (with-modules {"ok" conformant-source}
      (fn [dirs]
        (with-redefs-fn {#'api-tests/file-findings (constantly [42])}
          (fn []
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"api-tests findings does not conform"
                                  (api-tests/findings (.getPath (dirs "ok")))))))))))

(deftest workspace-test-namespace-and-directory-map-bidirectionally
  (let [valid {:name 'skein.ct.config-test
               :filename "test/skein/ct/config_test.clj" :row 1}
        external-ct {:name 'ct.spools.example-test
                     :filename "test/ct/spools/example_test.clj" :row 1}]
    (is (empty? (check-workspace-analysis
                 {:namespace-definitions [valid external-ct]})))
    (testing "the workspace namespace cannot leak outside its directory"
      (let [[finding] (check-workspace-analysis
                       {:namespace-definitions
                        [(assoc valid :filename "test/skein/config_test.clj")]})]
        (is (str/includes? finding "must be defined under `test/skein/ct/`"))))
    (testing "the workspace directory cannot carry an unrelated namespace"
      (let [[finding] (check-workspace-analysis
                       {:namespace-definitions
                        [(assoc valid :name 'skein.config-test)]})]
        (is (str/includes? finding "must declare a `skein.ct.*` namespace"))))
    (testing "absolute paths preserve the boundary"
      (is (empty? (check-workspace-analysis
                   {:namespace-definitions
                    [(assoc valid :filename "/tmp/repo/test/skein/ct/config_test.clj")]})))))
  (testing "malformed analysis fails at the public quality boundary"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"workspace-test analysis does not conform"
                          (check-workspace-analysis {})))))

(deftest workspace-test-check-rejects-malformed-roots
  (let [analysis {:namespace-definitions []}]
    (doseq [root ["" 42]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"workspace-test root does not conform"
                            (workspace-tests/check analysis root))))
    (let [file (java.io.File/createTempFile "workspace-test-root" ".clj")]
      (try
        (let [error (try
                      (workspace-tests/check analysis (.getPath file))
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? error))
          (is (= {:test-root (.getPath file) :expected :directory}
                 (ex-data error))))
        (finally
          (io/delete-file file true))))))

(deftest direct-workspace-path-catches-a-test-before-it-adopts-the-namespace
  (with-modules
    {"config-ops" (str "(ns skein.config-ops-test \"Doc.\")\n"
                       "(load-file \".skein/policy/config.clj\")\n")}
    (fn [dirs]
      (let [dir (dirs "config-ops")
            file (.getPath (io/file dir "alpha.clj"))
            findings (workspace-tests/check
                      {:namespace-definitions
                       [{:name 'skein.config-ops-test :filename file :row 1}]}
                      (.getPath dir))]
        (is (= 1 (count findings)))
        (is (str/includes? (first findings)
                           "direct workspace path `.skein/policy/config.clj`")))))
  (testing "incidental and disposable-workspace strings stay out of scope"
    (with-modules
      {"generic" (str "(ns skein.generic-test \"Doc.\")\n"
                      "(def paths [\"mentions .skein/policy/config.clj\"\n"
                      "            \".skein\"\n"
                      "            \"/tmp/world/.skein/init.clj\"])\n")}
      (fn [dirs]
        (let [dir (dirs "generic")
              file (.getPath (io/file dir "alpha.clj"))]
          (is (empty? (workspace-tests/check
                       {:namespace-definitions
                        [{:name 'skein.generic-test :filename file :row 1}]}
                       (.getPath dir)))))))))

(deftest private-vars-in-a-converted-alpha-are-not-findings
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis {:var-definitions [(var-def (dirs "tidy")
                                                 {:private true :name 'helper})]}]
        (is (empty? (api-form/findings analysis dirs #{})))))))

(deftest converted-module-with-undocumented-public-var-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis {:var-definitions
                      [(var-def (dirs "tidy") {:name 'bare})
                       ;; declare sites carry no doc; the definition does.
                       (var-def (dirs "tidy") {:name 'forward
                                               :defined-by 'clojure.core/declare})
                       (var-def (dirs "tidy") {:name 'ok :doc "Doc."})]}
            findings (api-form/findings analysis dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"public var `bare`.*no docstring" (first findings)))))))

(deftest converted-module-with-wide-line-is-a-finding
  (with-modules {"tidy" (str "(ns m.alpha \"Doc.\")\n;; "
                             (str/join (repeat 100 "x")) "\n")}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"alpha\.clj:2: line is 103 columns" (first findings)))))))

(deftest conformant-converted-module-yields-no-findings
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (is (empty? (api-form/findings {} dirs #{}))))))

(deftest pending-module-is-exempt-even-when-conformant
  (testing "nonconformance in a pending module stays silent"
    (with-modules {"messy" (str "(ns m.alpha \"Doc.\")\n;; "
                                (str/join (repeat 100 "x")) "\n")}
      (fn [dirs]
        (let [analysis {:var-definitions [(var-def (dirs "messy")
                                                   {:name 'bare})]}]
          (is (empty? (api-form/findings analysis dirs #{"messy"})))))))
  (testing "a conformant pending module forces nothing; deletion is deliberate"
    (with-modules {"tidy" conformant-source}
      (fn [dirs]
        (is (empty? (api-form/findings {} dirs #{"tidy"})))))))

(deftest stale-pending-entry-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{"tidy" "gone"})]
        (is (= 1 (count findings)))
        (is (re-find #"entry `gone` matches no module directory"
                     (first findings)))))))

(deftest internal-namespaces-never-require-alpha
  (let [usage {:from 'skein.api.tidy.internal :to 'skein.api.other.alpha
               :filename "src/skein/api/tidy/internal.clj" :row 3}
        findings (api-form/findings {:namespace-usages [usage]} {} #{})]
    (is (= 1 (count findings)))
    (is (re-find #"plumbing stays tier-free" (first findings)))))

(deftest only-own-alpha-reaches-internal
  (testing "a foreign src namespace requiring internal is a finding"
    (let [usage {:from 'skein.core.weaver :to 'skein.api.tidy.internal
                 :filename "src/skein/core/weaver.clj" :row 3}
          findings (api-form/findings {:namespace-usages [usage]} {} #{})]
      (is (= 1 (count findings)))
      (is (re-find #"only the module's own alpha" (first findings)))))
  (testing "absolute kondo filenames still hit the source-tier rule"
    (let [usage {:from 'skein.core.weaver :to 'skein.api.tidy.internal
                 :filename "/abs/repo/src/skein/core/weaver.clj" :row 3}]
      (is (= 1 (count (api-form/findings {:namespace-usages [usage]} {} #{}))))))
  (testing "the module's own alpha, internal siblings, and tests are allowed"
    (let [own {:from 'skein.api.tidy.alpha :to 'skein.api.tidy.internal
               :filename "src/skein/api/tidy/alpha.clj" :row 3}
          nested {:from 'skein.api.tidy.alpha :to 'skein.api.tidy.internal.validate
                  :filename "src/skein/api/tidy/alpha.clj" :row 4}
          sibling {:from 'skein.api.tidy.internal.validate
                   :to 'skein.api.tidy.internal.shared
                   :filename "src/skein/api/tidy/internal/validate.clj" :row 3}
          test-use {:from 'skein.api.tidy.alpha-test :to 'skein.api.tidy.internal
                    :filename "test/skein/api/tidy/alpha_test.clj" :row 3}]
      (is (empty? (api-form/findings
                   {:namespace-usages [own nested sibling test-use]} {} #{})))))
  (testing "a foreign module's internal is still fenced, nested or not"
    (let [usage {:from 'skein.api.other.internal.helpers
                 :to 'skein.api.tidy.internal.validate
                 :filename "src/skein/api/other/internal/helpers.clj" :row 3}]
      (is (= 1 (count (api-form/findings {:namespace-usages [usage]} {} #{})))))))

(defn- spool-file
  "Source path for namespace `from-ns` inside spool `spool`."
  [spool from-ns]
  (str "spools/" spool "/src/"
       (-> (str from-ns) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- spool-usage
  "One kondo-shaped usage row from `from-ns` in spool `spool` to `to-ns`."
  [spool from-ns to-ns row]
  {:from from-ns :to to-ns :filename (spool-file spool from-ns) :row row})

(defn- spool-ns-def
  "One kondo-shaped namespace-definition row for `from-ns` in `spool`."
  [spool from-ns doc]
  (cond-> {:name from-ns :filename (spool-file spool from-ns) :row 1}
    doc (assoc :doc doc)))

(deftest core-usage-from-safe-namespace-is-a-finding
  (testing "a require and a qualified var usage are each findings"
    (let [findings (spool-tiers/findings
                    {:namespace-usages [(spool-usage "tidy" 'skein.spools.tidy
                                                     'skein.core.db 5)]
                     :var-usages [(spool-usage "tidy" 'skein.spools.tidy
                                               'skein.core.weaver.module-refresh
                                               40)]}
                    #{"tidy"})]
      (is (= 2 (count findings)))
      (is (every? #(re-find #"spools build on `skein.api" %) findings))))
  (testing "absolute kondo filenames still hit the rule"
    (let [usage (assoc (spool-usage "tidy" 'skein.spools.tidy 'skein.core.db 5)
                       :filename "/abs/repo/spools/tidy/src/skein/spools/tidy.clj")]
      (is (= 1 (count (spool-tiers/findings {:namespace-usages [usage]}
                                            #{"tidy"})))))))

(deftest core-usage-from-unsafe-named-namespace-is-permitted
  (let [analysis {:namespace-definitions
                  [(spool-ns-def "unsafe-tidy" 'skein.spools.unsafe-tidy
                                 "UNSAFE: reads skein.core.db directly.")]
                  :namespace-usages
                  [(spool-usage "unsafe-tidy" 'skein.spools.unsafe-tidy
                                'skein.core.db 5)]}]
    (is (empty? (spool-tiers/findings analysis #{"unsafe-tidy"})))))

(deftest unsafe-marker-matches-segments-not-substrings
  (testing "a substring hit grants nothing"
    (let [analysis {:namespace-usages
                    [(spool-usage "tidy" 'skein.spools.notunsafe
                                  'skein.core.db 5)]}]
      (is (= 1 (count (spool-tiers/findings analysis #{"tidy"}))))))
  (testing "a bare `unsafe` segment is a marker"
    (is (spool-tiers/unsafe-ns? 'skein.spools.tidy.unsafe))
    (is (not (spool-tiers/unsafe-ns? 'skein.spools.unsafely)))))

(deftest core-usage-outside-shipped-spool-sources-is-out-of-scope
  (let [engine {:from 'skein.core.weaver :to 'skein.core.db
                :filename "src/skein/core/weaver.clj" :row 3}
        test-use {:from 'skein.spools.tidy-test :to 'skein.core.db
                  :filename "spools/tidy/test/skein/spools/tidy_test.clj" :row 3}]
    (is (empty? (spool-tiers/findings
                 {:namespace-usages [engine test-use]}
                 #{"tidy"})))))

(deftest own-spool-unsafe-require-is-permitted-cross-spool-is-not
  (let [defs [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db
                            "UNSAFE: reads skein.core.db directly.")
              (spool-ns-def "tidy" 'skein.spools.tidy "Doc.")
              (spool-ns-def "other" 'skein.spools.other "Doc.")]
        core-use (spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                              'skein.core.db 5)
        own-use (spool-usage "tidy" 'skein.spools.tidy
                             'skein.spools.tidy.unsafe-db 3)
        foreign-use (spool-usage "other" 'skein.spools.other
                                 'skein.spools.tidy.unsafe-db 3)]
    (testing "a safe ns wrapping its own spool's unsafe boundary is clean"
      (is (empty? (spool-tiers/findings
                   {:namespace-definitions defs
                    :namespace-usages [core-use own-use]}
                   #{"tidy" "other"}))))
    (testing "a safe ns requiring another spool's unsafe ns is a finding"
      (let [findings (spool-tiers/findings
                      {:namespace-definitions defs
                       :namespace-usages [core-use own-use foreign-use]}
                      #{"tidy" "other"})]
        (is (= 1 (count findings)))
        (is (re-find #"from another spool" (first findings)))))))

(deftest stale-unsafe-name-is-a-finding
  (let [analysis {:namespace-definitions
                  [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db
                                 "UNSAFE: reads skein.core.db directly.")]
                  :namespace-usages
                  [(spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                                'skein.api.graph.alpha 5)]}]
    (is (= 1 (count (spool-tiers/findings analysis #{"tidy"}))))
    (is (re-find #"drop the unsafe marker"
                 (first (spool-tiers/findings analysis #{"tidy"}))))))

(deftest unsafe-docstring-lead-agrees-with-the-name
  (testing "unsafe-named ns without the UNSAFE: lead is a finding"
    (let [analysis {:namespace-definitions
                    [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db "Doc.")]
                    :namespace-usages
                    [(spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                                  'skein.core.db 5)]}
          findings (spool-tiers/findings analysis #{"tidy"})]
      (is (= 1 (count findings)))
      (is (re-find #"must lead with `UNSAFE:`" (first findings)))))
  (testing "safe ns claiming UNSAFE: in its docstring is a finding"
    (let [analysis {:namespace-definitions
                    [(spool-ns-def "tidy" 'skein.spools.tidy
                                   "UNSAFE: but the name says otherwise.")]}
          findings (spool-tiers/findings analysis #{"tidy"})]
      (is (= 1 (count findings)))
      (is (re-find #"the marker is the name" (first findings))))))

(defn- def-spool-sites
  "Read `content` as a temp source file and return its `(def spool …)`
  sites, each tagged with a stub filename for `spool-var/findings`."
  [content]
  (let [file (io/file (.toFile (Files/createTempDirectory
                                "spool-var-test" (make-array FileAttribute 0)))
                      "spool.clj")]
    (spit file content)
    (map #(assoc % :filename "spools/tidy/src/skein/spools/tidy.clj")
         (spool-var/def-spool-sites file))))

(defn- findings-for
  "Findings for a single-form `content` string."
  [content]
  (spool-var/findings (def-spool-sites content)))

(deftest public-spool-var-is-rejected
  (doseq [content ["(def spool {:contribute 'contribute})"
                   "(defn spool [ctx] ctx)"
                   "(defonce spool {})"
                   "(do (def spool {:reconcile 'reconcile}))"]]
    (let [findings (findings-for content)]
      (is (= 1 (count findings)))
      (is (re-find #"removed module entry-point convention" (first findings))))))

(deftest private-spool-var-is-ignored-even-when-malformed
  (is (empty? (findings-for "(def ^:private spool {:contribute 42})")))
  (is (empty? (findings-for "(def ^:private spool \"not a declaration\")"))))

(deftest only-a-var-named-spool-is-a-declaration-site
  (testing "adjacent names are not the reserved var"
    (is (empty? (def-spool-sites "(def spooler {:contribute 42})")))
    (is (empty? (def-spool-sites "(def spool-state {:contribute 42})"))))
  (testing "private defn shorthand is unaffected"
    (is (empty? (def-spool-sites "(defn- spool [ctx] ctx)"))))
  (testing "the extraction records privacy and the authored value"
    (let [[site] (def-spool-sites "(def ^:private spool {:reconcile 'a/b})")]
      (is (:private? site))
      (is (:has-value? site))
      (is (map? (:value site))))))

(deftest executable-wrapper-spool-vars-are-declaration-sites
  (testing "quoted data and deferred function bodies stay inert"
    (doseq [content ["'(do (def spool {:contribute 42}))"
                     "(def held '(def spool {:contribute 42}))"
                     "(fn [] (do (def spool {:contribute 42})))"
                     "(defn factory [] (do (def spool {:contribute 42})))"]]
      (is (empty? (def-spool-sites content))))))

(deftest unreadable-file-site-is-surfaced-as-a-finding
  (let [findings (spool-var/findings
                  [{:filename "spools/tidy/src/skein/spools/tidy.clj"
                    :read-error "EOF while reading"
                    :read-error/class "clojure.lang.ExceptionInfo"
                    :read-error/data {:line 7}}])]
    (is (= 1 (count findings)))
    (is (re-find #"could not read file: EOF while reading" (first findings)))
    (is (re-find #"clojure.lang.ExceptionInfo" (first findings)))
    (is (re-find #"data=\{:line 7\}" (first findings)))))

(deftest module-root-enumeration-fails-loudly
  (let [directory-files! (ns-resolve 'quality.spool-var 'directory-files!)
        root (java.io.File/createTempFile "spool-var-root" ".not-a-directory")]
    (try
      (let [error (try
                    (directory-files! root)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? error))
        (is (= (.getPath root) (:root (ex-data error))))
        (is (= :list-module-loadable-roots
               (:operation (ex-data error))))
        (is (= :readable-directory (:expected (ex-data error)))))
      (finally
        (.delete root)))))

;; --- json-literals ---------------------------------------------------------

(def ^:private reproducible-object
  "JSON text `write-str` reproduces exactly — the shape the check flags.

  Built rather than hand-escaped, so this file passes its own rule."
  (json/write-str {:title "x"}))

(deftest only-write-str-output-is-a-reproducible-literal
  (testing "objects and arrays write-str would reproduce"
    (is (json-literals/reproducible-json? reproducible-object))
    (is (json-literals/reproducible-json? (json/write-str [{:k "v"}]))))
  (testing "spacing, JSONL, and trailing text are the author's own"
    (is (not (json-literals/reproducible-json? "{\"a\": 1}")))
    (is (not (json-literals/reproducible-json? "{\"k\":1}\n{\"k\":2}\n")))
    (is (not (json-literals/reproducible-json? "{\"k\":1} and then some"))))
  (testing "JSON carrying no quoted string is not escape soup"
    (is (not (json-literals/reproducible-json? "[1,2]")))
    (is (not (json-literals/reproducible-json? "{}"))))
  (testing "text that merely opens with a brace is not JSON"
    (is (not (json-literals/reproducible-json? "{not json at all")))))

(deftest literal-sites-report-the-enclosing-form-line
  (let [form (with-meta (list 'op! "weave" ["--input" reproducible-object]) {:line 12})]
    (is (= [{:line 12 :text reproducible-object}]
           (json-literals/literal-sites form 1)))))

(deftest comparison-arguments-are-the-text-under-test
  (testing "a direct argument of = or not= is the assertion, not an input"
    (is (empty? (json-literals/literal-sites (list '= reproducible-object 'actual) 1)))
    (is (empty? (json-literals/literal-sites (list 'not= reproducible-object 'actual) 1))))
  (testing "a literal nested inside the comparison is still an input"
    (is (= [{:line 1 :text reproducible-object}]
           (json-literals/literal-sites
            (list '= 'expected (list 'parse ["--input" reproducible-object]))
            1)))))

(deftest findings-locate-the-literal-and-name-the-fix
  (let [[finding] (json-literals/findings
                   [{:filename "test/skein/thing_test.clj"
                     :line 12
                     :text reproducible-object}])]
    (is (str/starts-with? finding "test/skein/thing_test.clj:12: "))
    (is (str/includes? finding reproducible-object))
    (is (str/includes? finding "json/write-str")))
  (testing "a long literal is excerpted rather than reprinted whole"
    (let [long-text (json/write-str (zipmap (map #(str "key" %) (range 20)) (range 20)))
          [finding] (json-literals/findings
                     [{:filename "t.clj" :line 1 :text long-text}])]
      (is (not (str/includes? finding long-text)))
      (is (str/includes? finding "..."))))
  (testing "an unreadable file is its own finding"
    (is (str/includes? (first (json-literals/findings
                               [{:filename "t.clj" :read-error "EOF while reading"}]))
                       "could not read file: EOF while reading"))))
