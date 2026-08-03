package ui

import "github.com/charmbracelet/lipgloss"

// The palette is the terminal's own ANSI 16, not a scheme of ralph's: every
// colour below names a slot the user's theme already defines, so the dashboard
// follows whatever they are looking at — light, dark, or a switch mid-run —
// without asking the terminal what its background is. Plain text carries no
// colour at all, which leaves it the terminal's default foreground.
var (
	colBase   lipgloss.TerminalColor = lipgloss.NoColor{}
	colMuted  lipgloss.TerminalColor = lipgloss.ANSIColor(8) // bright black
	colAccent lipgloss.TerminalColor = lipgloss.ANSIColor(4) // blue
	colOK     lipgloss.TerminalColor = lipgloss.ANSIColor(2) // green
	colWarn   lipgloss.TerminalColor = lipgloss.ANSIColor(3) // yellow
	colErr    lipgloss.TerminalColor = lipgloss.ANSIColor(1) // red
)

var (
	// Reverse swaps the terminal's own foreground and background, so the title
	// chip and the cursor row stay legible under any theme.
	styleTitle    = lipgloss.NewStyle().Bold(true).Reverse(true).Padding(0, 1)
	styleCursor   = lipgloss.NewStyle().Reverse(true)
	styleSelected = lipgloss.NewStyle().Bold(true)

	styleValue  = lipgloss.NewStyle().Foreground(colBase)
	styleStrong = lipgloss.NewStyle().Bold(true)
	styleAccent = lipgloss.NewStyle().Foreground(colAccent)
	styleOK     = lipgloss.NewStyle().Foreground(colOK)
	styleWarn   = lipgloss.NewStyle().Foreground(colWarn)
	styleErr    = lipgloss.NewStyle().Foreground(colErr)
	styleMuted  = lipgloss.NewStyle().Foreground(colMuted)

	styleHeaderLabel      = lipgloss.NewStyle().Foreground(colAccent).Bold(true)
	stylePaneTitle        = lipgloss.NewStyle().Foreground(colMuted).Bold(true)
	stylePaneTitleFocused = lipgloss.NewStyle().Foreground(colAccent).Bold(true)

	styleModal = lipgloss.NewStyle().
			Border(lipgloss.DoubleBorder()).
			BorderForeground(colWarn).
			Padding(1, 3)

	styleInfoModal = lipgloss.NewStyle().
			Border(lipgloss.RoundedBorder()).
			BorderForeground(colAccent).
			Padding(1, 3)
)
