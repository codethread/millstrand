package errfmt

import (
	"bytes"
	"strings"
	"testing"
)

// The `available` lists the shipped weaver actually sends, captured from a live
// `strand` run against this checkout. They are the shapes the policy has to be
// right about: long flat name families, hyphenated names, and qualified
// `ns/term` names.
var (
	// src/millstrand/api/weaver/alpha.clj:518 (unknown op) and :448 (missing
	// replacement op) both send the registered op names.
	opNames = []string{
		"about", "add", "agent", "bench", "bins", "burn", "guide", "harness", "help",
		"kanban", "kanban-export", "land", "list", "note", "notes", "pattern", "prime",
		"query", "ready", "search", "show", "spool", "subgraph", "supersede", "update",
		"vocab", "weave", "workflow",
	}
	// src/millstrand/api/cli/alpha.clj:179,183 (missing/unknown subcommand) and
	// src/millstrand/core/weaver/help.clj:388 (unknown help verb) both walk the same
	// subcommand tree.
	kanbanSubcommands = []string{
		"about", "add", "board", "card", "claim", "finish", "label", "next", "note",
		"prime", "priority", "promote", "reopen", "review", "rework", "task",
	}
	// src/millstrand/core/query.clj:311 (unknown query).
	queryNames = []string{
		"agent-failures", "bench-runs", "blocked-deliveries", "devflow-runs",
		"kanban-cards", "kanban-epic-pending", "kanban-feature-work", "kanban-pending",
		"merge-lock", "merge-queue", "run-active", "stalled-code-gates",
		"stalled-shell-gates", "stalled-subagent-gates", "work", "workflow-runs",
	}
	// src/millstrand/api/patterns/alpha.clj:55 (unknown pattern).
	patternNames = []string{"agent-plan", "delegate-pipeline", "kanban-batch", "macros-demo"}
	// src/millstrand/api/runtime/glossary/alpha.clj:142 (missing replacement
	// outcome). Outcome names are qualified by contract, so a typo has a slash
	// in it and the distance is measured over the whole name.
	outcomeNames = []string{"lifecycle/abort", "lifecycle/timeout", "land/merge-conflict"}
	// src/millstrand/api/cli/internal/parsing.clj:219 (missing payload): payload
	// names are per-invocation, so this is one caller's set.
	payloadNames = []string{"attributes", "body", "note"}
	// src/millstrand/api/return_shape/alpha.clj:86,95 (return subcommand selection).
	returnSubcommands = []string{"board", "card", "task"}
)

