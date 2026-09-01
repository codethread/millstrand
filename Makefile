.PHONY: help build version-check kanban-tree land-quality install dash api-docs test-go test-restart-acceptance test-e2e docs-site docs-serve docs-check identity-check ci-config-check fmt fmt-check-clj fmt-check-go lint lint-go lint-clj lint-clj-root lint-clj-millstrand lint-clj-batteries lint-clj-unsafe-text-search lint-splint lint-conventions reflect-check deps-report security-report security-report-clj security-report-go kondo-configs kondo-configs-root kondo-configs-millstrand kondo-configs-batteries kondo-configs-unsafe-text-search check-clj-kondo clean-kondo test-warm test-warm-stop

help:
	@printf '%s\n' \
		'Millstrand development commands:' \
		'  make build              Build repo-local strand, mill, and kanban-tree binaries' \
		'  make version-check      Validate VERSION and the matching changelog release' \
		'  make land-quality       Build and run the local landing quality DAG' \
		'    LAND_QUALITY_HEAVY_LIMIT=N sets its positive heavy-job cap (default 2)' \
		'  make test-go            Run Go tests in every Go module' \
		'  make test-restart-acceptance  Run the built-binary disposable Weaver restart acceptance' \
		'  make test-e2e           Run end-to-end CLI and REPL tests' \
		'  make fmt-check          Check Clojure and Go formatting' \
		'  make lint               Run Clojure, convention, and Go linters' \
		'  make reflect-check      Fail on reflected Java interop' \
		'  make identity-check     Audit active files for stale product identity' \
		'  make ci-config-check    Verify CI invokes identity and documentation gates' \
		'  make docs-check         Regenerate and verify documentation' \
		'  make install            Install globally stamped strand and mill binaries' \
		'  make dash               Launch the kanban dashboard' \
		'  make help               Show this command list'

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
# kanban-tree is repo-local development tooling too, on the same terms.
KANBAN_TREE_CLI := ./tools/kanban-tree
LAND_QUALITY_CLI := ./tools/land-quality
# BuildID falls back to the compiled-in "dev" when git is unavailable. A dirty
# suffix keeps an uncommitted or untracked build distinct from its HEAD commit.
VERSION_FILE := VERSION
VERSION := $(shell tr -d '\n' < $(VERSION_FILE))
BUILD_ID := $(shell revision=$$(git rev-parse HEAD 2>/dev/null || echo dev); if [ "$$revision" != dev ] && [ -n "$$(git status --porcelain --untracked-files=normal 2>/dev/null)" ]; then echo "$$revision-dirty"; else echo "$$revision"; fi)
SOURCE_LDFLAGS := -X millstrand-strand-cli/internal/config.InstalledSource=$(CURDIR) -X millstrand-strand-cli/internal/config.Version=$(VERSION) -X millstrand-strand-cli/internal/config.BuildID=$(BUILD_ID)
QUICKDOC_DEPS := '{:deps {io.github.borkdude/quickdoc {:git/tag "v0.2.6" :git/sha "ce86780"}}}'
QUICKDOC_SCRIPT := scripts/generate_api_docs.clj
CLJ_KONDO := clj-kondo
CLJ_KONDO_VERSION := 2026.08.04

# repo-local build for agents/worktrees validating CLI changes without touching
# the user's global install; run the resulting ./bin/strand and ./bin/mill directly
build: version-check kanban-tree
	mkdir -p ./bin
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/strand $(GO_CLI)
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/mill $(MILL_CLI)

version-check:
	@lines=$$(wc -l < $(VERSION_FILE) | tr -d ' '); \
	version=$$(tr -d '\n' < $(VERSION_FILE)); \
	if [ "$$lines" != 1 ] || ! printf '%s\n' "$$version" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$$'; then \
		echo 'VERSION must contain one MAJOR.MINOR.PATCH line' >&2; \
		exit 1; \
	fi; \
	if ! grep -Fq "## $$version -" CHANGELOG.md; then \
		echo "CHANGELOG.md has no release heading for $$version" >&2; \
		exit 1; \
	fi

# kanban-tree reads the board through the strand binary beside it.
kanban-tree:
	mkdir -p ./bin
	go build -o ./bin/kanban-tree $(KANBAN_TREE_CLI)

land-quality:
	mkdir -p ./bin
	go build -o ./bin/land-quality $(LAND_QUALITY_CLI)
	./bin/land-quality

# stamp the user's global binaries with the canonical checkout, not a worktree.
install: version-check
	bash scripts/install

# Interactive kanban TUI supplied by the pinned Millhouse Kanban root.
dash:
	mill bin build kanban-dash
	mill bin run kanban-dash

