(ns millstrand.notes-test
  "Tests for the millstrand.api.notes.alpha cross-spool note primitive: `note!` links
  notes by a `notes` edge (never `note/for`) and `notes` walks that edge, ordered
  by the sub-second `note/at` stamp."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.notes.alpha :as notes]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.spools.test-support :as test-support :refer [with-runtime]]
            [millstrand.test.alpha :as test-alpha])
  (:import (java.time Instant)))

(defn- set-time!
  "Install a manual clock at `instant` so `note/at` is deterministic."
  [rt instant]
  (test-alpha/set-clock! rt (test-alpha/manual-clock instant)))

(defn- target! [rt]
  (:id (weaver/add! rt {:title "target" :state "active"})))

(deftest note!-writes-content-attrs-and-edge-not-note-for
  (with-runtime
    (fn [rt _config-dir]
      (let [_ (set-time! rt (Instant/parse "2026-01-01T00:00:00.500Z"))
            target (target! rt)
            {note-id :id target-out :target}
            (notes/note! rt target "remember this"
                         {:identity/by-identity "unresolved-kind-otter" :round 3})
            note (weaver/show rt note-id)]
        (is (= target target-out))
        (testing "content attributes land"
          (is (= "remember this" (get-in note [:attributes :note/text])))
          (is (= "2026-01-01T00:00:00.500Z" (get-in note [:attributes :note/at])))
          (is (= "unresolved-kind-otter"
                 (get-in note [:attributes :identity/by-identity])))
          (is (= 3 (get-in note [:attributes :note/round])))
          (is (nil? (get-in note [:attributes :note/by])))
          (is (= "closed" (:state note))))
        (testing "the link is the notes edge, never note/for"
          (is (nil? (get-in note [:attributes :note/for])))
          (is (= [note-id]
                 (mapv :from_strand_id (graph/incoming-edges rt [target] "notes")))))
        (testing "content stays immutable after birth"
          (is (thrown? clojure.lang.ExceptionInfo
                       (weaver/update! rt note-id
                                       {:attributes {:note/text "rewritten"}})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (weaver/update! rt note-id
                                       {:attributes {:note/at "2026-01-02T00:00:00Z"}}))))))))

(deftest note!-accepts-unresolved-identity-without-the-identity-module
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            {note-id :id} (notes/note! rt target "needs later enrichment"
                                       {:identity/by-identity "late-kind-otter"})
            anonymous-id (:id (notes/note! rt target "anonymous" {}))]
        (testing "the raw and absent actors need no registry lookup"
          (is (= "late-kind-otter"
                 (get-in (weaver/show rt note-id)
                         [:attributes :identity/by-identity])))
          (is (nil? (get-in (weaver/show rt anonymous-id)
                            [:attributes :identity/by-identity])))
          (is (empty? (graph/incoming-edges rt [note-id] "attributed"))))
        (testing "the composed identity module enriches durable attribution"
          (test-support/activate-spool! rt :millhouse/spools-identity
                                        'millhouse.spools.identity)
          (test-alpha/await-quiescent! rt)
          (is (= :unresolved
                 (:status (first (identity/inspect-attributions rt [note-id])))))
          (let [actor (weaver/add!
                       rt
                       {:title "late-kind-otter"
                        :attributes {:identity/session "true"
                                     :identity/id "late-kind-otter"
                                     :identity/harness "test"
                                     :identity/native-session-id "late-native"}})]
            (test-alpha/await-quiescent! rt)
            (is (= [(:id actor)]
                   (mapv :from_strand_id
                         (graph/incoming-edges rt [note-id] "attributed"))))))))))

