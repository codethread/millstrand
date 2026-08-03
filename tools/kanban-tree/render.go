package main

import (
	"fmt"
	"sort"
	"strings"
)

// minTitle is the narrowest a title gets squeezed before the trailing metadata
// is dropped instead: below this the line stops saying anything useful.
const minTitle = 24

// itemKind says why a line sits under the one above it.
type itemKind int

const (
	itemContain itemKind = iota // the parent contains it
	itemDepends                 // it is blocked by the parent alone
	itemStub                    // it is blocked by several siblings; expanded below
)

// item is one line to draw, plus the scope whose dependency layout governs
// what hangs beneath it.
type item struct {
	id        string
	container string
	kind      itemKind
	group     int
}

// section is a deferred expansion: a node several siblings block, printed
// under its blocker set once the tree above it is drawn.
type section struct {
	container string
	group     int
}

type renderer struct {
	m      *model
	theme  theme
	out    *strings.Builder
	colour bool
	width  int

	drawn    map[string]bool
	queued   map[string]bool
	pending  []section
	statuses map[string]int
	usedDeps bool
	usedStub bool
}

// render draws the whole view: the containment tree of the root, then one
// section per node whose blockers fork across branches, then the key to what
// was drawn.
func render(m *model, t theme, colour bool, width int) string {
	r := &renderer{
		m:        m,
		theme:    t,
		out:      &strings.Builder{},
		colour:   colour,
		width:    width,
		drawn:    map[string]bool{},
		queued:   map[string]bool{},
		statuses: map[string]int{},
	}

	r.node("", item{id: m.rootID, kind: itemContain})
	r.drawn[m.rootID] = true
	r.children("", m.rootID, "")

	for i := 0; i < len(r.pending); i++ {
		sec := r.pending[i]
		group := r.m.layout(sec.container).shared[sec.group]
		r.out.WriteString("\n")
		r.sectionRule(group.blockers)
		r.branch(item{id: group.id, container: sec.container, kind: itemDepends}, "", true)
	}

	r.footer()
	return r.out.String()
}

// children draws everything hanging under one node: what it contains, the
// siblings it alone unblocks, and stubs for the siblings it shares.
func (r *renderer) children(container, id, prefix string) {
	items := r.childItems(container, id)
	for i, it := range items {
		r.branch(it, prefix, i == len(items)-1)
	}
}

// childItems collects the lines that belong under one node, in draw order:
// contained children first, then the work this node unblocks.
func (r *renderer) childItems(container, id string) []item {
	var items []item
	for _, kid := range r.m.layout(id).tops {
		items = append(items, item{id: kid, container: id, kind: itemContain})
	}
	if container == "" {
		return items
	}
	sc := r.m.layout(container)
	for _, dep := range sc.deps[id] {
		items = append(items, item{id: dep, container: container, kind: itemDepends})
	}
	for _, idx := range sc.sharedBy[id] {
		items = append(items, item{id: sc.shared[idx].id, container: container, kind: itemStub, group: idx})
	}
	return items
}

// branch draws one line and, unless it is a stub or a repeat, whatever hangs
// beneath it.
func (r *renderer) branch(it item, prefix string, last bool) {
	// A stub stands for a dependency too: the node hangs off this blocker and
	// several others, so it draws the same arrow as an exclusive dependent.
	connector := r.theme.endContain
	if it.kind == itemContain {
		if !last {
			connector = r.theme.teeContain
		}
	} else {
		r.usedDeps = true
		connector = r.theme.endDepends
		if !last {
			connector = r.theme.teeDepends
		}
	}

	childPrefix := prefix + r.theme.pipePrefix
	if last {
		childPrefix = prefix + r.theme.blankPrefix
	}

	if it.kind == itemStub {
		r.usedStub = true
		r.queue(it.container, it.group)
		r.line(prefix+connector, r.stub(it.id, r.theme.stub), "")
		return
	}
	if r.drawn[it.id] {
		// A node reached twice can only be a dependency cycle: name it once
		// and stop, rather than drawing the loop forever.
		r.line(prefix+connector, r.stub(it.id, r.theme.cycle), "")
		return
	}

	r.drawn[it.id] = true
	r.node(prefix+connector, it)
	r.children(it.container, it.id, childPrefix)
}

