package ui

import (
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/spinner"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"

	"millstrand-ralph/internal/board"
	"millstrand-ralph/internal/harness"
	"millstrand-ralph/internal/loop"
)

// logLimit caps each iteration's log. Everything ever streamed stays in the raw
// transcript on disk, so the in-memory tail only has to be long enough to read.
const logLimit = 2000

// logHistory is how many iterations keep their log in memory. Older ones are
// dropped to their transcript path, which the iterations pane still offers.
const logHistory = 20

type pane int

const (
	paneBoard pane = iota
	paneLog
	paneIters
	paneCount
)

type confirmKind int

const (
	confirmNone confirmKind = iota
	// confirmQuit is the prompt ctrl-c, ctrl-d and q raise: it offers the two
	// stops rather than killing the run.
	confirmQuit
	confirmHard
)

// tickMsg advances the clocks.
type tickMsg time.Time

// iterRecord is one completed or running iteration, as the history pane shows it.
type iterRecord struct {
	n          int
	startedAt  time.Time
	duration   time.Duration
	exitCode   int
	err        error
	stats      *harness.Stats
	transcript string
	running    bool
	tools      int
	lastText   string
	final      string
}

type model struct {
	session Session
	keys    keyMap
	help    help.Model
	spin    spinner.Model

	width, height int

	panes [paneCount]listPane
	focus pane

	detail      viewport.Model
	detailTitle string
	showDetail  bool
	showInfo    bool
	showHelp    bool
	confirm     confirmKind

	startedAt time.Time
	now       time.Time

	iteration int
	records   []*iterRecord
	byN       map[int]*iterRecord

	// The log is kept per iteration, keyed by iteration number, so the pane can
	// show one run at a time. Key zero holds anything logged before the first
	// iteration started.
	logs      map[int][]item
	logView   int
	logFollow bool

	snapshot board.Snapshot
	snapErr  error
	snapAt   time.Time
	hasSnap  bool

	failures  int
	totalCost float64
	totalIn   int
	totalOut  int

	outcome      *loop.Outcome
	finished     bool
	softArmed    bool
	hardArmed    bool
	quitWhenDone bool

	status     string
	statusTone tone
}

func newModel(s Session) model {
	sp := spinner.New()
	sp.Spinner = spinner.MiniDot
	sp.Style = styleAccent

	m := model{
		session:   s,
		keys:      defaultKeys(),
		help:      help.New(),
		spin:      sp,
		startedAt: time.Now(),
		now:       time.Now(),
		byN:       map[int]*iterRecord{},
		logs:      map[int][]item{},
		logFollow: true,
		focus:     paneLog,
	}
	m.panes[paneBoard] = newListPane("board")
	m.panes[paneLog] = newListPane("agent log")
	m.panes[paneIters] = newListPane("iterations")
	m.panes[paneBoard].follow = false
	return m
}

func (m model) Init() tea.Cmd {
	return tea.Batch(m.spin.Tick, tick())
}