(deftest note!-rejects-blank-text-and-missing-target
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (notes/note! rt target "   " {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (notes/note! rt "no-such-strand" "hi" {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity/by-identity"
                              (notes/note! rt target "legacy actor" {:by "alice"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity/by-identity"
                              (notes/note! rt target "unqualified actor"
                                           {:by-identity "alice"})))))))

(deftest notes-orders-by-note-at-across-writers-and-filters-by-round
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)]
        ;; Created out of note/at order and by different writers with divergent
        ;; decorating attrs: creation/id order (A, B, C) disagrees with note/at
        ;; order (B, C, A), so a green read proves the sort keys on note/at and
        ;; the walk ignores decorating attrs rather than filtering on note/for.
        (set-time! rt (Instant/parse "2026-01-01T00:00:00.300Z"))
        (notes/note! rt target "third by at" {:identity/by-identity "alice"})
        (set-time! rt (Instant/parse "2026-01-01T00:00:00.100Z"))
        (notes/note! rt target "first by at" {:identity/by-identity "bob" :round 2})
        (set-time! rt (Instant/parse "2026-01-01T00:00:00.200Z"))
        (notes/note! rt target "second by at" {:kanban/card "true"})
        (testing "every writer's note returns, ordered by note/at"
          (is (= ["first by at" "second by at" "third by at"]
                 (mapv :note (notes/notes rt target {}))))
          (is (= [{:note "first by at" :by-identity "bob" :round 2}
                  {:note "second by at"}
                  {:note "third by at" :by-identity "alice"}]
                 (mapv #(dissoc % :id :at) (notes/notes rt target {})))))
        (testing ":round filters to one writer's notes"
          (is (= ["first by at"] (mapv :note (notes/notes rt target {:round 2})))))))))

(deftest note-round-is-single-typed-and-ordering-is-chronological
  ;; regression (change-review-1a1d1cc7): a string round written through one
  ;; surface silently missed the other surface's int-round filter, and
  ;; lexicographic note/at comparison misordered mixed-precision timestamps
  ;; (Instant/toString drops trailing zero fraction digits).
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)]
        (testing "a non-integer round fails loudly on write and on read"
          (set-time! rt (Instant/parse "2026-01-01T00:00:00.100Z"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                                (notes/note! rt target "typed" {:round "2"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                                (notes/notes rt target {:round "2"}))))
        (testing "mixed fractional precision still sorts chronologically"
          ;; 00:00:01Z stringifies with no fraction; lexicographically
          ;; "2026-01-01T00:00:01Z" > "2026-01-01T00:00:01.900Z" is false but
          ;; "...:01Z" vs "...:01.100Z": 'Z' (0x5A) > '.' (0x2E), so the
          ;; fraction-less earlier-written 01Z would sort AFTER 01.100Z only
          ;; chronologically-wrongly under string compare when it is earlier.
          (set-time! rt (Instant/parse "2026-01-01T00:00:01.100Z"))
          (notes/note! rt target "later with fraction" {})
          (set-time! rt (Instant/parse "2026-01-01T00:00:01Z"))
          (notes/note! rt target "earlier without fraction" {})
          (is (= ["earlier without fraction" "later with fraction"]
                 (mapv :note (notes/notes rt target {})))))))))

(deftest target-deletion-cascades-the-edge-leaving-no-dangling-read
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            {note-id :id} (notes/note! rt target "outlives its target" {})]
        (is (= 1 (count (notes/notes rt target {}))))
        (graph/burn-by-ids! rt [target])
        (testing "the note strand survives but is unreachable through the read"
          (is (some? (weaver/show rt note-id)))
          (is (empty? (graph/incoming-edges rt [target] "notes")))
          (is (= [] (notes/notes rt target {}))))))))

(deftest writer-ref->prompt-renders-only-the-write-fragment
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            fragment (notes/writer-ref->prompt
                      {:target target
                       :decoration {"note/kind" "decision"
                                    "kanban/card" "true"}
                       :identity/by-identity "alice"})]
        (testing "the fragment is the write instruction with a text placeholder"
          (is (= (str "strand note " target
                      " \"<text>\" --by-identity alice --attr kanban/card=true --attr note/kind=decision")
                 fragment)))
        (testing "no read/agent notes string leaks into the fragment"
          (is (not (str/includes? fragment "agent notes")))))
      (testing "a malformed ref fails loudly naming the offending field"
        (doseq [bad-ref [nil "not-a-map" [:vec]]]
          (let [ex (try (notes/writer-ref->prompt bad-ref)
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (= :root (:field (ex-data ex))))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target"
                              (notes/writer-ref->prompt {:decoration {}
                                                         :identity/by-identity "x"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decoration"
                              (notes/writer-ref->prompt {:target "t" :decoration [:bad]}))))
      (testing "the removed :by writer field fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported"
                              (notes/writer-ref->prompt {:target "t" :by "x"})))))))
