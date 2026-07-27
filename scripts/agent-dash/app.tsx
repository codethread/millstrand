// The dashboard shell: the tab contract, the reusable list+detail state kit,
// the tab bar, the active-tab-only polling loop, the single keyboard dispatch,
// and the fullscreen/raw-mode entry point. Tab modules plug into the registry
// runApp receives; the shell owns no concrete row type and never runs a second
// useInput — every view-local key is routed to the active tab.

import { appendFileSync } from "node:fs";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Box, render, useApp, useInput, useStdin, type Key } from "ink";
import { copyToClipboard, editorArgv, editorFileFor, opts, workspaceRoot, type DetailRow } from "./data";
import { Header, TableRow, useTerminalSize, type Cell } from "./ui";

// ── reusable list+detail view state ──────────────────────────────────────────
// One scrollable list with an optional attribute detail. Selection is anchored
// to a stable per-row key (id, or a tree path) so it survives refreshes that
// reorder or drop rows. Both simple tabs and the agents subviews share this,
// so there is one copy of the movement/scroll logic, not one per view.

export type ListState = {
  selected: number;
  anchor: string | null;
  view: "list" | "detail";
  detailScroll: number;
};

export const emptyListState = (): ListState => ({ selected: 0, anchor: null, view: "list", detailScroll: 0 });

// Re-anchor the selection after a fetch: follow the anchored key; if it vanished,
// hold the old index clamped into the new list.
export function followSelection<R>(s: ListState, rows: R[], keyOf: (r: R) => string): ListState {
  if (rows.length === 0) return { ...s, selected: 0, anchor: null };
  const byKey = s.anchor ? rows.findIndex((r) => keyOf(r) === s.anchor) : -1;
  const selected = byKey >= 0 ? byKey : Math.max(0, Math.min(s.selected, rows.length - 1));
  return { ...s, selected, anchor: keyOf(rows[selected]!) };
}

// List-mode movement (↑↓/jk, ⌃u/⌃d half-page, g/G). Returns the next state, or
// null when the key is not a movement command so callers can layer enter/refresh
// on top. `page` is the ⌃u/⌃d jump distance (half a viewport, see listPage).
export function reduceListKeys<R>(s: ListState, input: string, key: Key, rows: R[], keyOf: (r: R) => string, page = 1): ListState | null {
  if (rows.length === 0) return null;
  const go = (raw: number): ListState => {
    const next = Math.max(0, Math.min(rows.length - 1, raw));
    return { ...s, selected: next, anchor: keyOf(rows[next]!) };
  };
  if (key.ctrl && input === "u") return go(s.selected - page);
  if (key.ctrl && input === "d") return go(s.selected + page);
  if (key.upArrow || input === "k") return go(s.selected - 1);
  if (key.downArrow || input === "j") return go(s.selected + 1);
  if (input === "g") return go(0);
  if (input === "G") return go(rows.length - 1);
  return null;
}

// Detail-mode scroll (↑↓/jk, ⌃u/⌃d half-page, g/G) and back (esc/h/←). Returns
// the next scroll offset, "back" to leave the detail, or null when the key is not
// handled. The offset can be 0, so callers must test `!== null`. `page` is the
// ⌃u/⌃d jump distance (half a viewport, see detailPage).
export function reduceScrollKeys(scroll: number, input: string, key: Key, maxScroll: number, page = 1): number | "back" | null {
  if (key.ctrl && input === "u") return Math.max(0, scroll - page);
  if (key.ctrl && input === "d") return Math.min(maxScroll, scroll + page);
  if (key.upArrow || input === "k") return Math.max(0, scroll - 1);
  if (key.downArrow || input === "j") return Math.min(maxScroll, scroll + 1);
  if (input === "g") return 0;
  if (input === "G") return maxScroll;
  if (key.escape || key.leftArrow || input === "h") return "back";
  return null;
}

// ── the tab contract ─────────────────────────────────────────────────────────
// A tab owns its view state V (persisted by the shell across tab switches),
// fetches into it, reduces its own keys, and renders list/detail/failure. The
// shell only owns the header/tab-bar chrome, the all/active axis, tab switching,
// quit, and the polling cadence. defineTab erases V at the single unsafe
// boundary so App can hold every tab uniformly.

