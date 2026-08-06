package ui

import (
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"

	"millstrand-ralph/internal/board"
)

// headerIndent aligns the run-status rows with the panes' own indentation.
const headerIndent = "  "

// failureLimit is the consecutive-failure budget the loop is running with; the
// engine's own default stands in when the session did not carry one.
func (m model) failureLimit() int {
	if m.session.FailureLimit <= 0 {
		return 3
	}
	return m.session.FailureLimit
}

// headerHeight is the run-status block plus the row that separates it from the
// panes. The block breathes differently by terminal size, so it is measured
// rather than fixed.
func (m model) headerHeight() int {
	return lipgloss.Height(m.renderHeader(m.width)) + 1
}

// airyHeader spends two rows on whitespace: one under the title, one above the
// board. Short terminals need those rows for the panes instead.
func (m model) airyHeader() bool { return m.height >= 24 }

func (m model) helpLines() int {
	if m.showHelp {
		return 3
	}
	return 1
}

func (m model) footerHeight() int { return m.helpLines() + 1 }

// A wide terminal can keep the selected item visible beside the three working
// panes. Narrow terminals stack that preview below them instead.
const widePreviewMinWidth = 120

// paneChrome is three title lines plus two separators between the primary
// panes. Stacked layout adds the preview title and its separator.
const (
	paneChrome        = 3 + 2
	stackedPaneChrome = 4 + 3
)

func (m model) sidePreview() bool { return m.width >= widePreviewMinWidth }

func (m model) dashboardChrome() int {
	if m.sidePreview() {
		return paneChrome
	}
	return stackedPaneChrome
}

// contentHeight is the stable viewport height for full-screen detail and
// popups. Dashboard layout has its own chrome calculation below.
func (m model) contentHeight() int {
	return max(9, m.height-m.headerHeight()-m.footerHeight()-paneChrome)
}

func (m model) dashboardContentHeight() int {
	floor := 9
	if !m.sidePreview() {
		floor = 7
	}
	return max(floor, m.height-m.headerHeight()-m.footerHeight()-m.dashboardChrome())
}

// layout gives board and iteration history their natural list height, capped at
// roughly a third of the available rows. The live log gets every remaining row.
func (m model) layout() (boardH, logH, iterH int) {
	content := m.dashboardContentHeight()
	maxPaneHeight := max(1, content/3)
	boardH = clamp(len(m.panes[paneBoard].items), 1, maxPaneHeight)
	iterH = clamp(len(m.panes[paneIters].items), 1, maxPaneHeight)
	if !m.sidePreview() {
		return boardH, content - boardH - iterH - m.stackedPreviewHeight(boardH, iterH), iterH
	}
	return boardH, max(1, content-boardH-iterH), iterH
}

// stackedPreviewHeight reserves a stable, bounded part of the narrow dashboard
// for the non-scrollable preview without changing pane geometry on cursor moves.
func (m model) stackedPreviewHeight(boardH, iterH int) int {
	content := m.dashboardContentHeight()
	maxPaneHeight := max(1, content/3)
	return min(maxPaneHeight, max(1, content-boardH-iterH-1))
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
	case m.showInfo:
		b.WriteString(m.renderInfo(inner))
	default:
		boardH, logH, iterH := m.layout()
		if m.sidePreview() {
			const gap = 2
			leftWidth := (inner - gap) * 3 / 5
			rightWidth := inner - gap - leftWidth
			left := strings.Join([]string{
				m.renderPane(paneBoard, "board", boardH, leftWidth),
				m.renderPane(paneLog, m.logPaneTitle(), logH, leftWidth),
				m.renderPane(paneIters, "iterations", iterH, leftWidth),
			}, "\n\n")
			left = lipgloss.NewStyle().Width(leftWidth).Render(left)
			right := lipgloss.NewStyle().Width(rightWidth).Render(
				m.renderPreview(rightWidth, m.dashboardContentHeight()+paneChrome-1))
			b.WriteString(lipgloss.JoinHorizontal(lipgloss.Top, left, strings.Repeat(" ", gap), right))
		} else {
			previewH := m.stackedPreviewHeight(boardH, iterH)
			b.WriteString(m.renderPane(paneBoard, "board", boardH, inner))
			b.WriteString("\n\n")
			b.WriteString(m.renderPane(paneLog, m.logPaneTitle(), logH, inner))
			b.WriteString("\n\n")
			b.WriteString(m.renderPane(paneIters, "iterations", iterH, inner))
			b.WriteString("\n\n")
			b.WriteString(m.renderPreview(inner, previewH))
		}
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

	failStyle := styleValue
	switch {
	case m.failures >= m.failureLimit():
		failStyle = styleErr
	case m.failures > 0:
		failStyle = styleWarn
	}

	dot := styleMuted.Render(" · ")
	cell := func(label, value string) string {
		return headerIndent + styleHeaderLabel.Render(pad(label, 7)) + value
	}

	loop := cell("LOOP", styleValue.Render("iter ")+styleStrong.Render(iterLabel)+dot+
		styleValue.Render(running)+dot+
		styleValue.Render(shortDur(m.now.Sub(m.startedAt))+" elapsed")+dot+
		failStyle.Render(fmt.Sprintf("failures %d/%d", m.failures, m.failureLimit()))+
		styleMuted.Render(cost))
	agent := cell("AGENT", styleValue.Render(s.HarnessName)+dot+
		styleAccent.Render(s.Settings.Model)+dot+
		styleValue.Render("effort "+s.Settings.Effort)+dot+
		styleMuted.Render(perms))

	rows := []string{m.renderTitleRow(width)}
	if m.airyHeader() {
		rows = append(rows, "")
	}
	rows = append(rows, cell("EPIC", styleStrong.Render(s.Epic.ID)+" "+styleValue.Render(title)+"  "+
		stateStyle.Render("["+epicState+"]")))
	if row, ok := sideBySide(loop, agent, width); ok {
		rows = append(rows, row)
	} else {
		rows = append(rows, loop, agent)
	}
	if m.airyHeader() {
		rows = append(rows, "")
	}
	for i, row := range rows {
		rows[i] = truncate(row, width)
	}
	return strings.Join(rows, "\n")
}

