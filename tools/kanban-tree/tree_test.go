package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// node builds one exported strand. created orders siblings, so tests spell it
// as a plain counter rather than a timestamp.
func node(id, title, state, created string, attrs map[string]string) strand {
	raw := make(map[string]json.RawMessage, len(attrs))
	for key, value := range attrs {
		encoded, err := json.Marshal(value)
		if err != nil {
			panic(err)
		}
		raw[key] = encoded
	}
	return strand{ID: id, Title: title, State: state, CreatedAt: created, Attributes: raw}
}

func epic(id, title, state string, attrs ...map[string]string) strand {
	return node(id, title, state, "0000", merge(map[string]string{attrCard: "true", attrType: "epic", attrLane: "pending"}, attrs...))
}

func feature(id, title, state, created string, attrs ...map[string]string) strand {
	return node(id, title, state, created, merge(map[string]string{attrCard: "true", attrType: "feature", attrLane: "pending"}, attrs...))
}

func task(id, title, state, created string, attrs ...map[string]string) strand {
	return node(id, title, state, created, merge(map[string]string{attrTask: "true"}, attrs...))
}

func merge(base map[string]string, extra ...map[string]string) map[string]string {
	for _, m := range extra {
		for key, value := range m {
			if value == "" {
				delete(base, key)
				continue
			}
			base[key] = value
		}
	}
	return base
}

// sketchExport is the shape the tool exists for: an epic over two features that
// both block a third, plus a task under one of them.
func sketchExport() export {
	return export{
		RootID: "df2f",
		Strands: []strand{
			epic("df2f", "Update the cli to go", stateActive),
			feature("owf2d", "Scan code for compat", stateClosed, "0001", map[string]string{attrLane: "", attrOutcome: "done"}),
			feature("9duw", "Check conflicts", stateActive, "0002", map[string]string{attrLane: "claimed", attrOwner: "opus"}),
			feature("ffwf", "Release", stateActive, "0003"),
			task("t1", "Read the go docs", stateActive, "0004"),
		},
		ParentOf: []edge{
			{From: "df2f", To: "owf2d"},
			{From: "df2f", To: "9duw"},
			{From: "df2f", To: "ffwf"},
			{From: "9duw", To: "t1"},
		},
		DependsOn: []edge{
			{From: "ffwf", To: "owf2d"},
			{From: "ffwf", To: "9duw"},
		},
	}
}

