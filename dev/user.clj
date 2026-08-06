(ns user
  "Dev-REPL scratchpad: a disposable demo weaver and a small seeded graph.

  It holds its own runtime and calls the explicit-runtime `skein.api.*.alpha`
  verbs — the ordinary shape for code that owns a weaver, and a worked example
  of the user-owned terse helpers `demo-strand!` and `demo-strands` below."
  (:require [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]))

(defonce ^:private demo-runtime (atom nil))
(defonce ^:private demo-world (atom nil))

(defn- checkout-root []
  (.getAbsolutePath (java.io.File. ".")))

(defn- new-demo-world! []
  (let [config-dir (.toFile (java.nio.file.Files/createTempDirectory "skein-demo-" (make-array java.nio.file.attribute.FileAttribute 0)))
        world {:config-dir (.getCanonicalPath config-dir)
               :state-dir (.getCanonicalPath (java.io.File. config-dir "state"))
               :data-dir (.getCanonicalPath (java.io.File. config-dir "data"))
               :db-path (.getCanonicalPath (java.io.File. config-dir "data/millstrand.sqlite"))}]
    (spit (java.io.File. config-dir "config.json")
          (format "{\"configFormat\":\"alpha\",\"source\":%s}\n" (pr-str (checkout-root))))
    world))

(defn start-demo-weaver!
  "Start a demo weaver in an explicit disposable config-dir world."
  []
  (when @demo-runtime
    (throw (ex-info "Demo weaver is already started from this REPL" {:world @demo-world})))
  (let [world (new-demo-world!)]
    (reset! demo-world world)
    (reset! demo-runtime (weaver-runtime/start! nil {:world world}))
    {:config-dir (:config-dir world)
     :status :weaver-started}))

(defn stop-demo-weaver!
  "Stop the demo weaver started by start-demo-weaver!."
  []
  (let [rt (or @demo-runtime
               (throw (ex-info "No demo weaver was started from this REPL" {:world @demo-world})))]
    (weaver-runtime/stop! rt)
    (let [world @demo-world]
      (reset! demo-runtime nil)
      (reset! demo-world nil)
      {:config-dir (:config-dir world)
       :status :weaver-stopped})))

(defn demo-runtime!
  "Return the demo weaver's runtime, failing loudly before it is started.

  Every helper below threads this explicitly; nothing here reads an ambient
  runtime, so a demo session cannot reach into another world by accident."
  []
  (or @demo-runtime
      (throw (ex-info "Start the demo weaver first" {}))))

(defn demo!
  "Initialize the demo weaver's storage."
  []
  (weaver/init (demo-runtime!))
  {:config-dir (:config-dir @demo-world)
   :status :ready})

(defn demo-strand!
  "Create a strand in the demo world with `title` and optional `attributes`."
  ([title] (demo-strand! title {} {}))
  ([title attributes] (demo-strand! title attributes {}))
  ([title attributes lifecycle]
   (weaver/add! (demo-runtime!) (merge {:title title :attributes attributes} lifecycle))))

(defn demo-strands
  "Return every strand in the demo world."
  []
  (weaver/list (demo-runtime!)))

(defn seed-demo!
  "Initialize the demo world and add a small dependency graph."
  []
  (demo!)
  (let [rt (demo-runtime!)
        design (demo-strand! "Sketch model" {:priority "high" :demo-id "design"} {:state "closed"})
        docs (demo-strand! "Write docs" {:owner "agent" :demo-id "docs"})
        impl (demo-strand! "Build feature" {:owner "agent" :demo-id "impl"})]
    (weaver/update! rt (:id docs) {:edges [{:type "depends-on" :to (:id design)}]})
    (weaver/update! rt (:id impl) {:edges [{:type "depends-on" :to (:id docs)}]})
    (demo-strands)))

(comment
  (start-demo-weaver!)
  (seed-demo!)
  (weaver/ready (demo-runtime!))
  (def docs-id (:id (first (filter #(= "docs" (get-in % [:attributes :demo-id])) (demo-strands)))))
  (def replacement-docs-id (:id (demo-strand! "Rewrite docs" {:owner "agent" :demo-id "replacement-docs"})))
  (weaver/supersede! (demo-runtime!) docs-id replacement-docs-id)
  (weaver/list (demo-runtime!) [:edge/out "supersedes" [:= [:attr :demo-id] "docs"]])
  (weaver/update! (demo-runtime!) replacement-docs-id {:state "closed"})
  (weaver/ready (demo-runtime!))
  ;; The registration verbs, one tier down from skein.repl's runtime-implied twins:
  (graph/register-query! (demo-runtime!) 'mine [:= [:attr :owner] "agent"])
  (weaver/list-query (demo-runtime!) 'mine {})
  (graph/unregister-query! (demo-runtime!) 'mine)
  (stop-demo-weaver!))
