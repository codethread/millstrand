(ns ct.agents.guide
  "The `guide` op: answer one millstrand-surface question from a delegated run.

  Millstrand's surface is deliberately self-describing — help, about, prime,
  workflow definitions, spool docs — which is cheap to keep true and expensive
  to read. An agent that discovers its way to one answer spends a large slice of
  the context it needed for the actual work.

  The op moves that spend onto a disposable seat: it spawns one guide run over
  `ct.spools.agent-run`, hands it the caller's question plus a distilled map of
  millstrand — the mental model, the discovery ladder, jq recipes over the help
  envelope, where the authored docs live — and returns the run's final message.
  The guide answers AND hands back the discovery commands the caller can re-run
  itself, so a repeat question costs nothing.

  The brief is millstrand-generic, not millstrand-src-specific: every path in it is
  either a live `strand`/`mill` command or discovered at run time, so any repo
  whose workspace registers this module gets a working guide over its own
  surface.

  Cross-harness by construction: the run is a tracked agent-run, so any harness
  (claude, codex, pi) can call `strand guide` and get the same answer, unlike a
  harness-native subagent."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.millstrand.alpha :as millstrand]
            [ct.spools.agent-run :as shuttle]))

(def ^:private default-harness
  "Seat the guide runs on unless `--harness` names another.

  luna-high is the cheapest seat that reads a live CLI surface accurately: the
  explore bench put luna's deep-trace and needle recall at the top of the cheap
  tier, and high effort buys the extra sweeps a surface map needs. Escalate to
  :sol-med by flag when a question is about design intent rather than surface."
  "luna-high")

(def ^:private timeout-secs
  "Seconds to block on the guide run before failing with its id.

  A surface question is a handful of read commands; a guide still running past
  this is stuck, and the caller wants the id back to await or kill rather than a
  silent block."
  600)

(def ^:private title-limit
  "Characters of the question kept in the run strand's title."
  70)

(s/def ::question (s/and string? (complement str/blank?)))
(s/def ::harness (s/and string? (complement str/blank?)))

