# Batch task refs

**Document ID:** `RFC-001` **Status:** Accepted **Date:** 2026-06-24 **Related:** [Strand Model](../specs/strand-model.md), [CLI Surface](../specs/cli.md), [DB-owned task IDs feature](../archive/26-06-24__db-owned-task-ids/proposal.md) **Configuration identification:** `RFC-001` is the first RFC in this repository. Every nested point ID is prefixed with `RFC-001`.

## RFC-001.P1 Problem

Once task ids are database-owned, users cannot know durable ids before creation. That is fine for sequential scripts that create a task, capture the returned id, and then link later work. It is awkward for batch creation of a small DAG where tasks need to reference each other before any database-owned ids exist.

## RFC-001.P2 Goals

- **RFC-001.G1:** Allow batch task creation to express intra-batch dependencies before durable ids exist.
- **RFC-001.G2:** Keep database-owned ids as the only durable task identifiers.
- **RFC-001.G3:** Let users write readable batch input with local aliases that are resolved during import.

## RFC-001.P3 Non-goals

- **RFC-001.NG1:** This RFC does not decide the DB-owned id generation algorithm; that belongs to the active id feature.
- **RFC-001.NG2:** This RFC does not add global user-controlled aliases or alternate durable identifiers.
- **RFC-001.NG3:** This RFC does not define a complete import/export format beyond task creation and dependency edges.

## RFC-001.P4 Options

| ID | Summary | Pros | Cons |
| --- | --- | --- | --- |
| RFC-001.O1 | Require strictly sequential creation and linking | No new batch format | Verbose for DAG setup; callers must manually capture ids between steps |
| RFC-001.O2 | Batch EDN shapes with temporary `:ref` aliases | Readable DAG input; refs disappear after creation; fits Clojure tooling | Requires parser and validation for duplicate/missing refs |
| RFC-001.O3 | Let callers provide durable ids in batch mode | Simple dependency references | Reintroduces id collision and overwrite risks the id feature is fixing |

## RFC-001.P5 Recommendation

- **RFC-001.REC1:** Accept `RFC-001.O2`: support batch EDN task shapes where an optional `:ref` field is only a batch-local alias.
- **RFC-001.REC2:** Use a generic edge form for intra-batch relationships so batch input can represent `depends-on`, `mentions`, and future edge types without adding dependency-specific syntax.
- **RFC-001.REC3:** Resolve edge targets by type to avoid ambiguity: symbolic targets identify batch-local refs, and string targets identify already-known durable ids. Replace refs with database-owned ids in all persisted records; command output may include a non-persistent ref-to-id mapping to help callers capture generated ids.

## RFC-001.P6 Consequences

- **RFC-001.C1:** Batch creation should fail loudly on duplicate refs, missing refs, malformed edge declarations, or any task creation failure.
- **RFC-001.C2:** The task model should remain centered on database-owned durable ids; `:ref` should not become a stored task field unless a later feature explicitly adds alias metadata.
- **RFC-001.C3:** Follow-up feature planning should define the final EDN container shape, transaction boundary, output shape, and validation behavior.
- **RFC-001.C4:** The accepted EDN task shape should use generic edge declarations, for example `{:ref design :title "Design"}` and `{:title "Docs" :edges [{:type "depends-on" :to design} {:type "mentions" :to "existing-id"}]}`.
- **RFC-001.C5:** The initial CLI surface should read batch EDN from standard input only. File input is intentionally omitted because callers can pipe or redirect files into stdin, keeping the API minimal.

## RFC-001.P7 Outcome

- **RFC-001.OUT1:** Accepted on 2026-06-24.
- **RFC-001.OUT2:** Batch task creation will use EDN input from standard input with optional batch-local refs and generic edge declarations.
- **RFC-001.OUT3:** Symbolic edge targets resolve to batch-local refs; string edge targets resolve to existing durable task ids. This avoids ambiguity while preserving database-owned ids as the only durable identifiers.
- **RFC-001.OUT4:** Command output may include a temporary ref-to-generated-id mapping, but refs remain non-durable and must not be stored in task or edge records.
- **RFC-001.OUT5:** The initial CLI will not expose file-specific input flags; callers can pipe or redirect files into stdin.
- **RFC-001.OUT6:** Follow-up implementation belongs in a separate batch task refs feature.
