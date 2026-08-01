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
// picks next. Candidates are tried in descending order of how strongly they
// claim to be the offender, and the first tier that lands near a name answers:
// the values the message quotes verbatim, then the last thing the user typed,
// then whatever else the details map is carrying.
func suggest(e Error, names []string) []string {
	quoted := QuotedDetails(e.Message, e.Details)
	for _, token := range sortedValues(quoted) {
		if ranked := rank(token, names); len(ranked) > 0 {
			return ranked
		}
	}
	if len(e.Command) > 0 {
		if ranked := rank(e.Command[len(e.Command)-1], names); len(ranked) > 0 {
			return ranked
		}
	}
	return agreed(remainingValues(e.Details, quoted), names)
}

// agreed ranks every candidate in the last tier and answers only when the ones
// that rank all say the same thing.
//
// The tier earns its place on the two errors that need it most: "Operation not
// found" and "Query not found" quote nothing, put the mistyped word under two
// keys apiece, and leave an argv tail that is a card id rather than the op. But
// it is also the tier with no provenance — a piece of metadata could sit two
// edits from a real name without being what the user mistyped. Agreement is the
// test that stands in for provenance. When two candidates point different ways
// the renderer cannot know which one is the typo, and a confident wrong answer
// sitting above the real list is worse than no answer at all.
func agreed(candidates, names []string) []string {
	var chosen []string
	for _, token := range candidates {
		ranked := rank(token, names)
		if len(ranked) == 0 {
			continue
		}
		if chosen != nil && !sameNames(chosen, ranked) {
			return nil
		}
		chosen = ranked
	}
	return chosen
}

// remainingValues collects the details this error carries that no earlier tier
// has already tried: every string value whose key the message does not quote,
// in key order, with duplicates dropped so a word repeated under two keys is one
// candidate rather than two agreeing ones.
func remainingValues(details map[string]any, quoted map[string]string) []string {
	byKey := map[string]string{}
	for key, value := range details {
		if _, alreadyQuoted := quoted[key]; alreadyQuoted {
			continue
		}
		if text, isString := value.(string); isString {
			byKey[key] = text
		}
	}
	seen := map[string]bool{}
	values := []string{}
	for _, value := range sortedValues(byKey) {
		if seen[value] {
			continue
		}
		seen[value] = true
		values = append(values, value)
	}
	return values
}

// sameNames reports whether two rankings are the same offer.
func sameNames(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
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
	if len(matches) == 0 {
		return nil
	}
	sort.SliceStable(matches, func(i, j int) bool { return matches[i].distance < matches[j].distance })
	ranked := make([]string, 0, min(len(matches), maxSuggestions))
	for _, m := range matches[:min(len(matches), maxSuggestions)] {
		ranked = append(ranked, m.name)
	}
	return ranked
}
