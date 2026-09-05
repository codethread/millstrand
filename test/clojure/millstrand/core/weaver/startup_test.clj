(ns millstrand.core.weaver.startup-test
  "Tests for startup world resolution, module loading, and runtime lifecycle."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.weaver.basis :as basis]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.weaver.scheduler :as scheduler]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.source-file :as source-file]
            [millstrand.spools.test-support :as test-support])

  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]))

(def delete-tree! test-support/delete-tree!)

(def ^:private basis-fingerprint
  (str "sha256:" (str/join (repeat 64 "a"))))

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (spit (io/file workspace "deps.edn") "{:paths []}\n")
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn- generation-basis
  ([world] (generation-basis world []))
  ([world classpath-roots]
   (let [coordinate {:local/root (.getCanonicalPath (io/file "."))}
         classpath-roots (mapv #(.getCanonicalPath (io/file %)) classpath-roots)
         classloader (clojure.lang.DynamicClassLoader.
                      (.getContextClassLoader (Thread/currentThread)))]
     (doseq [root classpath-roots]
       (.addURL classloader (.toURL (.toURI (io/file root)))))
     {:sources [{:kind :project
                 :path (.getCanonicalPath
                        (io/file (:config-dir world) "deps.edn"))
                 :deps {:paths []}}]
      :aliases []
      :reserved-deps {'io.millstrand/millstrand coordinate}
      :basis {:libs {'io.millstrand/millstrand coordinate}
              :classpath-roots classpath-roots
              :argmap {}}
      :fingerprint basis-fingerprint
      :classloader classloader})))

(defn- runtime-coordinate []
  {:local/root (.getCanonicalPath (io/file "."))})

(defn- start-runtime! [db-file opts]
  (let [world (:world opts)]
    (weaver-runtime/start!
     db-file
     (assoc opts :generation-basis
            (or (:generation-basis opts) (generation-basis world))))))

(defn- fresh-runtime-probe! [world opts]
  (binding [basis/*create-basis*
            (fn [{:keys [project extra aliases args]}]
              {:libs (merge (:deps project) (:deps extra) (:extra-deps args))
               :classpath-roots []
               :argmap {:aliases aliases}})]
    (weaver-runtime/fresh-runtime-probe! world opts)))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (start-runtime! db-file (assoc (or start-options {}) :world world :publish? false))]
     (try
       (weaver-runtime/with-runtime-binding rt #(f rt db-file))
       (finally
         (weaver-runtime/stop! rt)
         (db-test/delete-sqlite-family! db-file)
         (delete-tree! (io/file (:config-dir world))))))))

(defn socket-request-envelope [rt req]
  (let [m (:metadata rt)]
    (with-open [ch (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                     (.connect (UnixDomainSocketAddress/of (:socket-path m))))
                rdr (BufferedReader. (InputStreamReader. (Channels/newInputStream ch)))
                wrt (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch)))]
      (.write wrt (json/write-str req))
      (.newLine wrt)
      (.flush wrt)
      (json/read-str (.readLine rdr)))))

(defn socket-request [rt operation arguments]
  (let [m (:metadata rt)]
    (socket-request-envelope rt {"protocol_version" 3
                                 "request_id" "test-request"
                                 "weaver_id" (:nonce m)
                                 "operation" operation
                                 "arguments" arguments
                                 "options" {}})))

(deftest weaver-world-resolution
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Millstrand workspace selected"
                        (weaver-config/world)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Millstrand workspace selected"
                        (weaver-config/world nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"state-dir"
                        (weaver-config/world "/tmp/config" nil "/tmp/data")))
  (let [root (.getCanonicalFile (.toFile (java.nio.file.Files/createTempDirectory "tdx" (make-array java.nio.file.attribute.FileAttribute 0))))
        workspace (.getPath (io/file root "config"))
        state-dir (.getPath (io/file root "state"))
        data-dir (.getPath (io/file root "data"))]
    (is (= {:config-dir workspace
            :state-dir state-dir
            :data-dir data-dir
            :config-file (str workspace "/config.json")
            :db-path (str data-dir "/millstrand.sqlite")}
           (weaver-config/world workspace state-dir data-dir)))))

(deftest startup-uses-independent-xdg-world-dirs-and-initializes-storage
  (let [world (temp-world)
        rt (start-runtime! nil {:world world :publish? false})]
    (try
      (let [metadata (:metadata rt)]
        (is (= (:config-dir world) (:config-dir metadata)))
        (is (= (:state-dir world) (:state-dir metadata)))
        (is (= (:data-dir world) (:data-dir metadata)))
        (is (= (:db-path world) (:canonical-db-path metadata)))
        (is (string? (:generation-id metadata)))
        (is (not (str/blank? (:generation-id metadata))))
        (is (= (:generation-id metadata) (:generation-id rt)))
        (is (= (:basis-fingerprint rt) (:basis-fingerprint metadata)))
        (is (= basis-fingerprint (:basis-fingerprint metadata)))
        (is (= metadata (metadata/read-metadata world)))
        (is (false? (metadata/valid-metadata?
                     (dissoc metadata :version))))
        (is (false? (metadata/valid-metadata?
                     (assoc metadata :version "next"))))
        (is (false? (metadata/valid-metadata?
                     (dissoc metadata :basis-fingerprint))))
        (is (false? (metadata/valid-metadata?
                     (assoc metadata :basis-fingerprint "sha256:invalid"))))
        (is (.isFile (io/file (:state-dir world) "weaver.edn")))
        (is (.isFile (io/file (:state-dir world) "weaver.json")))
        (is (.exists (io/file (:state-dir world) "weaver.sock")))
        (is (.isFile (io/file (:data-dir world) "millstrand.sqlite")))
        (is (= ["depends-on" "notes" "parent-of" "serves" "supersedes"] (weaver/acyclic-relations rt)))
        (is (seq (db/execute! (:datasource rt) ["SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'strands'"]))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-owns-a-file-storage-handle
  (let [world (temp-world)
        rt (start-runtime! nil {:world world :publish? false})]
    (try
      (let [storage (:storage rt)]
        (is (= :sqlite-file (:storage-kind storage)))
        (is (= (:db-path world) (:canonical-db-path storage)))
        (is (= (:canonical-db-path storage) (:storage-label storage)))
        (is (= (:datasource rt) (:connectable storage)))
        (is (nil? (:close-fn storage))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest memory-storage-runtime-serves-weaver-api-without-a-db-file
  (let [world (temp-world)
        rt (start-runtime! nil {:world world :publish? false :storage :sqlite-memory})]
    (try
      (is (= :sqlite-memory (get-in rt [:storage :storage-kind])))
      (is (nil? (get-in rt [:storage :canonical-db-path])))
      (is (false? (.exists (io/file (:data-dir world) "millstrand.sqlite"))))
      (testing "metadata and status report memory storage without a fake path"
        (is (= :sqlite-memory (get-in rt [:metadata :storage-kind])))
        (is (nil? (get-in rt [:metadata :canonical-db-path])))
        (is (false? (metadata/stale-or-missing? (:metadata rt))))
        (let [json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
          (is (= "sqlite-memory" (get json-disk "database_kind")))
          (is (= (get-in rt [:metadata :storage-label]) (get json-disk "database_label")))
          (is (= (get-in rt [:metadata :generation-id]) (get json-disk "generation_id")))
          (is (= (:basis-fingerprint rt)
                 (get json-disk "basis_fingerprint")))
          (is (contains? json-disk "database_path"))
          (is (nil? (get json-disk "database_path"))))
        (let [status (socket-request rt "status" {})]
          (is (true? (get status "ok")))
          (is (= "sqlite-memory" (get-in status ["result" "database_kind"])))
          (is (= (:generation-id (:metadata rt))
                 (get-in status ["result" "generation_id"])))
          (is (nil? (get-in status ["result" "database_path"])))
          (is (not (contains? (get status "result") "registry_projection"))))
        (let [projection-status
              (socket-request rt "status"
                              {"include_registry_projection" true})]
          (is (map? (get-in projection-status
                            ["result" "registry_projection"])))))
      (let [strand (weaver/add! rt {:title "Mem strand" :attributes {:owner "mem"}})]
        (is (= [(:id strand)] (mapv :id (weaver/ready rt)))))
      (testing "concurrent weaver API calls at test scale"
        (let [ids (->> (range 10)
                       (mapv (fn [i] (future (:id (weaver/add! rt {:title (str "c" i)})))))
                       (mapv deref))]
          (is (= 10 (count (distinct ids))))
          (is (= 11 (count (weaver/list rt))))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))
    (testing "storage is destroyed with the runtime"
      (is (thrown? java.sql.SQLException (db/all-strands (:datasource rt)))))))

(deftest storage-selection-fails-loudly-on-bad-input
  (let [world (temp-world)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not take a database file"
                            (start-runtime! (db-test/temp-db-file)
                                            {:world world :publish? false :storage :sqlite-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver storage kind"
                            (start-runtime! nil {:world world :publish? false :storage :postgres})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest fresh-runtime-probe-is-unpublished-and-cleans-success
  (let [world (temp-world)
        result (try
                 (fresh-runtime-probe!
                  world {:old-generation-baseline {:status :admitted :projection {}}
                         :runtime-coordinate (runtime-coordinate)})
                 (finally
                   (delete-tree! (io/file (:config-dir world) ".."))))]
    (is (true? (:success result)))
    (is (= :probe/complete (:stage result)))
    (is (seq (:candidate-registries result)))
    (is (vector? (:diagnostics result)))
    (is (not (.exists (io/file (:probe/workspace result)))))
    (is (nil? @weaver-runtime/current-runtime))
    (is (not (.exists (io/file (:state-dir world) "weaver.json"))))
    (is (not (.exists (io/file (:data-dir world) "millstrand.sqlite"))))))

(deftest fresh-runtime-probe-rejects-invalid-source-and-coordinate
  (let [world (temp-world)
        opts {:old-generation-baseline {:status :admitted :projection {}}
              :runtime-coordinate (runtime-coordinate)}]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Fresh runtime probe requires a selected world"
           (weaver-runtime/fresh-runtime-probe!
            (assoc world :config-dir 42) opts)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Fresh runtime probe options have an invalid shape"
           (weaver-runtime/fresh-runtime-probe!
            world (assoc opts :runtime-coordinate {:local/root ""}))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest fresh-runtime-probe-resolves-copied-relative-local-roots-from-source
  (let [world (temp-world)
        source-root (io/file (:config-dir world) ".." "probe-lib")
        source-dir (io/file source-root "src" "probe")
        captured-roots (atom nil)]
    (.mkdirs source-dir)
    (spit (io/file source-root "deps.edn") "{:paths [\"src\"]}\n")
    (spit (io/file source-dir "fixture.clj") "(ns probe.fixture)\n")
    (spit (io/file (:config-dir world) "deps.edn")
          "{:deps {probe/fixture {:local/root \"../probe-lib\"}}}\n")
    (try
      (let [result
            (binding [basis/*create-basis*
                      (fn [options]
                        (let [resolved
                              ((requiring-resolve
                                'clojure.tools.deps/create-basis)
                               options)]
                          (reset! captured-roots (:classpath-roots resolved))
                          resolved))]
              (weaver-runtime/fresh-runtime-probe!
               world {:old-generation-baseline
                      {:status :admitted :projection {}}
                      :runtime-coordinate (runtime-coordinate)}))]
        (is (true? (:success result)))
        (is (some #{(.getCanonicalPath (io/file source-root "src"))}
                  @captured-roots)))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest fresh-runtime-probe-loads-namespace-modules-from-candidate-basis
  (let [world (temp-world)
        module-root (io/file (:config-dir world) ".." "probe-module")
        module-source (io/file module-root "src" "probe" "candidate_module.clj")
        module-source-root (.getCanonicalPath (io/file module-root "src"))
        captured-coordinate (atom nil)]
    (io/make-parents module-source)
    (spit (io/file module-root "deps.edn") "{:paths [\"src\"]}\n")
    (spit module-source
          (str "(ns probe.candidate-module)\n"
               "(millstrand.api.runtime.alpha/collect-entry! "
               ":queries \"candidate-only\" [:all])\n"))
    (spit (io/file (:config-dir world) "deps.edn")
          "{:deps {probe/module {:local/root \"../probe-module\"}}}\n")
    (spit (io/file (:config-dir world) "init.clj")
          (str "(millstrand.api.runtime.alpha/module! "
               "millstrand.core.weaver.runtime/*runtime* "
               ":candidate-only {:ns 'probe.candidate-module})\n"))
    (try
      (let [result
            (binding [basis/*create-basis*
                      (fn [{:keys [project extra aliases args]}]
                        (reset! captured-coordinate
                                (get-in args [:extra-deps
                                              'io.millstrand/millstrand]))
                        {:libs (merge (:deps project) (:deps extra)
                                      (:extra-deps args))
                         :classpath-roots [module-source-root]
                         :argmap {:aliases aliases}})]
              (weaver-runtime/fresh-runtime-probe!
               world {:old-generation-baseline
                      {:status :admitted :projection {}}
                      :runtime-coordinate (runtime-coordinate)}))]
        (is (true? (:success result)))
        (is (= (runtime-coordinate) @captured-coordinate))
        (is (= ["all"]
               (get-in result [:candidate-registries "queries" "effective"
                               "queries" "candidate-only" "value"]))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest runtime-rejects-invalid-source-config-dir-provenance
  (let [world (assoc (temp-world) :source-config-dir 42)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Weaver start options have an invalid shape"
                            (start-runtime!
                             nil {:world world
                                  :publish? false
                                  :storage :sqlite-memory})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest probe-failure-envelope-ignores-colliding-ex-data
  (let [world (temp-world)]
    (try
      (with-redefs [weaver-runtime/start!
                    (fn [_ _]
                      (throw (ex-info "candidate failed"
                                      {:success true
                                       :stage :probe/complete
                                       :probe/workspace "/foreign"})))]
        (let [result (fresh-runtime-probe!
                      world {:old-generation-baseline
                             {:status :admitted :projection {}}
                             :runtime-coordinate (runtime-coordinate)})]
          (is (false? (:success result)))
          (is (= :probe/failure (:stage result)))
          (is (not= "/foreign" (:probe/workspace result)))
          (is (= :probe/complete (get-in result [:failure :data :stage])))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest probe-adapter-conformance-corpus-is-readable
  (let [corpus (json/read-str
                (slurp "cli/cmd/mill/testdata/restart-conformance.json"))
        cases (get corpus "probe_results")]
    (is (= 4 (count cases)))
    (doseq [{:strs [name valid value]} cases]
      (testing name
        (is (map? value))
        (is (boolean? valid))))))

(defn- nonblank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-admission-case? [value]
  (and (map? value)
       (every? #{"state" "generation_id" "transition_id"} (keys value))
       (case (get value "state")
         "open" (and (= #{"state" "generation_id"} (set (keys value)))
                     (nonblank-string? (get value "generation_id")))
         "closed" (and (= #{"state" "transition_id"} (set (keys value)))
                       (nonblank-string? (get value "transition_id")))
         false)))

(defn- valid-restart-diagnostic-case? [value]
  (and (map? value)
       (every? #{"stage" "status" "data"} (keys value))
       (nonblank-string? (get value "stage"))
       (contains? #{"completed" "failed" "skipped" "in-progress"}
                  (get value "status"))
       (or (not (contains? value "data")) (map? (get value "data")))))

(defn- valid-restart-projection-case? [value]
  (and (map? value)
       (every? #{"operation" "workspace" "state" "generation_id"
                 "transition_id" "diagnostics"} (keys value))
       (= "restart" (get value "operation"))
       (nonblank-string? (get value "workspace"))
       (contains? #{"probing" "restarting" "running" "failed"}
                  (get value "state"))
       (every? #(or (not (contains? value %))
                    (nonblank-string? (get value %)))
               ["generation_id" "transition_id"])
       (or (not (contains? value "diagnostics"))
           (and (vector? (get value "diagnostics"))
                (seq (get value "diagnostics"))
                (every? valid-restart-diagnostic-case?
                        (get value "diagnostics"))))
       (case (get value "state")
         "probing" (and (contains? value "generation_id")
                        (contains? value "transition_id")
                        (not (contains? value "diagnostics")))
         "restarting" (and (contains? value "transition_id")
                           (not (contains? value "generation_id"))
                           (not (contains? value "diagnostics")))
         "running" (and (contains? value "generation_id")
                        (not (contains? value "diagnostics")))
         "failed" (contains? value "diagnostics")
         false)))

(defn- valid-probe-case? [value]
  (and (map? value)
       (= #{"success" "stage" "probe/workspace" "source/workspace"
            "completed" "diagnostics" "log"}
          (set (keys value)))
       (boolean? (get value "success"))
       (= (if (get value "success") "probe/complete" "probe/failure")
          (get value "stage"))
       (every? #(nonblank-string? (get value %))
               ["probe/workspace" "source/workspace" "log"])
       (vector? (get value "completed"))
       (every? nonblank-string? (get value "completed"))
       (vector? (get value "diagnostics"))
       (every? valid-restart-diagnostic-case? (get value "diagnostics"))))

(defn- valid-restart-record-case? [value]
  (let [state (get value "state")
        probe (get value "probe")
        failure (get value "failure")]
    (and (map? value)
         (every? #{"state" "transition_id" "generation_id"
                   "previous_generation_id" "updated_at"
                   "old_generation_stopped" "probe" "failure"} (keys value))
         (contains? #{"probing" "restarting" "running" "failed"} state)
         (nonblank-string? (get value "transition_id"))
         (nonblank-string? (get value "updated_at"))
         (or (not (contains? value "generation_id"))
             (nonblank-string? (get value "generation_id")))
         (or (not (contains? value "previous_generation_id"))
             (nonblank-string? (get value "previous_generation_id")))
         (or (not (contains? value "old_generation_stopped"))
             (false? (get value "old_generation_stopped"))
             (and (nonblank-string? (get value "generation_id"))
                  (map? probe) (true? (get probe "success"))))
         (or (not (contains? value "probe")) (valid-probe-case? probe))
         (or (not (contains? value "failure"))
             (and (map? failure)
                  (every? #{"stage" "message" "log_path" "exit_evidence"}
                          (keys failure))
                  (nonblank-string? (get failure "stage"))
                  (nonblank-string? (get failure "message"))))
         (or (not= state "failed") (map? failure))
         (or (not (contains? value "failure"))
             (= state "failed")
             (and (= state "running")
                  (= "probe" (get failure "stage"))
                  (false? (get probe "success"))))
         (or (not (get value "old_generation_stopped"))
             (= "launch" (get failure "stage"))))))

(deftest restart-boundary-conformance-corpus-executes-in-clojure
  (let [corpus (json/read-str (slurp "cli/cmd/mill/testdata/restart-conformance.json"))]
    (doseq [{:strs [name valid value]} (get corpus "probe_results")]
      (testing (str "probe/" name)
        (is (= valid (valid-probe-case? value)))))
    (doseq [{:strs [name valid value]} (get corpus "admission_states")]
      (testing (str "admission/" name)
        (is (= valid (valid-admission-case? value)))))
    (doseq [{:strs [name valid value]} (get corpus "restart_projections")]
      (testing (str "projection/" name)
        (is (= valid (valid-restart-projection-case? value)))))
    (doseq [{:strs [name valid value]} (get corpus "restart_records")]
      (testing (str "record/" name)
        (is (= valid (valid-restart-record-case? value)))))))

(deftest successful-probe-cleanup-fails-loudly-with-path
  (let [world (temp-world)]
    (try
      (with-redefs [weaver-runtime/delete-tree!
                    (fn [root]
                      (throw (ex-info "probe cleanup seam" {:probe/workspace
                                                            (.getPath root)})))]
        (let [result (fresh-runtime-probe!
                      world {:old-generation-baseline
                             {:status :admitted :projection {}}
                             :runtime-coordinate (runtime-coordinate)})]
          (is (false? (:success result)))
          (is (= :probe/failure (:stage result)))
          (is (string? (:probe/workspace result)))
          (is (re-find #"probe cleanup seam" (get-in result [:failure :message])))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest probe-failure-after-start-stops-runtime-and-keeps-primary-error
  (let [world (temp-world)
        stopped (atom nil)
        original-stop weaver-runtime/stop!
        report-skipped (ns-resolve 'millstrand.core.weaver.runtime
                                   'report-probe-skipped!)]
    (try
      (let [result (with-redefs-fn
                     {report-skipped (fn [_]
                                       (throw (ex-info "post-start probe failure" {})))
                      #'weaver-runtime/stop!
                      (fn [runtime]
                        (reset! stopped runtime)
                        (original-stop runtime))}
                     #(fresh-runtime-probe!
                       world {:old-generation-baseline
                              {:status :admitted :projection {}}
                              :runtime-coordinate (runtime-coordinate)}))]
        (is (false? (:success result)))
        (is (= "post-start probe failure"
               (get-in result [:failure :message])))
        (is (some? @stopped))
        (is (.exists (io/file (:probe/workspace result))))
        (is (.exists (io/file (:log result)))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest direct-probe-start-is-unpublished
  (let [world (temp-world)
        rt (start-runtime! nil {:world world
                                :probe? true
                                :storage :sqlite-memory
                                :old-generation-baseline
                                {:status :admitted :projection {}}})]
    (try
      (is (nil? @weaver-runtime/current-runtime))
      (is (nil? (metadata/read-metadata world)))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest probe-preserves-live-metadata-preflight
  (let [world (temp-world)
        rt (start-runtime! nil {:world world :publish? false})]
    (try
      (let [failure (try
                      (start-runtime! nil {:world world
                                           :probe? true
                                           :storage :sqlite-memory})
                      (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :metadata-present (:reason (ex-data failure))))
        (is (= (:config-dir world) (:config-dir (ex-data failure)))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest probe-preserves-orphaned-socket-preflight
  (let [world (temp-world)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/socket-file world) "orphan")
      (let [failure (try
                      (start-runtime! nil {:world world
                                           :probe? true
                                           :storage :sqlite-memory})
                      (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :orphaned-socket (:reason (ex-data failure))))
        (is (= (.getPath (metadata/socket-file world))
               (:socket-path (ex-data failure)))))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-owned-metadata-and-socket-are-reclaimed-before-bind
  (let [world (temp-world)
        db-file (db-test/temp-db-file)
        stale (metadata/metadata-shape
               {:pid 999999
                :version "0.5.1"
                :host "127.0.0.1"
                :port 5555
                :storage-kind :sqlite-file
                :storage-label (metadata/canonical-db-path db-file)
                :canonical-db-path (metadata/canonical-db-path db-file)
                :nonce "stale-generation"
                :generation-id "generation-old"
                :basis-fingerprint basis-fingerprint
                :world world
                :name "stale"
                :started-at "2026-08-24T00:00:00Z"})
        claim (atom nil)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/metadata-file world) (pr-str stale))
      (spit (metadata/json-metadata-file world) "stale")
      (spit (metadata/socket-file world) "stale")
      (metadata/claim-pre-publication-artifacts! world claim)
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (finally
        (metadata/release-pre-publication-artifacts! world claim)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-metadata-never-unlinks-a-live-unknown-socket-owner
  (let [world (temp-world)
        db-file (db-test/temp-db-file)
        stale (metadata/metadata-shape
               {:pid 999999
                :version "0.5.1"
                :host "127.0.0.1"
                :port 5555
                :storage-kind :sqlite-file
                :storage-label (metadata/canonical-db-path db-file)
                :canonical-db-path (metadata/canonical-db-path db-file)
                :nonce "unknown-owner"
                :generation-id "generation-unknown"
                :basis-fingerprint basis-fingerprint
                :world world
                :name "unknown-owner"
                :started-at "2026-08-24T00:00:00Z"})
        claim (atom nil)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/metadata-file world) (pr-str stale))
      (.bind server (UnixDomainSocketAddress/of (:socket-path stale)))
      (metadata/claim-pre-publication-artifacts! world claim)
      (is (= stale (metadata/read-metadata world)))
      (is (.exists (metadata/socket-file world)))
      (finally
        (metadata/release-pre-publication-artifacts! world claim)
        (.close server)
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest final-runtime-publication-requires-admitted-ownership
  (let [world (temp-world)
        published (promise)
        release (promise)
        candidate
        (future
          (try
            (binding [weaver-runtime/*after-metadata-publish!*
                      (fn [_]
                        (deliver published true)
                        @release)]
              (start-runtime! nil {:world world}))
            (catch Throwable t
              t)))]
    (try
      (is (true? (deref published (test-support/await-budget-ms) false))
          "candidate startup reaches final publication")
      (let [admitted @weaver-runtime/current-runtime]
        (is admitted "candidate generation is ambiently admitted")
        (weaver-runtime/stop! admitted)
        (let [replacement (start-runtime! nil {:world world})]
          (try
            (deliver release true)
            (let [failure (deref candidate (test-support/await-budget-ms) ::timed-out)]
              (is (not= ::timed-out failure) "candidate startup returns after release")
              (is (instance? clojure.lang.ExceptionInfo failure))
              (is (= :ambient-runtime-ownership-lost (:reason (ex-data failure))))
              (is (= (:generation-id replacement)
                     (:generation-id @weaver-runtime/current-runtime)))
              (is (= (:nonce (:metadata replacement))
                     (:nonce (metadata/read-metadata world))))
              (is (.exists (metadata/socket-file world)))
              (is (true? (get (socket-request replacement "status" {}) "ok"))))
            (finally
              (weaver-runtime/stop! replacement)))))
      (finally
        (deliver release true)
        (when-not (future-done? candidate)
          (future-cancel candidate))
        (try
          (deref candidate (test-support/await-budget-ms) ::timed-out)
          (catch java.util.concurrent.CancellationException _))
        (when-let [runtime @weaver-runtime/current-runtime]
          (weaver-runtime/stop! runtime))
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest overlapping-unpublished-start-rejects-after-the-first-publishes
  (let [world (temp-world)
        published (promise)
        release (promise)
        first (future
                (binding [weaver-runtime/*after-metadata-publish!*
                          (fn [_]
                            (deliver published true)
                            @release)]
                  (start-runtime! nil {:world world :publish? false})))]
    (try
      (is (true? (deref published (test-support/await-budget-ms) false))
          "first startup publishes before the overlapping preflight")
      (let [failure (try
                      (start-runtime! nil {:world world :publish? false})
                      (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :metadata-present (:reason (ex-data failure))))
        (is (= "Weaver metadata already exists for weaver world"
               (ex-message failure)))
        (is (= (:config-dir world) (:config-dir (ex-data failure)))))
      (deliver release true)
      (let [runtime (deref first (test-support/await-budget-ms) ::timed-out)]
        (is (not= ::timed-out runtime) "first startup completes after release")
        (is (true? (get (socket-request runtime "status" {}) "ok")))
        (weaver-runtime/stop! runtime))
      (finally
        (deliver release true)
        (when-not (future-done? first)
          (future-cancel first))
        (try
          (deref first (test-support/await-budget-ms) ::timed-out)
          (catch java.util.concurrent.CancellationException _))
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest pre-publication-claim-rejects-deterministic-overlap
  (let [world (temp-world)
        first-claim (atom nil)
        second-claim (atom nil)]
    (try
      (metadata/claim-pre-publication-artifacts! world first-claim)
      (let [failure (try
                      (metadata/claim-pre-publication-artifacts!
                       world second-claim)
                      (catch Throwable t t))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :pre-publication-claim-held (:reason (ex-data failure))))
        (is (= (:state-dir world) (:state-dir (ex-data failure))))
        (is (nil? @second-claim)))
      (finally
        (metadata/release-pre-publication-artifacts! world first-claim)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest pre-publication-claim-canonicalizes-state-dir-aliases
  (let [world (temp-world)
        state-dir (io/file (:state-dir world))
        root (.getParentFile state-dir)
        alias-world (assoc world :state-dir (.getPath (io/file root "alias" ".." "state")))
        symlink-file (io/file root "state-link")
        symlink-world (assoc world :state-dir (.getPath symlink-file))
        first-claim (atom nil)]
    (try
      (.mkdirs state-dir)
      (java.nio.file.Files/createSymbolicLink (.toPath symlink-file)
                                              (.toPath state-dir)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (metadata/claim-pre-publication-artifacts! world first-claim)
      (doseq [alias [alias-world symlink-world]]
        (let [failure (try
                        (metadata/claim-pre-publication-artifacts!
                         alias (atom nil))
                        (catch Throwable t t))]
          (is (instance? clojure.lang.ExceptionInfo failure))
          (is (= :pre-publication-claim-held (:reason (ex-data failure))))
          (is (= (:state-dir alias) (:state-dir (ex-data failure))))))
      (finally
        (metadata/release-pre-publication-artifacts! world first-claim)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest startup-failure-cleans-only-its-published-metadata
  (let [world (temp-world)]
    (try
      (binding [weaver-runtime/*after-metadata-publish!*
                (fn [_]
                  (throw (ex-info "fail after metadata publication" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"fail after metadata publication"
                              (start-runtime! nil {:world world :publish? false}))))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (let [replacement (start-runtime! nil {:world world :publish? false})]
        (try
          (is (true? (get (socket-request replacement "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! replacement))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest partial-metadata-publication-cleans-its-edn-artifact
  (let [world (temp-world)]
    (try
      (with-redefs [metadata/publish!
                    (fn [meta]
                      (.mkdirs (io/file (:state-dir meta)))
                      (spit (metadata/metadata-file meta) (pr-str meta))
                      (throw (ex-info "json metadata publication failed" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"json metadata publication failed"
                              (start-runtime! nil {:world world :publish? false}))))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (let [replacement (start-runtime! nil {:world world :publish? false})]
        (try
          (is (true? (get (socket-request replacement "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! replacement))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest pre-publication-rearm-failure-removes-its-socket-and-allows-restart
  (let [world (temp-world)]
    (try
      (with-redefs [scheduler/rearm! (fn [_]
                                       (throw (ex-info "rearm failed before publication" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"rearm failed before publication"
                              (start-runtime! nil {:world world :publish? false}))))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/json-metadata-file world))))
      (is (false? (.exists (metadata/socket-file world))))
      (let [replacement (start-runtime! nil {:world world :publish? false})]
        (try
          (is (true? (get (socket-request replacement "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! replacement))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest pre-publication-failure-retains-primary-error-when-storage-close-fails
  (let [world (temp-world)
        close-storage (ns-resolve 'millstrand.core.weaver.runtime 'close-storage!)]
    (try
      (let [failure
            (with-redefs-fn
              {#'scheduler/rearm! (fn [_]
                                    (throw (ex-info "rearm primary failure" {})))
               close-storage (fn [_]
                               (throw (ex-info "storage close failure" {})))}
              #(try
                 (start-runtime! nil {:world world :publish? false})
                 (catch Throwable t t)))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= "rearm primary failure" (ex-message failure)))
        (is (some #(= :storage/close (:teardown/step (ex-data %)))
                  (.getSuppressed ^Throwable failure))))
      (is (nil? (metadata/read-metadata world)))
      (is (false? (.exists (metadata/socket-file world))))
      (let [replacement (start-runtime! nil {:world world :publish? false})]
        (try
          (is (true? (get (socket-request replacement "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! replacement))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest pre-publication-cleanup-failure-is-suppressed-and-releases-its-claim
  (let [world (temp-world)]
    (try
      (let [failure
            (with-redefs [scheduler/rearm! (fn [_]
                                             (throw (ex-info "rearm primary failure" {})))
                          metadata/delete! (fn [_]
                                             (throw (ex-info "artifact delete failure" {})))]
              (try
                (start-runtime! nil {:world world :publish? false})
                (catch Throwable t t)))]
        (is (= "rearm primary failure" (ex-message failure)))
        (is (some #(and (= :artifacts/delete (:teardown/step (ex-data %)))
                        (= "artifact delete failure" (some-> % ex-cause ex-message)))
                  (.getSuppressed ^Throwable failure))))
      (metadata/delete! world)
      (let [runtime (start-runtime! nil {:world world :publish? false})]
        (try
          (is (true? (get (socket-request runtime "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! runtime))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-artifact-cleanup-preserves-successor-metadata
  (let [world (temp-world)
        stale {:nonce "stale"}
        successor {:nonce "successor"}]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/metadata-file world) (pr-str successor))
      (spit (metadata/json-metadata-file world) "successor")
      (spit (metadata/socket-file world) "successor")
      (is (nil? (metadata/delete-owned! stale world)))
      (is (= successor (metadata/read-metadata world)))
      (is (.exists (metadata/json-metadata-file world)))
      (is (.exists (metadata/socket-file world)))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest rollback-uses-canonical-state-dir-for-aliases
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "millstrand-rollback-alias-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        real-state (io/file root "real-state")
        alias-state (io/file root "alias-state")
        world {:config-dir (.getPath (io/file root "config"))
               :state-dir (.getPath real-state)
               :data-dir (.getPath (io/file root "data"))}
        alias-world (assoc world :state-dir (.getPath alias-state))
        claim (atom nil)]
    (try
      (.mkdirs real-state)
      (java.nio.file.Files/createSymbolicLink (.toPath alias-state)
                                              (.toPath real-state)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (metadata/claim-pre-publication-artifacts! world claim)
      (spit (metadata/socket-file alias-world) "partial")
      (is (true? (metadata/rollback-pre-publication-artifacts!
                  {} alias-world claim)))
      (is (false? (.exists (metadata/socket-file world))))
      (finally
        (metadata/release-pre-publication-artifacts! world claim)
        (delete-tree! root)))))

(deftest rollback-without-claim-preserves-an-unpublished-successor
  (let [world (temp-world)
        original-claim (atom nil)
        successor-claim (atom nil)]
    (try
      (metadata/claim-pre-publication-artifacts! world original-claim)
      (metadata/release-pre-publication-artifacts! world original-claim)
      (metadata/claim-pre-publication-artifacts! world successor-claim)
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/socket-file world) "successor")
      (is (nil? (metadata/rollback-pre-publication-artifacts!
                 {:nonce "original"} world original-claim)))
      (is (nil? (metadata/read-metadata world)))
      (is (.exists (metadata/socket-file world)))
      (finally
        (metadata/rollback-pre-publication-artifacts!
         {:nonce "successor"} world successor-claim)
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest rollback-with-nil-token-cannot-delete-an-unclaimed-socket
  (let [world (temp-world)
        claim (atom nil)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/socket-file world) "unclaimed successor")
      (is (nil? (metadata/rollback-pre-publication-artifacts!
                 {:nonce "original"} world claim)))
      (is (.exists (metadata/socket-file world)))
      (finally
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-stop-does-not-delete-artifacts-during-a-new-claim
  (let [world (temp-world)
        original {:nonce "original"}
        successor-claim (atom nil)]
    (try
      (.mkdirs (io/file (:state-dir world)))
      (spit (metadata/metadata-file world) (pr-str original))
      (spit (metadata/json-metadata-file world) "original")
      (spit (metadata/socket-file world) "successor socket")
      (metadata/claim-pre-publication-artifacts! world successor-claim)
      (is (= {:status :blocked
              :reason :blocked-by-successor-claim
              :state-dir (:state-dir world)}
             (metadata/delete-owned! original world)))
      (is (= original (metadata/read-metadata world)))
      (is (.exists (metadata/json-metadata-file world)))
      (is (.exists (metadata/socket-file world)))
      (finally
        (metadata/rollback-pre-publication-artifacts!
         {:nonce "successor"} world successor-claim)
        (metadata/delete! world)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest artifact-delete-attempts-later-files-after-an-earlier-failure
  (let [world (temp-world)
        edn-file (metadata/metadata-file world)]
    (try
      (.mkdirs edn-file)
      (spit (io/file edn-file "retained") "retained")
      (spit (metadata/json-metadata-file world) "metadata")
      (spit (metadata/socket-file world) "socket")
      (let [failure (try
                      (metadata/delete! world)
                      (catch Throwable t t))]
        (is (instance? java.nio.file.DirectoryNotEmptyException failure))
        (is (false? (.exists (metadata/json-metadata-file world))))
        (is (false? (.exists (metadata/socket-file world)))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-stop-preserves-a-same-world-replacement
  (let [world (temp-world)
        original (start-runtime! nil {:world world})]
    (try
      (weaver-runtime/stop! original)
      (let [replacement (start-runtime! nil {:world world})]
        (try
          (weaver-runtime/stop! original)
          (is (= (:generation-id replacement)
                 (:generation-id @weaver-runtime/current-runtime)))
          (is (= (:nonce (:metadata replacement))
                 (:nonce (metadata/read-metadata world))))
          (is (= (:nonce (:metadata replacement))
                 (get (json/read-str (slurp (metadata/json-metadata-file world)))
                      "weaver_id")))
          (is (.exists (metadata/socket-file world)))
          (is (true? (get (socket-request replacement "status" {}) "ok")))
          (finally
            (weaver-runtime/stop! replacement))))
      (finally
        (when-let [runtime @weaver-runtime/current-runtime]
          (weaver-runtime/stop! runtime))
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest stale-nrepl-port-removal-preserves-a-reused-port-registration
  (let [remove-runtime (ns-resolve 'millstrand.core.weaver.runtime
                                   'dissoc-generation-nrepl-runtime)
        port 43123
        original {:generation-id "original"}
        replacement {:generation-id "replacement"}]
    (is (= {port replacement}
           (remove-runtime {port replacement} port original)))
    (is (= {}
           (remove-runtime {port original} port original)))))

(deftest unpublished-runtimes-coexist-with-isolated-storage-and-registries
  (let [world-a (temp-world)
        world-b (temp-world)
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (start-runtime! db-a {:world world-a :publish? false})
        rt-b (start-runtime! db-b {:world world-b :publish? false})]
    (try
      (weaver/init rt-a)
      (weaver/init rt-b)
      (graph/register-query! rt-a 'mine [:= [:attr :owner] "a"])
      (graph/register-query! rt-b 'mine [:= [:attr :owner] "b"])
      (let [a (weaver/add! rt-a {:title "A" :attributes {:owner "a"}})
            b (weaver/add! rt-b {:title "B" :attributes {:owner "b"}})]
        (is (= [(:id a)] (mapv :id (weaver/list-query rt-a 'mine {}))))
        (is (= [(:id b)] (mapv :id (weaver/list-query rt-b 'mine {}))))
        (is (nil? (weaver/show rt-a (:id b))))
        (is (nil? (weaver/show rt-b (:id a)))))
      (finally
        (weaver-runtime/stop! rt-a)
        (weaver-runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)
        (delete-tree! (io/file (:config-dir world-a) ".."))
        (delete-tree! (io/file (:config-dir world-b) ".."))))))

(deftest unpublished-startup-config-resolves-current-runtime
  (let [world (temp-world)
        marker (io/file (:config-dir world) "startup-runtime.edn")]
    (source-file/spit-forms!
     (io/file (:config-dir world) "init.clj")
     ['(require '[millstrand.api.current.alpha :as current])
      `(spit ~(str marker) (pr-str (get-in (current/runtime) [:metadata :nonce])))])
    (let [rt (start-runtime! nil {:world world :publish? false})]
      (try
        (is (= (get-in rt [:metadata :nonce]) (read-string (slurp marker))))
        (finally
          (weaver-runtime/stop! rt)
          (delete-tree! (io/file (:config-dir world) "..")))))))

(deftest startup-fails-clearly-when-required-main-dirs-are-missing
  (let [parse-main-args (ns-resolve 'millstrand.core.weaver.runtime 'parse-main-args)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --workspace"
                          (parse-main-args [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --state-dir"
                          (parse-main-args ["--workspace" "/tmp/c" "--data-dir" "/tmp/d"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --data-dir"
                          (parse-main-args ["--workspace" "/tmp/c" "--state-dir" "/tmp/s"])))))

(deftest startup-rejects-unknown-flags
  (let [parse-main-args (ns-resolve 'millstrand.core.weaver.runtime 'parse-main-args)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unknown flag"
         (parse-main-args ["--workspace" "/tmp/c"
                           "--state-dir" "/tmp/s"
                           "--data-dir" "/tmp/d"
                           "--unknown-option" "value"])))))

(deftest startup-failing-init-aborts-before-ready-metadata
  (let [world (temp-world)]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       ['(throw (ex-info "init boom" {:source :shared}))])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed"
                            (start-runtime! nil {:world world :publish? false})))
      (is (nil? (metadata/read-metadata world)))
      (is (not (.exists (io/file (:state-dir world) "weaver.json"))))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(defn- live-thread-names [prefix]
  (->> (Thread/getAllStackTraces)
       keys
       (filter #(.isAlive ^Thread %))
       (map #(.getName ^Thread %))
       (filter #(str/starts-with? % prefix))
       sort
       vec))

(defn- throwable-messages [t]
  (loop [messages []
         t t]
    (if t
      (recur (conj messages (ex-message t)) (ex-cause t))
      messages)))

(deftest startup-failure-closes-spool-state-before-storage
  (let [world (temp-world)
        thread-prefix (str "millstrand-test-startup-spool-" (random-uuid))]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       ['(require '[millstrand.api.runtime.alpha]
                  '[millstrand.core.weaver.runtime :as weaver-runtime])
        (list 'let ['thread-prefix thread-prefix]
              '(let [stop-worker (promise)
                     worker (doto (Thread. (reify Runnable
                                             (run [_] @stop-worker))
                                           (str thread-prefix "-worker"))
                              (.setDaemon true))
                     rt weaver-runtime/*runtime*]
                 (.start worker)
                 (millstrand.api.runtime.alpha/spool-state rt :test/executor
                                                           (fn [] {:close-fn (fn []
                                                                               (deliver stop-worker true)
                                                                               (.join worker))}))
                 (millstrand.api.runtime.alpha/spool-state rt :test/bad-close
                                                           (fn [] {:close-fn (fn []
                                                                               (throw (ex-info "close boom" {:source :spool-close})))}))
                 (throw (ex-info "post spool boom" {:source :startup}))))])
      (let [failure (try
                      (start-runtime! nil {:world world :publish? false})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is failure "expected startup failure")
        (is (= "Selected workspace startup file failed to load" (ex-message failure)))
        (is (some #(= "post spool boom" %) (throwable-messages failure)))
        (is (some #(and (= :spool-state/close (:teardown/step (ex-data %)))
                        (= "Spool state close hook failed"
                           (some-> % ex-cause ex-message)))
                  (.getSuppressed failure))))
      (is (empty? (live-thread-names thread-prefix)))
      (is (nil? (metadata/read-metadata world)))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest startup-loads-layered-init-files-in-order
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        order-file (io/file (:config-dir world) "startup-order.edn")]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       [`(spit ~(str order-file) (pr-str [:shared]))
        :shared])
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.local.clj")
       [`(spit ~(str order-file)
               (pr-str (conj (read-string (slurp ~(str order-file))) :local)))
        :local])
      (let [rt (start-runtime! db-file {:world world :publish? false})]
        (try
          (is (= [:shared :local] (read-string (slurp order-file))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-skips-missing-local-init-file
  (let [db-file (db-test/temp-db-file)
        world (temp-world)
        marker (io/file (:config-dir world) "shared.edn")]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       [`(spit ~(str marker) (pr-str :shared))])
      (let [rt (start-runtime! db-file {:world world :publish? false})]
        (try
          (is (= :shared (read-string (slurp marker))))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))

(deftest startup-fails-loudly-when-local-init-file-fails
  (let [db-file (db-test/temp-db-file)
        world (temp-world)]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.local.clj")
       ['(throw (ex-info "local boom" {:source :local}))])
      (try
        (start-runtime! db-file {:world world :publish? false})
        (is false "expected startup failure")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Selected workspace startup file failed to load" (ex-message e)))
          (is (= (.getCanonicalPath (io/file (:config-dir world) "init.local.clj"))
                 (:file (ex-data e))))
          (is (nil? (metadata/read-metadata world)))))
      (finally
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))
(deftest runtime-uses-world-default-database-and-directories
  (let [world (temp-world)
        rt (start-runtime! nil {:world world :publish? false})]
    (try
      (is (= (.getPath (.getCanonicalFile (io/file (:db-path world))))
             (get-in rt [:metadata :canonical-db-path])))
      (is (.isDirectory (io/file (:state-dir world))))
      (is (.isDirectory (io/file (:data-dir world))))
      (is (= (str (:state-dir world) "/weaver.sock") (get-in rt [:metadata :socket-path])))
      (is (= (str (:state-dir world) "/weaver.edn") (.getPath (metadata/metadata-file world))))
      (is (= (str (:state-dir world) "/weaver.json") (.getPath (metadata/json-metadata-file world))))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world)))))))
(deftest runtime-loads-default-init-clj
  (let [world (temp-world)
        init (io/file (:config-dir world) "init.clj")]
    (try
      (source-file/spit-forms!
       init
       ['(require '[millstrand.api.current.alpha :as current]
                  '[millstrand.api.graph.alpha :as graph])
        '(graph/register-query! (current/runtime) 'trusted [:= :state "active"])])
      (let [rt (start-runtime! nil {:world world :publish? false})]
        (try
          (is (= {"trusted" [:= :state "active"]} (graph/queries rt)))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! (io/file (:config-dir world)))))))
(deftest runtime-nrepl-load-file-uses-generation-classloader
  (let [world (temp-world)
        suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
        ns-sym (symbol (str "demo.load-file-" suffix))
        source-root (io/file (:config-dir world) "load-file-src")
        source-file (io/file source-root
                             (str (-> (str ns-sym)
                                      (str/replace \- \_)
                                      (str/replace \. java.io.File/separatorChar))
                                  ".clj"))]
    (.mkdirs (.getParentFile source-file))
    (source-file/spit-forms! source-file
                             [(list 'ns ns-sym) '(def visible :through-generation-loader)])
    (with-runtime
      {:world world
       :generation-basis (generation-basis world [source-root])}
      (fn [rt _]
        (let [{:keys [host port]} (get-in rt [:metadata :endpoint])]
          (with-open [conn (nrepl/connect :host host :port port)]
            (let [session (nrepl/client-session
                           (nrepl/client conn (test-support/await-budget-ms)))
                  responses (doall
                             (nrepl/message
                              session
                              {:op "load-file"
                               :eval "clojure.core/eval"
                               :file (source-file/render-forms
                                      [`(require '~ns-sym)
                                       `(deref (resolve '~(symbol (str ns-sym) "visible")))])}))]
              (is (= ":through-generation-loader"
                     (last (keep :value responses)))))))))))
(deftest runtime-metadata-rejects-blank-friendly-name
  (let [world (temp-world)
        db-file (db-test/temp-db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Weaver name must not be blank"
                            (metadata/metadata-shape {:pid 1
                                                      :version "0.5.1"
                                                      :host "127.0.0.1"
                                                      :port 5555
                                                      :canonical-db-path (metadata/canonical-db-path db-file)
                                                      :nonce "weaver"
                                                      :basis-fingerprint basis-fingerprint
                                                      :world world
                                                      :name "  "
                                                      :started-at "now"})))
      (is (true? (metadata/stale-or-missing?
                  {:pid 1
                   :transport :nrepl
                   :protocol-version 3
                   :endpoint {:host "127.0.0.1" :port 5555}
                   :config-dir (:config-dir world)
                   :state-dir (:state-dir world)
                   :data-dir (:data-dir world)
                   :canonical-db-path (metadata/canonical-db-path db-file)
                   :nonce "weaver"
                   :basis-fingerprint basis-fingerprint
                   :socket-path (str (:state-dir world) "/weaver.sock")
                   :started-at "now"
                   :name "  "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"--name requires a non-blank value"
                            ((ns-resolve 'millstrand.core.weaver.runtime 'parse-main-args)
                             ["--workspace" (:config-dir world)
                              "--state-dir" (:state-dir world)
                              "--data-dir" (:data-dir world)
                              "--name" "  "])))
      (finally
        (metadata/delete! world)
        (db-test/delete-sqlite-family! db-file)
        (delete-tree! (io/file (:config-dir world)))))))
(deftest runtime-metadata-records-canonical-loopback-identity
  (with-runtime
    (fn [rt db-file]
      (let [canonical (metadata/canonical-db-path db-file)
            status (weaver-runtime/status rt)
            file (:metadata-file rt)
            from-disk (edn/read-string (slurp file))
            json-disk (json/read-str (slurp (metadata/json-metadata-file (:metadata rt))))]
        (is (= canonical (:canonical-db-path status)))
        (is (= :sqlite-file (:storage-kind status)))
        (is (= canonical (:storage-label status)))
        (is (= status from-disk))
        (is (= file (metadata/metadata-file (:metadata rt))))
        (is (pos-int? (get-in status [:endpoint :port])))
        (is (string? (:nonce status)))
        (is (= (.getName (io/file (:config-dir status))) (:name status)))
        (is (= (:name status) (get json-disk "name")))
        (is (= (str/trim (slurp "VERSION"))
               (:version status)
               (get json-disk "version")))
        (is (= :nrepl (:transport status)))
        (is (= 3 (:protocol-version status)))
        (is (string? (:socket-path status)))
        (is (= canonical (get json-disk "database_path")))
        (is (= "sqlite-file" (get json-disk "database_kind")))
        (is (= canonical (get json-disk "database_label")))
        (is (= (:nonce status) (get json-disk "weaver_id")))
        (is (= (:socket-path status) (get json-disk "socket_path")))
        (is (= "127.0.0.1" (get-in json-disk ["nrepl" "host"])))
        (is (false? (metadata/stale-or-missing? status)))
        (is (false? (metadata/stale-or-missing? (update status :pid long))))
        (is (= "127.0.0.1" (get-in status [:endpoint :host])))
        (is (.isLoopbackAddress (.getInetAddress (:server-socket (:server rt)))))))))
