.PHONY: help build version-check kanban-tree land-quality install dash api-docs test-go test-restart-acceptance test-e2e docs-site docs-serve docs-check identity-check ci-config-check fmt fmt-check-clj fmt-check-go lint lint-go lint-splint lint-conventions reflect-check deps-report security-report security-report-clj security-report-go kondo kondo-import kondo-import-root kondo-import-workspace kondo-import-batteries kondo-import-unsafe-text-search kondo-lint kondo-lint-root kondo-lint-workspace kondo-lint-batteries kondo-lint-unsafe-text-search check-clj-kondo clean-kondo test-warm test-warm-stop spool-suite-gate

help:
	@printf '%s\n' \
		'Millstrand development commands:' \
		'  make build              Build repo-local strand, mill, and kanban-tree binaries' \
		'  make version-check      Validate VERSION and the matching changelog release' \
		'  make land-quality       Build and run the local landing quality DAG' \
		'    LAND_QUALITY_HEAVY_LIMIT=N sets its positive heavy-job cap (default 2)' \
		'  make test-go            Run Go tests in every Go module' \
		'  make test-restart-acceptance  Run built-binary restart and JVM-pool acceptance' \
		'  make test-e2e           Run end-to-end CLI and REPL tests' \
		'  make fmt-check          Check Clojure and Go formatting' \
		'  make lint               Run Kondo, Splint, convention, and Go linters' \
		'  make kondo              Import dependency configs, then lint every Clojure root' \
		'  make kondo-import       Refresh imported configs from each resolved classpath' \
		'  make kondo-lint         Lint every Clojure root using imported configs' \
		'  make reflect-check      Fail on reflected Java interop' \
		'  make identity-check     Audit active files for stale product identity' \
		'  make ci-config-check    Verify CI invokes identity and documentation gates' \
		'  make docs-check         Regenerate and verify documentation' \
		'  make spool-suite-gate   Run the pinned Millhouse Workflow consumer suite' \
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

# The restart and JVM-pool acceptances control real Mill and Weaver processes.
# Keep them out of the normal Go suite, but make their explicit land-quality
# invocation mandatory.
test-restart-acceptance: build
	@output=$$(mktemp); \
	trap 'rm -f "$$output"' EXIT; \
	if ! (cd cli && go test -json -tags=integration -count=1 -run '^(TestDisposableWeaverRestartAcceptance|TestJVMPoolLifecycleAcceptance|TestJVMPoolProbeFailureAcceptance)$$' ./...) >"$$output"; then \
		cat "$$output"; \
		exit 1; \
	fi; \
	cat "$$output"; \
	for test_name in TestDisposableWeaverRestartAcceptance TestJVMPoolLifecycleAcceptance TestJVMPoolProbeFailureAcceptance; do \
		if ! grep -F '"Action":"pass"' "$$output" | grep -F "\"Test\":\"$$test_name\"" >/dev/null; then \
			echo "test-restart-acceptance: $$test_name did not run and pass" >&2; \
			exit 1; \
		fi; \
	done

test-e2e:
	clojure -M:e2e

lint: kondo lint-splint lint-conventions lint-go

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

# Keep import and source analysis as separate contracts. `kondo` sequences them
# explicitly so parallel Make invocation cannot lint against stale imports.
kondo: kondo-import
	@$(MAKE) --no-print-directory kondo-lint

kondo-import: kondo-import-root kondo-import-workspace kondo-import-batteries kondo-import-unsafe-text-search

kondo-import-root: check-clj-kondo
	@echo "==> root clj-kondo imports"
	@mkdir -p .clj-kondo && \
		rm -rf .clj-kondo/imports && \
		classpath="$$(clojure -Srepro -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-import-workspace: check-clj-kondo
	@echo "==> .millstrand clj-kondo imports"
	@cd .millstrand && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Srepro -Spath -M:dev)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-import-batteries: check-clj-kondo
	@echo "==> spools/batteries clj-kondo imports"
	@cd spools/batteries && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Srepro -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-import-unsafe-text-search: check-clj-kondo
	@echo "==> spools/unsafe-text-search clj-kondo imports"
	@cd spools/unsafe-text-search && \
		rm -rf .clj-kondo/imports && \
		mkdir -p .clj-kondo && \
		classpath="$$(clojure -Srepro -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-lint: kondo-lint-root kondo-lint-workspace kondo-lint-batteries kondo-lint-unsafe-text-search

kondo-lint-root: check-clj-kondo
	@echo "==> root clj-kondo"
	@$(CLJ_KONDO) --repro --parallel --lint src test/clojure test/fixtures/clojure
	@$(CLJ_KONDO) --repro --parallel --lint dev
	@$(CLJ_KONDO) --repro --parallel --lint scripts
	@$(CLJ_KONDO) --repro --parallel --lint resources/clj-kondo.exports .clj-kondo/hooks

kondo-lint-workspace: check-clj-kondo
	@echo "==> .millstrand clj-kondo"
	@cd .millstrand && $(CLJ_KONDO) --repro --parallel --lint init.clj me

kondo-lint-batteries: check-clj-kondo
	@echo "==> spools/batteries clj-kondo"
	@cd spools/batteries && $(CLJ_KONDO) --repro --parallel --lint src

kondo-lint-unsafe-text-search: check-clj-kondo
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

# Run the pinned Millhouse Workflow consumer suite against this checkout.
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