func tick() tea.Cmd {
	return tea.Tick(time.Second, func(t time.Time) tea.Msg { return tickMsg(t) })
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		m.detail.Width = max(10, msg.Width-4)
		m.detail.Height = max(3, m.contentHeight()-2)
		return m, nil

	case tickMsg:
		m.now = time.Time(msg)
		// The running iteration's row shows a live clock and tool count.
		m.refreshIterations()
		return m, tick()

	case spinner.TickMsg:
		var cmd tea.Cmd
		m.spin, cmd = m.spin.Update(msg)
		return m, cmd

	case tea.KeyMsg:
		return m.handleKey(msg)

	case signalStopMsg:
		if m.finished {
			return m, tea.Quit
		}
		if msg.hard {
			m.hardArmed = true
			m.quitWhenDone = true
			m.session.Engine.HardStop()
			m.setStatus("SIGTERM: killing the agent run", toneErr)
			return m, nil
		}
		m.confirm = confirmQuit
		return m, nil

	case loop.SnapshotMsg:
		m.snapAt = time.Now()
		m.snapErr = msg.Err
		if msg.Err == nil {
			m.snapshot = msg.Snapshot
			m.hasSnap = true
			m.panes[paneBoard].replace(boardItems(msg.Snapshot))
		}
		return m, nil

	case loop.IterationStarted:
		// Anything logged before the first iteration belongs to it: the loop was
		// already working towards this run when it was written.
		if pending := m.logs[0]; len(pending) > 0 {
			m.logs[msg.N] = pending
			delete(m.logs, 0)
		}
		m.iteration = msg.N
		rec := &iterRecord{n: msg.N, startedAt: msg.At, transcript: msg.Transcript, running: true}
		m.records = append(m.records, rec)
		m.byN[msg.N] = rec
		m.refreshIterations()
		if m.logFollow {
			m.showLog(msg.N)
		}
		m.pruneLogs()
		m.appendLog(item{
			gutter:  "──",
			summary: fmt.Sprintf("iteration %d started · %s", msg.N, msg.Transcript),
			detail:  msg.Prompt,
			tone:    toneAccent,
		})
		return m, nil

	case loop.StreamMsg:
		m.absorbStream(msg)
		return m, nil

	case loop.NoticeMsg:
		t := toneWarn
		if msg.Error {
			t = toneErr
		}
		m.appendLog(item{gutter: "!!", summary: msg.Text, detail: msg.Text, tone: t})
		return m, nil

	case loop.IterationFinished:
		m.absorbFinish(msg)
		return m, nil

	case loop.OutcomeMsg:
		out := msg.Outcome
		m.outcome = &out
		m.finished = true
		for _, rec := range m.records {
			rec.running = false
		}
		m.refreshIterations()
		m.appendLog(item{
			gutter:  "══",
			summary: fmt.Sprintf("loop stopped: %s — %s", out.Reason, out.Detail),
			detail:  fmt.Sprintf("reason: %s\ndetail: %s\niterations: %d\nexit code: %d", out.Reason, out.Detail, out.Iterations, out.ExitCode),
			tone:    outcomeTone(out),
		})
		if m.quitWhenDone {
			return m, tea.Quit
		}
		return m, nil
	}
	return m, nil
}

func (m *model) absorbStream(msg loop.StreamMsg) {
	ev := msg.Event
	rec := m.byN[msg.N]
	gutter := " "
	t := toneNormal
	switch ev.Kind {
	case harness.KindText:
		gutter = "▌"
		if rec != nil {
			rec.lastText = ev.Text
		}
	case harness.KindTool:
		gutter = "⏺"
		t = toneMuted
		if rec != nil {
			rec.tools++
		}
	case harness.KindResult:
		gutter = "●"
		t = toneOK
	case harness.KindError:
		gutter = "✖"
		t = toneErr
	case harness.KindNotice:
		gutter = "!!"
		t = toneWarn
	}
	summary := ev.Text
	if ev.Label != "" && ev.Kind == harness.KindTool {
		summary = ev.Label + " " + ev.Text
	}
	detail := ev.Detail
	if detail == "" {
		detail = ev.Text
	}
	m.appendLog(item{gutter: gutter, summary: summary, detail: detail, tone: t})
}

func (m *model) absorbFinish(msg loop.IterationFinished) {
	rec := m.byN[msg.N]
	if rec != nil {
		rec.running = false
		rec.duration = msg.Duration
		rec.exitCode = msg.ExitCode
		rec.err = msg.Err
		rec.stats = msg.Stats
		rec.final = msg.Final
	}
	if msg.Stats != nil {
		m.totalCost += msg.Stats.CostUSD
		m.totalIn += msg.Stats.InputTokens
		m.totalOut += msg.Stats.OutputTokens
	}
	if msg.ExitCode != 0 || msg.Err != nil {
		m.failures++
	} else {
		m.failures = 0
	}
	m.refreshIterations()
}

// appendLog files an entry under the iteration that produced it, and shows it
// only if that is the iteration the pane is scoped to.
func (m *model) appendLog(it item) {
	entries := append(m.logs[m.iteration], it)
	if over := len(entries) - logLimit; over > 0 {
		entries = entries[over:]
	}
	m.logs[m.iteration] = entries

	if m.iteration != m.logView {
		return
	}
	m.panes[paneLog].append(it)
	if over := len(m.panes[paneLog].items) - logLimit; over > 0 {
		m.panes[paneLog].items = m.panes[paneLog].items[over:]
		m.panes[paneLog].cursor = max(0, m.panes[paneLog].cursor-over)
		m.panes[paneLog].offset = max(0, m.panes[paneLog].offset-over)
	}
}

