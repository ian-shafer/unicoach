# RFC 261 — The money plan

**Slice:** `family/07/the-money-plan` (brief 0014, _parents in the room_).
**Status:** proposed. **Base:** `main@47edda64`. **Lane:** rfc.

## Executive Summary

A parent's coach writes a four-year money plan for the student's college list,
and a conversation for the parent to have with the student this week.

The plan's numbers are not the model's. One year's price for each school already
exists in the cost picture that the parent's coach reads (RFC 258,
`family_cost_picture`). Nothing today turns that into a total for all the years.
If the model did the multiplying, it would be the free-association about numbers
that the slice forbids. So a new read-only parent tool, `family_money_plan`,
does the arithmetic on the server. For each four-year school on the list it
gives two all-years totals. Each total is labelled as **derived** and sits
beside the **published** one-year figure it came from, with that figure's year
and source. Where the data cannot support a total, the tool says why in words
and gives no number.

The parent coach's prompt moves to `parent-coach` v2. It tells the coach to use
the tool and how to lay out the plan, and it scripts the week's conversation
around three topics:

- what all the years cost;
- what graduates of the student's field earned;
- the money that never has to be paid back.

The iPhone thread gets a second starter, "Write our four-year money plan".

**No new table.** "Retrievable afterwards" is the conversation. A parent's
conversations already persist, are named after their first message, and reopen
from the Coach screen (RFC 258). Asking again re-derives the plan from that
day's figures, which a stored plan could not do.

## Detailed Design

### 1. What the code already gives, and what it does not

- **One year's price, per school.** `CollegeCost.chosen: ChosenLivingPlan`
  (`CostBreakdown.kt:725`). `Priced` carries `totalPerYearUsd`: the published
  price for the way the family plans to live, at
  `CollegeCost.publishedPriceAcademicYear`. `NotPricedHere` and `NoTotalHere`
  carry the reason there is no total. `NotChosen` means the family has said
  nothing, and then `CollegeCost.breakdown` carries every arrangement the school
  prices.
- **One year's price after grants.** `CollegeCost.netPrice: NetPrice`. RFC 157
  withholding is already applied. It is `BandSpecific`, `OverallAverage` or
  `Withheld`, at `blendedAverageAcademicYear`.
- **No program length.** Nothing on the cost path says whether a school is
  four-year. The fact lives in `college_ipeds.inst_level`. Search reads it
  through `INST_LEVEL_FOUR_OR_MORE_YEARS` (`CollegesDao.kt:2520`).
- **No list total, and no grant subtraction.** Nothing today adds schools
  together. RFC 149 D-F forbids comparing the blended net price with an
  arrangement total, or substituting one for the other.

### 2. `family_money_plan`, a subject-scoped read

`FamilyMoneyPlanChatTool : SubjectScopedChatTool` lives in `ed.unicoach.family`.
It takes no argument: the subject is the conversation's and is injected by the
dispatcher (brief 0014 D15). It writes nothing. It is a `SubjectScopedChatTool`,
so `SupporterToolRegistry.of` admits it with no change to the allowlist, and
`SupporterToolRegistry.standard` registers it as a fifth tool.

It reads two things:

1. **The cost picture**, through
   `SubjectReadService.costPicture(viewer,
   studentId)`. This is the same seam
   call `family_cost_picture` makes, so the live link is re-checked on every
   call. `NotFound` returns the existing `studentNotFound()`.
2. **Each listed school's level.** A new read-only DAO call,
   `CollegeIpedsDao.levelsByCollegeId(session, ids)`, takes the cost profile's
   college ids and returns a `CollegeLevel` for each: `FOUR_YEAR`,
   `NOT_FOUR_YEAR` or `NOT_HELD` (no `college_ipeds` row, or a null level). It
   reads `college_ipeds.inst_level` through `CollegeLevel.of`, which names each
   published code (1 is four-year; 2 and 3 are not) and throws an
   `IllegalStateException` naming the code and the college on any other, rather
   than reading it as "not four-year". The four-year code moves out of
   `CollegesDao` into `CollegeLevel.FOUR_OR_MORE_YEARS_CODE` so search and this
   read share **one home**. This is reference data, not the student's, so it
   does not go through the seam. The tool therefore takes the `Database`, and
   `SupporterToolRegistry.standard` gains that parameter.

A pure `MoneyPlan.of(profile, levels)` turns those into the plan. The tool only
renders it. The domain is sealed, so a school can never carry a total and a
refusal at once:

