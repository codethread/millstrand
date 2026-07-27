// KANBAN tab: the user↔agent work board as a collapsible epic → feature → task
// tree (spools/kanban.md). One `strand kanban-tree` poll returns every card with
// its parent epic id and its tasks (derived status), so the whole hierarchy is in
// hand and expand/collapse is instant local state — no per-row fetch. Epics group
// their features (`=`/`-` collapses the group, open by default); a feature that
// bears tasks gets a marker and `=`/`-` reveals/hides them (collapsed by default).
// Alongside the board it scans active strands for the land workflow's merge-lock
// sentinel and surfaces it as a one-line status strip. Self-contained: the row
// types, both fetchers, colour maps, the tree component, and the merge-lock banner
// all live here so the tab is edited in exactly one place.

import { Box, Text } from "ink";
import { parseInstant, str, strandJson, type DetailRow, type StrandRecord } from "../data";
import {
  age,
  clip,
  DetailView,
  detailMaxScroll,
  detailPage,
  Failure,
  fitCol,
  listPage,
  ListFooter,
  oneLine,
  pad,
  TableRow,
  windowRows,
  type Cell,
} from "../ui";
import { defineTab, emptyListState, followSelection, reduceListKeys, reduceScrollKeys, type ListState, type RenderCtx } from "../app";

// A feature's task, projected with its derived status by the kanban-tree op.
type TaskChild = { id: string; title: string; state: string; status: string; owner?: string };

// A kanban card (epic or feature) plus the two tree joins the op supplies: the
// epic it hangs under (null for top-level cards) and its tasks.
export type KanbanRow = DetailRow & {
  lane: string;
  type: string;
  owner: string;
  priority: string;
  epic: string | null;
  tasks: TaskChild[];
};

const LANE_COLOR: Record<string, string | undefined> = { claimed: "green", in_review: "magenta", pending: "yellow", refinement: "cyan" };

// Derived task status (kanban-tree / `kanban card`): doing is live work, ready is
// actionable, blocked waits on a dependency, done is closed.
const TASK_STATUS_COLOR: Record<string, string | undefined> = { doing: "green", ready: "yellow", blocked: "red" };

// Priority tint mirrors the spool's p1..p4 urgency (spools/kanban.md): p1 is an
// immediate blocker, p4 is someday. p3 is the unstamped default and stays plain.
const PRIO_COLOR: Record<string, string | undefined> = { p1: "red", p2: "yellow" };
const prioDim = (p: string): boolean => p === "p4";

// Board lane order is review-first urgency, not the spool's lifecycle order:
// claimed work in flight, then the cards under review that a coordinator should
// clear next (in_review), then the actionable queue (pending), then ideas still
// in refinement. Closed strands sink regardless of their lane column — the
// vocabulary-reset cutover leaves closed cards on historic kanban/status while
// live cards carry kanban/lane and freshly closed ones kanban/outcome — and
// show their outcome (done/abandoned/...) dimmed.
const LANE_RANK: Record<string, number> = { claimed: 0, in_review: 1, pending: 2, refinement: 3 };
const laneRank = (r: KanbanRow): number => (r.state === "closed" ? 4 : (LANE_RANK[r.lane] ?? 4));

// created_at is "YYYY-MM-DD HH:MM:SS" (UTC), so lexical order is chronological,
// and "p1".."p4" also compares lexically. Active lanes are queues and sort
// priority-first then oldest-first to agree with `kanban next` (spools/kanban.md);
// the closed bucket lists newest-first so fresh outcomes stay in reach. Used for
// both the top level and each epic's feature group.
function byLane(a: KanbanRow, b: KanbanRow): number {
  const rank = laneRank(a) - laneRank(b);
  if (rank !== 0) return rank;
  if (a.state === "closed") return b.createdAt.localeCompare(a.createdAt);
  const prio = a.priority.localeCompare(b.priority);
  if (prio !== 0) return prio;
  return a.createdAt.localeCompare(b.createdAt);
}

