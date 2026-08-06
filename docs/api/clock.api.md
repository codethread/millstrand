
-----
# <a name="millstrand.api.clock.alpha">millstrand.api.clock.alpha</a>


Clock capability for runtime-owned time and sleeping.




## <a name="millstrand.api.clock.alpha/clock">`clock`</a>
``` clojure
(clock now-fn sleep-fn)
```
Function.

Build a Clock capability from `now-fn` and `sleep-fn`.

  `now-fn` is a zero-arg fn returning the current `java.time.Instant`; `sleep-fn`
  takes a non-negative `java.time.Duration` and waits or advances by it. Fails
  loudly unless both are functions.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/clock/alpha.clj#L29-L40">Source</a></sub></p>

## <a name="millstrand.api.clock.alpha/clock?">`clock?`</a>
``` clojure
(clock? x)
```
Function.

Return true when `x` is a Clock capability.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/clock/alpha.clj#L24-L27">Source</a></sub></p>

## <a name="millstrand.api.clock.alpha/now">`now`</a>
``` clojure
(now clock)
```
Function.

Return `clock`'s current `java.time.Instant`.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/clock/alpha.clj#L42-L45">Source</a></sub></p>

## <a name="millstrand.api.clock.alpha/sleep!">`sleep!`</a>
``` clojure
(sleep! clock duration)
```
Function.

Wait or advance `clock` by a non-negative `java.time.Duration`, then return nil.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/clock/alpha.clj#L47-L51">Source</a></sub></p>

## <a name="millstrand.api.clock.alpha/system-clock">`system-clock`</a>
``` clojure
(system-clock)
```
Function.

Return the shared clock backed by the system wall clock and thread sleep.
<p><sub><a href="https://github.com/codethread/millstrand/blob/main/src/millstrand/api/clock/alpha.clj#L53-L56">Source</a></sub></p>