// renderTitleRow carries the run's name and stop banner, with the board's
// freshness and the info-popup hint right-aligned when the width allows. The
// log paths themselves live in that popup rather than on screen.
func (m model) renderTitleRow(width int) string {
	left := headerIndent + styleTitle.Render("ralph") + " " + styleMuted.Render(m.stopBanner())
	right := styleMuted.Render("board "+m.boardAge()) + styleMuted.Render(" · ") +
		styleStrong.Render("e") + styleMuted.Render(" info")
	gap := width - lipgloss.Width(left) - lipgloss.Width(right) - 2
	if gap < 2 {
		return left
	}
	return left + strings.Repeat(" ", gap) + right
}

// sideBySide pins the right block to the right edge, where it holds still: the
// agent's settings are fixed for the run while the loop's own numbers move.
// Reports false when both blocks will not fit whole, so the caller can stack
// them rather than truncate live status.
func sideBySide(left, right string, width int) (string, bool) {
	gap := width - lipgloss.Width(left) - lipgloss.Width(right) - 1
	if gap < 2 {
		return "", false
	}
	return left + strings.Repeat(" ", gap) + right, true
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
		return m.snapErr.Error()
	}
	if m.snapAt.IsZero() {
		return "polling…"
	}
	if m.finished {
		// Polling stops with the loop; a growing "ago" would only suggest the
		// board is still being watched.
		return "as of " + m.snapAt.Format("15:04:05")
	}
	return shortDur(m.now.Sub(m.snapAt)) + " ago"
}

func (m model) current() *iterRecord {
	if len(m.records) == 0 {
		return nil
	}
	return m.records[len(m.records)-1]
}

// logPaneTitle names the iteration the log is scoped to, so a pane showing an
// earlier run cannot be mistaken for the live one.
func (m model) logPaneTitle() string {
	if m.logView == 0 {
		return "agent log"
	}
	return fmt.Sprintf("agent log · iteration %d", m.logView)
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

// renderPreview is the always-visible, non-interactive counterpart to detail.
// Enter still opens the same item in the scrollable full-screen detail view.
func (m model) renderPreview(width, height int) string {
	if height <= 0 {
		return ""
	}
	head := "  " + stylePaneTitle.Render("PREVIEW")
	lines := []string{styleMuted.Render("nothing selected")}
	if it, ok := m.panes[m.focus].selected(); ok {
		lines = []string{styleStrong.Render(truncate(it.summary, max(1, width-2)))}
		for _, line := range strings.Split(wrap(it.detail, max(1, width-2)), "\n") {
			lines = append(lines, truncate(line, max(1, width-2)))
		}
	}
	if len(lines) > height {
		lines = lines[:height]
		lines[height-1] = styleMuted.Render(truncate("…", max(1, width-2)))
	}
	for len(lines) < height {
		lines = append(lines, "")
	}
	return truncate(head, width) + "\n" + indent(strings.Join(lines, "\n"), "  ")
}

// renderInfo is the `e` popup: the run's paths and settings, which are read
// once in a while and would otherwise spend a header row on every frame.
func (m model) renderInfo(width int) string {
	s := m.session
	boxWidth := clamp(width-8, 30, 100)
	valueWidth := max(10, boxWidth-16)

	row := func(label, value string) string {
		return styleHeaderLabel.Render(pad(label, 13)) + styleValue.Render(truncate(value, valueWidth))
	}

	transcript := "not started"
	if rec := m.current(); rec != nil && rec.transcript != "" {
		transcript = rec.transcript
	}
	workspace := s.Workspace
	if workspace == "" {
		workspace = "repo default"
	}
	perms := "kept"
	if s.SkipPerms {
		perms = "bypassed"
	}

	body := strings.Join([]string{
		styleStrong.Render("run info"),
		"",
		row("epic", s.Epic.ID+"  "+s.Epic.Title),
		row("harness", fmt.Sprintf("%s · %s · effort %s", s.HarnessName, s.Settings.Model, s.Settings.Effort)),
		row("permissions", perms),
		row("limits", fmt.Sprintf("max %d iterations · %d consecutive failures",
			s.MaxIterations, m.failureLimit())),
		row("workspace", workspace),
		row("logs", s.LogDir),
		row("transcript", transcript),
		row("board", m.boardAge()),
		"",
		styleMuted.Render("esc or e closes"),
	}, "\n")

	return lipgloss.Place(width, m.contentHeight()+3, lipgloss.Center, lipgloss.Center,
		styleInfoModal.Render(body))
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
