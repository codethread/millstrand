.PHONY: help build kanban-tree install dash api-docs test-go docs-site docs-serve docs-check identity-check ci-config-check transition-check fmt fmt-check-clj fmt-check-go lint lint-go lint-clj lint-splint lint-conventions reflect-check deps-report security-report security-report-clj security-report-go test-warm test-warm-stop spool-suite-gate

help:
	@printf '%s\n' \
		'Millstrand development commands:' \
		'  make build              Build repo-local strand, mill, and kanban-tree binaries' \
		'  make test-go            Run Go tests in every Go module' \
		'  make fmt-check          Check Clojure and Go formatting' \
		'  make lint               Run Clojure, convention, and Go linters' \
		'  make reflect-check      Fail on reflected Java interop' \
		'  make identity-check     Audit active files for stale product identity' \
		'  make ci-config-check    Verify CI invokes identity and documentation gates' \
		'  make transition-check   Validate the temporary external publisher boundary' \
		'  make docs-check         Regenerate and verify documentation' \
		'  make spool-suite-gate   Run or report the pinned external spool gate' \
		'  make install            Install globally stamped strand and mill binaries' \
		'  make dash               Launch the kanban dashboard' \
		'  make help               Show this command list'

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
# kanban-tree is repo-local development tooling too, on the same terms.
KANBAN_TREE_CLI := ./tools/kanban-tree
# BuildID falls back to the compiled-in "dev" when git is unavailable; it is
# informational (skew attribution), so unlike InstalledSource it may degrade.
BUILD_ID := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
SOURCE_LDFLAGS := -X millstrand-strand-cli/internal/config.InstalledSource=$(CURDIR) -X millstrand-strand-cli/internal/config.BuildID=$(BUILD_ID)
QUICKDOC_DEPS := '{:deps {io.github.borkdude/quickdoc {:git/tag "v0.2.6" :git/sha "ce86780"}}}'
QUICKDOC_SCRIPT := scripts/generate_api_docs.clj

# repo-local build for agents/worktrees validating CLI changes without touching
# the user's global install; run the resulting ./bin/strand and ./bin/mill directly
build: kanban-tree
	mkdir -p ./bin
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/strand $(GO_CLI)
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/mill $(MILL_CLI)

# kanban-tree reads the board through the strand binary beside it.
kanban-tree:
	mkdir -p ./bin
	go build -o ./bin/kanban-tree $(KANBAN_TREE_CLI)

# stamp the user's global binaries with the canonical checkout, not a worktree.
install:
	bash scripts/install

# Interactive kanban TUI supplied by the pinned kanban.spool release.
dash:
	mill bin build kanban-dash
	mill bin run kanban-dash

api-docs:
	@if command -v bb >/dev/null 2>&1; then \
		bb -Sdeps $(QUICKDOC_DEPS) $(QUICKDOC_SCRIPT); \
	else \
		PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" clojure -Sdeps $(QUICKDOC_DEPS) -M $(QUICKDOC_SCRIPT); \
	fi

docs-site:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs build --strict

# Growth budget for AGENTS.md, which holds only what the live surface cannot
# tell an agent. Placement judgment lives with the docs-drift reviewer
# (guidance belongs to prime/about manuals, devflow/specs, or an automated
# check); this cap forces that conversation when the file grows.
AGENTS_MD_LINE_BUDGET := 70

docs-check:
	@lines=$$(awk 'END{print NR}' AGENTS.md) || { echo "docs-check: cannot read AGENTS.md" >&2; exit 1; }; \
	case "$$lines" in ''|*[!0-9]*) echo "docs-check: unexpected AGENTS.md line count '$$lines'" >&2; exit 1;; esac; \
	if [ "$$lines" -gt $(AGENTS_MD_LINE_BUDGET) ]; then \
		echo "AGENTS.md is $$lines lines, over the $(AGENTS_MD_LINE_BUDGET)-line budget."; \
		echo "Move guidance to the surface that owns it (prime/about manuals, devflow/specs, an automated check) instead of growing AGENTS.md."; \
		exit 1; \
	fi
	$(MAKE) api-docs
	git diff --exit-code -- 'spools/batteries.api.md' 'spools/unsafe-text-search.api.md' 'examples/*.api.md' 'docs/api/*.api.md'
	$(MAKE) docs-site

docs-serve:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs serve --dev-addr 127.0.0.1:8000

fmt:
	clojure -M:format/fix
	bash scripts/go-quality format

fmt-check: fmt-check-clj fmt-check-go

fmt-check-clj:
	clojure -M:format

fmt-check-go:
	bash scripts/go-quality format-check

test-go:
	bash scripts/go-quality test

lint: lint-clj lint-splint lint-conventions lint-go

lint-clj:
	clojure -M:lint/clj-kondo

lint-splint:
	clojure -M:lint/splint

# repo conventions that prose alone cannot hold: versioned tenet references,
# ns docstrings everywhere, no local bindings named after clojure.core macros,
# requires embedded in quoted forms resolving to real namespaces, shipped
# spool sources touching millstrand.core.* only from unsafe-named namespaces
# (quality.spool-tiers), and JSON authored as Clojure data rather than
# hand-escaped string literals (quality.json-literals). Workspace-config tests
# use millstrand.ct.* exactly under test/millstrand/ct/, and direct checked-in .millstrand
# paths cannot appear in tests outside that directory (quality.workspace-tests).
lint-conventions:
	@if git grep -n -E 'TEN-''000([^@]|$$)' -- . ':!devflow/TENETS.md'; then \
		echo 'lint-conventions: bare TEN-''000 reference(s); use TEN-''000@1' >&2; \
		exit 1; \
	fi
	clojure -M:lint/conventions

lint-go:
	bash scripts/go-quality lint

reflect-check:
	clojure -M:reflect-check

identity-check:
	bash scripts/quality/millstrand-active-identity.sh
	bash scripts/quality/millstrand-active-identity-regression.sh

ci-config-check:
	bash scripts/quality/millstrand-ci-config.sh

transition-check:
	clojure -M:transition-check

deps-report:
	-clojure -M:deps/antq
	@$(MAKE) security-report-go
	# local-only deep NVD scan; needs CLJ_WATSON_NVD_API_KEY exported
	-clojure -M:security/clj-watson-nvd

security-report: security-report-clj security-report-go

security-report-clj:
	-clojure -M:security/clj-watson

security-report-go:
	bash scripts/go-quality security

# Per-worktree warm test loop: probe-or-boot the worktree's warm REPL and run the
# NS-named namespaces through it. Iteration only — never a Done-when gate; the
# cold `clojure -M:test <ns...>` run is the slice gate (PLAN-Ttv-001.TC1).
test-warm:
	NS="$(NS)" bash scripts/test-warm

# Run the pinned external spool suites against this checkout. The script reads
# each pin from .millstrand/spools.edn and materializes an isolated sibling layout.
spool-suite-gate:
	bash scripts/spool-suite-gate

# Reap the worktree's warm REPL by recorded PID (PID only, never `pkill -f`) and
# remove the runtime files (PLAN-Ttv-001.R1). The land cleanup step calls this
# before `wktree remove`.
test-warm-stop:
	@if [ -f .test-repl.pid ]; then \
		pid="$$(tr -d '[:space:]' <.test-repl.pid)"; \
		if [ -n "$$pid" ]; then \
			echo "test-warm-stop: killing recorded warm REPL pid $$pid"; \
			kill "$$pid" 2>/dev/null || true; \
		fi; \
	fi; \
	rm -f .test-repl-port .test-repl.pid
