(ns skein.core.weaver.lifecycle
  "Lifecycle-gate invocation pipeline for weaver mutations.

  Builds the event and request context envelopes, runs validation-only hooks
  (including the socket `:payload/received` gate), and threads attribute values
  through transform hooks. Internal tier shared by the strand-lifecycle and
  batch/weave APIs; registration of hooks stays in the API layer."
  (:require [skein.core.db :as db]
            [skein.core.weaver.access :as access])
  (:import [java.time Instant]
           [java.util UUID]))

(defn event-base
  "Build the common event envelope fields for a weaver event type."
  [type]
  {:event/type type
   :event/id (str (UUID/randomUUID))
   :event/at (str (Instant/now))
   ;; Fixed contract value naming the weaver-API event origin (consumers assert
   ;; it); it is not this defining namespace.
   :event/source :skein.api.weaver.alpha})

(defn- hooks-for-type [runtime hook-type]
  ;; `sort-by` eagerly realizes one deref of the immutable effective projection,
  ;; so the whole gate runs against one hook snapshot: a hook that mutates the
  ;; registry mid-fold cannot change the set this invocation already began with,
  ;; and its replacement is seen only by a later invocation (DELTA-OlrDrt-001.CC9).
  (filter #(contains? (:types %) hook-type)
          (sort-by (juxt :order (comp pr-str :key))
                   (vals (access/hook-registry runtime)))))

(defn- cause-code [throwable]
  (loop [t throwable]
    (when t
      (let [data (ex-data t)]
        (or (:code data)
            (recur (ex-cause t)))))))

;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
(defn- hook-failure-data [hook-type {:keys [key] fn-sym :fn} throwable]
  (let [data (ex-data throwable)
        code (cause-code throwable)]
    (cond-> {:code "hook/failed"
             :hook/type hook-type
             :hook/key key
             :hook/fn fn-sym
             :exception/class (str (class throwable))
             :exception/message (ex-message throwable)}
      data (assoc :exception/data data)
      code (assoc :hook/cause-code code))))

(defn- hook-context [hook-type hook ctx]
  (assoc ctx
         :hook/type hook-type
         :hook/key (:key hook)
         :hook/fn (:fn hook)))

(defn- hook-callable
  "Return hook's callable under the runtime's spool classloader.

  Direct registration captured `:fn-value` eagerly; a module-published
  declaration carries only the `:fn` symbol, bound here at its declared
  `:hook/dispatch-start` moment. An unresolvable symbol fails loudly and
  surfaces through the standard `hook/failed` wrapper."
  [runtime {fn-sym :fn :keys [fn-value]}]
  (or fn-value
      (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
      (throw (ex-info "Hook function symbol cannot be resolved"
                      {:code "hook/unresolvable" :hook/fn fn-sym}))))

(defn- invoke-hook! [runtime hook-type hook ctx]
  (try
    (let [callable (hook-callable runtime hook)]
      (access/with-spool-classloader runtime #(callable ctx)))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn run-validation-hooks!
  "Run every validation-only hook registered for hook-type against ctx."
  [runtime hook-type ctx]
  (doseq [hook (hooks-for-type runtime hook-type)]
    (invoke-hook! runtime hook-type hook (hook-context hook-type hook ctx)))
  nil)

(defn run-payload-received-hooks!
  "Run validation-only hooks for a decoded JSON socket request payload."
  [runtime ctx]
  (run-validation-hooks! runtime :payload/received ctx))

(defn- require-transform-wrapper! [hook-type hook result]
  (when-not (and (map? result) (contains? result :hook/value))
    (throw (ex-info "Transform hook must return {:hook/value replacement}"
                    {:code "hook/invalid-return"
                     :hook/type hook-type
                     :hook/key (:key hook)
                     :hook/fn (:fn hook)
                     :hook/return result})))
  result)

(defn- require-json-attributes! [attrs]
  (db/->json attrs)
  attrs)

(defn- invoke-transform-hook! [runtime hook-type hook ctx]
  (try
    (require-json-attributes!
     (:hook/value
      (require-transform-wrapper!
       hook-type
       hook
       (let [callable (hook-callable runtime hook)]
         (access/with-spool-classloader runtime #(callable ctx))))))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn run-transform-hooks
  "Fold the transform hooks for hook-type over ctx's `:hook/value` attributes."
  [runtime hook-type ctx]
  (reduce (fn [value hook]
            (invoke-transform-hook! runtime hook-type hook (assoc (hook-context hook-type hook ctx) :hook/value value)))
          (require-json-attributes! (:hook/value ctx))
          (hooks-for-type runtime hook-type)))

(defn request-context
  "Build the trusted in-process request context for a weaver operation."
  [operation]
  {:request/source :weaver-api
   :request/operation operation})
