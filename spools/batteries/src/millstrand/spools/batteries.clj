(ns millstrand.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries declares the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — through
  `millstrand.api.millstrand.alpha/defop`. Their
  `:arg-spec` is parsed by `millstrand.api.cli.alpha`. Each op delegates to the same
  `millstrand.api.*.alpha` calls the JSON socket dispatch uses and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  `defop` declarations are the durable, owner-complete source for this surface.
  Explicit-runtime registration functions are the live code and test seam, and
  `millstrand.repl` supplies the same verbs with the runtime implied for an in-process
  session. Evaluating an authoring form outside module collection defines its Var
  but publishes no op.

  Production loading follows the ordinary approved-spool path. `mill init` seeds
  `millstrand.spools/batteries {:millstrand/source-root \"spools/batteries\"}` in
  `spools.edn`, and its startup module names that root in `:spools`. Deleting
  the seeded entry is the supported opt-out; a workspace without it has no
  batteries ops.

  Ops adopt the discovery-tier pattern (DELTA-Dtf-003.CC2): their arg-specs drive
  help, and where it adds value they carry closed `:annotations` sub-maps
  (`use-when`/`notes`/`failure-modes`) and op-level `:about`/`:prime` prose.
  `failure-modes` reference the batteries-owned glossary outcomes seeded by the
  batteries module (the load-order contract, DELTA-Dtf-002.CC7).

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
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.notes.alpha :as notes]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.runtime.alpha :as runtime-api]
            [millstrand.api.runtime.glossary.alpha :as glossary]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.vocab.alpha :as vocab]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file FileVisitResult Files LinkOption SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private lean-attribute-byte-floor 1024)
(def ^:private default-read-limit 500)
(def ^:private read-limit-state-version 1)
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private release-tag-pattern #"v([1-9][0-9]*)")
(def ^:private await-default-timeout-secs 1800)
(def ^:private await-poll-ms 1000)

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
                 :opt [:millstrand/min]
                 :opt-un [::requires])
         #(exact-keys? #{:spool/format :millstrand/min :roots :requires}
                       (merge {:millstrand/min nil :requires nil} %))))

