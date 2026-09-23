# readonlyleak — Design Details

## The gap: why this needs to exist

Spring's `@Transactional(readOnly = true)` reads like a promise the
framework enforces. It isn't one.

### What `readOnly = true` actually is: a hint, not a guard rail

Spring's own reference documentation is explicit about this, in the
"Transaction Management" chapter: `readOnly` is a **hint** passed down to
the underlying resources — the transaction manager, the JPA provider
(Hibernate, EclipseLink, ...), and ultimately the JDBC driver and
connection pool. What each layer *does* with that hint varies:

- **Hibernate**, when `readOnly` is propagated correctly, sets the
  `Session` to `FlushMode.MANUAL` and marks loaded entities read-only —
  which mainly means dirty-checking is skipped for performance, not that
  a write call is rejected. An explicit `.save(...)`/`.persist(...)`/
  `.merge(...)`/`.remove(...)` call, or an explicit `flush()`, can still
  go through depending on the exact Hibernate version and session state.
- **Some JDBC drivers/connection pools** honor `readOnly` more literally
  and may throw at the point of an actual write (e.g. `Connection.
  setReadOnly(true)` plus a strict driver) — but this is
  driver/configuration-dependent, not guaranteed across the ecosystem.
- **Some setups route `readOnly = true` transactions to a read replica**
  (a common pattern with `AbstractRoutingDataSource` or a
  read/write-split connection pool) — where a mutating call doesn't even
  reach the primary database, and may fail with a permissions error, or
  succeed against the replica and then vanish on the next read (since the
  replica's own write was never meant to be durable), or silently no-op
  depending on the replica's own configuration.

There is no single, ecosystem-wide answer to "what happens when you call
`.save(...)` inside a `readOnly = true` transaction." That is precisely
the problem: the actual behavior is a property of your specific
production stack, not of the code itself — so the same source line can be
harmless in a developer's local Postgres, silently wrong in staging, and
outright break in production, with nothing in the code that changed.

### Why tests don't catch it

Most test suites run against an in-memory database (H2, HSQLDB) or a
simplified local Postgres/MySQL container with default driver settings —
configurations that are far more likely to silently ignore the `readOnly`
hint entirely than a tuned, pooled production setup is. That means the
exact code that trips this bug can pass every test in CI, pass code
review (nothing about `orderRepository.save(order)` looks wrong on its
own), and only surface once it runs against the specific combination of
JPA provider version, connection pool, and possibly a read-replica router
that a given production environment happens to use.

### Why this is a routine mistake, not a rare one

`readOnly = true` is exactly the annotation a developer reaches for on a
method that's *mostly* reads, as a well-intentioned performance
optimization — skip dirty-checking, maybe get routed to a replica. The
mistake that creates this gap is almost always incidental, not a
misunderstanding of `readOnly` itself: a "touch a last-seen timestamp"
call tucked at the end of an otherwise read-only method, an audit-log
`persist()` added later by someone who didn't notice the enclosing method
(or its class) was marked read-only, or a class-level `readOnly = true`
that was correct when the class was mostly queries and became wrong once
one write-shaped method was added without anyone re-auditing every
sibling method in that class.

## The precedence rule this tool implements (read this carefully — it is easy to get backwards)

`@Transactional` can appear at the class level, the method level, or
both. Spring's real precedence rule, stated plainly:

> **A method-level `@Transactional` annotation, if present at all,
> completely replaces the class-level one for that method — it does not
> merge attribute-by-attribute with it.**

This means: if a class carries `@Transactional(readOnly = true)` and one
of its methods has its own `@Transactional` with **no `readOnly`
attribute specified**, that method's `readOnly` is **not** "inherited as
`true` from the class" — it resets to `readOnly`'s own default, `false`,
exactly as if the class-level annotation didn't exist for that method at
all. The class-level annotation is consulted **only** when the method has
**no** `@Transactional` annotation of its own.

```java
@Transactional(readOnly = true)
class ReportMaintenanceService {

    // No method-level @Transactional at all -> INHERITS readOnly = true
    // from the class. A mutating call here IS flagged.
    void archiveViaClassLevel(Report report) {
        reportRepository.save(report); // RL001
    }

    // Has ITS OWN @Transactional, with no readOnly attribute -> this
    // REPLACES the class-level annotation entirely for this method.
    // readOnly resolves to false (its own default), NOT true. A mutating
    // call here is correctly NOT flagged.
    @Transactional
    void archive(Report report) {
        reportRepository.save(report); // not flagged
    }
}
```

It is tempting — and wrong — to assume `readOnly = true` on the class
"wins" or "inherits down" onto a method-level `@Transactional` that
doesn't explicitly say otherwise. It does not. This tool's resolution
order mirrors Spring's exactly:

1. If the method has its own `@Transactional` annotation (of any shape —
   marker, single-member, or normal with attributes), that annotation
   alone determines `readOnly` for this method. Its own `readOnly`
   attribute is used if present; otherwise `readOnly` is `false` (the
   default), **regardless of what the class-level annotation says**.
2. Only when the method has **no** `@Transactional` annotation at all is
   the class-level annotation consulted, with the same "explicit
   attribute or default `false`" logic.
3. If neither the method nor the class carries `@Transactional`, the
   method is not transactional at all as far as this tool is concerned —
   out of scope, not flagged, regardless of what it calls.

## Prior-art check (done before building this)

Searched for existing tooling that flags a mutating persistence call
specifically inside a method that resolves to `@Transactional(readOnly =
true)`:

- **Generic SAST platforms** (Semgrep registry, SonarQube/SonarJava,
  CodeQL's Java query packs, PMD, Error Prone, SpotBugs) — no rule found
  that correlates `@Transactional(readOnly = ...)`'s resolved value
  against the set of persistence-mutating calls a method's body makes.
  SonarJava has a rule family around `@Transactional` method visibility
  and self-invocation (the "calling a `@Transactional` method from within
  the same class bypasses the proxy" gotcha), which is a real, different
  gap this tool does not attempt to cover — but nothing that looks at
  `readOnly` specifically.
- **Spring's own tooling** — no IDE inspection (IntelliJ's Spring
  plugin included) or Spring Boot build-time check flags a mutating call
  inside a `readOnly = true` method. IntelliJ's Spring support will
  highlight some `@Transactional` self-invocation issues but not this one.
- **Spring's own reference documentation** states the "hint, not a
  guarantee" nature of `readOnly` plainly in prose (the Core "Declarative
  transaction management" chapter) — but documenting the footgun and
  automatically checking whether a codebase stepped in it are two
  different things. Nothing found closes that loop.
- **Hibernate's own documentation and issue tracker** has extensive prior
  discussion of exactly this ambiguity (whether `FlushMode.MANUAL` under
  `readOnly = true` actually prevents a given write, across versions) —
  confirming this is a real, recognized, version/configuration-dependent
  gray area in practice, not a hypothetical concern.

Conclusion: a real, narrow, currently unfilled static-analysis gap between
"documented Spring caveat" and "checked automatically in CI." `readonlyleak`
fills it for Java/Spring, v0.1.

## Architecture

One AST pass (JavaParser, matching this tool series' approach across the
repo — never compiles or executes scanned code) per file:

1. For every `ClassOrInterfaceDeclaration` in the file, the class-level
   `@Transactional` annotation (if any) is read once.
2. For every method declared directly on that class (`getMethods()` —
   deliberately not `findAll(MethodDeclaration.class)` at the class level,
   so a nested/inner class's own methods are attributed to *their own*
   enclosing class, not this outer one, when the outer `findAll` loop
   over `ClassOrInterfaceDeclaration` reaches the inner class separately):
   - Skip it if it has no body (interface method, abstract method).
   - Resolve its effective `readOnly` value per the precedence rule above
     (`resolveEffectiveReadOnly` in `ReadOnlyLeakAnalyzer`).
   - Skip it if the effective value is `null` (not transactional at all)
     or `false` (a real, unflagged `readOnly = true` claim isn't being
     made).
   - Otherwise, search the method body (`findAll(MethodCallExpr.class)`,
     which recurses into nested blocks, `if`/`for`/lambdas, etc.) for any
     call whose simple method name is in the mutating-call set: `save`,
     `saveAndFlush`, `delete`, `deleteById`, `deleteAll`, `persist`,
     `merge`, `remove`, `executeUpdate`. Each match becomes an `RL001`
     finding at that call's own line.

`readOnly` attribute extraction (`readOnlyValue`): only a literal
`true`/`false` (`BooleanLiteralExpr`) is resolved; a marker annotation
(`@Transactional`) or a single-member annotation (`@Transactional("txMgr")`,
where the lone value is something other than `readOnly`, e.g. a
transaction manager qualifier) has no `readOnly` attribute to find at all
and resolves to the default, `false`. A `readOnly` attribute set to a
non-literal expression (e.g. a `static final boolean` constant reference)
cannot be resolved without symbol resolution and is treated as `false`
rather than risking a false positive from a value this tool cannot
actually read.

## What this tool does not do (explicit scope limits)

- **No symbol resolution / no compile classpath.** Mutating calls are
  matched by simple method name as written in source
  (`call.getNameAsString()`), not by resolving the call's receiver type
  to confirm it's actually a Spring Data repository, an `EntityManager`,
  or a `Query`/`TypedQuery`. A method that happens to share one of these
  names on an unrelated type (for example, a domain object with its own
  `remove(...)` method, or a `java.util.List.remove(...)` call inside a
  `readOnly = true` method) would be misclassified as a finding. This is
  a deliberate trade-off consistent with the rest of this tool series —
  no compile classpath is available at scan time — but it is a real,
  documented source of possible false positives for this specific rule,
  more so than most of this series' other rules, because `save`/`delete`/
  `remove`/`merge` are common English verbs that show up as method names
  on non-persistence types too. Judged acceptable for a v0.1 CLI given how
  distinctive the *combination* of `@Transactional(readOnly = true)` and
  one of these specific call names is in practice — but worth knowing
  about before trusting a large finding count blindly.
- **No interprocedural / cross-method analysis.** A mutating call is only
  detected when it appears textually inside the flagged method's own
  body. A `readOnly = true` method that calls a private helper method
  which itself performs the mutation is **not** flagged — verifying that
  reliably would require real call-graph/interprocedural analysis, out of
  scope for a v0.1 AST tool. This is a deliberately false-negative-leaning
  choice, consistent with this tool series' stated preference for
  under-flagging over eroding trust with false positives.
- **No detection of self-invocation bypassing the transactional proxy.**
  Spring's `@Transactional` (like `@Async`) only takes effect through a
  proxy — calling a `@Transactional` method from another method in the
  *same* class bypasses the proxy entirely, meaning the annotation has no
  effect at all in that call path. This tool does not check for that; it
  resolves and flags based on the annotation as written, regardless of
  whether the proxy would actually apply on every call path. Documented
  here as a known, deliberate choice, matching the scope this tool
  targets (the readOnly-vs-mutation correlation) rather than every
  `@Transactional` footgun in the ecosystem.
- **Does not verify `@EnableTransactionManagement`/a configured
  `PlatformTransactionManager` is actually present.** Like this series'
  other Spring-annotation tools, `readonlyleak` flags based on the
  annotation as written and does not check whether transaction management
  is actually wired up in the scanned path — under-flagging here would
  silently miss real cases where the wiring lives in a file or
  auto-configuration class outside the scanned path.
