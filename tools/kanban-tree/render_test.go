package main

import (
	"strings"
	"testing"
)

func renderExport(t *testing.T, ex export, f filters) string {
	t.Helper()
	return renderThemed(t, ex, f, unicodeTheme())
}

func renderThemed(t *testing.T, ex export, f filters, th theme) string {
	t.Helper()
	m, err := build(ex, f)
	if err != nil {
		t.Fatal(err)
	}
	return render(m, th, false, 0)
}

// The whole layout is the contract here, so this one asserts the drawing
// itself: contained children under plain elbows, sole dependents under arrows,
// and the doubly blocked feature stubbed in the tree and expanded once under
// the blocker set that gates it.
func TestRenderSketchShape(t *testing.T) {
	got := renderExport(t, sketchExport(), filters{tasks: true})
	want := strings.Join([]string{
		"○ `df2f` Update the cli to go  epic · 1/3 · pending",
		"├── ✓ `owf2d` Scan code for compat  feat · done",
		"│   └─▶ ○ `ffwf` …",
		"└── ◐ `9duw` Check conflicts  feat · 0/1 · claimed @opus",
		"    ├── ● `t1` Read the go docs  task · ready",
		"    └─▶ ○ `ffwf` …",
		"",
		"── blocked by `owf2d` + `9duw`",
		"└─▶ ○ `ffwf` Release  feat · pending",
		"",
		"5 items · ○ 2 pending · ◐ 1 claimed · ✓ 1 done · ● 1 ready",
		"└── contains   └─▶ blocked by the line above   … expanded below",
		"",
	}, "\n")
	if got != want {
		t.Errorf("render mismatch\n got:\n%s\nwant:\n%s", got, want)
	}
}

// The ASCII theme has to carry the same information for a terminal that cannot
// draw the box characters.
func TestRenderASCIIThemeDrawsTheSameShape(t *testing.T) {
	got := renderThemed(t, sketchExport(), filters{tasks: true}, asciiTheme())
	want := strings.Join([]string{
		"[ ] `df2f` Update the cli to go  epic . 1/3 . pending",
		"|-- [x] `owf2d` Scan code for compat  feat . done",
		"|   `-> [ ] `ffwf` ...",
		"`-- [~] `9duw` Check conflicts  feat . 0/1 . claimed @opus",
		"    |-- [>] `t1` Read the go docs  task . ready",
		"    `-> [ ] `ffwf` ...",
		"",
		"-- blocked by `owf2d` + `9duw`",
		"`-> [ ] `ffwf` Release  feat . pending",
		"",
		"5 items . [ ] 2 pending . [~] 1 claimed . [x] 1 done . [>] 1 ready",
		"`-- contains   `-> blocked by the line above   ... expanded below",
		"",
	}, "\n")
	if got != want {
		t.Errorf("render mismatch\n got:\n%s\nwant:\n%s", got, want)
	}
}

func TestRenderWithoutTasksStopsAtFeatures(t *testing.T) {
	got := renderExport(t, sketchExport(), filters{})
	if strings.Contains(got, "`t1`") {
		t.Errorf("task drawn in a features-only view:\n%s", got)
	}
	// The feature still reports the task the view is not drawing.
	if !strings.Contains(got, "◐ `9duw` Check conflicts  feat · 0/1 · claimed @opus") {
		t.Errorf("feature line missing:\n%s", got)
	}
}

func TestRenderAnnotatesBlockersOutsideTheBranch(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = append(ex.DependsOn, edge{From: "t1", To: "owf2d"})
	got := renderExport(t, ex, filters{tasks: true})
	if !strings.Contains(got, "`t1` Read the go docs  task · ready · needs `owf2d`") {
		t.Errorf("cross-container dependency not annotated:\n%s", got)
	}
}

// A cycle must terminate the walk rather than draw itself forever.
func TestRenderMarksDependencyCycles(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = []edge{{From: "owf2d", To: "9duw"}, {From: "9duw", To: "owf2d"}}
	got := renderExport(t, ex, filters{tasks: true})
	if !strings.Contains(got, "cycle") {
		t.Errorf("cycle not marked:\n%s", got)
	}
	if strings.Count(got, "`9duw` Check conflicts") != 1 {
		t.Errorf("cyclic node drawn more than once:\n%s", got)
	}
}

// A section is emitted once however many blockers stub it.
func TestRenderEmitsOneSectionPerSharedNode(t *testing.T) {
	got := renderExport(t, sketchExport(), filters{tasks: true})
	if n := strings.Count(got, "── blocked by `owf2d` + `9duw`"); n != 1 {
		t.Errorf("shared section drawn %d times, want 1:\n%s", n, got)
	}
	if n := strings.Count(got, "`ffwf` …"); n != 2 {
		t.Errorf("shared stub drawn %d times, want one per blocker:\n%s", n, got)
	}
}

func TestRenderLegendOnlyMentionsWhatItDrew(t *testing.T) {
	ex := sketchExport()
	ex.DependsOn = nil
	got := renderExport(t, ex, filters{tasks: true})
	if strings.Contains(got, "expanded below") {
		t.Errorf("legend advertises stubs that were never drawn:\n%s", got)
	}
	if strings.Contains(got, "blocked by the line above") {
		t.Errorf("legend advertises dependency edges that were never drawn:\n%s", got)
	}
}

func TestClipKeepsColourBalanced(t *testing.T) {
	plain := clip("abcdefghij", 8, "...")
	if plain != "abcde..." {
		t.Errorf("clip(plain) = %q", plain)
	}
	if strings.Contains(plain, "\x1b") {
		t.Errorf("clip added an escape to plain text: %q", plain)
	}
	coloured := clip("\x1b[2mabcdefghij\x1b[0m", 8, "...")
	if !strings.HasSuffix(coloured, "..."+ansiReset) {
		t.Errorf("clip(coloured) = %q, want a closed colour run", coloured)
	}
	if got := visibleLen(coloured); got != 8 {
		t.Errorf("visible length = %d, want 8", got)
	}
}

func TestClipLeavesShortLinesAlone(t *testing.T) {
	if got := clip("abc", 8, "..."); got != "abc" {
		t.Errorf("clip = %q, want the input untouched", got)
	}
	if got := clip("abcdefghij", 2, "..."); got != "abcdefghij" {
		t.Errorf("clip = %q, want no clipping below a usable budget", got)
	}
}

func TestRenderClipsToWidth(t *testing.T) {
	m, err := build(sketchExport(), filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(render(m, unicodeTheme(), true, 40), "\n") {
		if visibleLen(line) > 40 {
			t.Errorf("line exceeds the width budget: %q", line)
		}
	}
}

// Within a width budget the detail column is right aligned, and the title is
// what gives way to make room for it.
func TestRenderAlignsTheDetailColumn(t *testing.T) {
	ex := sketchExport()
	ex.Strands[1].Title = strings.Repeat("long ", 30)
	m, err := build(ex, filters{tasks: true})
	if err != nil {
		t.Fatal(err)
	}
	var ends []int
	for _, line := range strings.Split(render(m, unicodeTheme(), false, 60), "\n") {
		if strings.Contains(line, "feat ·") || strings.Contains(line, "epic ·") {
			if visibleLen(line) != 60 {
				t.Errorf("detail column not flush at the margin: %q", line)
			}
			ends = append(ends, visibleLen(line))
		}
	}
	if len(ends) == 0 {
		t.Fatal("no detail columns drawn")
	}
}
