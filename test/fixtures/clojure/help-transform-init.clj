;; Self-contained default-help-transform fixture, loadable from a disposable
;; workspace's init.clj via `(load-file ".../test/fixtures/help-transform-init.clj")`.
;;
;; It registers an at-most-one default help transform that renders the canonical
;; help envelope to a plain-text line `RENDERED <op>: <node doc>`. With it elected,
;; `strand help <op>` must relay that raw text VERBATIM (no JSON quoting), while
;; `strand help --json <op>` bypasses the slot to the raw canonical envelope
;; (DELTA-Dtf-002.CC1, DELTA-Dtf-001.CC4). The smoke dispatcher surface loads this
;; exact file to exercise the socket -> mill -> client verbatim relay end to end;
;; keep it dependency-free beyond blessed core APIs.
;; ns name intentionally differs from the file path: this is a load-file fixture,
;; not a classpath namespace, so the -init filename cannot match.
(ns ^{:clj-kondo/ignore [:namespace-name-mismatch]} millstrand.test.fixtures.help-transform
  "Default-help-transform fixture rendering the envelope to text, loaded via load-file."
  (:require [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.help-transform.alpha :as help-transform]))

(defn render-help-text
  "Render the canonical help envelope to a single plain-text line."
  [env _presentation]
  (str "RENDERED " (get-in env [:operation :name]) ": " (get-in env [:node :doc])))

(help-transform/register-default-help-transform!
 (current/runtime)
 {:transform millstrand.test.fixtures.help-transform/render-help-text
  :owner 'millstrand.test.fixtures.help-transform/render-help-text})