// queue registers a shared node's section once, whichever blocker reaches it
// first.
func (r *renderer) queue(container string, group int) {
	key := container + "#" + fmt.Sprint(group)
	if r.queued[key] {
		return
	}
	r.queued[key] = true
	r.pending = append(r.pending, section{container: container, group: group})
}

// node draws one strand: its status glyph, id, and title, with the board
// detail trailing on the right.
func (r *renderer) node(prefix string, it item) {
	s := r.m.nodes[it.id]
	status := r.m.status(it.id)
	r.statuses[status]++

	title := s.Title
	if it.id == r.m.rootID {
		title = r.wrap(ansiBold, title)
	}
	left := r.paint(status, r.theme.glyph(status)) + " " + r.dim("`"+it.id+"`") + " " + title
	r.line(prefix, left, r.meta(it, status))
}

// meta is the trailing detail column: what kind of card it is, a deliberate
// priority, how much of it is closed, its status, and who holds it.
func (r *renderer) meta(it item, status string) string {
	s := r.m.nodes[it.id]
	parts := []string{r.dim(r.m.kind(it.id))}
	// p3 is the default every card carries, so only a deliberate priority is
	// worth a column.
	if priority := s.attr(attrPriority); priority != "" && priority != "p3" {
		parts = append(parts, r.priority(priority))
	}
	if done, total := r.m.progress(it.id); total > 0 {
		parts = append(parts, r.dim(fmt.Sprintf("%d/%d", done, total)))
	}
	shown := status
	if owner := s.attr(attrOwner); owner != "" && s.State == stateActive {
		shown += " @" + owner
	}
	parts = append(parts, r.paint(status, shown))

	if it.container != "" {
		if external := r.m.external(it.container, it.id); len(external) > 0 {
			quoted := make([]string, 0, len(external))
			for _, b := range external {
				quoted = append(quoted, "`"+b+"`")
			}
			parts = append(parts, r.dim("needs "+strings.Join(quoted, " + ")))
		}
	}
	return strings.Join(parts, r.dim(r.theme.sep))
}

// stub renders a placeholder line for a node drawn elsewhere. It still carries
// the node's status glyph, so a branch tells you how its shared work is doing
// without scrolling to the expansion.
func (r *renderer) stub(id, marker string) string {
	status := r.m.status(id)
	return r.paint(status, r.theme.glyph(status)) + " " + r.dim("`"+id+"` "+marker)
}

// sectionRule heads a deferred expansion with the blocker set that gates it.
func (r *renderer) sectionRule(blockers []string) {
	quoted := make([]string, 0, len(blockers))
	for _, id := range blockers {
		quoted = append(quoted, "`"+id+"`")
	}
	label := strings.Repeat(r.theme.rule, 2) + " blocked by " + strings.Join(quoted, " + ")
	if fill := r.width - visibleLen(label) - 1; fill > 0 {
		label += " " + strings.Repeat(r.theme.rule, fill)
	}
	r.line("", r.dim(label), "")
}

// footer prints the tally of what was drawn and the key to the marks used.
func (r *renderer) footer() {
	if len(r.statuses) == 0 {
		return
	}
	r.out.WriteString("\n")

	total := 0
	kinds := make([]string, 0, len(r.statuses))
	for status, n := range r.statuses {
		total += n
		kinds = append(kinds, status)
	}
	sort.Slice(kinds, func(i, j int) bool {
		if r.statuses[kinds[i]] != r.statuses[kinds[j]] {
			return r.statuses[kinds[i]] > r.statuses[kinds[j]]
		}
		return kinds[i] < kinds[j]
	})
	tally := []string{r.dim(fmt.Sprintf("%d items", total))}
	if total == 1 {
		tally = []string{r.dim("1 item")}
	}
	for _, status := range kinds {
		tally = append(tally, r.paint(status, fmt.Sprintf("%s %d %s", r.theme.glyph(status), r.statuses[status], status)))
	}
	r.line("", strings.Join(tally, r.dim(r.theme.sep)), "")

	key := []string{r.theme.endContain + "contains"}
	if r.usedDeps {
		key = append(key, r.theme.endDepends+"blocked by the line above")
	}
	if r.usedStub {
		key = append(key, r.theme.stub+" expanded below")
	}
	r.line("", r.dim(strings.Join(key, "   ")), "")
}

