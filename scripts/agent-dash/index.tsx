#!/usr/bin/env bun
// Interactive TUI dashboard over the live coordination world, for code owners
// working in this repo (not shipped, not part of the CLI surface). Built on
// Ink; the shell, polling loop, and strand access live in ./app, ./ui, ./data.
//
// A tab bar (AGENTS | KANBAN | DEVFLOW) sits under the header; ⇥ cycles tabs
// from any list view and each tab keeps its own selection/scroll. Keys: ↑/↓ or
// j/k move, enter/l opens a full-attribute detail view of the selected strand,
// esc/h goes back, g/G jump, a toggles all/active (where the tab's current view
// supports it), r forces a refresh, q quits. On AGENTS, v toggles the flat runs
// table ⇄ the plans delegation tree, and d graphs the selected plans node's
// delegation subtree as boxart; on KANBAN, the board is an epic → feature → task
// tree where = expands and - collapses the selected card (epics open by default,
// a feature's tasks closed) and f opens the saved label-filter picker (⇧f parks
// the active view); on DEVFLOW, d graphs the selected feature's workflow DAG
// (esc/h returns). Non-TTY (and --once) prints the first tab's flat runs frame.
//
// Adding a tab is a local change: write a module under ./tabs that exports a Tab
// via defineTab(...) — composing the shared list/detail/graph reducers from ./app
// and ./ui for a plain list+detail or a tab that owns several views — then add it
// to the registry below. No other file needs to change.
//
// Usage: bun scripts/agent-dash/index.tsx [--interval secs] [--all] [--once] [--workspace dir]

import { runApp } from "./app";
import { agentsTab } from "./tabs/agents";
import { devflowTab } from "./tabs/devflow";
import { kanbanTab } from "./tabs/kanban";

await runApp([agentsTab, kanbanTab, devflowTab]);
