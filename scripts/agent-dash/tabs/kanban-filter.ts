// Saved label-filter views for the KANBAN board, and their on-disk store. Pure
// transforms plus two filesystem entry points, kept out of the Ink tab so Bun
// tests exercise matching, slot editing, and persistence without a dashboard.
//
// Filtering is client-side by design: `kanban board` ships each card's `labels`
// array (spool-side `--label` is AND-only, with no OR and no negation), so the
// richer boolean surface here costs nothing and never needs a second read.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

// A label is either required or forbidden; absent from `terms` means the view is
// indifferent to it. Excludes are hard in both modes (see `matches`), so `mode`
// only ever combines the includes.
export type FilterMode = "and" | "or";
export type Term = "include" | "exclude";
export type FilterView = { name: string; mode: FilterMode; terms: Record<string, Term> };

// `active` indexes `views`; `enabled` is the ⇧f on/off toggle, held separately so
// switching a view off and back on remembers which one it was.
export type FilterState = { views: FilterView[]; active: number | null; enabled: boolean };

export const emptyView = (): FilterView => ({ name: "", mode: "and", terms: {} });
export const emptyFilterState = (): FilterState => ({ views: [], active: null, enabled: true });

// A view nobody named and nobody gave a term to is the "new slot" placeholder:
// it is dropped at commit rather than saved. A *named* view with no terms is
// kept — it is a deliberate "show everything" bookmark.
export const isBlank = (v: FilterView): boolean =>
  v.name.trim() === "" && Object.keys(v.terms).length === 0;

// ── term editing ─────────────────────────────────────────────────────────────
// space toggles a label in and out of the view; ! swings it to the forbidden
// side (and back to required), so a label can be excluded without first being
// included.

export const toggleTerm = (t: Term | undefined): Term | undefined => (t === undefined ? "include" : undefined);
export const negateTerm = (t: Term | undefined): Term | undefined => (t === "exclude" ? "include" : "exclude");

export function withTerm(view: FilterView, label: string, next: Term | undefined): FilterView {
  const terms = { ...view.terms };
  if (next === undefined) delete terms[label];
  else terms[label] = next;
  return { ...view, terms };
}

// ── matching ─────────────────────────────────────────────────────────────────

// An excluded label always vetoes, in both modes: "or" widens which cards get in,
// never which ones get past a hard exclusion. With no includes at all the view is
// a pure subtraction, so everything the excludes don't veto passes.
export function matches(labels: readonly string[], view: FilterView): boolean {
  const carried = new Set(labels);
  const entries = Object.entries(view.terms);
  if (entries.some(([label, term]) => term === "exclude" && carried.has(label))) return false;
  const includes = entries.filter(([, term]) => term === "include").map(([label]) => label);
  if (includes.length === 0) return true;
  return view.mode === "and"
    ? includes.every((label) => carried.has(label))
    : includes.some((label) => carried.has(label));
}

// The board is a tree, so a filter that keeps a feature must keep the epic it
// hangs under or the row loses its grouping. Epics therefore survive on their own
// match OR as scaffolding for a surviving feature; an epic matching nothing and
// parenting nothing drops out entirely rather than showing as an empty group.
export function applyFilter<T extends { id: string; type: string; epic: string | null; labels: string[] }>(
  cards: readonly T[],
  view: FilterView | null,
): T[] {
  if (!view) return [...cards];
  const kept = cards.filter((c) => matches(c.labels, view));
  const parents = new Set(kept.filter((c) => c.type !== "epic" && c.epic).map((c) => c.epic!));
  const keptIds = new Set(kept.map((c) => c.id));
  return cards.filter((c) => keptIds.has(c.id) || (c.type === "epic" && parents.has(c.id)));
}

// ── slots ────────────────────────────────────────────────────────────────────

// The overlay edits a working copy of the saved views with one blank slot
// appended, so `l` off the end of the list lands on a fresh view. Committing
// drops every blank slot and re-points `active` at whichever view the cursor was
// on — or clears the filter outright when that slot was the blank one.
export function commitViews(views: readonly FilterView[], slot: number): { views: FilterView[]; active: number | null } {
  const kept: FilterView[] = [];
  let active: number | null = null;
  views.forEach((view, i) => {
    if (isBlank(view)) return;
    if (i === slot) active = kept.length;
    kept.push(view);
  });
  return { views: kept, active };
}

