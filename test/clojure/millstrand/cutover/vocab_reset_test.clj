(ns millstrand.cutover.vocab-reset-test
  "Focused coverage for the vocabulary-reset cutover against a disposable weaver
  world, so the rewrite is proved on the real schema rather than a hand-rolled
  fixture: the world is seeded through the weaver, migrated through the script's
  own datasource (the way an operator runs it), and read back through the weaver.

  The cases are the ones the table cannot make obvious by inspection — the
  guarded splits (a bare key that is only agent-run's word on a task strand, an
  overloaded bench/run that stays put on marker rows, a kanban/status that is a
  lane only where it holds one), the guarded drops (a kanban/devflow alias only
  where the canonical binding answers), the two readings a decline can carry and
  the exit code that separates them, the registry's boundary, the closed-strand
  scope boundary, idempotency, and the loud refusals."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cutover.vocab-reset :as cut]
   [next.jdbc :as jdbc]
   [millstrand.api.weaver.alpha :as weaver]
   [millstrand.test.alpha :as test-alpha])
  (:import
   [java.io StringWriter]))

(defn- add!
  "Seed one strand in `state` carrying `attributes`; return its id."
  [rt state attributes]
  (:id (weaver/add! rt {:title "seed" :state state :attributes attributes})))

(defn- attrs
  "Return the strand's current attribute map, as the weaver reads it back.

  Durable keys are stored as strings; the weaver normalizes them to keywords on
  read, so expectations here are keyword-keyed — asserting through that read is
  what proves the migrated rows land back in the runtime's own view."
  [rt id]
  (:attributes (weaver/show rt id)))

(defn- datasource
  "Build the script's own datasource over the world's db, as -main does."
  [ctx]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname (:db-path ctx)}))

(defn- report!
  "Run the script's report over `result`; return `[exit-code printed-output]`.

  Reports through the same function -main hands to `System/exit`, so the exit
  code an operator's automation reads is the one asserted here."
  [result]
  (let [out (StringWriter.)
        code (binding [*out* out] (cut/report "/tmp/world.sqlite" result))]
    [code (str out)]))

(def ^:private world-opts
  "A bare disposable world: no spools, so nothing normalizes the seeded keys."
  {:spools-edn {:spools {}}})

(deftest rewrites-active-strands-to-the-published-vocabulary
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          run (add! rt "active" {"workflow/phase" "review"
                                 "workflow/role" "molecule"
                                 "workflow/wisp" "true"
                                 "workflow/hitl" "true"
                                 "workflow/checkpoint-kind" "human"
                                 "gate/run-id" "run-7"
                                 "shell/error" "boom"
                                 "agent-run/for" "task-1"
                                 "panel/fresh-prompt" "prompt text"
                                 "review/target" "board-1"
                                 "review/pass" "pass-1"
                                 "review/synthesis" "true"
                                 "review/roster" "reviewers"
                                 "ephemeral" "entry-1"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:workflow/form "review"
              :workflow/role "root"
              :workflow/checkpoint-kind "human"
              :workflow/run-id "run-7"
              :gate/error "boom"
              :agent-run/completes-on "task-1"
              :agent-run/fresh-prompt "prompt text"
              :panel/blackboard "board-1"
              :panel/pass "pass-1"
              :panel/synthesis "true"
              :review/roster "reviewers"
              :ephemeral/entry "entry-1"}
             (attrs rt run))
          "every renamed key lands on its new word, values carried unchanged;
           the derivable markers are dropped and review/roster keeps its word"))))

(deftest leaves-closed-strands-as-written
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          closed (add! rt "closed" {"workflow/phase" "review" "review/target" "board-1"})
          card (add! rt "closed" {"kanban/card" "true" "kanban/status" "done"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:workflow/phase "review" :review/target "board-1"} (attrs rt closed))
          "closed strands are historical memory, not authority")
      (is (= {:kanban/card "true" :kanban/status "done"} (attrs rt card))
          "a finished card keeps the word it was closed under: kanban/outcome is
           stamped as a card closes, and the board counts closed cards by state
           rather than reading an outcome off them"))))

(deftest namespaces-bare-keys-only-on-task-strands
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          task (add! rt "active" {"kind" "task"
                                  "harness" "opus"
                                  "cwd" "/tmp/wt"
                                  "max-attempts" "2"
                                  "backend" "claude"})
          other (add! rt "active" {"kind" "bench-entry" "harness" "opus"})
          {:keys [left-behind]} (cut/rewrite! (datasource ctx))]
      (is (= {:kind "task"
              :agent-run/harness "opus"
              :agent-run/cwd "/tmp/wt"
              :agent-run/max-attempts "2"
              :agent-run/backend "claude"}
             (attrs rt task))
          "a delegated task's bare keys move onto their owning spool")
      (is (= {:kind "bench-entry" :harness "opus"} (attrs rt other))
          "a bare harness on a non-task strand is not agent-run's word")
      (is (= {"harness -> agent-run/harness (guarded)" [other]} left-behind)
          "the declined row is reported rather than migrated blind or dropped"))))

