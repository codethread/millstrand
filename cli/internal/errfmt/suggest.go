package errfmt

import (
	"sort"
	"strings"
)

// Matching policy. Suggestions are a courtesy, so the thresholds are tight
// enough that silence is the common answer: nothing beats a wrong guess sitting
// above the real list.
const (
	// maxSuggestDistance is the case-insensitive edit distance a name may sit
	// from the offending token and still be offered.
	maxSuggestDistance = 2
	// maxSuggestions caps the offer, so a token near a whole family of names
	// does not bury the list it is standing in front of.
	maxSuggestions = 3
)

// levenshtein is the classic two-row edit distance over runes.
//
// Twenty lines here rather than github.com/agnivade/levenshtein: cli/go.mod
// carries exactly one direct dependency, the policy above is ours to tune
// either way, and a dependency-free errfmt stays a leaf package with nothing
// for deps-report or govulncheck to track. The card records the trade.
func levenshtein(a, b string) int {
	left, right := []rune(a), []rune(b)
	previous := make([]int, len(right)+1)
	current := make([]int, len(right)+1)
	for j := range previous {
		previous[j] = j
	}
	for i := 1; i <= len(left); i++ {
		current[0] = i
		for j := 1; j <= len(right); j++ {
			substitution := previous[j-1]
			if left[i-1] != right[j-1] {
				substitution++
			}
			current[j] = min(previous[j]+1, min(current[j-1]+1, substitution))
		}
		previous, current = current, previous
	}
	return previous[len(right)]
}

// suggest offers the names closest to whatever the error is complaining about,
// or nothing at all.
//
// Which value that is stays affordance-driven — no key name is ever spelled out
// here, because `available` is a convention any op may follow and its
// companions are called token, arg, query, operation, and whatever a spool
// picks next. Candidates are tried in order of how strongly they claim to be
// the offender, and the first one that lands near a name wins: the values the
// message quotes verbatim, then the last thing the user typed, then the rest of
// the details map in key order.
func suggest(e Error, names []string) []string {
	for _, token := range suggestTokens(e) {
		if ranked := rank(token, names); len(ranked) > 0 {
			return ranked
		}
	}
	return nil
}

// suggestTokens lists the candidate tokens in descending order of claim. The
// trailing tier is what makes the feature fire on the errors that name their
// token only in the details map — an unknown op or query says "not found" and
// carries the word itself alongside — and it is reached only once the quoted
// value and the typed argv have both come up empty.
func suggestTokens(e Error) []string {
	quoted := QuotedDetails(e.Message, e.Details)
	tokens := append([]string{}, sortedValues(quoted)...)
	if len(e.Command) > 0 {
		tokens = append(tokens, e.Command[len(e.Command)-1])
	}
	remainder := map[string]string{}
	for key, value := range e.Details {
		text, ok := value.(string)
		if _, alreadyQuoted := quoted[key]; ok && !alreadyQuoted {
			remainder[key] = text
		}
	}
	return append(tokens, sortedValues(remainder)...)
}

// sortedValues reads a key→value map out in key order, so an error quoting two
// of its own details suggests the same thing on every run.
func sortedValues(byKey map[string]string) []string {
	keys := make([]string, 0, len(byKey))
	for key := range byKey {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	values := make([]string, 0, len(keys))
	for _, key := range keys {
		values = append(values, byKey[key])
	}
	return values
}

// rank returns the names within maxSuggestDistance of token, closest first with
// the producer's own list order breaking ties, capped at maxSuggestions. A
// token already on the list is not a typo and ranks nothing.
func rank(token string, names []string) []string {
	if token == "" {
		return nil
	}
	folded := strings.ToLower(token)
	type match struct {
		name     string
		distance int
	}
	matches := []match{}
	for _, name := range names {
		distance := levenshtein(folded, strings.ToLower(name))
		if distance == 0 {
			return nil
		}
		if distance <= maxSuggestDistance {
			matches = append(matches, match{name: name, distance: distance})
		}
	}
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].distance < matches[j].distance })
	ranked := make([]string, 0, min(len(matches), maxSuggestions))
	for _, m := range matches[:min(len(matches), maxSuggestions)] {
		ranked = append(ranked, m.name)
	}
	return ranked
}
