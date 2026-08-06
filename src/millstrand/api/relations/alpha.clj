(ns millstrand.api.relations.alpha
  "Advisory relation catalog for common Millstrand relation vocabulary.

  The catalog is source-visible data for agents, config, and REPL workflows. It
  is not a storage allowlist or runtime relation-semantics registry; valid
  relation names outside this catalog remain valid userland annotations."
  (:require [millstrand.api.format.alpha :as format-alpha]))

(def catalog
  "Shipped operational relation batteries and behavior-free annotation
  conventions."
  [{:relation "depends-on"
    :family :operational
    :direction "blocked --depends-on--> blocker"
    :declared-acyclic? true
    :help "Readiness battery: active targets block readiness."}
   {:relation "parent-of"
    :family :operational
    :direction "parent --parent-of--> child"
    :declared-acyclic? true
    :help "Structural hierarchy battery used by graph traversal helpers."}
   {:relation "supersedes"
    :family :operational
    :direction "replacement --supersedes--> replaced"
    :declared-acyclic? true
    :help "Replacement lineage battery written by the core supersession transaction."}
   {:relation "serves"
    :family :operational
    :direction "run --serves--> served-target"
    :declared-acyclic? true
    :help (format-alpha/reflow
           "|Engine-owned delegation battery: a run may serve at most one
            |target.")}
   {:relation "notes"
    :family :operational
    :direction "note --notes--> target"
    :declared-acyclic? true
    :help (format-alpha/reflow
           "|Append-only memory: a closed note strand attached to its target; its
            |note/text/note/at content is storage-enforced write-once.")}
   {:relation "related-to"
    :family :annotation
    :direction "source --related-to--> target"
    :declared-acyclic? false
    :help "Behavior-free loose association convention."}
   {:relation "duplicates"
    :family :annotation
    :direction "duplicate --duplicates--> canonical-or-other-duplicate"
    :declared-acyclic? false
    :help (format-alpha/reflow
           "|Behavior-free duplicate marker convention; use supersedes for
            |lifecycle replacement.")}
   {:relation "references"
    :family :annotation
    :direction "source --references--> referenced"
    :declared-acyclic? false
    :help "Behavior-free citation/reference convention."}
   {:relation "implements"
    :family :annotation
    :direction "implementation --implements--> requirement-or-design"
    :declared-acyclic? false
    :help "Behavior-free implementation trace convention."}
   {:relation "verifies"
    :family :annotation
    :direction "check-or-evidence --verifies--> claim-or-requirement"
    :declared-acyclic? false
    :help "Behavior-free verification/evidence convention."}
   {:relation "tracks"
    :family :annotation
    :direction "tracker --tracks--> tracked-subject"
    :declared-acyclic? false
    :help "Behavior-free tracking convention."}
   {:relation "caused-by"
    :family :annotation
    :direction "effect --caused-by--> cause"
    :declared-acyclic? false
    :help "Behavior-free causal-note convention."}])