// Drop the view at `slot` from the overlay's working copy. The trailing blank is
// the "start a new view" affordance rather than a saved view, so deleting it is
// refused — and if the slot removed was a filled trailing one, a fresh blank
// takes its place so a new view is always one step off the end. Like every other
// overlay edit this only lands when ⏎ commits, so ⎋ still puts the view back.
export function removeSlot(views: readonly FilterView[], slot: number): { views: FilterView[]; slot: number } {
  if (slot === views.length - 1 && isBlank(views[slot]!)) return { views: [...views], slot };
  const next = views.filter((_, i) => i !== slot);
  if (next.length === 0 || !isBlank(next[next.length - 1]!)) next.push(emptyView());
  return { views: next, slot: Math.min(slot, next.length - 1) };
}

// The one-line summary the filter strip shows: `#tests & !docs`, or the mode-less
// "all cards" when a named view carries no terms.
export function describeView(view: FilterView): string {
  const terms = Object.entries(view.terms)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([label, term]) => (term === "exclude" ? `!${label}` : `#${label}`));
  if (terms.length === 0) return "all cards";
  return terms.join(view.mode === "and" ? " & " : " | ");
}

// ── store ────────────────────────────────────────────────────────────────────
// Saved views are a UI preference, not coordination data, so they live in the
// user's cache rather than the workspace — writing under .skein would surface as
// a dirty tree on every validation run. One file holds every workspace's views,
// keyed by workspace root, so dashboards over different worlds keep their own.

export const filtersFile = (): string =>
  join(process.env.XDG_CACHE_HOME || join(homedir(), ".cache"), "skein", "agent-dash", "filters.json");

function normalizeView(raw: unknown): FilterView | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const terms: Record<string, Term> = {};
  if (r.terms && typeof r.terms === "object") {
    for (const [label, term] of Object.entries(r.terms as Record<string, unknown>)) {
      if (term === "include" || term === "exclude") terms[label] = term;
    }
  }
  return { name: typeof r.name === "string" ? r.name : "", mode: r.mode === "or" ? "or" : "and", terms };
}

// A corrupt or unreadable store degrades to "no saved views" plus a message the
// tab shows in a banner: losing a filter bookmark must never blank the board, but
// silently discarding the user's saved views would be worse than saying so.
export function loadFilterState(file: string, root: string): { state: FilterState; error: string | null } {
  let text: string;
  try {
    text = readFileSync(file, "utf8");
  } catch (e) {
    // A store that has never been written is the normal first-run case.
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return { state: emptyFilterState(), error: null };
    return { state: emptyFilterState(), error: e instanceof Error ? e.message : String(e) };
  }
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>;
    const entry = parsed?.[root];
    if (!entry || typeof entry !== "object") return { state: emptyFilterState(), error: null };
    const e = entry as Record<string, unknown>;
    const views = Array.isArray(e.views) ? e.views.map(normalizeView).filter((v): v is FilterView => v !== null) : [];
    const active = typeof e.active === "number" && e.active >= 0 && e.active < views.length ? e.active : null;
    return { state: { views, active, enabled: e.enabled !== false }, error: null };
  } catch (e) {
    return { state: emptyFilterState(), error: `saved filters unreadable: ${e instanceof Error ? e.message : String(e)}` };
  }
}

// Read-modify-write so a dashboard over one workspace never drops another's
// views. Returns an error message rather than throwing — a failed preference
// write is worth a banner, not a dead board.
export function saveFilterState(file: string, root: string, state: FilterState): string | null {
  try {
    let all: Record<string, unknown> = {};
    try {
      all = JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>;
      if (!all || typeof all !== "object") all = {};
    } catch {
      // missing or corrupt store: this write re-establishes it
    }
    all[root] = { views: state.views, active: state.active, enabled: state.enabled };
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify(all, null, 2) + "\n");
    return null;
  } catch (e) {
    return `saving filters failed: ${e instanceof Error ? e.message : String(e)}`;
  }
}
