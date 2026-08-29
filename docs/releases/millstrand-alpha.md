# Millstrand alpha SHA release

Millstrand is alpha software. This release publishes the renamed core by immutable Git commit only. It has no Millstrand-managed spool release identity.

The canonical repository is `codethread/millstrand`. The Clojure dependency coordinate is `io.millstrand/millstrand`.

Consumers pin the full commit SHA:

```clojure
{:deps {io.millstrand/millstrand
        {:git/url "https://github.com/codethread/millstrand.git"
         :git/sha "<40-hex-commit-sha>"}}}
```

For local sibling development only:

```clojure
{:deps {io.millstrand/millstrand {:local/root "../millstrand"}}}
```

Candidate proof checks a clean temporary consumer against the landed checkout. It does not claim that the remote commit resolves:

```sh
scripts/verify-published-core.sh --mode candidate \
  --source-root "$PWD" \
  --coordinate io.millstrand/millstrand \
  --repository https://github.com/codethread/millstrand.git
```

After the commit is pushed to canonical `main`, repeat the proof against its literal SHA:

```sh
scripts/verify-published-core.sh --mode published \
  --coordinate io.millstrand/millstrand \
  --repository https://github.com/codethread/millstrand.git \
  --sha <40-hex-commit-sha>
```

The verifier waits up to 30 seconds for the supervisor readiness marker. Set `MILLSTRAND_VERIFY_TIMEOUT_SECONDS` to a larger positive value on a slow host, up to 600 seconds.

This SHA pin makes no compatibility promise. Rollback means returning a consumer to its previous immutable SHA. The repository publishes no alias for the former product identity.
