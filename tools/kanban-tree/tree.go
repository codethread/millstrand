package main

import (
	"fmt"
	"sort"
	"strings"
)

// model is the kanban subtree reduced to what the renderer draws: the strands
// worth showing, who contains whom, and who is blocked by whom.
type model struct {
	rootID string
	nodes  map[string]strand
	// kids holds containment (parent-of) children per node, in board order.
	kids map[string][]string
	// blockers holds every depends-on target of a node that survived the
	// filters, in board order. A blocker must finish before the node can.
	blockers map[string][]string
	// scopes caches per-container dependency layouts; the renderer walks the
	// same scope several times and group indices must stay stable.
	scopes map[string]*scope
	// counts holds closed/total board children per node from before any
	// filtering, so a feature still reports its tasks in a view that does not
	// draw them.
	counts map[string][2]int
}

// filters say which strands survive into the model.
type filters struct {
	// all keeps execution strands (agent runs, workflow steps) too.
	all bool
	// tasks keeps task strands under an epic root; under a feature root the
	// tasks ARE the tree and this is always on.
	tasks bool
	// open drops closed strands whose whole subtree is closed.
	open bool
}

// build reduces one export to the model. A surviving strand whose own parent
// was dropped reattaches to its nearest surviving ancestor, so filtering an
// epic down to its features never orphans the features.
func build(ex export, f filters) (*model, error) {
	all := make(map[string]strand, len(ex.Strands))
	for _, s := range ex.Strands {
		all[s.ID] = s
	}
	root, ok := all[ex.RootID]
	if !ok {
		return nil, fmt.Errorf("export omits its own root %s", ex.RootID)
	}

	parent := make(map[string]string, len(ex.ParentOf))
	for _, e := range ex.ParentOf {
		if e.From != e.To {
			parent[e.To] = e.From
		}
	}

	keep := make(map[string]strand, len(all))
	for id, s := range all {
		switch {
		case id == root.ID, f.all, f.tasks && s.kanban():
			keep[id] = s
		case s.attr(attrCard) == "true":
			keep[id] = s
		}
	}
	if f.open {
		keep = pruneClosed(keep, parent, root.ID)
	}

	m := &model{
		rootID:   root.ID,
		nodes:    keep,
		kids:     map[string][]string{},
		blockers: map[string][]string{},
		scopes:   map[string]*scope{},
		counts:   countChildren(all, ex.ParentOf),
	}
	for id := range keep {
		if id == root.ID {
			continue
		}
		if anc, found := nearestKept(id, parent, keep); found {
			m.kids[anc] = append(m.kids[anc], id)
		}
	}
	for _, e := range ex.DependsOn {
		if e.From == e.To {
			continue
		}
		if _, ok := keep[e.From]; !ok {
			continue
		}
		if _, ok := keep[e.To]; !ok {
			continue
		}
		m.blockers[e.From] = append(m.blockers[e.From], e.To)
	}

	for id := range m.kids {
		m.sortIDs(m.kids[id])
	}
	for id := range m.blockers {
		m.sortIDs(m.blockers[id])
	}
	return m, nil
}

// pruneClosed drops closed strands whose whole containment subtree is closed.
// A closed card with live tasks under it stays: the path to open work is the
// point of the view.
func pruneClosed(keep map[string]strand, parent map[string]string, rootID string) map[string]strand {
	live := map[string]bool{rootID: true}
	for id, s := range keep {
		if s.State != stateActive {
			continue
		}
		for cur, hops := id, 0; cur != ""; cur, hops = parent[cur], hops+1 {
			if hops > len(keep) {
				break
			}
			live[cur] = true
		}
	}
	out := make(map[string]strand, len(keep))
	for id, s := range keep {
		if live[id] {
			out[id] = s
		}
	}
	return out
}

// nearestKept walks up the parent-of chain to the first surviving ancestor.
func nearestKept(id string, parent map[string]string, keep map[string]strand) (string, bool) {
	for cur, hops := parent[id], 0; cur != "" && hops <= len(parent); cur, hops = parent[cur], hops+1 {
		if _, ok := keep[cur]; ok {
			return cur, true
		}
	}
	return "", false
}

// sortIDs orders ids the way the board does: oldest first, id as tiebreak.
func (m *model) sortIDs(ids []string) {
	sort.SliceStable(ids, func(i, j int) bool {
		a, b := m.nodes[ids[i]], m.nodes[ids[j]]
		if a.CreatedAt != b.CreatedAt {
			return a.CreatedAt < b.CreatedAt
		}
		return a.ID < b.ID
	})
}

// scope is the dependency layout of one container's children: which children
// stand on their own, which hang under a single sibling that blocks them, and
// which are blocked by several siblings at once and so belong to no single
// branch of the tree.
type scope struct {
	tops []string
	// deps maps a blocker to the siblings it alone blocks.
	deps map[string][]string
	// shared lists the siblings blocked by more than one sibling, in the
	// order they are first reached.
	shared []sharedGroup
	// sharedBy maps a blocker to the shared groups it takes part in.
	sharedBy map[string][]int
}