// showLog scopes the log pane to one iteration, starting at its tail. The pane
// keeps its own copy so appending to the live iteration cannot disturb it.
func (m *model) showLog(n int) {
	m.logView = n
	entries, held := m.logs[n]
	p := &m.panes[paneLog]
	switch {
	case held:
		p.items = append([]item(nil), entries...)
	case m.byN[n] != nil:
		// Dropped by pruneLogs; the transcript is still the whole truth.
		p.items = []item{{
			gutter:  "──",
			summary: "log rolled out of memory · transcript " + m.byN[n].transcript,
			detail:  "This iteration is older than the last " + fmt.Sprintf("%d", logHistory) + " and its log was dropped.\nThe raw stream is still at " + m.byN[n].transcript,
			tone:    toneMuted,
		}}
	default:
		p.items = nil
	}
	p.cursor = max(0, len(p.items)-1)
	p.offset = 0
	p.follow = true
}

// pruneLogs drops the logs of iterations old enough that nobody is reading them
// in the pane any more; an unbounded run would otherwise hold every event.
func (m *model) pruneLogs() {
	for n := range m.logs {
		if n != m.logView && n <= m.iteration-logHistory {
			delete(m.logs, n)
		}
	}
}

func (m *model) refreshIterations() {
	items := make([]item, 0, len(m.records))
	for _, rec := range m.records {
		items = append(items, iterationItem(rec, m.now))
	}
	// Newest last keeps the pane's tail behaviour consistent with the log.
	m.panes[paneIters].replace(items)
	if m.panes[paneIters].follow {
		m.panes[paneIters].cursor = max(0, len(items)-1)
	}
}

func (m model) handleKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	// Modal prompts swallow every other binding while they are up.
	if m.confirm != confirmNone {
		return m.handleConfirmKey(msg)
	}
	switch {
	case key.Matches(msg, m.keys.Quit):
		if m.finished {
			return m, tea.Quit
		}
		m.confirm = confirmQuit
		return m, nil

	case key.Matches(msg, m.keys.Help):
		m.showHelp = !m.showHelp
		m.help.ShowAll = m.showHelp
		return m, nil

	case key.Matches(msg, m.keys.Info):
		m.showInfo = !m.showInfo
		return m, nil

	case m.showInfo && key.Matches(msg, m.keys.Back):
		m.showInfo = false
		return m, nil

	case m.showDetail && key.Matches(msg, m.keys.Back):
		m.showDetail = false
		return m, nil

	case key.Matches(msg, m.keys.Open):
		if m.showDetail || m.showInfo {
			return m, nil
		}
		if it, ok := m.panes[m.focus].selected(); ok {
			m.detailTitle = it.summary
			m.detail.Width = max(10, m.width-4)
			m.detail.Height = max(3, m.contentHeight()-2)
			m.detail.SetContent(wrap(it.detail, m.detail.Width))
			m.detail.GotoTop()
			m.showDetail = true
		}
		return m, nil

	case key.Matches(msg, m.keys.SoftStop):
		if m.finished {
			return m, nil
		}
		m.softArmed = !m.softArmed
		if m.softArmed {
			m.session.Engine.SoftStop()
			m.setStatus("soft stop armed — the loop ends when this iteration finishes", toneWarn)
		} else {
			m.session.Engine.CancelSoftStop()
			m.setStatus("soft stop cancelled", toneOK)
		}
		return m, nil

	case key.Matches(msg, m.keys.HardStop):
		if m.finished {
			return m, nil
		}
		m.confirm = confirmHard
		return m, nil

	case key.Matches(msg, m.keys.NextPane):
		m.focus = (m.focus + 1) % paneCount
		return m, nil

	case key.Matches(msg, m.keys.PrevPane):
		m.focus = (m.focus + paneCount - 1) % paneCount
		return m, nil

	case key.Matches(msg, m.keys.Follow):
		p := &m.panes[m.focus]
		p.follow = !p.follow
		if p.follow {
			p.bottom()
		}
		if m.focus == paneIters {
			m.scopeLogToSelection()
		}
		return m, nil

	case key.Matches(msg, m.keys.Refresh):
		m.session.Engine.Refresh()
		m.setStatus("board refresh requested", toneMuted)
		return m, nil
	}

	if m.showInfo {
		// The popup is static; list keys would scroll a pane nobody can see.
		return m, nil
	}
	if m.showDetail {
		var cmd tea.Cmd
		m.detail, cmd = m.detail.Update(msg)
		return m, cmd
	}
	return m.handleListKey(msg)
}

