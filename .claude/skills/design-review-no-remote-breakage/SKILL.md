---
name: design-review-no-remote-breakage
description: Reviews changes to ensure nothing in them can be silently broken by a change elsewhere — every dependency on something another artifact owns is derived, enforced, or referenced, and the rare unavoidable coupling is a considered, documented decision. Applies to code, configuration, and documentation alike.
implementation_summary: >
  **No Remote Breakage**: Do not write anything that a change elsewhere can silently break. Statically compiled code is the model: rename a function and every caller fails the build — breakage there is impossible to miss, so nobody worries about it. Seek guarantees of that strength everywhere else. Any dependency on something another artifact owns — a constant, count, default, list of members, format, port, path, ordering, behavior — must be DERIVED from the owner (import, compute, generate), ENFORCED (a test or build check that fails loudly on divergence), or REFERENCED without restating it. This is a strong default, not a strict invariant: unenforced coupling is occasionally unavoidable — then it must be a considered decision, documented at the coupling site naming what it depends on and what breaks (and, if the guarantee is durable and critical, recorded in the directory's INVARIANTS.md). Applies to every artifact in the repository — code, config, comments, docs.
---

# 🔍 Design Review: No Remote Breakage

You are a ruthless design reviewer focusing strictly on **remote fragility** —
places where a change to one artifact would silently break another, because the
second depends on something the first owns and nothing forces them to stay in
agreement.

## 📜 Review Criteria

### Key Definitions

- **Remote dependency**: anything an artifact relies on that another artifact
  owns — a constant, a count, a default value, the members of a list/enum, a
  wire format, a port, a path, an ordering, an incidental behavior.
- **The owner**: the single artifact where that thing authoritatively lives (the
  enum itself, the config schema, the referenced document, the migration, the
  generator input).
- **Silent breakage**: the failure mode where the owner changes and the
  dependent does not — with no compile error, test failure, or grep-able link to
  force the update. The dependent is now wrong, and nobody knows.

### 1. The Rule

Do not write anything that a change elsewhere can silently break.

**Statically compiled code is the model.** Rename a function and every caller
fails the build; change a type and every mismatched use is a compile error.
Nobody worries about remote breakage there, because it cannot happen silently —
the guarantee is structural. This lens asks for guarantees of that strength for
everything the compiler does not see: config, docs, comments, cross-service
values, parallel lists.

For any remote dependency, three postures approximate the compiler's guarantee,
in order of preference:

1. **Derive** — import the constant, compute the count, generate the artifact
   from the owner (as `bin/compile-skills.py` generates the baseline skills from
   the micro-skills).
2. **Enforce** — keep the copy, but add a check that fails loudly on divergence
   (a test asserting the two agree, a build-time assertion, an exhaustive
   `when`).
3. **Reference** — point at the owner without restating its content ("see
   `INVARIANTS.md`", not "the one invariant in `INVARIANTS.md`").

### 2. The Escape Hatch (this is a strong default, not a strict invariant)

Sometimes unenforced coupling is genuinely unavoidable — no shared source to
derive from, no seam to test across. Then it must be a **considered decision,
made visible**: document it at the coupling site (a comment naming the owner it
depends on and what breaks when the owner changes), and if the guarantee is
durable and critical, record it in the directory's `INVARIANTS.md`. What this
review rejects is not the coupling itself but the **undocumented, unconsidered**
copy — fragility nobody chose.

### 3. What This Catches

- A constant or default duplicated across modules or services instead of
  imported/derived from one place.
- Parallel lists that must stay in step (an enum here, a `when`/switch there, a
  doc table somewhere else) with no exhaustiveness check or generation.
- A comment or document restating a fact its link already owns — above all
  **counts and enumerations of another file's content** ("the three supported
  modes", "both files", "the directory's one invariant").
- Configuration in one component embedding a value another component owns
  (another service's port, host, or path) rather than deriving it from the
  shared source.
- A test hardcoding a duplicate of a production registry/list rather than
  reading it from the production source (the test must fail when the registry
  changes, not silently keep passing).
- Code that depends on an incidental remote behavior (an ordering, a side
  effect, a file location) that the owner never promised and may change.

### 4. What This Does Not Catch

- Duplicated _logic_ (that is `design-review-common-abstraction` / DRY
  territory). This lens is about unprotected dependencies, not repeated code.
- Facts restated inside the owner itself (a doc summarizing its own sections is
  fine — it updates atomically with them).
- Documented, considered couplings per the escape hatch above — verify the
  documentation exists and names the owner; do not flag the coupling again.

---

## 💻 Examples

### ❌ BAD: silently breakable by a change elsewhere

```markdown
<!-- README.md -->

This is the directory's one invariant — see INVARIANTS.md.
```

The count "one" is owned by `INVARIANTS.md`. The moment a second invariant lands
there, this sentence is silently wrong.

```kotlin
// EmailConfig.kt — the public-web port is owned by public-web's config
val verificationBaseUrl = "http://localhost:8083/verify"
```

`8083` is owned by another module. When public-web's port changes, emails break
and nothing fails at build or test time.

### ✅ GOOD: derive, enforce, reference — or document the unavoidable coupling

```markdown
<!-- README.md -->

RFCs are immutable after their initial commit — see INVARIANTS.md.
```

```kotlin
// EmailConfig.kt — derive from the owner
val verificationBaseUrl = "${publicWebConfig.baseUrl}/verify"
```

```kotlin
// Enforce: a divergence between the wire enum and the docs table fails a test
@Test
fun `error-code table in docs matches ErrorCode enum`() { … }
```

```bash
# Documented unavoidable coupling: mirrors the SES sandbox recipient limit,
# which AWS owns and exposes through no API we can read at build time. If AWS
# changes the limit, this constant is what breaks.
MAX_SANDBOX_RECIPIENTS=50
```