- `MoneyPlanCollege.Totalled(college, years = 4, publishedPrice, priceAfterGrants)`
  - `years` is the one home of the span; `allYearsUsdOf(figure)` multiplies each
    of the school's figures by it.
  - `publishedPrice` is one of:
    - `Chosen(total)` when `chosen` is `Priced`;
    - `EveryWayOfLiving(collegeId, totals)` when `chosen` is `NotChosen`. It
      carries one total per arrangement in `breakdown` that has a total, and
      never none;
    - a `NoTotal`, whose `pricing` and `statement` are derived from its case,
      never paired by hand: `NoTotalHere(plan)` or `NotPricedHere(plan)` when
      `chosen` is that case, with the statement the cost picture already says
      for it (`ChosenLivingPlanCopy`, which both tools read); and
      `NoWayOfLivingTotalled` when `chosen` is `NotChosen` and no arrangement in
      `breakdown` has a total: then the basis is `not_chosen` and the statement
      says no way of living at this school has a total.
    - Each total is an
      `ArrangementAllYears(arrangement, academicYear,
      figure, provenance)`.
      Its wire `chosen` is written from the case that holds it: `true` under
      `Chosen`, `false` under `EveryWayOfLiving`.
  - `priceAfterGrants` is one of:
    - `Totalled(netPrice, academicYear, applies, figure, provenance)` when
      `netPrice.amount` is non-null. It keeps its basis, its band and the
      applicability the cost picture gives;
    - `NotReported(status, statement)`: the store's own typed status for the
      cell and the sentence `figure_statuses` says it in, else
      `NOT_REPORTED_BY_INSTITUTION` and its sentence. The wire `basis` is the
      status's own code, so a suppressed figure or a gap of ours is never
      labelled the school's silence;
    - `Withheld(reason)`, whose wire `basis` is the reason's own code.
- `MoneyPlanCollege.NotTotalled(college, reason: NotTotalledReason)`, where the
  enum holds each code and its sentence in one home:
  - `NOT_FOUR_YEAR` — we hold a level and it is not four-year;
  - `LEVEL_NOT_HELD` — we hold no level for the school.
- `PerYearFigure(perYearUsd)` holds one per-year amount and no span of its own,
  so it cannot disagree with its school's `years`. `Totalled.allYearsUsdOf` is
  the one place a figure is multiplied. It uses `Math.multiplyExact`, so an
  overflow throws instead of wrapping.

**The arithmetic.** An all-years figure is the one published per-year amount
times `years`, in whole dollars. Nothing is rounded. No two years are added
together, and no two schools are added together. Grants are never computed by
subtracting one figure from another.

