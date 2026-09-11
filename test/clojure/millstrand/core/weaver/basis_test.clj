(ns millstrand.core.weaver.basis-test
  "Tests for generation basis construction and semantic fingerprints."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.core.weaver.basis :as basis]
            [millstrand.core.weaver.pool-basis :as pool-basis]))

(defn- inspect-basis!
  [workspace & arguments]
  (let [command (into ["clojure" "-Srepro" "-X:deps" "basis"
                       ":user" "nil" ":project" "\"deps.edn\""]
                      arguments)
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.directory workspace)
                    (.redirectErrorStream true)
                    (.start))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (is (zero? exit) (str "basis inspection failed: " output))
    (edn/read-string output)))

(defn- workspace!
  [project extra]
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "millstrand-basis-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (io/file directory "deps.edn") (pr-str project))
    (when extra
      (spit (io/file directory "deps.local.edn") (pr-str extra)))
    directory))

(defn- resolved-basis
  [options]
  {:libs (merge (:deps (:project options))
                (:deps (:extra options))
                (get-in options [:args :extra-deps]))
   :classpath-roots []
   :argmap {:aliases (:aliases options)}})

(defn- local-library!
  [parent name namespace-name]
  (let [root (io/file parent name)
        source (io/file root "src" (str (str/replace namespace-name "." "/")
                                        ".clj"))]
    (.mkdirs (.getParentFile source))
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
    (spit source (str "(ns " namespace-name ")\n"))
    root))

