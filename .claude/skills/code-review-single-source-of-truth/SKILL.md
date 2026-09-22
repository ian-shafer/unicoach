---
name: code-review-single-source-of-truth
description: Reviews code to ensure every fact has exactly one home and every other site derives it from that home instead of restating it.
implementation_summary: >
  **Single source of truth**: Every piece of data has exactly one home — a database column, a config value, or the return of a function — and every other site DERIVES it from that home rather than restating it. Look for the home before you create one: a new constant, column, literal or copied sentence that states a fact the system already holds is a violation regardless of intent, and it is a violation EVEN WHEN IT IS CURRENTLY CORRECT, because the property at issue is agreement over time, not correctness today. Store data in its most normalized and pure form — a CIP code, not a field-of-study name; an identifier, not its label — and derive the label at the boundary where it is shown. The fix is to READ THE SOURCE, not to extract a helper; if the home cannot be reached from the site, the unreachability IS the defect, so thread it or move the site — and when two runtimes each apply the value and neither can read the other, its home is the shell environment, per `CONFIGURATION.md`'s prime directive, unless carrying it there costs more than the two values disagreeing would: judge by what the disagreement DOES, let it stay duplicated (with the reason stated) when the damage is cosmetic or merely displayed and the carriage is genuinely hard, and never duplicate a value whose drift can SPEND money — paid capacity, billed calls, stored data, or a repair somebody funds. Does not apply to a test's expected value, a deliberate pin against an immutable record, a derived store with a stated reason that is rebuilt wholesale, verbatim quotation of a binding text, or a cross-runtime duplicate whose removal costs more than its drift would.
---

# 🔍 Code Review: Single Source of Truth

Origin: RFC 177 (a publisher's name hand-typed in chat and on the report page
while `CanonicalMoneyLoader.ORDERED_SOURCES` decided the real one, the winning
`MoneySource` read from the store and dropped two lines later), RFC 192 (a tool
description hand-typing English glosses for `AssuranceTier` enum tokens), RFC
192/199 (one behavioural rule written into both the coach prompt and a tool
description, drifting apart with nothing to catch it), RFC 203 (five numeric
test ceilings asserting a constant every change edited: 1,767 → 1,530 → 1,450 →
1,401 → 1,404, the last step upward), and the `max_subjects` / `MAX_SUBJECTS`
pair found in `main` on 2026-09-21 -- one cap declared in a PL/pgSQL trigger and
again in Kotlin, with neither side able to read the other -- which showed this
rule's remedy offering no fix a runtime boundary can take. Ian's framing: "there
should be a single source of truth, and only that single source of truth should
be trusted."

You are a ruthless code reviewer focusing strictly on identifying violations of
the following principle. Do not review for other concerns outside this scope.

## 📜 Review Criteria

- Every piece of data has one source of truth: a database column, a config
  value, or the return of a function. It has exactly one home, and every other
  site DERIVES it from that home rather than restating it.
- Look for the home before you create one. Most duplicates are not decisions;
  they are the result of a search nobody ran. A new constant, column or literal
  that states a fact the system already holds is a violation regardless of
  intent.
- A second statement of a fact is a violation EVEN WHEN IT IS CURRENTLY CORRECT.
  Correctness today is not the property at issue; agreement over time is. Two
  places that must agree, with nothing that makes them agree, will disagree.
- Store data in its most normalized and pure form. Prefer the real value over a
  rendering of it -- a CIP code, not a field-of-study name; an identifier, not
  its label. The label has its own home; derive it at the boundary where it is
  shown.
- The fix is to READ THE SOURCE, not to extract a helper. If the home cannot be
  reached from the site, the unreachability IS the defect: thread it, or move
  the site.
- **Across a runtime boundary, the home is the environment.** A value that two
  runtimes each apply -- one stated in the database and also in the application,
  one set in a stylesheet and also computed in code -- can be neither threaded
  nor moved: neither runtime can read the other's declaration. The repo has
  already decided where such a value lives. `CONFIGURATION.md` states it as a
  prime directive -- a value needed by one runtime AND any other consumer is set
  in the shell environment files -- and its invariant 4 is this principle under
  another name: each value has exactly one home, because two homes drift. Read
  that file for how the value reaches each consumer. Do not restate the
  mechanism in a finding, and do not invent a second route for it.