type TreeCard = {
  id: string;
  title?: string;
  state: string;
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
  type: string;
  epic: string | null;
  tasks: TaskChild[];
};

async function fetchKanban(all: boolean): Promise<KanbanRow[]> {
  // The kanban-tree op is active-by-default, widened with `--all true` — the same
  // axis the dash's a-key toggles. It returns the parent-of tiers already joined,
  // so the dash never walks edges itself.
  const args = ["kanban-tree", ...(all ? ["--all", "true"] : [])];
  const res = (await strandJson(args)) as { cards: TreeCard[] };
  return res.cards.map((s): KanbanRow => {
    const attrs = s.attributes;
    return {
      id: s.id,
      title: s.title,
      state: s.state,
      branch: str(attrs["branch"], "-"),
      lane: str(attrs["kanban/lane"], "") || str(attrs["kanban/outcome"], "") || str(attrs["kanban/status"], "?"),
      type: s.type,
      owner: str(attrs["owner"], "-"),
      priority: str(attrs["kanban/priority"], "p3"),
      createdAt: s.created_at,
      updatedAt: s.updated_at,
      attrs,
      epic: s.epic ?? null,
      tasks: s.tasks ?? [],
    };
  });
}

// ── tree flattening ──────────────────────────────────────────────────────────
// The board is a flattened pre-order walk of the epic → feature → task tree.
// Every strand appears exactly once per render — dedup keeps a feature either
// top-level or under its epic, never both — so the row `key` is the plain strand
// id. Selection then stays anchored to the same card when a poll regroups it (a
// feature hopping under a newly-linked epic) instead of jumping, which a
// position-derived key would do the moment the ancestry changed. Epics are open
// unless the user collapsed them; features are closed unless the user expanded
// them — so the default board shows every feature (grouped under any epic) with
// tasks tucked away, matching the old flat board plus expand markers.

type Marker = "open" | "closed" | "leaf";

// `guide` is the ID-column tree art (box-drawing connectors) for this row's place
// in the epic → feature → task tree; empty for a root. Titles carry their own
// indent+marker, so the guide is a second, denser read of the same structure.
type FlatRow = { key: string; depth: number; guide: string } & (
  | { kind: "card"; card: KanbanRow; marker: Marker }
  | { kind: "task"; task: TaskChild }
);

// One box-drawing prefix for a child row: the ancestor continuation columns
// (`segs`, "│ " where an ancestor has siblings below, "  " where it is spent)
// followed by the node's own connector. Roots (depth 0) carry no guide.
const guideOf = (depth: number, segs: string[], last: boolean): string =>
  depth === 0 ? "" : segs.join("") + (last ? "└─" : "├─");

function flatten(cards: KanbanRow[], collapsed: Set<string>, expanded: Set<string>): FlatRow[] {
  const byId = new Map(cards.map((c) => [c.id, c] as const));
  // Group features under an epic that is itself present in the payload; a feature
  // whose epic is closed-and-filtered (or unset) stays a top-level card.
  const featuresByEpic = new Map<string, KanbanRow[]>();
  const claimed = new Set<string>();
  for (const c of cards) {
    if (c.type === "feature" && c.epic && byId.has(c.epic)) {
      (featuresByEpic.get(c.epic) ?? featuresByEpic.set(c.epic, []).get(c.epic)!).push(c);
      claimed.add(c.id);
    }
  }
  for (const feats of featuresByEpic.values()) feats.sort(byLane);
  const topLevel = cards.filter((c) => c.type === "epic" || !claimed.has(c.id)).sort(byLane);

  const rows: FlatRow[] = [];
  // Each card's children are its epic's features or its own tasks; `segs` carries
  // the ancestor continuation columns down so a task under a non-last feature draws
  // the "│" that keeps the epic's branch connected.
  const emitCard = (card: KanbanRow, depth: number, segs: string[], last: boolean) => {
    const isEpic = card.type === "epic";
    const feats = isEpic ? featuresByEpic.get(card.id) ?? [] : [];
    const open = isEpic ? !collapsed.has(card.id) : expanded.has(card.id);
    const hasChildren = isEpic ? feats.length > 0 : card.tasks.length > 0;
    rows.push({ key: card.id, depth, guide: guideOf(depth, segs, last), kind: "card", card, marker: hasChildren ? (open ? "open" : "closed") : "leaf" });
    if (!open || !hasChildren) return;
    const childSegs = depth === 0 ? [] : [...segs, last ? "  " : "│ "];
    if (isEpic) feats.forEach((f, i) => emitCard(f, depth + 1, childSegs, i === feats.length - 1));
    else
      card.tasks.forEach((t, i) =>
        rows.push({ key: t.id, depth: depth + 1, guide: guideOf(depth + 1, childSegs, i === card.tasks.length - 1), kind: "task", task: t }),
      );
  };
  topLevel.forEach((c) => emitCard(c, 0, [], true));
  return rows;
}

