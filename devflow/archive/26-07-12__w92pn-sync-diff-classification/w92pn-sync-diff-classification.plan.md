# w92pn sync diff classification plan

## Steps

1. Add per-process generation state to the weaver runtime.
2. Keep a generation baseline of successfully synced roots and source fingerprints separate from the public last-sync result.
3. Classify new sync universes before classloader mutation:
    - allow additive roots;
    - refuse removed roots, changed roots, and changed loaded source;
    - record the refused diff as a pending generation.
4. Report retained spool-state entries tagged with older generations in sync results.
5. Cover root removal, same-length source redefinition, retained spool-state reporting, and reload interactions in `skein.spools-test`.
6. Run the full validation gate and commit the result.