(deftest folds-the-plan-root-marker-into-kind
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          root (add! rt "active" {"kind" "plan" "workflow" "agent-plan"})
          child (add! rt "active" {"kind" "task" "workflow" "agent-plan"})
          devflow (add! rt "active" {"kind" "plan" "workflow" "devflow"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:kind "agent-plan"} (attrs rt root))
          "the plan root's kind carries its identity; the workflow marker goes")
      (is (= {:kind "task"} (attrs rt child))
          "children drop the workflow marker but keep their own kind")
      (is (= {:kind "plan" :workflow "devflow"} (attrs rt devflow))
          "another workflow's plan root is untouched — the guard is by value"))))

(deftest drops-the-blackboard-self-marker
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          board (add! rt "active" {"panel/role" "panel" "review/pass" "pass-1"})
          seat (add! rt "active" {"panel/role" "reviewer"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:panel/pass "pass-1"} (attrs rt board))
          "the blackboard's self-marker goes; its pass tag is renamed")
      (is (= {:panel/role "reviewer"} (attrs rt seat))
          "the drop is guarded by value — another role is not this marker"))))

(deftest splits-the-overloaded-bench-run-key
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          judge (add! rt "active" {"bench/judge" "true" "bench/run" "root-42"})
          marker (add! rt "active" {"bench/run" "true"
                                    "bench/agent" "claude"
                                    "bench/aborted" "true"
                                    "bench/error" "aborted"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:bench/judge "true" :bench/run-id "root-42"} (attrs rt judge))
          "a judge's root-id value moves to the id key")
      (is (= {:bench/run "true" :bench/harness "claude" :bench/error "aborted"}
             (attrs rt marker))
          "the boolean marker keeps bench/run; the redundant abort flag goes"))))

