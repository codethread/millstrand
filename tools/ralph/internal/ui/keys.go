package ui

import "github.com/charmbracelet/bubbles/key"

// keyMap is the whole keyboard surface. It is rendered in the footer so the two
// stop keys are always visible, which is the point of having them.
type keyMap struct {
	Up       key.Binding
	Down     key.Binding
	PageUp   key.Binding
	PageDown key.Binding
	Top      key.Binding
	Bottom   key.Binding
	NextPane key.Binding
	PrevPane key.Binding
	Open     key.Binding
	Back     key.Binding
	Follow   key.Binding
	Refresh  key.Binding
	Info     key.Binding
	SoftStop key.Binding
	HardStop key.Binding
	Help     key.Binding
	Quit     key.Binding
}

func defaultKeys() keyMap {
	return keyMap{
		Up:       key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "up")),
		Down:     key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "down")),
		PageUp:   key.NewBinding(key.WithKeys("pgup", "ctrl+u"), key.WithHelp("pgup", "page up")),
		PageDown: key.NewBinding(key.WithKeys("pgdown", "ctrl+f"), key.WithHelp("pgdn", "page down")),
		Top:      key.NewBinding(key.WithKeys("g", "home"), key.WithHelp("g", "top")),
		Bottom:   key.NewBinding(key.WithKeys("G", "end"), key.WithHelp("G", "bottom")),
		NextPane: key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "next pane")),
		PrevPane: key.NewBinding(key.WithKeys("shift+tab"), key.WithHelp("⇧tab", "prev pane")),
		Open:     key.NewBinding(key.WithKeys("enter", "l", "right"), key.WithHelp("enter", "expand")),
		Back:     key.NewBinding(key.WithKeys("esc", "h", "left"), key.WithHelp("esc", "back")),
		Follow:   key.NewBinding(key.WithKeys("f"), key.WithHelp("f", "follow tail")),
		Refresh:  key.NewBinding(key.WithKeys("r"), key.WithHelp("r", "refresh board")),
		Info:     key.NewBinding(key.WithKeys("e"), key.WithHelp("e", "run info")),
		SoftStop: key.NewBinding(key.WithKeys("s"), key.WithHelp("s", "soft stop")),
		HardStop: key.NewBinding(key.WithKeys("x"), key.WithHelp("x", "hard stop")),
		Help:     key.NewBinding(key.WithKeys("?"), key.WithHelp("?", "help")),
		// ctrl+c and ctrl+d open the stop prompt rather than ending the run;
		// a loop this expensive must never die on a stray keystroke.
		Quit: key.NewBinding(key.WithKeys("q", "ctrl+c", "ctrl+d"), key.WithHelp("q", "stop/quit")),
	}
}

// ShortHelp implements help.KeyMap.
func (k keyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.NextPane, k.Up, k.Down, k.Open, k.SoftStop, k.HardStop, k.Help, k.Quit}
}

// FullHelp implements help.KeyMap.
func (k keyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.NextPane, k.PrevPane, k.Up, k.Down, k.PageUp, k.PageDown, k.Top, k.Bottom},
		{k.Open, k.Back, k.Follow, k.Refresh, k.Info, k.Help},
		{k.SoftStop, k.HardStop, k.Quit},
	}
}
