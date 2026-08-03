package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveFiltersByRootType(t *testing.T) {
	ex := sketchExport()

	epicDefault, err := resolveFilters(ex, options{})
	if err != nil {
		t.Fatal(err)
	}
	if epicDefault.tasks {
		t.Error("an epic showed tasks without being asked")
	}

	epicTasks, err := resolveFilters(ex, options{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	if !epicTasks.tasks {
		t.Error("--tasks did not reach the filters")
	}

	feature := ex
	feature.RootID = "9duw"
	featureDefault, err := resolveFilters(feature, options{})
	if err != nil {
		t.Fatal(err)
	}
	if !featureDefault.tasks {
		t.Error("a feature must show its own tasks")
	}
	if _, err := resolveFilters(feature, options{tasks: true}); err == nil {
		t.Error("--tasks on a feature should be refused, not ignored")
	}
}

func TestResolveFiltersAllImpliesTasks(t *testing.T) {
	f, err := resolveFilters(sketchExport(), options{all: true})
	if err != nil {
		t.Fatal(err)
	}
	if !f.tasks {
		t.Error("--all kept execution strands but dropped tasks")
	}
}

// fakeStrand writes a stand-in strand binary answering with one canned payload,
// so the CLI seam is exercised without a weaver.
func fakeStrand(t *testing.T, stdout, stderr string, code int) string {
	t.Helper()
	dir := t.TempDir()
	bin := filepath.Join(dir, "strand")
	script := fmt.Sprintf("#!/bin/sh\nprintf '%%s' %q\nprintf '%%s' %q >&2\nexit %d\n", stdout, stderr, code)
	if err := os.WriteFile(bin, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return bin
}

func TestFetchDecodesAnExport(t *testing.T) {
	bin := fakeStrand(t, `{"root-id":"df2f","strands":[{"id":"df2f","title":"Root","state":"active","created_at":"now"}],"parent-of-edges":[],"depends-on-edges":[]}`, "", 0)
	ex, err := fetch(context.Background(), bin, "", "df2f")
	if err != nil {
		t.Fatal(err)
	}
	if ex.RootID != "df2f" {
		t.Errorf("root-id = %q", ex.RootID)
	}
}

func TestFetchReportsStrandsOwnError(t *testing.T) {
	bin := fakeStrand(t, "", "error: no such strand", 1)
	_, err := fetch(context.Background(), bin, "", "nope")
	if err == nil || !strings.Contains(err.Error(), "no such strand") {
		t.Fatalf("err = %v, want strand's own message", err)
	}
}

// A payload without a root is a read that failed halfway, not an empty board.
func TestFetchRefusesARootlessPayload(t *testing.T) {
	bin := fakeStrand(t, `{"strands":[]}`, "", 0)
	if _, err := fetch(context.Background(), bin, "", "df2f"); err == nil {
		t.Fatal("expected a refusal for a payload carrying no root-id")
	}
}

func TestDecodeRefusesMalformedDisplayAttributes(t *testing.T) {
	payload := `{"root-id":"df2f","strands":[{"id":"df2f","title":"Root","state":"active","created_at":"now","attributes":{"kanban/card":{}}}]}`
	_, err := decode(strings.NewReader(payload))
	if err == nil || !strings.Contains(err.Error(), `strand df2f attribute "kanban/card"`) {
		t.Fatalf("err = %v, want the strand and malformed attribute", err)
	}
}

func TestDecodeRefusesInvalidExportShapes(t *testing.T) {
	root := `{"id":"df2f","title":"Root","state":"active","created_at":"now"}`
	child := `{"id":"task1","title":"Task","state":"active","created_at":"later"}`
	for name, tc := range map[string]struct {
		payload string
		want    string
	}{
		"invalid state": {
			`{"root-id":"df2f","strands":[{"id":"df2f","title":"Root","state":"open","created_at":"now"}]}`,
			`invalid state "open"`,
		},
		"duplicate id": {
			`{"root-id":"df2f","strands":[` + root + `,` + root + `]}`,
			"duplicate strand id df2f",
		},
		"unknown edge": {
			`{"root-id":"df2f","strands":[` + root + `],"parent-of-edges":[{"from_strand_id":"df2f","to_strand_id":"nope"}]}`,
			"unknown endpoint",
		},
		"multiple parent": {
			`{"root-id":"df2f","strands":[` + root + `,` + child + `,{"id":"other","title":"Other","state":"active","created_at":"later"}],"parent-of-edges":[{"from_strand_id":"df2f","to_strand_id":"task1"},{"from_strand_id":"other","to_strand_id":"task1"}]}`,
			"multiple parent-of parents",
		},
		"trailing value": {
			`{"root-id":"df2f","strands":[` + root + `]} {}`,
			"more than one value",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := decode(strings.NewReader(tc.payload))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
		})
	}
}

// `kanban-tree <card> --tasks` reads naturally, so a flag after the card id
// must still be a flag rather than a second positional.
func TestParseInterspersedTakesFlagsEitherSideOfTheCardID(t *testing.T) {
	for _, args := range [][]string{
		{"df2f", "--tasks", "--width", "80"},
		{"--tasks", "df2f", "--width", "80"},
	} {
		flags := flag.NewFlagSet("kanban-tree", flag.ContinueOnError)
		flags.SetOutput(io.Discard)
		tasks := flags.Bool("tasks", false, "")
		width := flags.Int("width", -1, "")
		positionals, err := parseInterspersed(flags, args)
		if err != nil {
			t.Fatalf("%v: %v", args, err)
		}
		if len(positionals) != 1 || positionals[0] != "df2f" {
			t.Errorf("%v: positionals = %v, want the card id alone", args, positionals)
		}
		if !*tasks || *width != 80 {
			t.Errorf("%v: tasks = %v, width = %d", args, *tasks, *width)
		}
	}
}

func TestResolveWidthPrefersExplicitThenColumns(t *testing.T) {
	t.Setenv("COLUMNS", "77")
	for _, want := range []int{42, 77, 0} {
		flagged := want
		if want == 77 {
			flagged = -1
		}
		got, err := resolveWidth(flagged)
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Errorf("width = %d, want %d", got, want)
		}
	}
}

func TestResolveWidthRefusesInvalidColumns(t *testing.T) {
	t.Setenv("COLUMNS", "wide")
	if _, err := resolveWidth(-1); err == nil || !strings.Contains(err.Error(), `COLUMNS value "wide"`) {
		t.Fatalf("err = %v, want the invalid COLUMNS value", err)
	}
}