(deftest moves-an-active-card-onto-the-board-lane
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          card (add! rt "active" {"kanban/card" "true"
                                  "kanban/status" "claimed"
                                  "kanban/type" "feature"
                                  "kanban/priority" "p2"})
          stray (add! rt "active" {"kanban/card" "true" "kanban/status" "done"})
          {:keys [drift]} (cut/rewrite! ds)]
      (is (= {:kanban/card "true"
              :kanban/lane "claimed"
              :kanban/type "feature"
              :kanban/priority "p2"}
             (attrs rt card))
          "an active card's placement moves to the word the board now reads,
           and the keys the board kept are left alone")
      (is (= {:kanban/card "true" :kanban/status "done"} (attrs rt stray))
          "an outcome is not a lane: an active card carrying one is drift, and
           the guard enumerates the whole lane vocabulary rather than one half
           of a split")
      (is (= {"kanban/status -> kanban/lane (guarded)" [stray]} drift)
          "the drift is named for the operator rather than placed in a lane the
           card never sat in")
      (is (= {:changes {} :total 0 :left-behind {} :drift drift} (cut/rewrite! ds))
          "a re-run against the migrated world is a no-op, and the decline is
           stable rather than a one-shot warning"))))

(deftest fails-nonzero-on-drift-a-value-guard-enumerated-away
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          stray (add! rt "active" {"kanban/card" "true" "kanban/status" "done"})
          moved (add! rt "active" {"kanban/card" "true" "kanban/status" "pending"})
          result (cut/rewrite! ds)
          [code out] (report! result)]
      (is (= {:kanban/card "true" :kanban/lane "pending"} (attrs rt moved))
          "the drift does not abort the transaction: every card the table can
           place is migrated around the one it cannot")
      (is (= 1 code)
          "a strand left under a word the new code has no reading for is not a
           cutover any automation may take for done")
      (is (re-find #"kanban/status -> kanban/lane \(guarded\)" out)
          "the failure names the migration whose vocabulary the row fell outside")
      (is (re-find (re-pattern stray) out)
          "and the strand the operator has to resolve by hand")
      (let [re-run (cut/rewrite! ds)]
        (is (zero? (:total re-run)) "the committed rewrite is idempotent")
        (is (= [1 (:drift result)] [(first (report! re-run)) (:drift re-run)])
            "the failure is stable across a re-run rather than a one-shot
             warning: the exit stays 1 until the row itself is resolved")))))

(deftest carries-each-card-run-binding-onto-the-tracker-key
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          claimed (add! rt "active" {"kanban/card" "true" "kanban/run" "bla23"})
          aliased (add! rt "active" {"kanban/card" "true" "kanban/devflow" "py7pm"})
          both (add! rt "active" {"kanban/card" "true"
                                  "kanban/run" "bla23"
                                  "kanban/devflow" "stale"})
          {:keys [left-behind total]} (cut/rewrite! ds)]
      (is (= {:kanban/card "true" :kanban/run-id "bla23"} (attrs rt claimed))
          "the canonical run key takes the word the tracker seam joins on")
      (is (= {:kanban/card "true" :kanban/run-id "py7pm"} (attrs rt aliased))
          "the deprecated alias holds the only copy of the binding here, so it
           survives under the new word rather than going as redundant")
      (is (= {:kanban/card "true" :kanban/run-id "bla23"} (attrs rt both))
          "where the canonical key answers, the alias is the value the old
           spool's fallback reader had already stopped consulting: the binding
           the board honoured wins, and the alias does not overwrite it")
      (is (= 4 total) "three cards, four rows: the alias half is dropped too")
      (is (= {} left-behind)
          "nothing is left behind — the drop's declines are the sole-copy rows
           the rename carried, not rows the cutover skipped")
      (is (zero? (:total (cut/rewrite! ds)))
          "a re-run against the migrated world is a no-op"))))

(deftest reports-rows-and-is-idempotent
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          run (add! rt "active" {"workflow/phase" "review"
                                 "workflow/role" "molecule"
                                 "workflow/wisp" "true"})
          first-run (cut/rewrite! ds)
          second-run (cut/rewrite! ds)]
      (is (= {"workflow/phase -> workflow/form" 1
              "workflow/role: molecule -> root" 1
              "drop workflow/wisp" 1}
             (:changes first-run))
          "the report names each entry that touched a row")
      (is (= 3 (:total first-run)))
      (is (= {:changes {} :total 0 :left-behind {} :drift {}} second-run)
          "a re-run against a migrated world is a no-op")
      (is (= {:workflow/form "review" :workflow/role "root"} (attrs rt run))
          "the migrated shape is stable across a second run"))))

(deftest refuses-a-strand-carrying-both-sides-of-a-rename
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)]
      (add! rt "active" {"workflow/phase" "review" "workflow/form" "review"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"carry both the old and the new key"
                            (cut/rewrite! (datasource ctx)))
          "an inconsistent world fails loudly rather than raising a raw
           SQLite constraint violation"))))

(deftest validates-the-migration-registry-against-its-contract
  (testing "the shipped table satisfies the contract it publishes"
    (is (cut/validate-table! cut/migrations)))
  (testing "an entry the engine has no reading for"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :rename-column
                                                 :from "a" :to "b"}]))))
  (testing "a missing field"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :rename-key :from "a"}]))))
  (testing "a misspelled guard, which would otherwise rewrite unguarded"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :drop-key :key "k"
                                                 :when-sibling-ky "twin"}]))))
  (testing "a guard of the wrong type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :rename-key :from "a" :to "b"
                                                 :when-value-in ["x"]}]))))
  (testing "an enumeration no value could satisfy"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :rename-key :from "a" :to "b"
                                                 :when-value-in #{}}]))))
  (testing "two value guards, which would compose into a match on neither"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                          (cut/validate-table! [{:migration :rename-key :from "a" :to "b"
                                                 :when-value "x" :unless-value "y"}]))))
  (testing "the offending entry is named, not just the fact of a bad table"
    (let [entry {:migration :drop-key :key "k" :when-sibling {"a" "b" "c" "d"}}]
      (is (= entry (:entry (try (cut/validate-table! [{:migration :drop-key :key "ok"}
                                                      entry])
                                (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest refuses-a-malformed-table-before-any-sql
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          run (add! rt "active" {"workflow/phase" "review"})]
      (with-redefs [cut/migrations [{:migration :rename-key
                                     :from "workflow/phase" :to "workflow/form"}
                                    {:migration :rename-key :from "a" :to "b"
                                     :when-vlaue "x"}]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed migration entry"
                              (cut/rewrite! (datasource ctx)))))
      (is (= {:workflow/phase "review"} (attrs rt run))
          "the whole table is validated before the rewrite opens a transaction,
           so a typo in a later entry cannot half-migrate a world"))))

(deftest refuses-an-implicit-db-target
  (testing "no --db and no --workspace"
    (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {}))))
  (testing "an ambiguous db/workspace pair"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cut/resolve-db-path {:db "x" :workspace "y"}))))
  (testing "an explicit --db is honoured verbatim"
    (is (= "/tmp/x.sqlite" (cut/resolve-db-path {:db "/tmp/x.sqlite"})))))
