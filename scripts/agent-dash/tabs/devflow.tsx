// DEVFLOW tab: three views over the live devflow workflow runs. `d` on a feature
// opens its workflow DAG.
//   LIST  (default) — a row per run from `strand list --query devflow-runs`, each
//                     active run enriched with `strand flow-status <feature>` for
//                     its actionable frontier, agent failures, and stalled gates.
//   DETAIL          — the shared attribute view of the selected run strand, with
//                     the frontier composed in as a synthetic `frontier` attribute
//                     (the shared DetailView can't be given a bespoke component).
//   GRAPH           — the feature's workflow DAG as boxart: the step/checkpoint
//                     subtree (`strand subgraph <run-id>`) with each subagent gate's
//                     fulfilling agent run (from flow-status `gates`, labelled
//                     harness + phase) hung beneath its step, plus dashed depends-on
//                     ordering between steps. Shares the AGENTS graph pipeline, so it
//                     degrades to the indented tree when graph-easy is missing.

import { Box, Text } from "ink";
import { renderBoxart, str, strandJson, type DetailRow, type StrandRecord } from "../data";
import {
  clip,
  DetailView,
  detailMaxScroll,
  detailPage,
  Failure,
  fitCol,
  hintRows,
  listPage,
  ListFooter,
  oneLine,
  pad,
  TableRow,
  windowRows,
  type Cell,
  type ListProps,
} from "../ui";
import { defineTab, emptyListState, followSelection, reduceListKeys, reduceScrollKeys, type ListState } from "../app";
import {
  buildGraphDot,
  emptyGraph,
  fetchDepEdges,
  GraphPane,
  reduceGraphKeys,
  type Edge,
  type GraphNode,
  type GraphState,
} from "../graph";

type FrontierItem = {
  id: string;
  role: string; // "step" | "checkpoint"
  title: string;
  state?: string;
  instruction?: string;
  hitl: boolean; // human-in-the-loop checkpoint (workflow/checkpoint-kind on the item strand)
};

type StateLabel = "error" | "attention" | "active" | "done";

export type DevflowRow = DetailRow & {
  feature: string;
  stage: string;
  stateLabel: StateLabel;
  frontier: FrontierItem[];
  failCount: number;
  // Set when flow-status enrichment failed for an active run: the row renders
  // loud (red "error" state) instead of a healthy-looking zero frontier.
  flowError?: string;
};

const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const msg = (e: unknown): string => (e instanceof Error ? e.message : String(e));

const STATE_COLOR: Record<StateLabel, string | undefined> = { error: "red", attention: "red", active: "green", done: undefined };
// error floats highest, then attention above active; done sinks. Stable within
// groups (Array.sort).
const STATE_RANK: Record<StateLabel, number> = { error: 0, attention: 1, active: 2, done: 3 };

type GateRun = { id: string; "agent-run/harness"?: string; "agent-run/phase"?: string; "agent-run/result"?: string };
type Gate = { gate: string; id: string; run?: GateRun };

type FlowStatus = {
  done?: boolean;
  frontier?: { id: string; role: string; title: string; state?: string; instruction?: string }[];
  gates?: Gate[];
  "agent-failures"?: unknown[];
  "stalled-gates"?: unknown[];
};

// checkpoint-kind is not carried inline in the flow-status frontier, and only
// checkpoints can be human decisions, so we only pay the extra `strand show` for those.
async function frontierWithHitl(raw: NonNullable<FlowStatus["frontier"]>): Promise<FrontierItem[]> {
  return Promise.all(
    raw.map(async (f): Promise<FrontierItem> => {
      let hitl = false;
      if (f.role === "checkpoint") {
        try {
          const item = (await strandJson(["show", f.id])) as StrandRecord;
          hitl = item.attributes?.["workflow/checkpoint-kind"] === "human";
        } catch {
          hitl = false;
        }
      }
      return { id: f.id, role: f.role, title: str(f.title), state: f.state, instruction: f.instruction, hitl };
    }),
  );
}

function frontierAttr(frontier: FrontierItem[]): string {
  if (frontier.length === 0) return "none";
  return frontier
    .map((f, i) => {
      const head = `${i + 1}. ${f.role} ${f.id}${f.hitl ? " [HITL]" : ""}  ${oneLine(f.title)}`;
      return f.instruction ? `${head}\n   ↳ ${oneLine(f.instruction)}` : head;
    })
    .join("\n");
}

