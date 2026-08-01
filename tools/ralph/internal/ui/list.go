package ui

import (
	"strings"

	"github.com/charmbracelet/lipgloss"
)

// tone picks the accent an item is drawn with.
type tone int

const (
	toneNormal tone = iota
	toneMuted
	toneAccent
	toneOK
	toneWarn
	toneErr
)

// item is one selectable row: a single rendered line plus the body shown when
// it is expanded.
type item struct {
	gutter  string
	summary string
	detail  string
	tone    tone
}

// listPane is a scrollable, cursor-driven list. It tails new rows while the
// cursor sits at the bottom and stops tailing as soon as the reader scrolls up,
// which is what makes a live log readable.
type listPane struct {
	title  string
	items  []item
	cursor int
	offset int
	follow bool
}

func newListPane(title string) listPane {
	return listPane{title: title, follow: true}
}

func (l *listPane) append(it item) {
	l.items = append(l.items, it)
	if l.follow {
		l.cursor = len(l.items) - 1
	}
}

func (l *listPane) replace(items []item) {
	// Keep the cursor on-screen when a poll shrinks the list.
	l.items = items
	if l.cursor >= len(l.items) {
		l.cursor = max(0, len(l.items)-1)
	}
}

func (l *listPane) moveUp(n int) {
	l.cursor = max(0, l.cursor-n)
	l.follow = false
}

func (l *listPane) moveDown(n int) {
	l.cursor = min(max(0, len(l.items)-1), l.cursor+n)
	l.follow = l.cursor == len(l.items)-1
}

func (l *listPane) top() {
	l.cursor = 0
	l.follow = false
}

func (l *listPane) bottom() {
	l.cursor = max(0, len(l.items)-1)
	l.follow = true
}

func (l *listPane) selected() (item, bool) {
	if l.cursor < 0 || l.cursor >= len(l.items) {
		return item{}, false
	}
	return l.items[l.cursor], true
}

func (l listPane) toneStyle(t tone) lipgloss.Style {
	switch t {
	case toneMuted:
		return styleMuted
	case toneAccent:
		return styleAccent
	case toneOK:
		return styleOK
	case toneWarn:
		return styleWarn
	case toneErr:
		return styleErr
	default:
		return styleValue
	}
}

// view renders the pane body at exactly height lines.
func (l *listPane) view(width, height int, focused bool) string {
	if height <= 0 {
		return ""
	}
	// Scroll the window so the cursor stays visible.
	if l.cursor < l.offset {
		l.offset = l.cursor
	}
	if l.cursor >= l.offset+height {
		l.offset = l.cursor - height + 1
	}
	if l.offset > max(0, len(l.items)-height) {
		l.offset = max(0, len(l.items)-height)
	}
	if l.offset < 0 {
		l.offset = 0
	}

	var b strings.Builder
	for row := range height {
		idx := l.offset + row
		if row > 0 {
			b.WriteByte('\n')
		}
		if idx >= len(l.items) {
			continue
		}
		it := l.items[idx]
		gutter := it.gutter
		if gutter != "" {
			gutter += " "
		}
		line := truncate(gutter+it.summary, width)
		switch {
		case idx == l.cursor && focused:
			b.WriteString(styleCursor.Render(pad(line, width)))
		case idx == l.cursor:
			b.WriteString(styleSelected.Render(pad(line, width)))
		default:
			b.WriteString(l.toneStyle(it.tone).Render(line))
		}
	}
	return b.String()
}

// scrollHint summarises the pane's position for its title bar.
func (l *listPane) scrollHint(height int) string {
	if len(l.items) == 0 {
		return ""
	}
	if len(l.items) <= height {
		return ""
	}
	if l.follow {
		return "tail"
	}
	return "…"
}