export type RenderCtx = { cols: number; termRows: number; interactive: boolean; all: boolean };

export type KeyCtx<V> = {
  input: string;
  key: Key;
  cols: number;
  termRows: number;
  setV: (next: V | ((v: V) => V)) => void;
  refresh: () => void;
};

export type Tab<V> = {
  id: string;
  label: string;
  init: () => V;
  // Fetch under the current all/active axis, then return a pure updater the shell
  // applies against the *latest* V — never the pre-fetch snapshot — so a slow poll
  // landing after the user has scrolled or switched views folds in the new rows
  // without clobbering that interim navigation. Errors are caught into the updater
  // so a poll failure renders instead of throwing. `v` selects which data to fetch
  // (e.g. the agents mode); the updater re-checks the latest V and drops itself if
  // that choice changed mid-flight.
  refresh: (v: V, all: boolean) => Promise<(latest: V) => V>;
  // A change re-runs the poll immediately (e.g. the agents runs⇄plans toggle,
  // which swaps datasets). Constant for single-dataset tabs.
  fetchKey: (v: V) => string;
  // View-local keys: movement, enter, esc, scroll, and any tab-private keys.
  onKey: (v: V, ctx: KeyCtx<V>) => V;
  // A detail is open: the shell blocks ⇥ and leaves the all/active axis inert.
  inDetail: (v: V) => boolean;
  // The tab is reading raw text (a filter name being typed) and every key belongs
  // to it — including the shell's own q/⇥/a/⌃g/y, which would otherwise quit or
  // switch tabs mid-word. Absent means "never captures".
  capturesInput?: (v: V) => boolean;
  // The strand under the cursor in the tab's current view, or null when nothing is
  // focused (empty list, or a view with no single strand like the graph pane). The
  // shell opens it in $EDITOR on ⌃g.
  editTarget: (v: V) => DetailRow | null;
  // The strand id under the cursor, or null when nothing is focused. Broader than
  // editTarget — a bare tree/task row has an id even where no full DetailRow is in
  // hand (kanban tasks, an unopened plans row) — so the shell can copy it on y.
  copyId: (v: V) => string | null;
  // The all/active axis applies in the tab's current view.
  allApplies: (v: V) => boolean;
  render: (v: V, ctx: RenderCtx) => React.ReactElement;
};

export function defineTab<V>(tab: Tab<V>): Tab<unknown> {
  return tab as unknown as Tab<unknown>;
}

// ── the shell ────────────────────────────────────────────────────────────────

function TabBar({ tabs, active, cols }: { tabs: readonly Tab<unknown>[]; active: number; cols: number }) {
  const cells: Cell[] = [];
  tabs.forEach((t, i) => {
    if (i > 0) cells.push({ text: " | ", dimColor: true });
    cells.push({ text: ` ${t.label} `, bold: i === active, inverse: i === active });
  });
  return <TableRow cells={cells} width={cols} />;
}

// The per-tab envelope the shell owns: the all/active axis, the last refresh
// time, and the tab's opaque view state.
type Env = { all: boolean; refreshedAt: Date; v: unknown };