const keyOf = (r: FlatRow): string => r.key;
const cardAt = (rows: FlatRow[], i: number): KanbanRow | undefined => {
  const r = rows[i];
  return r?.kind === "card" ? r.card : undefined;
};

// ── list view ──────────────────────────────────────────────────────────────

const MARK: Record<Marker, string> = { open: "▾ ", closed: "▸ ", leaf: "  " };

const rowId = (r: FlatRow): string => (r.kind === "card" ? r.card.id : r.task.id);
// The ID cell doubles as the tree spine: the box-drawing guide precedes the id.
const rowIdCell = (r: FlatRow): string => r.guide + rowId(r);
const rowLane = (r: FlatRow): string => (r.kind === "card" ? r.card.lane : r.task.status);
const rowPrio = (r: FlatRow): string => (r.kind === "card" ? r.card.priority : "");
// PRIO renders as the bare number (colour carries the urgency); rowPrio keeps the
// "p1".."p4" form the colour/dim lookups key on.
const rowPrioNum = (r: FlatRow): string => rowPrio(r).replace(/^p/, "");
// Type column compacts feature→feat so it never widens past its 4-char header.
const TYPE_ABBR: Record<string, string> = { feature: "feat", epic: "epic", task: "task" };
const rowType = (r: FlatRow): string => {
  const t = r.kind === "card" ? r.card.type : "task";
  return TYPE_ABBR[t] ?? t;
};
// Under a narrow terminal (<80) the lane column costs the most width; compact known
// lanes to four-letter codes (unknowns—task statuses—fall back to a four-char slice).
const LANE_ABBR: Record<string, string> = { claimed: "clmd", in_review: "revw", pending: "pend", refinement: "refn" };
const abbrevLane = (lane: string): string => LANE_ABBR[lane] ?? lane.slice(0, 4);
const rowOwner = (r: FlatRow): string => (r.kind === "card" ? r.card.owner : r.task.owner ?? "-");
const rowBranch = (r: FlatRow): string => (r.kind === "card" ? r.card.branch : "");
const rowTitle = (r: FlatRow): string =>
  "  ".repeat(r.depth) + (r.kind === "card" ? MARK[r.marker] : "  ") + oneLine(r.kind === "card" ? r.card.title ?? "" : r.task.title);

const HINT = "↑↓/jk move · ⌃d/⌃u page · = expand · - collapse · ⏎ attrs · ⌃g open · y copy · a all/active · r refresh · ⇥ tab · q quit";

