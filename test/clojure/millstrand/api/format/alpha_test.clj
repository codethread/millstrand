(ns millstrand.api.format.alpha-test
  "Behavior coverage for the blessed `|`-margin doc-block contract.

  `fill` and `reflow` back every prose-as-data surface in the tree, so the
  v1 promise is pinned through the blessed namespace rather than the core
  implementation: item splitting on a bare `|`, soft-wrap of flush-left
  prose, verbatim preservation of indented layout, indentation freedom for
  the enclosing form, and the loud bar-less failure."
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is]]
            [millstrand.api.format.alpha :as fmt]))

(deftest prose-renders-without-scope-and-preserves-markdown-after-baseline-dedent
  (is (= "# Consumer tooling\n\nRead the repository before changing it.\n  - Preserve deliberate list indentation.\n```sh\nstrand spool status\n```"
         (fmt/prose
          "
            # Consumer tooling

            Read the repository before changing it.
              - Preserve deliberate list indentation.
            ```sh
            strand spool status
            ```
            "))))

(deftest prose-interpolates-values-and-compact-json
  (is (= "Inspect /tmp/consumer.\n\n```json\n{\"git/url\":\"https://example.test/spool.git\",\"ready\":true}\n```"
         (fmt/prose
          "
            Inspect {worktree}.

            ```json
            {coordinate:json}
            ```
            "
          {"worktree" "/tmp/consumer"
           :coordinate {:git/url "https://example.test/spool.git"
                        :ready true}}))))

(deftest prose-fails-loudly-for-invalid-input-and-placeholders
  (doseq [[template scope expected-key]
          [[nil {} :template]
           ["text" nil :scope]
           ["{missing}" {} :placeholder]
           ["\n  first\n second\n" {} :baseline]]]
    (let [ex (try (fmt/prose template scope) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) (pr-str [template scope]))
      (is (contains? (ex-data ex) expected-key) (pr-str (ex-data ex)))))
  (let [ex (try (fmt/prose "{missing}") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (contains? (ex-data ex) :placeholder))))

(deftest prose-rejects-malformed-placeholders
  (doseq [template ["{worktree" "{1name}" "{name:}"]]
    (let [ex (try (fmt/prose template {:worktree "/tmp/worktree"
                                       :name "Millstrand"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) template)
      (is (= template (:template (ex-data ex))))
      (is (contains? (ex-data ex) :placeholder)))))

(deftest reflow-soft-wraps-one-paragraph
  (is (= "one paragraph of prose joined into a single line"
         (fmt/reflow "|one paragraph of prose
                      |joined into a single line"))))

(deftest reflow-ignores-enclosing-indentation-and-blank-bars
  (is (= "prose survives margins" (fmt/reflow "   |prose
                                               |
                                               |survives margins"))))

(deftest fill-splits-items-on-bare-bar
  (is (= ["first item soft wrapped" "second item"]
         (fmt/fill "|first item
                    |soft wrapped
                    |
                    |second item"))))

(deftest fill-keeps-indented-items-verbatim
  (is (= ["Run it like this:" "  strand add \"title\"\n  strand ready"]
         (fmt/fill "|Run it like this:
                    |
                    |  strand add \"title\"
                    |  strand ready"))))

(deftest invalid-blocks-fail-loudly-with-the-offending-value
  (doseq [bad ["no bars here" nil 42 [:not :a :string]]
          f [fmt/reflow fmt/fill]]
    (let [ex (try (f bad) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) (pr-str bad))
      (is (contains? (ex-data ex) :block))
      (is (= bad (:block (ex-data ex))))
      (is (contains? (ex-data ex) :explain)))))

(deftest generated-blocks-satisfy-declared-contracts
  ;; exercises the ::block generator against the fdefs, including reflow's
  ;; no-newline :fn property.
  (let [results (stest/check [`fmt/fill `fmt/reflow]
                             {:clojure.spec.test.check/opts {:num-tests 50}})]
    (is (= 2 (count results)))
    (doseq [result results]
      (is (true? (get-in result [:clojure.spec.test.check/ret :pass?]))
          (pr-str (:sym result))))))
