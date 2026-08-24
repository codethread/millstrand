(ns millstrand.peers-test
  "Tests for local weaver peer discovery."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [millstrand.api.peers.alpha :as peers]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.core.db-test :as db-test]
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.spools.test-support :as test-support]))

(defn peer-test-op [{:op/keys [name argv]}]
  {:name name :argv argv :from :peer-test})

(defn peer-stream-op
  "A streaming op used to prove call! rejects peer stream responses."
  [{emit! :op/emit!}]
  (emit! {"line" 1})
  {"done" true})

(defn- current-pid []
  (.pid (java.lang.ProcessHandle/current)))

(defn- metadata-map [state-dir workspace name pid]
  {:pid pid
   :transport :nrepl
   :protocol-version 3
   :endpoint {:host "127.0.0.1" :port 5555}
   :config-dir (.getPath workspace)
   :name name
   :state-dir (.getPath state-dir)
   :data-dir (.getPath (io/file workspace "data"))
   :storage-kind :sqlite-file
   :storage-label (.getPath (io/file workspace "data" "millstrand.sqlite"))
   :canonical-db-path (.getPath (io/file workspace "data" "millstrand.sqlite"))
   :nonce (str "nonce-" name "-" (System/nanoTime))
   :generation-id (str "generation-" name "-" (System/nanoTime))
   :socket-path (.getPath (metadata/socket-file {:state-dir (.getPath state-dir)}))
   :started-at "2026-07-02T00:00:00Z"})

(defn- write-peer! [state-root hash workspace name pid]
  (let [state-dir (io/file state-root "weavers" hash)]
    (.mkdirs state-dir)
    (spit (io/file state-dir "weaver.edn") (pr-str (metadata-map state-dir workspace name pid)))
    state-dir))

(defn- with-state-root [state-root f]
  (let [state-root-var (ns-resolve 'millstrand.api.peers.alpha 'state-root)
        original @state-root-var]
    (alter-var-root state-root-var (constantly (fn [] state-root)))
    (try
      (f)
      (finally
        (alter-var-root state-root-var (constantly original))))))

(deftest peers-empty-root-test
  (let [state-root (test-support/temp-dir "millstrand-peers-empty")]
    (with-state-root state-root
      #(is (= [] (peers/peers))))))

(deftest peers-list-running-test
  (let [state-root (test-support/temp-dir "millstrand-peers-running")
        workspace (test-support/temp-dir "millstrand-peer-workspace")
        state-dir (write-peer! state-root "a" workspace "alpha" (current-pid))
        socket-path (.getPath (metadata/socket-file {:state-dir (.getPath state-dir)}))]
    (with-state-root state-root
      #(let [rows (peers/peers)]
         (is (= 1 (count rows)))
         (is (= {:name "alpha"
                 :workspace (.getPath workspace)
                 :protocol-version 3
                 :socket-path socket-path
                 :state-dir (.getPath state-dir)
                 :running? true}
                (select-keys (first rows) [:name :workspace :protocol-version :socket-path :state-dir :running?])))
         (is (= "alpha" (:name (first rows))))))))

(deftest peers-stale-listed-but-not-resolvable-test
  (let [state-root (test-support/temp-dir "millstrand-peers-stale")
        workspace (test-support/temp-dir "millstrand-peer-stale-workspace")]
    (write-peer! state-root "stale" workspace "stale" 999999999)
    (with-state-root state-root
      #(do
         (is (false? (:running? (first (peers/peers)))))
         (try
           (peers/call! "stale" "status")
           (is false "expected stale peer resolution to throw")
           (catch clojure.lang.ExceptionInfo ex
             (is (= :peer/stale (:code (ex-data ex))))))))))

(deftest peers-unknown-name-not-found-test
  (let [state-root (test-support/temp-dir "millstrand-peers-none")]
    (with-state-root state-root
      #(try
         (peers/call! "nobody" "status")
         (is false "expected unknown peer resolution to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/not-found (:code (ex-data ex))))
           (is (= :name (:match-by (ex-data ex)))))))))

