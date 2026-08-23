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
            [millstrand.core.weaver.config :as weaver-config]
            [millstrand.core.weaver.metadata :as metadata]
            [millstrand.core.weaver.runtime :as weaver-runtime]
            [millstrand.core.db :as db]
            [millstrand.core.db-test :as db-test]
            [millstrand.source-file :as source-file]
            [millstrand.spools.test-support :as test-support])

  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(def delete-tree! test-support/delete-tree!)

(defn temp-world []
  (let [root (java.io.File/createTempFile "tdx" "")]
    (.delete root)
    (.mkdirs root)
    (let [workspace (io/file root "config")
          state-dir (io/file root "state")
          data-dir (io/file root "data")]
      (.mkdirs workspace)
      (weaver-config/world (.getCanonicalPath workspace)
                           (.getCanonicalPath state-dir)
                           (.getCanonicalPath data-dir)))))

(defn with-runtime
  ([f] (with-runtime nil f))
  ([start-options f]
   (let [db-file (db-test/temp-db-file)
         world (or (:world start-options) (temp-world))
         rt (weaver-runtime/start! db-file (assoc (or start-options {}) :world world :publish? false))]
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
        rt (weaver-runtime/start! nil {:world world :publish? false})]
    (try
      (let [metadata (:metadata rt)]
        (is (= (:config-dir world) (:config-dir metadata)))
        (is (= (:state-dir world) (:state-dir metadata)))
        (is (= (:data-dir world) (:data-dir metadata)))
        (is (= (:db-path world) (:canonical-db-path metadata)))
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
        rt (weaver-runtime/start! nil {:world world :publish? false})]
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
        rt (weaver-runtime/start! nil {:world world :publish? false :storage :sqlite-memory})]
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
          (is (contains? json-disk "database_path"))
          (is (nil? (get json-disk "database_path"))))
        (let [status (socket-request rt "status" {})]
          (is (true? (get status "ok")))
          (is (= "sqlite-memory" (get-in status ["result" "database_kind"])))
          (is (nil? (get-in status ["result" "database_path"])))))
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
                            (weaver-runtime/start! (db-test/temp-db-file)
                                                   {:world world :publish? false :storage :sqlite-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown weaver storage kind"
                            (weaver-runtime/start! nil {:world world :publish? false :storage :postgres})))
      (finally
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest fresh-runtime-probe-is-unpublished-and-cleans-success
  (let [world (temp-world)
        result (try
                 (weaver-runtime/fresh-runtime-probe! world)
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

(deftest direct-probe-start-is-unpublished
  (let [world (temp-world)
        rt (weaver-runtime/start! nil {:world world
                                       :probe? true
                                       :storage :sqlite-memory})]
    (try
      (is (nil? @weaver-runtime/current-runtime))
      (is (nil? (metadata/read-metadata world)))
      (finally
        (weaver-runtime/stop! rt)
        (delete-tree! (io/file (:config-dir world) ".."))))))

(deftest unpublished-runtimes-coexist-with-isolated-storage-and-registries
  (let [world-a (temp-world)
        world-b (temp-world)
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (weaver-runtime/start! db-a {:world world-a :publish? false})
        rt-b (weaver-runtime/start! db-b {:world world-b :publish? false})]
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
    (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
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

(deftest startup-release-marker-uses-declared-payload-resolution
  (let [parse-main-args (ns-resolve 'millstrand.core.weaver.runtime 'parse-main-args)
        required ["--workspace" "/tmp/c"
                  "--state-dir" "/tmp/s"
                  "--data-dir" "/tmp/d"]]
    (is (= "v8"
           (:release-marker
            (parse-main-args (conj required "--release-marker" ":stdin")
                             {"stdin" "v8"}))))
    (is (= "v9"
           (:release-marker
            (parse-main-args (conj required "--release-marker" ":payload/marker")
                             {"marker" "v9"}))))))

(deftest startup-failing-init-aborts-before-ready-metadata
  (let [world (temp-world)]
    (try
      (source-file/spit-forms!
       (io/file (:config-dir world) "init.clj")
       ['(throw (ex-info "init boom" {:source :shared}))])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"startup file failed"
                            (weaver-runtime/start! nil {:world world :publish? false})))
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
                      (weaver-runtime/start! nil {:world world :publish? false})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is failure "expected startup failure")
        (is (= "Selected workspace startup file failed to load" (ex-message failure)))
        (is (some #(= "post spool boom" %) (throwable-messages failure)))
        (is (some #(= "Spool state close hook failed" (ex-message %))
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
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
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
      (let [rt (weaver-runtime/start! db-file {:world world :publish? false})]
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
        (weaver-runtime/start! db-file {:world world :publish? false})
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
        rt (weaver-runtime/start! nil {:world world :publish? false})]
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
      (let [rt (weaver-runtime/start! nil {:world world :publish? false})]
        (try
          (is (= {"trusted" [:= :state "active"]} (graph/queries rt)))
          (finally
            (weaver-runtime/stop! rt))))
      (finally
        (delete-tree! (io/file (:config-dir world)))))))
(deftest runtime-nrepl-load-file-uses-spool-classloader
  (with-runtime
    (fn [rt _]
      (let [suffix (str/replace (str (java.util.UUID/randomUUID)) "-" "")
            ns-sym (symbol (str "demo.load-file-" suffix))
            source-root (io/file (get-in rt [:metadata :config-dir]) "load-file-src")
            source-file (io/file source-root
                                 (str (-> (str ns-sym)
                                          (str/replace \- \_)
                                          (str/replace \. java.io.File/separatorChar))
                                      ".clj"))
            {:keys [host port]} (get-in rt [:metadata :endpoint])]
        (.mkdirs (.getParentFile source-file))
        (source-file/spit-forms! source-file [(list 'ns ns-sym) '(def visible :through-spool-loader)])
        (.addURL ^clojure.lang.DynamicClassLoader (:spool-classloader rt)
                 (.toURL (.toURI source-root)))
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
            (is (= ":through-spool-loader" (last (keep :value responses))))))))))
(deftest runtime-metadata-rejects-blank-friendly-name
  (let [world (temp-world)
        db-file (db-test/temp-db-file)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Weaver name must not be blank"
                            (metadata/metadata-shape {:pid 1
                                                      :host "127.0.0.1"
                                                      :port 5555
                                                      :canonical-db-path (metadata/canonical-db-path db-file)
                                                      :nonce "weaver"
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
