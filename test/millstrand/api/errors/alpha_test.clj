(ns millstrand.api.errors.alpha-test
  "Tests for millstrand.api.errors.alpha: the error factories that stamp the CLI's
  rendered affordances and reject the shapes it would silently ignore."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.api.errors.alpha :as errors]))

(defn- thrown
  "Return the ExceptionInfo `f` throws, failing the test when it returns."
  [f]
  (is (thrown? clojure.lang.ExceptionInfo (f))))

(defn- unusable
  "Return the affordance-check failure `f` raises over key `k`.

  Both outcomes are an ExceptionInfo — the check firing, and the factory
  building the error it was asked for — so a rejection test that only asserted
  `thrown?` would pass either way. The check names the key it refused."
  [k f]
  (let [e (thrown f)]
    (is (re-find (re-pattern (str "received an unusable " k)) (str (ex-message e)))
        (str "expected the " k " shape check to fire"))
    e))

(deftest not-found!-carries-the-token-and-the-available-list
  (let [e (thrown #(errors/not-found! "No such card"
                                      {:token "lyv34"
                                       :available ["lyv33" "sc94i"]
                                       :try "strand kanban board"}))]
    (is (= "No such card" (ex-message e)))
    (is (= {:token "lyv34" :available ["lyv33" "sc94i"] :try "strand kanban board"}
           (ex-data e)))))

(deftest factories-stamp-no-code-of-their-own
  (testing "an absent code stays absent, so the weaver's own inference stands"
    ;; A canonical-query lookup owes its callers query/not-found (SPEC-004.C36b),
    ;; which the socket infers only while the error carries no :code. A default
    ;; code here would replace that contract wholesale.
    (let [e (thrown #(errors/not-found! "no such query: agent-failure"
                                        {:token "agent-failure"
                                         :canonical-query "agent-failure"
                                         :available ["agent-failures"]}))]
      (is (not (contains? (ex-data e) :code)))))
  (testing "an author's own code passes through untouched"
    (let [e (thrown #(errors/not-found! "No such card"
                                        {:code "kanban/card-not-found" :token "x"}))]
      (is (= "kanban/card-not-found" (:code (ex-data e)))))))

(deftest details-outside-the-grammar-ride-along-untouched
  (let [e (thrown #(errors/not-found! "No such card"
                                      {:token "x" :op "kanban" :path [] :count 3}))]
    (is (= {:token "x" :op "kanban" :path [] :count 3} (ex-data e)))))