(deftest peers-workspace-path-resolution-test
  (let [state-root (test-support/temp-dir "millstrand-peers-bypath")
        workspace (test-support/temp-dir "millstrand-peer-bypath-workspace")]
    (write-peer! state-root "p" workspace "pathy" 999999999)
    (with-state-root state-root
      #(try
         (peers/call! (.getPath workspace) "status")
         (is false "expected stale path-resolved peer to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/stale (:code (ex-data ex))))
           (is (= :workspace (:match-by (ex-data ex)))))))))

(deftest peers-duplicate-name-ambiguity-test
  (let [state-root (test-support/temp-dir "millstrand-peers-ambiguous")
        workspace-a (test-support/temp-dir "millstrand-peer-a")
        workspace-b (test-support/temp-dir "millstrand-peer-b")]
    (write-peer! state-root "a" workspace-a "shared" (current-pid))
    (write-peer! state-root "b" workspace-b "shared" (current-pid))
    (with-state-root state-root
      #(try
         (peers/call! "shared" "status")
         (is false "expected ambiguous peer resolution to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/ambiguous (:code (ex-data ex))))
           (is (= #{(.getPath workspace-a) (.getPath workspace-b)}
                  (set (map :workspace (:candidates (ex-data ex)))))))))))

(deftest peers-malformed-metadata-fails-test
  (let [state-root (test-support/temp-dir "millstrand-peers-malformed")
        state-dir (io/file state-root "weavers" "bad")]
    (.mkdirs state-dir)
    (spit (io/file state-dir "weaver.edn") (pr-str {:pid (current-pid) :name "bad"}))
    (with-state-root state-root
      #(try
         (peers/peers)
         (is false "expected malformed metadata to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/malformed-metadata (:code (ex-data ex)))))))))

(defn- world-under [root hash name]
  (let [workspace (io/file root "workspaces" name)
        state-dir (io/file root "state" "millstrand" "weavers" hash)
        data-dir (io/file root "data" name)]
    (.mkdirs workspace)
    (.mkdirs state-dir)
    (.mkdirs data-dir)
    (weaver-config/world (.getCanonicalPath workspace)
                         (.getCanonicalPath state-dir)
                         (.getCanonicalPath data-dir))))

(defn- short-temp-root [prefix]
  (let [root (io/file "/tmp" (str prefix (System/nanoTime)))]
    (.mkdirs root)
    root))

(defn- with-two-runtimes [f]
  (let [root (short-temp-root "sg")
        state-root (io/file root "state" "millstrand")
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (weaver-runtime/start! db-a {:world (world-under root "a" "alpha") :name "alpha"})]
    ;; The runtime enforces one process-current runtime for REPL convenience;
    ;; peer socket tests need two independent local runtimes and do not rely on
    ;; current-runtime dispatch.
    (reset! weaver-runtime/current-runtime nil)
    (let [rt-b (weaver-runtime/start! db-b {:world (world-under root "b" "beta") :name "beta"})]
      (try
        (with-state-root state-root #(f rt-a rt-b))
        (finally
          (weaver-runtime/stop! rt-b)
          (weaver-runtime/stop! rt-a)
          (reset! weaver-runtime/current-runtime nil)
          (db-test/delete-sqlite-family! db-a)
          (db-test/delete-sqlite-family! db-b)
          (test-support/delete-tree! root))))))

(deftest call-peer-invoke-and-status-test
  (with-two-runtimes
    (fn [_rt-a rt-b]
      (weaver/register-op! rt-b 'echo {:hook-class :mutating
                                       :deadline-class :standard}
                           'millstrand.peers-test/peer-test-op)
      (let [beta (first (filter #(= "beta" (:name %)) (peers/peers)))
            echoed (peers/call! "beta" "echo" {:argv ["x" "y"]})
            via-symbol (peers/call! "beta" 'echo)
            status (peers/call! beta "status")
            listed (peers/call! beta "help")]
        (is (= {"name" "echo" "argv" ["x" "y"] "from" "peer-test"} echoed))
        (is (= {"name" "echo" "argv" [] "from" "peer-test"} via-symbol))
        (is (= (:weaver-id beta) (get status "weaver_id")))
        (is (true? (get status "healthy")))
        (is (some #(= "echo" (get-in % ["operation" "name"])) (get listed "ops")))))))

(deftest call-peer-rejects-invalid-op-type-before-connect-test
  (let [peer-row {:name "offline"
                  :workspace "/tmp/offline"
                  :weaver-id "missing"
                  :generation-id "missing-generation"
                  :protocol-version 3
                  :socket-path "/tmp/millstrand-peer-missing.sock"
                  :state-dir "/tmp"
                  :running? false}]
    (try
      (peers/call! peer-row 42 {})
      (is false "expected invalid operation type to throw")
      (catch clojure.lang.ExceptionInfo ex
        (is (= 42 (:operation (ex-data ex))))))
    (try
      (peers/call! peer-row :peer/stop {})
      (is false "expected namespaced operation to throw")
      (catch clojure.lang.ExceptionInfo ex
        (is (= :peer/stop (:operation (ex-data ex))))))))

(deftest call-peer-rejects-malformed-args-before-connect-test
  (let [peer-row {:name "offline"
                  :workspace "/tmp/offline"
                  :weaver-id "missing"
                  :generation-id "missing-generation"
                  :protocol-version 3
                  :socket-path "/tmp/millstrand-peer-missing.sock"
                  :state-dir "/tmp"
                  :running? false}]
    (doseq [[args key] [["not-a-map" :args]
                        [{:argv "x"} :argv]
                        [{:argv [1 2]} :argv]
                        [{:payloads []} :payloads]]]
      (try
        (peers/call! peer-row "echo" args)
        (is false (str "expected malformed args to throw for " (pr-str args)))
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/invalid-args (:code (ex-data ex))))
          (is (contains? (ex-data ex) key)))))))

(deftest call-peer-unknown-op-domain-error-is-structured-test
  (with-two-runtimes
    (fn [_rt-a _rt-b]
      (try
        (peers/call! "beta" "missing-op")
        (is false "expected peer domain error")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/domain-error (:code (ex-data ex))))
          (is (= "beta" (get-in (ex-data ex) [:peer :name])))
          (is (= "missing-op" (:operation (ex-data ex))))
          (is (= "domain" (get-in (ex-data ex) [:error "type"]))))))))

(deftest call-peer-stream-response-fails-loudly-test
  (with-two-runtimes
    (fn [_rt-a rt-b]
      (weaver/register-op! rt-b 'streamer {:stream? true
                                           :hook-class :mutating
                                           :deadline-class :unbounded}
                           'millstrand.peers-test/peer-stream-op)
      (try
        (peers/call! "beta" "streamer")
        (is false "expected stream response to fail loudly")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/stream-unsupported (:code (ex-data ex))))
          (is (= "streamer" (:operation (ex-data ex)))))))))

(deftest call-stopped-peer-fails-loudly-test
  (let [root (short-temp-root "sgstop")
        state-root (io/file root "state" "millstrand")
        db-b (db-test/temp-db-file)
        rt-b (weaver-runtime/start! db-b {:world (world-under root "b" "beta") :name "beta"})]
    (try
      (let [beta (with-state-root state-root #(first (filter (fn [row] (= "beta" (:name row)))
                                                             (peers/peers))))]
        (weaver-runtime/stop! rt-b)
        (try
          (peers/call! beta "status" {})
          (is false "expected stopped peer transport failure")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/transport-failed (:code (ex-data ex))))
            (is (= "beta" (get-in (ex-data ex) [:peer :name]))))))
      (finally
        (weaver-runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-b)
        (test-support/delete-tree! root)))))