(s/def ::status #{:inserted :updated})
(s/def ::operation ::non-blank-string)
(s/def ::family symbol?)
(s/def ::entry ::runtime-api/spool-entry)
(s/def ::requirements
  #(s/valid? ::runtime-api/declared-result {:families {} :requirements %}))
(s/def ::spool-add-result
  (s/and (s/keys :req-un [::operation ::status ::family ::entry ::requirements])
         #(exact-keys? #{:operation :status :family :entry :requirements} %)))
(s/def ::tag (s/nilable ::runtime-api/release-marker-claim))
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
  (s/and #(exact-spool-args? #{:subcommand :git-url} #{:tag :lib :unversioned-head} %)
         #(= ["add"] (:subcommand %))
         #(s/valid? ::non-blank-string (:git-url %))
         #(or (nil? (:tag %)) (s/valid? ::non-blank-string (:tag %)))
         #(or (nil? (:lib %)) (s/valid? ::non-blank-string (:lib %)))
         #(or (nil? (:unversioned-head %)) (boolean? (:unversioned-head %)))
         #(not (and (:tag %) (:unversioned-head %)))))
(s/def ::spool-bump-args
  (s/and #(exact-spool-args? #{:subcommand :family :latest} #{} %)
         #(= ["bump"] (:subcommand %))
         #(s/valid? ::non-blank-string (:family %))
         #(s/valid? ::non-blank-string (:latest %))))
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

(defn- default-branch-head [git-url]
  (let [output (checked-git nil "ls-remote" git-url "HEAD")
        matches (keep (fn [line]
                        (when-let [[_ sha] (re-matches #"([0-9a-f]{40})\s+HEAD" line)]
                          sha))
                      (str/split-lines output))]
    (when-not (= 1 (count matches))
      (throw (ex-info
              "Remote default branch HEAD did not resolve to one exact lowercase 40-character Git SHA"
              {:git-url git-url
               :reason :invalid-default-branch-head
               :output (str/trim output)})))
    (first matches)))

(defn- throw-manifest-git-failure! [git-url sha args result]
  (throw (ex-info "Git command failed while reading advisory spool.edn"
                  {:git-url git-url
                   :git/sha sha
                   :argv (vec (cons "git" args))
                   :exit (:exit result)
                   :stderr (str/trim (:stderr result))})))

(defn- manifest-at [git-url sha]
  (let [tmp (.toFile (Files/createTempDirectory "millstrand-spool-manifest-"
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

(defn- fetch-commit
  "Fetch `sha` from `git-url`, failing when the remote cannot provide it."
  [git-url sha]
  (let [tmp (.toFile (Files/createTempDirectory "millstrand-spool-commit-"
                                                (make-array FileAttribute 0)))]
    (try
      (checked-git tmp "init" "--quiet")
      (checked-git tmp "fetch" "--quiet" "--depth=1" git-url sha)
      sha
      (finally
        (delete-tree! tmp)))))

(def ^:private default-git-client
  {:ls-remote ls-remote
   :default-branch-head default-branch-head
   :fetch-commit fetch-commit
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

(defn- require-latest-mode [latest]
  (when-not (contains? #{"tag" "sha"} latest)
    (throw (ex-info
            "--latest must be either tag or sha"
            {:latest latest
             :reason :invalid-latest-mode
             :accepted ["tag" "sha"]})))
  latest)

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
           :git/sha sha
           :roots (manifest-roots manifest lib)}
    tag (assoc :git/tag tag)
    (contains? manifest :requires) (assoc :requires (:requires manifest))
    (contains? manifest :millstrand/min) (assoc :millstrand/min (:millstrand/min manifest))))

(declare unversioned-head-target)

(defn- add-spool-op [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [git-url tag lib unversioned-head]} (:op/args ctx)
        requested-lib (some-> lib symbol)
        lib (or requested-lib (url-basename git-url))
        client (git-client rt)
        {:keys [tag sha]} (if unversioned-head
                            (unversioned-head-target git-url client)
                            (select-tag ((:ls-remote client) git-url) tag))
        manifest (read-manifest git-url sha ((:manifest-at client) git-url sha))
        _ (require-matching-manifest-lib! manifest requested-lib)
        entry (family-entry git-url tag sha manifest lib)
        write (runtime-api/upsert-spool-entry! rt lib entry)
        requirements (:requirements (runtime-api/declared rt))]
    {:status (:status write)
     :family lib
     :entry entry
     :requirements requirements}))

(defn- latest-tag-target [git-url client]
  (let [tags (peeled-tags ((:ls-remote client) git-url))]
    (if-let [tag (last (keys tags))]
      {:tag tag :sha (get tags tag)}
      (throw (ex-info
              (str "Repository has no annotated release tags matching vN where N is a positive "
                   "integer; available annotated tags: none found")
              (merge {:reason :no-release-tags}
                     (release-tag-diagnostics (keys tags))))))))

(defn- latest-sha-target [git-url client]
  (let [sha ((:default-branch-head client) git-url)]
    ((:fetch-commit client) git-url sha)
    {:tag nil :sha sha}))

(defn- unversioned-head-target [git-url client]
  (let [tags ((:ls-remote client) git-url)]
    (when (re-find #"(?m)^[0-9a-fA-F]{40}\s+refs/tags/" tags)
      (throw (ex-info "Repository has tags; use --tag instead"
                      {:git-url git-url :reason :repository-tagged})))
    (latest-sha-target git-url client)))

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
        {:keys [family latest]} (:op/args ctx)
        family (symbol family)
        declared (runtime-api/declared rt)
        old-entry (get-in declared [:families family :declared])]
    (when-not old-entry
      (throw (ex-info "Spool family is not declared" {:family family})))
    (when-not (:git/url old-entry)
      (throw (ex-info "Only Git spool families can be bumped" {:family family :entry old-entry})))
    (let [client (git-client rt)
          latest (require-latest-mode latest)
          target (case latest
                   "tag" (latest-tag-target (:git/url old-entry) client)
                   "sha" (latest-sha-target (:git/url old-entry) client))
          entry (cond-> (assoc old-entry :git/sha (:sha target))
                  (= "sha" latest) (dissoc :git/tag)
                  (= "tag" latest) (assoc :git/tag (:tag target)))
          write (runtime-api/upsert-spool-entry! rt family entry)]
      {:status (:status write)
       :family family
       :old {:tag (:git/tag old-entry) :sha (:git/sha old-entry)}
       :new target
       :compare-url (compare-url (:git/url old-entry) (:git/sha old-entry) (:sha target))
       :requirements (:requirements (runtime-api/declared rt))})))

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
             "|Add annotated releases or unversioned default-branch HEAD Git
              |coordinates, and bump existing coordinates through the validated,
              |comment-preserving runtime write verb. Status joins declared,
              |overlay, sync, use, pending-generation, and release-marker state.")
   :commands
   [{:verb "add"
     :behavior (format-alpha/reflow
                "|By default, queries remote annotated tags and pins the selected
                 |peeled commit. `--unversioned-head` instead requires no remote
                 |tags, resolves and fetches default-branch HEAD, and writes a
                 |SHA-only coordinate. Both read advisory spool.edn at the pin.")}
    {:verb "bump"
     :behavior (format-alpha/reflow
                "|Requires an explicit latest mode. `tag` selects the highest
                 |annotated vN release; `sha` resolves and fetches the remote
                 |default branch HEAD before atomically rewriting the coordinate.")}
    {:verb "status"
     :behavior (format-alpha/reflow
                "|Reads runtime and workspace state only. It performs no network,
                 |file write, sync, reload, or other adoption action.")}]
   :conventions
   [(format-alpha/reflow
     "|Add and `--latest tag` resolve only peeled refs/tags/vN^{} commits.
      |`--unversioned-head` and `--latest sha` resolve the remote default branch
      |HEAD and require its exact 40-character lowercase commit to be fetchable.
      |Unversioned add rejects every repository with tags; v0 and lightweight
      |tags are refused for release-tag targets.")
    (format-alpha/reflow
     "|An optional producer spool.edn supplies roots and floors. With it,
      |--lib must match one declared root symbol. Without it, add uses one root
      |at . under the URL basename; --lib confirms or overrides that symbol.")
    (format-alpha/reflow
     "|Status performs no remote Git calls. It reports the running runtime's
      |declared and adopted state without attempting sync or reload.")]})

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

(defn- await-options
  [{:keys [query param min-count max-count timeout-secs]}]
  (when (str/blank? query)
    (throw (ex-info "await --query requires a non-empty name" {})))
  (when (and (nil? min-count) (nil? max-count))
    (throw (ex-info "await requires --min-count or --max-count" {})))
  (doseq [[flag value] [["--min-count" min-count] ["--max-count" max-count]]
          :when (some? value)]
    (when (neg? value)
      (throw (ex-info (str "await " flag " must be non-negative") {flag value}))))
  (when (and (some? min-count) (some? max-count) (> min-count max-count))
    (throw (ex-info "await --min-count must not exceed --max-count"
                    {:min-count min-count :max-count max-count})))
  (when (and (zero? (or min-count -1)) (nil? max-count))
    (throw (ex-info "await --min-count 0 alone is vacuous" {:min-count min-count})))
  (let [timeout-secs (or timeout-secs await-default-timeout-secs)]
    (when (or (neg? timeout-secs) (> timeout-secs (quot Long/MAX_VALUE 1000)))
      (throw (ex-info "await --timeout-secs must be non-negative and safely bounded"
                      {:timeout-secs timeout-secs})))
    {:query query
     :raw-params (or param {})
     :min-count min-count
     :max-count max-count
     :timeout-secs timeout-secs}))

(defn- await-query
  [rt {:keys [query raw-params] :as opts}]
  (let [query-def (graph/resolve-query rt query)
        params (graph/coerce-declared-params query-def raw-params)]
    (assoc opts :query-def query-def :params params)))

(defn- await-probe
  [rt {:keys [query-def params min-count max-count]}]
  (let [limit (long (if (some? max-count) (inc max-count) min-count))
        count (count (weaver/list-lean rt lean-attribute-byte-floor query-def params limit
                                       {:clamp? true}))]
    {:count count
     :satisfied? (and (or (nil? min-count) (<= min-count count))
                      (or (nil? max-count) (<= count max-count)))}))

(defn- await-result
  [rt started {:keys [query min-count max-count]} reason probe]
  {:operation "await"
   :query query
   :reason reason
   :count (:count probe)
   :min_count min-count
   :max_count max-count
   :elapsed_ms (.toMillis (Duration/between started (runtime-api/now rt)))})

(defn- query-list-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (graph/referenced-params query-def)})

(defn- query-list-entries [rt]
  (mapv query-list-entry (graph/queries rt)))

;; --- op handlers ------------------------------------------------------------

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

(def ^:private await-arg-spec
  {:op "await"
   :doc "Block until a named query's result count is inside an inclusive band."
   :hook-class :read
   :deadline-class :unbounded
   :flags {:query {:type :string :doc "Weaver-registered named query."}
           :param {:type :map :doc "Named-query parameter key=value; repeatable."}
           :min-count {:type :int :doc "Inclusive minimum result count."}
           :max-count {:type :int :doc "Inclusive maximum result count."}
           :timeout-secs
           {:type :int
            :doc (format-alpha/reflow
                  "|Seconds to block before returning the timeout reason (default
                   |1800). Cap waits at about 50 minutes and re-issue them so an
                   |idle provider prompt cache does not expire.")}}
   :annotations
   {:notes [(format-alpha/reflow
             "|Await observes query cardinality, not strand completion. Closing,
              |replacing, or burning a strand all remove it from an active-set
              |query.")]
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
   :annotations {:use-when ["Pinning an annotated Git release, an untagged default-branch HEAD, or advancing a Git spool coordinate in workspace spools.edn."]}
   :subcommands
   {"about" {:doc "Return spool helper conventions and status semantics."
             :hook-class :read
             :deadline-class :standard}
    "add" {:doc "Add one annotated release or an unversioned default-branch HEAD Git spool coordinate to spools.edn."
           :hook-class :mutating
           :deadline-class :standard
           :flags {:tag {:type :string
                         :doc "Annotated release tag vN; defaults to the highest release. Cannot be used with --unversioned-head."}
                   :unversioned-head {:type :boolean
                                      :doc "Pin the default-branch HEAD only when the repository has no tags. Cannot be used with --tag."}
                   :lib {:type :string
                         :doc "Consumer family symbol; defaults to the Git URL basename."}}
           :positionals [{:name :git-url
                          :type :string
                          :required? true
                          :doc "Git repository URL."}]
           :annotations {:failure-modes ["batteries/spool-release-unresolved"]}}
    "bump" {:doc "Bump one Git spool family atomically to its latest annotated release or default-branch Git SHA."
            :hook-class :mutating
            :deadline-class :standard
            :flags {:latest {:type :string
                             :required? true
                             :doc "Latest coordinate mode: tag for the highest annotated vN, or sha for the remote default branch HEAD."}}
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
   'await {:type :map
           :required {:operation :string
                      :query :string
                      :reason :string
                      :count :integer
                      :min_count [:nullable :integer]
                      :max_count [:nullable :integer]
                      :elapsed_ms :integer}}
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
                                    ;; the projection node grammar is owned by
                                    ;; millstrand.api.spec.alpha, so these carry as json
                                    :contract :json :template :json :spec-forms :json}
                         :optional {:doc :string}}}}
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

(defn- bullet-lines
  "Render a labelled bullet block for a string-array annotation, or nil when empty."
  [depth label items]
  (when (seq items)
    (cons (str (indent depth) label ":")
          (map #(str (indent (inc depth)) "- " %) items))))

(defn- spaced
  "Prepend a blank line to a non-empty rendered section."
  [lines]
  (when (seq lines) (cons "" lines)))

(defn- arg-markers
  "Render the shared trailing markers of a flag or positional line."
  [{:keys [required repeat variadic parse spec doc]}]
  (str (when required " (required)")
       (when repeat " (repeatable)")
       (when variadic " (variadic)")
       (when parse (str " {parse " parse "}"))
       (when spec (str " {spec " spec "}"))
       (when (seq doc) (str "  " doc))))

(declare shape-lines)

(defn- inline-shape
  "Render a nested return-shape value compactly on one line."
  [value]
  (cond
    (map? value)
    (str "{" (str/join ", " (map (fn [[k v]]
                                   (str (if (keyword? k) (name k) k)
                                        ": " (inline-shape v)))
                                 value)) "}")

    (sequential? value)
    (str "[" (str/join ", " (map inline-shape value)) "]")

    :else
    (str value)))

(defn- template-lines
  "Render a declared-spec copyable template block under its arg line, or nil."
  [depth template]
  (when (some? template)
    (cons (str (indent depth) "template:")
          (shape-lines (inc depth) template))))

(defn- flag-lines
  "Render one declared flag: its line, then any declared-spec template block.

  A presence `:boolean` flag takes no value on the command line, so its line
  renders the bare token with no value placeholder; every value-consuming type
  keeps its `<type>` form."
  [depth {:keys [flag type template] :as entry}]
  (cons (str (indent depth) flag
             (when-not (= type "boolean") (str " <" type ">"))
             (arg-markers entry))
        (template-lines (inc depth) template)))

(defn- positional-lines
  "Render one declared positional: its line, then any declared-spec template
  block."
  [depth {:keys [name type template] :as entry}]
  (cons (str (indent depth) "<" name "> <" type ">" (arg-markers entry))
        (template-lines (inc depth) template)))

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
      (map (fn [[k v]]
             (str (indent depth)
                  (if (keyword? k) (name k) k)
                  ": " (inline-shape v)))
           value))

    (sequential? value)
    (if (some coll? value)
      (mapcat #(shape-lines depth %) value)
      [(str (indent depth) "[" (str/join ", " value) "]")])

    :else
    [(str (indent depth) value)]))

(defn- json-help-command
  "Return the raw-help command selecting `jq-path` for one help node path."
  [path jq-path]
  (str "strand help --json " (str/join " " path) " | jq '" jq-path "'"))

(defn- render-node
  "THE recursive renderer over the uniform fractal node (DELTA-Dtf-001.CC2).

  One body renders every level: an op root, a verb child, and any deeper
  descendant are the same shape, and the sole node recursion is the closing tail
  over `:children`. No branch keys off the node's depth or kind — that uniformity
  is the schema's forcing function (DELTA-Dtf-003.D1). If a level ever needed its
  own case, the schema would be wrong, not this renderer."
  [path depth {:keys [name doc invocation returns hook-class deadline-class
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
     (spaced
      (when (seq (:flags invocation))
        (cons (str (indent field) "flags:")
              (mapcat #(flag-lines entry %) (:flags invocation)))))
     (spaced
      (when (seq (:positionals invocation))
        (cons (str (indent field) "positionals:")
              (mapcat #(positional-lines entry %) (:positionals invocation)))))
     (spaced
      (when returns
        [(str (indent field) "returns: " (json-help-command path ".node.returns"))]))
     (spaced (bullet-lines field "use-when" use-when))
     (spaced (bullet-lines field "notes" notes))
     (spaced (bullet-lines field "failure-modes-glossary" failure-modes))
     (mapcat (fn [{child-name :name :as child}]
               (cons "" (render-node (conj path child-name) field child)))
             children))))

(defn- inspect-lines
  "Render commands for op-wide details omitted from friendly help."
  [path glossary]
  (cond-> ["inspect:"
           (str (indent 1) "operation: " (json-help-command path ".operation"))]
    (seq glossary)
    (conj (str (indent 1) "glossary:  " (json-help-command path ".glossary")))))

(defn- render-detail
  "Render a detail help envelope `{schema-version, operation, source, glossary,
  node}` (DELTA-Dtf-001.CC1) as text."
  [{:keys [operation glossary node]}]
  (let [operation-name (:name operation)
        path (cond-> [operation-name]
               (not= operation-name (:name node)) (conj (:name node)))]
    (str/join
     "\n"
     (concat (render-node path 0 node)
             [""]
             (inspect-lines path glossary)))))

(defn- render-catalog
  "Render the versioned no-arg catalog `{schema-version, ops[]}`
  (DELTA-Dtf-001.CC3) as text.

  Each shallow per-op envelope's summary node renders through the SAME uniform
  `render-node`, so the catalog reuses the node contract unchanged."
  [{:keys [ops]}]
  (str/join
   "\n"
   (mapcat (fn [{:keys [node]}]
             (concat (render-node [(:name node)] 0 node) [""]))
           ops)))

(defn- colorize-help
  "Add ANSI emphasis to friendly help rendered for a terminal."
  [text]
  (-> text
      (str/replace #"(?m)^(\s*)([^ ]+)( — )"
                   (fn [[_ prefix name separator]]
                     (str prefix "\u001b[1;36m" name "\u001b[0m" separator)))
      (str/replace #"(?m)^(\s*)(flags|positionals|returns|use-when|notes|failure-modes-glossary|inspect|operation|glossary):"
                   (fn [[_ prefix label]]
                     (str prefix "\u001b[1;33m" label ":\u001b[0m")))))

(defn default-help-transform
  "Render a canonical help envelope (DELTA-Dtf-001.CC1) as readable text.

  The batteries reference default help transform (DELTA-Dtf-002.CC1): a full
  envelope plus terminal capabilities → the string the CLI relays verbatim. It is EXPORTED for trusted
  `init.clj` election through `register-default-help-transform!` (Task 8) and is
  deliberately not auto-registered by the module, so a fresh world keeps the
  raw-JSON floor (DELTA-Dtf-002.D1).

  Both members of the one help-schema family render through the single uniform
  node renderer (`render-node`): the detail envelope carrying `node`, and the
  no-arg catalog carrying `ops[]` of summary nodes (DELTA-Dtf-001.CC3). The only
  branch is which envelope family this is — an envelope-shape choice, never a
  per-node-level one, so the recursive node renderer stays uniform at every depth
  (the forcing-function invariant, DELTA-Dtf-003.D1). ANSI color is added only
  when the caller reports `:is-tty true`; redirected and agent output stays plain."
  [envelope {:keys [is-tty]}]
  (cond-> (if (contains? envelope :ops)
            (render-catalog envelope)
            (render-detail envelope))
    is-tty colorize-help))

;; --- declarations -----------------------------------------------------------

(defn- op-options
  [op-name arg-spec & [metadata]]
  (merge {:arg-spec arg-spec
          :returns (get op-returns op-name)}
         metadata))

(millstrand/defop add
  "Create a strand with merged attributes, optional state, and outgoing edges."
  (op-options 'add add-arg-spec add-meta)
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

(millstrand/defop update
  "Patch one strand's title, state, attributes, and outgoing edges."
  (op-options 'update update-arg-spec)
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

(millstrand/defop show
  "Return one normalized strand by id."
  (op-options 'show show-arg-spec)
  [ctx]
  (weaver/show (:op/runtime ctx) (:id (:op/args ctx))))

(millstrand/defop supersede
  "Replace one strand with another and return the supersession result."
  (op-options 'supersede supersede-arg-spec)
  [ctx]
  (let [{:keys [old-id replacement-id]} (:op/args ctx)]
    (weaver/supersede! (:op/runtime ctx) old-id replacement-id (request-context :supersede))))

(millstrand/defop burn
  "Physically delete one strand by id and return the burn summary."
  (op-options 'burn burn-arg-spec)
  [ctx]
  (graph/burn-by-ids! (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(millstrand/defop list
  "List lean-projected strands, optionally filtered by lifecycle state or a named query."
  (op-options 'list list-arg-spec)
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

(millstrand/defop ready
  "List lean-projected ready strands, optionally from a named query result set."
  (op-options 'ready ready-arg-spec)
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

(millstrand/defquery strand-closed
  "Return the closed strand identified by `id`, when it exists."
  {:usage "strand await --query strand-closed --param id=<id> --min-count 1"}
  {:params [:id]
   :where [:and [:= :state "closed"]
           [:= :id [:param :id]]]})

(millstrand/defquery strand-active
  "Return the active strand identified by `id`, when it exists."
  {:usage "strand await --query strand-active --param id=<id> --max-count 0"}
  {:params [:id]
   :where [:and [:= :state "active"]
           [:= :id [:param :id]]]})

(millstrand/defquery children-active
  "Return active children of the strand identified by `parent`."
  {:usage "strand await --query children-active --param parent=<id> --max-count 0"}
  {:params [:parent]
   :where [:and [:= :state "active"]
           [:edge/in "parent-of" [:= :id [:param :parent]]]]})

(millstrand/defquery blockers-active
  "Return active blockers of the strand identified by `id`."
  {:usage "strand await --query blockers-active --param id=<id> --max-count 0"}
  {:params [:id]
   :where [:and [:= :state "active"]
           [:edge/in "depends-on" [:= :id [:param :id]]]]})

(millstrand/defop await
  "Block until a named query's result count is inside the requested band."
  (op-options 'await await-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        opts (await-query rt (await-options (:op/args ctx)))
        started (runtime-api/now rt)]
    (spool/poll-until!
     (runtime-api/clock rt)
     {:timeout-ms (* 1000 (long (:timeout-secs opts)))
      :poll-ms await-poll-ms
      :check #(await-probe rt opts)
      :pred->result #(when (:satisfied? %) (await-result rt started opts "satisfied" %))
      :on-timeout #(await-result rt started opts "timeout" %)})))

(millstrand/defop subgraph
  "Return a relation-scoped subgraph rooted at one strand."
  (op-options 'subgraph subgraph-arg-spec)
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (graph/subgraph (:op/runtime ctx) [root-id]
                        (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(millstrand/defop weave
  "Apply a registered create-only weave pattern to one JSON input value."
  (op-options 'weave weave-arg-spec weave-meta)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (patterns/weave! rt
                     pattern
                     (walk/keywordize-keys (read-single-json input))
                     (request-context :weave))))

(millstrand/defop query
  "Introspect registered named queries: list all metadata or explain one."
  (op-options 'query query-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (json-safe-value (query-list-entries rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (graph/query-explain rt nm))))))

(millstrand/defop pattern
  "Introspect registered weave patterns: list all metadata or explain one."
  (op-options 'pattern pattern-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case (first subcommand)
      "list" (patterns/patterns rt)
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (patterns/explain rt nm)))))

(millstrand/defop note
  "Append a note to a target strand's memory via the note primitive."
  (op-options 'note note-arg-spec)
  [ctx]
  (let [{:keys [id text by round attr]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    ;; note! folds every non-:by/:round opt into decorating attrs, so the
    ;; string-keyed --attr map lands as ordinary strand attrs on the note.
    (notes/note! (:op/runtime ctx) id text (merge (or attr {}) {:by by :round round}))))

(millstrand/defop notes
  "Return a target strand's notes in note/at order."
  (op-options 'notes notes-arg-spec)
  [ctx]
  (let [{:keys [id round]} (:op/args ctx)]
    (notes/notes (:op/runtime ctx) id {:round round})))

(millstrand/defop vocab
  "List the runtime's vocabulary declarations, optionally narrowed to one kind."
  (op-options 'vocab vocab-arg-spec)
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [kind]} (:op/args ctx)]
    (json-safe-value
     (vocab/declarations rt (when kind {:kind (validate-vocab-kind kind)})))))

(millstrand/defop spool
  "Dispatch validated `strand spool about|add|bump|status` inputs and results."
  (op-options 'spool spool-arg-spec)
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

(s/def ::runtime some?)
(s/def ::seed-context (s/keys :req-un [::runtime]))
(s/def ::seeded #{:batteries-glossary})
(s/def ::seed-result
  (s/and (s/keys :req-un [::seeded])
         #(= #{:seeded} (set (keys %)))))

(defn seed-batteries-glossary!
  "Seed Batteries' process-lifetime failure glossary.

  Input conforms to `::seed-context`; the result conforms to `::seed-result`."
  [ctx]
  (require-valid! ::seed-context ctx "Batteries glossary seed context is invalid")
  (let [runtime (:runtime ctx)
        result {:seeded :batteries-glossary}]
    (doseq [outcome batteries-glossary]
      (glossary/register-glossary-outcome!
       runtime (assoc outcome :owner 'millstrand.spools.batteries)))
    (require-valid! ::seed-result result "Batteries glossary seed result is invalid")))

(lifecycle/defseed batteries-glossary-seed
  "Seed the process-lifetime Batteries failure glossary."
  {:apply 'millstrand.spools.batteries/seed-batteries-glossary!})