function App({
  tabs,
  fullscreen,
  preloadedFirst,
  frame,
}: {
  tabs: readonly Tab<unknown>[];
  fullscreen: boolean;
  preloadedFirst: unknown | undefined;
  frame: { clear?: () => void };
}) {
  const { exit } = useApp();
  const { isRawModeSupported, setRawMode } = useStdin();
  const { cols, rows: termRows } = useTerminalSize();
  // Bumped after an $EDITOR round-trip to force Ink to repaint the clobbered frame.
  const [, setRedraw] = useState(0);
  const [active, setActive] = useState(0);
  // A transient one-line status (a y-copy result), shown in the header and cleared
  // after a moment. The timer is held in a ref so a fresh flash resets it rather
  // than leaving an older timer to blank the newer message.
  const [flash, setFlash] = useState<string | null>(null);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showFlash = useCallback((msg: string) => {
    setFlash(msg);
    if (flashTimer.current) clearTimeout(flashTimer.current);
    flashTimer.current = setTimeout(() => setFlash(null), 2000);
  }, []);
  useEffect(() => () => void (flashTimer.current && clearTimeout(flashTimer.current)), []);
  const [envs, setEnvs] = useState<Env[]>(() =>
    tabs.map((t, i) => ({
      all: opts.all,
      refreshedAt: new Date(),
      v: i === 0 && preloadedFirst !== undefined ? preloadedFirst : t.init(),
    })),
  );
  // Mirror for callbacks/timers that must read the latest env without
  // re-subscribing. refreshing guards against overlapping fetches per tab.
  const envsRef = useRef(envs);
  envsRef.current = envs;
  const refreshing = useRef<boolean[]>(tabs.map(() => false));
  // A refresh asked for while one is already in flight (a v/all toggle during a
  // slow poll) is coalesced here and re-run against the then-current v/all when
  // the in-flight fetch settles, so the new view can't be left stale.
  const pendingRefresh = useRef<boolean[]>(tabs.map(() => false));

  const patchEnv = useCallback((i: number, patch: Partial<Env>) => {
    setEnvs((es) => es.map((e, j) => (j === i ? { ...e, ...patch } : e)));
  }, []);

  const setV = useCallback((i: number, next: unknown | ((v: unknown) => unknown)) => {
    setEnvs((es) => es.map((e, j) => (j === i ? { ...e, v: typeof next === "function" ? (next as (v: unknown) => unknown)(e.v) : next } : e)));
  }, []);

  const refresh = useCallback(
    async (i: number, allOverride?: boolean) => {
      // No overlapping fetches: a request landing mid-flight is queued, not run,
      // then replayed below with the latest v/all once this fetch settles.
      if (refreshing.current[i]) {
        pendingRefresh.current[i] = true;
        return;
      }
      refreshing.current[i] = true;
      const all = allOverride ?? envsRef.current[i].all;
      try {
        const apply = await tabs[i].refresh(envsRef.current[i].v, all);
        setEnvs((es) => es.map((e, j) => (j === i ? { ...e, refreshedAt: new Date(), v: apply(e.v) } : e)));
      } finally {
        refreshing.current[i] = false;
        if (pendingRefresh.current[i]) {
          pendingRefresh.current[i] = false;
          void refresh(i);
        }
        if (opts.once) exit();
      }
    },
    [exit, tabs],
  );

  // Suspend the dashboard, hand the editor the controlling tty, then restore.
  // spawnSync blocks the input loop so no poll runs meanwhile; on return we drop
  // Ink's cached frame (frame.clear) and bump redraw so the alt screen repaints
  // from scratch rather than diffing against a frame the editor overwrote.
  const openInEditor = useCallback(
    (row: DetailRow) => {
      let file: string;
      try {
        file = editorFileFor(row);
      } catch {
        return;
      }
      setRawMode?.(false);
      if (fullscreen) process.stdout.write("\x1b[?1049l");
      try {
        Bun.spawnSync([...editorArgv(), file], { cwd: workspaceRoot, stdin: "inherit", stdout: "inherit", stderr: "inherit" });
      } catch {
        // editor missing or spawn failed: fall through to restore the dashboard.
      }
      if (fullscreen) process.stdout.write("\x1b[?1049h\x1b[2J\x1b[H");
      setRawMode?.(true);
      frame.clear?.();
      setRedraw((n) => n + 1);
    },
    [fullscreen, frame, setRawMode],
  );

  // Copy the id under the cursor to a clipboard, flashing the result. The copy is
  // best-effort across tmux/OS tools (copyToClipboard); a world with none reachable
  // flashes the failure with the id still shown so it can be read off the screen.
  const copyCursorId = useCallback(
    async (id: string) => {
      const how = await copyToClipboard(id);
      showFlash(how ? `copied ${id} · ${how}` : `no clipboard — ${id}`);
    },
    [showFlash],
  );

  // Re-poll on tab entry and whenever the active tab's fetch key changes (an
  // agents view toggle swaps datasets); the interval then refreshes only the
  // active tab. Toggling all/active refetches directly from its key handler, so
  // it is intentionally not a dependency here.
  const fetchKey = tabs[active].fetchKey(envs[active].v);
  useEffect(() => {
    if (opts.once) {
      exit();
      return;
    }
    void refresh(active);
    const timer = setInterval(() => void refresh(active), opts.interval * 1000);
    return () => clearInterval(timer);
  }, [active, fetchKey, refresh, exit]);

  useInput(
    (input, key) => {
      const t = tabs[active];
      const v = envsRef.current[active].v;
      if (process.env.SHUTTLE_DASH_DEBUG) {
        appendFileSync(
          process.env.SHUTTLE_DASH_DEBUG,
          `${JSON.stringify({ input, ret: key.return, esc: key.escape, tab: t.id, inDetail: t.inDetail(v) })}\n`,
        );
      }
      // A capturing tab owns the whole keyboard: every global binding is skipped so
      // no keystroke meant for a text field can quit or switch tabs mid-word.
      if (t.capturesInput?.(v) !== true) {
        if (input === "q") {
          exit();
          return;
        }
        if (key.tab && !t.inDetail(v)) {
          setActive((a) => (key.shift ? (a - 1 + tabs.length) % tabs.length : (a + 1) % tabs.length));
          return;
        }
        if (input === "a" && !t.inDetail(v) && t.allApplies(v)) {
          const newAll = !envsRef.current[active].all;
          patchEnv(active, { all: newAll });
          void refresh(active, newAll);
          return;
        }
        if (key.ctrl && input === "g") {
          const target = t.editTarget(v);
          if (target) openInEditor(target);
          return;
        }
        if (input === "y") {
          const id = t.copyId(v);
          if (id) void copyCursorId(id);
          return;
        }
      }
      const ctx: KeyCtx<unknown> = {
        input,
        key,
        cols,
        termRows,
        setV: (next) => setV(active, next),
        refresh: () => void refresh(active),
      };
      // Reduce against the latest V so keys arriving faster than React commits
      // don't drop through a stale snapshot. onKey's only side effects (refresh,
      // async detail fetch) suspend on an await before any setState, so running
      // them inside the updater is safe.
      setV(active, (prev: unknown) => t.onKey(prev, ctx));
    },
    { isActive: isRawModeSupported === true && !opts.once },
  );

  const interactive = isRawModeSupported === true && !opts.once;
  const env = envs[active];
  const rctx: RenderCtx = { cols, termRows, interactive, all: env.all };
  // Pin the frame to the full terminal in the alt screen: a constant-height root
  // makes every frame terminal-tall so a shorter frame overwrites a taller one
  // (list ⇄ detail, tab switches) instead of leaving stale lines. overflow hidden
  // keeps a miscounted view from scrolling the alt screen.
  return (
    <Box flexDirection="column" height={fullscreen ? termRows : undefined} overflow={fullscreen ? "hidden" : undefined}>
      <Header all={env.all} noun={tabs[active].label.toLowerCase()} refreshedAt={env.refreshedAt} cols={cols} flash={flash} />
      <TabBar tabs={tabs} active={active} cols={cols} />
      <Box marginTop={1} flexDirection="column">
        {tabs[active].render(env.v, rctx)}
      </Box>
    </Box>
  );
}

