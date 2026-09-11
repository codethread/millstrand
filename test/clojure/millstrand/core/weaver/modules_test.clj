(ns millstrand.core.weaver.modules-test
  "Tests for module refresh and generation continuity."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.basis :as basis]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.pool :as pool]
            [millstrand.core.weaver.pool-basis :as pool-basis-api]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.test.alpha :as t]))

(def ^:private source-checkout
  (.getCanonicalFile (io/file ".")))

(defn- temp-dir []
  (let [path (java.nio.file.Files/createTempDirectory
              (java.nio.file.Paths/get
               "/tmp" (make-array String 0))
              "ms-mod"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile path)))

(defn- delete-tree! [root]
  (when (.exists (io/file root))
    (with-open [paths (java.nio.file.Files/walk
                       (.toPath (io/file root))
                       (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (->> (.toArray paths)
                        (sort-by #(.getNameCount ^java.nio.file.Path %) >))]
        (java.nio.file.Files/deleteIfExists ^java.nio.file.Path path)))))

(defn- generation-basis [workspace]
  (let [coordinate {:local/root (.getCanonicalPath source-checkout)}]
    {:sources [{:kind :project
                :path (.getCanonicalPath (io/file workspace "deps.edn"))
                :deps {:paths []}}]
     :aliases []
     :reserved-deps {'io.millstrand/millstrand coordinate}
     :basis {:libs {'io.millstrand/millstrand coordinate}
             :classpath-roots []
             :argmap {}}
     :fingerprint (str "sha256:" (str/join (repeat 64 "a")))
     :classloader (.getContextClassLoader (Thread/currentThread))}))

(defn- refresh! [rt]
  (with-redefs [basis/create-generation-basis
                (fn [_workspace _coordinate] (:generation-basis rt))]
    (runtime/refresh! rt)))

(defn- with-runtime [f]
  (let [root (temp-dir)
        workspace (io/file root "workspace")
        state-dir (io/file root "state")
        data-dir (io/file root "data")
        db-file (db-test/temp-db-file)]
    (try
      (.mkdirs workspace)
      (spit (io/file workspace "deps.edn") "{:paths []}\n")
      (spit (io/file workspace "init.clj") "")
      (let [workspace-path (.getCanonicalPath workspace)
            world (weaver-config/world workspace-path
                                       (.getCanonicalPath state-dir)
                                       (.getCanonicalPath data-dir))
            started (with-redefs-fn
                      {#'weaver-runtime/install-built-in-ops! (fn [_runtime])}
                      #(weaver-runtime/start!
                        db-file {:world world
                                 :publish? false
                                 :generation-basis
                                 (generation-basis workspace-path)}))
            basis (generation-basis workspace-path)
            runtime (assoc started
                           :generation-basis basis
                           :basis-fingerprint (:fingerprint basis)
                           :generation-classloader (:classloader basis))]
        (try
          (weaver-runtime/with-runtime-binding runtime #(f runtime workspace))
          (finally
            (weaver-runtime/stop! started))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! root)))))

(defn- module-source! [workspace relative-path ns-sym query]
  (let [file (io/file workspace relative-path)]
    (io/make-parents file)
    (spit file
          (str "(ns " ns-sym ")\n"
               "(millstrand.api.runtime.alpha/collect-entry! "
               ":queries \"owned\" " (pr-str query) ")\n"))
    file))

(defn- pooled-member [root name]
  (let [member-root (io/file root name)
        config (io/file member-root "config")
        state (io/file member-root "state")
        source (io/file member-root "source")]
    (doseq [directory [config state source]] (.mkdirs directory))
    {:config-dir (.getCanonicalPath config)
     :source-cwd (.getCanonicalPath source)
     :state-dir (.getCanonicalPath state)
     :data-dir (.getCanonicalPath (io/file member-root "data"))
     :name name
     :weaver-id (str "weaver-" name)
     :generation-id (str "generation-" name)
     :dependency-diagnostic
     (.getCanonicalPath (io/file state "dependency.json"))}))

(defn- pooled-refresh-fixture [root]
  (let [members [(pooled-member root "a") (pooled-member root "b")]
        manifest {:format "millstrand.jvm-pool-launch/v1"
                  :jvm-pool "backend"
                  :host-id "host-test"
                  :host-generation-id "host-generation-test"
                  :membership-revision "membership-old"
                  :millstrand-source (.getCanonicalPath source-checkout)
                  :millstrand-version "dev"
                  :members members}
        generation-basis (fn [member]
                           {:config-dir (:config-dir member)
                            :generation-basis {:aliases []
                                               :reserved-deps {}
                                               :basis {:classpath-roots []}
                                               :fingerprint "member-fingerprint"
                                               :classloader (.getContextClassLoader
                                                             (Thread/currentThread))}})
        pool-basis {:pool/name "backend"
                    :host/id "host-test"
                    :host/generation-id "host-generation-test"
                    :membership/revision "membership-old"
                    :members (mapv generation-basis members)
                    :classpath-roots []
                    :fingerprint "pool-fingerprint"
                    :classloader (.getContextClassLoader (Thread/currentThread))}
        membership-file (io/file root "jvm-pools" "membership.json")]
    (.mkdirs (.getParentFile membership-file))
    {:manifest manifest
     :pool-basis pool-basis
     :membership-file membership-file
     :members members
     :host {:manifest manifest
            :pool-basis pool-basis
            :refresh-lock (Object.)
            :runtimes-by-config (zipmap (map :config-dir members)
                                        (map #(hash-map :member-config (:config-dir %))
                                             members))}}))

(defn- write-membership! [file members]
  (spit file (json/write-str {:format "millstrand.jvm-pool-membership/v1"
                              :revision "membership-new"
                              :members (mapv (fn [{:keys [config-dir source-cwd jvm-pool]}]
                                               {:config_dir config-dir
                                                :source_cwd source-cwd
                                                :jvm_pool jvm-pool})
                                             members)})))

(deftest reload-code-selects-loaded-namespaces-from-var-source-provenance
  (let [root (.getCanonicalPath (io/file "src"))
        coordinate {:local/root (.getCanonicalPath source-checkout)
                    :paths [root]}
        reloaded (atom [])
        rt {:generation-basis {:basis {:libs {'demo/source coordinate}}}
            :generation-classloader (.getContextClassLoader (Thread/currentThread))}]
    (with-redefs [clojure.core/require
                  (fn [namespace & args]
                    (swap! reloaded conj [namespace args]))]
      (let [result (weaver-runtime/reload-basis-lib! rt 'demo/source)]
        (is (= :reloaded (:status result)))
        (is (some #{'millstrand.core.weaver.runtime} (:namespaces result)))
        (is (some #{['millstrand.core.weaver.runtime '(:reload)]} @reloaded))))))

(deftest module-declarations-reject-unknown-options
  (let [error (try
                (runtime/module! {} :demo {:ns 'demo.module
                                           :unknown-option true})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= [:unknown-option] (-> error ex-data :unknown)))
    (is (re-find #"unknown keys" (ex-message error)))))

(deftest file-modules-change-live-within-one-generation
  (with-runtime
    (fn [rt workspace]
      (let [suffix (str/replace (str (random-uuid)) "-" "")
            ns-sym (symbol (str "test.module.live-" suffix))
            source "modules/live.clj"
            generation-id (:generation-id rt)
            fingerprint (:basis-fingerprint rt)]
        (module-source! workspace source ns-sym [:= [:attr :version] 1])
        (spit (io/file workspace "init.clj")
              (str "(millstrand.api.runtime.alpha/module! "
                   "millstrand.core.weaver.runtime/*runtime* "
                   ":live {:file \"" source "\"})\n"))
        (is (= :applied (:status (refresh! rt))))
        (is (= [:= [:attr :version] 1]
               (get (graph/queries rt) "owned")))

        (module-source! workspace source ns-sym [:= [:attr :version] 2])
        (is (= :applied (:status (refresh! rt))))
        (is (= [:= [:attr :version] 2]
               (get (graph/queries rt) "owned")))

        (spit (io/file workspace "init.clj") "")
        (is (= :applied (:status (refresh! rt))))
        (is (not (contains? (graph/queries rt) "owned")))
        (is (= generation-id (:generation-id rt)))
        (is (= fingerprint (:basis-fingerprint rt)))))))

(deftest same-namespace-declarations-replay-per-runtime-scope
  (let [ns-sym (symbol (str "test.module.shared-"
                            (str/replace (str (random-uuid)) "-" "")))]
    (with-runtime
      (fn [runtime-a workspace-a]
        (with-runtime
          (fn [runtime-b workspace-b]
            (module-source! workspace-a "modules/shared.clj" ns-sym
                            [:= [:attr :member] "a"])
            (module-source! workspace-b "modules/shared.clj" ns-sym
                            [:= [:attr :member] "b"])
            (spit (io/file workspace-a "init.clj")
                  (str "(millstrand.api.runtime.alpha/module! "
                       "millstrand.core.weaver.runtime/*runtime* "
                       ":shared {:file \"modules/shared.clj\"})\n"))
            (spit (io/file workspace-b "init.clj")
                  (str "(millstrand.api.runtime.alpha/module! "
                       "millstrand.core.weaver.runtime/*runtime* "
                       ":shared {:file \"modules/shared.clj\"})\n"))
            (is (= :applied (:status (refresh! runtime-a))))
            (is (= :applied (:status (refresh! runtime-b))))
            (is (= [:= [:attr :member] "a"]
                   (get (graph/queries runtime-a) "owned")))
            (is (= [:= [:attr :member] "b"]
                   (get (graph/queries runtime-b) "owned")))
            (is (= :unchanged (:status (refresh! runtime-a))))
            (is (= [:= [:attr :member] "a"]
                   (get (graph/queries runtime-a) "owned")))
            (is (= [:= [:attr :member] "b"]
                   (get (graph/queries runtime-b) "owned")))))))))

(deftest consumer-refresh-resolves-dependencies-from-the-runtime-coordinate
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn "{:deps {}}\n"
          :init-clj (str "(millstrand.api.runtime.alpha/module! "
                         "millstrand.core.weaver.runtime/*runtime* "
                         ":consumer {:file \"modules/consumer.clj\"})\n")
          :files {"modules/consumer.clj"
                  (str "(ns test.module.consumer)\n"
                       "(millstrand.api.runtime.alpha/collect-entry! "
                       ":queries \"consumer\" [:all])\n")}}]
    (is (= [:all] (get (graph/queries (:runtime ctx)) "consumer")))
    (spit (io/file (:config-dir ctx) "init.clj") "")
    (is (= :applied (:status (runtime/refresh! (:runtime ctx)))))
    (is (not (contains? (graph/queries (:runtime ctx)) "consumer")))))

(deftest changed-basis-short-circuits-before-activation
  (with-runtime
    (fn [rt workspace]
      (let [running (:basis-fingerprint rt)
            candidate (str "sha256:" (str/join (repeat 64 "f")))
            before @(:module-state rt)]
        (spit (io/file workspace "init.clj") "(throw (ex-info \"must not run\" {}))\n")
        (with-redefs [basis/create-generation-basis
                      (fn [_workspace _coordinate]
                        (assoc (:generation-basis rt) :fingerprint candidate))]
          (is (= {:status :restart-required
                  :reason :dependency-basis-changed
                  :basis {:running-fingerprint running
                          :candidate-fingerprint candidate}}
                 (runtime/refresh! rt))))
        (is (= before @(:module-state rt)))))))

(deftest status-is-the-closed-generation-view
  (with-runtime
    (fn [rt _workspace]
      (let [status (runtime/status rt)]
        (is (= #{:basis-fingerprint :modules :resources
                 :loaded-namespaces :last-refresh}
               (set (keys status))))
        (is (= (:basis-fingerprint rt) (:basis-fingerprint status)))
        (is (vector? (:loaded-namespaces status)))))))

(deftest pooled-refresh-ignores-unrelated-membership-registration
  (let [root (temp-dir)
        {:keys [host pool-basis membership-file members]} (pooled-refresh-fixture root)
        refreshed (atom [])
        rows (concat (map #(assoc (select-keys % [:config-dir :source-cwd])
                                  :jvm-pool "backend") members)
                     [{:config-dir "/unrelated/config"
                       :source-cwd "/unrelated/source"
                       :jvm-pool "other"}])]
    (try
      (write-membership! membership-file rows)
      (with-redefs [pool-basis-api/create-pool-basis (fn [_] pool-basis)
                    weaver-runtime/refresh-modules!
                    (fn [runtime _]
                      (swap! refreshed conj runtime)
                      {:status :unchanged :mode :full :modules {}})]
        (let [result (pool/refresh! host)]
          (is (= :unchanged (:status result)))
          (is (= 2 (count @refreshed)))
          (is (= (set (map :config-dir members))
                 (set (keys (:members result)))))))
      (finally
        (delete-tree! root)))))

(deftest pooled-membership-drift-short-circuits-before-member-refresh
  (let [root (temp-dir)
        {:keys [host pool-basis membership-file members]} (pooled-refresh-fixture root)
        refreshed (atom [])
        rows (map #(assoc (select-keys % [:config-dir :source-cwd])
                          :jvm-pool "changed") members)]
    (try
      (write-membership! membership-file rows)
      (with-redefs [pool-basis-api/create-pool-basis (fn [_] pool-basis)
                    weaver-runtime/refresh-modules!
                    (fn [_ _] (swap! refreshed conj true))]
        (let [result (pool/refresh! host)]
          (is (= :restart-required (:status result)))
          (is (= :pool/membership-changed (:reason result)))
          (is (empty? @refreshed))))
      (finally
        (delete-tree! root)))))

(deftest pooled-refresh-reports-completed-and-skipped-members-after-failure
  (let [root (temp-dir)
        {:keys [host pool-basis membership-file members]} (pooled-refresh-fixture root)
        first-config (:config-dir (first members))
        second-config (:config-dir (second members))
        rows (map #(assoc (select-keys % [:config-dir :source-cwd])
                          :jvm-pool "backend") members)]
    (try
      (write-membership! membership-file rows)
      (with-redefs [pool-basis-api/create-pool-basis (fn [_] pool-basis)
                    weaver-runtime/refresh-modules!
                    (fn [runtime _]
                      (if (= (:member-config runtime) first-config)
                        {:status :applied :mode :full :modules {}}
                        (throw (ex-info "member refresh failed" {}))))]
        (let [result (pool/refresh! host)]
          (is (= :partial (:status result)))
          (is (= :applied (get-in result [:members first-config :status])))
          (is (= :failed (get-in result [:members second-config :status])))))
      (finally
        (delete-tree! root)))))
