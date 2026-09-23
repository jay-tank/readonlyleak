# readonlyleak

A static analysis CLI that scans Java/Spring source for
**`@Transactional(readOnly = true)`** methods (method-level, or inherited
from a class-level annotation when the method has no override of its own)
whose body calls a **mutating persistence operation** — a Spring Data JPA
repository's `.save(...)`, `.saveAndFlush(...)`, `.delete(...)`,
`.deleteById(...)`, `.deleteAll(...)`, a JPA `EntityManager`'s
`.persist(...)`, `.merge(...)`, `.remove(...)`, or a `Query`/`TypedQuery`'s
`.executeUpdate()`.

`readOnly = true` is documented by Spring itself as a **hint** to the
underlying transaction manager, JPA provider, and DB driver — primarily
used for optimizations like skipping dirty-checking or routing to a read
replica. It is **not** an enforced guarantee that a mutation inside that
transaction will be rejected. Depending on the exact combination of JPA
provider (Hibernate vs. EclipseLink), DB driver, and connection pool
settings, a mutating call inside a `readOnly = true` transaction can:

- silently succeed and commit anyway, if the hint is ignored;
- silently succeed and then silently fail to actually persist, if flush is
  suppressed; or
- throw at flush/commit time with a confusing exception far from the
  actual mutating call site.

None of those outcomes is what the developer intended when they marked a
method `readOnly = true` — they were declaring "this method doesn't
write," and the code violates that contract in a way the compiler and most
tests won't catch (tests often run against an in-memory DB that ignores
the hint entirely, masking the bug until a specific production DB
configuration surfaces it). See [DETAILS.md](DETAILS.md) for the full
mechanics, the exact method-vs-class precedence rule this tool implements,
and a prior-art check.

`readonlyleak` only flags a method when it **resolves to** `readOnly =
true` — a plain `@Transactional` (readOnly defaults to `false`) is
correctly **not** flagged, since mutations are the normal, expected case
there.

## Quick start

```bash
mvn -q package -DskipTests
java -jar target/readonlyleak.jar src/main/java
```

```
src/main/java/com/example/users/UserProfileService.java:24: [BLOCKER] RL001 @Transactional(readOnly = true) method 'touchLastSeen' calls mutating persistence operation 'save(...)' -- readOnly = true is a HINT to the JPA provider/transaction manager, not an enforced guarantee: depending on the provider, driver, and connection pool, this mutation can silently commit anyway, silently fail to flush, or throw far from this call site.

readonlyleak: scanned 1 file(s), 1 finding(s) (RL001)
```

Exit code is `1` when any `RL001` finding is present, `0` otherwise — wire
it straight into CI.

## What it detects

- **`RL001`** — a method whose *effective* `@Transactional` setting is
  `readOnly = true` (its own annotation, or inherited from a class-level
  one when the method has no annotation of its own), whose body calls one
  of: `save`, `saveAndFlush`, `delete`, `deleteById`, `deleteAll`,
  `persist`, `merge`, `remove`, `executeUpdate`.

Explicitly **not** flagged:

- A plain `@Transactional` with no `readOnly` attribute (defaults to
  `false`) — mutations are the normal, expected case, no claim is being
  violated.
- A method-level `@Transactional` with **no** `readOnly` attribute, on a
  class that carries `@Transactional(readOnly = true)` — Spring's real
  precedence is that the **method-level annotation replaces the
  class-level one entirely**, including `readOnly` defaulting back to
  `false`. This is the trickiest case in the whole tool and is documented
  in detail in [DETAILS.md](DETAILS.md) — it is easy to get backwards.
- A `readOnly = true` method that only reads (`findById`, `findAll`, etc.)
  — no mutating call present.
- A non-`@Transactional` method calling `.save(...)` — out of scope, there
  is no `readOnly` claim being made at all.
- A mutating call reached only through a helper method the flagged method
  calls, rather than appearing textually inside the flagged method's own
  body — this is a per-method structural check, not interprocedural
  analysis.

See [DETAILS.md](DETAILS.md) for exactly how method-vs-class precedence is
resolved and the tool's documented scope limits (AST heuristic, no
dataflow analysis, no symbol resolution).

## Install / build

Not yet published; build it locally:

```bash
git clone https://github.com/jay-tank/readonlyleak.git
cd readonlyleak
mvn -q package -DskipTests
# -> target/readonlyleak.jar (self-contained shaded jar, no runtime dependencies)
```

See [docs/USAGE.md](docs/USAGE.md) for the full CLI reference and JSON
output shape.

## License

MIT — see [LICENSE](LICENSE).
