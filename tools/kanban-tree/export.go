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
	stateActive   = "active"
	stateClosed   = "closed"
	stateReplaced = "replaced"
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

func validateExport(out export) error {
	ids := make(map[string]bool, len(out.Strands))
	for _, s := range out.Strands {
		if s.ID == "" {
			return errors.New("strand carries no id")
		}
		if ids[s.ID] {
			return fmt.Errorf("duplicate strand id %s", s.ID)
		}
		if s.Title == "" {
			return fmt.Errorf("strand %s carries no title", s.ID)
		}
		if s.CreatedAt == "" {
			return fmt.Errorf("strand %s carries no created_at", s.ID)
		}
		switch s.State {
		case stateActive, stateClosed, stateReplaced:
		default:
			return fmt.Errorf("strand %s has invalid state %q", s.ID, s.State)
		}
		ids[s.ID] = true
	}
	if !ids[out.RootID] {
		return fmt.Errorf("export omits its own root %s", out.RootID)
	}
	if err := validateEdges("parent-of", out.ParentOf, ids); err != nil {
		return err
	}
	if err := validateParents(out.ParentOf); err != nil {
		return err
	}
	if err := validateEdges("depends-on", out.DependsOn, ids); err != nil {
		return err
	}
	return validateAttributes(out.Strands)
}

func validateParents(edges []edge) error {
	parents := make(map[string]string, len(edges))
	for _, edge := range edges {
		if parent, exists := parents[edge.To]; exists && parent != edge.From {
			return fmt.Errorf("strand %s has multiple parent-of parents", edge.To)
		}
		parents[edge.To] = edge.From
	}
	for child := range parents {
		seen := map[string]bool{}
		for current := child; current != ""; current = parents[current] {
			if seen[current] {
				return fmt.Errorf("parent-of cycle through %s", current)
			}
			seen[current] = true
		}
	}
	return nil
}

func validateEdges(relation string, edges []edge, ids map[string]bool) error {
	seen := make(map[string]bool, len(edges))
	for _, edge := range edges {
		if edge.From == "" || edge.To == "" {
			return fmt.Errorf("%s edge carries a blank endpoint", relation)
		}
		if edge.From == edge.To {
			return fmt.Errorf("%s edge %s points to itself", relation, edge.From)
		}
		if !ids[edge.From] || !ids[edge.To] {
			return fmt.Errorf("%s edge %s -> %s has an unknown endpoint", relation, edge.From, edge.To)
		}
		key := edge.From + "\x00" + edge.To
		if seen[key] {
			return fmt.Errorf("duplicate %s edge %s -> %s", relation, edge.From, edge.To)
		}
		seen[key] = true
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
	decoder := json.NewDecoder(r)
	if err := decoder.Decode(&out); err != nil {
		return export{}, fmt.Errorf("unreadable kanban-export payload: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			return export{}, errors.New("kanban-export payload carries more than one value")
		}
		return export{}, fmt.Errorf("unreadable trailing kanban-export payload: %w", err)
	}
	if out.RootID == "" {
		return export{}, errors.New("kanban-export payload carries no root-id")
	}
	if err := validateExport(out); err != nil {
		return export{}, fmt.Errorf("invalid kanban-export payload: %w", err)
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
