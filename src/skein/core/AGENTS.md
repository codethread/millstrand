# skein.core

Implementation boundaries for core and anything that reaches into it:

- Keep SQL and shared persistence behavior in `skein.core.db`; strand attribute values stay JSON `TEXT` in the `attributes` table — no JSONB assumptions.
- One ambient runtime per real weaver process (SPEC-004.C8a).
