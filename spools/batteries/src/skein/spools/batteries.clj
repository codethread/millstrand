(ns skein.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — as
  `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.*.alpha` calls the JSON socket dispatch uses and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  Production loading follows the ordinary approved-spool path. `mill init` seeds
  `skein.spools/batteries {:skein/source-root \"spools/batteries\"}` in
  `spools.edn`, and its startup module names that root in `:spools`. Deleting
  the seeded entry is the supported opt-out; a workspace without it has no
  batteries ops.

  Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive
  help, and where it adds value they carry closed `:annotations` sub-maps
  (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose.
  `failure-modes` reference the batteries-owned glossary outcomes reconciled by
  the batteries module (the load-order contract, DELTA-Dtf-002.CC7).

  Batteries also EXPORTS `default-help-transform` — the reference default help
  transform (DELTA-Dtf-002.CC1): one recursive renderer over the uniform fractal
  node (DELTA-Dtf-001.CC2) with no per-level branch. It is exported for trusted
  config election and never auto-registers.

  Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value`
  is a repeatable, highest-precedence string map whose values may be payload
  references; `--attributes` references a JSON object of typed bulk attributes at
  lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state`
  accepts `active|closed` for mutations and `active|closed|replaced` for `list`
  filtering."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.runtime.alpha :as runtime-api]
            [skein.api.runtime.glossary.alpha :as glossary]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file FileVisitResult Files LinkOption SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private lean-attribute-byte-floor 1024)
(def ^:private default-read-limit 500)
(def ^:private read-limit-state-version 1)
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private release-tag-pattern #"v([1-9][0-9]*)")

(defn- exact-keys? [expected value]
  (and (map? value) (= expected (set (keys value)))))

;; Public data contracts for the spool op. The manifest spec owns the producer
;; input shape; the result specs own the three JSON-bearing command results.
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::root ::non-blank-string)
(s/def ::manifest-root
  (s/and (s/keys :req-un [::root])
         #(exact-keys? #{:root} %)))
(s/def ::roots (s/map-of symbol? ::manifest-root :min-count 1))
(s/def ::requires (s/map-of symbol? ::runtime-api/release-marker-claim))
(s/def :spool/format #{1})
(s/def ::advisory-manifest
  (s/and (s/keys :req [:spool/format]
                 :req-un [::roots]
                 :opt [:skein/min]
                 :opt-un [::requires])
         #(exact-keys? #{:spool/format :skein/min :roots :requires}
                       (merge {:skein/min nil :requires nil} %))))

(s/def ::status #{:inserted :updated})
(s/def ::operation ::non-blank-string)
(s/def ::family symbol?)
(s/def ::entry ::runtime-api/spool-entry)
(s/def ::requirements
  #(s/valid? ::runtime-api/declared-result {:families {} :requirements %}))
(s/def ::spool-add-result
  (s/and (s/keys :req-un [::operation ::status ::family ::entry ::requirements])
         #(exact-keys? #{:operation :status :family :entry :requirements} %)))
(s/def ::tag ::runtime-api/release-marker-claim)
(s/def ::sha #(and (string? %) (boolean (re-matches #"[0-9a-f]{40}" %))))
(s/def ::coordinate
  (s/and (s/keys :req-un [::tag ::sha])
         #(exact-keys? #{:tag :sha} %)))
(s/def ::old ::coordinate)
(s/def ::new ::coordinate)
(s/def ::compare-url (s/nilable ::non-blank-string))
(s/def ::spool-bump-result
  (s/and (s/keys :req-un [::operation ::status ::family ::old ::new ::compare-url ::requirements])
         #(exact-keys? #{:operation :status :family :old :new :compare-url :requirements} %)))
(s/def ::declared ::runtime-api/spool-entry)
(s/def ::effective-coordinate ::runtime-api/spool-coordinate)
(s/def ::provenance ::runtime-api/spool-provenance)
(s/def ::claims ::runtime-api/spool-claims)
(s/def ::root-outcome
  (s/and map?
         #(let [status (:status %)]
            (cond
              (#{:synced :failed} status)
              (and (map? (:sync %))
                   (or (not= :failed status) (keyword? (:reason %))))

              (= :source-reloaded status) (map? (:reload %))
              (#{:partial-source-reload :source-reload-failed} status) (map? (:error %))
              (= :hard-conflict status) (map? (:conflict %))
              :else false))))
(s/def ::status-roots (s/map-of symbol? ::root-outcome))
(s/def ::modules (s/map-of keyword? ::runtime-api/module-declaration))
(s/def ::status-family
  (s/and (s/keys :req-un [::declared ::effective-coordinate ::provenance ::claims ::modules])
         #(s/valid? ::status-roots (:roots %))
         #(exact-keys? #{:declared :effective-coordinate :provenance :claims :roots :modules} %)))
(s/def ::families (s/map-of symbol? ::status-family))
(s/def ::pending-generation (s/nilable ::runtime-api/pending-generation))
(s/def ::release-marker ::runtime-api/release-marker-result)
(s/def ::spool-status-result
  (s/and (s/keys :req-un [::operation ::families ::requirements ::pending-generation ::release-marker])
         #(exact-keys? #{:operation :families :requirements :pending-generation :release-marker} %)))
(s/def ::purpose ::non-blank-string)
(s/def ::verb ::non-blank-string)
(s/def ::behavior ::non-blank-string)
;; Keyed by bare verb, never an invocation form: `strand help spool` owns the
;; flags and positionals, and a second copy here drifts from it.
(s/def ::spool-command
  (s/and (s/keys :req-un [::verb ::behavior])
         #(exact-keys? #{:verb :behavior} %)))
(s/def ::commands (s/coll-of ::spool-command :kind vector? :min-count 1))
(s/def ::conventions (s/coll-of ::non-blank-string :kind vector? :min-count 1))
(s/def ::spool-about-result
  (s/and (s/keys :req-un [::operation ::purpose ::commands ::conventions])
         #(exact-keys? #{:operation :purpose :commands :conventions} %)))

(defn- exact-spool-args? [required optional args]
  (and (map? args)
       (every? #(contains? args %) required)
       (set/subset? (set (keys args)) (set/union required optional))))

(s/def ::spool-about-args
  (s/and #(exact-spool-args? #{:subcommand} #{} %)
         #(= ["about"] (:subcommand %))))
(s/def ::spool-add-args
  (s/and #(exact-spool-args? #{:subcommand :git-url} #{:tag :lib} %)
         #(= ["add"] (:subcommand %))
         #(s/valid? ::non-blank-string (:git-url %))
         #(or (nil? (:tag %)) (s/valid? ::non-blank-string (:tag %)))
         #(or (nil? (:lib %)) (s/valid? ::non-blank-string (:lib %)))))
(s/def ::spool-bump-args
  (s/and #(exact-spool-args? #{:subcommand :family} #{:to} %)
         #(= ["bump"] (:subcommand %))
         #(s/valid? ::non-blank-string (:family %))
         #(or (nil? (:to %)) (s/valid? ::non-blank-string (:to %)))))
(s/def ::spool-status-args
  (s/and #(exact-spool-args? #{:subcommand} #{} %)
         #(= ["status"] (:subcommand %))))
(s/def ::spool-args
  (s/or :about ::spool-about-args
        :add ::spool-add-args
        :bump ::spool-bump-args
        :status ::spool-status-args))
(s/def ::spool-op-context
  (s/and map?
         #(s/valid? ::spool-args (:op/args %))
         #(or (= ["about"] (get-in % [:op/args :subcommand]))
              (some? (:op/runtime %)))))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message
                    {:spec spec
                     :value value
                     :explain (s/explain-data spec value)})))
  value)

(defn- validate-generic-state
  "Return state when it is active|closed, else fail loudly (mutations)."
  [state]
  (when-not (generic-states state)
    (throw (ex-info "Strand state must be active or closed"
                    {:state state :allowed (vec (sort generic-states))})))
  state)

(defn- validate-readable-state
  "Return state when it is active|closed|replaced, else fail loudly (list filter)."
  [state]
  (when-not (readable-states state)
    (throw (ex-info "Strand state must be active, closed, or replaced"
                    {:state state :allowed (vec (sort readable-states))})))
  state)

(s/def ::read-limit pos-int?)

(defn- validate-read-limit
  "Return limit when it is a positive integer, else fail loudly."
  [limit]
  (when-not (s/valid? ::read-limit limit)
    (throw (ex-info "Read result limit must be a positive integer"
                    {:limit limit :explain (s/explain-str ::read-limit limit)})))
  limit)

(defn- validate-vocab-kind
  "Return the --kind value as its declaration-kind keyword, else fail loudly.
  Reuses the vocab registry's own `:kind` enum as the allow-list."
  [kind]
  (let [k (keyword kind)]
    (when-not (contains? vocab/declaration-kinds k)
      (throw (ex-info "vocab --kind must be attr-namespace or edge"
                      {:kind kind :allowed (mapv name (sort vocab/declaration-kinds))})))
    k))

(defn- read-limit-state [rt]
  (runtime-api/spool-state rt ::read-limit {:version read-limit-state-version}
                           #(hash-map :limit (atom default-read-limit))))

(defn read-limit
  "Return the runtime's batteries read-result cap for CLI list/ready ops."
  [rt]
  @(:limit (read-limit-state rt)))

(defn set-read-limit!
  "Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap."
  [rt limit]
  (let [limit (validate-read-limit limit)]
    (reset! (:limit (read-limit-state rt)) limit)
    limit))

(defn- effective-read-limit [rt explicit-limit]
  (validate-read-limit (or explicit-limit (read-limit rt))))

(defn- request-context
  "Build the mutation request context so hooks and events see the operation."
  [operation]
  {:request/source :json-socket
   :request/operation operation})

(defn- json-safe-value
  "Coerce query-introspection payloads (which carry EDN query expressions with
  keywords, symbols, and sets) into JSON-safe data, matching the JSON socket's
  `query-list`/`query-explain` projection so `strand query …` returns identical
  shapes to the old builtin."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

;; --- spool Git boundary -----------------------------------------------------

(defn- delete-tree!
  "Delete a temporary tree without following symbolic links."
  [^java.io.File root]
  (let [path (.toPath root)]
    (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (Files/walkFileTree
       path
       (proxy [SimpleFileVisitor] []
         (visitFile [file _attrs]
           (Files/delete file)
           FileVisitResult/CONTINUE)
         (postVisitDirectory [dir error]
           (when error (throw error))
           (Files/delete dir)
           FileVisitResult/CONTINUE))))))

(defn- run-git
  "Run Git with argv and return its exit, stdout, and stderr."
  [dir & args]
  (let [process (try
                  (-> (ProcessBuilder. ^java.util.List (vec (cons "git" args)))
                      (.directory dir)
                      (.start))
                  (catch java.io.IOException error
                    {:exit 127 :stdout "" :stderr (ex-message error)}))]
    (if (map? process)
      process
      (let [stdout (future (slurp (.getInputStream ^Process process)))
            stderr (future (slurp (.getErrorStream ^Process process)))
            exit (.waitFor ^Process process)]
        {:exit exit :stdout @stdout :stderr @stderr}))))

(defn- checked-git
  "Run Git, returning stdout or failing with command diagnostics."
  [dir & args]
  (let [result (apply run-git dir args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Git command failed"
                      {:argv (vec (cons "git" args))
                       :exit (:exit result)
                       :stderr (str/trim (:stderr result))})))
    (:stdout result)))

(defn- ls-remote [git-url]
  (checked-git nil "ls-remote" "--tags" git-url))

(defn- throw-manifest-git-failure! [git-url sha args result]
  (throw (ex-info "Git command failed while reading advisory spool.edn"
                  {:git-url git-url
                   :git/sha sha
                   :argv (vec (cons "git" args))
                   :exit (:exit result)
                   :stderr (str/trim (:stderr result))})))

(defn- manifest-at [git-url sha]
  (let [tmp (.toFile (Files/createTempDirectory "skein-spool-manifest-"
                                                (make-array FileAttribute 0)))]
    (try
      (checked-git tmp "init" "--quiet")
      (checked-git tmp "fetch" "--quiet" "--depth=1" git-url sha)
      (let [probe-args ["cat-file" "-e" "FETCH_HEAD:spool.edn"]
            probe (apply run-git tmp probe-args)]
        (if (zero? (:exit probe))
          (checked-git tmp "show" "FETCH_HEAD:spool.edn")
          (let [missing-args ["ls-tree" "--name-only" "FETCH_HEAD" "--" "spool.edn"]
                missing-check (apply run-git tmp missing-args)]
            (cond
              (not (zero? (:exit missing-check)))
              (throw-manifest-git-failure! git-url sha missing-args missing-check)

              (str/blank? (:stdout missing-check))
              nil

              :else
              (throw-manifest-git-failure! git-url sha probe-args probe)))))
      (finally
        (delete-tree! tmp)))))

(def ^:private default-git-client
  {:ls-remote ls-remote
   :manifest-at manifest-at})

(def ^:private git-client-state-version 1)

(defn- git-client-state [rt]
  (runtime-api/spool-state rt ::git-client {:version git-client-state-version}
                           #(hash-map :client (atom default-git-client))))

(defn- git-client [rt]
  @(:client (git-client-state rt)))

(defn- release-number [tag]
  (when-let [[_ n] (and (string? tag) (re-matches release-tag-pattern tag))]
    (bigint n)))

(defn- available-tags-description [available]
  (if (seq available)
    (str/join ", " available)
    "none found"))

(defn- release-tag-diagnostics [available]
  {:accepted-format "vN where N is a positive integer"
   :available (vec available)})

(defn- require-release-tag [tag role available]
  (when-not (release-number tag)
    (throw (ex-info
            (str role " must match vN where N is a positive integer; v0 is reserved. "
                 "Available annotated tags: " (available-tags-description available))
            {:tag tag
             :reason :invalid-release-marker
             :accepted-format (:accepted-format (release-tag-diagnostics available))
             :available (:available (release-tag-diagnostics available))})))
  tag)

(defn- peeled-tags [output]
  (into (sorted-map-by #(compare (release-number %1) (release-number %2)))
        (keep (fn [line]
                (when-let [[_ sha tag]
                           (re-matches #"([0-9a-fA-F]{40})\s+refs/tags/(v[1-9][0-9]*)\^\{\}"
                                       line)]
                  [tag (str/lower-case sha)])))
        (str/split-lines output)))

(defn- select-tag [output requested]
  (let [tags (peeled-tags output)
        tag (if requested
              (require-release-tag requested "Requested tag" (keys tags))
              (last (keys tags)))]
    (when-not tag
      (if (re-find #"refs/tags/v0\^\{\}" output)
        (throw (ex-info
                (str "v0 is reserved; release tags must match vN where N is a positive integer. "
                     "Available annotated tags: " (available-tags-description (keys tags)))
                (merge {:reason :reserved-release-marker :tag "v0"}
                       (release-tag-diagnostics (keys tags)))))
        (throw (ex-info
                (str "Repository has no annotated release tags matching vN where N is a positive "
                     "integer; available annotated tags: none found. Pin a reviewed sha manually "
                     "in spools.edn")
                (merge {:reason :no-release-tags}
                       (release-tag-diagnostics (keys tags)))))))
    (when-not (contains? tags tag)
      (throw (ex-info
              (str "Release tag is missing or is not annotated; accepted format is vN where N is a "
                   "positive integer. Available annotated tags: "
                   (available-tags-description (keys tags)))
              (merge {:reason :annotated-tag-not-found :tag tag}
                     (release-tag-diagnostics (keys tags))))))
    {:tag tag :sha (get tags tag)}))

(defn- url-basename [git-url]
  (let [trimmed (str/replace git-url #"[/]+$" "")
        basename (last (str/split trimmed #"[/\\:]"))
        basename (str/replace basename #"\.git$" "")]
    (when (str/blank? basename)
      (throw (ex-info "Git URL must have a repository basename" {:git-url git-url})))
    (symbol basename)))

(defn- read-manifest [git-url sha content]
  (when content
    (let [eof (Object.)
          rdr (PushbackReader. (StringReader. content))]
      (try
        (let [manifest (edn/read {:eof eof} rdr)
              trailing (edn/read {:eof eof} rdr)]
          (when (identical? eof manifest)
            (throw (ex-info "manifest is empty" {:reason :empty-manifest})))
          (when-not (identical? eof trailing)
            (throw (ex-info "trailing input follows the first EDN value"
                            {:reason :trailing-input :trailing trailing})))
          (require-valid! ::advisory-manifest manifest
                          "Advisory spool.edn has an invalid manifest shape"))
        (catch Exception cause
          (throw
           (ex-info
            (str "Advisory spool.edn parse failed for " git-url " at commit " sha ": "
                 (ex-message cause) ". The manifest must contain exactly one EDN value; "
                 "trailing input is invalid.")
            {:git-url git-url
             :git/sha sha
             :contract :single-edn-value
             :reason (or (:reason (ex-data cause)) :invalid-edn)}
            cause)))))))

(defn- require-matching-manifest-lib! [manifest requested-lib]
  (when (and manifest requested-lib (not (contains? (:roots manifest) requested-lib)))
    (let [manifest-libs (vec (sort (keys (:roots manifest))))]
      (throw (ex-info
              (str "Requested --lib " requested-lib
                   " conflicts with advisory spool.edn roots "
                   (str/join ", " manifest-libs))
              {:requested-lib requested-lib
               :manifest-libs manifest-libs
               :reason :manifest-lib-conflict})))))

(defn- manifest-roots [manifest implicit-lib]
  (if manifest
    (into {} (map (fn [[lib opts]] [lib (:root opts)])) (:roots manifest))
    {implicit-lib "."}))

(defn- family-entry [git-url tag sha manifest lib]
  (cond-> {:git/url git-url
           :git/tag tag
           :git/sha sha
           :roots (manifest-roots manifest lib)}
    (contains? manifest :requires) (assoc :requires (:requires manifest))
    (contains? manifest :skein/min) (assoc :skein/min (:skein/min manifest))))

(defn- add-spool-op [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [git-url tag lib]} (:op/args ctx)
        requested-lib (some-> lib symbol)
        lib (or requested-lib (url-basename git-url))
        client (git-client rt)
        {:keys [tag sha]} (select-tag ((:ls-remote client) git-url) tag)
        manifest (read-manifest git-url sha ((:manifest-at client) git-url sha))
        _ (require-matching-manifest-lib! manifest requested-lib)
        entry (family-entry git-url tag sha manifest lib)
        write (runtime-api/upsert-spool-entry! rt lib entry)
        requirements (:requirements (runtime-api/declared rt))]
    {:status (:status write)
     :family lib
     :entry entry
     :requirements requirements}))

(defn- bump-target [declared family requested tags]
  (if requested
    (require-release-tag requested "Target tag" (keys tags))
    (let [requirements (:requirements declared)]
      (if (:valid? requirements)
        (last (keys tags))
        (or (get (:suggestions requirements) family)
            (throw (ex-info "Current floor failures do not suggest a bump for this family"
                            {:family family
                             :requirements requirements})))))))

(defn- github-web-url [git-url]
  (when-let [[_ owner repository]
             (or (re-matches #"(?i)https?://github\.com/([^/]+)/([^/?#]+?)(?:\.git)?/?" git-url)
                 (re-matches #"(?i)ssh://(?:[^@/]+@)?github\.com(?:\:\d+)?/([^/]+)/([^/?#]+?)(?:\.git)?/?" git-url)
                 (re-matches #"(?i)(?:[^@/:]+@)?github\.com:([^/]+)/([^/?#]+?)(?:\.git)?/?" git-url))]
    (str "https://github.com/" owner "/" repository)))

(defn- compare-url [git-url old-sha new-sha]
  (when-let [web-url (or (github-web-url git-url)
                         (when (re-matches #"(?i)https?://.+" git-url)
                           (str/replace git-url #"\.git$" "")))]
    (str web-url "/compare/" old-sha "..." new-sha)))

(defn- bump-spool-op [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [family to]} (:op/args ctx)
        family (symbol family)
        declared (runtime-api/declared rt)
        old-entry (get-in declared [:families family :declared])]
    (when-not old-entry
      (throw (ex-info "Spool family is not declared" {:family family})))
    (when-not (:git/url old-entry)
      (throw (ex-info "Only Git spool families can be bumped" {:family family :entry old-entry})))
    (let [tags (peeled-tags ((:ls-remote (git-client rt)) (:git/url old-entry)))
          target (bump-target declared family to tags)]
      (when-not target
        (throw (ex-info
                (str "Repository has no annotated release tags matching vN where N is a positive "
                     "integer; available annotated tags: none found")
                (merge {:family family :reason :no-release-tags}
                       (release-tag-diagnostics (keys tags))))))
      (when-not (contains? tags target)
        (throw (ex-info
                (str "Target release tag is missing or is not annotated; accepted format is vN "
                     "where N is a positive integer. Available annotated tags: "
                     (available-tags-description (keys tags)))
                (merge {:family family :reason :annotated-tag-not-found :tag target}
                       (release-tag-diagnostics (keys tags))))))
      (let [new-sha (get tags target)
            entry (assoc old-entry :git/tag target :git/sha new-sha)
            write (runtime-api/upsert-spool-entry! rt family entry)]
        {:status (:status write)
         :family family
         :old {:tag (:git/tag old-entry) :sha (:git/sha old-entry)}
         :new {:tag target :sha new-sha}
         :compare-url (compare-url (:git/url old-entry) (:git/sha old-entry) new-sha)
         :requirements (:requirements (runtime-api/declared rt))}))))

(defn- family-modules [modules roots]
  (into (sorted-map)
        (filter (fn [[_ declaration]]
                  (seq (set/intersection roots (set (:spools declaration))))))
        modules))

(defn- spool-status [rt]
  (let [declared (runtime-api/declared rt)
        status (runtime-api/status rt)]
    {:families
     (into (sorted-map)
           (map (fn [[family projection]]
                  ;; :roots-map is the normalized root set (implicit {family "."}
                  ;; already applied by sync), so status joins outcomes and
                  ;; modules against it directly rather than re-reading the raw
                  ;; :declared entry, which may carry no :roots.
                  (let [roots (set (keys (:roots-map projection)))]
                    [family
                     (-> projection
                         (dissoc :roots-map)
                         (assoc :roots (select-keys (:root/outcomes status) roots)
                                :modules (family-modules (:modules status) roots)))])))
           (:families declared))
     :requirements (:requirements declared)
     :pending-generation (:pending-generation status)
     :release-marker (runtime-api/release-marker rt)}))

(defn- spool-about
  []
  {:operation "spool about"
   :purpose (format-alpha/reflow
             "|Add and bump annotated Git spool releases through the validated,
              |comment-preserving runtime write verb. Status joins declared,
              |overlay, sync, use, pending-generation, and release-marker state.")
   :commands
   [{:verb "add"
     :behavior (format-alpha/reflow
                "|Queries remote annotated tags, fetches the optional advisory
                 |spool.edn at the selected peeled commit, then atomically writes
                 |the validated tag and commit pin to the workspace spools.edn.")}
    {:verb "bump"
     :behavior (format-alpha/reflow
                "|Queries remote annotated tags, selects a requested, floor-driven,
                 |or latest release, then atomically rewrites its tag and peeled
                 |commit pin together.")}
    {:verb "status"
     :behavior (format-alpha/reflow
                "|Reads runtime and workspace state only. It performs no network,
                 |file write, sync, reload, or other adoption action.")}]
   :conventions
   [(format-alpha/reflow
     "|Add and bump resolve only peeled refs/tags/vN^{} commits. v0,
      |lightweight tags, and untagged repositories are refused.")
    (format-alpha/reflow
     "|An optional producer spool.edn supplies roots and floors. With it,
      |--lib must match one declared root symbol. Without it, add uses one root
      |at . under the URL basename; --lib confirms or overrides that symbol.")
    (format-alpha/reflow
     "|Status performs no remote Git calls. It reports the running runtime's
      |declared and adopted state without attempting sync or reload.")]})

(defn spool-op
  "Dispatch validated `strand spool about|add|bump|status` inputs and results.

  Input uses `::spool-op-context`; results use `::spool-about-result`,
  `::spool-add-result`, `::spool-bump-result`, or `::spool-status-result`.
  Producer manifests use `::advisory-manifest`. Each closed result/manifest map
  also uses the named `exact-keys?` predicate because `clojure.spec.alpha/keys`
  accepts extra keys. The `status` read leaf keeps the retired `spool-status`
  op's offline, no-network, closed-result contract verbatim
  (DELTA-Lhc-001.CC8)."
  [ctx]
  (require-valid! ::spool-op-context ctx "spool received an invalid operation context")
  (case (first (:subcommand (:op/args ctx)))
    "about" (require-valid! ::spool-about-result (spool-about)
                            "spool about returned an invalid result")
    "add" (require-valid! ::spool-add-result (assoc (add-spool-op ctx) :operation "spool add")
                          "spool add returned an invalid result")
    "bump" (require-valid! ::spool-bump-result (assoc (bump-spool-op ctx) :operation "spool bump")
                           "spool bump returned an invalid result")
    "status" (require-valid! ::spool-status-result
                             (assoc (spool-status (:op/runtime ctx)) :operation "spool status")
                             "spool status returned an invalid result")))

;; The blessed parser's :parse :json uses clojure.data.json/read-str, which
;; silently returns the first value and ignores trailing input, so it cannot
;; enforce old C13a's "exactly one JSON value" contract. weave reads --input as
;; a raw string and parses it strictly here instead: empty, malformed, and
;; trailing-value inputs all fail loudly before any mutation.
(defn- read-single-json
  "Read exactly one JSON value from s, failing loudly on empty, malformed, or
  trailing input (reproduces old SPEC-002.C13a stdin parsing weaver-side)."
  [s]
  (let [eof (Object.)
        ;; data.json/read unreads several characters of lookahead while parsing,
        ;; so the reader needs a pushback buffer wider than the default of 1.
        rdr (PushbackReader. (StringReader. s) 64)
        value (try (json/read rdr :eof-error? false :eof-value eof)
                   (catch Exception e
                     (throw (ex-info (str "weave --input is not valid JSON: " (ex-message e))
                                     {:code "pattern/input-invalid"}))))]
    (when (identical? value eof)
      (throw (ex-info "weave --input requires exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    (when-not (identical? (json/read rdr :eof-error? false :eof-value eof) eof)
      (throw (ex-info "weave --input must contain exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    value))

;; The blessed parser's :map flag silently collapses duplicate keys, but old
;; C6e requires duplicate keys within a single --attr priority to fail loudly.
;; The parser guarantees each --attr is followed by a well-formed key=value
;; token, so the flag keys can be recovered from the raw argv to enforce it.
(defn- attr-flag-keys [argv]
  (keep (fn [[flag token]]
          (when (= "--attr" flag)
            (subs token 0 (str/index-of token "="))))
        (partition 2 1 argv)))

(defn- check-attr-duplicates! [argv]
  (when-let [dup (some (fn [[k n]] (when (> n 1) k))
                       (frequencies (attr-flag-keys argv)))]
    (throw (ex-info (str "Duplicate attribute key in --attr: " dup) {:key dup}))))

(defn- attributes->map
  "Coerce a supplied --attributes value into an attribute map, failing loudly on
  anything but a JSON object. A JSON null parses to nil and is rejected here, not
  read as an empty patch; callers guard against an omitted flag before calling."
  [attributes]
  (if (map? attributes)
    (do (doseq [k (keys attributes)]
          (when (str/blank? k)
            (throw (ex-info "--attributes contains a blank attribute key" {:key k}))))
        attributes)
    (throw (ex-info "--attributes must reference a JSON object" {:value attributes}))))

(defn- parse-edges
  "Parse repeatable --edge edge-type:to-id specs into edge maps."
  [edge-specs]
  (mapv (fn [spec]
          (let [idx (str/index-of spec ":")]
            (when (or (nil? idx) (zero? idx) (= idx (dec (count spec))))
              (throw (ex-info "Malformed --edge; expected edge-type:to-id" {:edge spec})))
            {:type (subs spec 0 idx) :to (subs spec (inc idx))}))
        edge-specs))

(defn- run-named-query
  "Resolve a named query, validate params, overlay an optional state filter, and
  invoke the runtime list/ready fn exactly as the socket dispatch does."
  [rt query-fn query-name raw-params state limit]
  (let [query-def (graph/resolve-query rt query-name)
        params (graph/coerce-declared-params query-def raw-params)
        query-def (graph/conjoin-where query-def
                                       (when state [:= :state state])
                                       params)]
    (query-fn rt lean-attribute-byte-floor query-def params limit)))

(defn- run-named-ready-lean [rt query-name raw-params limit]
  (let [query-def (graph/resolve-query rt query-name)
        params (graph/coerce-declared-params query-def raw-params)]
    (weaver/ready-lean rt lean-attribute-byte-floor query-def params limit)))

(defn- query-list-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (graph/referenced-params query-def)})

(defn- query-list-entries [rt]
  (mapv query-list-entry (graph/queries rt)))

;; --- op handlers ------------------------------------------------------------

(defn add-op
  "Create a strand with merged attributes, optional state, and outgoing edges."
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [title state attr attributes edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [merged (merge (when (contains? args :attributes) (attributes->map attributes))
                        (or attr {}))
          edges (parse-edges edge)]
      (weaver/add! rt
                   (cond-> {:title title :attributes merged}
                     (some? state) (assoc :state (validate-generic-state state))
                     (seq edges) (assoc :edges edges))
                   (request-context :add)))))

(defn update-op
  "Patch one strand's title, state, attributes, and outgoing edges.

  Attributes are a JSON Merge Patch: `--attr` string values merge on top of the
  typed `--attributes` object (add precedence), and a JSON null in `--attributes`
  removes that key. Passing no attribute flag leaves the attribute map untouched."
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [id title state attr attributes edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [edges (parse-edges edge)
          attribute-patch? (or (contains? args :attr) (contains? args :attributes))
          patch (cond-> {}
                  (seq edges) (assoc :edges edges)
                  (some? title) (assoc :title title)
                  (some? state) (assoc :state (validate-generic-state state))
                  attribute-patch? (assoc :attributes
                                          (merge (when (contains? args :attributes)
                                                   (attributes->map attributes))
                                                 (or attr {}))))]
      (weaver/update! rt id patch (request-context :update)))))

(defn show-op
  "Return one normalized strand by id."
  [ctx]
  (weaver/show (:op/runtime ctx) (:id (:op/args ctx))))

(defn supersede-op
  "Replace one strand with another and return the supersession result."
  [ctx]
  (let [{:keys [old-id replacement-id]} (:op/args ctx)]
    (weaver/supersede! (:op/runtime ctx) old-id replacement-id (request-context :supersede))))

(defn burn-op
  "Physically delete one strand by id and return the burn summary."
  [ctx]
  (graph/burn-by-ids! (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(defn list-op
  "List lean-projected strands, optionally filtered by lifecycle state and/or a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [state query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (when state (validate-readable-state state))
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt weaver/list-lean query params state limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/list-lean rt lean-attribute-byte-floor (if state [:= :state state] [:exists :id]) {} limit)))))

(defn ready-op
  "List lean-projected ready strands, optionally from the result set of a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-ready-lean rt query params limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/ready-lean rt lean-attribute-byte-floor [:exists :id] {} limit)))))

(defn subgraph-op
  "Return a relation-scoped subgraph rooted at one strand."
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (graph/subgraph (:op/runtime ctx) [root-id]
                        (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(defn weave-op
  "Apply a registered create-only weave pattern to one JSON input value."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (patterns/weave! rt
                     pattern
                     (walk/keywordize-keys (read-single-json input))
                     (request-context :weave))))

(defn query-op
  "Introspect registered named queries: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (json-safe-value (query-list-entries rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (graph/query-explain rt nm))))))

(defn pattern-op
  "Introspect registered weave patterns: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (patterns/patterns rt)
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (patterns/explain rt nm)))))

(defn note-op
  "Append a note to a target strand's memory via the note primitive.

  Its `note/text`/`note/at` content is storage-enforced write-once (SPEC-001.P4);
  the note strand stays open to decorating attrs. Returns the primitive's
  `{:id :target}` shape, where `target` is a projection of the `notes` edge rather
  than a stored attribute."
  [ctx]
  (let [{:keys [id text by round attr]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    ;; note! folds every non-:by/:round opt into decorating attrs, so the
    ;; string-keyed --attr map lands as ordinary strand attrs on the note.
    (notes/note! (:op/runtime ctx) id text (merge (or attr {}) {:by by :round round}))))

(defn notes-op
  "Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round."
  [ctx]
  (let [{:keys [id round]} (:op/args ctx)]
    (notes/notes (:op/runtime ctx) id {:round round})))

(defn vocab-op
  "List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [kind]} (:op/args ctx)]
    (json-safe-value
     (vocab/declarations rt (when kind {:kind (validate-vocab-kind kind)})))))

;; --- arg-specs --------------------------------------------------------------

(def ^:private add-arg-spec
  {:op "add"
   :doc "Create a strand with attributes, lifecycle state, and outgoing edges."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:state {:type :string
                   :doc "Lifecycle state: active (default) or closed."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object of typed bulk attributes (lowest precedence)."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :title :type :string :required? true :doc "Strand title."}]
   :annotations {:use-when ["Minting a new unit of work with its initial attributes, state, and edges in one call."]
                 :failure-modes ["batteries/state-invalid"
                                 "batteries/attr-key-duplicate"
                                 "batteries/edge-malformed"]}})

(def ^:private update-arg-spec
  {:op "update"
   :doc "Update one strand's title, state, attributes, and outgoing edges. Attributes merge-patch, they do not replace the whole map."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:title {:type :string
                   :doc "New strand title."}
           :state {:type :string
                   :doc "Lifecycle state: active or closed (cannot set replaced)."}
           :attr {:type :map
                  :doc "String attribute key=value merge patch; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object merge patch of typed attributes (lowest precedence); a JSON null removes that key, an empty string stores \"\"."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]
   :annotations {:notes ["Omitting every attribute flag leaves the stored attribute map untouched; supply --attr or --attributes to patch it."]
                 :failure-modes ["batteries/state-invalid"
                                 "batteries/attr-key-duplicate"
                                 "batteries/edge-malformed"]}})

(def ^:private show-arg-spec
  {:op "show"
   :doc "Return one strand by id."
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]
   :annotations {:use-when ["Fetching one strand's full normalized shape by id, including its typed attributes."]}})

(def ^:private supersede-arg-spec
  {:op "supersede"
   :doc "Replace one strand with another, marking the old replaced and rewiring dependencies."
   :hook-class :mutating
   :deadline-class :standard
   :positionals [{:name :old-id :type :string :required? true :doc "Strand being replaced."}
                 {:name :replacement-id :type :string :required? true :doc "Replacement strand."}]})

(def ^:private burn-arg-spec
  {:op "burn"
   :doc "Physically delete one strand and its incident edges."
   :hook-class :mutating
   :deadline-class :standard
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private list-arg-spec
  {:op "list"
   :doc "List lean-projected strands, optionally filtered by state and/or a named query."
   :hook-class :read
   :deadline-class :standard
   :flags {:state {:type :string
                   :doc "Filter by lifecycle state: active, closed, or replaced."}
           :query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}
   :annotations {:use-when ["Browsing or filtering strands; combine --state and --query to narrow the set."]
                 :failure-modes ["batteries/state-invalid" "batteries/query-unknown"]}})

(def ^:private ready-arg-spec
  {:op "ready"
   :doc "List lean-projected ready strands, optionally from a named query result set."
   :hook-class :read
   :deadline-class :standard
   :flags {:query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}
   :annotations {:use-when ["Selecting actionable strands whose blocking dependencies are already closed."]
                 :failure-modes ["batteries/query-unknown"]}})

(def ^:private subgraph-arg-spec
  {:op "subgraph"
   :doc "Return a relation-scoped subgraph rooted at a strand."
   :hook-class :read
   :deadline-class :standard
   :flags {:relation {:type :string
                      :doc "Declared acyclic relation type (defaults to parent-of)."}}
   :positionals [{:name :root-id :type :string :required? true :doc "Root strand id."}]})

(def ^:private weave-arg-spec
  {:op "weave"
   :doc "Apply a registered create-only weave pattern to one JSON input value."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:pattern {:type :string
                     :required? true
                     :doc "Registered weave pattern name."}
           :input {:type :string
                   :required? true
                   :doc "Payload reference (e.g. :stdin) to exactly one JSON value for the pattern."}}
   :annotations {:use-when ["Applying a registered create-only pattern to bulk-mint a coordinated strand set from one JSON value."]
                 :failure-modes ["batteries/weave-input-invalid" "batteries/pattern-unknown"]}})

(def ^:private query-arg-spec
  {:op "query"
   :doc "Introspect registered named queries: list all or explain one."
   :annotations {:use-when ["Discovering which named queries the runtime exposes before driving list or ready."]}
   :subcommands {"list" {:doc "List registered named query metadata."
                         :hook-class :read
                         :deadline-class :standard}
                 "explain" {:doc "Explain one registered named query."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Query name."}]
                            :hook-class :read
                            :deadline-class :standard
                            :annotations {:failure-modes ["batteries/query-unknown"]}}}})

(def ^:private pattern-arg-spec
  {:op "pattern"
   :doc "Introspect registered weave patterns: list all or explain one."
   :annotations {:use-when ["Discovering which weave patterns the runtime exposes before calling weave."]}
   :subcommands {"list" {:doc "List registered weave pattern metadata."
                         :hook-class :read
                         :deadline-class :standard}
                 "explain" {:doc "Explain one registered weave pattern."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Pattern name."}]
                            :hook-class :read
                            :deadline-class :standard
                            :annotations {:failure-modes ["batteries/pattern-unknown"]}}}})

(def ^:private note-arg-spec
  {:op "note"
   :doc "Append a note to a target strand's memory; its note/text/note/at content is write-once."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:by {:type :string
                :doc "Author attribution recorded on the note."}
           :round {:type :int
                   :doc "Review round the note belongs to."}
           :attr {:type :map
                  :doc "Decorating attribute key=value on the note strand (e.g. note/kind); repeatable. Values may be payload references."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}
                 {:name :text :type :string :required? true :doc "Note text."}]})

(def ^:private notes-arg-spec
  {:op "notes"
   :doc "Return a target strand's notes in note/at order from every writer."
   :hook-class :read
   :deadline-class :standard
   :flags {:round {:type :int
                   :doc "Filter to notes from one review round."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}]})

(def ^:private vocab-arg-spec
  {:op "vocab"
   :doc "List the declared attribute-namespace and edge vocabulary."
   :hook-class :read
   :deadline-class :standard
   :flags {:kind {:type :string
                  :doc "Narrow to one declaration kind: attr-namespace or edge."}}})

(def ^:private spool-arg-spec
  "The spool op's fractal node tree: every leaf declares both classes in the
  arg-spec (DELTA-Lhc-001.CC2 single-source authoring; no registration-opts
  classes). `status` is the folded-in read leaf that keeps the retired
  `spool-status` op's offline contract verbatim (DELTA-Lhc-001.CC8)."
  {:op "spool"
   :doc "Add and bump validated spool family coordinates. Run `strand spool about` for conventions."
   :annotations {:use-when ["Pinning or advancing an annotated Git spool release in the workspace spools.edn."]}
   :subcommands
   {"about" {:doc "Return spool helper conventions and status semantics."
             :hook-class :read
             :deadline-class :standard}
    "add" {:doc "Add one annotated Git spool release to spools.edn."
           :hook-class :mutating
           :deadline-class :standard
           :flags {:tag {:type :string
                         :doc "Annotated release tag vN; defaults to the highest release."}
                   :lib {:type :string
                         :doc "Consumer family symbol; defaults to the Git URL basename."}}
           :positionals [{:name :git-url
                          :type :string
                          :required? true
                          :doc "Git repository URL."}]
           :annotations {:failure-modes ["batteries/spool-release-unresolved"]}}
    "bump" {:doc "Bump one Git spool family atomically to an annotated release."
            :hook-class :mutating
            :deadline-class :standard
            :flags {:to {:type :string
                         :doc "Target annotated release tag vN; defaults from floors or latest."}}
            :positionals [{:name :family
                           :type :string
                           :required? true
                           :doc "Declared spool family symbol."}]
            :annotations {:failure-modes ["batteries/spool-release-unresolved"]}}
    "status" {:doc "Join declared, overlay, sync, use, pending, and running release state without network access."
              :hook-class :read
              :deadline-class :standard}}})

(def ^:private attributes-return
  {:type :map :extra :json})

(def ^:private strand-return
  {:type :map
   :required {:id :string
              :title :string
              :state :string
              :created_at :string
              :updated_at :string
              :attributes attributes-return}})

(def ^:private edge-return
  {:type :map
   :required {:from_strand_id :string
              :to_strand_id :string
              :edge_type :string
              :attributes attributes-return}})

(def ^:private strand-collection-return
  {:type :collection :items strand-return})

(def ^:private query-list-return
  {:type :map
   :required {:name :string
              :params {:type :collection :items :string}
              :referenced-params {:type :collection :items :string}}})

(def ^:private pattern-key-return
  {:type :map
   :required {:key :string :spec :string :spec-form :string}})

(def ^:private op-returns
  {'add strand-return
   'update strand-return
   'show strand-return
   'supersede
   {:type :map
    :required {:old {:type :map :required {:before strand-return :after strand-return}}
               :replacement-id :string
               :supersedes-edge edge-return
               :rewired-dependencies
               {:type :collection
                :items {:type :map
                        :required {:from :string :old-to :string :new-to :string :type :string
                                   :deleted-edge edge-return :edge edge-return}}}}}
   'burn {:type :map
          :required {:burned {:type :collection :items :string}
                     :count :integer}}
   'list strand-collection-return
   'ready strand-collection-return
   'subgraph {:type :map
              :required {:root_ids {:type :collection :items :string}
                         :strands strand-collection-return
                         :edges {:type :collection :items edge-return}}}
   'weave {:type :map
           :required {:created strand-collection-return
                      :refs {:type :map :extra :string}}}
   'query {:subcommands
           {"list" {:type :collection :items query-list-return}
            "explain" {:type :map
                       :required {:name :string
                                  :operation :string
                                  :params {:type :collection :items :string}
                                  :referenced-params {:type :collection :items :string}
                                  :where :json
                                  :definition :json
                                  :where-form :string
                                  :definition-form :string
                                  :summary :string}}}}
   'pattern {:subcommands
             {"list" {:type :collection
                      :items {:type :map
                              :required {:name :string :fn :string :input-spec :string}
                              :optional {:doc :string}}}
              "explain" {:type :map
                         :required {:name :string :operation :string :fn :string :input-spec :string
                                    :spec-form :string :summary :string}
                         :optional {:doc :string
                                    :required {:type :collection :items pattern-key-return}
                                    :optional {:type :collection :items pattern-key-return}}}}}
   'note {:type :map :required {:id :string :target :string}}
   'notes {:type :collection
           :items {:type :map
                   :required {:id :string :note :string :at :string}
                   :optional {:by :string :round :integer}}}
   'vocab {:type :collection
           :items {:type :map
                   :required {:kind :string :name :string :owner :string}
                   :optional {:keys {:type :collection :items :string}
                              :doc :string
                              :family :string
                              :direction :string
                              :declared-acyclic? :boolean}}}
   'spool {:subcommands
           {"about" {:type :map
                     :required {:operation :string
                                :purpose :string
                                :commands {:type :collection
                                           :items {:type :map
                                                   :required {:verb :string :behavior :string}}}
                                :conventions {:type :collection :items :string}}}
            "add" {:type :map
                   :required {:operation :string :status :string :family :string
                              :entry :json :requirements :json}}
            "bump" {:type :map
                    :required {:operation :string :status :string :family :string :old :json :new :json
                               :compare-url [:nullable :string] :requirements :json}}
            "status" {:type :map
                      :required {:operation :string :families :json :requirements :json
                                 :pending-generation :json :release-marker :json}}}}})

;; --- op-level about/prime prose ---------------------------------------------

(def ^:private add-meta
  "Cross-verb narrative for `add`, projected by the `about`/`prime` meta-verbs
  (DELTA-Dtf-002.CC4). Kept off the node: it frames add against its sibling verbs
  rather than restating any node-derivable flag."
  {:about (format-alpha/reflow
           "|add is the create verb of the batteries strand surface: it mints one
            |strand and hands back its generated id. update patches that strand
            |afterward, supersede replaces it wholesale, and burn deletes it —
            |add is where a unit of work first enters the graph.")
   :prime (format-alpha/reflow
           "|Reach for add the moment a unit of work appears. Prefer --edge over
            |free-text references so later readiness and subgraph traversal can
            |follow the structural links you record now.")})

(def ^:private weave-meta
  "Cross-verb narrative for `weave` (DELTA-Dtf-002.CC4)."
  {:about (format-alpha/reflow
           "|weave applies a registered create-only pattern to one JSON value,
            |minting a coordinated set of strands and returning their refs. It is
            |the bulk-creation counterpart to add's single-strand mint, and the
            |pattern op explains which patterns a runtime exposes.")
   :prime (format-alpha/reflow
           "|Reach for weave when one input should fan out into several linked
            |strands under a reviewed pattern. Run `strand pattern list` first to
            |see the registered patterns and their input specs.")})

;; --- batteries-owned glossary outcomes --------------------------------------

(def ^:private batteries-glossary
  "Batteries-owned named failure outcomes (DELTA-Dtf-002.CC5).

  Reconciled by the module before help resolves an op whose `:annotations` `failure-modes`
  reference them — the load-order contract (DELTA-Dtf-002.CC7). Each name is
  qualified and stable; a changed meaning takes a new name, never a redefinition."
  [{:name "batteries/state-invalid"
    :definition "A mutation named a lifecycle state outside active|closed; list also permits replaced."}
   {:name "batteries/attr-key-duplicate"
    :definition "Two --attr flags in one invocation set the same key at the same precedence."}
   {:name "batteries/edge-malformed"
    :definition "An --edge token is not the required edge-type:to-id shape."}
   {:name "batteries/query-unknown"
    :definition "A --query names no registered query, or a --param names an undeclared query parameter."}
   {:name "batteries/pattern-unknown"
    :definition "A named weave pattern is not registered in the runtime."}
   {:name "batteries/spool-release-unresolved"
    :definition "No annotated vN release tag resolves for the requested spool coordinate."}
   {:name "batteries/weave-input-invalid"
    :definition "weave --input did not carry exactly one JSON value."}])

;; --- reference default help renderer (the forcing function) -----------------
;;
;; DELTA-Dtf-002.CC1 / DELTA-Dtf-003.D1: batteries exports ONE recursive renderer
;; over the uniform fractal node (DELTA-Dtf-001.CC2). `render-node` is the whole
;; point: an op root, a verb child, and any deeper descendant render through its
;; single body with no per-level branch, and the only recursion over nodes is its
;; own closing tail over `:children`. Everything else here — the envelope headers,
;; the leaf flag/positional lines, the returns pretty-printer — is non-recursive
;; framing around that one uniform recursion.

(defn- indent
  "A two-space indent string for nesting `depth`."
  [depth]
  (str/join (repeat depth "  ")))

(defn- source-label
  "Render the op-wide `source` pointer (DELTA-Dtf-002.CC2) as `file:line`, or the
  best-effort `unavailable`."
  [source]
  (if source (str (:file source) ":" (:line source)) "unavailable"))

(defn- bullet-lines
  "Render a labelled bullet block for a string-array annotation, or nil when empty."
  [depth label items]
  (when (seq items)
    (cons (str (indent depth) label ":")
          (map #(str (indent (inc depth)) "- " %) items))))

(defn- flag-line
  "Render one declared flag as a single readable line."
  [depth {:keys [flag type required repeat parse doc]}]
  (str (indent depth) flag " <" type ">"
       (when required " (required)")
       (when repeat " (repeatable)")
       (when parse (str " {parse " parse "}"))
       (when (seq doc) (str "  " doc))))

(defn- positional-line
  "Render one declared positional as a single readable line."
  [depth {:keys [name type required variadic parse doc]}]
  (str (indent depth) "<" name "> <" type ">"
       (when required " (required)")
       (when variadic " (variadic)")
       (when parse (str " {parse " parse "}"))
       (when (seq doc) (str "  " doc))))

(defn- shape-lines
  "Render one JSON-safe return-shape value as indented readable lines.

  A generic recursion over the return-shape `explain` data (SPEC-003.C60b),
  distinct from the fractal-node recursion — it descends return shapes, never
  help nodes, so it does not touch the node-uniformity invariant."
  [depth value]
  (cond
    (map? value)
    (if (empty? value)
      [(str (indent depth) "{}")]
      (mapcat (fn [[k v]]
                (let [label (if (keyword? k) (name k) (str k))]
                  (cond
                    (and (coll? v) (empty? v))
                    [(str (indent depth) label ": " (if (map? v) "{}" "[]"))]
                    (coll? v)
                    (cons (str (indent depth) label ":") (shape-lines (inc depth) v))
                    :else
                    [(str (indent depth) label ": " v)])))
              value))

    (sequential? value)
    (if (some coll? value)
      (mapcat #(shape-lines depth %) value)
      [(str (indent depth) "[" (str/join ", " value) "]")])

    :else
    [(str (indent depth) value)]))

(defn- render-node
  "THE recursive renderer over the uniform fractal node (DELTA-Dtf-001.CC2).

  One body renders every level: an op root, a verb child, and any deeper
  descendant are the same shape, and the sole node recursion is the closing tail
  over `:children`. No branch keys off the node's depth or kind — that uniformity
  is the schema's forcing function (DELTA-Dtf-003.D1). If a level ever needed its
  own case, the schema would be wrong, not this renderer."
  [depth {:keys [name doc invocation returns hook-class deadline-class
                 use-when notes failure-modes children]}]
  (let [field (inc depth)
        entry (inc field)]
    (concat
     [(str (indent depth) name (when (seq doc) (str " — " doc)))
      (str (indent field) "invocation: " (:mode invocation))]
     ;; classes render only where they exist: invocable leaf nodes
     ;; (DELTA-Lhc-003.CC1); interior nodes carry null and stay silent.
     (when hook-class
       [(str (indent field) "hook-class: " hook-class "   deadline: " deadline-class)])
     (when (seq (:flags invocation))
       (cons (str (indent field) "flags:")
             (map #(flag-line entry %) (:flags invocation))))
     (when (seq (:positionals invocation))
       (cons (str (indent field) "positionals:")
             (map #(positional-line entry %) (:positionals invocation))))
     (when returns
       (cons (str (indent field) "returns:")
             (shape-lines entry returns)))
     (bullet-lines field "use-when" use-when)
     (bullet-lines field "notes" notes)
     (bullet-lines field "failure-modes" failure-modes)
     (mapcat #(render-node field %) children))))

(defn- operation-lines
  "Render the op-wide envelope facts (DELTA-Dtf-001.CC1) that stay off the node;
  hook/deadline classes are per-leaf node facts, not op-wide ones
  (DELTA-Lhc-003.CC1)."
  [operation source]
  [(str "operation: " (:name operation) "  [" (:provenance operation) "]")
   (str (indent 1) "streaming: " (:stream? operation)
        "   raw-envelope: " (:raw-envelope operation))
   (str (indent 1) "source:    " (source-label source))])

(defn- glossary-lines
  "Render the referenced-term glossary closure (DELTA-Dtf-002.CC5), or nil when
  the returned subtree references no outcomes."
  [glossary]
  (when (seq glossary)
    (cons "glossary:"
          (map (fn [[name definition]] (str (indent 1) name " — " definition))
               (sort glossary)))))

(defn- render-detail
  "Render a detail help envelope `{schema-version, operation, source, glossary,
  node}` (DELTA-Dtf-001.CC1) as text."
  [{:keys [schema-version operation source glossary node]}]
  (str/join
   "\n"
   (concat [(str "strand help — schema v" schema-version) ""]
           (operation-lines operation source)
           [""]
           (when-let [gloss (glossary-lines glossary)] (concat gloss [""]))
           (render-node 0 node))))

(defn- render-catalog
  "Render the versioned no-arg catalog `{schema-version, ops[]}`
  (DELTA-Dtf-001.CC3) as text.

  Each shallow per-op envelope's summary node renders through the SAME uniform
  `render-node`, so the catalog reuses the node contract unchanged."
  [{:keys [schema-version ops]}]
  (str/join
   "\n"
   (concat [(str "strand help — schema v" schema-version " — " (count ops) " ops") ""]
           (mapcat (fn [{:keys [source node]}]
                     (concat (render-node 0 node)
                             [(str (indent 1) "source: " (source-label source)) ""]))
                   ops))))

(defn default-help-transform
  "Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

  The batteries reference default help transform (DELTA-Dtf-002.CC1): a full
  envelope → the string the CLI relays verbatim. It is EXPORTED for trusted
  `init.clj` election through `register-default-help-transform!` (Task 8) and is
  deliberately not auto-registered by the module, so a fresh world keeps the
  raw-JSON floor (DELTA-Dtf-002.D1).

  Both members of the one help-schema family render through the single uniform
  node renderer (`render-node`): the detail envelope carrying `node`, and the
  no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only
  branch is which envelope family this is — an envelope-shape choice, never a
  per-node-level one, so the recursive node renderer stays uniform at every depth
  (the forcing-function invariant, DELTA-Dtf-003.D1)."
  [envelope]
  (if (contains? envelope :ops)
    (render-catalog envelope)
    (render-detail envelope)))

;; --- registration -----------------------------------------------------------

(def ^:private op-registrations
  "Each shipped op: [op-name arg-spec handler-symbol op-meta?].

  The optional trailing `op-meta` map carries extra registration metadata merged
  over the derived defaults — today the `:about`/`:prime` prose (DELTA-Dtf-002.CC4)
  a few ops declare. Every row carries its classes single-source on arg-spec
  leaves (DELTA-Lhc-002.CC1)."
  [['add add-arg-spec 'skein.spools.batteries/add-op add-meta]
   ['update update-arg-spec 'skein.spools.batteries/update-op]
   ['show show-arg-spec 'skein.spools.batteries/show-op]
   ['supersede supersede-arg-spec 'skein.spools.batteries/supersede-op]
   ['burn burn-arg-spec 'skein.spools.batteries/burn-op]
   ['list list-arg-spec 'skein.spools.batteries/list-op]
   ['ready ready-arg-spec 'skein.spools.batteries/ready-op]
   ['subgraph subgraph-arg-spec 'skein.spools.batteries/subgraph-op]
   ['weave weave-arg-spec 'skein.spools.batteries/weave-op weave-meta]
   ['query query-arg-spec 'skein.spools.batteries/query-op]
   ['pattern pattern-arg-spec 'skein.spools.batteries/pattern-op]
   ['note note-arg-spec 'skein.spools.batteries/note-op]
   ['notes notes-arg-spec 'skein.spools.batteries/notes-op]
   ['vocab vocab-arg-spec 'skein.spools.batteries/vocab-op]
   ['spool spool-arg-spec 'skein.spools.batteries/spool-op]])

(defn- op-contribution-entry
  "Assemble one op registry entry in `register-op!`'s canonical shape.

  A blessed spool may not reach the weaver's internal op-entry plumbing
  (SPEC-003.C19a), so this mirrors the assembly the eager `register-op!` path
  performs (a peer of `skein.macros.ops/declaration-entry`): a string `:name`,
  the handler `:fn`, provenance, and authored arg-spec node metadata, keyed by the
  canonical string op name so the effective registry stays string-keyed."
  [op-name arg-spec handler op-meta]
  (let [opts (merge {:doc (:doc arg-spec)
                     :arg-spec arg-spec
                     :returns (get op-returns op-name)}
                    op-meta)]
    (cond-> {:name (name op-name)
             :fn handler
             :provenance (symbol (namespace handler))}
      (:stream? opts) (assoc :stream? true)
      (:doc opts) (assoc :doc (:doc opts))
      (some? (:arg-spec opts)) (assoc :arg-spec (:arg-spec opts))
      (contains? opts :returns) (assoc :returns (:returns opts))
      (:about opts) (assoc :about (:about opts))
      (:prime opts) (assoc :prime (:prime opts))
      (some? (:annotations opts)) (assoc :annotations (:annotations opts)))))

(defn contribute
  "Return batteries' complete stable-owner CLI operation contribution.

  Workspace startup loads the namespace through the seeded approved
  `skein.spools/batteries` source root and a module guarded by that root.
  Deleting the seeded entry is the supported opt-out. This function supplies
  the declarative operation partition. Each entry is assembled into the
  canonical `::op-entry` shape (string key, `:name`, `:fn`, provenance, and
  arg-spec node metadata) exactly as `register-op!` would, so the module
  publication path is equivalent to direct registration. Batteries ships no
  `help` op of its own — the built-in help op stays effective and batteries
  elects only the reference help transform (DELTA-Dtf-002.D1) — so the
  partition declares no overrides over the lower defaults layer."
  [_ctx]
  {:ops {:entries (into {}
                        (map (fn [[op-name arg-spec handler op-meta]]
                               [(name op-name)
                                (op-contribution-entry op-name arg-spec handler op-meta)]))
                        op-registrations)}})

(defn reconcile
  "Reconcile batteries' owned glossary outcomes per the module contract.

  The declarative operation partition publishes through `contribute`; the
  glossary outcomes its ops' `failure-modes` reference are batteries-owned
  runtime resources (not declaration data), so an applied contribution seeds
  them here rather than during direct registration (DELTA-OlrRepl-001.CC6).
  Module publication does not run the direct-registration glossary-ref check,
  so publishing before this reconcile is safe; help resolves the
  referenced-term closure against the seeded outcomes. The removal branch is
  deliberately effect-free: the glossary API ships register/replace and no
  unregister — outcomes are process-lifetime seeds (SPEC-004.C46b,
  DELTA-Itr-001) — so there is nothing to retract, and re-registering on
  removal is the defect the contract names. Any other status is a direct-call
  error and fails loudly."
  [{:keys [runtime] :as ctx}]
  (let [status (get-in ctx [:module/contribution :status])]
    (case status
      :applied (do (doseq [outcome batteries-glossary]
                     (glossary/register-glossary-outcome!
                      runtime (assoc outcome :owner 'skein.spools.batteries)))
                   {:reconciled :batteries-glossary})
      :removed {:reconciled :removed}
      (throw (ex-info "Unsupported module contribution status"
                      {:status status
                       :allowed #{:applied :removed}
                       :module/key (:module/key ctx)
                       :reconciler 'skein.spools.batteries/reconcile})))))

(def spool
  "Entry-point declaration for the batteries spool (PROP-Dsp-001 `def spool`
  convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'skein.spools.batteries :spools [...]}`) and never
  mirrors the pair. Unqualified symbols resolve against this namespace; fn
  values are rejected (ADR-002.O1)."
  {:contribute 'contribute
   :reconcile 'reconcile})
