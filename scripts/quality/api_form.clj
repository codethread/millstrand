(ns quality.api-form
  "The api-form slice of quality.conventions-check: SPEC-003.C19a over
  converted `millstrand.api.*` modules.

  A converted module's `alpha.clj` leads with its public promised vars,
  each carrying a docstring; private helpers below them are welcome when
  they are part of the file's story, genuine plumbing moves to the sibling
  `internal` namespace, and no source line passes column 96. Reading order
  and the story/plumbing line are judgment (the source-form reviewer);
  this gate checks only what is mechanical. `pending` names the modules
  not yet converted; each conversion card under epic 9nu0q deletes its own
  entry, and a stale entry is a finding. Two dependency rules hold for
  every internal namespace regardless of pending: an internal namespace
  never requires an alpha namespace, and only a module's own alpha (or a
  test) requires its internal. Kept clj-kondo-free so the findings logic
  loads on the test classpath; the caller supplies kondo's analysis data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def pending
  "Modules not yet through their v1 form card (epic 9nu0q)."
  #{})

(def ^:private width-limit 96)

(def ^:private api-root "src/millstrand/api")

(defn module-dirs
  "Return {module-name module-dir} for every directory under `api-root`,
  with directory underscores read back as namespace dashes."
  []
  (into {}
        (for [^java.io.File dir (.listFiles (io/file api-root))
              :when (.isDirectory dir)]
          [(str/replace (.getName dir) "_" "-") dir])))

(defn- wide-lines
  "Return {:file :line :length} for every line past `width-limit` in the
  .clj files under `dir`."
  [^java.io.File dir]
  (for [^java.io.File file (sort (file-seq dir))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        [idx line] (map-indexed vector (str/split-lines (slurp file)))
        :when (> (count line) width-limit)]
    {:file (.getPath file) :line (inc idx) :length (count line)}))

(defn- alpha-vars
  "Return the kondo var definitions from `dir`/alpha.clj."
  [analysis ^java.io.File dir]
  (let [alpha-path (.getPath (io/file dir "alpha.clj"))]
    (filter #(= alpha-path (:filename %)) (:var-definitions analysis))))

(defn- undocumented?
  "True for a public var definition that promises surface without a
  docstring; `declare` sites carry their doc at the definition."
  [var-def]
  (and (not (:private var-def))
       (not= 'clojure.core/declare (:defined-by var-def))
       (str/blank? (:doc var-def))))

(defn- api-ns-module
  "Return [module tier] for a `millstrand.api.<module>.alpha` or
  `millstrand.api.<module>.internal[.<concern>]` symbol, nil for anything else."
  [ns-sym]
  (rest (re-matches #"millstrand\.api\.([^.]+)\.(alpha|internal)(?:\..+)?" (str ns-sym))))

(defn- dependency-findings
  "Enforce the internal-namespace dependency rules over kondo
  `:namespace-usages`, everywhere in `src/`: internal (including nested
  `internal.<concern>` files) requires no alpha — its own alpha would be a
  cycle, a foreign one smuggles tiered surface into plumbing — and nothing
  but a module's own alpha or its own internal siblings requires its
  internal (tests may)."
  [analysis]
  (for [{:keys [from to filename row]} (:namespace-usages analysis)
        :when (re-find #"(?:^|/)src/" (str filename))
        :let [[from-module from-tier] (api-ns-module from)
              [to-module to-tier] (api-ns-module to)
              finding
              (cond
                (and (= "internal" from-tier) (= "alpha" to-tier))
                (str filename ":" row ": internal namespace `" from "` requires the"
                     " alpha namespace `" to "`; plumbing stays tier-free"
                     " (SPEC-003.C19a)")

                (and (= "internal" to-tier)
                     (not (and (#{"alpha" "internal"} from-tier)
                               (= from-module to-module))))
                (str filename ":" row ": `" from "` requires `" to "`; only the"
                     " module's own alpha namespace reaches its internal plumbing"
                     " (SPEC-003.C19a)"))]
        :when finding]
    finding))

(defn findings
  "Enforce the SPEC-003.C19a form contract over the api modules in `dirs`
  ({module-name module-dir}), skipping modules named in `pending-set` but
  flagging stale pending entries. Dependency rules apply to every module."
  [analysis dirs pending-set]
  (concat
   (for [m (sort pending-set)
         :when (not (contains? dirs m))]
     (str "conventions-check: api-form pending entry `" m
          "` matches no module directory under " api-root))
   (dependency-findings analysis)
   (for [[m dir] (sort dirs)
         :when (not (contains? pending-set m))
         finding (concat
                  (for [{:keys [filename row name]} (filter undocumented? (alpha-vars analysis dir))]
                    (str filename ":" row ": public var `" name "` in a converted"
                         " api module has no docstring (SPEC-003.C19a)"))
                  (for [{:keys [file line length]} (wide-lines dir)]
                    (str file ":" line ": line is " length " columns; converted api"
                         " modules wrap at " width-limit " (SPEC-003.C19a)")))]
     finding)))

(defn check
  "Run `findings` over the live tree and the current `pending` set."
  [analysis]
  (findings analysis (module-dirs) pending))