(deftest dns-s9-01-and-02-compose-workspace-sources-without-user-deps
  (let [workspace (workspace!
                   {:deps {'example/shared {:local/root "shared"}}
                    :aliases {:millstrand/weaver {:extra-paths ["shared-src"]}}}
                   {:deps {'example/shared {:local/root "local"}}
                    :aliases {:millstrand/local {:extra-paths ["local-src"]}}})
        runtime-coordinate {:local/root "/millstrand"}
        captured (atom nil)
        generation
        (binding [basis/*create-basis*
                  (fn [options]
                    (reset! captured options)
                    (resolved-basis options))]
          (basis/create-generation-basis (.getPath workspace)
                                         runtime-coordinate))]
    (is (= [:millstrand/weaver :millstrand/local] (:aliases generation)))
    (is (nil? (:user @captured)))
    (is (= (.getCanonicalPath workspace) (:dir @captured)))
    (is (= {'io.millstrand/millstrand runtime-coordinate}
           (get-in @captured [:args :extra-deps])))
    (is (s/valid? :millstrand.core.specs/generation-basis generation))))

(deftest dns-s9-03-absent-local-overlays-are-optional
  (let [workspace (workspace! {:paths ["shared-src"]} nil)
        captured (atom nil)
        generation
        (binding [basis/*create-basis*
                  (fn [options]
                    (reset! captured options)
                    (resolved-basis options))]
          (basis/create-generation-basis
           (.getPath workspace)
           {:local/root "/millstrand"}))]
    (is (= [:project] (mapv :kind (:sources generation))))
    (is (= [] (:aliases generation)))
    (is (nil? (:extra @captured)))
    (is (not (.exists (io/file workspace "deps.local.edn"))))))

(deftest relative-classpath-roots-resolve-from-the-selected-workspace
  (let [workspace (workspace! {:paths ["."]} nil)
        absolute-root (.getCanonicalPath (io/file workspace "absolute"))
        generation
        (binding [basis/*create-basis*
                  (fn [_]
                    {:libs {}
                     :classpath-roots ["." "src" absolute-root]
                     :argmap {}})]
          (basis/create-generation-basis
           (.getPath workspace)
           {:local/root "/millstrand"}))]
    (is (= [(.getCanonicalPath workspace)
            (.getCanonicalPath (io/file workspace "src"))
            absolute-root]
           (get-in generation [:basis :classpath-roots])))))

(deftest create-generation-basis-validates-its-closed-options-contract
  (let [workspace (workspace! {} nil)
        runtime-coordinate {:local/root "/millstrand"}]
    (binding [basis/*create-basis* resolved-basis]
      (is (s/valid?
           :millstrand.core.specs/generation-basis
           (basis/create-generation-basis
            (.getPath workspace)
            runtime-coordinate
            {:dependency-source-workspace (.getPath workspace)}))))
    (doseq [options [{:unknown true}
                     {:dependency-source-workspace 42}]]
      (let [error (try
                    (basis/create-generation-basis
                     (.getPath workspace) runtime-coordinate options)
                    nil
                    (catch clojure.lang.ExceptionInfo throwable throwable))]
        (is (re-find #"options violate their contract" (ex-message error)))
        (is (map? (:explain (ex-data error))))))))

(deftest dns-s9-05-documented-basis-inspection
  (let [shared-root (workspace! {:paths ["shared-src"]} nil)
        local-root (workspace! {:paths ["local-src"]} nil)
        workspace
        (workspace!
         {:deps {'example/shared {:local/root (.getCanonicalPath shared-root)}}
          :aliases {:millstrand/weaver {:jvm-opts ["-Dshared=true"]}}}
         {:deps {'example/local {:local/root (.getCanonicalPath local-root)}}
          :aliases {:millstrand/local {:jvm-opts ["-Dlocal=true"]}}})
        shared (inspect-basis! workspace
                               ":aliases" "[:millstrand/weaver]")
        overlaid (inspect-basis! workspace
                                 ":extra" "\"deps.local.edn\""
                                 ":aliases"
                                 "[:millstrand/weaver :millstrand/local]")]
    (is (contains? (:libs shared) 'example/shared))
    (is (not (contains? (:libs shared) 'example/local)))
    (is (every? #(contains? (:libs overlaid) %)
                ['example/shared 'example/local]))
    (is (= ["-Dshared=true"] (get-in shared [:argmap :jvm-opts])))
    (is (= ["-Dshared=true" "-Dlocal=true"]
           (get-in overlaid [:argmap :jvm-opts])))))

(deftest copied-workspace-rebases-relative-local-roots-from-source
  (let [source (workspace! {} nil)
        parent (.getParentFile source)
        prefix (.getName source)
        project-name (str prefix "-project-lib")
        extra-name (str prefix "-extra-lib")
        alias-name (str prefix "-alias-lib")
        project-lib (local-library! parent project-name "example.project-lib")
        extra-lib (local-library! parent extra-name "example.extra-lib")
        alias-lib (local-library! parent alias-name "example.alias-lib")
        project {:deps {'example/project {:local/root (str "../" project-name)}}
                 :aliases {:millstrand/weaver
                           {:extra-deps {'example/alias
                                         {:local/root (str "../" alias-name)}}}}}
        extra {:deps {'example/extra {:local/root (str "../" extra-name)}}}
        copied (workspace! project extra)]
    (spit (io/file source "deps.edn") (pr-str project))
    (spit (io/file source "deps.local.edn") (pr-str extra))
    (let [generation
          (basis/create-generation-basis
           (.getPath copied)
           {:local/root (.getCanonicalPath (io/file "."))}
           {:dependency-source-workspace (.getPath source)})
          roots (set (get-in generation [:basis :classpath-roots]))]
      (is (every? roots
                  (map #(str (.getCanonicalPath %) "/src")
                       [project-lib extra-lib alias-lib])))
      (is (= (str "../" project-name)
             (get-in (edn/read-string (slurp (io/file copied "deps.edn")))
                     [:deps 'example/project :local/root])))
      (is (= (str "../" alias-name)
             (get-in (edn/read-string (slurp (io/file copied "deps.edn")))
                     [:aliases :millstrand/weaver :extra-deps
                      'example/alias :local/root])))
      (is (= (str "../" extra-name)
             (get-in (edn/read-string
                      (slurp (io/file copied "deps.local.edn")))
                     [:deps 'example/extra :local/root]))))))

(deftest copied-workspace-missing-relative-local-root-fails-loudly
  (let [source (workspace! {:deps {'example/missing
                                   {:local/root "../does-not-exist"}}}
                           nil)
        copied (workspace! (edn/read-string (slurp (io/file source "deps.edn")))
                           nil)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot resolve Weaver dependency basis"
         (basis/create-generation-basis
          (.getPath copied)
          {:local/root (.getCanonicalPath (io/file "."))}
          {:dependency-source-workspace (.getPath source)})))))

(deftest fingerprint-is-canonical-and-semantic
  (let [project {:deps {'a/x {:mvn/version "1"}}
                 :aliases {:millstrand/weaver {:jvm-opts ["-Dmode=weaver"]}}}
        create-basis (fn [root version]
                       {:libs {'a/x {:mvn/version version
                                     :deps/manifest :mvn
                                     :deps/root (str root "/cache")
                                     :paths [(str root "/cache/a-x.jar")]}
                               'io.millstrand/millstrand
                               {:local/root (str root "/millstrand")
                                :deps/root (str root "/millstrand")
                                :paths [(str root "/millstrand/src")]}}
                        :classpath-roots [(str root "/workspace/src")]
                        :argmap {:jvm-opts ["-Dmode=weaver"]
                                 :extra-paths [(str root "/workspace/dev")]}})
        fingerprint (fn [workspace root version]
                      (binding [basis/*create-basis*
                                (fn [_] (create-basis root version))]
                        (:fingerprint
                         (basis/create-generation-basis
                          (.getPath workspace)
                          {:local/root (str root "/millstrand")}))))
        left (workspace! project nil)
        relocated (workspace! project nil)
        coordinate-edit (workspace! (assoc-in project [:deps 'a/x :mvn/version]
                                              "2") nil)
        alias-edit (workspace! (assoc-in project
                                         [:aliases :millstrand/weaver :jvm-opts]
                                         ["-Dmode=changed"]) nil)
        source-edit (workspace! project {:deps {'extra/x {:mvn/version "1"}}})
        left-fingerprint (fingerprint left "/first" "1")]
    (is (= left-fingerprint (fingerprint relocated "/copied" "1")))
    (is (not= left-fingerprint (fingerprint coordinate-edit "/copied" "2")))
    (is (not= left-fingerprint (fingerprint alias-edit "/copied" "1")))
    (is (not= left-fingerprint (fingerprint source-edit "/copied" "1")))
    (is (re-matches #"sha256:[0-9a-f]{64}" left-fingerprint))))

(deftest dependency-files-fail-with-closed-diagnostics
  (testing "missing canonical file"
    (let [workspace (workspace! {} nil)]
      (.delete (io/file workspace "deps.edn"))
      (try
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"})
        (is false "expected missing deps.edn to fail")
        (catch clojure.lang.ExceptionInfo failure
          (is (= :deps-read (:stage (ex-data failure))))
          (is (s/valid? :millstrand.core.specs/dependency-diagnostic
                        (basis/dependency-diagnostic failure)))))))
  (testing "reserved runtime coordinate"
    (let [workspace (workspace!
                     {:deps {'io.millstrand/millstrand
                             {:local/root "elsewhere"}}}
                     nil)]
      (try
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"})
        (is false "expected reserved coordinate to fail")
        (catch clojure.lang.ExceptionInfo failure
          (is (= "reserved dependency io.millstrand/millstrand is supplied by Mill"
                 (:message (ex-data failure)))))))))

(deftest resolver-failure-preserves-cause
  (let [workspace (workspace! {:deps {'broken/lib {:mvn/version "nope"}}}
                              nil)]
    (try
      (binding [basis/*create-basis*
                (fn [_]
                  (throw (ex-info "artifact missing"
                                  {:lib 'broken/lib
                                   :coord {:mvn/version "nope"}})))]
        (basis/create-generation-basis (.getPath workspace)
                                       {:local/root "/millstrand"}))
      (is false "expected resolution to fail")
      (catch clojure.lang.ExceptionInfo failure
        (is (= :deps-resolve (:stage (ex-data failure))))
        (is (= "artifact missing" (:cause (ex-data failure))))
        (is (= 'broken/lib (get-in (ex-data failure) [:coordinate :lib])))))))

(deftest canonical-encoder-rejects-tagged-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"outside the canonical EDN domain"
                        (basis/canonical-edn (java.util.UUID/randomUUID)))))

(defn- launch-manifest
  [members source]
  {:format "millstrand.jvm-pool-launch/v1"
   :jvm-pool "backend"
   :host-id "host-1"
   :host-generation-id "host-generation-1"
   :membership-revision "membership-1"
   :millstrand-source (.getCanonicalPath source)
   :millstrand-version "dev"
   :members (mapv (fn [[config-dir source-cwd name]]
                    {:config-dir (.getCanonicalPath config-dir)
                     :source-cwd (.getCanonicalPath source-cwd)
                     :state-dir (.getCanonicalPath (io/file config-dir "state"))
                     :data-dir (.getCanonicalPath (io/file config-dir "data"))
                     :name name
                     :weaver-id (str "weaver-" name)
                     :generation-id (str "generation-" name)
                     :dependency-diagnostic
                     (.getCanonicalPath (io/file config-dir "dependency.json"))})
                  members)})

(deftest pool-basis-keeps-member-relative-resolution-and-root-order
  (let [source (workspace! {} nil)
        member-a (workspace! {:deps {'demo/a {:local/root "member-a-root"}}} nil)
        member-b (workspace! {:deps {'demo/b {:local/root "member-b-root"}}} nil)
        captured (atom [])
        manifest (launch-manifest [[member-a source "a"]
                                   [member-b source "b"]]
                                  source)
        root-a (.getCanonicalPath (io/file source "root-a"))
        root-b (.getCanonicalPath (io/file source "root-b"))
        shared (.getCanonicalPath (io/file source "shared"))
        created
        (binding [basis/*create-basis*
                  (fn [options]
                    (swap! captured conj options)
                    {:libs {:demo/a {:mvn/version "1"}}
                     :classpath-roots (if (= (.getCanonicalPath member-a)
                                             (:dir options))
                                        [root-a shared shared]
                                        [shared root-b])
                     :argmap {}})]
          (pool-basis/create-pool-basis manifest
                                        {:local/root (.getCanonicalPath source)}))]
    (is (= [(.getCanonicalPath member-a) (.getCanonicalPath member-b)]
           (mapv :dir @captured)))
    (is (every? #(= (.getCanonicalPath
                     (io/file source
                              (if (= (:dir %) (.getCanonicalPath member-a))
                                "member-a-root"
                                "member-b-root")))
                    (get-in % [:project :deps (if (= (:dir %) (.getCanonicalPath member-a))
                                                'demo/a
                                                'demo/b) :local/root]))
                @captured))
    (is (= [root-a shared root-b] (:classpath-roots created)))
    (is (nil? (:libs created))
        "the pool basis never presents a merged dependency map")
    (is (= {:mvn/version "1"}
           (get-in created [:members 0 :generation-basis :basis :libs :demo/a])))
    (is (instance? ClassLoader (:classloader created)))
    (is (= 2 (count (:members created))))
    (is (not= (get-in created [:members 0 :generation-basis :classloader])
              (get-in created [:members 1 :generation-basis :classloader])))))

(deftest pool-basis-fingerprint-includes-members-and-member-roots
  (let [source (workspace! {} nil)
        member-a (workspace! {} nil)
        member-b (workspace! {} nil)
        manifest (launch-manifest [[member-a source "a"]
                                   [member-b source "b"]]
                                  source)
        roots (atom {(.getCanonicalPath member-a) ["/root/a"]
                     (.getCanonicalPath member-b) ["/root/b"]})
        make-basis (fn [candidate]
                     (binding [basis/*create-basis*
                               (fn [{:keys [dir]}]
                                 {:libs {}
                                  :classpath-roots (@roots dir)
                                  :argmap {}})]
                       (pool-basis/create-pool-basis
                        candidate {:local/root (.getCanonicalPath source)})))
        original (make-basis manifest)
        changed-root (do (swap! roots update (.getCanonicalPath member-b)
                                conj "/root/changed")
                         (make-basis manifest))]
    (is (not= (:fingerprint original) (:fingerprint changed-root)))
    (is (not= (:fingerprint original)
              (:fingerprint
               (make-basis (assoc-in manifest [:members 1 :name]
                                     "changed")))))
    (is (not= (:fingerprint original)
              (:fingerprint
               (make-basis
                (assoc manifest :members (vec (reverse (:members manifest))))))))))

(deftest pool-manifests-are-closed
  (let [source (workspace! {} nil)
        member (workspace! {} nil)
        manifest (launch-manifest [[member source "a"]] source)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"closed contract"
         (pool-basis/validate-launch-manifest
          (assoc manifest :unexpected true))))))

(deftest probe-basis-uses-private-config-and-original-source-authority
  (let [source (workspace! {} nil)
        original-root (workspace! {} nil)
        probe-root (workspace! {:deps {'demo/local {:local/root "original-lib"}}}
                               nil)
        captured (atom nil)
        manifest {:format "millstrand.jvm-pool-probe/v1"
                  :jvm-pool "backend"
                  :probe-id "probe-1"
                  :candidate-host-id "probe-host-1"
                  :candidate-host-generation-id "probe-generation-1"
                  :probe-root (.getCanonicalPath (io/file probe-root "root"))
                  :millstrand-source (.getCanonicalPath source)
                  :result (.getCanonicalPath (io/file probe-root "result.json"))
                  :collective-diagnostic
                  (.getCanonicalPath (io/file probe-root "collective.jsonl"))
                  :members [{:original-config-dir (.getCanonicalPath original-root)
                             :original-source-cwd (.getCanonicalPath source)
                             :probe-config-dir (.getCanonicalPath probe-root)
                             :probe-state-dir
                             (.getCanonicalPath (io/file probe-root "state"))
                             :probe-data-dir
                             (.getCanonicalPath (io/file probe-root "data"))
                             :member-diagnostic
                             (.getCanonicalPath (io/file probe-root "member.jsonl"))
                             :name "a"
                             :candidate-weaver-id "probe-weaver-a"
                             :candidate-generation-id "probe-generation-a"
                             :old-member-baseline nil}]}
        result
        (binding [basis/*create-basis*
                  (fn [options]
                    (reset! captured options)
                    {:libs {}
                     :classpath-roots []
                     :argmap {}})]
          (pool-basis/create-probe-pool-basis
           manifest {:local/root (.getCanonicalPath source)}))]
    (is (= (.getCanonicalPath probe-root) (:dir @captured)))
    (is (= (.getCanonicalPath (io/file source "original-lib"))
           (get-in @captured [:project :deps 'demo/local :local/root])))
    (is (= (.getCanonicalPath original-root)
           (get-in result [:members 0 :config-dir])))
    (is (instance? ClassLoader (:classloader result)))))
