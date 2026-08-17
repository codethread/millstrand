# Land sign-off input discoverability

## Problem

The land workflow exposes checkpoint choice names through `land next` and `land status`, but not the choices' required JSON input declarations. The requirements exist in help and failed-choice errors, so coordinators often discover them only after an invalid sign-off attempt.

## Scope

- Keep the generic workflow step view compact and unchanged.
- Enrich land checkpoint views with the canonical workflow choice details.
- Surface the details on every land result carrying the checkpoint in `:ready`, including the first result that reaches sign-off.
- Keep non-checkpoint ready views unchanged.
- Add focused end-to-end coverage.

Oracle council `dfsp4` recommended this land-specific join unanimously.
