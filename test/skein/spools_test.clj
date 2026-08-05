(ns skein.spools-test
  "Tests for runtime spool workspace surfaces: approved spools.edn and
  spools.local.edn reading, approved-root acquisition, module refresh, event helper routing,
  and daemon init."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.core.client :as client]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.api.events.alpha :as events]
            [skein.spools.test-support :refer [delete-tree! run-git! temp-config-dir with-runtime]]
            [skein.api.runtime.alpha :as runtime]
            [skein.source-file :as source-file]
            [skein.test.alpha :as t]))

;; Namespace-level on purpose: event handlers are registered by symbol and
;; resolved to top-level vars, so capture state cannot be a per-test local.
;; Reset by the :each fixture below; the runner never splits a namespace.
(def reload-deliveries (atom []))

(use-fixtures :each (fn [f] (reset! reload-deliveries []) (f)))

(defn fresh-reload-handler [event]
  (swap! reload-deliveries conj (:event/id event)))

(defn- write-spools! [config-dir content]
  (spit (io/file config-dir "spools.edn") content))

(defn- write-local-spools! [config-dir content]
  (spit (io/file config-dir "spools.local.edn") content))

(defn- shared-source [config-dir]
  {:kind :shared
   :file (.getPath (io/file (.getCanonicalPath config-dir) "spools.edn"))})

(defn- local-source [config-dir]
  {:kind :local
   :file (.getPath (io/file (.getCanonicalPath config-dir) "spools.local.edn"))})

