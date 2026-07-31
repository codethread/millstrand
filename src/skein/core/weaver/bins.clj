(ns skein.core.weaver.bins
  "Resolve and expose executable declarations collected from active modules.

  Registration keeps authored path spellings and never touches the filesystem.
  The read-only `bins` operation is the single planning boundary: it resolves
  approved family/root anchors, computes the executable readiness tri-state, and
  supplies the selected workspace environment overlay."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :refer [require-valid!]]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.spool-sync :as spool-sync]))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::name non-blank-string?)
(s/def ::doc string?)
(s/def ::spool non-blank-string?)
(s/def ::executable non-blank-string?)
(s/def ::build-argv (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def ::list-item
  (s/and map?
         #(contains? % :name)
         #(contains? % :spool)
         #(contains? % :doc)
         #(contains? % :executable)
         #(s/valid? ::name (:name %))
         #(s/valid? ::spool (:spool %))
         #(s/valid? ::doc (:doc %))
         #(s/valid? ::executable (:executable %))
         #(or (not (contains? % :build))
              (s/valid? ::build-argv (:build %)))))
(s/def ::list-result
  (s/and map?
         #(= "bins list" (:operation %))
         #(vector? (:bins %))
         #(every? (fn [item] (s/valid? ::list-item item)) (:bins %))))
(s/def ::runnable (s/nilable boolean?))
(s/def ::env (s/map-of string? string?))
(s/def ::exec-path
  (s/and map? #(contains? % :path) #(not (contains? % :command))
         #(s/valid? ::executable (:path %)) #(s/valid? ::env (:env %))))
(s/def ::exec-command
  (s/and map? #(contains? % :command) #(not (contains? % :path))
         #(s/valid? ::executable (:command %)) #(s/valid? ::env (:env %))))
(s/def ::exec (s/or :path ::exec-path :command ::exec-command))
(s/def ::build
  (s/and map?
         #(vector? (:argv %))
         #(s/valid? ::build-argv (:argv %))
         #(s/valid? ::executable (:cwd %))))
(s/def ::plan-result
  (s/and map?
         #(= "bins plan" (:operation %))
         #(s/valid? ::name (:bin %))
         #(s/valid? ::runnable (:runnable %))
         #(s/valid? ::exec (:exec %))
         #(or (not (contains? % :build)) (s/valid? ::build (:build %)))))

(defn- canonical-file [path]
  (.getCanonicalFile (io/file path)))

(defn- file-under? [file root]
  (let [file-path (.getPath ^java.io.File (canonical-file file))
        root-path (.getPath ^java.io.File (canonical-file root))
        prefix (str root-path java.io.File/separator)]
    (or (= file-path root-path) (str/starts-with? file-path prefix))))

(defn- approved [runtime]
  (if (contains? runtime :release-marker)
    (spool-sync/approved-spools runtime (some-> runtime :release-marker :marker))
    (spool-sync/approved-spools runtime)))

(defn- root-matches [runtime source-file]
  (let [source-file (canonical-file source-file)
        approved (approved runtime)]
    (->> (:spools approved)
         (keep (fn [[lib entry]]
                 (when (file-under? source-file (:root entry))
                   (let [family (::spool-sync/family (meta entry))]
                     {:lib lib
                      :entry entry
                      :family family
                      :coordinate (get-in approved [:families family :effective-coordinate])
                      :root (canonical-file (:root entry))}))))
         (sort-by (comp count #(.getPath ^java.io.File (:root %))))
         reverse)))

(defn- longest-root-match [runtime source-file]
  (first (root-matches runtime source-file)))

(defn- coordinate-root [runtime coordinate]
  (case (:kind coordinate)
    :git (canonical-file (io/file (access/cache-base) "skein" "spools" (:git/sha coordinate)))
    :local (canonical-file (access/canonical-root runtime (:local/root coordinate)))
    :skein/source-root
    (canonical-file (io/file (access/source-checkout-root) (:skein/source-root coordinate)))
    (throw (ex-info "Bin anchor has an unsupported approved coordinate"
                    {:reason :bin/anchor-unresolved :coordinate coordinate}))))

(defn- anchor-unresolved! [bin source-file anchor]
  (throw (ex-info "Bin executable anchor cannot be resolved"
                  {:reason :bin/anchor-unresolved
                   :code "bin/anchor-unresolved"
                   :bin bin
                   :file source-file
                   :anchor anchor
                   :allowed [:family :root]
                   :remedy "Use a string executable spelling such as ~/bin/x or /absolute/path/x."})))

(defn- source-directory [source-file]
  (some-> source-file io/file .getCanonicalFile .getParentFile))

(defn- canonical-relative [source-file path]
  (canonical-file (io/file (source-directory source-file) path)))

(defn- resolve-executable
  "Resolve one declaration's executable and build base without checking it."
  [runtime bin entry]
  (let [source-file (:source/file entry)
        executable (:executable entry)]
    (when-not (non-blank-string? source-file)
      (when (vector? executable)
        (anchor-unresolved! bin source-file (first executable))))
    (if (vector? executable)
      (let [[anchor path] executable
            match (longest-root-match runtime source-file)
            _ (when-not match (anchor-unresolved! bin source-file anchor))
            base (case anchor
                   :family (coordinate-root runtime (:coordinate match))
                   :root (:root match)
                   (anchor-unresolved! bin source-file anchor))]
        {:exec {:path (.getPath ^java.io.File (canonical-file (io/file base path)))}
         :base base
         :family (:family match)
         :root-match match})
      (let [path executable
            bare? (not (re-find #"[\\/]" path))
            home? (or (= "~" path) (str/starts-with? path "~/"))
            absolute? (.isAbsolute (io/file path))
            resolved-path (cond
                            bare? nil
                            home? (canonical-file (access/expand-user-home path))
                            absolute? (canonical-file path)
                            :else (canonical-relative source-file path))]
        {:exec (if resolved-path
                 {:path (.getPath ^java.io.File resolved-path)}
                 {:command path})
         :base (source-directory source-file)
         :family (some-> (longest-root-match runtime source-file) :family)
         :root-match (longest-root-match runtime source-file)}))))

(defn- effective-entry [runtime bin]
  (let [name (if (keyword? bin) (name bin) (str bin))]
    (or (get (core-registry/effective (:bin-store runtime)) name)
        (throw (ex-info "Bin was not found"
                        {:reason :bin/unknown
                         :code "bin/unknown"
                         :bin name
                         :available (vec (sort (keys (core-registry/effective
                                                      (:bin-store runtime)))))})))))

(defn- runnable? [exec]
  (when-let [path (:path exec)]
    (let [file (io/file path)]
      (and (.isFile file) (.canExecute file)))))

(defn plan
  "Return the resolved execution plan for registered `bin`."
  [runtime bin]
  (let [bin (if (keyword? bin) (name bin) (str bin))
        entry (effective-entry runtime bin)
        {:keys [exec base]} (resolve-executable runtime bin entry)
        result (cond-> {:operation "bins plan"
                        :bin bin
                        :runnable (runnable? exec)
                        :exec (assoc exec :env {"SKEIN_WORKSPACE" (access/config-dir runtime)})}
                 (:build entry) (assoc :build {:argv (:build entry)
                                               :cwd (.getPath ^java.io.File (canonical-file base))}))]
    (require-valid! ::plan-result result "bins plan result is invalid")))

(defn- list-item [[_ entry]]
  (let [executable (:executable entry)
        result (cond-> {:name (:name entry)
                        :spool (str (:provenance entry))
                        :doc (:doc entry)
                        ;; Listing is declaration inspection. Anchors are
                        ;; deliberately rendered without resolving them; the
                        ;; plan path owns anchor resolution and its
                        ;; bin/anchor-unresolved failure.
                        :executable (if (vector? executable)
                                      (pr-str executable)
                                      executable)}
                 (:build entry) (assoc :build (:build entry)))]
    (require-valid! ::list-item result "bins list item is invalid")))

(defn list-bins
  "Return effective executable declarations in deterministic name order."
  [runtime]
  (let [entries (sort-by key (core-registry/effective (:bin-store runtime)))
        result {:operation "bins list"
                :bins (mapv list-item entries)}]
    (require-valid! ::list-result result "bins list result is invalid")))

(def ^:private bins-arg-spec
  {:op "bins"
   :doc "List shipped executables or resolve one execution plan."
   :subcommands
   {"list" {:doc "List effective executable declarations."
            :hook-class :read
            :deadline-class :standard}
    "plan" {:doc "Resolve one executable declaration into an execution plan."
            :hook-class :read
            :deadline-class :standard
            :positionals [{:name :bin
                           :type :string
                           :required? true
                           :doc "Registered bin name."}]}}})

(def ^:private bins-returns
  {:subcommands
   {"list" {:type :map
            :required {:operation :string
                       :bins {:type :collection :items :json}}}
    "plan" {:type :map
            :required {:operation :string
                       :bin :string
                       :runnable [:nullable :boolean]
                       :exec :json}
            :optional {:build :json}}}})

(defn bins-op
  "Handle the read-only `bins list` and `bins plan` protocol."
  [{:keys [op/runtime op/args]}]
  (case (first (:subcommand args))
    "list" (list-bins runtime)
    "plan" (plan runtime (:bin args))
    (throw (ex-info "Unsupported bins subcommand"
                    {:subcommand (:subcommand args)
                     :allowed ["list" "plan"]}))))

(defn register-built-in-ops!
  "Register the core `bins` read-only operation."
  [runtime]
  (let [register-op! (requiring-resolve 'skein.api.weaver.alpha/register-op!)]
    (register-op! runtime core-registry/system-owner 'bins
                  {:doc (:doc bins-arg-spec)
                   :arg-spec bins-arg-spec
                   :returns bins-returns}
                  'skein.core.weaver.bins/bins-op)))
