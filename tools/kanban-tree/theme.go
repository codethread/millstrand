package main

// theme is the drawing alphabet: the box characters that join the tree and the
// glyph that stands for each derived status. Two exist so a terminal that
// cannot render box drawing still gets a readable tree.
type theme struct {
	teeContain  string
	endContain  string
	teeDepends  string
	endDepends  string
	pipePrefix  string
	blankPrefix string
	rule        string
	stub        string
	ellipsis    string
	cycle       string
	sep         string
	glyphs      map[string]string
	fallback    string
}

func unicodeTheme() theme {
	return theme{
		teeContain:  "├── ",
		endContain:  "└── ",
		teeDepends:  "├─▶ ",
		endDepends:  "└─▶ ",
		pipePrefix:  "│   ",
		blankPrefix: "    ",
		rule:        "─",
		stub:        "…",
		ellipsis:    "…",
		cycle:       "↺ cycle",
		sep:         " · ",
		fallback:    "◇",
		glyphs: map[string]string{
			"done":       "✓",
			"dropped":    "✗",
			"pending":    "○",
			"ready":      "●",
			"blocked":    "⊘",
			"claimed":    "◐",
			"review":     "◈",
			"refinement": "◌",
		},
	}
}

func asciiTheme() theme {
	return theme{
		teeContain:  "|-- ",
		endContain:  "`-- ",
		teeDepends:  "|-> ",
		endDepends:  "`-> ",
		pipePrefix:  "|   ",
		blankPrefix: "    ",
		rule:        "-",
		stub:        "...",
		ellipsis:    "...",
		cycle:       "(cycle)",
		sep:         " . ",
		fallback:    "[?]",
		glyphs: map[string]string{
			"done":       "[x]",
			"dropped":    "[-]",
			"pending":    "[ ]",
			"ready":      "[>]",
			"blocked":    "[!]",
			"claimed":    "[~]",
			"review":     "[r]",
			"refinement": "[.]",
		},
	}
}

// glyph is the marker for one derived status, falling back to a neutral mark
// for a lane or outcome this tool has not seen before.
func (t theme) glyph(status string) string {
	if g, ok := t.glyphs[status]; ok {
		return g
	}
	return t.fallback
}
