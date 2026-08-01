package ui

import (
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"

	"skein-ralph/internal/board"
)

// headerHeight is the fixed run-status block at the top of the screen.
const headerHeight = 6

func (m model) helpLines() int {
	if m.showHelp {
		return 3
	}
	return 1
}

func (m model) footerHeight() int { return m.helpLines() + 1 }

// contentHeight is everything between the header and the footer, minus the one
// title line each pane spends.
func (m model) contentHeight() int {
	return max(9, m.height-headerHeight-m.footerHeight()-3)
}

// layout splits the content rows across the three panes: board first because it
// answers "where is the work", log largest because it is what moves.
func (m model) layout() (boardH, logH, iterH int) {
	content := m.contentHeight()
	boardH = clamp(content*40/100, 3, 20)
	iterH = clamp(content*20/100, 3, 10)
	logH = content - boardH - iterH
	if logH < 3 {
		logH = 3
		boardH = clamp(content-logH-iterH, 3, 20)
	}
	return boardH, logH, iterH
}

func (m model) paneHeight(p pane) int {
	boardH, logH, iterH := m.layout()
	switch p {
	case paneBoard:
		return boardH
	case paneLog:
		return logH
	default:
		return iterH
	}
}

func (m model) View() string {
	if m.width == 0 || m.height == 0 {
		return "starting ralph…"
	}
	inner := m.width

	var b strings.Builder
	b.WriteString(m.renderHeader(inner))
	b.WriteByte('\n')

	switch {
	case m.showDetail:
		b.WriteString(m.renderDetail(inner))
	case m.confirm != confirmNone:
		b.WriteString(m.renderConfirm(inner))
	default:
		boardH, logH, iterH := m.layout()
		b.WriteString(m.renderPane(paneBoard, "board", boardH, inner))
		b.WriteByte('\n')
		b.WriteString(m.renderPane(paneLog, "agent log", logH, inner))
		b.WriteByte('\n')
		b.WriteString(m.renderPane(paneIters, "iterations", iterH, inner))
	}
	b.WriteByte('\n')
	b.WriteString(m.renderFooter(inner))
	return b.String()
}

func (m model) renderHeader(width int) string {
	s := m.session
	epicState := s.Epic.State
	title := s.Epic.Title
	if m.hasSnap {
		epicState = m.snapshot.Epic.State
		title = m.snapshot.Epic.Title
	}

	stateStyle := styleOK
	if epicState != board.StateActive {
		stateStyle = styleWarn
	}

	iterLabel := fmt.Sprintf("%d", m.iteration)
	if s.MaxIterations > 0 {
		iterLabel = fmt.Sprintf("%d/%d", m.iteration, s.MaxIterations)
	} else {
		iterLabel += "/∞"
	}

	running := ""
	if rec := m.current(); rec != nil && rec.running {
		running = fmt.Sprintf("%s running %s", m.spin.View(), shortDur(m.now.Sub(rec.startedAt)))
	} else if m.finished {
		running = "stopped"
	} else {
		running = "between runs"
	}

	perms := "prompts kept"
	if s.SkipPerms {
		perms = "prompts bypassed"
	}

	cost := ""
	switch {
	case m.totalCost > 0:
		cost = fmt.Sprintf(" · $%.2f total", m.totalCost)
	case m.totalIn > 0 || m.totalOut > 0:
		cost = fmt.Sprintf(" · tokens %s in / %s out", compactInt(m.totalIn), compactInt(m.totalOut))
	}

	line := func(label, value string) string {
		return truncate(styleLabel.Render(pad(label, 7))+value, width)
	}

	rows := []string{
		styleTitle.Render("ralph") + " " + styleMuted.Render(m.stopBanner()),
		line("epic", styleStrong.Render(s.Epic.ID)+" "+styleValue.Render(title)+"  "+stateStyle.Render("["+epicState+"]")),
		line("loop", styleValue.Render(fmt.Sprintf("iteration %s · %s · elapsed %s · failures %d/3%s",
			iterLabel, running, shortDur(m.now.Sub(m.startedAt)), m.failures, cost))),
		line("agent", styleValue.Render(fmt.Sprintf("%s · model %s · effort %s · %s",
			s.HarnessName, s.Settings.Model, s.Settings.Effort, perms))),
		line("logs", styleMuted.Render(s.LogDir+"  "+m.boardAge())),
	}
	return strings.Join(rows, "\n")
}

func (m model) stopBanner() string {
	switch {
	case m.finished && m.outcome != nil:
		return styleStrong.Render(fmt.Sprintf("finished: %s (exit %d) — press q to leave",
			m.outcome.Reason, m.outcome.ExitCode))
	case m.hardArmed:
		return styleErr.Render("HARD STOP — killing the run")
	case m.softArmed:
		return styleWarn.Render("SOFT STOP armed — ends after this iteration (s to cancel)")
	default:
		return "epic loop"
	}
}