// line writes one line: the tree prefix, the text that must survive, and the
// detail column that trails it. Within a width budget the detail is right
// aligned into a column of its own, and the title gives way before it does.
func (r *renderer) line(prefix, left, meta string) {
	if r.width <= 0 {
		if meta != "" {
			left += "  " + meta
		}
		r.out.WriteString(prefix + left + "\n")
		return
	}

	used := visibleLen(prefix) + visibleLen(left)
	budget := r.width - visibleLen(prefix)
	if meta == "" {
		r.out.WriteString(prefix + clip(left, budget, r.theme.ellipsis) + "\n")
		return
	}

	metaLen := visibleLen(meta)
	room := r.width - metaLen - 2
	if room-visibleLen(prefix) < minTitle {
		// No honest room for both, and the title is what a reader came for.
		r.out.WriteString(prefix + clip(left, budget, r.theme.ellipsis) + "\n")
		return
	}
	if used > room {
		left = clip(left, room-visibleLen(prefix), r.theme.ellipsis)
		used = visibleLen(prefix) + visibleLen(left)
	}
	r.out.WriteString(prefix + left + strings.Repeat(" ", r.width-used-metaLen) + meta + "\n")
}

// clip shortens text to n visible columns, leaving escape sequences intact.
func clip(text string, n int, marker string) string {
	if n <= visibleLen(marker) || visibleLen(text) <= n {
		return text
	}
	var b strings.Builder
	visible, escape, coloured := 0, false, false
	for _, ch := range text {
		if escape {
			b.WriteRune(ch)
			if ch == 'm' {
				escape = false
			}
			continue
		}
		if ch == '\x1b' {
			escape, coloured = true, true
			b.WriteRune(ch)
			continue
		}
		if visible == n-visibleLen(marker) {
			// Clipping can cut a line mid-colour, so close the sequence —
			// but only on a line that opened one, or plain output grows a
			// stray escape.
			b.WriteString(marker)
			if coloured {
				b.WriteString(ansiReset)
			}
			break
		}
		b.WriteRune(ch)
		visible++
	}
	return b.String()
}

// visibleLen counts printable columns, ignoring colour escapes.
func visibleLen(text string) int {
	n, escape := 0, false
	for _, ch := range text {
		switch {
		case escape:
			if ch == 'm' {
				escape = false
			}
		case ch == '\x1b':
			escape = true
		default:
			n++
		}
	}
	return n
}

// ANSI colours, applied only when the caller asked for them.
const (
	ansiReset   = "\x1b[0m"
	ansiDim     = "\x1b[2m"
	ansiBold    = "\x1b[1m"
	ansiGreen   = "\x1b[32m"
	ansiYellow  = "\x1b[33m"
	ansiCyan    = "\x1b[36m"
	ansiRed     = "\x1b[31m"
	ansiMagenta = "\x1b[35m"
)

func (r *renderer) dim(text string) string {
	return r.wrap(ansiDim, text)
}

// priority paints p1 the way the board treats it: an immediate blocker.
func (r *renderer) priority(priority string) string {
	if priority == "p1" {
		return r.wrap(ansiBold+ansiRed, priority)
	}
	return priority
}

// paint colours text by the derived status behind it, so live work stands out
// from closed work.
func (r *renderer) paint(status, text string) string {
	switch status {
	case "done":
		return r.wrap(ansiGreen, text)
	case "dropped":
		return r.wrap(ansiDim, text)
	case "ready", "pending":
		return r.wrap(ansiCyan, text)
	case "blocked":
		return r.wrap(ansiRed, text)
	case "claimed":
		return r.wrap(ansiYellow, text)
	case "review":
		return r.wrap(ansiMagenta, text)
	default:
		return text
	}
}

func (r *renderer) wrap(code, text string) string {
	if !r.colour {
		return text
	}
	return code + text + ansiReset
}
