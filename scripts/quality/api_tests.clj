(ns quality.api-tests
  "Enforce the ownership boundary for tests under test/clojure/millstrand/api."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [quality.source-forms :as source-forms]))

(def ^:private allowed-core-namespaces
  "Core namespaces whose published specs may be used as contract oracles."
  #{'millstrand.core.specs})

(def ^:private test-megasuites
  "Integration suites that must not become API-test dependencies."
  #{'millstrand.spools-test
    'millstrand.weaver-test
    'millstrand.alpha-test
    'millstrand.core.db-test
    'millstrand.core.weaver.hooks-events-test})

(defn- core-namespace?
  [ns-sym]
  (and (symbol? ns-sym)
       (str/starts-with? (str ns-sym) "millstrand.core.")))

(defn- symbol-namespace
  [sym]
  (or (some-> sym namespace symbol)
      sym))

(defn- allowed-core-symbol?
  [sym aliases]
  (contains? allowed-core-namespaces
             (get aliases (some-> sym namespace symbol)
                  (symbol-namespace sym))))

(defn- implementation-symbol?
  [value aliases]
  (and (symbol? value)
       (let [ns-sym (get aliases (some-> value namespace symbol)
                         (symbol-namespace value))]
         (and (core-namespace? ns-sym)
              (not (allowed-core-symbol? value aliases))))))

(defn- line-of
  [form]
  (or (:line (meta form)) 1))

(defn- qualified-symbols
  [form aliases]
  (filter #(implementation-symbol? % aliases)
          (tree-seq coll? seq form)))

(defn- special-call
  [form aliases]
  (when (seq? form)
    (let [op (first form)
          args (rest form)
          core-symbols (vec (mapcat #(qualified-symbols % aliases) args))]
      (cond
        (and (= 'with-redefs op) (seq core-symbols))
        {:kind :core-redefs :symbol (first core-symbols)}

        (and (#{'ns-resolve 'var-get} op) (seq core-symbols))
        {:kind :private-var-resolution :symbol (first core-symbols)}))))

(defn- form-findings
  [file form aliases]
  (let [seen (atom #{})]
    (letfn [(emit [kind sym node]
              (let [key [kind sym]]
                (when-not (contains? @seen key)
                  (swap! seen conj key)
                  (str (.getPath file) ":" (line-of node) ": "
                       (case kind
                         :core-redefs "quality.api-tests: core collaborator redefinition"
                         :private-var-resolution "quality.api-tests: private core Var resolution"
                         "quality.api-tests: direct millstrand.core implementation use")
                       ": " sym))))
            (walk [node]
              (let [special (special-call node aliases)
                    direct (when-not special
                             (first (qualified-symbols node aliases)))
                    here (cond
                           special (emit (:kind special) (:symbol special) node)
                           direct (emit :direct-core direct node))]
                (into (cond-> [] here (conj here))
                      (mapcat walk (if special [] (if (coll? node) node []))))))]
      (walk form))))

(defn- require-names
  [form]
  (when (and (seq? form) (= 'ns (first form)))
    (for [part (rest form)
          :when (and (seq? part) (= :require (first part)))
          libspec (rest part)
          :let [lib (cond
                      (symbol? libspec) libspec
                      (and (vector? libspec) (symbol? (first libspec))) (first libspec))]
          :when lib]
      lib)))

(defn- require-aliases
  [form]
  (if (and (seq? form) (= 'ns (first form)))
    (into {}
          (for [part (rest form)
                :when (and (seq? part) (= :require (first part)))
                libspec (rest part)
                :when (vector? libspec)
                :let [lib (first libspec)
                      items (vec libspec)
                      as-index (.indexOf ^java.util.List items :as)]
                :when (and (symbol? lib) (<= 0 as-index)
                           (< (inc as-index) (count items)))]
            [(nth items (inc as-index)) lib]))
    {}))

(defn- file-findings
  [file]
  (try
    (let [forms (source-forms/read-all file)
          aliases (or (some require-aliases forms) {})
          structural (mapcat #(form-findings file % aliases) forms)
          megasuite (for [form forms
                          lib (require-names form)
                          :when (contains? test-megasuites lib)]
                      (str (.getPath file) ":" (line-of form)
                           ": quality.api-tests: test-megasuite require: " lib))]
      (concat structural megasuite))
    (catch Exception error
      [(str (.getPath file) ": quality.api-tests: could not read file: "
            (ex-message error))])))

(s/def ::api-root (s/and string? #(not (str/blank? %))))
(s/def ::finding string?)
(s/def ::findings (s/coll-of ::finding :kind vector?))

(defn- require-valid!
  [spec value label]
  (when-not (s/valid? spec value)
    (throw (ex-info (str label " does not conform to " spec)
                    {:spec spec :value value :explain (s/explain-data spec value)})))
  value)

(defn findings
  "Return `::findings` for Clojure files rooted at `::api-root`.

  Fails loudly when the root or returned findings do not conform."
  [api-root]
  (require-valid! ::api-root api-root "api-tests root")
  (let [root (io/file api-root)]
    (when-not (.isDirectory root)
      (throw (ex-info "api-tests root does not conform"
                      {:test-root api-root :expected :directory})))
    (require-valid! ::findings
                    (vec (mapcat file-findings
                                 (filter #(and (.isFile ^java.io.File %)
                                               (str/ends-with? (.getName ^java.io.File %) ".clj"))
                                         (file-seq root))))
                    "api-tests findings")))

(s/fdef findings
  :args (s/cat :api-root ::api-root)
  :ret ::findings)

(defn check
  "Return `::findings` for API test boundary findings rooted at `::api-root`."
  [api-root]
  (findings api-root))

(s/fdef check
  :args (s/cat :api-root ::api-root)
  :ret ::findings)