**Why four, and only at four-year schools.** Four is the length of the degree
these schools award, and it is the figure the research names ("what all four
years cost"). It is a figure **we** chose, and the payload says so. It also says
that many students take longer, and that the total uses the one year's prices
shown, while prices usually rise. Projecting a rise would be a new figure, which
the slice forbids. A two-year or shorter school gets no total, rather than a
guessed length (decision 2).

**The payload** (snake_case, matching the cost tool's conventions):

```text
colleges[]:
  college_id, college_name
  either:
    years: 4
    published_price[]:            one entry, or one per way of living
      way_of_living, label, chosen (true|false),
      per_year_usd, academic_year, provenance[], assumed_by_unicoach?,
      all_years_usd
    | published_price_availability: { basis, statement }
    price_after_grants:
      per_year_usd, academic_year, basis, income_band?, income_band_label?,
      applies_basis, applies_to_this_family?, provenance[], all_years_usd
    | price_after_grants_availability: { basis: <valueless figure status> | <withheld reason>, statement }
  or:
    all_years_availability: { basis: not_a_four_year_school | level_not_held, statement }
all_years_statement   one sentence, once: every all_years_usd is ours — that
                      year's published price times the years, at that year's
                      prices; many students take longer and prices usually rise
source                the publishers, as the cost payload names them
```

`provenance[]` holds one `{assurance, assurance_statement, source}` entry per
distinct publisher and tier behind the figure. A total's parts can come from
more than one publisher, so a single object could misstate it. Every key that
the cost picture also carries (`label`, `assumed_by_unicoach`, `income_band`,
`applies_to_this_family`, and each provenance entry) is rendered by the **same**
helpers `CollegeCostChatTool.payloadOf` uses. `assumed_by_unicoach` marks the
with-family `$0` as ours. One home, so the two tools cannot describe one figure
two ways. The tool's description carries a short legend of these keys, following
the convention that a legend says how to READ a key and the prompt says what to
SAY (RFC 192). No bare source code reaches the payload (brief 0003).

### 3. The prompt: `parent-coach` v2

`prompts/parent-coach-system-prompt.md` changes in two places. Every other line
is byte-identical to v1, including the four shared money-vocabulary sentences
that `ParentCoachPromptTest` binds to the student coach.

**(a) The first answer**, inserted after the line "When the student has schools
on their list, open the first conversation with …":

> In that first answer, give each school's total for all its years from the
> family_money_plan tool beside its one-year figure, because one year's price
> hides most of the bill.

**(b) The plan and the conversation**, a new paragraph inserted after the line
"You may help this person plan the money conversation with the student …":

> When this person asks for a money plan, or what all the years will cost, write
> it from the family_money_plan and family_cost_picture tools, and never
> multiply, add or estimate a figure yourself.
>
> For each school, give its total for all its years and the one-year figure it
> came from, with that figure's year and source, and say plainly that the total
> is ours — that year's price times the years, not a figure any school
> publishes.
>
> Give a school's total after grants and scholarships beside its published-price
> total only when the tool gives both, and never subtract one from the other.
>
> Never add schools together into one total for the list.
>
> Where the tool gives a school no total, say why in its words and give no
> number for it.
>
> Then write the conversation for this person to have with the student this
> week, as a few questions they can ask and then listen to the answers, covering
> three things: what all the years cost, what graduates of the program the
> student wants to study earned, and the money that never has to be paid back.
>
> For what graduates earned, use the program figures in the cost picture and say
> what they measure — what graduates earned four years after starting college —
> and never call it a starting salary; when the student has not shared what they
> want to study, say so, and say that part of the conversation is the student's
> to tell.
>
> For the money that never has to be paid back, talk about grants and
> scholarships: a school's average need-based grant where its result gives one,
> said with the freshmen it describes, and the price after grants and
> scholarships.
>
> The conversation is the family's to start and the student's to answer: never
> script it to change the student's mind about a school.

(The block quote shows one sentence per paragraph for reading. In the file each
sentence is one line, with no blank lines between them, in the house style.)

- **Seed:** a new hand-written migration,
  `db/schema/seed-parent-coach-system-prompt-v2.sql`, plus its `ORDER` line. It
  has the same shape and header as v1 and inserts `('parent-coach', 'v2')` with
  the file's text verbatim. v1 is untouched: the catalog is insert-only.
  `bin/prompt-seed` still handles only the student coach.
- **Pin:** `parentCoaching.systemPromptVersion = "v2"` in `service.conf`. The
  rollback comment changes to name v1 as the row to fall back to.
- **Tool description, not prompt:** what each payload key means lives in the
  tool's legend, not in the prompt.

### 4. iOS: a second starter

`SupporterThreadView`'s empty thread offers two starters, each of which sends
itself. The first is unchanged: "What would <name>'s list cost our family?". The
second is new: "Write our four-year money plan for <name>'s list".
`static func starter(for:)` becomes `starters(for:) -> [String]`, both
localised, and each starter's accessibility identifier is indexed
(`supporterCoachStarter0`, `supporterCoachStarter1`). Nothing else changes on
iOS. The conversation is named after its first message by `convoNameFrom`, so a
plan conversation is listed on the Coach screen as "Write our four-year money
plan for <name>'s list", which is how it is found again.

### Reachability

The door is the iPhone. A parent signs in and lands on the parent home. They tap
"Talk to your coach about <name>", then "New conversation", then the starter
"Write our four-year money plan for <name>'s list". The coach writes the plan
and the week's conversation. Afterwards, the conversation sits in the Coach
screen's list under that name and reopens with the plan in it. The plan can also
be asked for in words in any parent conversation. The first starter's answer now
carries the all-years totals too, which meets the slice's first-session test.

### Value before ask, guided not gated

The parent is asked nothing. A school with no total gets a labelled reason and
not a refusal of the whole plan. A list with no living plan gets a total for
every way of living the school prices. A student who has not shared their field
gets that said, not a gap.

### Schema

**No DDL.** No table, column, constraint or index changes. One seed migration
inserts one `system_prompts` row.

## Tests

All server tests use the real database. The parent's tool and service tests use
the fixtures `SubjectScopedToolTest` and `FamilyCostPictureChatTool`'s tests
already use. The end-to-end test uses `ParentCoachServer` and its
`ScriptedCoach`.

**`MoneyPlanTest` / `FamilyMoneyPlanChatToolTest`** (`:service`):

1. A four-year school with a priced chosen plan and a band net price.
   `all_years_usd` is exactly 4 × `per_year_usd` for both totals. The academic
   years and provenance are carried. `years` is 4. The payload has no key that
   sums colleges.
2. No living plan chosen. There is one `published_price` entry per arrangement
   the school prices, each with `chosen: false`.
3. A chosen plan with no total (`NoTotalHere`, `NotPricedHere`). There is no
   `published_price`. `published_price_availability` carries the cost picture's
   own statement for that case.
4. The net price is withheld (an out-of-state family at a public school), or the
   school does not report it. There is no `price_after_grants` and the
   availability says which.
5. A two-year school, and a school with no level held. That college carries
   `all_years_availability` with the matching basis, and **no dollar amount
   anywhere** in its entry.
6. A viewer whose link is gone gets `student not found`.

**Level read** (the DAO's own test): a seeded four-year school, a two-year
school and a school with no `college_ipeds` row read back as four-year, not
four-year and not held.

**Registry** (`SupporterToolRegistryTest`): `standard()` registers
`family_money_plan`, and every registered tool is still admitted by `of`.

**Prompt** (`ParentCoachPromptTest`): the pin resolves to `parent-coach` v2. The
served v2 body equals the prompt file byte for byte. The served v1 row is still
present and unchanged. The four shared money sentences are still byte-identical
in both pins.

**End to end** (`:rest-server`, a new case beside the RFC 258 cost and cut-point
tests): a parent conversation where the scripted model calls
`family_money_plan`. The tool result recorded in `llm_requests` carries
`all_years_usd` equal to four times the seeded per-year figure. Cut point 3 (a
parent turn writes no student-owned row) still holds with the new tool called.
If that test iterates the registry it covers this automatically; if it does not,
the tool is added to it.

**iOS** (`SupporterCoachTests`, snapshots): the empty thread offers both
starters, and tapping the plan starter sends its exact text. Two scenes are
re-recorded, both of which render the empty thread: `supporter-coach-starter`,
and `supporter-coach-list-unavailable`, which shows it under a notice. No other
scene moves.

## Implementation Plan

1. **DAO.** Add the level read by college ids, with the four-year constant in
   one home, plus its test. Verify: `nix develop -c bin/test`.
2. **Domain and tool.** Add `MoneyPlan` and `FamilyMoneyPlanChatTool`, register
   the tool in `SupporterToolRegistry.standard`, and wire it in `Application.kt`
   if the registry's inputs change. Add tests 1–6 and the registry test. Verify:
   `nix develop -c bin/test`.
3. **Prompt.** Edit `prompts/parent-coach-system-prompt.md` as in §3. Hand-write
   `seed-parent-coach-system-prompt-v2.sql` and its `ORDER` line, bump the pin,
   and update `ParentCoachPromptTest`. Verify: `nix develop -c bin/test`.
4. **End to end.** Add the `:rest-server` case. Verify:
   `nix develop -c
   bin/test`.
5. **iOS.** Add the second starter, its test and the snapshot. Verify:
   `bin/test-ios` and `bin/snapshot-ios`, outside the dev shell.
6. **Full suite.** `nix develop -c bin/test`, unscoped.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/` — `models/CollegeLevel.kt` (new, the
  constant's one home), `dao/CollegeIpedsDao.kt` (the level read),
  `dao/CollegesDao.kt` (search reads the moved constant)
- `service/src/main/kotlin/ed/unicoach/family/` — `MoneyPlan.kt` (new),
  `FamilyMoneyPlanChatTool.kt` (new), `SupporterToolRegistry.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` —
  only if a provenance helper must be made reachable rather than copied
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` (wiring, if
  needed)
- `prompts/parent-coach-system-prompt.md`
- `db/schema/seed-parent-coach-system-prompt-v2.sql` (new), `db/schema/ORDER`
- `service/src/main/resources/service.conf`
- `ios-app/UnicoachiOS/SupporterCoachView.swift`, iOS tests and the snapshot
  scene
- Tests beside each module, as listed under Tests.

## Rollback

Revert the code. To stop the new prompt without a deploy, set
`PARENT_COACHING_SYSTEM_PROMPT_VERSION=v1`. v1 does not mention
`family_money_plan`, and a registered tool that nobody is told about is
harmless. The seed row stays in the insert-only catalog.
