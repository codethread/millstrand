package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
)

// Attribute keys the kanban spool owns; kanban-tree only ever reads them.
const (
	attrCard     = "kanban/card"
	attrTask     = "kanban/task"
	attrType     = "kanban/type"
	attrLane     = "kanban/lane"
	attrOutcome  = "kanban/outcome"
	attrPriority = "kanban/priority"
	attrOwner    = "owner"
)

// Strand lifecycle states.
const (
	stateActive = "active"
	stateClosed = "closed"
)

// strand is the lean projection `kanban-export` returns for every node.
type strand struct {
	ID         string                     `json:"id"`
	Title      string                     `json:"title"`
	State      string                     `json:"state"`
	CreatedAt  string                     `json:"created_at"`
	Attributes map[string]json.RawMessage `json:"attributes"`
}

// displayAttributes are the scalar values kanban-tree reads. Validate them
// where the export enters so corrupt board data cannot become a plausible tree.
var displayAttributes = []string{
	attrCard, attrTask, attrType, attrLane, attrOutcome, attrPriority, attrOwner,
}

// attr returns a validated scalar attribute as a string.
func (s strand) attr(key string) string {
	raw, ok := s.Attributes[key]
	if !ok {
		return ""
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		panic(fmt.Sprintf("validated attribute %q on %s: %v", key, s.ID, err))
	}
	switch t := v.(type) {
	case string:
		return t
	case bool:
		return strconv.FormatBool(t)
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	default:
		panic(fmt.Sprintf("validated attribute %q on %s has unsupported %T value", key, s.ID, v))
	}
}

func validateAttributes(strands []strand) error {
	for _, s := range strands {
		for _, key := range displayAttributes {
			raw, ok := s.Attributes[key]
			if !ok {
				continue
			}
			var v any
			if err := json.Unmarshal(raw, &v); err != nil {
				return fmt.Errorf("strand %s attribute %q: %w", s.ID, key, err)
			}
			switch v.(type) {
			case string, bool, float64:
			default:
				return fmt.Errorf("strand %s attribute %q has unsupported %T value", s.ID, key, v)
			}
		}
	}
	return nil
}

// kanban reports whether the strand is board furniture — a card or a task —
// rather than one of the execution strands that hang under a card.
func (s strand) kanban() bool {
	return s.attr(attrCard) == "true" || s.attr(attrTask) == "true"
}

// edge is one directed relation between two strands in the exported subtree.
// A depends-on edge points from the blocked strand to the one blocking it.
type edge struct {
	From string `json:"from_strand_id"`
	To   string `json:"to_strand_id"`
}

// export is the `strand kanban-export` payload: a card's parent-of subtree
// with the depends-on edges internal to it.
type export struct {
	RootID    string   `json:"root-id"`
	Strands   []strand `json:"strands"`
	ParentOf  []edge   `json:"parent-of-edges"`
	DependsOn []edge   `json:"depends-on-edges"`
}

// decode reads an export payload from a reader. A payload that does not carry
// a root back is refused rather than rendered as an empty tree.
func decode(r io.Reader) (export, error) {
	var out export
	if err := json.NewDecoder(r).Decode(&out); err != nil {
		return export{}, fmt.Errorf("unreadable kanban-export payload: %w", err)
	}
	if out.RootID == "" {
		return export{}, errors.New("kanban-export payload carries no root-id")
	}
	if err := validateAttributes(out.Strands); err != nil {
		return export{}, fmt.Errorf("invalid kanban-export attribute: %w", err)
	}
	return out, nil
}

// fetch reads one card's subtree through the strand CLI.
func fetch(ctx context.Context, bin, workspace, cardID string) (export, error) {
	args := []string{}
	if workspace != "" {
		args = append(args, "--workspace", workspace)
	}
	args = append(args, "kanban-export", cardID)

	cmd := exec.CommandContext(ctx, bin, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return export{}, fmt.Errorf("strand kanban-export %s: %s", cardID, msg)
	}

	out, err := decode(bytes.NewReader(stdout.Bytes()))
	if err != nil {
		return export{}, fmt.Errorf("strand kanban-export %s: %w", cardID, err)
	}
	return out, nil
}