function KanbanTree({
  rows,
  selected,
  interactive,
  cols,
  termRows,
  all,
  loaded,
}: {
  rows: FlatRow[];
  selected: number;
  interactive: boolean;
  cols: number;
  termRows: number;
  all: boolean;
  loaded: boolean;
}) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}cards` : "loading board…", cols)}</Text>;
  }
  const narrow = cols < 80;
  const laneText = (r: FlatRow): string => (narrow ? abbrevLane(rowLane(r)) : rowLane(r));
  // Narrow terminals collapse a present branch to a tick; rows without one keep
  // their bare value (a "-"/"" placeholder) so the column still reads.
  const hasBranch = (v: string): boolean => v !== "" && v !== "-";
  const branchHeader = narrow ? "B" : "BRANCH";
  const branchText = (r: FlatRow): string => {
    const b = rowBranch(r);
    return narrow && hasBranch(b) ? "✓" : b;
  };
  const w = {
    id: fitCol("ID", rows.map(rowIdCell), 16),
    lane: fitCol("LANE", rows.map(laneText), 12),
    prio: fitCol("P", rows.map(rowPrioNum), 4),
    type: fitCol("TYPE", rows.map(rowType), 8),
    owner: fitCol("OWNER", rows.map(rowOwner), 14),
    branch: fitCol(branchHeader, rows.map(branchText), 24),
  };
  const titleWidth = Math.max(0, cols - 12 - w.id - w.lane - w.prio - w.type - w.owner - w.branch);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows);

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("ID", w.id) }, { text: "  " },
          { text: pad("LANE", w.lane) }, { text: "  " },
          { text: pad("P", w.prio) }, { text: "  " },
          { text: pad("TYPE", w.type) }, { text: "  " },
          { text: pad("TITLE", titleWidth) }, { text: "  " },
          { text: pad("OWNER", w.owner) }, { text: "  " },
          { text: branchHeader },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const isTask = r.kind === "task";
        // Cards colour the lane/priority; tasks colour the derived-status cell in
        // the lane column and read dimmer overall (they hang under their feature).
        const laneColor = isSelected ? undefined : isTask ? TASK_STATUS_COLOR[r.task.status] : LANE_COLOR[rowLane(r)];
        const closed = r.kind === "card" && r.card.state === "closed";
        const laneDim = !isSelected && (isTask ? r.task.status === "done" : closed);
        const cells: Cell[] = [
          { text: pad(rowIdCell(r), w.id), dimColor: !isSelected && isTask },
          { text: "  " },
          { text: pad(laneText(r), w.lane), color: laneColor, dimColor: laneDim },
          { text: "  " },
          { text: pad(rowPrioNum(r), w.prio), color: isSelected || closed ? undefined : PRIO_COLOR[rowPrio(r)], dimColor: !isSelected && (closed || prioDim(rowPrio(r))) },
          { text: "  " },
          { text: pad(rowType(r), w.type), dimColor: !isSelected && rowType(r) !== "epic" },
          { text: "  " },
          { text: pad(rowTitle(r), titleWidth), dimColor: !isSelected && (isTask ? r.task.status === "done" : closed) },
          { text: "  " },
          { text: pad(rowOwner(r), w.owner), dimColor: !isSelected },
          { text: "  " },
          { text: pad(branchText(r), w.branch) },
        ];
        return <TableRow key={r.key} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && <ListFooter hint={HINT} cols={cols} start={start} below={below} total={rows.length} />}
    </Box>
  );
}

// ── merge-lock banner ────────────────────────────────────────────────────────
// The singleton merge sentinel (land workflow): at most one active kind=merge-lock
// strand exists, acquired at land sign-off and released at cleanup/abort. Its
// owner is the land root id, land/run-id is the feature, and created_at is when
// the lock was acquired. The repo's merge-lock query isolates the sentinel from
// broad list caps; the board payload itself omits the lock.
//
// LockState is what that scan resolves to. The dash is the coordinator's anomaly
// watch, so it never normalizes a corrupt world into a plausible banner: >1 active
// lock is corruption (break-merge-lock! throws on it), and a lock missing the
// owner/land-run-id that acquire-merge-lock! always sets together is a tampered
// record — both surface as their own loud state rather than a clean "MERGE LOCK"
// strip. A failed scan is likewise isolated so the board still renders (see refresh).
type MergeLock = { id: string; owner: string; feature: string; acquiredAt: string };
type LockState =
  | { kind: "none" }
  | { kind: "ok"; lock: MergeLock }
  | { kind: "corrupt"; count: number }
  | { kind: "malformed"; id: string; missing: string[] }
  | { kind: "error"; message: string };

async function fetchMergeLock(): Promise<LockState> {
  let items: StrandRecord[];
  try {
    items = (await strandJson(["list", "--query", "merge-lock"])) as StrandRecord[];
  } catch (e) {
    return { kind: "error", message: e instanceof Error ? e.message : String(e) };
  }
  const locks = items.filter((s) => str(s.attributes["kind"]) === "merge-lock");
  if (locks.length === 0) return { kind: "none" };
  if (locks.length > 1) return { kind: "corrupt", count: locks.length };
  const lock = locks[0]!;
  const owner = str(lock.attributes["owner"]);
  const feature = str(lock.attributes["land/run-id"]);
  const missing = [owner ? "" : "owner", feature ? "" : "land/run-id"].filter(Boolean);
  if (missing.length > 0) return { kind: "malformed", id: lock.id, missing };
  return { kind: "ok", lock: { id: lock.id, owner, feature, acquiredAt: lock.created_at } };
}

// Reserved viewport lines: the banner draws one line for every state except a
// clean "no lock", so the list windows against the height it actually gets (see
// render/onKey). Kept in lockstep with MergeLockBanner returning null only here.
const lockRows = (st: LockState): number => (st.kind === "none" ? 0 : 1);

// One clipped line above the board. A healthy lock reads magenta so a coordinator
// sees a merge in flight at a glance (feature, owning land run, how long held);
// every anomaly reads red and loud so corruption is never mistaken for a normal
// in-flight merge. Null only when no lock is active, matching lockRows's 0.
function MergeLockBanner(st: LockState, ctx: RenderCtx) {
  const line = (color: string, text: string) => (
    <Text bold color={color}>
      {clip(text, ctx.cols)}
    </Text>
  );
  switch (st.kind) {
    case "none":
      return null;
    case "ok": {
      const held = age(parseInstant(st.lock.acquiredAt), new Date());
      return line("magenta", `MERGE LOCK · ${st.lock.feature} · owner ${st.lock.owner} · held ${held}`);
    }
    case "corrupt":
      return line(
        "red",
        `${st.count} ACTIVE MERGE LOCKS · close duplicate strands manually until one remains; then strand land break-lock --reason`,
      );
    case "malformed":
      return line("red", `MERGE LOCK ${st.id} malformed · missing ${st.missing.join(", ")} · inspect the strand`);
    case "error":
      return line("red", `merge-lock check failed · ${oneLine(st.message)}`);
  }
}

// ── the tab ────────────────────────────────────────────────────────────────
// kanban is a list+detail tab with a status strip, so it composes the shared
// movement/scroll reducers directly (like agents/devflow) rather than a shared list
// helper. Owning the tab locally is what lets the banner scan fail without blanking
// the board and lets its reserved row flow into the paging math, not just the
// render. `collapsed`/`expanded` are the tree's per-card overrides against the
// defaults (epics open, features closed); both survive polls so the tree the user
// shaped stays put while the board refreshes underneath it.
type KanbanView = {
  rows: KanbanRow[];
  lock: LockState;
  loaded: boolean;
  failure: string | null;
  collapsed: Set<string>;
  expanded: Set<string>;
  s: ListState;
};

export const kanbanTab = defineTab<KanbanView>({
  id: "kanban",
  label: "KANBAN",
  init: () => ({ rows: [], lock: { kind: "none" }, loaded: false, failure: null, collapsed: new Set(), expanded: new Set(), s: emptyListState() }),
  fetchKey: () => "",
  allApplies: () => true,
  inDetail: (v) => v.s.view === "detail",
  editTarget: (v) => cardAt(flatten(v.rows, v.collapsed, v.expanded), v.s.selected) ?? null,
  // Unlike editTarget (cards only — a task has no openable source), y copies the id
  // of whatever row is under the cursor, task rows included: they are strands too.
  copyId: (v) => {
    const row = flatten(v.rows, v.collapsed, v.expanded)[v.s.selected];
    return row ? rowId(row) : null;
  },
  refresh: async (_v, all) => {
    // The board is the tab's primary data: a kanban-tree failure surfaces as the
    // full-pane <Failure>. The merge-lock scan is isolated inside fetchMergeLock
    // (it resolves failures to a LockState.error rather than throwing), so a flaky
    // merge-lock query degrades to a loud banner and never blanks a good board.
    try {
      const [rows, lock] = await Promise.all([fetchKanban(all), fetchMergeLock()]);
      return (latest) => ({
        ...latest,
        rows,
        lock,
        loaded: true,
        failure: null,
        s: followSelection(latest.s, flatten(rows, latest.collapsed, latest.expanded), keyOf),
      });
    } catch (e) {
      const failure = e instanceof Error ? e.message : String(e);
      return (latest) => ({ ...latest, loaded: true, failure });
    }
  },
  onKey: (v, ctx) => {
    const { input, key } = ctx;
    const rows = flatten(v.rows, v.collapsed, v.expanded);
    if (v.s.view === "detail") {
      const r = reduceScrollKeys(v.s.detailScroll, input, key, detailMaxScroll(cardAt(rows, v.s.selected), ctx.cols, ctx.termRows), detailPage(ctx.termRows));
      if (r === "back") return { ...v, s: { ...v.s, view: "list" } };
      if (r !== null) return { ...v, s: { ...v.s, detailScroll: r } };
      if (input === "r") ctx.refresh();
      return v;
    }
    // The banner steals a list row when it draws, so page jumps size against the
    // same reduced viewport the list renders into, not the raw terminal.
    const listRows = ctx.termRows - lockRows(v.lock);
    const moved = reduceListKeys(v.s, input, key, rows, keyOf, listPage(listRows));
    if (moved) return { ...v, s: moved };
    // Expand/collapse the selected card. Epics default open (toggle via collapsed);
    // features default closed (toggle via expanded), and only when they bear tasks.
    if (input === "=" || input === "-") {
      const card = cardAt(rows, v.s.selected);
      if (!card) return v;
      const open = input === "=";
      if (card.type === "epic") {
        const collapsed = new Set(v.collapsed);
        open ? collapsed.delete(card.id) : collapsed.add(card.id);
        return { ...v, collapsed };
      }
      if (card.tasks.length === 0) return v;
      const expanded = new Set(v.expanded);
      open ? expanded.add(card.id) : expanded.delete(card.id);
      return { ...v, expanded };
    }
    if (key.return || key.rightArrow || input === "l") return cardAt(rows, v.s.selected) ? { ...v, s: { ...v.s, view: "detail", detailScroll: 0 } } : v;
    if (input === "r") ctx.refresh();
    return v;
  },
  render: (v, ctx) => {
    if (v.failure) return <Failure failure={v.failure} cols={ctx.cols} />;
    const rows = flatten(v.rows, v.collapsed, v.expanded);
    if (v.s.view === "detail" && ctx.interactive)
      return <DetailView row={cardAt(rows, v.s.selected)} scroll={v.s.detailScroll} cols={ctx.cols} termRows={ctx.termRows} />;
    // The banner reserves its row so the list windows against the height it gets and
    // the stacked heights still sum to the pinned frame (detail view hides the strip).
    const reserved = lockRows(v.lock);
    return (
      <Box flexDirection="column">
        {MergeLockBanner(v.lock, ctx)}
        <KanbanTree
          rows={rows}
          selected={v.s.selected}
          interactive={ctx.interactive}
          cols={ctx.cols}
          termRows={ctx.termRows - reserved}
          all={ctx.all}
          loaded={v.loaded}
        />
      </Box>
    );
  },
});