export async function runApp(tabs: readonly Tab<unknown>[]) {
  const fullscreen = process.stdout.isTTY === true && process.stdin.isTTY === true && !opts.once;

  // Non-interactive frames pre-fetch so the printed frame is real data. The
  // interactive path must NOT await spawns before render: under Bun, subprocess
  // activity before Ink attaches to stdin leaves the pty in canonical mode and
  // setRawMode never takes effect — keys then arrive line-buffered and dead.
  let preloadedFirst: unknown | undefined;
  if (!fullscreen) {
    const apply = await tabs[0].refresh(tabs[0].init(), opts.all);
    preloadedFirst = apply(tabs[0].init());
  }

  // Enter the alt screen and clear+home before Ink renders, so the frame starts
  // at row 0 rather than the shell's old cursor row and no scrollback shows
  // through. Leaving the alt screen on exit restores the shell buffer verbatim.
  if (fullscreen) process.stdout.write("\x1b[?1049h\x1b[2J\x1b[H");
  // Handed to App so an $EDITOR round-trip can drop Ink's cached frame and force a
  // full repaint of the alt screen the editor clobbered.
  const frame: { clear?: () => void } = {};
  const app = render(<App tabs={tabs} fullscreen={fullscreen} preloadedFirst={preloadedFirst} frame={frame} />);
  frame.clear = app.clear;
  await app.waitUntilExit();
  if (fullscreen) process.stdout.write("\x1b[?1049l");
}