func TestBuildKeepsCardsAndDropsExecutionStrands(t *testing.T) {
	ex := sketchExport()
	ex.Strands = append(ex.Strands, node("run1", "Review ffwf: correctness", stateClosed, "0005", map[string]string{"agent-run/run": "true"}))
	ex.ParentOf = append(ex.ParentOf, edge{From: "ffwf", To: "run1"})

	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.nodes["run1"]; ok {
		t.Error("agent run strand survived the default filters")
	}
	if _, ok := m.nodes["t1"]; !ok {
		t.Error("task strand was dropped with --tasks on")
	}

	all, err := build(ex, filters{all: true, tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := all.nodes["run1"]; !ok {
		t.Error("--all dropped an agent run strand")
	}
}

func TestBuildWithoutTasksReattachesNothingButKeepsFeatures(t *testing.T) {
	m, err := build(sketchExport(), filters{})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.nodes["t1"]; ok {
		t.Error("task survived an epic view that did not ask for tasks")
	}
	if got, want := len(m.kids["df2f"]), 3; got != want {
		t.Errorf("epic children = %d, want %d", got, want)
	}
}

// A task whose feature is filtered out must still reach the tree: it reattaches
// to the nearest surviving ancestor rather than vanishing.
func TestBuildReattachesUnderNearestKeptAncestor(t *testing.T) {
	ex := sketchExport()
	ex.Strands = append(ex.Strands, node("mid", "devflow run", stateActive, "0006", nil))
	ex.ParentOf = append(ex.ParentOf,
		edge{From: "ffwf", To: "mid"},
		edge{From: "mid", To: "t2"},
	)
	ex.Strands = append(ex.Strands, task("t2", "Cut the tag", stateActive, "0007"))

	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	kids := strings.Join(m.kids["ffwf"], ",")
	if kids != "t2" {
		t.Errorf("ffwf children = %q, want the reattached task", kids)
	}
}

func TestLayoutSplitsSoleAndSharedDependents(t *testing.T) {
	m, err := build(sketchExport(), filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	sc := m.layout("df2f")
	if got := strings.Join(sc.tops, ","); got != "owf2d,9duw" {
		t.Errorf("tops = %q, want the two unblocked features", got)
	}
	if len(sc.deps) != 0 {
		t.Errorf("deps = %v, want no sole-blocker nesting", sc.deps)
	}
	if len(sc.shared) != 1 || sc.shared[0].id != "ffwf" {
		t.Fatalf("shared = %v, want the doubly blocked feature", sc.shared)
	}
	if got := groupKey(sc.shared[0].blockers); got != "(`owf2d`, `9duw`)" {
		t.Errorf("group key = %q", got)
	}
}

func TestLayoutNestsSoleDependent(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = []edge{{From: "ffwf", To: "9duw"}}
	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	sc := m.layout("df2f")
	if got := strings.Join(sc.deps["9duw"], ","); got != "ffwf" {
		t.Errorf("9duw unblocks %q, want ffwf", got)
	}
	if len(sc.shared) != 0 {
		t.Errorf("shared = %v, want none", sc.shared)
	}
}

// A dependency cycle is still work someone filed: every card in it must reach
// the tree instead of falling out of the layout.
func TestLayoutPromotesCycleMembers(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = []edge{{From: "owf2d", To: "9duw"}, {From: "9duw", To: "owf2d"}}
	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	reached := m.layout("df2f").reachable()
	for _, id := range []string{"owf2d", "9duw", "ffwf"} {
		if !reached[id] {
			t.Errorf("%s is unreachable in a cyclic scope", id)
		}
	}
}

func TestExternalBlockersAreThoseOutsideTheContainer(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = append(ex.DependsOn, edge{From: "t1", To: "owf2d"})
	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(m.external("9duw", "t1"), ","); got != "owf2d" {
		t.Errorf("external blockers = %q, want owf2d", got)
	}
	if got := m.external("df2f", "ffwf"); len(got) != 0 {
		t.Errorf("sibling blockers leaked into the annotation: %v", got)
	}
}

func TestStatusDerivation(t *testing.T) {
	ex := sketchExport()
	ex.Strands = append(ex.Strands,
		feature("drop", "Abandoned idea", stateClosed, "0008", map[string]string{attrLane: "", attrOutcome: "abandoned"}),
		task("t2", "Cut the tag", stateActive, "0009"),
	)
	ex.ParentOf = append(ex.ParentOf, edge{From: "df2f", To: "drop"}, edge{From: "9duw", To: "t2"})
	ex.DependsOn = append(ex.DependsOn, edge{From: "t2", To: "t1"})

	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	for id, want := range map[string]string{
		"owf2d": "done",
		"9duw":  "claimed",
		"ffwf":  "pending",
		"drop":  "dropped",
		"t1":    "ready",
		"t2":    "blocked",
	} {
		if got := m.status(id); got != want {
			t.Errorf("status(%s) = %q, want %q", id, got, want)
		}
	}
}

func TestOpenFilterKeepsClosedAncestorsOfLiveWork(t *testing.T) {
	ex := sketchExport()
	ex.Strands = append(ex.Strands, task("t2", "Cut the tag", stateActive, "0009"))
	ex.ParentOf = append(ex.ParentOf, edge{From: "owf2d", To: "t2"})

	m, err := build(ex, filters{tasks: true, open: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.nodes["owf2d"]; !ok {
		t.Error("closed feature with a live task was pruned")
	}
	if _, ok := m.nodes["t2"]; !ok {
		t.Error("live task under a closed feature was pruned")
	}

	ex.ParentOf = ex.ParentOf[:len(ex.ParentOf)-1]
	ex.Strands = ex.Strands[:len(ex.Strands)-1]
	m, err = build(ex, filters{tasks: true, open: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.nodes["owf2d"]; ok {
		t.Error("fully closed feature survived --open")
	}
}

func TestBuildRefusesAnExportMissingItsRoot(t *testing.T) {
	ex := sketchExport()
	ex.RootID = "nope"
	if _, err := build(ex, filters{}); err == nil {
		t.Fatal("expected an error for a root that is not in the payload")
	}
}