- **The yield is a calculation with two sides.** Weigh what carrying the value
  through the environment costs -- a key in each committed environment file, a
  value to pass at startup, a deploy path, a fixture -- against what the two
  values DISAGREEING costs. Not against how likely drift is; against what it
  does when it happens. Where disagreement moves a label, a spacing, a log line,
  a wording -- or a number a user reads, which is weighed like everything else
  and is not automatically grave -- the plumbing may well cost more, the second
  statement stays, and this rule does NOT fire. State both sides out loud.
  Naming the duplicate while saying nothing about the cost of removing it, or
  nothing about the cost of it drifting, is the finding this clause exists to
  stop.
- **SPEND ends the calculation.** One side never yields: disagreement that costs
  the OWNER money. A bound that sizes paid infrastructure, a quota or rate that
  is billed per call, a retention window that pays for storage, a schedule that
  runs paid work -- and the same for a disagreement that forces paid recovery,
  an outage, a restore, or a repair of data written wrong. There is no amount of
  plumbing that justifies the duplicate there: carry the value through the
  environment whatever it costs. Do not weigh this one; the answer is fixed
  before the calculation starts.

## ✅ Where this rule does not fire

Five cases restate a fact on purpose. None of them is a violation.

1. **A test's expected value.** `assertEquals("in-state", tier.label)` restates
   the label deliberately; deriving it from the subject makes the assertion
   tautological. A test must not derive its expectation from its subject.
2. **A deliberate pin against an immutable record.** This repo's nine
   byte-frozen sentences are duplicated into an immutable prompt row so that
   drift FAILS A BUILD. There the duplication is the detection mechanism.
3. **A derived store with a stated reason and a wholesale rebuild.** See
   `db/schema/0064.create-search-index-and-subjects.sql` —
   `college_search_index` duplicates thirty columns legitimately: each earns its
   place by being FILTERED, SORTED or INDEXED on; it is rebuilt wholesale, never
   hand-edited; everything below that bar is read back from the source.
   Denormalisation is a decision; it is a violation only when nothing keeps it
   fresh or a human can write to it.
4. **Verbatim quotation of a binding text.** Quoting a decision's exact words is
   correct. The violation is restating a DERIVED fact (a count, a size, a line
   number); repeating an AUTHORED one is how it stays binding.
5. **A second statement whose removal costs more than its drift would.** Two
   runtimes that each apply the same value share one home only through the
   environment. When that carriage is larger than the damage disagreement would
   do -- a label, a spacing, a log line, and sometimes a displayed number -- the
   duplicate is a decision, not an oversight, and like case 3 it is a decision
   only when it is STATED. An unremarked copy is still a copy. The exemption
   stops where drift spends money.

Note what separates 3 from a violation: a mechanism that keeps the copy fresh.
Absent that mechanism, a copy is a copy.

## 🎯 Review Guidelines

- **Adversarial Posture:** For every literal, constant, column and copied
  sentence in the diff, ask "does this fact already have a home, and is this
  site reading it?" Grep the diff for hand-typed labels beside the enum,
  `when`-block or table that defines them; for prose duplicated between a prompt
  and a tool description; for numeric thresholds asserted in tests that a
  production constant already states; and for a name or gloss written next to
  the identifier it renders. Do not accept "it is correct" as an answer — ask
  what makes it STAY correct. A value read from the store and then dropped in
  favour of a hard-coded one is the strongest tell.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: A hand-typed label beside the enum that defines it

#### ❌ Negative Example (a second home for the display name)

```kotlin
enum class AssuranceTier(val value: String, val label: String) {
  VERIFIED("verified", "Verified"),
  MODELLED("modelled", "Modelled estimate"),
  UNKNOWN("unknown", "Not published"),
}

// VIOLATION: every label is stated a SECOND time, here. It is correct today and
// nothing keeps it correct: add a tier, or reword one label, and this `when`
// silently disagrees with the enum that owns the fact.
fun describe(tier: AssuranceTier): String =
  when (tier) {
    AssuranceTier.VERIFIED -> "Verified"
    AssuranceTier.MODELLED -> "Modelled estimate"
    AssuranceTier.UNKNOWN -> "Not published"
  }
```

#### ✅ Positive Example (read the source)

```kotlin
// ADHERES TO RULE: the label has one home — the enum constant — and this site
// DERIVES it. A new tier or a reworded label cannot make the two disagree,
// because there is only one of them.
fun describe(tier: AssuranceTier): String = tier.label
```

### Example 2: A fact read from the store and then dropped for a literal

#### ❌ Negative Example (the answer was in hand and was thrown away)

