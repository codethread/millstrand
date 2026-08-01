// Package board reads epic and kanban state through the repo-local strand CLI.
//
// Everything here is read-only. Ralph never mutates the board; the agent runs
// it drives do that themselves. Payloads that do not match the shapes strand
// documents are refused rather than defaulted (TEN-003).
package board

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Attribute keys the kanban spool owns; ralph only ever reads them.
const (
	AttrType     = "kanban/type"
	AttrCard     = "kanban/card"
	AttrLane     = "kanban/lane"
	AttrPriority = "kanban/priority"
	AttrOwner    = "owner"
	AttrBranch   = "branch"
	AttrRalph    = "kanban.label/ralph"
)

// Strand lifecycle states. Anything else is a payload ralph refuses to act on.
const (
	StateActive   = "active"
	StateClosed   = "closed"
	StateReplaced = "replaced"
)

// ErrGate marks every refusal to prompt a model: a missing epic, the wrong card
// type, or a withdrawn ralph label. The loop treats it as a clean stop rather
// than a crash.
var ErrGate = errors.New("epic gate")

// Client invokes a strand binary and decodes its JSON.
type Client struct {
	// Bin is the strand executable; the repo-local ./bin/strand by default.
	Bin string
	// Workspace selects a non-default world, passed through as --workspace.
	Workspace string
	// Timeout bounds a single strand call.
	Timeout time.Duration
}

// Strand is the lean projection every strand read returns.
type Strand struct {
	ID         string                     `json:"id"`
	Title      string                     `json:"title"`
	State      string                     `json:"state"`
	CreatedAt  string                     `json:"created_at"`
	UpdatedAt  string                     `json:"updated_at"`
	Attributes map[string]json.RawMessage `json:"attributes"`
}

// Attr returns a scalar attribute as a string. Structured values (an omitted
// body, a nested map) read as empty: callers want them for display only.
func (s Strand) Attr(key string) string {
	value, _ := s.attr(key)
	return value
}

// attr also reports whether the key is present but holds something other than a
// scalar. The gate needs that distinction: an attribute it cannot read is a
// different problem from one nobody set.
func (s Strand) attr(key string) (value string, malformed bool) {
	raw, ok := s.Attributes[key]
	if !ok {
		return "", false
	}
	got, isScalar := scalar(raw)
	return got, !isScalar
}

// Labels returns the card's kanban.label/* keys, sorted.
func (s Strand) Labels() []string {
	var out []string
	for key, raw := range s.Attributes {
		name, ok := strings.CutPrefix(key, "kanban.label/")
		if value, _ := scalar(raw); ok && value == "true" {
			out = append(out, name)
		}
	}
	sort.Strings(out)
	return out
}

// scalar renders a JSON scalar for display, reporting false for anything that
// is not one: a nested map, a list, or an unreadable value.
func scalar(raw json.RawMessage) (string, bool) {
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return "", false
	}
	switch t := v.(type) {
	case string:
		return t, true
	case bool:
		return strconv.FormatBool(t), true
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64), true
	default:
		return "", false
	}
}

// Task is one slice of a feature card, carrying the status kanban derives from
// its dependency edges.
type Task struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Status string `json:"status"`
	State  string `json:"state"`
}

// Card is a feature or epic card as the board projects it.
type Card struct {
	ID       string
	Title    string
	Type     string
	Lane     string
	Priority string
	Owner    string
	Branch   string
	Epic     string
	State    string
	Labels   []string
	// Tasks and Ready are filled for cards under active work only; listing
	// them for every pending card would cost one strand call each.
	Tasks []Task
	Ready []Strand
}

// Snapshot is one poll of everything the UI shows about an epic.
type Snapshot struct {
	Epic     Strand
	Features []Card
	TakenAt  time.Time
}

// lanes orders the board's card groups the way the UI reads them: work in
// flight first, then the queue.
var lanes = []string{"claimed", "in_review", "pending", "refinement"}

type boardPayload struct {
	Claimed    []boardCard `json:"claimed"`
	InReview   []boardCard `json:"in_review"`
	Pending    []boardCard `json:"pending"`
	Refinement []boardCard `json:"refinement"`
}

type boardCard struct {
	ID       string   `json:"id"`
	Title    string   `json:"title"`
	Type     string   `json:"type"`
	Lane     string   `json:"lane"`
	Priority string   `json:"priority"`
	Owner    string   `json:"owner"`
	Branch   string   `json:"branch"`
	Epic     string   `json:"epic"`
	State    string   `json:"state"`
	Labels   []string `json:"labels"`
}

type cardPayload struct {
	Card  Strand   `json:"card"`
	Tasks []Task   `json:"tasks"`
	Ready []Strand `json:"ready"`
}

func (c Client) exec(ctx context.Context, args ...string) ([]byte, error) {
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	full := args
	if c.Workspace != "" {
		full = append([]string{"--workspace", c.Workspace}, args...)
	}
	cmd := exec.CommandContext(ctx, c.Bin, full...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return nil, fmt.Errorf("strand %s: %s", strings.Join(args, " "), msg)
	}
	return stdout.Bytes(), nil
}

