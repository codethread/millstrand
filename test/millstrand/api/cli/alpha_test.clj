(ns millstrand.api.cli.alpha-test
  "Tests for the blessed declarative op argv parser (SPEC-003-D003.C1/C2)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.cli.alpha :as cli]))

(defn- reason
  "Run `f`, returning the :reason of the thrown parse error (or ::no-throw)."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- thrown-data
  "Run `f`, returning thrown ex-data (or ::no-throw)."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def add-spec
  {:op :add
   :doc "Add a strand"
   :hook-class :mutating
   :deadline-class :standard
   :flags {:state {:type :string :doc "Lifecycle state"}
           :count {:type :int :doc "How many"}
           :force {:type :boolean :doc "Skip checks"}
           :attr {:type :map :doc "Attribute key=value"}
           :edge {:type :string :repeat? true :doc "Edge spec"}
           :owner {:type :string :required? true :doc "Owner"}}
   :positionals [{:name :title :type :string :required? true :doc "Title"}
                 {:name :note :type :string :doc "Optional note"}]})

;; ---------------------------------------------------------------------------
;; Happy paths for every arg kind
;; ---------------------------------------------------------------------------

(deftest string-flag
  (is (= "closed" (:state (cli/parse add-spec ["--owner" "me" "--state" "closed" "t"])))))

(deftest int-flag-coerces
  (is (= 3 (:count (cli/parse add-spec ["--owner" "me" "--count" "3" "t"])))))

(deftest boolean-flag-presence
  (let [r (cli/parse add-spec ["--owner" "me" "--force" "t"])]
    (is (true? (:force r)))
    (is (not (contains? (cli/parse add-spec ["--owner" "me" "t"]) :force)))))

(deftest map-flag-accumulates
  (is (= {"priority" "high" "owner" "agent"}
         (:attr (cli/parse add-spec ["--owner" "me" "--attr" "priority=high" "--attr" "owner=agent" "t"])))))

(deftest map-flag-empty-value-allowed
  (is (= {"k" ""} (:attr (cli/parse add-spec ["--owner" "me" "--attr" "k=" "t"])))))

(deftest repeat-flag-collects-vector
  (is (= ["depends-on:a" "depends-on:b"]
         (:edge (cli/parse add-spec ["--owner" "me" "--edge" "depends-on:a" "--edge" "depends-on:b" "t"])))))

(deftest required-positional-and-optional
  (let [r (cli/parse add-spec ["--owner" "me" "title" "a note"])]
    (is (= "title" (:title r)))
    (is (= "a note" (:note r))))
  (is (= "title" (:title (cli/parse add-spec ["--owner" "me" "title"])))))

(deftest variadic-trailing-positional
  (let [spec {:op :weave
              :hook-class :mutating
              :deadline-class :standard
              :positionals [{:name :pattern :type :string :required? true}
                            {:name :ids :type :string :variadic? true}]}]
    (is (= {:pattern "p" :ids ["a" "b" "c"]} (cli/parse spec ["p" "a" "b" "c"])))
    (is (= {:pattern "p" :ids []} (cli/parse spec ["p"])))))

;; ---------------------------------------------------------------------------
;; Loud failures (MI2)
;; ---------------------------------------------------------------------------

(deftest unknown-flag-fails
  (is (= :unknown-flag (reason #(cli/parse add-spec ["--owner" "me" "--bogus" "x" "t"])))))

(deftest errors-retain-public-qualified-marker
  (is (true? (::cli/error
              (thrown-data #(cli/parse add-spec
                                       ["--owner" "me" "--bogus" "x" "t"]))))))

(deftest missing-required-flag-fails
  (is (= :missing-required (reason #(cli/parse add-spec ["--state" "closed" "t"])))))

(deftest missing-required-positional-fails
  (is (= :missing-required (reason #(cli/parse add-spec ["--owner" "me"])))))

(deftest type-violation-fails
  (is (= :type-violation (reason #(cli/parse add-spec ["--owner" "me" "--count" "notint" "t"])))))

(deftest duplicate-non-repeat-flag-fails
  (is (= :duplicate-flag (reason #(cli/parse add-spec ["--owner" "me" "--state" "a" "--state" "b" "t"]))))
  (testing "boolean duplicate is loud too"
    (is (= :duplicate-flag (reason #(cli/parse add-spec ["--owner" "me" "--force" "--force" "t"]))))))

(deftest malformed-kv-fails
  (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr" "novalue" "t"]))))
  (testing "empty key is malformed"
    (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr" "=v" "t"])))))
  (testing "missing token after map flag is malformed"
    (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr"]))))))

(deftest trailing-tokens-fail
  (is (= :trailing-tokens (reason #(cli/parse add-spec ["--owner" "me" "a" "b" "c"])))))

;; ---------------------------------------------------------------------------
;; Payload references (MI3)
;; ---------------------------------------------------------------------------

(def payload-spec
  {:op :weave
   :hook-class :mutating
   :deadline-class :standard
   :flags {:attr {:type :map}}
   :positionals [{:name :body :type :string :required? true}
                 {:name :extra :type :string :variadic? true}]})

(deftest stdin-reference-resolves
  (is (= "hello" (:body (cli/parse payload-spec [":stdin"] {"stdin" "hello"})))))

(deftest named-payload-reference-resolves
  (is (= "manifest-body"
         (:body (cli/parse payload-spec [":payload/manifest"] {"manifest" "manifest-body"})))))

(deftest reference-resolves-across-map-and-positionals
  (let [r (cli/parse payload-spec [":stdin" "--attr" "doc=:payload/note" ":payload/tail"]
                     {"stdin" "B" "note" "N" "tail" "T"})]
    (is (= "B" (:body r)))
    (is (= {"doc" "N"} (:attr r)))
    (is (= ["T"] (:extra r)))))

(deftest whole-value-only-matching
  (testing "a value containing :stdin as a substring is untouched (real ref consumes the payload)"
    (let [r (cli/parse payload-spec ["prefix:stdin" ":stdin"] {"stdin" "X"})]
      (is (= "prefix:stdin" (:body r)))
      (is (= ["X"] (:extra r)))))
  (testing "a leading-substring :payload ref is untouched"
    (let [r (cli/parse payload-spec ["x:payload/note" ":payload/note"] {"note" "N"})]
      (is (= "x:payload/note" (:body r)))
      (is (= ["N"] (:extra r))))))

(deftest dangling-reference-fails-loudly
  (is (= :missing-payload (reason #(cli/parse payload-spec [":stdin"] {}))))
  (is (= :missing-payload (reason #(cli/parse payload-spec [":payload/absent"] {"stdin" "x"})))))

(deftest unused-payload-fails-loudly
  (let [r (reason #(cli/parse payload-spec ["literal"] {"stdin" "unused"}))]
    (is (= :unused-payloads r)))
  (testing "message names the unused slots"
    (try (cli/parse payload-spec [":stdin"] {"stdin" "used" "orphan" "x"})
         (is false "expected throw")
         (catch clojure.lang.ExceptionInfo e
           (is (= :unused-payloads (:reason (ex-data e))))
           (is (= ["orphan"] (:unused (ex-data e))))))))

(deftest same-payload-referenced-twice-is-not-unused
  (let [r (cli/parse payload-spec [":stdin" ":stdin"] {"stdin" "S"})]
    (is (= "S" (:body r)))
    (is (= ["S"] (:extra r)))))

;; ---------------------------------------------------------------------------
;; Payload parse declarations (MI4)
;; ---------------------------------------------------------------------------

(def parse-spec
  {:op :weave
   :hook-class :mutating
   :deadline-class :standard
   :flags {:data {:type :string :parse :json}}
   :positionals [{:name :items :type :string :parse :jsonl :required? true}]})

(deftest json-parse-success
  (is (= {"a" 1} (:data (cli/parse parse-spec ["[]" "--data" ":stdin"] {"stdin" "{\"a\": 1}"})))))

(deftest jsonl-parse-success
  (is (= [{"k" 1} {"k" 2}]
         (:items (cli/parse parse-spec [":stdin"] {"stdin" "{\"k\":1}\n{\"k\":2}\n"})))))

(deftest jsonl-skips-blank-lines
  (is (= [{"k" 1}] (:items (cli/parse parse-spec [":stdin"] {"stdin" "\n{\"k\":1}\n\n"})))))

(deftest json-parse-failure-is-loud
  (is (= :malformed-json (reason #(cli/parse parse-spec ["[]" "--data" ":stdin"] {"stdin" "{not json"})))))

(deftest jsonl-parse-failure-has-line-context
  (try (cli/parse parse-spec [":stdin"] {"stdin" "{\"k\":1}\nnot-json"})
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :malformed-jsonl (:reason (ex-data e))))
         (is (= 2 (:line (ex-data e)))))))

(deftest parse-applies-to-literal-value
  (is (= {"x" true} (:data (cli/parse parse-spec ["[]" "--data" (json/write-str {"x" true})])))))

;; ---------------------------------------------------------------------------
;; explain (MI5)
;; ---------------------------------------------------------------------------

(deftest explain-shape
  (let [e (cli/explain add-spec)]
    (is (= "add" (:op e)))
    (is (= "Add a strand" (:doc e)))
    (let [attr (first (filter #(= "attr" (:name %)) (:flags e)))]
      (is (= "map" (:type attr)))
      (is (false? (:required attr))))
    (let [owner (first (filter #(= "owner" (:name %)) (:flags e)))]
      (is (= "--owner" (:flag owner)))
      (is (true? (:required owner))))
    (let [edge (first (filter #(= "edge" (:name %)) (:flags e)))]
      (is (true? (:repeat edge))))
    (is (= [{:name "title" :type "string" :required true :variadic false :parse nil :spec nil
             :doc "Title"}
            {:name "note" :type "string" :required false :variadic false :parse nil :spec nil
             :doc "Optional note"}]
           (:positionals e)))))

(deftest explain-renders-parse-declarations
  (let [e (cli/explain parse-spec)
        data (first (filter #(= "data" (:name %)) (:flags e)))]
    (is (= "json" (:parse data)))
    (is (= "jsonl" (:parse (first (:positionals e)))))))

(def spec-ref-spec
  {:op :choose
   :doc "Choose with declared value specs"
   :hook-class :mutating
   :deadline-class :standard
   :flags {:input {:type :string :parse :json :spec :my.ns/input :doc "Input"}}
   :positionals [{:name :choice :required? true :spec :my.ns/choice :doc "Choice"}]})

(deftest explain-renders-declared-specs
  (let [e (cli/explain spec-ref-spec)]
    (is (= "my.ns/input" (:spec (first (:flags e)))))
    (is (= "my.ns/choice" (:spec (first (:positionals e)))))
    (testing "a declared qualified symbol renders as its printed name"
      (is (= "my.ns/sym-spec"
             (:spec (first (:flags (cli/explain
                                    (assoc-in spec-ref-spec
                                              [:flags :input :spec]
                                              'my.ns/sym-spec))))))))))

(deftest declared-spec-must-be-a-qualified-ident
  (doseq [bad [:input "my.ns/input" 'input 42]]
    (is (= :invalid-spec-ref
           (reason #(cli/validate! (assoc-in spec-ref-spec
                                             [:flags :input :spec] bad))))
        (pr-str bad)))
  (testing "positional :spec validates the same way"
    (is (= :invalid-spec-ref
           (reason #(cli/validate! (assoc-in spec-ref-spec
                                             [:positionals 0 :spec] "nope")))))))

;; ---------------------------------------------------------------------------
;; Subcommands (fractal, arity-N — DELTA-Lhc-001.CC1/CC2/CC3)
;; ---------------------------------------------------------------------------

(def subcommand-spec
  {:op :thing
   :doc "Manage things"
   :subcommands {"add" {:doc "Add a thing"
                        :hook-class :mutating
                        :deadline-class :standard
                        :flags {:force {:type :boolean :doc "Skip checks"}
                                :data {:type :string :parse :json :doc "JSON data"}}
                        :positionals [{:name :title :type :string :required? true :doc "Title"}]}
                 "list" {:doc "List things"
                         :hook-class :read
                         :deadline-class :standard
                         :flags {:limit {:type :int :doc "Maximum count"}}
                         :positionals []}
                 "tag" {:doc "Tag things"
                        :hook-class :mutating
                        :deadline-class :standard
                        :flags {:attr {:type :map :doc "Metadata"}}
                        :positionals [{:name :id :type :string :required? true}
                                      {:name :labels :type :string :variadic? true}]}}})

(deftest subcommand-routes-and-merges-path-vector
  (is (= {:subcommand ["add"] :force true :title "hello"}
         (cli/parse subcommand-spec ["add" "--force" "hello"]))))

(deftest subcommand-supports-each-arg-kind
  (testing "int flags"
    (is (= {:subcommand ["list"] :limit 10}
           (cli/parse subcommand-spec ["list" "--limit" "10"]))))
  (testing "map flags and variadic positionals"
    (is (= {:subcommand ["tag"] :attr {"k" "v"} :id "abc" :labels ["x" "y"]}
           (cli/parse subcommand-spec ["tag" "--attr" "k=v" "abc" "x" "y"]))))
  (testing "nested required args remain local to the routed subcommand"
    (is (= :missing-required (reason #(cli/parse subcommand-spec ["add"]))))))

(deftest subcommand-missing-and-unknown-errors-carry-canonical-context
  (try (cli/parse subcommand-spec [])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :missing-subcommand (:reason (ex-data e))))
         (is (= "thing" (:op (ex-data e))))
         (is (= [] (:path (ex-data e))))
         (is (nil? (:token (ex-data e))))
         (is (= ["add" "list" "tag"] (:available (ex-data e))))))
  (try (cli/parse subcommand-spec ["bogus"])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :unknown-subcommand (:reason (ex-data e))))
         (is (= "thing" (:op (ex-data e))))
         (is (= [] (:path (ex-data e))))
         (is (= "bogus" (:token (ex-data e))))
         (is (= ["add" "list" "tag"] (:available (ex-data e)))))))

;; Depth-3 grammar composed from flat def'ed node blocks (TASK-Lhc-001.MI8):
;; each leaf and interior level is authored standalone and composed by
;; reference, proving fractal composition without inline nesting.

(def caps-show-leaf
  {:doc "Show one cap"
   :hook-class :read
   :deadline-class :standard
   :positionals [{:name :id :type :string :required? true :doc "Cap id"}]})

(def caps-grant-leaf
  {:doc "Grant a cap"
   :hook-class :mutating
   :deadline-class :unbounded
   :flags {:role {:type :string :required? true :doc "Role name"}
           :data {:type :string :parse :json :doc "JSON payload"}}
   :positionals [{:name :subject :type :string :required? true :doc "Subject"}]})

(def caps-node
  {:doc "Manage caps"
   :subcommands {"show" caps-show-leaf
                 "grant" caps-grant-leaf}})

(def audit-leaf
  {:doc "Audit trail (doc-only leaf)"
   :hook-class :read
   :deadline-class :standard})

(def admin-node
  {:doc "Admin surface"
   :subcommands {"caps" caps-node
                 "audit" audit-leaf}})

(def deep-spec
  {:op :acl
   :doc "Access control"
   :subcommands {"admin" admin-node
                 "list" {:doc "List entries"
                         :hook-class :read
                         :deadline-class :standard
                         :flags {:limit {:type :int :doc "Maximum count"}}}}})

(deftest deep-parse-routes-to-depth-three-leaf
  (is (= {:subcommand ["admin" "caps" "show"] :id "c1"}
         (cli/parse deep-spec ["admin" "caps" "show" "c1"])))
  (is (= {:subcommand ["admin" "caps" "grant"] :role "r" :subject "s"}
         (cli/parse deep-spec ["admin" "caps" "grant" "--role" "r" "s"])))
  (testing "the path is a vector at depth one too"
    (is (= {:subcommand ["list"] :limit 3}
           (cli/parse deep-spec ["list" "--limit" "3"])))))

(deftest deep-parse-doc-only-leaf-is-invocable
  (is (= {:subcommand ["admin" "audit"]} (cli/parse deep-spec ["admin" "audit"])))
  (testing "trailing tokens at a doc-only leaf still fail in the leaf parse"
    (is (= :trailing-tokens (reason #(cli/parse deep-spec ["admin" "audit" "extra"]))))))

(deftest deep-parse-payload-refs-and-parse-work-at-depth
  (is (= {:subcommand ["admin" "caps" "grant"] :role "r" :subject "s" :data {"k" 1}}
         (cli/parse deep-spec
                    ["admin" "caps" "grant" "--role" "r" "--data" ":stdin" "s"]
                    {"stdin" (json/write-str {"k" 1})}))))

(deftest deep-parse-routing-errors-carry-walked-path
  (try (cli/parse deep-spec ["admin"])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :missing-subcommand (:reason (ex-data e))))
         (is (= "acl" (:op (ex-data e))))
         (is (= ["admin"] (:path (ex-data e))))
         (is (nil? (:token (ex-data e))))
         (is (= ["audit" "caps"] (:available (ex-data e))))))
  (try (cli/parse deep-spec ["admin" "caps" "bogus"])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :unknown-subcommand (:reason (ex-data e))))
         (is (= ["admin" "caps"] (:path (ex-data e))))
         (is (= "bogus" (:token (ex-data e))))
         (is (= ["grant" "show"] (:available (ex-data e)))))))

(deftest resolve-leaf-walks-routing-tokens-only
  (testing "a deep walk returns the leaf node and full path, ignoring leaf args"
    (is (= {:node caps-grant-leaf :path ["admin" "caps" "grant"]}
           (cli/resolve-leaf deep-spec ["admin" "caps" "grant" "--role" "r" "s"]))))
  (testing "a flat arg-spec resolves to its own root at path []"
    (is (= {:node add-spec :path []} (cli/resolve-leaf add-spec ["--owner" "me" "t"]))))
  (testing "routing failures carry the canonical context without parsing the leaf"
    (let [data (thrown-data #(cli/resolve-leaf deep-spec ["admin" "nope"]))]
      (is (= :unknown-subcommand (:reason data)))
      (is (= ["admin"] (:path data)))
      (is (= "nope" (:token data)))
      (is (= ["audit" "caps"] (:available data))))))

(deftest leaf-class-metadata-validates
  (testing "leaf classes are accepted on nested leaves and flat roots"
    (is (map? (cli/validate! deep-spec)))
    (is (map? (cli/validate! add-spec))))
  (testing "both leaf classes are required with canonical node context"
    (doseq [[node field] [[{:deadline-class :standard} :hook-class]
                          [{:hook-class :read} :deadline-class]]]
      (let [data (thrown-data #(cli/validate! (assoc node :op :x)))]
        (is (= :missing-node-class (:reason data)))
        (is (= "x" (:op data)))
        (is (= [] (:path data)))
        (is (= field (:field data))))))
  (testing "an unknown class value fails loudly"
    (let [data (thrown-data
                #(cli/validate!
                  {:op :x :subcommands {"a" {:hook-class :write}}}))]
      (is (= :invalid-node-class (:reason data)))
      (is (= ["a"] (:path data)))
      (is (= :hook-class (:field data)))
      (is (= [:mutating :read] (:allowed data))))
    (is (= :invalid-node-class
           (reason #(cli/validate! {:op :x :deadline-class :forever})))))
  (testing "interior nodes may not declare classes"
    (let [data (thrown-data
                #(cli/validate!
                  {:op :x
                   :hook-class :read
                   :subcommands {"a" {:doc "leaf"}}}))]
      (is (= :misplaced-node-class (:reason data)))
      (is (= [] (:path data)))
      (is (= :hook-class (:field data))))
    (is (= :misplaced-node-class
           (reason #(cli/validate!
                     {:op :x
                      :subcommands {"a" {:deadline-class :standard
                                         :subcommands {"b" {:doc "leaf"}}}}}))))))

(deftest subcommand-structure-failures-are-loud
  (testing "interior nodes cannot declare flags"
    (is (= :invalid-subcommands
           (reason #(cli/validate! (assoc subcommand-spec :flags {:verbose {:type :boolean}}))))))
  (testing "interior nodes cannot declare positionals"
    (is (= :invalid-subcommands
           (reason #(cli/validate! (assoc subcommand-spec :positionals [{:name :id}]))))))
  (testing "interior restrictions hold at depth, with the node path in context"
    (let [data (thrown-data
                #(cli/validate!
                  {:op :x
                   :subcommands {"a" {:flags {:n {:type :int}}
                                      :subcommands {"b" {:doc "leaf"}}}}}))]
      (is (= :invalid-subcommands (:reason data)))
      (is (= "x" (:op data)))
      (is (= ["a"] (:path data)))
      (is (= :flags (:field data)))))
  (testing "empty :subcommands is invalid — every op must reach a leaf"
    (let [data (thrown-data #(cli/validate! {:op :x :subcommands {}}))]
      (is (= :empty-subcommands (:reason data)))
      (is (= [] (:path data))))
    (is (= :empty-subcommands
           (reason #(cli/validate! {:op :x :subcommands {"a" {:subcommands {}}}})))))
  (testing "subcommand names must be non-blank strings"
    (let [data (thrown-data #(cli/validate! {:op :x :subcommands {"" {:doc "Nope"}}}))]
      (is (= :invalid-subcommand-name (:reason data)))
      (is (= "x" (:op data)))
      (is (= [] (:path data)))
      (is (= "" (:value data)))
      (is (= :subcommands (:field data)))))
  (testing "nested subcommand specs must be maps"
    (let [data (thrown-data #(cli/validate! {:op :x :subcommands {"a" 42}}))]
      (is (= :invalid-subcommand-spec (:reason data)))
      (is (= "x" (:op data)))
      (is (= "a" (:subcommand data)))
      (is (= :subcommands (:field data)))
      (is (= 42 (:value data)))))
  (testing "nested flags must be a map"
    (let [data (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:flags []}}}))]
      (is (= :invalid-flags (:reason data)))
      (is (= "x" (:op data)))
      (is (= ["a"] (:path data)))
      (is (= :flags (:field data)))
      (is (= [] (:value data)))))
  (testing "nested flag entries must be keyword to map"
    (let [bad-name (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:flags {"force" {:type :boolean}}}}}))
          bad-spec (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:flags {:force true}}}}))]
      (is (= :invalid-flag (:reason bad-name)))
      (is (= :flags (:field bad-name)))
      (is (= "force" (:arg bad-name)))
      (is (= :invalid-flag (:reason bad-spec)))
      (is (= :force (:arg bad-spec)))
      (is (true? (:value bad-spec)))))
  (testing "nested positionals must be sequential"
    (let [data (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:positionals {:name :id}}}}))]
      (is (= :invalid-positionals (:reason data)))
      (is (= "x" (:op data)))
      (is (= ["a"] (:path data)))
      (is (= :positionals (:field data)))
      (is (= {:name :id} (:value data)))))
  (testing "nested positional entries must be maps with keyword :name"
    (let [bad-entry (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:positionals ["id"]}}}))
          bad-name (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:positionals [{:name "id"}]}}}))]
      (is (= :invalid-positional (:reason bad-entry)))
      (is (= :positionals (:field bad-entry)))
      (is (zero? (:index bad-entry)))
      (is (= "id" (:value bad-entry)))
      (is (= :invalid-positional (:reason bad-name)))
      (is (= {:name "id"} (:value bad-name)))))
  (testing "nested flag and positional types must be known"
    (let [bad-flag (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:flags {:n {:type :strnig}}}}}))
          bad-pos (thrown-data #(cli/validate! {:op :x :subcommands {"a" {:positionals [{:name :id :type :uuid}]}}}))]
      (is (= :invalid-arg-type (:reason bad-flag)))
      (is (= :flags (:field bad-flag)))
      (is (= :n (:arg bad-flag)))
      (is (= :strnig (:type bad-flag)))
      (is (= [:boolean :boolean-token :int :map :string] (:supported-types bad-flag)))
      (is (= :invalid-arg-type (:reason bad-pos)))
      (is (= ["a"] (:path bad-pos)))
      (is (= :positionals (:field bad-pos)))
      (is (= :id (:arg bad-pos)))
      (is (= :uuid (:type bad-pos))))))

(deftest subcommand-reserved-name-failures-are-loud
  (testing "nested flag named subcommand is reserved"
    (is (= :reserved-subcommand
           (reason #(cli/validate!
                     {:op :x :subcommands {"a" {:flags {:subcommand {:type :string}}}}})))))
  (testing "nested positional named subcommand is reserved"
    (is (= :reserved-subcommand
           (reason #(cli/validate!
                     {:op :x :subcommands {"a" {:positionals [{:name :subcommand}]}}})))))
  (testing "the subcommand arg name is reserved on flat roots too"
    (is (= :reserved-subcommand
           (reason #(cli/validate! {:op :x :flags {:subcommand {:type :string}}})))))
  (testing "help-alias names are reserved at every depth"
    (let [data (thrown-data
                #(cli/validate!
                  {:op :x
                   :subcommands {"a" {:subcommands {"help" {:doc "Nope"}}}}}))]
      (is (= :reserved-subcommand-name (:reason data)))
      (is (= ["a"] (:path data)))
      (is (= "help" (:name data))))
    (is (= :reserved-subcommand-name
           (reason #(cli/validate!
                     {:op :x :subcommands {"--help" {:doc "Nope"}}}))))))

(deftest subcommand-payload-ref-and-json-parse-use-routed-spec
  (is (= {:subcommand ["add"] :title "literal" :data {"ok" true}}
         (cli/parse subcommand-spec ["add" "literal" "--data" ":payload/body"]
                    {"body" (json/write-str {"ok" true})})))
  (testing "unused payloads are evaluated against routed subcommand consumption"
    (try (cli/parse subcommand-spec ["list"] {"body" "unused"})
         (is false "expected throw")
         (catch clojure.lang.ExceptionInfo e
           (is (= :unused-payloads (:reason (ex-data e))))
           (is (= ["body"] (:unused (ex-data e))))))))

(deftest explain-renders-subcommands
  (let [e (cli/explain subcommand-spec)]
    (is (= "thing" (:op e)))
    (is (= "Manage things" (:doc e)))
    (is (= [] (:flags e)))
    (is (= [] (:positionals e)))
    (is (= ["add" "list" "tag"] (mapv :name (:subcommands e))))
    (let [add (first (:subcommands e))]
      (is (= "Add a thing" (:doc add)))
      (is (= [{:name "data" :flag "--data" :type "string" :required false :repeat false
               :parse "json" :spec nil :doc "JSON data"}
              {:name "force" :flag "--force" :type "boolean" :required false :repeat false
               :parse nil :spec nil :doc "Skip checks"}]
             (:flags add)))
      (is (= [{:name "title" :type "string" :required true :variadic false :parse nil :spec nil
               :doc "Title"}]
             (:positionals add))))))

(deftest explain-renders-nested-subcommands-recursively
  (let [e (cli/explain deep-spec)
        admin (first (filter #(= "admin" (:name %)) (:subcommands e)))
        caps (first (filter #(= "caps" (:name %)) (:subcommands admin)))
        audit (first (filter #(= "audit" (:name %)) (:subcommands admin)))]
    (is (= "acl" (:op e)))
    (is (= ["admin" "list"] (mapv :name (:subcommands e))))
    (is (= "Admin surface" (:doc admin)))
    (is (= ["audit" "caps"] (mapv :name (:subcommands admin))))
    (is (= ["grant" "show"] (mapv :name (:subcommands caps))))
    (testing "a doc-only leaf renders with empty flags and positionals"
      (is (= "Audit trail (doc-only leaf)" (:doc audit)))
      (is (= [] (:flags audit)))
      (is (= [] (:positionals audit)))
      (is (not (contains? audit :subcommands))))
    (testing "deep leaves render their declared args"
      (let [grant (first (filter #(= "grant" (:name %)) (:subcommands caps)))]
        (is (= ["data" "role"] (mapv :name (:flags grant))))
        (is (= ["subject"] (mapv :name (:positionals grant))))))))

(deftest flat-spec-parse-and-explain-regression
  (is (= {:owner "me" :title "title"}
         (cli/parse add-spec ["--owner" "me" "title"])))
  (is (= {:op "add"
          :doc "Add a strand"
          :flags (mapv (fn [[k v]]
                         {:name (name k)
                          :flag (str "--" (name k))
                          :type (name (:type v :string))
                          :required (boolean (:required? v))
                          :repeat (boolean (:repeat? v))
                          :parse (some-> (:parse v) name)
                          :spec nil
                          :doc (:doc v)})
                       (sort-by key (:flags add-spec)))
          :positionals [{:name "title" :type "string" :required true :variadic false
                         :parse nil :spec nil :doc "Title"}
                        {:name "note" :type "string" :required false :variadic false
                         :parse nil :spec nil :doc "Optional note"}]}
         (cli/explain add-spec))))

(deftest declared-parse-kinds-are-validated-up-front
  (testing "an unsupported :parse fails at validate!, not at first invocation"
    (is (= :invalid-parse-kind
           (reason #(cli/validate! {:op "x"
                                    :flags {:data {:type :string :parse :yaml}}}))))
    (let [data (thrown-data #(cli/validate!
                              {:op "x"
                               :positionals [{:name :items :type :string
                                              :parse :yaml}]}))]
      (is (= :yaml (:parse data)))
      (is (= [:json :jsonl] (:supported-parse-kinds data))))))

;; ---------------------------------------------------------------------------
;; Closed annotation sub-map structural validation (DELTA-Dtf-003.CC2)
;; ---------------------------------------------------------------------------

(deftest annotations-valid-shapes-pass
  (testing "a flat spec's closed annotation sub-map validates"
    (is (map? (cli/validate!
               (assoc add-spec :annotations
                      {:use-when ["When adding a strand"]
                       :notes ["Idempotent on retry"]
                       :failure-modes ["lifecycle/timeout"]})))))
  (testing "a partial sub-map (only some keys) validates — each key is optional"
    (is (map? (cli/validate! (assoc add-spec :annotations {:notes ["just a note"]})))))
  (testing "annotations on a subcommand's nested spec validate"
    (is (map? (cli/validate!
               (assoc-in subcommand-spec [:subcommands "add" :annotations]
                         {:failure-modes ["lifecycle/timeout"]}))))))

(deftest annotations-structural-failures-are-loud
  (testing "annotations must be a map"
    (let [data (thrown-data #(cli/validate! (assoc add-spec :annotations [:not :a :map])))]
      (is (= :invalid-annotations (:reason data)))
      (is (= :annotations (:field data)))))
  (testing "unknown annotation keys fail loudly"
    (let [data (thrown-data #(cli/validate! (assoc add-spec :annotations {:bogus ["x"]})))]
      (is (= :invalid-annotations (:reason data)))
      (is (= [:bogus] (:unknown data)))
      (is (= [:failure-modes :notes :use-when] (:allowed data)))))
  (testing "annotation values must be arrays of non-blank strings"
    (is (= :invalid-annotations
           (reason #(cli/validate! (assoc add-spec :annotations {:use-when "not-an-array"})))))
    (is (= :invalid-annotations
           (reason #(cli/validate! (assoc add-spec :annotations {:notes [42]})))))
    (is (= :invalid-annotations
           (reason #(cli/validate! (assoc add-spec :annotations {:failure-modes ["  "]}))))))
  (testing "nested annotation failures carry the node path"
    (let [data (thrown-data
                #(cli/validate!
                  (assoc-in subcommand-spec [:subcommands "add" :annotations]
                            {:failure-modes [42]})))]
      (is (= :invalid-annotations (:reason data)))
      (is (= ["add"] (:path data)))))
  (testing "annotations are honored (and validated) at every depth"
    (is (map? (cli/validate!
               (assoc-in deep-spec [:subcommands "admin" :subcommands "caps"
                                    :subcommands "show" :annotations]
                         {:failure-modes ["acl/denied"]}))))
    (let [data (thrown-data
                #(cli/validate!
                  (assoc-in deep-spec [:subcommands "admin" :subcommands "caps"
                                       :annotations]
                            {:bogus ["x"]})))]
      (is (= :invalid-annotations (:reason data)))
      (is (= ["admin" "caps"] (:path data))))))