func (m model) boardAge() string {
	if m.snapErr != nil {
		return "· board: " + m.snapErr.Error()
	}
	if m.snapAt.IsZero() {
		return "· board: polling…"
	}
	if m.finished {
		// Polling stops with the loop; a growing "ago" would only suggest the
		// board is still being watched.
		return "· board as of " + m.snapAt.Format("15:04:05")
	}
	return fmt.Sprintf("· board %s ago", shortDur(m.now.Sub(m.snapAt)))
}

func (m model) current() *iterRecord {
	if len(m.records) == 0 {
		return nil
	}
	return m.records[len(m.records)-1]
}

func (m model) renderPane(p pane, title string, height, width int) string {
	focused := m.focus == p && !m.showDetail && m.confirm == confirmNone
	marker := "  "
	titleStyle := stylePaneTitle
	if focused {
		marker = styleAccent.Render("▌ ")
		titleStyle = stylePaneTitleFocused
	}
	pn := &m.panes[p]
	count := fmt.Sprintf("%d", len(pn.items))
	if len(pn.items) > 0 {
		count = fmt.Sprintf("%d/%d", pn.cursor+1, len(pn.items))
	}
	hint := pn.scrollHint(height)
	head := marker + titleStyle.Render(strings.ToUpper(title)) + "  " + styleMuted.Render(count)
	if hint != "" {
		head += styleMuted.Render(" · " + hint)
	}
	body := pn.view(max(1, width-2), height, focused)
	return truncate(head, width) + "\n" + indent(body, "  ")
}

func (m model) renderDetail(width int) string {
	head := styleAccent.Render("▌ DETAIL  ") + styleMuted.Render("esc back · ↑/↓ scroll")
	return truncate(head, width) + "\n" +
		truncate(styleStrong.Render(m.detailTitle), width) + "\n" +
		m.detail.View()
}

func (m model) renderConfirm(width int) string {
	var body string
	switch m.confirm {
	case confirmHard:
		body = strings.Join([]string{
			styleErr.Render("Hard stop"),
			"",
			"Kill the running agent now and end the loop.",
			"Work the agent has not committed will be lost.",
			"",
			styleStrong.Render("x") + " kill now      " + styleStrong.Render("esc") + " keep running",
		}, "\n")
	default:
		soft := "the loop ends when the current iteration finishes"
		if m.current() == nil || !m.current().running {
			soft = "the loop ends before the next iteration starts"
		}
		body = strings.Join([]string{
			styleWarn.Render("Stop ralph?"),
			"",
			styleStrong.Render("s") + "   soft stop — " + soft,
			styleStrong.Render("x") + "   hard stop — kill the agent now",
			styleStrong.Render("esc") + " cancel — keep running",
			"",
			styleMuted.Render("ctrl-c and ctrl-d land here; neither kills the run on its own."),
		}, "\n")
	}
	box := styleModal.Render(body)
	return lipgloss.Place(width, m.contentHeight()+3, lipgloss.Center, lipgloss.Center, box)
}

func (m model) renderFooter(width int) string {
	status := m.status
	if status == "" {
		status = m.defaultStatus()
	}
	statusStyle := styleMuted
	switch m.statusTone {
	case toneErr:
		statusStyle = styleErr
	case toneWarn:
		statusStyle = styleWarn
	case toneOK:
		statusStyle = styleOK
	}
	m.help.Width = width
	return truncate(statusStyle.Render(status), width) + "\n" + m.help.View(m.keys)
}

func (m model) defaultStatus() string {
	if m.finished && m.outcome != nil {
		return fmt.Sprintf("%s · %s", m.outcome.Reason, m.outcome.Detail)
	}
	switch m.focus {
	case paneBoard:
		return "board: features under this epic, with the tasks and ready work of whatever is claimed"
	case paneLog:
		return "log: what the current agent run is doing, newest last"
	default:
		return "iterations: one row per agent run — enter opens its stats, final message and transcript path"
	}
}

// boardItems flattens a snapshot into the board pane's rows.
func boardItems(snap board.Snapshot) []item {
	items := []item{{
		gutter:  "◆",
		summary: fmt.Sprintf("%s  %s", snap.Epic.ID, snap.Epic.Title),
		detail:  epicDetail(snap),
		tone:    toneAccent,
	}}
	if len(snap.Features) == 0 {
		items = append(items, item{
			gutter:  " ",
			summary: "no feature cards under this epic",
			detail:  "The epic has no feature cards. The next iteration has nothing to pick up unless it authors some.",
			tone:    toneWarn,
		})
		return items
	}
	for _, f := range snap.Features {
		items = append(items, item{
			gutter:  laneGlyph(f.Lane),
			summary: featureSummary(f),
			detail:  featureDetail(f),
			tone:    laneTone(f.Lane),
		})
		for _, t := range f.Tasks {
			if t.State != "active" {
				continue
			}
			items = append(items, item{
				gutter:  "  " + taskGlyph(t.Status),
				summary: fmt.Sprintf("%-8s %s  %s", t.Status, t.ID, t.Title),
				detail:  fmt.Sprintf("task %s (%s)\n\n%s", t.ID, t.Status, t.Title),
				tone:    taskTone(t.Status),
			})
		}
		for _, r := range f.Ready {
			items = append(items, item{
				gutter:  "  ▸",
				summary: fmt.Sprintf("ready    %s  %s", r.ID, r.Title),
				detail:  fmt.Sprintf("ready work %s\n\n%s", r.ID, r.Title),
				tone:    toneMuted,
			})
		}
	}
	return items
}