(def ^:private millstrand-model
  "What millstrand is, distilled from the user reference so the guide starts
  oriented instead of spending its first sweeps rediscovering the model."
  [(format-alpha/reflow
    "|A weaver daemon owns one workspace: its SQLite strand store, named
     |queries, weave patterns, and registered ops.")
   (format-alpha/reflow
    "|A strand is {id, title, state active|closed|replaced, JSON attributes};
     |edges (depends-on, parent-of, ...) join strands; everything else —
     |status, owner, kind — is attribute convention, usually a spool's declared
     |vocabulary.")
   (format-alpha/reflow
    "|Each workspace assembles its own `strand` surface from config plus
     |spools, so the op catalog differs per repo: `strand help` is the ground
     |truth, never assume an op exists.")
   (format-alpha/reflow
    "|Without --workspace, strand targets the canonical repo's .millstrand
     |workspace; `strand --workspace <dir> ...` selects another world
     |explicitly.")
   (format-alpha/reflow
    "|Ops declare their surface as data: help output is generated from it, so
     |help never lies about invocation.")])

(def ^:private discovery-ladder
  "The live-surface commands the guide works from, cheapest tier first."
  ["strand help                            catalog of every registered op, one line each"
   "strand help <op> [<verb>...]           generated invocation truth: flags, positionals, types, returns, failure modes"
   "strand help --json <op> [<verb>...]    the raw envelope for jq (--json must lead the help surface)"
   "strand about <op>                      authored runbook prose: what the op means and who drives it"
   "strand prime <op>                      the run-first discipline for an op family (agent, workflow)"
   "strand <op> about|prime                some spool ops own these as real subcommands (kanban, spool, bench)"
   "mill prime millstrand|strand                offline orientation from the Millstrand source docs"
   "strand workflow list|show <name>       the definition is the truth for a registered workflow: stages, params, gates, choices"
   "strand agent harnesses|rosters         seats and reviewer rosters with their routing docs"
   "strand query list | strand pattern list | strand vocab   named queries, weave patterns, attribute vocabulary"])

(def ^:private jq-recipes
  "Filters that cut the help envelope down to the slice a question needs."
  ["verbs of an op        strand help --json <op> | jq -r '.node.children[].name'"
   "flags of a verb       strand help --json <op> <verb> | jq -r '.node.invocation.flags[] | \"\\(.flag)\\t\\(.type)\\t\\(.doc)\"'"
   "positionals           strand help --json <op> <verb> | jq -r '.node.invocation.positionals[] | \"\\(.name)\\t\\(.doc)\"'"
   "when to reach for it  strand help --json <op> <verb> | jq -r '.node.use-when[]?, .node.notes[]?'"
   "failure vocabulary    strand help --json <op> | jq -r '.glossary | to_entries[] | \"\\(.key): \\(.value)\"'"
   "where the code lives  strand help --json <op> | jq -r '.source | \"\\(.file):\\(.line)\"'"
   "op catalog            strand help --json | jq -r '.ops[].node.name'"])

(def ^:private docs-map
  "Where authored truth lives, found from any repo — no path here is assumed,
  each is printed by a command or discovered in the caller's checkout."
  ["millstrand source docs      `mill prime millstrand` prints their paths: the user reference (reference.md —"
   "                       Discovery tiers, Strand model, Queries), the spool index, and the config and"
   "                       customisation guides; grep there before calling anything undocumented"
   "the caller's repo      your working directory; its AGENTS.md / CLAUDE.md carry a seeded"
   "                       `## Millstrand / strand` section plus repo policy — read them for the"
   "                       conventions this workspace layers on top"
   "workspace config       the config dir (.millstrand/ unless another workspace is selected) holds init.clj"
   "                       — its header documents which modules activate, in what order — beside the"
   "                       workflow, harness, and query definitions it loads"
   "help envelope .source  file:line into the op's implementation — read it when the prose runs out;"
   "                       spool sources ship a generated *.api.md per-fn reference beside them"])

(def ^:private guide-brief
  "The guide seat's standing task, prepended to every question.

  The engine preamble already tells the run how to invoke `strand`; this adds
  the job, the map, and the shape of an answer that saves the caller more
  context than the question cost."
  (str/join
   "\n"
   (concat
    ["[millstrand guide]"
     (format-alpha/reflow
      "|Answer one question about the millstrand/strand surface for another agent
       |working in a repo that uses millstrand. Run the commands below as widely as
       |the question needs; you read the surface so your caller does not have
       |to. Answer from this workspace's live surface, not from memory of any
       |other repo's.")
     ""
     "[millstrand in five lines]"]
    millstrand-model
    [""
     "[discovery ladder — cheapest tier first]"]
    discovery-ladder
    [""
     "[jq recipes over the help envelope]"]
    jq-recipes
    [""
     "[where authored docs live]"]
    docs-map
    [""
     (format-alpha/reflow
      "|You are answering one question, not onboarding: skip tenets- and
       |philosophy-style reading and instead grep these docs for the op,
       |workflow, or attribute the question names — authored warnings
       |(parameter semantics, ordering, footguns) live beside them, not in
       |generated help.")
     ""
     "[answer]"
     (format-alpha/reflow
      "|Lead with the ordered commands the caller runs next, written plain:
       |strand <op> ....")
     (format-alpha/reflow
      "|Use plain `strand ...` commands in your answer. Never echo the
       |agent-run env prefix, selected workspace, or run id.")
     (format-alpha/reflow
      "|Never tell the caller to restart a canonical weaver: that requires
       |explicit user sign-off. Report a pending generation instead.")
     (format-alpha/reflow
      "|Verify every flag against `strand help`; cite file:line for behavior;
       |say what you could not verify.")
     (format-alpha/reflow
      "|Close with the discovery commands and jq filters that re-derive your
       |answer.")])))

(defn- guide-prompt
  "Return the full guide prompt for question."
  [question]
  (str guide-brief "\n\n[question from your caller]\n" question))

(defn- guide-title
  "Return the run strand title for question, clipped to a scannable length."
  [question]
  (let [clean (str/replace (str/trim question) #"\s+" " ")]
    (str "millstrand guide: "
         (if (> (count clean) title-limit)
           (str (subs clean 0 (dec title-limit)) "…")
           clean))))

(defn- guide-failure!
  "Throw the loud failure for a guide run that stalled or returned no answer."
  [run-id summary timed-out?]
  (throw (ex-info (if timed-out?
                    "guide run timed out"
                    "guide run returned no answer")
                  {:run run-id
                   :phase (:phase summary)
                   :error (:error summary)
                   :timeout-secs (when timed-out? timeout-secs)
                   :await (str "strand agent await " run-id)
                   :logs (str "strand agent logs " run-id " --tail 80")})))

(def ^:private guide-arg-spec
  "Declared surface for the repo-local `guide` op."
  {:op "guide"
   :doc (format-alpha/reflow
         "|Ask a delegated guide run about this workspace's millstrand/strand surface, and
          |get back both the answer and the discovery commands behind it.")
   :hook-class :mutating
   :deadline-class :unbounded
   :annotations
   {:use-when
    [(format-alpha/reflow
      "|Answering \"what is this op/workflow/attribute and what do I run next?\"
       |without spending your own context walking help, about, prime, and the
       |workflow definitions yourself.")]
    :notes
    [(format-alpha/reflow
      "|The guide is a tracked agent run, not a harness-native subagent, so every
       |harness gets the same guide and every answer stays inspectable with
       |`strand show <run-id>` and `strand agent logs <run-id>`.")
     (format-alpha/reflow
      "|Ask for the shape of answer you want: a plain explanation, the exact
       |commands to run, or the help query plus jq filter that finds the source
       |yourself. The default answer carries all three.")]}
   :flags {:harness {:type :string
                     :spec ::harness
                     :doc (str "Seat override for the guide run (default " default-harness ").")}}
   :positionals [{:name :question
                  :type :string
                  :required? true
                  :spec ::question
                  :doc "The question, in your own words; include what you are trying to do."}]})

(def ^:private guide-returns
  "Return shape: the answer and the run that produced it."
  {:type :map
   :required {:operation :string
              :run :string
              :answer :string}})

(def ^:private guide-about
  "Runbook context for `strand about guide`."
  (format-alpha/reflow
   "|`guide` spends a cheap seat's context so you keep yours: it spawns one
    |agent run on this workspace's guide seat, tasked with a distilled map of
    |millstrand (the mental model, the discovery ladder, jq recipes over the help
    |envelope, where the authored docs live), and blocks
    |until the run answers. The answer is advice about the surface — the op
    |source, specs, and workflow definitions it cites stay the contract. A run
    |that fails, stalls, or answers with nothing fails the op loudly with its
    |id, so `strand agent logs <run-id>` picks the thread back up."))

(millstrand/defop guide
  "Answer one millstrand-surface question from a delegated guide run."
  {:arg-spec guide-arg-spec
   :returns guide-returns
   :about guide-about}
  [ctx]
  (let [{:keys [question harness]} (:op/args ctx)
        seat (or harness default-harness)
        ;; launch where the caller stands: their repo's AGENTS.md and docs are
        ;; part of the surface, and the engine default (the workspace config
        ;; dir) sits below the repo root — kept only for in-process calls that
        ;; carry no envelope cwd
        cwd (or (:op/worktree-root ctx) (:op/cwd ctx))
        run (shuttle/spawn-run! (cond-> {:harness seat
                                         :prompt (guide-prompt question)
                                         :title (guide-title question)}
                                  cwd (assoc :cwd cwd)))
        {:keys [runs timed-out]} (shuttle/await-runs [(:id run)] {:timeout-secs timeout-secs})
        summary (first runs)]
    (if (or timed-out (str/blank? (:result summary)))
      (guide-failure! (:id run) summary timed-out)
      {:operation "guide" :run (:id run) :answer (:result summary)})))
