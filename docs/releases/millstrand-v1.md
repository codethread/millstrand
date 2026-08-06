# Millstrand v1

This is the first published Millstrand marker. The project is alpha software, and the rename is an intentional identity break. The release publishes no compatibility aliases.

The canonical repository is `codethread/millstrand`. The Clojure dependency coordinate is `io.millstrand/millstrand`.

The annotated marker is `v1`. Consumers pin the peeled commit reported by `git ls-remote`:

```clojure
{:deps {io.millstrand/millstrand
        {:git/url "https://github.com/codethread/millstrand.git"
         :git/tag "v1"
         :git/sha "<peeled-commit-sha>"}}}
```

For local sibling development only:

```clojure
{:deps {io.millstrand/millstrand {:local/root "../millstrand"}}}
```

Verify a landed candidate before tagging:

```sh
scripts/verify-published-core.sh --mode pre-tag \
  --source-root "$PWD" \
  --coordinate io.millstrand/millstrand \
  --repository https://github.com/codethread/millstrand.git \
  --candidate-tag v1
```

After the tag is pushed, repeat the proof with its literal peeled commit:

```sh
scripts/verify-published-core.sh --mode published \
  --coordinate io.millstrand/millstrand \
  --repository https://github.com/codethread/millstrand.git \
  --tag v1 --sha <peeled-commit-sha>
```

This release does not promise compatibility with the former product identity. Rollback means returning a consumer to its previous sha-pinned family entry; it does not restore an alias in this repository.