(defn- with-cache-base [cache-dir f]
  (let [original @#'access/cache-base]
    (try
      (alter-var-root #'access/cache-base (constantly (fn [] cache-dir)))
      (f)
      (finally
        (alter-var-root #'access/cache-base (constantly original))))))

(defn- with-resolver
  "Run `f` with the private Maven resolver seam replaced by `resolver`.

  `resolver` receives the merged `{lib coord}` universe and returns a tools.deps
  `:added` lib-map, so sync tests exercise the two-phase loader without real
  network resolution."
  [resolver f]
  (let [original @#'spool-sync/resolve-spool-maven-libs]
    (try
      (alter-var-root #'spool-sync/resolve-spool-maven-libs (constantly resolver))
      (f)
      (finally
        (alter-var-root #'spool-sync/resolve-spool-maven-libs (constantly original))))))

(defn- running-release-marker [runtime]
  (let [{:keys [marker provenance]} (runtime/release-marker runtime)]
    (if (= :none provenance) :none marker)))

(defn- sync-approved! [runtime]
  (spool-sync/sync-approved-spools runtime (running-release-marker runtime)))

(defn- sync-state [runtime]
  (spool-sync/approved-spool-syncs runtime))

(defn- load-synced! [runtime owner {ns-sym :ns}]
  (spool-sync/load-synced-namespace! runtime ns-sym owner)
  {:status :loaded})

(defn- write-local-lib! [config-dir lib-name ns-sym]
  (let [root (io/file config-dir "spools" lib-name)
        ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n(defn event-handler [_] :handled)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- write-spool-manifest!
  "Write a legacy spool.edn file so regression tests can prove acquisition ignores it.

  Spool manifests are not part of the spool contract; documentation-only
  metadata belongs in README prose per devflow/specs/repl-api.md SPEC-003.P5."
  [root manifest]
  (spit (io/file root "spool.edn") (pr-str manifest)))

(defn- write-spool-ns! [root ns-sym content]
  (let [ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file content)
    src-file))

(defn- write-git-lib! [root ns-sym]
  (let [ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        src-file (io/file root "src" (str ns-path ".clj"))]
    (.mkdirs (.getParentFile src-file))
    (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :git-spool-loaded)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")))

(defn- file-url [file]
  (str "file://" (.getCanonicalPath file)))

(defn- init-git-repo! [root ns-sym & {:keys [tag annotated-tag]}]
  (.mkdirs root)
  (run-git! root "init")
  (run-git! root "config" "user.email" "skein-test@example.invalid")
  (run-git! root "config" "user.name" "Skein Test")
  (write-git-lib! root ns-sym)
  (run-git! root "add" ".")
  (run-git! root "commit" "-m" "fixture")
  (let [sha (str/trim (run-git! root "rev-parse" "HEAD"))]
    (when tag
      (run-git! root "tag" tag))
    (when annotated-tag
      (run-git! root "tag" "-a" annotated-tag "-m" annotated-tag))
    sha))

(deftest approved-returns-empty-spools-when-files-are-missing
  (with-runtime
    (fn [rt _]
      (is (= {:spools {}} (select-keys (runtime/approved rt) [:spools]))))))

(deftest approved-fails-loudly-when-local-spools-edn-is-malformed
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir "{:spools")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spools.local.edn is malformed or unreadable"
                            (runtime/approved rt))))))

(deftest approved-rejects-legacy-libs-files-and-symlinks
  (with-runtime
    (fn [rt config-dir]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file config-dir "libs.local.edn"))
       (.toPath (io/file config-dir "missing"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"rename libs.edn/libs.local.edn to spools.edn/spools.local.edn"
                            (runtime/approved rt))))))

(deftest approved-includes-primary-local-spools
  (with-runtime
    (fn [rt config-dir]
      (let [shared-root (io/file config-dir "spools" "shared")
            local-root (io/file config-dir "spools" "local")]
        (write-spools! config-dir (pr-str {:spools {'demo/shared {:local/root "spools/shared"}
                                                    'demo/local {:local/root "spools/local"}}}))
        (is (= {:spools {'demo/shared {:kind :local
                                       :local/root "spools/shared"
                                       :root (.getCanonicalPath shared-root)
                                       :source (shared-source config-dir)
                                       :provenance :spools-edn}
                         'demo/local {:kind :local
                                      :local/root "spools/local"
                                      :root (.getCanonicalPath local-root)
                                      :source (shared-source config-dir)
                                      :provenance :spools-edn}}}
               (select-keys (runtime/approved rt) [:spools])))))))

(deftest approved-local-spools-override-shared-by-coordinate
  (with-runtime
    (fn [rt config-dir]
      (let [local-root (io/file config-dir "spools" "local")
            sha "0123456789abcdef0123456789abcdef01234567"]
        (write-spools! config-dir
                       (pr-str {:spools {'demo/family {:git/url "https://example.invalid/demo.git"
                                                       :git/sha sha
                                                       :git/tag "v2"
                                                       :roots {'demo/override "nested"}}}}))
        (write-local-spools! config-dir
                             (pr-str {:spools {'demo/family {:local/root "spools/local"
                                                             :claims "v2"}}}))
        (is (= {:kind :local
                :local/root "spools/local"
                :claims "v2"
                :root (.getPath (io/file (.getCanonicalPath local-root) "nested"))
                :source (local-source config-dir)
                :provenance :local-overlay}
               (get-in (runtime/approved rt) [:spools 'demo/override])))))))

(deftest approved-fails-when-spools-edn-is-not-a-file
  (with-runtime
    (fn [rt config-dir]
      (.mkdirs (io/file config-dir "spools.edn"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"malformed or unreadable"
                            (runtime/approved rt))))))

(deftest approved-normalizes-relative-and-absolute-roots
  (with-runtime
    (fn [rt config-dir]
      (let [relative-root (io/file config-dir "spools" "demo")
            absolute-root (io/file config-dir "external" "abs")]
        (.mkdirs relative-root)
        (.mkdirs absolute-root)
        (write-spools! config-dir
                       (pr-str {:spools {'demo/relative {:local/root "spools/demo"}
                                         'demo/absolute {:local/root (.getAbsolutePath absolute-root)}}}))
        (is (= {:spools {'demo/absolute {:kind :local
                                         :local/root (.getAbsolutePath absolute-root)
                                         :root (.getCanonicalPath absolute-root)
                                         :source (shared-source config-dir)
                                         :provenance :spools-edn}
                         'demo/relative {:kind :local
                                         :local/root "spools/demo"
                                         :root (.getCanonicalPath relative-root)
                                         :source (shared-source config-dir)
                                         :provenance :spools-edn}}}
               (select-keys (runtime/approved rt) [:spools])))))))

(deftest approved-expands-home-relative-roots
  (with-runtime
    (fn [rt config-dir]
      (let [home (System/getProperty "user.home")
            home-root (io/file home "dev" "projects" "my-lib")]
        (write-spools! config-dir (pr-str {:spools {'demo/home {:local/root "~/dev/projects/my-lib"}}}))
        (is (= {:spools {'demo/home {:kind :local
                                     :local/root "~/dev/projects/my-lib"
                                     :root (.getCanonicalPath home-root)
                                     :source (shared-source config-dir)
                                     :provenance :spools-edn}}}
               (select-keys (runtime/approved rt) [:spools])))))))

(deftest approved-canonicalizes-symlink-roots
  (with-runtime
    (fn [rt config-dir]
      (let [target (io/file config-dir "spools" "target")
            link (io/file config-dir "spools" "link")]
        (.mkdirs target)
        (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                                (make-array java.nio.file.attribute.FileAttribute 0))
        (write-spools! config-dir (pr-str {:spools {'demo/link {:local/root "spools/link"}}}))
        (is (= {:spools {'demo/link {:kind :local
                                     :local/root "spools/link"
                                     :root (.getCanonicalPath target)
                                     :source (shared-source config-dir)
                                     :provenance :spools-edn}}}
               (select-keys (runtime/approved rt) [:spools])))))))

(deftest approved-does-not-reject-missing-local-roots
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:kind :local
                                        :local/root "spools/missing"
                                        :root (.getCanonicalPath missing)
                                        :source (shared-source config-dir)
                                        :provenance :spools-edn}}}
               (select-keys (runtime/approved rt) [:spools])))))))

(deftest approved-normalizes-git-family-roots
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            sha "0123456789abcdef0123456789abcdef01234567"]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/git {:git/url "file:///tmp/repo"
                                                                   :git/sha sha
                                                                   :git/tag "v1"
                                                                   :roots {'demo/root "nested/spool"}}}}))
            (is (= {:spools {'demo/root {:kind :git
                                         :git/url "file:///tmp/repo"
                                         :git/sha sha
                                         :git/tag "v1"
                                         :root (.getPath (io/file cache-dir "skein" "spools" sha "nested/spool"))
                                         :source (shared-source config-dir)
                                         :provenance :spools-edn}}}
                   (select-keys (runtime/approved rt) [:spools])))))))))

(deftest approved-rejects-malformed-git-spools
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label entry pattern data-keys]
              [["absolute root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :roots {'demo/lib "/abs"}} #"root path" [:root-path]]
               ["parent root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :roots {'demo/lib "a/../b"}} #"root path" [:root-path]]
               ["empty roots" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :roots {}} #":roots must be a non-empty map" [:roots]]
               ["non-map roots" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :roots []} #":roots must be a non-empty map" [:roots]]
               ["non-symbol root lib" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :roots {"demo/lib" "."}} #"root lib must be a symbol" [:root-lib]]
               ["nil requires" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :requires nil} #":requires must be a map" [:requires]]
               ["non-map requires" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :requires []} #":requires must be a map" [:requires]]
               ["non-symbol required root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :requires {"demo/root" "v1"}} #"Required spool root must be a symbol" [:requires]]
               ["legacy deps root" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :deps/root "src"} #"replace :deps/root with :roots" [:deps/root]]
               ["short sha" {:git/url "u" :git/sha "012345"} #":git/sha" [:git/sha]]
               ["uppercase sha" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef0123456A"} #":git/sha" [:git/sha]]
               ["non-hex sha" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef0123456g"} #":git/sha" [:git/sha]]
               ["blank url" {:git/url " " :git/sha "0123456789abcdef0123456789abcdef01234567"} #":git/url" [:git/url]]
               ["unknown key" {:git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567" :extra true} #"unknown keys" [:keys]]
               ["mixed local and git" {:local/root "spools/demo" :git/url "u" :git/sha "0123456789abcdef0123456789abcdef01234567"} #"exactly one coordinate kind" [:entry]]]]
        (testing label
          (write-spools! config-dir (pr-str {:spools {'demo/lib entry}}))
          (try
            (runtime/approved rt)
            (is false "expected approved spool validation to fail")
            (catch clojure.lang.ExceptionInfo e
              (is (re-find pattern (ex-message e)))
              (doseq [k data-keys]
                (is (contains? (ex-data e) k))))))))))

(deftest approved-validates-every-declared-marker-through-the-strict-parser
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"]
        (doseq [[field entry]
                [[:git/tag {:git/url "u" :git/sha sha :git/tag "v0"}]
                 [:requires {:git/url "u" :git/sha sha :requires {'other/root "v0"}}]
                 [:skein/min {:git/url "u" :git/sha sha :skein/min "v0"}]]]
          (testing (name field)
            (write-spools! config-dir (pr-str {:spools {'demo/family entry}}))
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
              (is (re-find #"v0 is reserved" (ex-message ex)))
              (is (= field (:field (ex-data ex)))))))
        (doseq [marker [nil :v1 1 "" "1" "v" "v01" "v-1" "v1.0" "V1"]]
          (testing (pr-str marker)
            (write-spools! config-dir
                           (pr-str {:spools {'demo/family {:git/url "u"
                                                           :git/sha sha
                                                           :git/tag marker}}}))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"must match vN"
                                  (runtime/approved rt)))))
        (write-spools! config-dir
                       (pr-str {:spools {'target/family {:git/url "target"
                                                         :git/sha sha
                                                         :git/tag "v123456789012345678901234567890"}
                                         'client/family {:git/url "client"
                                                         :git/sha sha
                                                         :git/tag "v1"
                                                         :requires {'target/family
                                                                    "v123456789012345678901234567889"}}}}))
        (is (= #{'target/family 'client/family}
               (set (keys (:spools (runtime/approved rt))))))
        (write-spools! config-dir
                       (pr-str {:spools {'demo/family {:git/url "u"
                                                       :git/sha sha
                                                       :git/tag "v1"}}}))
        (write-local-spools! config-dir
                             (pr-str {:spools {'demo/family {:local/root "spools/demo"
                                                             :claims "v0"}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
          (is (re-find #"v0 is reserved" (ex-message ex)))
          (is (= :claims (:field (ex-data ex)))))))))

(deftest approved-normalizes-one-stage-one-record-per-family
  (let [source {:kind :shared :file "/tmp/spools.edn"}
        sha "0123456789abcdef0123456789abcdef01234567"]
    (is (= {:family 'demo/family
            :coordinate {:kind :git :git/url "u" :git/sha sha :git/tag "v3"}
            :roots-map {'demo/a "." 'demo/b "nested"}
            :requires {'other/root "v2"}
            :skein-min "v1"
            :claims nil
            :provenance :spools-edn
            :source source}
           (#'spool-sync/normalize-shared-family
            source
            'demo/family
            {:git/url "u"
             :git/sha sha
             :git/tag "v3"
             :roots {'demo/a "." 'demo/b "nested"}
             :requires {'other/root "v2"}
             :skein/min "v1"})))
    (is (s/valid? :skein.core.weaver.spool-sync/normalized-family
                  (#'spool-sync/normalize-shared-family
                   source 'demo/family {:git/url "u" :git/sha sha})))
    (is (= {'demo/local "."}
           (:roots-map (#'spool-sync/normalize-shared-family
                        source 'demo/local {:local/root "spools/local"}))))))

(deftest approved-rejects-duplicate-git-family-urls
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"]
        (write-spools! config-dir
                       (pr-str {:spools {'demo/a {:git/url "https://example.invalid/same.git"
                                                  :git/sha sha}
                                         'demo/b {:git/url "https://example.invalid/same.git"
                                                  :git/sha sha}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
          (is (re-find #"must not share :git/url" (ex-message ex)))
          (is (= #{'demo/a 'demo/b} (set (:families (ex-data ex))))))))))

(deftest approved-rejects-duplicate-root-lib-owners-before-requirements
  (with-runtime
    (fn [rt config-dir]
      (let [sha-a "0123456789abcdef0123456789abcdef01234567"
            sha-b "1123456789abcdef0123456789abcdef01234567"]
        (write-spools! config-dir
                       (pr-str {:spools {'demo/one {:git/url "one"
                                                    :git/sha sha-a
                                                    :roots {'shared/root "."}}
                                         'demo/two {:git/url "two"
                                                    :git/sha sha-b
                                                    :roots {'shared/root "nested"}
                                                    :requires {'missing/root "v1"}}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
          (is (= :duplicate-spool-root (:reason (ex-data ex))))
          (is (= 'shared/root (:root-lib (ex-data ex))))
          (is (= #{'demo/one 'demo/two} (set (:families (ex-data ex)))))
          (is (not (contains? (ex-data ex) :findings))))))))

(deftest approved-rejects-overlays-without-a-valid-claim-or-git-base
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"]
        (write-spools! config-dir
                       (pr-str {:spools {'demo/family {:git/url "u" :git/sha sha :git/tag "v1"}}}))
        (write-local-spools! config-dir
                             (pr-str {:spools {'demo/family {:local/root "spools/demo"}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
          (is (= :override-without-claim (:error (ex-data ex))))
          (is (re-find #"add :claims \"vN\"" (:fix (ex-data ex)))))
        (write-local-spools! config-dir
                             (pr-str {:spools {'other/family {:local/root "spools/other"
                                                              :claims "v1"}}}))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"must shadow a shared git family"
                              (runtime/approved rt)))))))

(deftest malformed-family-shape-gates-marker-validation
  (with-runtime
    (fn [rt config-dir]
      (write-spools! config-dir
                     (pr-str {:spools {'demo/family {:git/url "u"
                                                     :git/sha "short"
                                                     :git/tag "v0"
                                                     :unknown true}}}))
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (runtime/approved rt)))]
        (is (re-find #"unknown keys" (ex-message ex)))
        (is (not (re-find #"v0 is reserved" (ex-message ex))))))))

(deftest sync-refuses-all-unsatisfied-family-and-skein-floors
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"]
        (write-spools!
         config-dir
         (pr-str
          {:spools
           {'target/family {:git/url "https://example.invalid/target.git"
                            :git/sha sha
                            :git/tag "v2"
                            :roots {'target/root "."}}
            'target/unmarked {:local/root "spools/unmarked"}
            'client/one {:git/url "https://example.invalid/client-one.git"
                         :git/sha sha
                         :git/tag "v1"
                         :requires {'target/root "v3"
                                    'missing/root "v4"
                                    'target/unmarked "v2"}
                         :skein/min "v3"}
            'client/two {:git/url "https://example.invalid/client-two.git"
                         :git/sha sha
                         :git/tag "v1"
                         :requires {'target/root "v5"}}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (spool-sync/sync-approved-spools rt "v2")))
              data (ex-data ex)]
          (is (= :spool-requirements-unsatisfied (:reason data)))
          (is (= #{{:error :pin-below-minimum
                    :requirer 'client/one
                    :requires 'target/root
                    :minimum "v3"
                    :family 'target/family
                    :pinned "v2"}
                   {:error :required-root-not-approved
                    :requirer 'client/one
                    :requires 'missing/root
                    :minimum "v4"}
                   {:error :required-root-unmarked
                    :requirer 'client/one
                    :requires 'target/unmarked
                    :minimum "v2"
                    :family 'target/unmarked}
                   {:error :skein-below-minimum
                    :spool 'client/one
                    :skein/min "v3"
                    :running "v2"}
                   {:error :pin-below-minimum
                    :requirer 'client/two
                    :requires 'target/root
                    :minimum "v5"
                    :family 'target/family
                    :pinned "v2"}}
                 (set (:findings data))))
          (is (= {'target/family "v5"} (:suggestions data)))
          (is (= {:spools {}} (sync-state rt))))))))

(deftest runtime-sync-validates-skein-minimum-against-its-release-marker
  (let [family 'demo/skein-floor
        sha "0123456789abcdef0123456789abcdef01234567"
        write-floor! (fn [config-dir floor]
                       (let [root (write-local-lib! config-dir "skein-floor" 'demo.skein_floor)]
                         (write-spools! config-dir
                                        (pr-str {:spools {family {:git/url "https://example.invalid/skein-floor.git"
                                                                  :git/sha sha
                                                                  :git/tag "v1"
                                                                  :skein/min floor}}}))
                         (write-local-spools! config-dir
                                              (pr-str {:spools {family {:local/root "spools/skein-floor"
                                                                        :claims "v1"}}}))
                         root))]
    (testing "satisfied floor"
      (with-runtime
        {:release-marker "v2"}
        (fn [rt config-dir]
          (let [root (write-floor! config-dir "v2")
                result (sync-approved! rt)]
            (is (not (contains? result :pending-validations)))
            (is (= :loaded (get-in result [:spools family :status])))
            (is (= (.getCanonicalPath root) (get-in result [:spools family :root])))))))
    (testing "violated floor"
      (with-runtime
        {:release-marker "v1"}
        (fn [rt config-dir]
          (write-floor! config-dir "v2")
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
            (is (= :spool-requirements-unsatisfied (:reason (ex-data ex))))
            (is (= [{:error :skein-below-minimum
                     :spool family
                     :skein/min "v2"
                     :running "v1"}]
                   (:findings (ex-data ex))))))))
    (testing "unmarked runtime with a declared floor"
      (with-runtime
        (fn [rt config-dir]
          (write-floor! config-dir "v2")
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
            (is (= :release-marker-unavailable (:reason (ex-data ex))))
            (is (= [{:family family :skein/min "v2"}]
                   (:floors (ex-data ex))))))))
    (testing "unmarked runtime without a declared floor"
      (with-runtime
        (fn [rt config-dir]
          (let [root (write-local-lib! config-dir "no-skein-floor" 'demo.no_skein_floor)]
            (write-spools! config-dir
                           (pr-str {:spools {family {:local/root "spools/no-skein-floor"}}}))
            (is (= :loaded (get-in (sync-approved! rt) [:spools family :status])))
            (is (= (.getCanonicalPath root)
                   (get-in (sync-state rt) [:spools family :root])))))))))

(deftest malformed-stage-one-entry-gates-requirement-arithmetic
  (with-runtime
    (fn [rt config-dir]
      (write-spools!
       config-dir
       (pr-str {:spools {'demo/malformed {:git/url "u"
                                          :git/sha "short"
                                          :requires {'missing/root "v2"}}}}))
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (spool-sync/sync-approved-spools rt "v1")))]
        (is (re-find #":git/sha" (ex-message ex)))
        (is (not (contains? (ex-data ex) :findings)))
        (is (not (contains? (ex-data ex) :suggestions)))))))

(deftest event-helpers-register-list-replace-unregister-directly
  (with-runtime
    (fn [rt _]
      (is (= {:key :capture
              :types #{:strand/added}
              :fn 'skein.core.weaver.hooks-events-test/capture-event
              :metadata {:purpose :test}}
             (events/register-handler! rt :capture #{:strand/added} 'skein.core.weaver.hooks-events-test/capture-event {:purpose :test})))
      (is (= [:capture] (mapv :key (events/handlers rt))))
      (is (= {:key :capture
              :types #{:strand/updated}
              :fn 'skein.core.weaver.hooks-events-test/capture-event
              :metadata {}}
             (events/register-handler! rt :capture #{:strand/updated} 'skein.core.weaver.hooks-events-test/capture-event)))
      (is (= #{:strand/updated} (:types (first (events/handlers rt)))))
      (is (= {:unregistered :capture} (events/unregister-handler! rt :capture)))
      (is (= [] (events/handlers rt)))
      (is (= [] (events/recent-failures rt))))))

(deftest connected-client-can-register-weaver-only-spool-handler
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.event_handler_" suffix))
            lib (symbol (str "demo/event-handler-lib-" suffix))]
        (write-local-lib! config-dir "event-handler" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/event-handler"}}}))
        (is (= :applied
               (:status (client/call-world (get-in rt [:metadata :config-dir])
                                           {:timeout-ms 30000}
                                           :module! :events {:ns ns-sym :spools [lib]}))))
        (let [entry (client/call-world (get-in rt [:metadata :config-dir]) {:timeout-ms 30000}
                                       :register-event-handler! :lib #{:strand/added}
                                       (symbol (str ns-sym) "event-handler") {})]
          (is (= :lib (:key entry)))
          (is (= (symbol (str ns-sym) "event-handler") (:fn entry))))))))

(deftest approved-fails-loudly-on-structural-errors
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label content pattern]
              [["malformed EDN" "{:spools" #"malformed or unreadable"]
               ["unknown top-level key" (pr-str {:spools {} :extra true}) #"unknown top-level keys"]
               ["missing :spools" (pr-str {}) #"requires :spools map"]
               ["non-map :spools" (pr-str {:spools []}) #"requires :spools map"]
               ["non-symbol family" (pr-str {:spools {"demo/lib" {:local/root "spools/demo"}}}) #"family must be a symbol"]
               ["non-map entry" (pr-str {:spools {'demo/lib "spools/demo"}}) #"entry must be a map"]
               ["unknown per-lib key" (pr-str {:spools {'demo/lib {:local/root "spools/demo" :extra true}}}) #"unknown keys"]
               ["missing root" (pr-str {:spools {'demo/lib {}}}) #"exactly one coordinate kind"]
               ["non-string root" (pr-str {:spools {'demo/lib {:local/root 1}}}) #"requires non-blank string"]
               ["blank root" (pr-str {:spools {'demo/lib {:local/root "  "}}}) #"requires non-blank string"]]]
        (testing label
          (write-spools! config-dir content)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo pattern (runtime/approved rt))))))))

(deftest approved-applies-structural-validation-to-local-spools
  (with-runtime
    (fn [rt config-dir]
      (write-local-spools! config-dir (pr-str {:spools {'demo/lib {:local/root " "}}}))
      (try
        (runtime/approved rt)
        (is false "expected spools.local.edn structural validation to fail")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"requires non-blank string" (ex-message e)))
          (is (= (local-source config-dir) (select-keys (ex-data e) [:kind :file]))))))))

(deftest approved-fails-loudly-on-broken-local-spools-symlink
  (with-runtime
    (fn [rt config-dir]
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file config-dir "spools.local.edn"))
       (.toPath (io/file config-dir "missing-target.edn"))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"spools.local.edn is malformed or unreadable"
                            (runtime/approved rt))))))

(deftest sync-loads-approved-local-root-and-exposes-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.synced-" suffix))
            lib (symbol (str "demo/lib-" suffix))
            root (write-local-lib! config-dir "demo" ns-sym)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/demo"}}}))
        (is (= {:spools {lib {:lib lib
                              :family lib
                              :coordinate {:kind :local :local/root "spools/demo"}
                              :kind :local
                              :local/root "spools/demo"
                              :root (.getCanonicalPath root)
                              :source (shared-source config-dir)
                              :provenance :spools-edn
                              :status :loaded}}}
               (sync-approved! rt)))
        (is (= {:spools {lib {:lib lib
                              :family lib
                              :coordinate {:kind :local :local/root "spools/demo"}
                              :kind :local
                              :local/root "spools/demo"
                              :root (.getCanonicalPath root)
                              :source (shared-source config-dir)
                              :provenance :spools-edn
                              :status :loaded}}}
               (sync-state rt)))
        (is (= {:spools {lib {:lib lib
                              :family lib
                              :coordinate {:kind :local :local/root "spools/demo"}
                              :kind :local
                              :local/root "spools/demo"
                              :root (.getCanonicalPath root)
                              :source (shared-source config-dir)
                              :provenance :spools-edn
                              :status :already-available}}}
               (sync-approved! rt)))))))

;; Regression guard: a present legacy spool.edn, even with retired manifest
;; keys, must have no effect on sync results. The absent :manifest and
;; :unmet-needs assertions below prevent manifest machinery from creeping back.
(deftest sync-ignores-spool-manifest-files
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.ignored_manifest_" suffix))
            lib (symbol (str "demo/ignored-manifest-" suffix))
            root (write-local-lib! config-dir "ignored-manifest" ns-sym)]
        (write-spool-manifest! root {:coordinate 'demo/other
                                     :provides ['demo.missing]
                                     :needs {'demo/missing {:suggest {:git/url "file:///tmp/suggested"}}}
                                     :docs []})
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/ignored-manifest"}}}))
        (let [result (get-in (sync-approved! rt) [:spools lib])]
          (is (= :loaded (:status result)))
          (is (not (contains? result :manifest)))
          (is (not (contains? result :unmet-needs)))
          (is (= :loaded (:status (load-synced! rt :ignored/manifest {:ns ns-sym :spools [lib]})))))))))

(deftest daemon-init-loads-module-with-the-spool-classloader
  (let [root (temp-config-dir)
        suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
        ns-sym (symbol (str "demo.init-synced-" suffix))
        lib (symbol (str "demo/init-lib-" suffix))
        ns-path (-> (str ns-sym)
                    (str/replace \- \_)
                    (str/replace \. java.io.File/separatorChar))
        module-key (keyword (str "init-synced-" suffix))]
    (try
      (t/with-weaver-world [ctx {:root (.getPath root)
                                 :spools-edn {:spools {lib {:local/root "spools/init-demo"}}}
                                 :files {(str "spools/init-demo/src/" ns-path ".clj")
                                         (str "(ns " ns-sym ")\n(defn marker [] :synced-lib-loaded)\n")
                                         "spools/init-demo/deps.edn" "{:paths [\"src\"]}\n"}
                                 :init (source-file/render-forms
                                        ['(require '[skein.api.current.alpha :as current]
                                                   '[skein.api.runtime.alpha :as runtime])
                                         `(runtime/module! (current/runtime) ~module-key
                                                           {:ns '~ns-sym :spools ['~lib]})])}]
        (is (= :synced (get-in (t/repl! ctx
                                        '(do
                                           (require '[skein.api.current.alpha :as current]
                                                    '[skein.api.runtime.alpha :as runtime])
                                           (runtime/status (current/runtime))))
                               [:root/outcomes lib :status])))
        (is (= :synced-lib-loaded
               (t/repl! ctx `((requiring-resolve '~(symbol (str ns-sym "/marker")))))))
        (testing "repl! forms run under the spool classloader, so synced namespaces are requirable"
          (is (= :synced-lib-loaded
                 (t/repl! ctx `(do
                                 (require '~ns-sym)
                                 (~(symbol (str ns-sym) "marker"))))))))
      (finally
        (delete-tree! root)))))

(deftest sync-clears-stale-state-before-structural-failure
  (with-runtime
    (fn [rt config-dir]
      (let [missing (io/file config-dir "spools" "missing")]
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}}}))
        (is (= {:spools {'demo/missing {:lib 'demo/missing
                                        :family 'demo/missing
                                        :coordinate {:kind :local :local/root "spools/missing"}
                                        :kind :local
                                        :local/root "spools/missing"
                                        :root (.getCanonicalPath missing)
                                        :source (shared-source config-dir)
                                        :provenance :spools-edn
                                        :status :failed
                                        :reason :missing-root}}}
               (sync-approved! rt)))
        (write-spools! config-dir "{:spools")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed or unreadable" (sync-approved! rt)))
        (is (= {:spools {}} (sync-state rt)))))))

(deftest sync-records-runtime-add-failures-as-failed-outcomes
  (with-runtime
    (fn [rt config-dir]
      (let [root (io/file config-dir "spools" "bad-deps")]
        (.mkdirs root)
        (spit (io/file root "deps.edn") "{:paths [}")
        (write-spools! config-dir (pr-str {:spools {'demo/bad-deps {:local/root "spools/bad-deps"}}}))
        (let [result (get-in (sync-approved! rt) [:spools 'demo/bad-deps])]
          (is (= {:lib 'demo/bad-deps
                  :kind :local
                  :local/root "spools/bad-deps"
                  :root (.getCanonicalPath root)
                  :source (shared-source config-dir)
                  :status :failed
                  :reason :runtime-add-failed}
                 (select-keys result [:lib :kind :local/root :root :source :status :reason])))
          (is (string? (:message result)))
          (is (string? (:class result))))))))

(deftest sync-records-missing-and-unreadable-roots-as-failed-outcomes
  (with-runtime
    (fn [rt config-dir]
      (let [not-dir (io/file config-dir "spools" "not-dir")]
        (.mkdirs (.getParentFile not-dir))
        (spit not-dir "not a directory")
        (write-spools! config-dir (pr-str {:spools {'demo/missing {:local/root "spools/missing"}
                                                    'demo/not-dir {:local/root "spools/not-dir"}}}))
        (is (= {:spools {'demo/missing {:lib 'demo/missing
                                        :family 'demo/missing
                                        :coordinate {:kind :local :local/root "spools/missing"}
                                        :kind :local
                                        :local/root "spools/missing"
                                        :root (.getCanonicalPath (io/file config-dir "spools" "missing"))
                                        :source (shared-source config-dir)
                                        :provenance :spools-edn
                                        :status :failed
                                        :reason :missing-root}
                         'demo/not-dir {:lib 'demo/not-dir
                                        :family 'demo/not-dir
                                        :coordinate {:kind :local :local/root "spools/not-dir"}
                                        :kind :local
                                        :local/root "spools/not-dir"
                                        :root (.getCanonicalPath not-dir)
                                        :source (shared-source config-dir)
                                        :provenance :spools-edn
                                        :status :failed
                                        :reason :unreadable-root}}}
               (sync-approved! rt)))))))

(deftest sync-git-missing-root-outcome-is-kind-shaped
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            sha "0123456789abcdef0123456789abcdef01234567"]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/git {:git/url "file:///tmp/repo"
                                                                   :git/sha sha
                                                                   :git/tag "v1"
                                                                   :roots {'demo/root "spool"}}}}))
            (let [result (get-in (sync-approved! rt) [:spools 'demo/root])]
              (is (= {:lib 'demo/root
                      :kind :git
                      :git/url "file:///tmp/repo"
                      :git/sha sha
                      :git/tag "v1"
                      :root (.getPath (io/file cache-dir "skein" "spools" sha "spool"))
                      :source (shared-source config-dir)
                      :status :failed
                      :reason :fetch-failed}
                     (select-keys result [:lib :kind :git/url :git/sha :git/tag :root :source :status :reason])))
              (is (integer? (:exit result)))
              (is (string? (:stderr result)))
              (is (not (contains? result :local/root))))))))))

(deftest sync-converts-expected-materialization-io-failures
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"
            lib 'demo/io-failure
            original @#'spool-sync/materialize-git-spool!]
        (write-spools! config-dir
                       (pr-str {:spools {lib {:git/url "file:///tmp/io-failure"
                                              :git/sha sha}}}))
        (try
          (alter-var-root #'spool-sync/materialize-git-spool!
                          (constantly (fn [_] (throw (java.io.IOException. "disk unavailable")))))
          (let [result (get-in (sync-approved! rt) [:spools lib])]
            (is (= :failed (:status result)))
            (is (= :fetch-failed (:reason result)))
            (is (= 1 (:exit result)))
            (is (= "disk unavailable" (:stderr result))))
          (finally
            (alter-var-root #'spool-sync/materialize-git-spool! (constantly original))))))))

(deftest sync-lets-unexpected-materialization-throwables-escape
  (with-runtime
    (fn [rt config-dir]
      (let [sha "0123456789abcdef0123456789abcdef01234567"
            lib 'demo/programming-failure
            original @#'spool-sync/materialize-git-spool!]
        (write-spools! config-dir
                       (pr-str {:spools {lib {:git/url "file:///tmp/programming-failure"
                                              :git/sha sha}}}))
        (try
          (doseq [failure [(AssertionError. "broken invariant")
                           (InterruptedException. "cancelled")
                           (ex-info "unexpected ex-info" {:bug true})]]
            (alter-var-root #'spool-sync/materialize-git-spool!
                            (constantly (fn [_] (throw failure))))
            (let [thrown (try
                           (sync-approved! rt)
                           nil
                           (catch Throwable t t))]
              (is (identical? failure thrown))
              (is (= {:spools {}} (sync-state rt)))))
          (finally
            (alter-var-root #'spool-sync/materialize-git-spool! (constantly original))))))))

(deftest sync-fetches-git-spool-and-uses-cache-hit-without-origin
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_fetch_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            url (file-url repo)
            lib 'demo/git-fetch]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= :fetched (get-in (sync-state rt) [:spools lib :fetch])))
            (is (false? (.exists (io/file cache-dir "skein" "spools" sha ".git"))))
            (delete-tree! repo)
            (let [result (get-in (sync-approved! rt) [:spools lib])]
              (is (= :already-available (:status result)))
              (is (= :cached (:fetch result))))))))))

(deftest sync-refetches-advertised-refs-when-direct-sha-fetch-misses
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_refetch_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (spit (io/file repo "after-initial-cache.txt") "fresh")
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "fresh sha")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            url (file-url repo)
            lib 'demo/git-refetch
            original-run-git @#'spool-sync/run-git
            exact-sha-fetches (atom 0)]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (try
              (alter-var-root
               #'spool-sync/run-git
               (constantly
                (fn [dir & args]
                  (if (and (= "fetch" (first args))
                           (= [url sha] (take-last 2 args))
                           (< (swap! exact-sha-fetches inc) 3))
                    {:exit 128 :stderr "simulated stale remote cache miss"}
                    (apply original-run-git dir args)))))
              (let [result (get-in (sync-approved! rt) [:spools lib])]
                (is (= :loaded (:status result)))
                (is (= :fetched (:fetch result)))
                (is (= 2 @exact-sha-fetches))
                (is (.isFile (io/file cache-dir "skein" "spools" sha "after-initial-cache.txt"))))
              (finally
                (alter-var-root #'spool-sync/run-git (constantly original-run-git))))))))))

(deftest sync-git-unreachable-sha-names-cache-path-and-remote
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            sha (init-git-repo! repo 'demo.unreachable_sha)
            unknown (str/join (repeat 40 \a))]
        (is (not= sha unknown))
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/unreachable {:git/url (file-url repo) :git/sha unknown}}}))
            (let [result (get-in (sync-approved! rt) [:spools 'demo/unreachable])]
              (is (= :failed (:status result)))
              (is (= :fetch-failed (:reason result)))
              (is (= (file-url repo) (:remote result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" unknown)) (:cache-path result)))
              (is (string? (:stderr result)))
              (is (not (contains? result :initial-stderr))))))))))

(deftest sync-git-concurrent-cache-publish-race-loads-winner-tree
  (with-runtime
    (fn [rt config-dir]
      (let [cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_publish_race_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha "0123456789abcdef0123456789abcdef01234567"
            url "file:///tmp/race-winner-supplies-cache"
            lib 'demo/git-publish-race]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url url :git/sha sha}}}))
            (let [cache-root (io/file cache-dir "skein" "spools" sha)
                  original-checkout @#'spool-sync/checkout-git-spool!]
              (try
                (alter-var-root
                 #'spool-sync/checkout-git-spool!
                 (constantly
                  (fn [_entry tmp _cache-root]
                    ;; The pre-check in materialize-git-spool! has already
                    ;; missed. Simulate a concurrent winner publishing a valid,
                    ;; non-empty tree immediately before this materializer's
                    ;; atomic move, so the loser must treat the move failure as
                    ;; a cache hit rather than :fetch-failed.
                    (write-git-lib! cache-root ns-sym)
                    (spit (io/file tmp "loser.txt") "loser"))))
                (let [result (get-in (sync-approved! rt) [:spools lib])]
                  (is (= :loaded (:status result)))
                  (is (= :cached (:fetch result)))
                  (is (= :loaded (:status (load-synced! rt :race/winner {:ns ns-sym :spools [lib]})))))
                (finally
                  (alter-var-root #'spool-sync/checkout-git-spool! (constantly original-checkout)))))))))))

(deftest sync-rejects-deps-edn-paths-escaping-the-spool-root
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.escape_" suffix))
            lib (symbol (str "demo/escape-" suffix))
            root (write-local-lib! config-dir (str "escape-" suffix) ns-sym)
            outside (io/file config-dir "outside-src")]
        (.mkdirs outside)
        (spit (io/file root "deps.edn") (pr-str {:paths ["src" "../../outside-src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/escape-" suffix)}}}))
        (let [result (get-in (sync-approved! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"stay inside the spool root" (:message result))))))))

(deftest sync-lets-unexpected-root-validation-throwables-escape
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.validation_failure_" suffix))
            lib (symbol (str "demo/validation-failure-" suffix))
            original @#'spool-sync/root-paths]
        (write-local-lib! config-dir (str "validation-failure-" suffix) ns-sym)
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root
                                              (str "spools/validation-failure-" suffix)}}}))
        (try
          (doseq [failure [(AssertionError. "broken invariant")
                           (InterruptedException. "cancelled")
                           (IllegalStateException. "unexpected state")]]
            (alter-var-root #'spool-sync/root-paths
                            (constantly (fn [_] (throw failure))))
            (let [thrown (try
                           (sync-approved! rt)
                           nil
                           (catch Throwable t t))]
              (is (identical? failure thrown))
              (is (= {:spools {}} (sync-state rt)))))
          (finally
            (alter-var-root #'spool-sync/root-paths (constantly original))))))))

(deftest sync-accepts-deps-edn-path-naming-the-spool-root-itself
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.rootpath_" suffix))
            lib (symbol (str "demo/rootpath-" suffix))
            root (write-local-lib! config-dir (str "rootpath-" suffix) ns-sym)]
        (spit (io/file root "deps.edn") (pr-str {:paths ["." "src"]}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/rootpath-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (sync-approved! rt) [:spools lib :status])))))))

(deftest sync-approved-local-spool-loads-maven-deps-from-shared-config
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.shared_local_deps_" suffix))
            lib (symbol (str "demo/shared-local-deps-" suffix))
            root (write-local-lib! config-dir (str "shared-local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/shared-local-deps-" suffix)}}}))
        (is (#{:loaded :already-available}
             (get-in (sync-approved! rt) [:spools lib :status])))))))

;; Under stateless resolution, :loaded/:already-available is driven by whether a
;; sync newly added the root's source dir or its directly-declared Maven jars to
;; the classloader (DELTA-Sor-001.CC1). A fresh root loads via its source dir; an
;; unchanged re-sync is already-available; adding a Maven dep whose jar is absent
;; flips the root back to :loaded even though its source dir is already present.
(deftest sync-status-reflects-newly-added-source-and-maven-jars
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.added_maven_dep_" suffix))
            lib (symbol (str "demo/added-maven-dep-" suffix))
            root (write-local-lib! config-dir (str "added-maven-dep-" suffix) ns-sym)
            fake-jar (str (io/file config-dir "fake" (str "data.csv-1.1.0-" suffix ".jar")))
            universes (atom [])
            resolver (fn [universe]
                       (swap! universes conj universe)
                       (if (contains? universe 'org.clojure/data.csv)
                         {'org.clojure/data.csv {:mvn/version "1.1.0" :paths [fake-jar]}}
                         {}))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/added-maven-dep-" suffix)}}}))
        (with-resolver
          resolver
          (fn []
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= :already-available (get-in (sync-approved! rt) [:spools lib :status])))
            (spit (io/file root "deps.edn")
                  (pr-str {:paths ["src"]
                           :deps {'org.clojure/data.csv {:mvn/version "1.1.0"}}}))
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= [{} {} {'org.clojure/data.csv {:mvn/version "1.1.0"}}]
                   @universes))))))))

(deftest sync-refuses-maven-version-bump-for-loaded-coordinate
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.maven_bump_" suffix))
            lib (symbol (str "demo/maven-bump-" suffix))
            root (write-local-lib! config-dir (str "maven-bump-" suffix) ns-sym)
            version (atom "1.0.0")
            resolver (fn [universe]
                       (when (contains? universe 'org.example/loaded)
                         {'org.example/loaded {:mvn/version @version
                                               :paths [(str (io/file config-dir "fake" (str "loaded-" @version ".jar")))]}}))]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.example/loaded {:mvn/version "1.0.0"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/maven-bump-" suffix)}}}))
        (with-resolver
          resolver
          (fn []
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (spit (io/file root "deps.edn") "{:paths [\"src\"]")
            (is (= :failed (get-in (sync-approved! rt) [:spools lib :status])))
            (reset! version "2.0.0")
            (spit (io/file root "deps.edn")
                  (pr-str {:paths ["src"]
                           :deps {'org.example/loaded {:mvn/version "2.0.0"}}}))
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
                  data (ex-data ex)
                  bump (get-in data [:diff :maven-version-bumps 0])]
              (is (= :non-additive-sync-diff (:reason data)))
              (is (= 'org.example/loaded (:coordinate bump)))
              (is (= "1.0.0" (:previous-version bump)))
              (is (= "2.0.0" (:new-version bump)))
              (is (str/includes? (:remedy data) "next weaver generation")))))))))

(deftest sync-allows-unchanged-and-new-maven-versions
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.maven_unchanged_" suffix))
            lib (symbol (str "demo/maven-unchanged-" suffix))
            root (write-local-lib! config-dir (str "maven-unchanged-" suffix) ns-sym)
            resolver (fn [universe]
                       (cond-> {'org.example/loaded {:mvn/version "1.0.0"
                                                     :paths [(str (io/file config-dir "fake" "loaded-1.0.0.jar"))]}}
                         (contains? universe 'org.example/new)
                         (assoc 'org.example/new {:mvn/version "2.0.0"
                                                  :paths [(str (io/file config-dir "fake" "new-2.0.0.jar"))]})))]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.example/loaded {:mvn/version "1.0.0"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/maven-unchanged-" suffix)}}}))
        (with-resolver
          resolver
          (fn []
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= :already-available (get-in (sync-approved! rt) [:spools lib :status])))
            (spit (io/file root "deps.edn")
                  (pr-str {:paths ["src"]
                           :deps {'org.example/loaded {:mvn/version "1.0.0"}
                                  'org.example/new {:mvn/version "2.0.0"}}}))
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))))))))

(deftest sync-refuses-resolved-maven-coordinate-without-version
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.maven_missing_version_" suffix))
            lib (symbol (str "demo/maven-missing-version-" suffix))
            root (write-local-lib! config-dir (str "maven-missing-version-" suffix) ns-sym)
            version? (atom true)
            resolver (fn [universe]
                       (when (contains? universe 'org.example/loaded)
                         {'org.example/loaded (cond-> {:paths [(str (io/file config-dir "fake" "loaded.jar"))]}
                                                @version? (assoc :mvn/version "1.0.0"))}))]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.example/loaded {:mvn/version "1.0.0"}}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/maven-missing-version-" suffix)}}}))
        (with-resolver
          resolver
          (fn []
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= {'org.example/loaded "1.0.0"}
                   @(:approved-spool-generation-maven rt)))
            (reset! version? false)
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
                  data (ex-data ex)]
              (is (= "Resolved Maven coordinate must declare string :mvn/version" (ex-message ex)))
              (is (= 'org.example/loaded (:lib data)))
              (is (= {:paths [(str (io/file config-dir "fake" "loaded.jar"))]} (:coord data)))
              (is (= {'org.example/loaded "1.0.0"}
                     @(:approved-spool-generation-maven rt))))))))))

(deftest sync-records-pending-generation-for-removed-loaded-root
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.pending_removed_" suffix))
            lib (symbol (str "demo/pending-removed-" suffix))]
        (write-local-lib! config-dir (str "pending-removed-" suffix) ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/pending-removed-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (write-spools! config-dir (pr-str {:spools {}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              data (ex-data ex)]
          (is (= :non-additive-sync-diff (:reason data)))
          (is (= [lib] (mapv :lib (get-in data [:diff :removed-roots]))))
          (is (str/includes? (:remedy data) "next weaver generation"))
          (is (= (:pending-generation data) (:pending-generation (sync-state rt)))))))))

(deftest sync-skips-namespace-less-source-files-while-classifying-diffs
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.nsless_source_" suffix))
            lib (symbol (str "demo/nsless-source-" suffix))
            root (write-local-lib! config-dir (str "nsless-source-" suffix) ns-sym)]
        (spit (io/file root "src" "data_readers.clj") "{demo/tag demo.reader/read}\n")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/nsless-source-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :already-available (get-in (sync-approved! rt) [:spools lib :status])))))))

(deftest sync-reports-still-approved-validation-failure-as-per-root-failure
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.failed_still_approved_" suffix))
            lib (symbol (str "demo/failed-still-approved-" suffix))
            root (write-local-lib! config-dir (str "failed-still-approved-" suffix) ns-sym)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/failed-still-approved-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]")
        (let [result (sync-approved! rt)]
          (is (= :failed (get-in result [:spools lib :status])))
          (is (str/includes? (get-in result [:spools lib :message]) "EOF"))
          (is (nil? (:pending-generation result))))))))

(deftest sync-records-pending-generation-for-redefined-loaded-root
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.pending_redefined_" suffix))
            lib (symbol (str "demo/pending-redefined-" suffix))
            root (io/file config-dir "spools" (str "pending-redefined-" suffix))
            src-file (write-spool-ns! root ns-sym (str "(ns " ns-sym ")\n(defn marker [] :v1)\n"))]
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/pending-redefined-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt (keyword (str "pending-redefined-" suffix))
                                              {:ns ns-sym :spools [lib]}))))
        (spit src-file (str "(ns " ns-sym ")\n(defn marker [] :v2)\n"))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              data (ex-data ex)]
          (is (= :non-additive-sync-diff (:reason data)))
          (is (= [lib] (mapv :lib (get-in data [:diff :redefinitions]))))
          (is (= [ns-sym] (get-in data [:diff :redefinitions 0 :loaded-namespaces])))
          (is (str/includes? (ex-message ex) "recorded")))))))

(deftest sync-reports-retained-spool-state-from-older-generation
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.retained_state_" suffix))
            lib (symbol (str "demo/retained-state-" suffix))]
        (write-local-lib! config-dir (str "retained-state-" suffix) ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/retained-state-" suffix)}}}))
        (runtime/spool-state rt ::retained (constantly {:shape :old}))
        (swap! (:spool-state rt) update ::retained vary-meta assoc :skein.runtime/generation "older-generation")
        (let [result (sync-approved! rt)]
          (is (= :loaded (get-in result [:spools lib :status])))
          (is (= [{:key ::retained
                   :generation "older-generation"
                   :current-generation (:generation-id rt)}]
                 (:retained-spool-state result))))))))

(deftest sync-reports-untagged-retained-spool-state
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.untagged_state_" suffix))
            lib (symbol (str "demo/untagged-state-" suffix))]
        (write-local-lib! config-dir (str "untagged-state-" suffix) ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/untagged-state-" suffix)}}}))
        (runtime/spool-state rt ::untagged (fn [] (Object.)))
        (let [result (sync-approved! rt)]
          (is (= :loaded (get-in result [:spools lib :status])))
          (is (= [{:key ::untagged
                   :generation :unknown
                   :current-generation (:generation-id rt)
                   :reason :untagged}]
                 (:retained-spool-state result))))))))

(deftest sync-approved-spool-conflicts-fail-unless-pinned-by-mvn-overrides
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib-a (symbol (str "demo/conflict-a-" suffix))
            lib-b (symbol (str "demo/conflict-b-" suffix))
            root-a (write-local-lib! config-dir (str "conflict-a-" suffix)
                                     (symbol (str "demo.conflict_a_" suffix)))
            root-b (write-local-lib! config-dir (str "conflict-b-" suffix)
                                     (symbol (str "demo.conflict_b_" suffix)))
            universes (atom [])]
        (spit (io/file root-a "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.4.0"}}}))
        (spit (io/file root-b "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"}}}))
        (write-spools! config-dir
                       (pr-str {:spools {lib-a {:local/root (str "spools/conflict-a-" suffix)}
                                         lib-b {:local/root (str "spools/conflict-b-" suffix)}}}))
        (with-resolver
          (fn [universe]
            (swap! universes conj universe)
            {})
          (fn []
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
                  data (ex-data ex)]
              (is (str/includes? (ex-message ex) "org.clojure/data.json"))
              (is (= 'org.clojure/data.json (:lib data)))
              (is (= #{lib-a lib-b} (set (:roots data))))
              (is (= [] @universes)))
            (spit (io/file root-b "deps.edn")
                  (pr-str {:paths ["src"]
                           :deps {'org.clojure/data.json {:mvn/version "2.4.0"
                                                          :exclusions ['org.clojure/clojure]}}}))
            (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
                  data (ex-data ex)]
              (is (str/includes? (ex-message ex) "coordinate conflict"))
              (is (= 'org.clojure/data.json (:lib data)))
              (is (= #{{:mvn/version "2.4.0"}
                       {:mvn/version "2.4.0" :exclusions ['org.clojure/clojure]}}
                     (set (:coordinates data)))))
            (write-spools! config-dir
                           (pr-str {:spools {lib-a {:local/root (str "spools/conflict-a-" suffix)}
                                             lib-b {:local/root (str "spools/conflict-b-" suffix)}}
                                    :mvn-overrides {'org.clojure/data.json {:mvn/version "2.5.1"}}}))
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib-a :status])))
            (is (= [{'org.clojure/data.json {:mvn/version "2.5.1"}}]
                   @universes))))))))

(deftest sync-approved-spool-validates-mvn-overrides-policy
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "demo/bad-override-" suffix))]
        (write-local-lib! config-dir (str "bad-override-" suffix)
                          (symbol (str "demo.bad_override_" suffix)))
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root (str "spools/bad-override-" suffix)}}
                                :mvn-overrides {'org.clojure/data.json {:local/root "../nope"}}}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              data (ex-data ex)]
          (is (str/includes? (ex-message ex) "source-bearing coordinates"))
          (is (= :shared (:kind data)))
          (is (= 'org.clojure/data.json (:lib data))))))))

(deftest sync-approved-local-overlay-rejects-source-bearing-deps
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.local_deps_" suffix))
            lib (symbol (str "demo/local-deps-" suffix))
            root (write-local-lib! config-dir (str "local-deps-" suffix) ns-sym)]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'demo/source {:local/root "../other"}}}))
        (write-spools! config-dir
                       (pr-str {:spools {lib {:git/url "https://example.invalid/local-deps.git"
                                              :git/sha "0123456789abcdef0123456789abcdef01234567"
                                              :git/tag "v1"}}}))
        (write-local-spools! config-dir
                             (pr-str {:spools {lib {:local/root (str "spools/local-deps-" suffix)
                                                    :claims "v1"}}}))
        (let [result (get-in (sync-approved! rt) [:spools lib])]
          (is (= :failed (:status result)))
          (is (= :runtime-add-failed (:reason result)))
          (is (re-find #"source-bearing coordinates" (:message result)))
          (is (= {:keys [:local/root] :lib 'demo/source}
                 (select-keys (get result :data) [:keys :lib]))))))))

(deftest sync-approved-spool-rejects-mutable-versions-and-repo-redirection
  (with-runtime
    (fn [rt config-dir]
      (doseq [[label deps-edn pattern]
              [["snapshot" {:paths ["src"] :deps {'org.example/lib {:mvn/version "1.0.0-SNAPSHOT"}}} #"mutable Maven versions"]
               ["release" {:paths ["src"] :deps {'org.example/lib {:mvn/version "RELEASE"}}} #"mutable Maven versions"]
               ["latest" {:paths ["src"] :deps {'org.example/lib {:mvn/version "LATEST"}}} #"mutable Maven versions"]
               ["repos" {:paths ["src"] :mvn/repos {"central" {:url "file:/tmp/nope"}} :deps {}} #"top-level :mvn/repos"]
               ["local repo" {:paths ["src"] :mvn/local-repo "/tmp/nope" :deps {}} #"top-level :mvn/local-repo"]]]
        (testing label
          (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
                ns-sym (symbol (str "demo.bad_deps_" suffix))
                lib (symbol (str "demo/bad-deps-" suffix))
                root (write-local-lib! config-dir (str "bad-deps-" suffix) ns-sym)]
            (spit (io/file root "deps.edn") (pr-str deps-edn))
            (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/bad-deps-" suffix)}}}))
            (let [result (get-in (sync-approved! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :runtime-add-failed (:reason result)))
              (is (re-find pattern (:message result))))))))))

(deftest sync-approved-spool-allows-maven-refinement-keys-and-ignores-aliases
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.maven_refinement_" suffix))
            lib (symbol (str "demo/maven-refinement-" suffix))
            root (write-local-lib! config-dir (str "maven-refinement-" suffix) ns-sym)
            universes (atom [])]
        (spit (io/file root "deps.edn")
              (pr-str {:paths ["src"]
                       :deps {'org.clojure/data.json {:mvn/version "2.5.1"
                                                      :exclusions ['org.clojure/clojure]
                                                      :classifier "sources"
                                                      :extension "jar"}}
                       :aliases {:dev {:extra-deps {'demo/source {:local/root "../ignored"}}}}
                       :ignored/top-level {:also :ignored}}))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/maven-refinement-" suffix)}}}))
        (with-resolver
          (fn [universe]
            (swap! universes conj universe)
            {})
          (fn []
            (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
            (is (= [{'org.clojure/data.json {:mvn/version "2.5.1"
                                             :exclusions ['org.clojure/clojure]
                                             :classifier "sources"
                                             :extension "jar"}}]
                   @universes))))))))

(deftest sync-git-spool-rejects-source-bearing-deps
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_deps_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (spit (io/file repo "deps.edn") (pr-str {:paths ["src"] :deps {'demo/source {:git/url "file:///tmp/repo" :git/sha (str/join (repeat 40 \a))}}}))
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "deps")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-deps]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (sync-approved! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :runtime-add-failed (:reason result)))
              (is (re-find #"source-bearing coordinates" (:message result)))
              (is (= :fetched (:fetch result))))))))))

(deftest delete-tree-removes-symlinks-without-following-them
  (let [base (.toFile (java.nio.file.Files/createTempDirectory "skein-delete-tree" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (io/file base "outside")
        outside-file (io/file outside "keep.txt")
        tree (io/file base "tree")]
    (try
      (.mkdirs outside)
      (spit outside-file "keep")
      (.mkdirs tree)
      (spit (io/file tree "inner.txt") "inner")
      (java.nio.file.Files/createSymbolicLink
       (.toPath (io/file tree "link-out"))
       (.toPath outside)
       (make-array java.nio.file.attribute.FileAttribute 0))
      (#'spool-sync/delete-tree! tree)
      (is (false? (.exists tree)))
      (is (true? (.exists outside-file)))
      (finally
        (delete-tree! base)))))

;; Regression guard: a fetched git spool may contain a legacy spool.edn, but
;; sync results are driven solely by the approved coordinate and fetch outcome.
;; The absent :manifest assertion prevents manifest machinery from creeping back.
(deftest sync-git-ignores-spool-manifest-and-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_ignored_manifest_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            _ (init-git-repo! repo ns-sym)
            _ (write-spool-manifest! repo [])
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "manifest")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            lib 'demo/git-ignored-manifest]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo) :git/sha sha}}}))
            (let [result (get-in (sync-approved! rt) [:spools lib])]
              (is (= :loaded (:status result)))
              (is (= :fetched (:fetch result)))
              (is (not (contains? result :manifest))))))))))

(deftest sync-git-unknown-sha-is-fetch-failed
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "repo")
            cache-dir (io/file config-dir "cache")
            sha (init-git-repo! repo 'demo.unknown_sha)
            unknown (str/join (repeat 40 \a))]
        (is (not= sha unknown))
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/unknown {:git/url (file-url repo) :git/sha unknown}}}))
            (let [result (get-in (sync-approved! rt) [:spools 'demo/unknown])]
              (is (= :failed (:status result)))
              (is (= :fetch-failed (:reason result)))
              (is (integer? (:exit result)))
              (is (string? (:stderr result))))))))))

(deftest sync-git-tag-verification-loads-and-rejects-mismatch
  (with-runtime
    (fn [rt config-dir]
      (let [matching-repo (io/file config-dir "matching-repo")
            mismatching-repo (io/file config-dir "mismatching-repo")
            cache-dir (io/file config-dir "cache")
            matching-sha (init-git-repo! matching-repo 'demo.matching_tag :annotated-tag "v1")
            mismatching-sha (init-git-repo! mismatching-repo 'demo.mismatching_tag :tag "v1")]
        (spit (io/file mismatching-repo "extra.txt") "new")
        (run-git! mismatching-repo "add" ".")
        (run-git! mismatching-repo "commit" "-m" "second")
        (let [new-sha (str/trim (run-git! mismatching-repo "rev-parse" "HEAD"))]
          (with-cache-base
            cache-dir
            (fn []
              (write-spools! config-dir (pr-str {:spools {'demo/matching {:git/url (file-url matching-repo)
                                                                          :git/sha matching-sha
                                                                          :git/tag "v1"}
                                                          'demo/mismatching {:git/url (file-url mismatching-repo)
                                                                             :git/sha new-sha
                                                                             :git/tag "v1"}}}))
              (let [results (:spools (sync-approved! rt))]
                (is (= :loaded (get-in results ['demo/matching :status])))
                (is (= :fetched (get-in results ['demo/matching :fetch])))
                (is (= :failed (get-in results ['demo/mismatching :status])))
                (is (= :tag-mismatch (get-in results ['demo/mismatching :reason])))
                (is (= new-sha (get-in results ['demo/mismatching :expected])))
                (is (= mismatching-sha (get-in results ['demo/mismatching :actual])))
                (is (false? (.exists (io/file cache-dir "skein" "spools" new-sha))))))))))))

(deftest sync-git-deps-root-selects-monorepo-subdir
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "mono")
            spool-root (io/file repo "nested" "spool")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_mono_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (do
                  (.mkdirs repo)
                  (run-git! repo "init")
                  (run-git! repo "config" "user.email" "skein-test@example.invalid")
                  (run-git! repo "config" "user.name" "Skein Test")
                  (write-git-lib! spool-root ns-sym)
                  (spit (io/file repo "README.md") "root only")
                  (run-git! repo "add" ".")
                  (run-git! repo "commit" "-m" "monorepo")
                  (str/trim (run-git! repo "rev-parse" "HEAD")))]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {'demo/mono {:git/url (file-url repo)
                                                                    :git/sha sha
                                                                    :roots {'demo/mono "nested/spool"}}}}))
            (let [result (get-in (sync-approved! rt) [:spools 'demo/mono])]
              (is (= :loaded (:status result)))
              (is (= :fetched (:fetch result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" sha "nested/spool")) (:root result))))))))))

(deftest sync-materializes-family-once-and-vets-each-root
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "family-repo")
            good-root (io/file repo "good")
            bad-root (io/file repo "bad")
            cache-dir (io/file config-dir "cache")
            family 'demo/family
            coordinate {:kind :git}
            _ (.mkdirs repo)
            _ (run-git! repo "init")
            _ (run-git! repo "config" "user.email" "skein-test@example.invalid")
            _ (run-git! repo "config" "user.name" "Skein Test")
            _ (write-git-lib! good-root 'demo.family_good)
            _ (write-git-lib! bad-root 'demo.family_bad)
            _ (spit (io/file bad-root "deps.edn") "{:paths [\"missing\"]}\n")
            _ (run-git! repo "add" ".")
            _ (run-git! repo "commit" "-m" "family roots")
            sha (str/trim (run-git! repo "rev-parse" "HEAD"))
            url (file-url repo)]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir
                           (pr-str {:spools {family {:git/url url
                                                     :git/sha sha
                                                     :roots {'demo/good "good"
                                                             'demo/bad "bad"}}}}))
            (let [results (:spools (sync-approved! rt))
                  expected-coordinate (assoc coordinate :git/url url :git/sha sha)]
              (is (= :loaded (get-in results ['demo/good :status])))
              (is (= :runtime-add-failed (get-in results ['demo/bad :reason])))
              (is (= #{:fetched} (set (map :fetch (vals results)))))
              (doseq [result (vals results)]
                (is (= family (:family result)))
                (is (= expected-coordinate (:coordinate result)))
                (is (= :spools-edn (:provenance result)))))))))))

(deftest sync-local-overlay-inherits-family-roots
  (with-runtime
    (fn [rt config-dir]
      (let [family 'demo/overlay-family
            local-root (io/file config-dir "overlay-family")
            _ (write-git-lib! (io/file local-root "one") 'demo.overlay_one)
            _ (write-git-lib! (io/file local-root "two") 'demo.overlay_two)
            sha "0123456789abcdef0123456789abcdef01234567"]
        (write-spools! config-dir
                       (pr-str {:spools {family {:git/url "https://example.invalid/family.git"
                                                 :git/sha sha
                                                 :git/tag "v2"
                                                 :roots {'demo/one "one" 'demo/two "two"}}}}))
        (write-local-spools! config-dir
                             (pr-str {:spools {family {:local/root "overlay-family"
                                                       :claims "v2"}}}))
        (let [results (:spools (sync-approved! rt))]
          (is (= #{'demo/one 'demo/two} (set (keys results))))
          (doseq [result (vals results)]
            (is (= :loaded (:status result)))
            (is (= family (:family result)))
            (is (= {:kind :local :local/root "overlay-family"} (:coordinate result)))
            (is (= "v2" (:claims result)))
            (is (= :local-overlay (:provenance result)))
            (is (= (local-source config-dir) (:source result)))))))))

(deftest sync-git-deps-root-missing-after-fetch-keeps-fetch-outcome
  (with-runtime
    (fn [rt config-dir]
      (let [repo (io/file config-dir "missing-root-repo")
            cache-dir (io/file config-dir "cache")
            ns-sym (symbol (str "demo.git_missing_root_" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))
            sha (init-git-repo! repo ns-sym)
            lib 'demo/git-missing-root]
        (with-cache-base
          cache-dir
          (fn []
            (write-spools! config-dir (pr-str {:spools {lib {:git/url (file-url repo)
                                                             :git/sha sha
                                                             :roots {lib "missing/spool"}}}}))
            (let [result (get-in (sync-approved! rt) [:spools lib])]
              (is (= :failed (:status result)))
              (is (= :missing-root (:reason result)))
              (is (= :fetched (:fetch result)))
              (is (= (.getPath (io/file cache-dir "skein" "spools" sha "missing/spool"))
                     (:root result))))))))))

(deftest spool-state-unversioned-reuses-existing-value
  (with-runtime
    (fn [rt _config-dir]
      (let [first-value (runtime/spool-state rt ::demo (constantly {:v 1}))
            ;; a second init-fn returning a different shape must NOT run: the
            ;; unversioned accessor reuses the first value for the runtime life.
            second-value (runtime/spool-state rt ::demo (fn [] (throw (ex-info "should not init" {}))))]
        (is (= {:v 1} first-value))
        (is (identical? first-value second-value))))))

(deftest spool-state-versioned-reuses-on-matching-version
  (with-runtime
    (fn [rt _config-dir]
      (let [opts {:version 1}
            a (runtime/spool-state rt ::demo opts (constantly {:v 1}))
            b (runtime/spool-state rt ::demo opts (fn [] (throw (ex-info "should not reinit" {}))))]
        (is (identical? a b))
        (is (= 1 (::runtime/version (meta a)))
            "version is stored as value metadata, leaving the plain value visible")))))

(deftest spool-state-reinits-on-version-mismatch
  (with-runtime
    (fn [rt _config-dir]
      (let [closed? (atom false)
            v1 (runtime/spool-state rt ::demo {:version 1}
                                    (constantly {:shape :old :close-fn #(reset! closed? true)}))
            ;; a bumped version with a new-shape init-fn deliberately reinits and
            ;; releases the old value's resources via its :close-fn.
            v2 (runtime/spool-state rt ::demo {:version 2}
                                    (constantly {:shape :new :extra true}))]
        (is (= :old (:shape v1)))
        (is (= :new (:shape v2)))
        (is (true? (:extra v2)))
        (is (true? @closed?) "mismatched reinit runs the old value's close-fn")
        (is (= 2 (::runtime/version (meta v2))))))))

(deftest spool-state-migrate-fn-owns-old-value
  (with-runtime
    (fn [rt _config-dir]
      (let [closed? (atom false)
            _v1 (runtime/spool-state rt ::demo {:version 1}
                                     (constantly {:keep :registry :close-fn #(reset! closed? true)}))
            migrated (runtime/spool-state
                      rt ::demo
                      {:version 2 :migrate-fn (fn [old] {:keep (:keep old) :fresh true})}
                      (fn [] (throw (ex-info "migrate-fn owns reinit, init-fn must not run" {}))))]
        (is (= {:keep :registry :fresh true} (into {} migrated)))
        (is (false? @closed?) "migrate-fn owns the old value; the runtime does not auto-close")
        (is (= 2 (::runtime/version (meta migrated))))))))

(deftest spool-state-versioned-requires-metadata-support
  (with-runtime
    (fn [rt _config-dir]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must support metadata"
                            (runtime/spool-state rt ::demo {:version 1}
                                                 (constantly (Object.))))))))

(deftest spool-state-rejects-malformed-opts
  (with-runtime
    (fn [rt _config-dir]
      (testing "a typo'd key fails loudly instead of degrading to unversioned reuse"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (runtime/spool-state rt ::demo {:versoin 2} (constantly {:v 1})))))
      (testing "opts must be a map or nil"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (runtime/spool-state rt ::demo 2 (constantly {:v 1})))))
      (testing ":version must be a non-nil comparable tag"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (runtime/spool-state rt ::demo {:version nil} (constantly {:v 1}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (runtime/spool-state rt ::demo {:version 1.5} (constantly {:v 1})))))
      (testing ":migrate-fn must be a function and requires a :version to compare"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid shape"
                              (runtime/spool-state rt ::demo {:version 1 :migrate-fn 5} (constantly {:v 1}))))
        (let [e (try
                  (runtime/spool-state rt ::demo {:migrate-fn identity} (constantly {:v 1}))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
          (is (re-find #"invalid shape" (ex-message e)))
          (is (contains? (ex-data e) :explain)))))))

(deftest spool-state-serializes-concurrent-version-mismatch-reinit
  ;; Several threads observing the same version mismatch must not each build a
  ;; replacement: a lock-free CAS loser would discard its freshly-built value
  ;; (and the executors it holds) and leak them for the JVM lifetime. The reinit
  ;; — init-fn plus the old value's close-fn — must run exactly once.
  (with-runtime
    (fn [rt _config-dir]
      (let [inits (atom 0)
            closes (atom 0)
            build-fn (fn [] (swap! inits inc) (Thread/sleep 20) {:shape :new})]
        (runtime/spool-state rt ::demo {:version 1}
                             (constantly {:shape :old :close-fn #(swap! closes inc)}))
        (let [n 8
              start (java.util.concurrent.CountDownLatch. 1)
              futures (doall (repeatedly n #(future (.await start)
                                                    (runtime/spool-state rt ::demo {:version 2} build-fn))))]
          (.countDown start)
          (run! deref futures)
          (is (= 1 @inits) "init-fn built the replacement exactly once")
          (is (= 1 @closes) "the old value's close-fn ran exactly once")
          (is (= :new (:shape (runtime/spool-state rt ::demo {:version 2} build-fn)))))))))

(deftest spool-state-reinit-survives-close-fn-failure
  ;; A failing best-effort cleanup during a version-mismatch reinit is warned,
  ;; not swallowed silently and not fatal — the reinit still completes.
  (with-runtime
    (fn [rt _config-dir]
      (runtime/spool-state rt ::demo {:version 1}
                           (constantly {:shape :old :close-fn #(throw (ex-info "boom" {}))}))
      (let [v2 (runtime/spool-state rt ::demo {:version 2} (constantly {:shape :new}))]
        (is (= :new (:shape v2)))))))

(defn- write-empty-lib!
  "Write a synced-able spool root with a deps.edn but no namespace sources."
  [config-dir lib-name]
  (let [root (io/file config-dir "spools" lib-name)]
    (.mkdirs (io/file root "src"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- reload-reason [rt root-lib]
  (try
    (spool-sync/reload-synced-spool! rt root-lib)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:reason (ex-data e)))))

(defn- namespace-residual [rt ns-sym]
  (some #(when (= ns-sym (:namespace %)) %)
        (:residuals (spool-sync/loaded-namespace-status rt))))

(deftest f3-cross-root-require-load-retains-unledgered-residual
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            required-ns (symbol (str "demo.required-" suffix))
            requiring-ns (symbol (str "demo.requiring-" suffix))
            required-lib (symbol (str "demo/required-" suffix))
            requiring-lib (symbol (str "demo/requiring-" suffix))
            required-root (write-local-lib! config-dir (str "required-" suffix) required-ns)
            requiring-root (write-local-lib! config-dir (str "requiring-" suffix) requiring-ns)
            required-file (write-spool-ns!
                           required-root required-ns
                           (str "(ns " required-ns ")\n(def value :required)\n"))
            required-entry {:local/root (str "spools/required-" suffix)}
            requiring-entry {:local/root (str "spools/requiring-" suffix)}]
        (write-spool-ns!
         requiring-root requiring-ns
         (str "(ns " requiring-ns " (:require [" required-ns "]))\n"
              "(def value " required-ns "/value)\n"))
        (write-spools! config-dir
                       (pr-str {:spools {required-lib required-entry
                                         requiring-lib requiring-entry}}))
        (sync-approved! rt)
        (load-synced! rt :f3/requiring {:ns requiring-ns :spools [requiring-lib]})
        (is (find-ns required-ns))
        (is (not-any? #(= required-ns (:namespace %))
                      (spool-sync/namespace-load-ledger rt)))
        (is (= :unledgered-loaded-namespace
               (:reason (namespace-residual rt required-ns))))
        (testing "edited source cannot false-clean the unledgered root"
          (spit required-file
                (str "(ns " required-ns ")\n(def value :edited)\n"))
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
            (is (= :non-additive-sync-diff (:reason (ex-data ex))))
            (is (false? (:clean? (spool-sync/loaded-namespace-status rt))))
            (is (= :pending (get-in (sync-state rt)
                                    [:pending-generation :status])))))
        (testing "deleted source cannot clear the pending generation"
          (is (.delete required-file))
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
            (is (= :non-additive-sync-diff (:reason (ex-data ex))))
            (is (= :unledgered-loaded-namespace
                   (:reason (namespace-residual rt required-ns))))
            (is (= :pending (get-in (sync-state rt)
                                    [:pending-generation :status])))))))))

(deftest loaded-namespace-ledger-path-shrink-remains-residual
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.path-shrink-" suffix))
            lib (symbol (str "demo/path-shrink-" suffix))
            root (io/file config-dir "spools" (str "path-shrink-" suffix))
            src-file (io/file root "legacy" "demo" (str "path_shrink_" suffix ".clj"))]
        (.mkdirs (.getParentFile src-file))
        (.mkdirs (io/file root "src"))
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v1)\n"))
        (spit (io/file root "deps.edn") (pr-str {:paths ["src" "legacy"]}))
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root (str "spools/path-shrink-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt :ledger/path-shrink
                                              {:ns ns-sym :spools [lib]}))))
        (let [record (last (spool-sync/namespace-load-ledger rt))]
          (is (= {:root-lib lib
                  :owner :ledger/path-shrink
                  :namespace ns-sym
                  :file (.getCanonicalPath src-file)}
                 (select-keys record [:root-lib :owner :namespace :file])))
          (is (re-matches #"[0-9a-f]{64}" (:sha256 record)))
          (is (s/valid? :skein.core.weaver.spool-sync/namespace-load-ledger
                        @(access/namespace-load-ledger rt))))
        (spit (io/file root "deps.edn") (pr-str {:paths ["src"]}))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              residual (namespace-residual rt ns-sym)]
          (is (= :non-additive-sync-diff (:reason (ex-data ex))))
          (is (= :path-shrunk (:reason residual)))
          (is (= (.getCanonicalPath src-file) (get-in residual [:binding :file])))
          (is (false? (:clean? (spool-sync/loaded-namespace-status rt))))
          (is (= :pending (get-in (sync-state rt) [:pending-generation :status]))))))))

(deftest loaded-namespace-ledger-deleted-file-remains-residual
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.deleted-ledger-" suffix))
            lib (symbol (str "demo/deleted-ledger-" suffix))
            root (write-local-lib! config-dir (str "deleted-ledger-" suffix) ns-sym)
            src-file (io/file root "src" "demo" (str "deleted_ledger_" suffix ".clj"))]
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root (str "spools/deleted-ledger-" suffix)}}}))
        (sync-approved! rt)
        (load-synced! rt :ledger/deleted {:ns ns-sym :spools [lib]})
        (is (.delete src-file))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              residual (namespace-residual rt ns-sym)]
          (is (= :non-additive-sync-diff (:reason (ex-data ex))))
          (is (= :deleted-file (:reason residual)))
          (is (= :pending (get-in (sync-state rt) [:pending-generation :status])))
          (is (= [ns-sym]
                 (mapv :namespace (spool-sync/namespace-load-ledger rt)))))))))

(deftest loaded-namespace-ledger-rename-stays-attributed-to-old-binding
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.renamed-ledger-" suffix))
            lib (symbol (str "demo/renamed-ledger-" suffix))
            root (write-local-lib! config-dir (str "renamed-ledger-" suffix) ns-sym)
            old-file (io/file root "src" "demo" (str "renamed_ledger_" suffix ".clj"))
            new-file (io/file root "src" "demo" (str "moved_ledger_" suffix ".clj"))]
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root (str "spools/renamed-ledger-" suffix)}}}))
        (sync-approved! rt)
        (load-synced! rt :ledger/rename {:ns ns-sym :spools [lib]})
        (spit new-file (slurp old-file))
        (is (.delete old-file))
        (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
        (let [residual (namespace-residual rt ns-sym)]
          (is (= :namespace-renamed (:reason residual)))
          (is (= (.getCanonicalPath old-file) (get-in residual [:binding :file])))
          (is (= [(.getCanonicalPath new-file)] (mapv :file (:providers residual)))))))))

(deftest loaded-namespace-ledger-transfer-requires-successful-new-root-load
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.transfer-ledger-" suffix))
            lib-a (symbol (str "demo/transfer-a-" suffix))
            lib-b (symbol (str "demo/transfer-b-" suffix))
            root-a (write-local-lib! config-dir (str "transfer-a-" suffix) ns-sym)
            root-b (write-empty-lib! config-dir (str "transfer-b-" suffix))
            file-a (io/file root-a "src" "demo" (str "transfer_ledger_" suffix ".clj"))
            entry-a {:local/root (str "spools/transfer-a-" suffix)}
            entry-b {:local/root (str "spools/transfer-b-" suffix)}]
        (write-spools! config-dir (pr-str {:spools {lib-a entry-a lib-b entry-b}}))
        (sync-approved! rt)
        (load-synced! rt :ledger/transfer {:ns ns-sym :spools [lib-a]})
        (is (.delete file-a))
        (write-spool-ns! root-b ns-sym (str "(ns " ns-sym ")\n(defn marker [] :from-b)\n"))
        (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
        (is (= :transfer-pending (:reason (namespace-residual rt ns-sym))))
        (is (= lib-a (get-in (namespace-residual rt ns-sym) [:binding :root-lib])))
        (spool-sync/reload-synced-spool! rt lib-b)
        (let [status (spool-sync/loaded-namespace-status rt)
              current (get-in status [:current-bindings ns-sym])]
          (is (= lib-b (:root-lib current)))
          (is (= :ledger/transfer (:owner current)))
          (is (= [lib-a] (mapv :root-lib (get-in status [:prior-bindings ns-sym]))))
          (is (:clean? status)))
        (let [result (sync-approved! rt)]
          (is (nil? (:pending-generation result)))
          (is (nil? (:pending-generation (sync-state rt)))))))))

(deftest loaded-namespace-ledger-cross-root-duplicate-is-hard-conflict
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.duplicate-provider-" suffix))
            lib-a (symbol (str "demo/duplicate-provider-a-" suffix))
            lib-b (symbol (str "demo/duplicate-provider-b-" suffix))
            root-b (write-empty-lib! config-dir (str "duplicate-provider-b-" suffix))]
        (write-local-lib! config-dir (str "duplicate-provider-a-" suffix) ns-sym)
        (write-spools! config-dir
                       (pr-str {:spools {lib-a {:local/root (str "spools/duplicate-provider-a-" suffix)}
                                         lib-b {:local/root (str "spools/duplicate-provider-b-" suffix)}}}))
        (sync-approved! rt)
        (load-synced! rt :ledger/duplicate {:ns ns-sym :spools [lib-a]})
        (write-spool-ns! root-b ns-sym (str "(ns " ns-sym ")\n(defn marker [] :other)\n"))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
              conflict (first (:hard-conflicts (spool-sync/loaded-namespace-status rt)))]
          (is (= :non-additive-sync-diff (:reason (ex-data ex))))
          (is (= :duplicate-provider (:reason conflict)))
          (is (= #{lib-a lib-b} (set (map :root-lib (:providers conflict)))))
          (is (= :pending (get-in (sync-state rt) [:pending-generation :status]))))))))

(deftest loaded-namespace-status-separates-test-classpath-ownership
  (with-runtime
    (fn [rt _config-dir]
      ;; Direct requires remain legitimate in tests. Batteries and workflow are
      ;; visible here because the :test alias adds their source paths; their
      ;; base-classpath classification is a test-tooling artifact, not shipped
      ;; production ownership.
      (require 'skein.spools.batteries)
      (require 'skein.spools.workflow)
      (let [bindings (into {} (map (juxt :namespace identity))
                           (:classpath-bindings (spool-sync/loaded-namespace-status rt)))]
        (doseq [ns-sym ['skein.api.runtime.alpha
                        'skein.spools.batteries
                        'skein.spools.workflow]]
          (is (= :classpath (get-in bindings [ns-sym :ownership])))
          (is (= :base-classpath (get-in bindings [ns-sym :classpath-owner])))
          (is (string? (get-in bindings [ns-sym :source]))))))))

(deftest f12-base-classpath-overlap-is-not-observed-as-unledgered
  (with-runtime
    (fn [rt config-dir]
      (require 'skein.spools.workflow)
      (let [lib 'demo/workflow-overlap
            root (write-local-lib! config-dir "workflow-overlap"
                                   'skein.spools.workflow)]
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root "spools/workflow-overlap"}}}))
        (is (#{:loaded :already-available}
             (get-in (sync-approved! rt) [:spools lib :status])))
        (is (#{:loaded :already-available}
             (get-in (sync-approved! rt) [:spools lib :status])))
        (let [status (spool-sync/loaded-namespace-status rt)]
          (is (:clean? status))
          (is (nil? (:pending-generation (sync-state rt))))
          (is (empty? (filter #(= 'skein.spools.workflow (:namespace %))
                              (:residuals status))))
          (is (= (.getCanonicalPath root)
                 (get-in status [:provisions 'skein.spools.workflow 0 :root]))))))))

(deftest f13-source-load-boundaries-batch-namespace-observation
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-a (symbol (str "demo.batch-a-" suffix))
            ns-b (symbol (str "demo.batch-b-" suffix))
            lib (symbol (str "demo/batch-" suffix))
            root (write-local-lib! config-dir (str "batch-" suffix) ns-a)
            observe @#'spool-sync/current-namespace-observations
            calls (atom 0)]
        (write-spool-ns!
         root ns-b
         (str "(ns " ns-b " (:require [" ns-a "]))\n"
              "(def value " ns-a "/marker)\n"))
        (write-spools! config-dir
                       (pr-str {:spools {lib {:local/root (str "spools/batch-" suffix)}}}))
        (sync-approved! rt)
        (with-redefs-fn
          {#'spool-sync/current-namespace-observations
           (fn [& args]
             (swap! calls inc)
             (apply observe args))}
          #(do
             (load-synced! rt :f13/target {:ns ns-b :spools [lib]})
             (is (= 1 @calls)
                 "the two-namespace target closure classifies once")
             (reset! calls 0)
             (spool-sync/reload-synced-spool! rt lib)
             (is (= 1 @calls)
                 "the two-namespace root reload classifies once")))))))

(deftest reload-synced-spool-makes-single-namespace-source-live
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.reload-ns-" suffix))
            lib (symbol (str "demo/reload-lib-" suffix))
            root (write-local-lib! config-dir "reload-ns" ns-sym)
            src-file (io/file root "src" "demo" (str "reload_ns_" suffix ".clj"))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/reload-ns"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= lib (:root-lib result)))
          (is (= (.getCanonicalPath root) (:root result)))
          (is (= [{:ns ns-sym :file (.getCanonicalPath src-file)}]
                 (:namespaces result)))
          (is (= :synced-lib-loaded ((requiring-resolve (symbol (str ns-sym "/marker")))))))))))

(deftest reload-synced-spool-fails-when-root-lib-not-approved
  (with-runtime
    (fn [rt _config-dir]
      (is (= :not-approved (reload-reason rt 'demo/never-approved))))))

(deftest reload-synced-spool-fails-when-root-lib-not-synced
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.unsynced-" suffix))
            lib (symbol (str "demo/unsynced-lib-" suffix))]
        (write-local-lib! config-dir "unsynced" ns-sym)
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/unsynced"}}}))
        (is (= :not-synced (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-sync-failed
  (with-runtime
    (fn [rt config-dir]
      (let [lib (symbol (str "demo/sync-failed-lib-" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/missing"}}}))
        (is (= :missing-root (get-in (sync-approved! rt) [:spools lib :reason])))
        (is (= :sync-failed (reload-reason rt lib)))))))

;; A synced root removed or replaced *after* a clean sync is a real post-sync
;; scenario. The on-disk re-check reads only the sync-state :root, so seeding a
;; clean :loaded entry whose root is absent / replaced exercises the same gate
;; without needing a real sync of a doomed root.
(deftest reload-synced-spool-fails-when-root-missing-after-sync
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "demo/missing-root-lib-" suffix))
            rel (str "spools/missing-root-" suffix)
            missing-root (io/file config-dir rel)]
        (write-spools! config-dir (pr-str {:spools {lib {:local/root rel}}}))
        (swap! (access/approved-spool-sync-state rt) assoc lib
               {:lib lib :status :loaded :root (.getCanonicalPath missing-root)})
        (is (= :missing-root (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-root-replaced-by-file-after-sync
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            lib (symbol (str "demo/unreadable-root-lib-" suffix))
            rel (str "spools/unreadable-root-" suffix)
            root-file (io/file config-dir rel)]
        (.mkdirs (.getParentFile root-file))
        (spit root-file "not a directory\n")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root rel}}}))
        (swap! (access/approved-spool-sync-state rt) assoc lib
               {:lib lib :status :loaded :root (.getCanonicalPath root-file)})
        (is (= :unreadable-root (reload-reason rt lib)))))))

(deftest reload-synced-spool-fails-when-root-has-no-namespaces
  (with-runtime
    (fn [rt config-dir]
      (let [lib (symbol (str "demo/no-ns-lib-" (str/replace (str (java.util.UUID/randomUUID)) "-" "")))]
        (write-empty-lib! config-dir "no-ns")
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/no-ns"}}}))
        (is (contains? #{:loaded :already-available}
                       (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :no-namespaces (reload-reason rt lib)))))))

;; Two distinct source files under the root declaring the same namespace would let
;; `into {}` keep whichever parsed last and silently drop the other from the reload
;; set. Sync only adds the root to the classpath (no compile), so the collision must
;; fail loudly at reload-ordering time, naming the namespace and both file paths.
(deftest reload-synced-spool-fails-when-two-sources-declare-same-namespace
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            shared-ns (symbol (str "demo.dup-shared-" suffix))
            lib (symbol (str "demo/dup-ns-lib-" suffix))
            root (io/file config-dir "spools" "dup-ns")
            file-a (io/file root "src" "demo" (str "dup_a_" suffix ".clj"))
            file-b (io/file root "src" "demo" (str "dup_b_" suffix ".clj"))]
        (.mkdirs (.getParentFile file-a))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit file-a (str "(ns " shared-ns ")\n(defn a [] :a)\n"))
        (spit file-b (str "(ns " shared-ns ")\n(defn b [] :b)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/dup-ns"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/reload-synced-spool! rt lib)))
              data (ex-data ex)]
          (is (= :duplicate-namespace (:reason data)))
          (is (= shared-ns (:namespace data)))
          (is (= lib (:root-lib data)))
          (is (= #{(.getCanonicalPath file-a) (.getCanonicalPath file-b)}
                 (set (:files data)))))))))

;; A genuine circular intra-root require makes tools.namespace throw a raw
;; `::circular-dependency` ex-info with no :status/:root-lib. The seam must catch it
;; and rethrow under the documented `{:status :failed :reason :root-lib}` contract.
;; Sync only adds the root to the classpath (no compile), so the cycle surfaces at
;; reload-ordering rather than sync.
(deftest reload-synced-spool-fails-on-circular-intra-root-requires
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-a (symbol (str "demo.cycle-a-" suffix))
            ns-b (symbol (str "demo.cycle-b-" suffix))
            lib (symbol (str "demo/cycle-lib-" suffix))
            root (io/file config-dir "spools" "cycle")]
        (.mkdirs (io/file root "src"))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (write-spool-ns! root ns-a (str "(ns " ns-a " (:require [" ns-b " :as b]))\n"))
        (write-spool-ns! root ns-b (str "(ns " ns-b " (:require [" ns-a " :as a]))\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/cycle"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (spool-sync/reload-synced-spool! rt lib)))
              data (ex-data ex)]
          (is (= :circular-requires (:reason data)))
          (is (= lib (:root-lib data)))
          (is (= #{ns-a ns-b} (set (vals (:cycle data))))))))))

;; PLAN-shr-001.V3: two intra-root namespaces where `a` uses a macro from `b`.
;; A bumped `b` macro only reaches `a`'s expansion when `b` reloads before `a`;
;; the reverse order re-expands `a` against the stale macro. Asserting `a`'s new
;; value proves the topo-sort reloads dependencies first, not in arbitrary order.
(deftest reload-synced-spool-reloads-intra-root-dependencies-first
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-b (symbol (str "demo.dep-macro-" suffix))
            ns-a (symbol (str "demo.dep-consumer-" suffix))
            lib (symbol (str "demo/dep-order-lib-" suffix))
            root (io/file config-dir "spools" "dep-order")]
        (.mkdirs (io/file root "src"))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (write-spool-ns! root ns-b (str "(ns " ns-b ")\n(defmacro tag [] :v1)\n"))
        (write-spool-ns! root ns-a
                         (str "(ns " ns-a " (:require [" ns-b " :as b]))\n(defn value [] (b/tag))\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/dep-order"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt (keyword (str "dep-order-" suffix))
                                              {:ns ns-a :spools [lib]}))))
        (is (= :v1 ((requiring-resolve (symbol (str ns-a "/value"))))))
        (write-spool-ns! root ns-b (str "(ns " ns-b ")\n(defmacro tag [] :v2)\n"))
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= :v2 ((requiring-resolve (symbol (str ns-a "/value")))))
              "a re-expands against the bumped macro only if b reloaded first")
          (is (= [ns-b ns-a] (mapv :ns (:namespaces result)))))))))

;; PLAN-shr-001.V2 keystone: a loaded spool fn returns :v1; after the source is
;; bumped to :v2, a bare `(require ns :reload)` remains blind to the spool
;; classloader's roots, while the code-reload seam makes the change live.
(deftest reload-synced-spool-picks-up-bumped-source-that-reload-and-require-miss
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.keystone-" suffix))
            lib (symbol (str "demo/keystone-lib-" suffix))
            root (io/file config-dir "spools" "keystone")
            src-file (io/file root "src" "demo" (str "keystone_" suffix ".clj"))
            version #(requiring-resolve (symbol (str ns-sym "/version")))]
        (.mkdirs (.getParentFile src-file))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v1)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/keystone"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt (keyword (str "keystone-" suffix))
                                              {:ns ns-sym :spools [lib]}))))
        (is (= :v1 ((version))))
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v2)\n"))
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
          (is (= :non-additive-sync-diff (:reason (ex-data ex)))))
        (is (= :v1 ((version))) "re-sync refuses the non-additive source bump")
        ;; A bare require :reload runs on the base loader, which has no spool root.
        (try (require ns-sym :reload) (catch java.io.FileNotFoundException _ nil))
        (is (= :v1 ((version))) "require :reload is classloader-blind to the spool root")
        (let [result (spool-sync/reload-synced-spool! rt lib)]
          (is (= :v2 ((version))) "the blessed seam load-files the bumped source live")
          (is (= [ns-sym] (mapv :ns (:namespaces result)))))
        ;; The completed hot bump converges: the recorded fingerprint now
        ;; matches disk, and a clean acquisition pass retires the pending record.
        (is (= :pending (get-in (sync-state rt) [:pending-generation :status]))
            "only a clean sync proves the pending record stale")
        (let [result (sync-approved! rt)]
          (is (= :already-available (get-in result [:spools lib :status])))
          (is (nil? (:pending-generation result))))
        (is (nil? (:pending-generation (sync-state rt))))
        (is (= :v2 ((version))) "convergence never rolls the loaded source back")))))

(deftest reload-synced-spool-partial-failure-keeps-the-refusal
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-a (symbol (str "demo.partial-a-" suffix))
            ns-b (symbol (str "demo.partial-b-" suffix))
            lib (symbol (str "demo/partial-lib-" suffix))
            root (io/file config-dir "spools" (str "partial-" suffix))
            file-a (io/file root "src" "demo" (str "partial_a_" suffix ".clj"))
            file-b (io/file root "src" "demo" (str "partial_b_" suffix ".clj"))]
        (.mkdirs (.getParentFile file-a))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit file-a (str "(ns " ns-a ")\n(defn version [] :v1)\n"))
        (spit file-b (str "(ns " ns-b " (:require [" ns-a "]))\n(defn version [] :v1)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root (str "spools/partial-" suffix)}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt (keyword (str "partial-" suffix))
                                              {:ns ns-b :spools [lib]}))))
        ;; Bump both sources, breaking the dependency-ordered second file: the
        ;; reload rebinds ns-a then throws on ns-b, leaving a half-reloaded root.
        (spit file-a (str "(ns " ns-a ")\n(defn version [] :v2)\n"))
        (spit file-b (str "(ns " ns-b " (:require [" ns-a "]))\n(defn version [] :v2\n"))
        (is (thrown? Throwable (spool-sync/reload-synced-spool! rt lib)))
        (let [ledger (spool-sync/namespace-load-ledger rt)
              latest-a (last (filter #(= ns-a (:namespace %)) ledger))
              latest-b (last (filter #(= ns-b (:namespace %)) ledger))]
          (is (< (:order latest-b) (:order latest-a))
              "the successful first load remains after the later source fails")
          (is (= :v2 ((requiring-resolve (symbol (str ns-a "/version"))))))
          (is (= :changed-bytes (:reason (namespace-residual rt ns-b)))))
        (testing "no fingerprint recorded: the half-reloaded root keeps refusing"
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))]
            (is (= :non-additive-sync-diff (:reason (ex-data ex))))))))))

(deftest sync-with-per-root-failures-never-clears-a-pending-generation
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-x (symbol (str "demo.pending_x_" suffix))
            ns-y (symbol (str "demo.pending_y_" suffix))
            lib-x (symbol (str "demo/pending-x-" suffix))
            lib-y (symbol (str "demo/pending-y-" suffix))
            root-x (write-local-lib! config-dir (str "pending-x-" suffix) ns-x)
            entry-x {:local/root (str "spools/pending-x-" suffix)}
            entry-y {:local/root (str "spools/pending-y-" suffix)}]
        (write-local-lib! config-dir (str "pending-y-" suffix) ns-y)
        (write-spools! config-dir (pr-str {:spools {lib-x entry-x lib-y entry-y}}))
        (let [result (sync-approved! rt)]
          (is (= :loaded (get-in result [:spools lib-x :status])))
          (is (= :loaded (get-in result [:spools lib-y :status]))))
        (is (= :loaded (:status (load-synced! rt :ledger/failed-x
                                              {:ns ns-x :spools [lib-x]}))))
        (is (= :loaded (:status (load-synced! rt :ledger/failed-y
                                              {:ns ns-y :spools [lib-y]}))))
        ;; Record a pending generation by removing the loaded lib-y.
        (write-spools! config-dir (pr-str {:spools {lib-x entry-x}}))
        (is (thrown? clojure.lang.ExceptionInfo (sync-approved! rt)))
        (is (= :pending (get-in (sync-state rt) [:pending-generation :status])))
        ;; Restore lib-y but break lib-x: the sync succeeds around the per-root
        ;; failure, which proves nothing about the failed root, so the pending
        ;; record must survive (C44d).
        (write-spools! config-dir (pr-str {:spools {lib-x entry-x lib-y entry-y}}))
        (spit (io/file root-x "deps.edn") "{:paths [\"src\"")
        (let [result (sync-approved! rt)]
          (is (= :failed (get-in result [:spools lib-x :status])))
          (is (= :pending (get-in result [:pending-generation :status])))
          (is (= :unclassified-root (:reason (namespace-residual rt ns-x))))
          (is (false? (:complete? (spool-sync/loaded-namespace-status rt)))))
        (is (= :pending (get-in (sync-state rt) [:pending-generation :status])))
        ;; Fix lib-x: a zero-failure pass classifies every loaded root and
        ;; retires the stale record.
        (spit (io/file root-x "deps.edn") "{:paths [\"src\"]}\n")
        (let [result (sync-approved! rt)]
          (is (contains? #{:loaded :already-available} (get-in result [:spools lib-x :status])))
          (is (nil? (:pending-generation result))))
        (is (nil? (:pending-generation (sync-state rt))))))))

;; TASK-shr-003.MI1: the blessed verb fails loudly before delegating when `coord`
;; is not a symbol, rather than passing a bad key into the sync-state lookup. It
;; routes through the canonical `require-valid!` seam, so the failure carries the
;; standard `{:value :explain}` boundary-validation shape its siblings produce.
(deftest reload-spool-verb-rejects-non-symbol-root-lib
  (with-runtime
    (fn [rt _config-dir]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (runtime/reload-code! rt :demo/keyword-coord)))
            data (ex-data ex)]
        (is (= :demo/keyword-coord (:value data)))
        (is (contains? data :explain))))))

;; TASK-shr-003.MI5: the keystone gap proof exercised *through* the blessed
;; `runtime/reload-code!` (runtime passed explicitly, no ambient singleton): a
;; synced+loaded spool fn returns :v1; after the source is bumped, the blessed
;; verb makes :v2 live and returns the data-first coordinate/root/namespaces map.
(deftest reload-spool-verb-makes-bumped-source-live
  (with-runtime
    (fn [rt config-dir]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.verb-keystone-" suffix))
            lib (symbol (str "demo/verb-keystone-lib-" suffix))
            root (io/file config-dir "spools" "verb-keystone")
            src-file (io/file root "src" "demo" (str "verb_keystone_" suffix ".clj"))
            version #(requiring-resolve (symbol (str ns-sym "/version")))]
        (.mkdirs (.getParentFile src-file))
        (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v1)\n"))
        (write-spools! config-dir (pr-str {:spools {lib {:local/root "spools/verb-keystone"}}}))
        (is (= :loaded (get-in (sync-approved! rt) [:spools lib :status])))
        (is (= :loaded (:status (load-synced! rt (keyword (str "verb-keystone-" suffix))
                                              {:ns ns-sym :spools [lib]}))))
        (is (= :v1 ((version))))
        (spit src-file (str "(ns " ns-sym ")\n(defn version [] :v2)\n"))
        (let [result (runtime/reload-code! rt lib)]
          (is (= :v2 ((version))) "the blessed verb makes the bumped source live")
          (is (s/valid? ::runtime/reload-code-result result))
          (is (= lib (:root-lib result)))
          (is (= (.getCanonicalPath root) (:root result)))
          (is (= [{:ns ns-sym :file (.getCanonicalPath src-file)}]
                 (:namespaces result))))))))