func featureSummary(f board.Card) string {
	owner := f.Owner
	if owner == "" {
		owner = "unclaimed"
	}
	tail := owner
	if f.Branch != "" {
		tail += " · " + f.Branch
	}
	return fmt.Sprintf("%-10s %-3s %-6s %-44s %s",
		f.Lane, f.Priority, f.ID, truncate(f.Title, 44), tail)
}

func featureDetail(f board.Card) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s  %s\n\n", f.ID, f.Title)
	fmt.Fprintf(&b, "lane      %s\npriority  %s\nstate     %s\n", f.Lane, f.Priority, f.State)
	if f.Owner != "" {
		fmt.Fprintf(&b, "owner     %s\n", f.Owner)
	}
	if f.Branch != "" {
		fmt.Fprintf(&b, "branch    %s\n", f.Branch)
	}
	if len(f.Labels) > 0 {
		fmt.Fprintf(&b, "labels    %s\n", strings.Join(f.Labels, ", "))
	}
	if len(f.Tasks) > 0 {
		b.WriteString("\ntasks\n")
		for _, t := range f.Tasks {
			fmt.Fprintf(&b, "  %-8s %-8s %s\n", t.Status, t.ID, t.Title)
		}
	}
	if len(f.Ready) > 0 {
		b.WriteString("\nready\n")
		for _, r := range f.Ready {
			fmt.Fprintf(&b, "  %-8s %s\n", r.ID, r.Title)
		}
	}
	return b.String()
}

func epicDetail(snap board.Snapshot) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s  %s\n\n", snap.Epic.ID, snap.Epic.Title)
	fmt.Fprintf(&b, "state     %s\npriority  %s\nlabels    %s\n",
		snap.Epic.State, snap.Epic.Attr(board.AttrPriority), strings.Join(snap.Epic.Labels(), ", "))
	fmt.Fprintf(&b, "features  %d\npolled    %s\n", len(snap.Features), snap.TakenAt.Format("15:04:05"))
	if body := snap.Epic.Attr("body"); body != "" {
		b.WriteString("\n" + body)
	}
	return b.String()
}

func laneGlyph(lane string) string {
	switch lane {
	case "claimed":
		return "●"
	case "in_review":
		return "◐"
	case "pending":
		return "○"
	default:
		return "·"
	}
}

func laneTone(lane string) tone {
	switch lane {
	case "claimed":
		return toneOK
	case "in_review":
		return toneAccent
	case "refinement":
		return toneMuted
	default:
		return toneNormal
	}
}

func taskGlyph(status string) string {
	switch status {
	case "doing":
		return "▶"
	case "ready":
		return "▸"
	case "blocked":
		return "⊘"
	case "done", "closed":
		return "✓"
	default:
		return "·"
	}
}

func taskTone(status string) tone {
	switch status {
	case "doing":
		return toneAccent
	case "ready":
		return toneNormal
	case "blocked":
		return toneMuted
	default:
		return toneMuted
	}
}

func indent(s, prefix string) string {
	lines := strings.Split(s, "\n")
	for i, line := range lines {
		lines[i] = prefix + line
	}
	return strings.Join(lines, "\n")
}

func truncate(s string, width int) string {
	if width <= 0 {
		return ""
	}
	s = strings.ReplaceAll(s, "\t", "  ")
	if lipgloss.Width(s) <= width {
		return s
	}
	return ansi.Truncate(s, width, "…")
}

func pad(s string, width int) string {
	gap := width - lipgloss.Width(s)
	if gap <= 0 {
		return s
	}
	return s + strings.Repeat(" ", gap)
}

func wrap(s string, width int) string {
	if width <= 0 {
		return s
	}
	return ansi.Wrap(s, width, "")
}

func shortDur(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%ds", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm%02ds", int(d.Minutes()), int(d.Seconds())%60)
	default:
		return fmt.Sprintf("%dh%02dm", int(d.Hours()), int(d.Minutes())%60)
	}
}

func compactInt(n int) string {
	switch {
	case n >= 1_000_000:
		return fmt.Sprintf("%.1fM", float64(n)/1_000_000)
	case n >= 1_000:
		return fmt.Sprintf("%.1fk", float64(n)/1_000)
	default:
		return fmt.Sprintf("%d", n)
	}
}

func clamp(v, lo, hi int) int {
	return min(max(v, lo), hi)
}
