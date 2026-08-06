package board

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeStrand writes a stand-in strand binary that answers from canned payloads.
// Keys are the argv the client builds, with spaces and slashes flattened, so a
// test spells its fixtures the same way it spells the calls it expects.
func fakeStrand(t *testing.T, responses map[string]string) Client {
	t.Helper()
	dir := t.TempDir()
	for key, body := range responses {
		path := filepath.Join(dir, "resp_"+flatten(key)+".json")
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	script := fmt.Sprintf(`#!/bin/sh
# Drop the dispatcher-level workspace flag the client may prepend.
while [ "$1" = "--workspace" ]; do shift 2; done
key="$(printf '%%s' "$*" | tr ' /' '__')"
dir=%q
if [ -f "$dir/override_$key.json" ]; then cat "$dir/override_$key.json"; exit 0; fi
if [ -f "$dir/resp_$key.json" ]; then cat "$dir/resp_$key.json"; exit 0; fi
echo "fake strand: unexpected call: $*" >&2
exit 1
`, dir)
	bin := filepath.Join(dir, "strand")
	if err := os.WriteFile(bin, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return Client{Bin: bin}
}

func flatten(key string) string {
	return strings.NewReplacer(" ", "_", "/", "_").Replace(key)
}

const activeEpic = `{"id":"e1","title":"Epic one","state":"active",
	"attributes":{"kanban/type":"epic","kanban/card":"true","kanban.label/ralph":"true","kanban/priority":"p2"}}`

func TestGateAccepts(t *testing.T) {
	c := fakeStrand(t, map[string]string{"show e1": activeEpic})
	epic, err := c.Gate(context.Background(), "e1")
	if err != nil {
		t.Fatalf("Gate: %v", err)
	}
	if epic.Title != "Epic one" || epic.State != StateActive {
		t.Errorf("epic = %+v", epic)
	}
	if got := epic.Labels(); len(got) != 1 || got[0] != "ralph" {
		t.Errorf("labels = %v, want [ralph]", got)
	}
}

func TestGateRefusals(t *testing.T) {
	cases := []struct {
		name    string
		payload string
		want    string
	}{
		{
			name:    "not an epic",
			payload: `{"id":"e1","title":"A feature","state":"active","attributes":{"kanban/type":"feature","kanban.label/ralph":"true"}}`,
			want:    "expected epic",
		},
		{
			// Removing the label is how a human stops the loop from outside, so
			// this refusal has to hold.
			name:    "ralph label withdrawn",
			payload: `{"id":"e1","title":"Epic one","state":"active","attributes":{"kanban/type":"epic"}}`,
			want:    "kanban.label/ralph=<missing>",
		},
		{
			name:    "wrong strand returned",
			payload: `{"id":"other","title":"Epic one","state":"active","attributes":{"kanban/type":"epic","kanban.label/ralph":"true"}}`,
			want:    "returned strand",
		},
		{
			name:    "unexpected lifecycle state",
			payload: `{"id":"e1","title":"Epic one","state":"sleeping","attributes":{"kanban/type":"epic","kanban.label/ralph":"true"}}`,
			want:    "unexpected state",
		},
		{
			name:    "undecodable payload",
			payload: `not json`,
			want:    "cannot read epic",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := fakeStrand(t, map[string]string{"show e1": tc.payload})
			_, err := c.Gate(context.Background(), "e1")
			if err == nil {
				t.Fatal("expected a refusal")
			}
			if !errors.Is(err, ErrGate) {
				t.Errorf("error must wrap ErrGate, got %v", err)
			}
			if !strings.Contains(err.Error(), tc.want) {
				t.Errorf("error = %q, want it to mention %q", err, tc.want)
			}
		})
	}
}

func TestGateReadsClosedEpics(t *testing.T) {
	// A closed epic passes the gate; it is the loop that decides to stop.
	c := fakeStrand(t, map[string]string{
		"show e1": `{"id":"e1","title":"Epic one","state":"closed","attributes":{"kanban/type":"epic","kanban.label/ralph":"true"}}`,
	})
	epic, err := c.Gate(context.Background(), "e1")
	if err != nil {
		t.Fatalf("Gate: %v", err)
	}
	if epic.State != StateClosed {
		t.Errorf("state = %q", epic.State)
	}
}

func TestSnapshotFiltersToTheEpicAndDetailsActiveWork(t *testing.T) {
	c := fakeStrand(t, map[string]string{
		"show e1": activeEpic,
		"kanban board": `{"claimed":[
			{"id":"f1","title":"Claimed feature","epic":"e1","priority":"p2","owner":"opus","branch":"f1-work","state":"active"},
			{"id":"x1","title":"Someone else's card","epic":"other","priority":"p3","state":"active"}],
		 "in_review":[],
		 "pending":[{"id":"f2","title":"Queued feature","epic":"e1","priority":"p3","state":"active"}],
		 "refinement":[{"id":"f3","title":"Half-formed idea","epic":"e1","priority":"p4","state":"active"}]}`,
		"kanban card f1": `{"card":{"id":"f1","title":"Claimed feature","state":"active",
			"attributes":{"kanban/lane":"claimed","kanban/priority":"p2","owner":"opus","branch":"f1-work"}},
			"tasks":[{"id":"t1","title":"Do the thing","status":"doing","state":"active"}],
			"ready":[{"id":"r1","title":"Ready work","state":"active","attributes":{}}]}`,
	})

	snap, err := c.Snapshot(context.Background(), "e1")
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	var ids []string
	for _, f := range snap.Features {
		ids = append(ids, f.ID+":"+f.Lane)
	}
	// Work in flight sorts ahead of the queue, and other epics are excluded.
	want := "f1:claimed f2:pending f3:refinement"
	if got := strings.Join(ids, " "); got != want {
		t.Fatalf("features = %q, want %q", got, want)
	}

	claimed := snap.Features[0]
	if len(claimed.Tasks) != 1 || claimed.Tasks[0].Status != "doing" {
		t.Errorf("claimed card must carry its tasks, got %+v", claimed.Tasks)
	}
	if len(claimed.Ready) != 1 || claimed.Ready[0].ID != "r1" {
		t.Errorf("claimed card must carry its ready frontier, got %+v", claimed.Ready)
	}
	// Pending cards are listed but never detailed: the fake would have failed
	// the call, so reaching here proves no `kanban card f2` was made.
	if len(snap.Features[1].Tasks) != 0 {
		t.Error("pending cards must not be detailed")
	}
}

func TestAttrIgnoresStructuredValues(t *testing.T) {
	c := fakeStrand(t, map[string]string{
		"show e1": `{"id":"e1","title":"Epic one","state":"active","attributes":{
			"kanban/type":"epic","kanban.label/ralph":"true",
			"body":{"bytes":3852,"millstrand/omitted":true},"kanban/count":7}}`,
	})
	epic, err := c.Gate(context.Background(), "e1")
	if err != nil {
		t.Fatalf("Gate: %v", err)
	}
	if got := epic.Attr("body"); got != "" {
		t.Errorf("an omitted body must read empty, got %q", got)
	}
	if got := epic.Attr("kanban/count"); got != "7" {
		t.Errorf("numeric attributes should render, got %q", got)
	}
	if got := epic.Attr("nope"); got != "" {
		t.Errorf("missing attributes must read empty, got %q", got)
	}
}

func TestGateNamesAMalformedAttribute(t *testing.T) {
	// A value the CLI could not render must not be reported as one nobody set;
	// the two problems have different fixes.
	c := fakeStrand(t, map[string]string{
		"show e1": `{"id":"e1","title":"Epic one","state":"active","attributes":{
			"kanban/type":{"nested":"map"},"kanban.label/ralph":"true"}}`,
	})
	_, err := c.Gate(context.Background(), "e1")
	if err == nil || !strings.Contains(err.Error(), "<malformed>") {
		t.Fatalf("err = %v, want it to report a malformed kanban/type", err)
	}
}

func TestCardDetailRefusesAnotherCard(t *testing.T) {
	c := fakeStrand(t, map[string]string{
		"kanban card f1": `{"card":{"id":"f2","title":"Some other card","state":"active","attributes":{}},"tasks":[],"ready":[]}`,
	})
	_, err := c.CardDetail(context.Background(), "f1")
	if err == nil || !strings.Contains(err.Error(), `returned card "f2"`) {
		t.Fatalf("err = %v, want a refusal naming the card that came back", err)
	}
}

func TestSnapshotRefusesACardWithNoID(t *testing.T) {
	c := fakeStrand(t, map[string]string{
		"show e1":      activeEpic,
		"kanban board": `{"claimed":[],"in_review":[],"pending":[{"title":"Nameless","epic":"e1"}],"refinement":[]}`,
	})
	_, err := c.Snapshot(context.Background(), "e1")
	if err == nil || !strings.Contains(err.Error(), "no id") {
		t.Fatalf("err = %v, want a refusal for the card with no id", err)
	}
}
