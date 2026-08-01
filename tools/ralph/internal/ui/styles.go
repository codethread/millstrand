package ui

import "github.com/charmbracelet/lipgloss"

// The palette is adaptive so the same build reads on light and dark terminals.
var (
	colBase    = lipgloss.AdaptiveColor{Light: "#3c3c43", Dark: "#c8ccd4"}
	colMuted   = lipgloss.AdaptiveColor{Light: "#7a7a85", Dark: "#7f8694"}
	colAccent  = lipgloss.AdaptiveColor{Light: "#0b6bcb", Dark: "#7aa2f7"}
	colOK      = lipgloss.AdaptiveColor{Light: "#137547", Dark: "#9ece6a"}
	colWarn    = lipgloss.AdaptiveColor{Light: "#a05a00", Dark: "#e0af68"}
	colErr     = lipgloss.AdaptiveColor{Light: "#b3261e", Dark: "#f7768e"}
	colSubtle  = lipgloss.AdaptiveColor{Light: "#d5d7de", Dark: "#3b4048"}
	colInverse = lipgloss.AdaptiveColor{Light: "#ffffff", Dark: "#1a1b26"}
)

var (
	styleTitle    = lipgloss.NewStyle().Bold(true).Foreground(colInverse).Background(colAccent).Padding(0, 1)
	styleLabel    = lipgloss.NewStyle().Foreground(colMuted)
	styleValue    = lipgloss.NewStyle().Foreground(colBase)
	styleStrong   = lipgloss.NewStyle().Foreground(colBase).Bold(true)
	styleAccent   = lipgloss.NewStyle().Foreground(colAccent)
	styleOK       = lipgloss.NewStyle().Foreground(colOK)
	styleWarn     = lipgloss.NewStyle().Foreground(colWarn)
	styleErr      = lipgloss.NewStyle().Foreground(colErr)
	styleMuted    = lipgloss.NewStyle().Foreground(colMuted)
	styleCursor   = lipgloss.NewStyle().Foreground(colInverse).Background(colAccent)
	styleSelected = lipgloss.NewStyle().Foreground(colBase).Background(colSubtle)

	stylePaneTitle        = lipgloss.NewStyle().Foreground(colMuted).Bold(true)
	stylePaneTitleFocused = lipgloss.NewStyle().Foreground(colAccent).Bold(true)

	styleModal = lipgloss.NewStyle().
			Border(lipgloss.DoubleBorder()).
			BorderForeground(colWarn).
			Padding(1, 3)
)