(deftest not-found!-insists-on-the-token-it-ranks-suggestions-against
  (let [e (thrown #(errors/not-found! "No such card" {:available ["a" "b"]}))]
    (is (re-find #"not-found! needs :token" (ex-message e)))
    (is (= [:token] (:missing (ex-data e))))))

(deftest available-rejects-every-shape-the-cli-would-drop
  ;; `stringList` (cli/internal/errfmt/errfmt.go:233) reads a JSON array and
  ;; keeps only its string items, so each of these renders as no list at all.
  (testing "not an array"
    (unusable :available #(errors/not-found! "x" {:token "t" :available "add, list"}))
    (unusable :available #(errors/not-found! "x" {:token "t" :available {:add 1}})))
  (testing "an array with nothing to show"
    (unusable :available #(errors/not-found! "x" {:token "t" :available []})))
  (testing "an array of values that are not names"
    (unusable :available #(errors/not-found! "x" {:token "t" :available [1 2]}))
    (unusable :available #(errors/not-found! "x" {:token "t" :available [{:n "add"}]}))))

(deftest available-accepts-the-name-types-the-wire-renders-as-text
  (testing "keywords and symbols reach the client as bare strings"
    (let [e (thrown #(errors/not-found! "x" {:token :add :available [:add 'list]}))]
      (is (= {:token :add :available [:add 'list]} (ex-data e)))))
  (testing "a set is a collection of names too"
    (let [e (thrown #(errors/not-found! "x" {:token "t" :available #{"add" "list"}}))]
      (is (= #{"add" "list"} (:available (ex-data e)))))))

(deftest invalid-argument!-insists-on-the-value-and-on-what-would-be-accepted
  (testing "prose is enough"
    (let [e (thrown #(errors/invalid-argument! "--priority is not a lane"
                                               {:token "p9" :expected "p1..p4"}))]
      (is (= {:token "p9" :expected "p1..p4"} (ex-data e)))))
  (testing "so is an enumerable set"
    (let [e (thrown #(errors/invalid-argument! "unknown lane"
                                               {:token "doing"
                                                :available ["pending" "claimed"]}))]
      (is (= ["pending" "claimed"] (:available (ex-data e))))))
  (testing "the rejected value is held to no shape, unlike a lookup token"
    ;; An argument is as often a number, a map, or nil as a name, and a factory
    ;; that cannot carry the offending value is worse than the message it fixes.
    (doseq [token [42 {:lane "doing"} [1 2] nil false ""]]
      (let [e (thrown #(errors/invalid-argument! "bad" {:token token :expected "p1"}))]
        (is (= token (:token (ex-data e)))))))
  (testing "a rejection that says nothing about what is valid is refused"
    (let [e (thrown #(errors/invalid-argument! "bad" {:token "p9"}))]
      (is (re-find #"needs :available or :expected" (ex-message e)))
      (is (= [:available :expected] (:missing (ex-data e))))))
  (testing "and so is one that does not name the offending value"
    (let [e (thrown #(errors/invalid-argument! "bad" {:expected "p1..p4"}))]
      (is (= [:token] (:missing (ex-data e)))))))

(deftest conflict!-insists-on-a-way-out
  (let [e (thrown #(errors/conflict! "Another coordinator holds the merge lock"
                                     {:try "strand land await abc12"
                                      :code :land/lock-held}))]
    (is (= "strand land await abc12" (:try (ex-data e))))
    (is (= :land/lock-held (:code (ex-data e)))))
  (let [e (thrown #(errors/conflict! "Another coordinator holds the lock" {}))]
    (is (re-find #"conflict! needs :try" (ex-message e)))))

(deftest try-rejects-what-pretty-mode-cannot-read
  ;; pretty.go reads `:try` with a string type assertion, so a non-string drops
  ;; back into the ordinary detail rows without ever becoming the `try:` line.
  (unusable :try #(errors/conflict! "x" {:try ["strand help"]}))
  (unusable :try #(errors/conflict! "x" {:try ""}))
  (unusable :try #(errors/conflict! "x" {:try :strand/help})))

(deftest not-found!-holds-its-lookup-token-to-the-name-grammar
  ;; The one place a token is shape-checked: you looked a name up, and the same
  ;; grammar governs the names it will be ranked against.
  (unusable :token #(errors/not-found! "x" {:token 42}))
  (unusable :token #(errors/not-found! "x" {:token nil}))
  (unusable :token #(errors/not-found! "x" {:token {:id "x"}})))

(deftest canonical-query-is-checked-like-any-other-rendered-key
  ;; It reaches the client as the plain-mode message tail and drives the
  ;; weaver's query/not-found inference, so a value that is not a name would
  ;; select that code and then vanish from the line the reader sees.
  (unusable :canonical-query #(errors/not-found! "x" {:token "t" :canonical-query 7}))
  (unusable :canonical-query #(errors/not-found! "x" {:token "t" :canonical-query ""}))
  (unusable :canonical-query #(errors/conflict! "x" {:try "y" :canonical-query {}}))
  (let [e (thrown #(errors/conflict! "x" {:try "y" :canonical-query :work}))]
    (is (= :work (:canonical-query (ex-data e))))))

(deftest code-accepts-the-names-the-envelope-carries-and-refuses-the-rest
  (testing "string, keyword, and symbol all render whole"
    (doseq [code ["kanban/card-not-found" :kanban/card-not-found 'kanban/card-not-found]]
      (let [e (thrown #(errors/not-found! "x" {:token "t" :code code}))]
        (is (= code (:code (ex-data e)))))))
  (testing "anything else would reach the client as domain/invalid-error-code"
    (unusable :code #(errors/not-found! "x" {:token "t" :code 42}))
    (unusable :code #(errors/not-found! "x" {:token "t" :code nil}))
    (unusable :code #(errors/not-found! "x" {:token "t" :code ""}))))

(deftest every-factory-threads-an-underlying-cause
  (let [cause (RuntimeException. "socket closed")]
    (doseq [throwing [#(errors/not-found! "x" {:token "t"} cause)
                      #(errors/invalid-argument! "x" {:token "t" :expected "y"} cause)
                      #(errors/conflict! "x" {:try "mill start"} cause)]]
      (is (identical? cause (ex-cause (thrown throwing)))))))

(deftest remedy-stamps-the-try-affordance-onto-any-details-map
  (is (= {:id "abc" :try "mill init"} (errors/remedy {:id "abc"} "mill init")))
  (is (thrown #(errors/remedy {:id "abc"} "")))
  (is (thrown #(errors/remedy {:id "abc"} ["mill" "init"])))
  (testing "assoc would quietly turn a non-map into one"
    (is (re-find #"expects a details map" (ex-message (thrown #(errors/remedy nil "mill")))))))

(deftest details-must-be-a-map
  (is (re-find #"expects a details map" (ex-message (thrown #(errors/not-found! "x" nil)))))
  (is (re-find #"expects a details map"
               (ex-message (thrown #(errors/conflict! "x" [:try "mill start"]))))))
