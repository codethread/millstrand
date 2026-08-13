(ns quality.conventions-check
  "Enforce repo-wide Clojure conventions that prose alone cannot hold.

  Eight checks, all held at zero findings:
  - every namespace carries a docstring;
  - no local binding is named after a clojure.core macro (a local named
    `fn` shadows the macro and turns later thunks into eager calls; rename
    on destructure instead: `{fn-sym :fn}`);
  - every literal `(require ...)` embedded in code-as-data — the quoted
    forms tests route through `millstrand.test.alpha/repl!` and init fixtures —
    names a namespace that resolves to a source file, so a namespace rename
    cannot silently strand a tested form until weaver-side eval;
  - converted `millstrand.api.*` modules keep the v1 form contract
    (SPEC-003.C19a); the check and its shrinking `pending` ratchet live
    in `quality.api-form`;
  - shipped spool sources use `millstrand.core.*` only from unsafe-named
    namespaces (SPEC-005.C5) — spools are userland code building on
    `millstrand.api.*.alpha`, and the designed exception is nominal; the
    unsafe-namespace convention's rules live in `quality.spool-tiers`;
  - a public `spool` var in a module-loadable namespace is a removed grammar;
    the structural guard lives in `quality.spool-var`;
  - no source hand-escapes JSON that `json/write-str` would reproduce
    from Clojure data; the narrowing rules live in
    `quality.json-literals`;
  - repository workspace tests use `millstrand.ct.*` exactly under
    `test/clojure/millstrand/ct/`, and a test that directly names a checked-in `.millstrand` path
    belongs there even before it adopts the namespace; the boundary lives in
    `quality.workspace-tests`;
  - tests under `test/clojure/millstrand/api` pin public contracts and do not reach core
    implementation seams or integration megasuites; the boundary lives in
    `quality.api-tests`."
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [quality.api-form :as api-form]
            [quality.api-tests :as api-tests]
            [quality.json-literals :as json-literals]
            [quality.source-forms :as source-forms]
            [quality.spool-tiers :as spool-tiers]
            [quality.spool-var :as spool-var]
            [quality.workspace-tests :as workspace-tests]))

(def ^:private local-source-roots
  ["src"
   "spools/batteries/src"
   "spools/unsafe-text-search/src"
   ".millstrand"
   "test"])

(defn- configured-source-roots
  "Return every lintable engine, spool, workspace-config, and test root."
  []
  local-source-roots)

(def ^:private core-macro-names
  (->> (ns-publics 'clojure.core)
       vals
       (filter #(:macro (meta %)))
       (map #(-> % symbol name))
       set))

(defn- quoted-libspec-ns
  "Return the namespace symbol named by a literal quoted require argument:
  `'ns.sym` or `'[ns.sym ...]`. Dynamically assembled arguments return nil."
  [arg]
  (when (and (seq? arg) (= 'quote (first arg)) (= 2 (count arg)))
    (let [libspec (second arg)]
      (cond
        (symbol? libspec) libspec
        (and (vector? libspec) (symbol? (first libspec))) (first libspec)))))

(defn- quoted-require-calls
  "Return every `(require ...)` call sitting inside quoted data in `form`.

  Requires executing directly in live code are exercised (and sometimes
  deliberately fail) when the suite runs; requires inside quoted data reach
  no loader until weaver-side eval, so only those can silently strand a
  tested form after a namespace rename."
  [form]
  (letfn [(walk [node quoted?]
            (if (coll? node)
              (let [inner? (or quoted? (and (seq? node) (= 'quote (first node))))
                    hit (when (and quoted? (seq? node) (= 'require (first node)))
                          [node])]
                (into (vec hit) (mapcat #(walk % inner?)) (seq node)))
              []))]
    (walk form false)))

(defn- embedded-requires
  "Return {:ns sym :line n} for every literal libspec passed to a `require`
  call inside quoted data anywhere in `form`."
  [form]
  (for [call (quoted-require-calls form)
        arg (rest call)
        :let [ns-sym (quoted-libspec-ns arg)]
        :when ns-sym]
    {:ns ns-sym :line (:line (meta call))}))

(defn- resolvable-namespace?
  "True when `ns-sym` maps to a source file under a repo root or on this
  JVM's classpath (clojure.* and other library namespaces)."
  [source-roots ns-sym]
  (let [path (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        candidates [(str path ".clj") (str path ".cljc")]]
    (boolean (or (some (fn [root]
                         (some #(.isFile (io/file root %)) candidates))
                       source-roots)
                 (some io/resource candidates)))))

(defn- embedded-require-findings
  "Scan every source file under `source-roots` for embedded literal requires
  of namespaces that resolve nowhere. An unreadable file is itself a finding."
  [source-roots]
  (for [root source-roots
        ^java.io.File file (sort (file-seq (io/file root)))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        finding (try
                  (for [{:keys [ns line]} (embedded-requires (source-forms/read-all file))
                        :when (not (resolvable-namespace? source-roots ns))]
                    (str (.getPath file) ":" line ": embedded require of `" ns
                         "` resolves to no source file under the repo roots or classpath"))
                  (catch Exception e
                    [(str (.getPath file) ": embedded-require scan could not read file: "
                          (ex-message e))]))]
    finding))

(defn -main [& _]
  (let [source-roots (configured-source-roots)]
    (doseq [root source-roots]
      (when-not (.isDirectory (java.io.File. root))
        (binding [*out* *err*]
          (println "conventions-check: configured source root does not exist:" root))
        (System/exit 1)))
    (let [{:keys [analysis]} (kondo/run! {:lint source-roots
                                          :config {:analysis {:locals true}}})
          undocumented (->> (:namespace-definitions analysis)
                            (remove :doc)
                            (map (juxt :filename :name)))
          macro-shadows (->> (:locals analysis)
                             (filter #(core-macro-names (str (:name %))))
                             (map (juxt :filename :row :name)))
          findings (concat
                    (for [[file ns-name] undocumented]
                      (str file ": namespace " ns-name " has no docstring"))
                    (for [[file row local] macro-shadows]
                      (str file ":" row ": local `" local
                           "` shadows the clojure.core macro; rename on destructure"
                           " (e.g. `{" local "-sym :" local "}`)"))
                    (embedded-require-findings source-roots)
                    (api-form/check analysis)
                    (spool-tiers/check analysis)
                    (spool-var/check)
                    (api-tests/check "test/clojure/millstrand/api")
                    (json-literals/check source-roots)
                    (workspace-tests/check analysis "test/clojure/millstrand"))]
      (if (seq findings)
        (do (binding [*out* *err*]
              (doseq [f findings] (println f))
              (println (str "conventions-check: " (count findings) " finding(s)")))
            (System/exit 1))
        (println "conventions-check: OK")))))