```kotlin
// VIOLATION: `winning.source` IS the publisher the loader chose. It is read and
// discarded two lines later in favour of a name typed by hand, so the page can
// state a publisher the loader no longer picks. The hard-coded string is a
// second home for a fact the store already owns.
fun renderNetPrice(collegeId: CollegeId): String {
  val winning = moneyStore.canonical(collegeId)
  return "Net price ${winning.amount} (source: IPEDS)"
}
```

#### ✅ Positive Example (carry the real value to the boundary)

```kotlin
// ADHERES TO RULE: the value that decided the number also names its publisher.
// Nothing restates it, so nothing can drift from it. Note the fix is to READ
// THE SOURCE, not to extract a `publisherLabel()` helper: a helper that returns
// a literal is still a second home.
fun renderNetPrice(collegeId: CollegeId): String {
  val winning = moneyStore.canonical(collegeId)
  return "Net price ${winning.amount} (source: ${winning.source.label})"
}
```

### Example 3: A derived figure restated as a constant

#### ❌ Negative Example (a threshold with two homes)

```kotlin
// VIOLATION: the budget is a fact owned by the production constant. Restating
// the number here means every change to it edits this line too — and the value
// walked 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404 while the test pretended to
// pin it. A test's EXPECTED value is exempt; a test restating a value that
// PRODUCTION already owns is not.
@Test
fun `payload stays within budget`() {
  assertTrue(buildPayload().length <= 1_401)
}
```

#### ✅ Positive Example (assert against the source)

```kotlin
// ADHERES TO RULE: the budget has one home. The test asserts the relationship
// it actually cares about — the payload fits the budget — and cannot go stale
// when the budget moves.
@Test
fun `payload stays within budget`() {
  assertTrue(buildPayload().length <= PayloadBudget.MAX_BYTES)
}
```

### Example 4: One value, two runtimes, and drift that spends money

#### ❌ Negative Example (a retention window with a home in each runtime)

```sql
-- db/schema/00NN.prune-uploads.sql
CREATE OR REPLACE FUNCTION prune_expired_uploads() RETURNS void AS $$
DECLARE
    -- VIOLATION: the window is stated here, and again in the job below. The
    -- two runtimes cannot read each other, so nothing holds them equal.
    retention_days CONSTANT INTEGER := 30;
BEGIN
    DELETE FROM uploads WHERE created_at < now() - (retention_days || ' days')::interval;
END
$$ LANGUAGE plpgsql;
```

```kotlin
// VIOLATION: the second home. Raise one side only and the rows leave the table
// while the objects they name stay in paid storage for ever — or the objects
// go and the rows point at nothing. This drift SPENDS money, so the size of
// the plumbing does not enter into it.
object UploadPruneJob {
  const val RETENTION_DAYS: Int = 30
}
```

#### ✅ Positive Example (one home in the environment, read by both)

```kotlin
// ADHERES TO RULE: the window is declared once as an environment key and
// reaches the JVM through config, the route `CONFIGURATION.md` prescribes.
// Nothing here states the number.
class UploadPruneJob(config: Config) {
  private val retentionDays: Int = config.getInt("uploads.retentionDays")
}
```

```sql
-- ADHERES TO RULE: the same key, carried into this runtime by the process that
-- runs the statement. One home, two readers; raising it is one edit.
DECLARE
    retention_days CONSTANT INTEGER := current_setting('unicoach.uploads_retention_days')::int;
```

### Example 5: The weighed duplicate — stated, or it is just a copy

#### ❌ Negative Example (two homes, and neither mentions the other)

```sql
-- db/schema/intended-subjects.sql
-- VIOLATION as written: the cap is real in two runtimes and this side names
-- neither the other side nor the reason it is stated twice.
max_subjects CONSTANT INTEGER := 3;
```

```kotlin
// VIOLATION as written: the same number, a second time. A one-sided edit makes
// the trigger and the `require` refuse at different counts, silently.
const val MAX_SUBJECTS: Int = 3
```

#### ✅ Positive Example (the same two statements, with the decision on both)

```kotlin
// ADHERES TO RULE: the duplicate is a DECISION. The cap is also declared in
// `db/schema/intended-subjects.sql` as `max_subjects`, because a deferred
// trigger cannot read this constant and this constant cannot be moved into the
// trigger — it is what turns the common case into a 400 the student can act
// on. Carrying it through the environment would add a key to every committed
// environment file and a setting to every session that migrates or writes,
// against a drift that costs a late constraint error instead of a clean
// refusal. No money moves. The cap is stated twice, on purpose, and each side
// names the other.
const val MAX_SUBJECTS: Int = 3
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