// Show reads one strand.
func (c Client) Show(ctx context.Context, id string) (Strand, error) {
	out, err := c.exec(ctx, "show", id)
	if err != nil {
		return Strand{}, err
	}
	var s Strand
	if err := json.Unmarshal(out, &s); err != nil {
		return Strand{}, fmt.Errorf("strand show %s returned undecodable JSON: %w", id, err)
	}
	if s.ID != id {
		return Strand{}, fmt.Errorf("strand show %s returned strand %q", id, s.ID)
	}
	if s.Title == "" || s.State == "" {
		return Strand{}, fmt.Errorf("strand show %s returned a strand with no title or state", id)
	}
	return s, nil
}

// Gate reads the epic and refuses anything ralph must not drive: a card that is
// not an epic, or one whose ralph label has been withdrawn. Both scripts ran
// this check before every model prompt so that removing the label stops the
// loop; the binary keeps that contract.
func (c Client) Gate(ctx context.Context, id string) (Strand, error) {
	s, err := c.Show(ctx, id)
	if err != nil {
		return Strand{}, fmt.Errorf("%w: cannot read epic %s: %v", ErrGate, id, err)
	}
	if got, malformed := s.attr(AttrType); got != "epic" {
		return Strand{}, fmt.Errorf("%w: %s has %s=%s, expected epic", ErrGate, id, AttrType, unreadable(got, malformed))
	}
	if got, malformed := s.attr(AttrRalph); got != "true" {
		return Strand{}, fmt.Errorf("%w: %s has %s=%s, expected true", ErrGate, id, AttrRalph, unreadable(got, malformed))
	}
	switch s.State {
	case StateActive, StateClosed, StateReplaced:
	default:
		return Strand{}, fmt.Errorf("%w: %s has unexpected state %q", ErrGate, id, s.State)
	}
	return s, nil
}

// unreadable names why an attribute failed the gate, so a value the CLI could
// not render is not reported as one nobody set.
func unreadable(v string, malformed bool) string {
	switch {
	case malformed:
		return "<malformed>"
	case v == "":
		return "<missing>"
	default:
		return v
	}
}

// Snapshot polls the epic and the feature cards beneath it. Cards under active
// work also carry their tasks and ready frontier, which is what a watcher needs
// to see where the agent actually is.
func (c Client) Snapshot(ctx context.Context, epicID string) (Snapshot, error) {
	epic, err := c.Gate(ctx, epicID)
	if err != nil {
		return Snapshot{}, err
	}
	out, err := c.exec(ctx, "kanban", "board")
	if err != nil {
		return Snapshot{}, err
	}
	var payload boardPayload
	if err := json.Unmarshal(out, &payload); err != nil {
		return Snapshot{}, fmt.Errorf("kanban board returned undecodable JSON: %w", err)
	}

	grouped := map[string][]boardCard{
		"claimed":    payload.Claimed,
		"in_review":  payload.InReview,
		"pending":    payload.Pending,
		"refinement": payload.Refinement,
	}
	snap := Snapshot{Epic: epic, TakenAt: time.Now()}
	for _, lane := range lanes {
		for _, raw := range grouped[lane] {
			if raw.Epic != epicID {
				continue
			}
			if raw.ID == "" {
				return Snapshot{}, fmt.Errorf("kanban board returned a %s card under epic %s with no id", lane, epicID)
			}
			card := Card{
				ID: raw.ID, Title: raw.Title, Type: raw.Type, Lane: lane,
				Priority: raw.Priority, Owner: raw.Owner, Branch: raw.Branch,
				Epic: raw.Epic, State: raw.State, Labels: raw.Labels,
			}
			// Tasks are the resume signal, and only work in flight has any
			// worth reading; fetching them for the whole queue would cost a
			// strand call per pending card on every poll.
			if lane == "claimed" || lane == "in_review" {
				detail, err := c.CardDetail(ctx, raw.ID)
				if err != nil {
					return Snapshot{}, err
				}
				card.Tasks = detail.Tasks
				card.Ready = detail.Ready
			}
			snap.Features = append(snap.Features, card)
		}
	}
	return snap, nil
}

// CardDetail reads one card's resume view.
func (c Client) CardDetail(ctx context.Context, id string) (Card, error) {
	out, err := c.exec(ctx, "kanban", "card", id)
	if err != nil {
		return Card{}, err
	}
	var payload cardPayload
	if err := json.Unmarshal(out, &payload); err != nil {
		return Card{}, fmt.Errorf("kanban card %s returned undecodable JSON: %w", id, err)
	}
	if payload.Card.ID != id {
		return Card{}, fmt.Errorf("kanban card %s returned card %q", id, payload.Card.ID)
	}
	return Card{
		ID:       payload.Card.ID,
		Title:    payload.Card.Title,
		Type:     payload.Card.Attr(AttrType),
		Lane:     payload.Card.Attr(AttrLane),
		Priority: payload.Card.Attr(AttrPriority),
		Owner:    payload.Card.Attr(AttrOwner),
		Branch:   payload.Card.Attr(AttrBranch),
		State:    payload.Card.State,
		Labels:   payload.Card.Labels(),
		Tasks:    payload.Tasks,
		Ready:    payload.Ready,
	}, nil
}
