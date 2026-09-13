# Weaver runtime delta for declared op returns

**Document ID:** `DELTA-Dcr-dr-001`

**Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) (`SPEC-004`)

**Feature:** [../proposal.md](../proposal.md) (`PROP-Dcr-001`)

**Contract:** [../brief.md](../brief.md)

**Status:** Draft

**Last Updated:** 2026-07-14

## DELTA-Dcr-dr-001.P1 Summary

This feature extends the operation-registry contracts in `SPEC-004.C63a-d`. Registry entries may carry a validated `:returns` declaration, and full help renders it. Ordinary op invocation still returns the handler result without checking or changing it.

## DELTA-Dcr-dr-001.P2 Contract changes

- **DELTA-Dcr-dr-001.CC1 — `SPEC-004.C63a`: add return metadata.** The accepted op metadata keys gain optional `:returns`. Registration and replacement retain the declaration in the registry entry after validating it through `skein.api.return-shape.alpha`. Unknown metadata keys continue to fail.

- **DELTA-Dcr-dr-001.CC2 — `SPEC-004.C63b`: no serving-path check.** Declared returns do not wrap handler invocation, emitted stream items, the handler's terminal result, or socket response encoding. `op!` calls the resolved handler and returns its result under the existing dispatch contract. This remains true if another feature adds an independent result transform at that boundary.

- **DELTA-Dcr-dr-001.CC3 — `SPEC-004.C63c`: render returns in full help.** The built-in `help` op adds a JSON-safe `returns` projection to one-op detail when the entry declares `:returns`. The no-argument registry projection is unchanged.

- **DELTA-Dcr-dr-001.CC4 — `SPEC-004.C63d`: validate declaration structure at registration.** `register-op!` and `replace-op!` call the shared return-shape validator. A malformed scalar, map, collection, subcommand, or stream declaration fails before the registry changes. For a subcommand arg-spec, the declared return subcommand names must match the arg-spec names exactly. For a `:stream? true` op, each return case must declare both `:emits` and `:result`; stream cases are rejected on non-stream ops.

## DELTA-Dcr-dr-001.P3 Design decisions

### DELTA-Dcr-dr-001.D1 Return declarations are registry metadata

- **Decision:** Put `:returns` beside `:arg-spec` in the existing metadata map and registry entry.
- **Rationale:** The registry already owns op discovery, replacement, provenance, and registration-time metadata validation. A second store could drift.
- **Rejected:** A separate return registry or declarations attached to handler vars outside `register-op!`.

### DELTA-Dcr-dr-001.D2 Validation stops at declaration boundaries

- **Decision:** Validate declaration data at register/replace time, not handler values during ordinary invocation.
- **Rationale:** Bad authored data should fail before publication. Per-request result checking adds serving-path work and changes runtime failure behavior for a contract whose primary enforcement belongs in tests.
- **Rejected:** Wrapping every handler or `:op/emit!` call with the evaluator.

### DELTA-Dcr-dr-001.D3 Routed and streaming cases are explicit

- **Decision:** A subcommand op declares one return case for every arg-spec subcommand. A streaming case declares emitted items separately from the terminal result.
- **Rationale:** One broad map shape would erase the useful differences between verbs, and one stream shape would leave either emitted items or the terminal result undocumented.
- **Rejected:** Shape unions, inferred subcommand routing, and treating a stream as a homogeneous collection returned by the handler.

## DELTA-Dcr-dr-001.P4 Open questions

None.
