# Proposal: clj-kondo hook provenance and repository hygiene

**Document ID:** `PROP-Khp-001`
**Status:** Approved
**Approved:** 2026-08-11
**Related RFCs:** None
**Related root specs:** [`docs/spools/writing-shared-spools.md`](../../../docs/spools/writing-shared-spools.md) (consumer linting contract)
**Configuration identification:** Document IDs are ordered as document type, short name, sequential id, then optional version. Nested point IDs use the full document ID.

Once approved this document is frozen. It records the agreed intent; implementation changes belong in the plan, spec deltas, and code.

## PROP-Khp-001.P1 Problem

Consumer repositories can import a producer's clj-kondo export and still override it with local mappings or copied hook functions. The duplicate definitions drift independently while ordinary lint commands continue to pass. Generated imports and caches can also leave dirty working trees or become committed source without a clear ownership rule.

The `d8yjf` rollout exposed both failures. Millhouse overrides Millstrand-owned authoring forms, while Millstrand and Agent Harness approximate Millhouse Workflow forms. Notebook and Standup commit clj-kondo caches. The publisher and bump workflows do not reject these states.

Checked-in external snapshots make a dependency change visible as a consumer review diff. A producer using its resource export directly avoids a second copy that can drift.

## PROP-Khp-001.P2 Goals

- `PROP-Khp-001.G1` Every public authoring form has one clj-kondo source owned and published by the repository that defines the form.
- `PROP-Khp-001.G2` Consumers use reviewed imports from their resolved producer coordinates without local remapping of the same symbols.
- `PROP-Khp-001.G3` Producer repositories use their own export directly instead of storing a generated self-import copy.
- `PROP-Khp-001.G4` Normal lint and quality runs finish with a clean Git tree and cannot commit clj-kondo caches by accident.
- `PROP-Khp-001.G5` The publishing and bump routines check the ownership and cleanliness rules they teach.

## PROP-Khp-001.P3 Non-goals

- `PROP-Khp-001.NG1` This work does not redesign clj-kondo hooks or change the public macro contracts they model.
- `PROP-Khp-001.NG2` It does not remove checked-in external dependency imports. Those reviewed snapshots are part of the consumer contract.
- `PROP-Khp-001.NG3` It does not clean unrelated user changes or ignored local tool caches that do not affect Git state.
- `PROP-Khp-001.NG4` It does not publish a Millstrand `v1` marker.

## PROP-Khp-001.P4 Proposed scope

- `PROP-Khp-001.S1` Millhouse stops overriding Millstrand's exported authoring forms and stops duplicating the Workflow, Chime, and Cron roots' exported analysis.
- `PROP-Khp-001.S2` Millstrand and Agent Harness consume the Millhouse Workflow export and drop local approximations and obsolete namespace mappings.
- `PROP-Khp-001.S3` Notebook and Standup stop tracking clj-kondo cache artifacts and ignore future cache output.
- `PROP-Khp-001.S4` Publishing and bump workflows detect local overrides, import drift, cache artifacts, and a dirty final tree.
- `PROP-Khp-001.S5` Producer, consumer, and hygiene changes are validated and landed in dependency order, with downstream coordinates and imports refreshed when required.

## PROP-Khp-001.P5 Examples

- `PROP-Khp-001.E1` Relative to their repository roots, a producer owns one export while an external consumer stores the generated snapshot under the matching coordinate.

```text
producer/resources/clj-kondo.exports/acme/widgets/config.edn
producer/resources/clj-kondo.exports/acme/widgets/hooks/acme/widgets.clj

consumer/.clj-kondo/imports/acme/widgets/config.edn
consumer/.clj-kondo/imports/acme/widgets/hooks/acme/widgets.clj
```

- `PROP-Khp-001.E2` In the producer's `.clj-kondo/config.edn`, the producer points clj-kondo at its own export and does not create `.clj-kondo/imports/acme/widgets`.

```clojure
{:config-paths ["../resources/clj-kondo.exports/acme/widgets"]}
```

## PROP-Khp-001.P6 Open questions

None.