func TestSuggestRanksTheRealAvailableLists(t *testing.T) {
	cases := []struct {
		name string
		in   Error
		want []string
	}{
		{
			// The op name is nowhere in "Operation not found", so the token has
			// to come out of the details map.
			name: "unknown op",
			in: Error{
				Message: "Operation not found",
				Command: []string{"kanbn", "card", "lyv33"},
				Details: map[string]any{"operation": "kanbn", "canonical-operation": "kanbn"},
			},
			want: []string{"kanban"},
		},
		{
			name: "missing replacement op",
			in: Error{
				Message: "Operation not registered; cannot replace",
				Details: map[string]any{"operation": "workflw"},
			},
			want: []string{"workflow"},
		},
		{
			// The message quotes the token, so the quoted-value tier answers.
			name: "unknown subcommand",
			in: Error{
				Message: `Unknown subcommand "clam"`,
				Command: []string{"kanban", "clam"},
				Details: map[string]any{"op": "kanban", "reason": "unknown-subcommand", "token": "clam"},
			},
			want: []string{"claim"},
		},
		{
			name: "unknown help verb",
			in: Error{
				Message: "Help verb not found",
				Command: []string{"help", "kanban", "revew"},
				Details: map[string]any{"op": "kanban", "token": "revew"},
			},
			want: []string{"review"},
		},
		{
			name: "unknown query",
			in: Error{
				Message: "Query not found",
				Command: []string{"list", "--query", "agent-failure"},
				Details: map[string]any{"query": "agent-failure", "canonical-query": "agent-failure"},
			},
			want: []string{"agent-failures"},
		},
		{
			name: "unknown pattern",
			in: Error{
				Message: "Pattern not found",
				Details: map[string]any{"pattern": "kanban-bath", "canonical-pattern": "kanban-bath"},
			},
			want: []string{"kanban-batch"},
		},
		{
			name: "missing replacement outcome",
			in: Error{
				Message: "Glossary outcome not registered; cannot replace",
				Details: map[string]any{"outcome": "lifecycle/timout"},
			},
			want: []string{"lifecycle/timeout"},
		},
		{
			name: "missing payload",
			in: Error{
				Message: "No payload attached for reference :stdin",
				Details: map[string]any{"op": "add", "payload-name": "bodyy"},
			},
			want: []string{"body"},
		},
		{
			name: "unknown return subcommand",
			in: Error{
				Message: "Return declaration routes deeper than the selection path",
				Details: map[string]any{"token": "carrd"},
			},
			want: []string{"card"},
		},
		{
			// A spool-authored op gets the same treatment for free, and gets
			// silence when its token is nobody's typo.
			name: "spool-authored list with no near miss",
			in: Error{
				Message: "Unknown widget",
				Details: map[string]any{"token": "gizmo"},
			},
			want: nil,
		},
		{
			name: "spool-authored list with a near miss",
			in: Error{
				Message: "Unknown widget",
				Details: map[string]any{"token": "alpa"},
			},
			want: []string{"alpha"},
		},
	}
	lists := map[string][]string{
		"unknown op":                            opNames,
		"missing replacement op":                opNames,
		"unknown subcommand":                    kanbanSubcommands,
		"unknown help verb":                     kanbanSubcommands,
		"unknown query":                         queryNames,
		"unknown pattern":                       patternNames,
		"missing replacement outcome":           outcomeNames,
		"missing payload":                       payloadNames,
		"unknown return subcommand":             returnSubcommands,
		"spool-authored list with no near miss": {"alpha", "beta"},
		"spool-authored list with a near miss":  {"alpha", "beta"},
	}
	for _, c := range cases {
		got := suggest(c.in, lists[c.name])
		if !sameNames(got, c.want) {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

// The last tier is reachable and load-bearing: an error whose message quotes
// nothing and whose argv tail is not the offender still gets its suggestion out
// of the details map. This is the tier `strand kanbn card lyv33` depends on.
func TestSuggestReachesTheUnquotedDetailsTier(t *testing.T) {
	e := Error{
		Message: "Operation not found",
		Command: []string{"kanbn", "card", "lyv33"},
		Details: map[string]any{"operation": "kanbn", "canonical-operation": "kanbn"},
	}
	if got := QuotedDetails(e.Message, e.Details); len(got) != 0 {
		t.Fatalf("precondition: the message must quote nothing, got %v", got)
	}
	if got := rank(e.Command[len(e.Command)-1], opNames); got != nil {
		t.Fatalf("precondition: the argv tail must not rank, got %v", got)
	}
	if got := suggest(e, opNames); !sameNames(got, []string{"kanban"}) {
		t.Fatalf("the unquoted details tier did not answer: got %v", got)
	}
}

// Provenance stands in for a contract the last tier does not have: metadata
// that happens to land near a real name is not evidence of a typo, so two
// candidates disagreeing means silence rather than a confident wrong answer.
func TestSuggestStaysSilentWhenTheUnquotedTierDisagrees(t *testing.T) {
	// `owner` is a near miss for `list` and the mistyped op is `nots` — the
	// renderer cannot tell which of the two the user got wrong.
	ambiguous := Error{
		Message: "Operation not found",
		Details: map[string]any{"operation": "nots", "owner": "lest"},
	}
	if got := suggest(ambiguous, opNames); got != nil {
		t.Fatalf("disagreeing candidates must suggest nothing, got %v", got)
	}
	// The same error without the stray metadata still answers.
	clean := Error{Message: "Operation not found", Details: map[string]any{"operation": "nots"}}
	if got := suggest(clean, opNames); !sameNames(got, []string{"note", "notes"}) {
		t.Fatalf("unambiguous candidate: got %v", got)
	}
	// Agreement across keys carrying the same word is one candidate, not two
	// voices, so the canonical/non-canonical pairs every producer sends still
	// answer.
	paired := Error{
		Message: "Query not found",
		Details: map[string]any{"query": "agent-failure", "canonical-query": "agent-failure"},
	}
	if got := suggest(paired, queryNames); !sameNames(got, []string{"agent-failures"}) {
		t.Fatalf("a word repeated under two keys is one candidate: got %v", got)
	}
}

// An earlier tier is provenance enough: a quoted value or the typed argv wins
// outright, and stray metadata never gets a vote.
func TestSuggestLetsEarlierTiersOverrideStrayMetadata(t *testing.T) {
	e := Error{
		Message: `Unknown subcommand "clam"`,
		Command: []string{"kanban", "clam"},
		Details: map[string]any{"token": "clam", "owner": "tsk", "reason": "unknown-subcommand"},
	}
	if got := suggest(e, kanbanSubcommands); !sameNames(got, []string{"claim"}) {
		t.Fatalf("the quoted token must win: got %v", got)
	}
}

// The canonical contexts at validation.clj:42, op_entry.clj:118, and
// return_shape/alpha.clj:101,180 send an empty list: no candidates, so no
// suggestion and no crash.
func TestSuggestHandlesListsWithNothingInThem(t *testing.T) {
	e := Error{Message: `Unknown subcommand "clam"`, Details: map[string]any{"token": "clam"}}
	if got := suggest(e, nil); got != nil {
		t.Errorf("nil list: got %v", got)
	}
	if got := suggest(e, []string{}); got != nil {
		t.Errorf("empty list: got %v", got)
	}
	if got := suggest(Error{}, opNames); got != nil {
		t.Errorf("empty error: got %v", got)
	}
}

func TestSuggestHoldsTheMatchingPolicy(t *testing.T) {
	cases := []struct {
		name  string
		token string
		names []string
		want  []string
	}{
		{"distance exactly two is a near miss", "abcd", []string{"abxy"}, []string{"abxy"}},
		{"distance three is not", "abcd", []string{"axyz"}, nil},
		{"ties keep the producer's order and stop at the cap", "cat", []string{"rat", "mat", "hat", "bat"}, []string{"rat", "mat", "hat"}},
		{"closer names come first whatever the list order", "abcd", []string{"abxy", "abcx"}, []string{"abcx", "abxy"}},
		{"case is not a difference", "KANBN", opNames, []string{"kanban"}},
		{"a token already on the list is not a typo", "kanban", opNames, nil},
		{"an empty token ranks nothing", "", opNames, nil},
	}
	for _, c := range cases {
		if got := rank(c.token, c.names); !sameNames(got, c.want) {
			t.Errorf("%s: rank(%q) = %v, want %v", c.name, c.token, got, c.want)
		}
	}
}

// Pretty puts the near misses where they are read first, and still prints the
// full list underneath.
func TestPrettyShowsSuggestionsAboveTheAvailableList(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Code:    fallbackCode,
		Message: `Unknown subcommand "clam"`,
		Command: []string{"kanban", "clam"},
		Details: map[string]any{
			"available": anyList(kanbanSubcommands),
			"op":        "kanban",
			"reason":    "unknown-subcommand",
			"token":     "clam",
		},
	})
	want := strings.Join([]string{
		`  x kanban: Unknown subcommand "clam"`,
		``,
		`    did you mean:`,
		`      claim`,
		``,
		`    available:`,
		`      about     add       board     card      claim     finish    label`,
		`      next      note      prime     priority  promote   reopen    review`,
		`      rework    task`,
		``,
		`    details:`,
		`      op      kanban`,
		`      reason  unknown-subcommand`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("layout:\n got:\n%s\nwant:\n%s", got, want)
	}
}

// Nothing ranks, so the list stands on its own exactly as it did before.
func TestPrettyFallsBackToThePlainListWhenNothingRanks(t *testing.T) {
	got := pretty(t, Error{
		Type:    "domain",
		Message: "Unknown widget",
		Details: map[string]any{"available": anyList([]string{"alpha", "beta"}), "token": "gizmo"},
	})
	want := strings.Join([]string{
		`  x Unknown widget`,
		``,
		`    available:`,
		`      alpha  beta`,
		``,
		`    details:`,
		`      token  gizmo`,
		``,
	}, "\n")
	if got != want {
		t.Fatalf("fallback:\n got:\n%s\nwant:\n%s", got, want)
	}
}

// Suggestions are a pretty-mode courtesy. Plain is the contract surface every
// script scrapes: the same error that grows a `did you mean` section at a
// terminal must render byte-for-byte as it always has, details JSON included.
func TestPlainNeverCarriesSuggestions(t *testing.T) {
	var out bytes.Buffer
	Render(&out, Error{
		Type:    "domain",
		Code:    fallbackCode,
		Message: `Unknown subcommand "clam"`,
		Command: []string{"kanban", "clam"},
		Details: map[string]any{
			"available": anyList([]string{"about", "add", "claim"}),
			"op":        "kanban",
			"token":     "clam",
		},
	}, Plain)
	want := `error: weaver domain error (domain/error): Unknown subcommand "clam" (available: about, add, claim) ` +
		`details={"available":["about","add","claim"],"op":"kanban","token":"clam"}` + "\n"
	if out.String() != want {
		t.Fatalf("plain drifted:\n got %q\nwant %q", out.String(), want)
	}
}

func anyList(names []string) []any {
	items := make([]any, 0, len(names))
	for _, name := range names {
		items = append(items, name)
	}
	return items
}