async function fetchDevflow(all: boolean): Promise<DevflowRow[]> {
  const args = ["list", "--query", "devflow-runs", ...(all ? [] : ["--state", "active"])];
  const runs = (await strandJson(args)) as StrandRecord[];
  const rows = await Promise.all(
    runs.map(async (s): Promise<DevflowRow> => {
      const attrs = s.attributes;
      const feature = str(attrs["devflow/feature"], "-");
      const stage = str(attrs["devflow/stage"], "-");
      const base = {
        id: s.id,
        title: s.title,
        state: s.state,
        branch: "-",
        createdAt: s.created_at,
        updatedAt: s.updated_at,
        feature,
        stage,
      };
      // Only live runs have a meaningful frontier; closed/archived runs sink to
      // "done" and skip flow-status entirely (it errors on unknown run-ids).
      if (s.state !== "active") {
        return { ...base, attrs, stateLabel: "done", frontier: [], failCount: 0 };
      }
      // Enrichment must fail loudly: a broken flow-status call on an active run
      // surfaces as a red "error" row carrying the message (list + detail view),
      // never a healthy-looking zero frontier that hides an actionable failure.
      let status: FlowStatus;
      try {
        status = (await strandJson(["flow-status", feature])) as FlowStatus;
      } catch (e) {
        const flowError = msg(e);
        return {
          ...base,
          attrs: { ...attrs, "flow-status-error": flowError },
          stateLabel: "error",
          frontier: [],
          failCount: 0,
          flowError,
        };
      }
      const frontier = await frontierWithHitl(status.frontier ?? []);
      const failCount = arr(status["agent-failures"]).length;
      const stalled = arr(status["stalled-gates"]).length;
      const stateLabel: StateLabel = status.done ? "done" : failCount > 0 || stalled > 0 ? "attention" : "active";
      return {
        ...base,
        attrs: { ...attrs, frontier: frontierAttr(frontier) },
        stateLabel,
        frontier,
        failCount,
      };
    }),
  );
  return rows.sort((a, b) => STATE_RANK[a.stateLabel] - STATE_RANK[b.stateLabel]);
}

// ── graph build ────────────────────────────────────────────────────────────
// Shape the feature's step subtree into the shared graph pipeline's generic
// nodes. `subgraph <run-id>` returns the root and its step/checkpoint
// children joined by parent-of; each subagent gate that has fulfilled its step
// contributes a run node hung beneath that step, so the DAG shows which agent ran
// each delegated step and where it stands.

type SubgraphResult = {
  root_ids?: string[];
  strands?: StrandRecord[];
  edges?: { from_strand_id: string; to_strand_id: string; edge_type: string }[];
};

const firstLine = (s: string): string => oneLine(s.split("\n").find((l) => l.trim()) ?? s);

function featureGraph(sub: SubgraphResult, gates: Gate[]): { nodes: GraphNode[]; parentEdges: Edge[] } {
  const byId = new Map((sub.strands ?? []).map((s) => [s.id, s] as const));
  const parentEdges: Edge[] = [];
  for (const e of sub.edges ?? []) if (e.edge_type === "parent-of") parentEdges.push([e.from_strand_id, e.to_strand_id]);

  // A run node per fulfilled gate, attached under its step. Skip pending gates
  // (no run yet) and gates whose step is outside this subtree.
  const runNodes = new Map<string, GraphNode>();
  for (const g of gates) {
    if (!g.run || !byId.has(g.id)) continue;
    parentEdges.push([g.id, g.run.id]);
    runNodes.set(g.run.id, {
      id: g.run.id,
      kind: "run",
      status: `${g.run["agent-run/harness"] ?? "?"} ${g.run["agent-run/phase"] ?? "?"}`,
      title: firstLine(g.run["agent-run/result"] ?? ""),
      depth: 0,
    });
  }

  // Pre-order DFS over parent-of for node order + depth (the fallback's indent).
  const children = new Map<string, string[]>();
  for (const [from, to] of parentEdges) (children.get(from) ?? children.set(from, []).get(from)!).push(to);
  const nodes: GraphNode[] = [];
  const seen = new Set<string>();
  const walk = (id: string, depth: number) => {
    if (seen.has(id)) return;
    seen.add(id);
    const s = byId.get(id);
    if (s) nodes.push({ id: s.id, kind: str(s.attributes["workflow/role"], "strand"), status: str(s.state, "-"), title: str(s.title), depth });
    else if (runNodes.has(id)) nodes.push({ ...runNodes.get(id)!, depth });
    for (const c of children.get(id) ?? []) walk(c, depth + 1);
  };
  for (const root of sub.root_ids ?? []) walk(root, 0);
  // Any strand not reached from a declared root (defensive) still gets a node.
  for (const s of sub.strands ?? []) if (!seen.has(s.id)) walk(s.id, 0);
  return { nodes, parentEdges };
}

