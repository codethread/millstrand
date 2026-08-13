(ns millstrand.api.millstrand.alpha
  "Authoring forms for Millstrand's owner-complete core kinds.

  Every family has an inert definition, a typed use form, and a bang shorthand
  that defines and selects. Definitions attach a reusable descriptor to the
  exact authored Var; only selection contributes to a module collector. The
  imperative runtime registration functions and `collect-kind!` remain the
  direct low-level surface."
  (:require [millstrand.api.authoring.alpha :as authoring]
            [millstrand.core.contribution :as contribution]))

(doseq [[kind entry-spec] [[:ops ::contribution/op-entry]
                           [:queries ::contribution/query-entry]
                           [:patterns ::contribution/pattern-entry]
                           [:hooks ::contribution/hook-entry]
                           [:events ::contribution/event-entry]
                           [:bins ::contribution/bin-entry]]]
  (authoring/register-registry-kind! kind entry-spec))

(authoring/defauthoring op [mode form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))
        entry (list 'millstrand.core.contribution/op-declaration
                    (list 'quote form-name) doc opts (list 'quote fn-sym))]
    {:name form-name
     :definition (list* 'defn form-name doc argv body)
     :kind :ops
     :key (str form-name)
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))

(authoring/defauthoring query [mode form-name doc opts definition]
  (let [entry (list 'millstrand.core.contribution/query-declaration
                    (list 'quote form-name) opts definition)]
    {:name form-name
     :definition (list 'def form-name doc entry)
     :kind :queries
     :key (str form-name)
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))

(authoring/defauthoring pattern [mode form-name doc opts argv & body]
  (let [fn-sym (symbol (str (ns-name *ns*)) (str form-name))
        entry (list 'millstrand.core.contribution/pattern-declaration
                    (list 'quote form-name) doc opts (list 'quote fn-sym))]
    {:name form-name
     :definition (list* 'defn form-name doc argv body)
     :kind :patterns
     :key (str form-name)
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))

(authoring/defauthoring hook [mode form-name doc opts argv & body]
  (let [key (keyword form-name)
        fn-sym (symbol (str (ns-name *ns*)) (str form-name))
        entry (list 'millstrand.core.contribution/hook-declaration
                    (list 'quote key) opts (list 'quote fn-sym))]
    {:name form-name
     :definition (list* 'defn form-name doc argv body)
     :kind :hooks
     :key key
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))

(authoring/defauthoring handler [mode form-name doc opts argv & body]
  (let [key (keyword form-name)
        fn-sym (symbol (str (ns-name *ns*)) (str form-name))
        entry (list 'millstrand.core.contribution/handler-declaration
                    (list 'quote key) opts (list 'quote fn-sym))]
    {:name form-name
     :definition (list* 'defn form-name doc argv body)
     :kind :events
     :key key
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))

(authoring/defauthoring bin [mode form-name doc opts]
  (let [entry (list 'millstrand.core.contribution/bin-declaration
                    (list 'quote form-name) doc opts
                    (list 'quote (ns-name *ns*)))]
    {:name form-name
     :definition (list 'def form-name doc entry)
     :kind :bins
     :key (str form-name)
     :entry entry
     :use-options (list 'select-keys opts #{:override?})}))