// sharedGroup is one child whose blockers are several siblings at once.
type sharedGroup struct {
	blockers []string
	id       string
}

// layout arranges one container's children into the dependency shape the
// renderer walks. Only sibling depends-on edges shape the tree; a dependency
// that crosses containers is an annotation on the line, not a branch.
func (m *model) layout(container string) *scope {
	if cached, ok := m.scopes[container]; ok {
		return cached
	}
	children := m.kids[container]
	sibling := make(map[string]bool, len(children))
	for _, id := range children {
		sibling[id] = true
	}

	sc := &scope{deps: map[string][]string{}, sharedBy: map[string][]int{}}
	for _, id := range children {
		var within []string
		for _, b := range m.blockers[id] {
			if sibling[b] {
				within = append(within, b)
			}
		}
		switch len(within) {
		case 0:
			sc.tops = append(sc.tops, id)
		case 1:
			sc.deps[within[0]] = append(sc.deps[within[0]], id)
		default:
			idx := len(sc.shared)
			sc.shared = append(sc.shared, sharedGroup{blockers: within, id: id})
			for _, b := range within {
				sc.sharedBy[b] = append(sc.sharedBy[b], idx)
			}
		}
	}
	sc.promoteUnreached(children)
	m.scopes[container] = sc
	return sc
}

// promoteUnreached lifts children no branch can reach up to the top of the
// scope. Only a dependency cycle among siblings produces them, and a cycle
// must still be drawn: a card that never appears reads as a card nobody filed.
func (sc *scope) promoteUnreached(children []string) {
	for range children {
		seen := sc.reachable()
		promoted := false
		for _, id := range children {
			if !seen[id] {
				sc.tops = append(sc.tops, id)
				promoted = true
				break
			}
		}
		if !promoted {
			return
		}
	}
}

// reachable walks the scope from its top children through the dependencies
// they unblock.
func (sc *scope) reachable() map[string]bool {
	seen := map[string]bool{}
	queue := append([]string(nil), sc.tops...)
	for len(queue) > 0 {
		id := queue[0]
		queue = queue[1:]
		if seen[id] {
			continue
		}
		seen[id] = true
		queue = append(queue, sc.deps[id]...)
		for _, idx := range sc.sharedBy[id] {
			queue = append(queue, sc.shared[idx].id)
		}
	}
	return seen
}

// external returns the node's blockers that live outside its own container:
// real dependencies that no single branch of the tree can express.
func (m *model) external(container, id string) []string {
	sibling := make(map[string]bool, len(m.kids[container]))
	for _, s := range m.kids[container] {
		sibling[s] = true
	}
	var out []string
	for _, b := range m.blockers[id] {
		if !sibling[b] {
			out = append(out, b)
		}
	}
	return out
}

// kind is the display tag for a node: what sort of board object it is.
func (m *model) kind(id string) string {
	s := m.nodes[id]
	switch {
	case s.attr(attrType) == "epic":
		return "epic"
	case s.attr(attrCard) == "true":
		return "feat"
	case s.attr(attrTask) == "true":
		return "task"
	default:
		return "work"
	}
}

// status is the node's derived state: the recorded outcome once it is closed,
// the lane while a card is live, and for a live task whether its blockers
// still stand.
func (m *model) status(id string) string {
	s := m.nodes[id]
	if s.State != stateActive {
		switch outcome := s.attr(attrOutcome); outcome {
		case "abandoned":
			return "dropped"
		case "":
			if s.State == stateClosed {
				return "done"
			}
			return s.State
		default:
			return outcome
		}
	}
	if lane := s.attr(attrLane); lane != "" {
		if lane == "in_review" {
			return "review"
		}
		return lane
	}
	for _, b := range m.blockers[id] {
		if m.nodes[b].State == stateActive {
			return "blocked"
		}
	}
	return "ready"
}

// progress reports how many of a node's board children are closed. The counts
// come from the whole export, so an epic view that hides tasks still tells you
// how far each feature has got.
func (m *model) progress(id string) (done, total int) {
	count := m.counts[id]
	return count[0], count[1]
}

// countChildren tallies the cards and tasks directly under each strand, before
// any filter has run.
func countChildren(all map[string]strand, edges []edge) map[string][2]int {
	counts := map[string][2]int{}
	for _, e := range edges {
		child, ok := all[e.To]
		if !ok || !child.kanban() {
			continue
		}
		count := counts[e.From]
		count[1]++
		if child.State != stateActive {
			count[0]++
		}
		counts[e.From] = count
	}
	return counts
}

// groupKey renders a shared group's blocker set the way the section header
// prints it, so the same set always reads the same.
func groupKey(ids []string) string {
	quoted := make([]string, 0, len(ids))
	for _, id := range ids {
		quoted = append(quoted, "`"+id+"`")
	}
	return "(" + strings.Join(quoted, ", ") + ")"
}