func (m model) handleListKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	p := &m.panes[m.focus]
	page := max(1, m.paneHeight(m.focus)-1)
	switch {
	case key.Matches(msg, m.keys.Up):
		p.moveUp(1)
	case key.Matches(msg, m.keys.Down):
		p.moveDown(1)
	case key.Matches(msg, m.keys.PageUp):
		p.moveUp(page)
	case key.Matches(msg, m.keys.PageDown):
		p.moveDown(page)
	case key.Matches(msg, m.keys.Top):
		p.top()
	case key.Matches(msg, m.keys.Bottom):
		p.bottom()
	}
	if m.focus == paneIters {
		m.scopeLogToSelection()
	}
	return m, nil
}

// scopeLogToSelection points the log pane at whichever iteration the iterations
// pane has under its cursor. Sitting on the newest row resumes following the
// live run, so a new iteration takes the pane over again.
func (m *model) scopeLogToSelection() {
	idx := m.panes[paneIters].cursor
	if idx < 0 || idx >= len(m.records) {
		return
	}
	m.logFollow = idx == len(m.records)-1
	if rec := m.records[idx]; rec.n != m.logView {
		m.showLog(rec.n)
	}
}

func (m model) handleConfirmKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	kind := m.confirm
	switch msg.String() {
	case "esc", "n", "N":
		m.confirm = confirmNone
		m.setStatus("stop cancelled — the loop is still running", toneOK)
		return m, nil

	case "s", "S":
		if kind != confirmQuit {
			return m, nil
		}
		m.confirm = confirmNone
		m.softArmed = true
		m.quitWhenDone = true
		m.session.Engine.SoftStop()
		m.setStatus("soft stop armed — ralph exits when this iteration finishes", toneWarn)
		return m, nil

	case "x", "X", "y", "Y":
		if kind == confirmQuit && (msg.String() == "y" || msg.String() == "Y") {
			return m, nil
		}
		m.confirm = confirmNone
		m.hardArmed = true
		m.quitWhenDone = true
		m.session.Engine.HardStop()
		m.setStatus("hard stop: killing the agent run", toneErr)
		return m, nil
	}
	return m, nil
}

func (m *model) setStatus(text string, t tone) {
	m.status = text
	m.statusTone = t
}

func outcomeTone(out loop.Outcome) tone {
	switch out.Reason {
	case loop.ReasonEpicInactive, loop.ReasonSoftStop:
		return toneOK
	case loop.ReasonBrake, loop.ReasonHardStop:
		return toneWarn
	default:
		return toneErr
	}
}

func iterationItem(rec *iterRecord, now time.Time) item {
	elapsed := rec.duration
	if rec.running {
		elapsed = now.Sub(rec.startedAt)
	}
	status, t := "ok", toneOK
	switch {
	case rec.running:
		status, t = "running", toneAccent
	case rec.err != nil:
		status, t = "error", toneErr
	case rec.exitCode != 0:
		status, t = fmt.Sprintf("exit %d", rec.exitCode), toneErr
	}
	summary := fmt.Sprintf("#%-3d %-8s %-7s tools %-3d %s",
		rec.n, status, shortDur(elapsed), rec.tools, rec.lastText)

	var b strings.Builder
	fmt.Fprintf(&b, "iteration %d\n", rec.n)
	fmt.Fprintf(&b, "started    %s\n", rec.startedAt.Format("15:04:05"))
	fmt.Fprintf(&b, "duration   %s\n", shortDur(elapsed))
	fmt.Fprintf(&b, "status     %s\n", status)
	fmt.Fprintf(&b, "tool calls %d\n", rec.tools)
	fmt.Fprintf(&b, "transcript %s\n", rec.transcript)
	if rec.stats != nil {
		s := rec.stats
		if s.Turns > 0 || s.CostUSD > 0 {
			fmt.Fprintf(&b, "turns      %d\ncost       $%.2f\n", s.Turns, s.CostUSD)
		}
		if s.InputTokens > 0 || s.OutputTokens > 0 {
			fmt.Fprintf(&b, "tokens     in %d (cached %d) · out %d\n", s.InputTokens, s.CachedTokens, s.OutputTokens)
		}
	}
	if rec.err != nil {
		fmt.Fprintf(&b, "\nerror\n%s\n", rec.err)
	}
	if strings.TrimSpace(rec.final) != "" {
		fmt.Fprintf(&b, "\nfinal message\n%s\n", rec.final)
	}
	return item{gutter: "", summary: summary, detail: b.String(), tone: t}
}