api-docs:
	@if command -v bb >/dev/null 2>&1; then \
		bb -Sdeps $(QUICKDOC_DEPS) $(QUICKDOC_SCRIPT); \
	else \
		PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" clojure -Sdeps $(QUICKDOC_DEPS) -M $(QUICKDOC_SCRIPT); \
	fi
	bun run format:api-docs

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
	git diff --exit-code -- 'spools/batteries.api.md' 'spools/unsafe-text-search.api.md' 'docs/api/*.api.md'
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

# The restart acceptance controls real Mill and Weaver processes and exercises
# caller convergence over a gated probe. Keep it out of the normal Go suite,
# but make its explicit land-quality invocation mandatory.
test-restart-acceptance: build
	@output=$$(mktemp); \
	trap 'rm -f "$$output"' EXIT; \
	if ! (cd cli && go test -json -tags=integration -count=1 -run '^TestDisposableWeaverRestartAcceptance$$' ./...) >"$$output"; then \
		cat "$$output"; \
		exit 1; \
	fi; \
	cat "$$output"; \
	if ! grep -F '"Action":"pass"' "$$output" | grep -F '"Test":"TestDisposableWeaverRestartAcceptance"' >/dev/null; then \
		echo 'test-restart-acceptance: TestDisposableWeaverRestartAcceptance did not run and pass' >&2; \
		exit 1; \
	fi

test-e2e:
	clojure -M:e2e

lint: lint-clj lint-splint lint-conventions lint-go

check-clj-kondo:
	@command -v $(CLJ_KONDO) >/dev/null 2>&1 || { \
		echo "clj-kondo $(CLJ_KONDO_VERSION) is required" >&2; \
		exit 1; \
	}
	@actual="$$($(CLJ_KONDO) --version)"; \
	expected="clj-kondo v$(CLJ_KONDO_VERSION)"; \
	if [ "$$actual" != "$$expected" ]; then \
		echo "Expected $$expected, found $$actual" >&2; \
		exit 1; \
	fi

kondo-configs: kondo-configs-root kondo-configs-millstrand kondo-configs-batteries kondo-configs-unsafe-text-search

kondo-configs-root: check-clj-kondo
	@echo "==> root clj-kondo imports"
	@rm -rf .clj-kondo/imports
	@classpath="$$(clojure -Spath -M:test)"; \
	$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-configs-millstrand: check-clj-kondo
	@echo "==> .millstrand clj-kondo imports"
	@cd .millstrand && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Spath -M:dev)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-configs-batteries: check-clj-kondo
	@echo "==> spools/batteries clj-kondo imports"
	@cd spools/batteries && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-configs-unsafe-text-search: check-clj-kondo
	@echo "==> spools/unsafe-text-search clj-kondo imports"
	@cd spools/unsafe-text-search && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

lint-clj: lint-clj-root lint-clj-millstrand lint-clj-batteries lint-clj-unsafe-text-search

lint-clj-root: kondo-configs-root
	@echo "==> root clj-kondo"
	@$(CLJ_KONDO) --repro --parallel --lint src test/clojure
	@$(CLJ_KONDO) --repro --parallel --lint dev
	@$(CLJ_KONDO) --repro --parallel --lint scripts
	@$(CLJ_KONDO) --repro --parallel --lint resources/clj-kondo.exports .clj-kondo/hooks

lint-clj-millstrand: kondo-configs-millstrand
	@echo "==> .millstrand clj-kondo"
	@cd .millstrand && $(CLJ_KONDO) --repro --parallel --lint init.clj ct

lint-clj-batteries: kondo-configs-batteries
	@echo "==> spools/batteries clj-kondo"
	@cd spools/batteries && $(CLJ_KONDO) --repro --parallel --lint src

lint-clj-unsafe-text-search: kondo-configs-unsafe-text-search
	@echo "==> spools/unsafe-text-search clj-kondo"
	@cd spools/unsafe-text-search && $(CLJ_KONDO) --repro --parallel --lint src

clean-kondo:
	rm -rf \
		.clj-kondo/imports \
		.clj-kondo/.cache \
		.millstrand/.clj-kondo/imports \
		.millstrand/.clj-kondo/.cache \
		spools/batteries/.clj-kondo/imports \
		spools/batteries/.clj-kondo/.cache \
		spools/unsafe-text-search/.clj-kondo/imports \
		spools/unsafe-text-search/.clj-kondo/.cache

lint-splint:
	clojure -M:lint/splint

# repo conventions that prose alone cannot hold: versioned tenet references,
# ns docstrings everywhere, no local bindings named after clojure.core macros,
# requires embedded in quoted forms resolving to real namespaces, shipped
# spool sources touching millstrand.core.* only from unsafe-named namespaces
# (quality.spool-tiers), and JSON authored as Clojure data rather than
# hand-escaped string literals (quality.json-literals). Workspace-config tests
# use millstrand.ct.* exactly under test/clojure/millstrand/ct/, and direct checked-in .millstrand
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
	bash test/shell/quality/millstrand-active-identity-regression.sh

ci-config-check:
	bash scripts/quality/millstrand-ci-config.sh

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
