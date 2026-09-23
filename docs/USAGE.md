# readonlyleak — CLI reference

## Install / build

`readonlyleak` is not yet published; build it locally:

```bash
git clone https://github.com/jay-tank/readonlyleak.git
cd readonlyleak
mvn -q package -DskipTests
# -> target/readonlyleak.jar (self-contained shaded jar, no runtime dependencies)
```

## Usage

```
Usage: readonlyleak [options] <path...>
```

- `<path>` — one or more files or directories. A directory is walked
  recursively for `.java` files. Each file is analyzed independently
  (unlike some other tools in this series, this rule needs no
  whole-project pass — a method's effective `readOnly` value is resolved
  from its own annotation and its own enclosing class only).
- `--json` — emit findings as a single JSON object instead of plain text.
- `--help` / `-h` — print usage and exit `0`.

Exit codes:

- `0` — no findings.
- `1` — at least one `RL001` finding.
- `2` — usage error (no paths given, or a given path does not exist).

## Example

```bash
java -jar target/readonlyleak.jar src/main/java
```

```
src/main/java/com/example/users/UserProfileService.java:24: [BLOCKER] RL001 @Transactional(readOnly = true) method 'touchLastSeen' calls mutating persistence operation 'save(...)' -- readOnly = true is a HINT to the JPA provider/transaction manager, not an enforced guarantee: depending on the provider, driver, and connection pool, this mutation can silently commit anyway, silently fail to flush, or throw far from this call site.

readonlyleak: scanned 1 file(s), 1 finding(s) (RL001)
```

## Fixing a finding

Two real fixes, depending on what the method actually does:

**1. The method genuinely doesn't need to write — remove the mutation** (or
move it to a separate, correctly-annotated method/transaction):

```java
@Transactional(readOnly = true)
public User touchLastSeen(Long userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return user; // drop the write, or...
}

@Transactional
public void recordLastSeen(Long userId) {
    User user = userRepository.findById(userId).orElseThrow();
    user.setLastSeenAt(Instant.now());
    userRepository.save(user); // ...do it in its own, honestly-annotated transaction
}
```

**2. The method genuinely does need to write — the `readOnly = true` claim
was wrong, drop it:**

```java
@Transactional
public User touchLastSeen(Long userId) {
    User user = userRepository.findById(userId).orElseThrow();
    user.setLastSeenAt(Instant.now());
    return userRepository.save(user);
}
```

See [DETAILS.md](../DETAILS.md) for exactly why `readOnly = true` is a
hint rather than an enforced guarantee, the precedence rule for
method-level vs. class-level `@Transactional`, and the tool's documented
scope limits.

## JSON output shape

```json
{
  "filesScanned": 1,
  "findings": [
    {
      "ruleId": "RL001",
      "severity": "BLOCKER",
      "file": "src/main/java/com/example/users/UserProfileService.java",
      "line": 24,
      "location": "touchLastSeen",
      "message": "..."
    }
  ],
  "summary": { "blockers": 1, "failed": true }
}
```