// ── list view ──────────────────────────────────────────────────────────────

const LIST_HINT = "↑↓/jk move · ⌃d/⌃u page · ⏎ attrs+frontier · y copy · d graph · a all/active · r refresh · ⇥ tab · q quit";

const frontierText = (r: DevflowRow): string =>
  r.flowError
    ? `flow-status failed: ${oneLine(r.flowError)}`
    : r.frontier.length === 0
      ? "0"
      : `${r.frontier.length} · ${oneLine(r.frontier[0]!.title)}`;

function DevflowList({ rows, selected, interactive, cols, termRows, all, loaded }: ListProps<DevflowRow>) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}devflow runs` : "loading devflow runs…", cols)}</Text>;
  }
  const w = {
    feature: fitCol("FEATURE", rows.map((r) => r.feature), 28),
    stage: fitCol("STAGE", rows.map((r) => r.stage), 14),
    state: fitCol("STATE", rows.map((r) => r.stateLabel), 10),
    fail: fitCol("FAIL", rows.map((r) => String(r.failCount)), 5),
  };
  const frontierWidth = Math.max(0, cols - 8 - w.feature - w.stage - w.state - w.fail);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows, hintRows(LIST_HINT, cols));

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("FEATURE", w.feature) }, { text: "  " },
          { text: pad("STAGE", w.stage) }, { text: "  " },
          { text: pad("STATE", w.state) }, { text: "  " },
          { text: pad("FRONTIER", frontierWidth) }, { text: "  " },
          { text: pad("FAIL", w.fail) },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const cells: Cell[] = [
          { text: pad(r.feature, w.feature) },
          { text: "  " },
          { text: pad(r.stage, w.stage) },
          { text: "  " },
          { text: pad(r.stateLabel, w.state), color: isSelected ? undefined : STATE_COLOR[r.stateLabel], dimColor: !isSelected && r.stateLabel === "done" },
          { text: "  " },
          { text: pad(clip(frontierText(r), frontierWidth), frontierWidth) },
          { text: "  " },
          { text: pad(String(r.failCount), w.fail), color: isSelected ? undefined : r.failCount > 0 ? "red" : undefined, dimColor: !isSelected && r.failCount === 0 },
        ];
        return <TableRow key={r.id} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && <ListFooter hint={LIST_HINT} cols={cols} start={start} below={below} total={rows.length} />}
    </Box>
  );
}

// ── the tab ──────────────────────────────────────────────────────────────────
// LIST carries the shared list+detail state kit (s.view flips table⇄detail); GRAPH
// is a separate full-pane mode keyed by the graphed run so ⇥ stays live there and
// opening one re-polls immediately.

type DevflowView = {
  mode: "list" | "graph";
  rows: DevflowRow[];
  loaded: boolean;
  failure: string | null;
  s: ListState;
  graph: GraphState | null;
};

export const devflowTab = defineTab<DevflowView>({
  id: "devflow",
  label: "DEVFLOW",
  init: () => ({ mode: "list", rows: [], loaded: false, failure: null, s: emptyListState(), graph: null }),
  fetchKey: (v) => (v.mode === "graph" ? `graph:${v.graph?.root ?? ""}` : "list"),
  // The graph is one feature's DAG, so the all/active axis only applies to the list.
  allApplies: (v) => v.mode === "list" && v.s.view === "list",
  // The graph is a full-pane view, not a modal detail, so ⇥ stays live there.
  inDetail: (v) => v.mode === "list" && v.s.view === "detail",
  // ⌃g opens the selected run strand; the graph pane has no single strand focus.
  editTarget: (v) => (v.mode === "list" ? (v.rows[v.s.selected] ?? null) : null),
  // y copies the selected run's id, or the graphed run's id from the graph pane.
  copyId: (v) => (v.mode === "list" ? (v.rows[v.s.selected]?.id ?? null) : (v.graph?.root ?? null)),
  refresh: async (v, all) => {
    if (v.mode === "list") {
      try {
        const rows = await fetchDevflow(all);
        return (latest) =>
          latest.mode === "list"
            ? { ...latest, rows, loaded: true, failure: null, s: followSelection(latest.s, rows, (r) => r.id) }
            : latest;
      } catch (e) {
        const failure = msg(e);
        return (latest) => (latest.mode === "list" ? { ...latest, loaded: true, failure } : latest);
      }
    }

    // graph mode: fetch the run's step subtree + its gate runs + depends-on
    // ordering, build DOT, and — unless the DOT is unchanged from the last render —
    // shell out to graph-easy. Mirrors the AGENTS graph refresh: the pipeline is
    // awaited here (never blocking the render loop), and the returned updater drops
    // itself when the user has left the graph or graphed a different run.
    const g = v.graph;
    if (!g) return (latest) => latest;
    const root = g.root;
    const feature = v.rows.find((r) => r.id === root)?.feature;
    try {
      const [sub, gates] = await Promise.all([
        strandJson(["subgraph", root]) as Promise<SubgraphResult>,
        feature
          ? (strandJson(["flow-status", feature]) as Promise<FlowStatus>).then((f) => f.gates ?? []).catch(() => [] as Gate[])
          : Promise.resolve([] as Gate[]),
      ]);
      const { nodes, parentEdges } = featureGraph(sub, gates);
      const stepIds = nodes.filter((n) => n.kind !== "run" && n.id !== root).map((n) => n.id);
      const depEdges = await fetchDepEdges(stepIds).catch(() => [] as Edge[]);
      const { dot, fallback } = buildGraphDot(nodes, parentEdges, depEdges, process.stdout.columns || 120);
      if (g.dot === dot && g.art) {
        return (latest) =>
          latest.mode === "graph" && latest.graph?.root === root
            ? { ...latest, graph: { ...latest.graph, fallback, loaded: true } }
            : latest;
      }
      const { lines, error } = await renderBoxart(dot);
      return (latest) => {
        if (latest.mode !== "graph" || latest.graph?.root !== root) return latest;
        return { ...latest, graph: { ...latest.graph, dot, fallback, loaded: true, art: lines, error } };
      };
    } catch (e) {
      const failure = msg(e);
      return (latest) =>
        latest.mode === "graph" && latest.graph?.root === root
          ? { ...latest, graph: { ...latest.graph, loaded: true, error: failure, art: null } }
          : latest;
    }
  },
  onKey: (v, ctx) => {
    const { input, key } = ctx;
    if (v.mode === "graph") {
      const g = v.graph;
      if (!g) return { ...v, mode: "list" };
      if (input === "r") {
        ctx.refresh();
        return v;
      }
      const next = reduceGraphKeys(g, input, key, ctx.cols, ctx.termRows);
      if (next === "back") return { ...v, mode: "list" };
      return next ? { ...v, graph: next } : v;
    }
    if (v.s.view === "detail") {
      const r = reduceScrollKeys(v.s.detailScroll, input, key, detailMaxScroll(v.rows[v.s.selected], ctx.cols, ctx.termRows), detailPage(ctx.termRows));
      if (r === "back") return { ...v, s: { ...v.s, view: "list" } };
      if (r !== null) return { ...v, s: { ...v.s, detailScroll: r } };
      if (input === "r") ctx.refresh();
      return v;
    }
    const moved = reduceListKeys(v.s, input, key, v.rows, (r) => r.id, listPage(ctx.termRows, hintRows(LIST_HINT, ctx.cols)));
    if (moved) return { ...v, s: moved };
    if (input === "d") {
      const r = v.rows[v.s.selected];
      return r ? { ...v, mode: "graph", graph: emptyGraph(r.id) } : v;
    }
    if (key.return || key.rightArrow || input === "l")
      return v.rows[v.s.selected] ? { ...v, s: { ...v.s, view: "detail", detailScroll: 0 } } : v;
    if (input === "r") ctx.refresh();
    return v;
  },
  render: (v, ctx) => {
    const { cols, termRows, interactive, all } = ctx;
    if (v.mode === "graph") {
      return v.graph ? (
        <GraphPane g={v.graph} cols={cols} termRows={termRows} label={`dag ${v.rows.find((r) => r.id === v.graph!.root)?.feature ?? v.graph.root}`} />
      ) : (
        <Text dimColor>{clip("no graph selected", cols)}</Text>
      );
    }
    if (v.failure) return <Failure failure={v.failure} cols={cols} />;
    if (v.s.view === "detail" && interactive)
      return <DetailView row={v.rows[v.s.selected]} scroll={v.s.detailScroll} cols={cols} termRows={termRows} />;
    return (
      <DevflowList rows={v.rows} selected={v.s.selected} interactive={interactive} cols={cols} termRows={termRows} all={all} loaded={v.loaded} />
    );
  },
});
