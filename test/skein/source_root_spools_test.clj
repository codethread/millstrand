(ns skein.source-root-spools-test
  "Focused tests for the `{:skein/source-root \"spools/<name>\"}` spool coordinate
  kind (SPEC-004-D006): structural validation, checkout-confined resolution,
  loud failure, git-materialization abstention, kind-neutral same-root identity,
  and synced-provider-wins classification over the intentional test-classpath
  overlap. PH1 owns only this file and the core namespaces; it deliberately does
  not assert fresh-generation classpath-ownership absence — the ambient test JVM
  cannot prove it, so the PH5 end-to-end smoke owns that."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.module-refresh :as module-refresh]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.spools.test-support :as test-support :refer [with-runtime]]))

(defn- ns-relative [ns-sym]
  (str (-> (str ns-sym) (str/replace \- \_) (str/replace \. \/)) ".clj"))

(defn- write-checkout-spool!
  "Create a minimal spool at `<checkout>/<rel-path>` providing `ns-sym`."
  [checkout rel-path ns-sym]
  (let [root (io/file checkout rel-path)
        ns-file (io/file root "src" (ns-relative ns-sym))]
    (.mkdirs (.getParentFile ns-file))
    (spit ns-file (str "(ns " ns-sym ")\n(defn marker [] :source-root-loaded)\n"))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    root))

(defn- write-spools! [config-dir m]
  (spit (io/file config-dir "spools.edn") (pr-str m)))

(defn- running-marker [rt]
  (let [{:keys [marker provenance]} (access/release-marker rt)]
    (if (= :none provenance) :none marker)))

(defn- sync! [rt]
  (spool-sync/sync-approved-spools rt (running-marker rt)))

(defn- with-checkout
  "Point the source-checkout authority at `checkout` for the duration of `f`."
  [checkout f]
  (let [orig @#'access/source-checkout-root
        canonical (.getCanonicalFile (io/file checkout))]
    (try
      (alter-var-root #'access/source-checkout-root (constantly (fn [] canonical)))
      (f)
      (finally
        (alter-var-root #'access/source-checkout-root (constantly orig))))))

(defn- with-unavailable-checkout
  "Force the resource-derived locator to report no checkout for the duration of `f`."
  [f]
  (let [orig @#'weaver-runtime/source-checkout-root]
    (try
      (alter-var-root #'weaver-runtime/source-checkout-root (constantly (fn ([] nil) ([_] nil))))
      (f)
      (finally
        (alter-var-root #'weaver-runtime/source-checkout-root (constantly orig))))))

;; --- Structural validation (SPEC-004-D006.C1) ---

(deftest source-root-rejects-non-relative-and-mixed-kind-shapes
  (testing "absolute path fails structural validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relative :skein/source-root"
                          (spool-sync/validate-shared-spools-config!
                           "/tmp/spools.edn" {:spools {'demo {:skein/source-root "/abs/spools/demo"}}}))))
  (testing "leading ~ fails structural validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relative :skein/source-root"
                          (spool-sync/validate-shared-spools-config!
                           "/tmp/spools.edn" {:spools {'demo {:skein/source-root "~/spools/demo"}}}))))
  (testing ".. segment fails structural validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relative :skein/source-root"
                          (spool-sync/validate-shared-spools-config!
                           "/tmp/spools.edn" {:spools {'demo {:skein/source-root "spools/../etc"}}}))))
  (testing "mixing source-root with a local coordinate fails before resolution"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one coordinate kind"
                          (spool-sync/validate-shared-spools-config!
                           "/tmp/spools.edn"
                           {:spools {'demo {:skein/source-root "spools/demo" :local/root "spools/demo"}}}))))
  (testing "mixing source-root with a git coordinate fails before resolution"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one coordinate kind"
                          (spool-sync/validate-shared-spools-config!
                           "/tmp/spools.edn"
                           {:spools {'demo {:skein/source-root "spools/demo"
                                            :git/url "https://example.invalid/demo.git"
                                            :git/sha "0123456789abcdef0123456789abcdef01234567"}}})))))

(deftest source-root-normalizes-to-a-single-kind-relevant-coordinate
  (let [normalized (spool-sync/validate-shared-spools-config!
                    "/tmp/spools.edn" {:spools {'demo {:skein/source-root "spools/demo"}}})
        family (first (:families normalized))]
    (is (= {:kind :skein/source-root :skein/source-root "spools/demo"}
           (:coordinate family)))
    (is (= {'demo "."} (:roots-map family)))))

;; --- Resolution + canonical containment (SPEC-004-D006.C3/C4) ---

(deftest source-root-resolves-beneath-checkout-spools-and-syncs-a-loaded-root
  (with-runtime
    (fn [rt config-dir]
      (let [checkout (test-support/temp-dir "skein-source-root-checkout")]
        (write-checkout-spool! checkout "spools/demo" 'demo.core)
        (write-spools! config-dir {:spools {'demo {:skein/source-root "spools/demo"}}})
        (with-checkout checkout
          (fn []
            (sync! rt)
            (let [entry (get-in (spool-sync/approved-spool-syncs rt) [:spools 'demo])
                  spools-dir (.getCanonicalPath (io/file checkout "spools"))
                  root (.getCanonicalPath (io/file checkout "spools" "demo"))]
              (is (= :skein/source-root (:kind entry)))
              (is (= "spools/demo" (:skein/source-root entry)))
              (is (= root (:root entry)))
              (is (str/starts-with? root (str spools-dir java.io.File/separator)))
              (is (contains? #{:loaded :already-available} (:status entry)))
              (testing "carries only the kind-relevant source field, no cross-kind keys"
                (is (not (contains? entry :local/root)))
                (is (not (contains? entry :git/url)))
                (is (not (contains? entry :git/sha))))
              (testing "the synced provider resolves the source file under the checkout"
                (is (= (.getCanonicalPath (io/file checkout "spools" "demo" "src" "demo" "core.clj"))
                       (spool-sync/synced-namespace-file rt 'demo.core)))))))))))

(deftest source-root-rejects-a-symlink-escaping-the-checkout-spools-dir
  (with-runtime
    (fn [rt config-dir]
      (let [checkout (test-support/temp-dir "skein-source-root-checkout")
            outside (test-support/temp-dir "skein-source-root-outside")]
        (write-checkout-spool! outside "escaped" 'escaped.core)
        (.mkdirs (io/file checkout "spools"))
        (java.nio.file.Files/createSymbolicLink
         (.toPath (io/file checkout "spools" "evil"))
         (.toPath (.getCanonicalFile (io/file outside "escaped")))
         (make-array java.nio.file.attribute.FileAttribute 0))
        (write-spools! config-dir {:spools {'evil {:skein/source-root "spools/evil"}}})
        (with-checkout checkout
          (fn []
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"beneath <checkout>/spools"
                                  (sync! rt)))))))))

;; --- Loud failure when the checkout is unavailable (SPEC-004-D006.C3) ---

(deftest source-root-fails-loudly-when-the-checkout-is-unavailable
  (testing "a non-file classpath resource identifies no readable file checkout"
    (is (nil? (weaver-runtime/source-checkout-root
               (java.net.URL. "http://example.invalid/skein/core/weaver/runtime.clj"))))
    (is (nil? (weaver-runtime/source-checkout-root nil))))
  (with-unavailable-checkout
    (fn []
      (testing "the resolution authority throws rather than resolving against a missing checkout"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"source checkout is unavailable"
                              (access/source-checkout-root))))
      (with-runtime
        (fn [rt config-dir]
          (write-spools! config-dir {:spools {'demo {:skein/source-root "spools/demo"}}})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"source checkout is unavailable"
                                (sync! rt))))))))

;; --- deps.edn :deps key rejection (SPEC-004-D006.C8/C9) ---

(deftest source-root-key-is-rejected-inside-a-spool-root-deps
  (testing "a :skein/source-root source coordinate is rejected loudly in deps.edn :deps"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"source-bearing coordinates"
                          (#'spool-sync/validate-maven-coordinate!
                           'some/lib {:skein/source-root "spools/x"} {:root "/tmp/root"}))))
  (testing "a source-root spool root whose deps.edn declares the key syncs to a failed root"
    (with-runtime
      (fn [rt config-dir]
        (let [checkout (test-support/temp-dir "skein-source-root-checkout")
              root (write-checkout-spool! checkout "spools/demo" 'demo.core)]
          (spit (io/file root "deps.edn")
                (pr-str {:paths ["src"] :deps {'evil/lib {:skein/source-root "spools/other"}}}))
          (write-spools! config-dir {:spools {'demo {:skein/source-root "spools/demo"}}})
          (with-checkout checkout
            (fn []
              (sync! rt)
              (let [entry (get-in (spool-sync/approved-spool-syncs rt) [:spools 'demo])]
                (is (= :failed (:status entry)))
                (is (str/includes? (str (:message entry)) "source-bearing coordinates"))))))))))

;; --- Source-root resolution never invokes git materialization (SPEC-004-D006.C2/C6) ---

(deftest source-root-resolution-never-materializes-git
  (with-runtime
    (fn [rt config-dir]
      (let [checkout (test-support/temp-dir "skein-source-root-checkout")]
        (write-checkout-spool! checkout "spools/demo" 'demo.core)
        (write-spools! config-dir {:spools {'demo {:skein/source-root "spools/demo"}}})
        (with-checkout checkout
          (fn []
            (let [approved (spool-sync/approved-spools rt (running-marker rt))]
              (testing "materialize-families stays git-only: the source-root family materializes to nil"
                (is (= {'demo nil} (#'spool-sync/materialize-families (:spools approved))))))))))))

;; --- Kind-neutral same-root identity in the non-additive diff (SPEC-004-D006.C5) ---

(deftest non-additive-diff-keys-identity-on-the-canonical-root-path
  (let [root "/tmp/checkout/spools/demo"
        clean-status {:ledger [] :residuals [] :hard-conflicts []}
        previous {'demo {:lib 'demo :status :loaded :kind :local
                         :root root :source {:kind :shared :file "/tmp/spools.edn"}}}
        approved {:spools {'demo {}}}]
    (testing "rewriting the coordinate kind/text is additive when the canonical root is unchanged"
      (let [survivors [{:lib 'demo :entry {:root root} :source-paths []}]]
        (is (= {} (#'spool-sync/non-additive-diff previous {} {} approved survivors {} clean-status)))))
    (testing "a genuinely repointed root is still classified non-additive"
      (let [survivors [{:lib 'demo :entry {:root "/tmp/checkout/spools/other"} :source-paths []}]
            diff (#'spool-sync/non-additive-diff previous {} {} approved survivors {} clean-status)]
        (is (contains? diff :changed-roots))))))

;; --- Synced-provider-wins over the intentional test-classpath overlap (SPEC-004-D006.C5/C7) ---

(deftest synced-source-root-provider-wins-over-the-test-classpath-overlap
  (with-runtime
    (fn [rt config-dir]
      (let [checkout (test-support/temp-dir "skein-source-root-checkout")
            batteries-file (io/file checkout "spools" "batteries" "src" "skein" "spools" "batteries.clj")]
        ;; A source-root batteries whose synced source file differs from the
        ;; batteries source on the ambient test classpath, so provider precedence
        ;; is observable rather than incidentally identical.
        (.mkdirs (.getParentFile batteries-file))
        (spit batteries-file "(ns skein.spools.batteries)\n")
        (spit (io/file checkout "spools" "batteries" "deps.edn") "{:paths [\"src\"]}\n")
        (write-spools! config-dir {:spools {'skein.spools/batteries {:skein/source-root "spools/batteries"}}})
        (with-checkout checkout
          (fn []
            (sync! rt)
            (let [classpath-resource (io/resource "skein/spools/batteries.clj")
                  synced-path (.getCanonicalPath batteries-file)
                  resolved (@#'module-refresh/ns-source-file rt 'skein.spools.batteries)]
              (testing "batteries genuinely overlaps: it is on the ambient test classpath"
                (is (some? classpath-resource))
                (is (not= synced-path
                          (.getCanonicalPath (io/file (.toURI classpath-resource))))))
              (testing "module source resolution returns the synced source-root provider"
                (is (= synced-path resolved))))))))))
