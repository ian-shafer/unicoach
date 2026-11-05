# What we hold today — the repo behind the six-school cost answer

**METHOD.** Read-only pass over the worktree at `main@42e75798` (2026-11-04),
driven by `grep`/`sed` and file reads; no Gradle, no tests, no database. Every
claim below names a `path:line` in this tree. The coach prompt is cited by its
**source** file `prompts/coach-system-prompt.md`; the seed
`db/schema/seed-coach-system-prompt-v51.sql` is generated from it verbatim
(`seed-coach-system-prompt-v51.sql:1-12`) with the body starting at seed line
21, so a seed line is the source line plus 20 (the brief's "v51 lines 36–60 and
135–148" are source lines 16–40 and 115–128). Where the code's behaviour depends
on live rows I could not read, I say what the code guarantees and stop.

Two corrections to the brief's foundation column, found on the way and stated up
front because the ranked table will lean on them:

- **The IPEDS CSVs are not in the repo.** `.gitignore:36-43` ignores
  `db/seed/ipeds/*` except `PROVENANCE.json` and `FETCH-NOTES.md`; the directory
  holds exactly those two files. `ic2023_ay.csv` and `HD2023.csv` are artefacts
  of a `bin/fetch-ipeds` run, pinned by digest
  (`db/seed/ipeds/FETCH-NOTES.md:3-6, 36-41`), never committed.
- **`concise/07/quiet-provenance` is cancelled, not open.**
  `product/0009-say-it-once/spec.md:964-966` ("D16 — cancel quiet-provenance.
  (Ian, 2026-09-11)").

---

## 1. The cost answer's path

### The prompt in force

`db/schema/seed-coach-system-prompt-v51.sql:17-20` inserts
`('coach', 'v51',
...)`; `SystemPromptCatalogTest` reads the row `service.conf`
pins
(`service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt:622-626`).
The money lines that governed the recorded answer, by source line:

- **Order of the answer.** `prompts/coach-system-prompt.md:167`: "When two or
  more schools appear together, the cost tool gives you a comparison_basis: say
  those five lines first, as ordinary sentences above the table, never as a note
  at the bottom." Then `:176-178`: "Lead a money answer with the number: the
  first sentence carries a figure ... The explanation, the caution and the
  method come after it, never before it. Where the tool gives a
  comparison_basis, those five sentences still come first as the rule above
  says". The second rule defers to the first by its own words, so the basis
  paragraph precedes the table by design.
- **Provenance.** `:35` "Always attribute each cost figure to the source the
  tool names beside it"; `:130` "The tool gives each academic year together with
  the figures it covers: say a number with the year that lists it, never with
  the other year, and never add figures from two different years together";
  `:135` "Say the academic year the tool gives for the figures it returns";
  `:185` "name the source and the academic year with the figure they cover
  rather than in a list at the end"; `:281` "Where we hold a figure only for an
  earlier year, say that year as the result gives it".
- **The at-home $0.** `:120-124`: "The at-home total counts no housing and food,
  and that zero is ours rather than the school's ... Living at home, we count no
  food-and-housing cost: eating at home is not free, but it is not a new cost
  that enrolling creates, so the at-home total counts it as zero. That is our
  assumption, not a figure any school published." The recorded answer says this
  almost verbatim (`research/side-by-side-2026-11-04.md:135-139`).
- **Residency basis and the out-of-state total.** `:221-223`: "At a public
  school, the published price and the price after a financial aid offer are
  figures for students paying in-state tuition and fees ... Never offer either
  of those two figures to a family from another state as their price. Say the
  out-of-state total instead". This is the line behind "there is no comparable
  out-of-state figure" (`side-by-side-2026-11-04.md:131-132`).
- **The follow-up.** `:25-27`: precision_offer lists "the upgrades that result
  can take, in the order to raise them ... A residency_state offer comes first:
  ask what state the family lives in before you raise household income";
  `:199-200`: living_plan "comes after residency_state and income_band, and it
  is worth one question only when the result offers it and cost is already what
  you are talking about — say what it buys, which is one price picture instead
  of three." The recorded follow-up (`side-by-side-2026-11-04.md:141-143`) is
  that sentence. `:4` caps the coach at one question per reply.

### The tool and what it emits per school

`service/src/main/kotlin/ed/unicoach/coaching/StudentToolRegistry.kt:101`
registers `CollegeCostChatTool(CollegeCostService(database))`. One college
object is built at
`service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt:574-640`,
in this key order:

| Key(s)                                                                                                                  | Emitter                                                                                            | Notes                                                                                                                                                                                         |
| ----------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `college_id`, `name`, `city`, `state`, `control`, `list_status`                                                         | `CollegeCostChatTool.kt:588-593`                                                                   |                                                                                                                                                                                               |
| sticker cost of attendance                                                                                              | `:594` (`CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD`)                                       | a blend (`FigureGroup.kt:42-48`)                                                                                                                                                              |
| **tuition tiers** — in-state / out-of-state / in-district                                                               | `putTuitionTiers` `:674-675`, iterating `cost.publishedTuitionTiers` (`CollegeCostService.kt:566`) | "a fourth tier is emitted without a second list saying it should be" (`:662-671`)                                                                                                             |
| fees-only tiers                                                                                                         | `putFeesOnly` `:685-686`                                                                           |                                                                                                                                                                                               |
| `residency_tiers` `{basis, statement}`                                                                                  | `:698` → `db/src/main/kotlin/ed/unicoach/db/models/ResidencyTierBasis.kt:247-255`                  | six codes, `ResidencyTierBasis.kt:50-116`                                                                                                                                                     |
| `tuition_applicable` (`in_state` / `out_of_state` / `unknown`)                                                          | `:600`; enum `CollegeCostService.kt:169-177`                                                       | public colleges only                                                                                                                                                                          |
| `net_price` `{amount_usd?, basis, income_band(+label)?, withheld_reason?, statement?}`                                  | `putNetPrice` `:714-717`, `netPriceObject` `:1372-1398`                                            | `basis` is `your_income_band` or `overall_average` (`CollegeCostService.kt:75-93`)                                                                                                            |
| `precision_offer[]` `{field, offer}`                                                                                    | `:1294-1301`, copy `:1321-1358`                                                                    | absent, never empty (`:719-723`)                                                                                                                                                              |
| median debt, median earnings                                                                                            | `:602-603`                                                                                         | undated                                                                                                                                                                                       |
| `cost_by_living_arrangement`                                                                                            | `:607`, `breakdownObject` `:1226`                                                                  | per-arrangement lines and totals (RFC 149)                                                                                                                                                    |
| chosen arrangement, `offers_on_campus_housing`                                                                          | `:615-616`                                                                                         |                                                                                                                                                                                               |
| **vintage labels** `published_price_academic_year` / `blended_average_academic_year`, each `{academic_year, figures[]}` | `putVintageLabels` `:1187-1213`                                                                    | one object per `FigureGroup` (`FigureGroup.kt:40,48`); year read off the rows, "a school served from the Scorecard says 2024-25 where a school served from IC_AY says 2023-24" (`:1193-1197`) |
| `merit_aid`, `aid_policy`, `borrowing`                                                                                  | `:627-636`                                                                                         | CDS-sourced, each with its own citation                                                                                                                                                       |
| `data_availability`, `withheld_figures`, `figure_statuses`, blended-figures-apply                                       | `:637-646`                                                                                         |                                                                                                                                                                                               |

The one **withheld-figure reason** today is
`WithheldReason.IN_STATE_ONLY_FIGURE`
(`service/src/main/kotlin/ed/unicoach/coaching/costs/BlendedFigureBasis.kt:18-35`),
whose wire sentence (`:49-57`) is: "This school publishes this figure for
students paying in-state tuition, and this family would pay the out-of-state
price here, so it is not shown for them. The source publishes no out-of-state
version of it. The totals built from this school's out-of-state tuition and fees
are the ones that apply to this family." `NetPrice.Withheld` carries that reason
and no amount (`CollegeCostService.kt:155-161`). "One member today. The
vocabulary is open on purpose" (`BlendedFigureBasis.kt:15-16`).

The income-band net price: `NetPrice.BandSpecific(band, amount)`
(`CollegeCostService.kt:138-143`), selected by the profile's answered band; the
band's spoken range rides beside it (`CollegeCostChatTool.kt:1380-1382`;
`db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt:24-36`, top band
`OVER_110K` "$110,000 or more"). That is the "$110,000 or more" figure the
answer led with for the two in-state schools
(`side-by-side-2026-11-04.md:124-129`).

### The PrecisionOffer follow-up ordering

`CollegeCostService.kt:1118-1129`: `precisionOffersFor` is
`PrecisionOffer.entries.filter { it.appliesTo(moneyProfile, college) }` —
declaration order IS the order. Members: `RESIDENCY("residency_state")` applies
at a public college with residency `UNANSWERED` and published tuition
(`:1200-1217`); `INCOME_BAND("income_band")` applies when the band is
`UNANSWERED` and the school reports band pricing (`:1237-1241`);
`LIVING_PLAN("living_plan")` applies when the plan is unanswered and the school
prices enough arrangements (`:1273-1283`). In the recorded session residency and
income were already answered, so the only offer left was living_plan — the
follow-up the brief objects to was the only one the tool could make.

### Where "All figures below are from the 2023-24 academic year" came from

The tool emits **no such sentence**. It emits:

1. per college, up to two vintage objects (`CollegeCostChatTool.kt:1187-1213`),
   and
2. with two or more colleges, `comparison_basis.academic_years[]` (`:268-270`),
   one `DatedFigures` per `(group, year)` with the schools it is true of
   (`:556-573`), each with a sentence from
   `service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt:1259-1271`:
   "The published price figures for {schools}, which are tuition and fees and
   the parts of living cost a school publishes, come from the {year} academic
   year." and "The blended averages for {schools}, which are the published cost
   of attendance and the net price, come from the {year} academic year."

**Yes, the tool can emit different years for charges and blends.** The
published-price year per school is `served.academicYear`
(`CollegeCostService.kt:514`), chosen by `CollegeFigures.publishedPriceYearOf`
(`service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/CollegeFigures.kt:898-918`):
"the latest year completely pricing SOME candidateSets, else the latest year
bearing any value". Scorecard charges are stamped 2024-25
(`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1691`) and
IC_AY rows carry 2020-21 through 2023-24
(`college/src/main/kotlin/ed/unicoach/college/IpedsChargeVocabulary.kt:36-39,
137-140`),
so a school whose Scorecard row completely prices the arrangement is served at
**2024-25**, and one priced only from IC_AY at 2023-24. The blend year is
`blendedAverageVintage(band)` (`CollegeFigures.kt:547-584`): the single distinct
vintage across the served blend rows, **null when they disagree**. Scorecard
`COSTT4_A`/`NPT4*` are stamped 2023-24 (`CanonicalMoneyLoader.kt:1700`;
`db/seed/scorecard/dictionary-variable-sources.csv:10-23`), but the SFA fill
stamps band rows with the SFA aid year (`CanonicalMoneyLoader.kt:637`), which is
2022-23 (`db/seed/ipeds/FETCH-NOTES.md:19-22`; `bin/fetch-ipeds:208`), and SFA
wins the natural key over the Scorecard (`CanonicalMoneyLoader.kt:79-82`).
`CollegeFigures.kt:571-581` says exactly this case is reachable: "the SFA fill
writes the BAND net price rows ... at a newer aid year than the Scorecard's
COSTT4_A blend, so any family with an answered band had the sticker cost quoted
under the net price's year" — and the fix was to emit **no** blended year label
at all in that case.

So the single "All figures ... 2023-24" sentence
(`side-by-side-2026-11-04.md:106-107`) is the model's compression of two or
three per-group sentences the payload carried, and the coordinator's own table
header already distinguishes "2023-24 blend / 2024-25 charges"
(`side-by-side-2026-11-04.md:25`). Whether each of the six schools was served at
2024-25 or 2023-24 depends on which cells its Scorecard row filled, which only
the live `price_figures` rows can answer; the code guarantees only that the year
said is the year of the rows served, per school, per group.

**What C6 would touch here:** `prompts/coach-system-prompt.md:167, 176-178`
(order), `:199-200` and `CollegeCostService.kt:1178-1290` (follow-up choice) —
copy plus one enum-order decision; see §7 for the test that pins the order.

---

## 2. Vintages and sources

### Which source feeds which measure

`CanonicalMoneyLoader.kt:65-82`: the fill "re-reads the pinned Scorecard CSV,
the staged IC_AY charges and this run's staged SFA cells, and rebuilds
`price_figures`, `cohort_money_stats` and `cohort_population_counts` wholesale —
DELETE + re-insert"; `ORDERED_SOURCES` is "IPEDS SFA, then IPEDS IC_AY, then the
Scorecard: a publisher's own number displaces the Scorecard's copy of it
wherever the two share a natural key", first write wins (`:1152-1153`).

- **Published charges from the Scorecard** (`:1160-1173`): `TUITIONFEE_IN` →
  in_state tuition, `TUITIONFEE_OUT` → out_of_state, `ROOMBOARD_ON/OFF` →
  housing and food by arrangement, `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM`; each
  stamped by `ScorecardInstitutionColumns.datedYearOf`
  (`college/src/main/kotlin/ed/unicoach/college/ScorecardInstitutionColumns.kt:290-291`,
  group map `:280-281`).
- **Published charges from IC_AY** (`:818-890`): the staged `CHG*AY` stems
  mapped by `IpedsChargeVocabulary.CELLS`, keyed
  `PriceKey(college, concept, residency, arrangement, academic_year)` (`:885`),
  one row per year in the file's four-year window. The in-district tier comes
  only from here (`ResidencyTierBasis.kt:11-13`).
- **Blends from the Scorecard** (`:1505-1518`): `COSTT4_A` and `NPT4` + five
  bands, control-keyed, at `BLENDED_AVERAGE_VINTAGE`.
- **Blends from SFA** (`:598-662`): the `NPIS4x/NPT4x` twins at `cell.aidYear`
  (`:637`), displacing the Scorecard copy where keys coincide (`:643-662`).
- **IC_AY vs Scorecard precedence on tuition**: the Scorecard's `TUITIONFEE_IN`
  is written only when the same fill staged IC_AY residency evidence; where
  IPEDS files in_state ≠ in_district the Scorecard in-state cell is withheld
  (`:1019-1052`, `:1245-1284`, `:1387-1397`). That guard reads IC_AY at the
  **same** academic year.

### The pins

```
college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1691  internal val PUBLISHED_PRICE_YEAR = AcademicYear(2024)
college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1700  internal val BLENDED_AVERAGE_VINTAGE = AcademicYear(2023)
college/src/main/kotlin/ed/unicoach/college/IpedsChargeVocabulary.kt:36    const val SURVEY_YEAR = 2023
college/src/main/kotlin/ed/unicoach/college/IpedsChargeVocabulary.kt:39    const val YEARS_CARRIED = 4
bin/fetch-ipeds:201                                                         SURVEY_YEAR = 2023
bin/fetch-ipeds:208                                                         SFA_AID_YEAR = 2022
```

The Scorecard constants are transcribed from the publisher's dictionary
(`CanonicalMoneyLoader.kt:1677-1685`: "AcadYr 2024-25 ... release 06102026")
into `db/seed/scorecard/dictionary-variable-sources.csv:2-23` and asserted by
`college/src/test/kotlin/ed/unicoach/college/ScorecardDictionaryPinTest.kt:209-228`
and `:292-295` (`assertEquals("2024-25", ...PUBLISHED_PRICE_YEAR.label)`;
`assertEquals("2023-24", ...BLENDED_AVERAGE_VINTAGE.label)`). The release itself
is **discovered, not pinned**, by `bin/fetch-scorecard:20-24`; the current
manifest records release `06102026` (`db/seed/scorecard/PROVENANCE.json:5`).

### The schema keys

`db/schema/0083.create-canonical-money-tables.sql:184-202`:

```
CREATE TABLE price_figures (
    ...
    academic_year   TEXT        NOT NULL,                       -- :190
    source          TEXT        NOT NULL,                       -- :193
    source_variable TEXT        NOT NULL,   -- e.g. 'TUITIONFEE_IN'
    ...
        UNIQUE (college_id, price_concept, residency_basis, arrangement,
                academic_year),                                 -- :198-200
    CONSTRAINT price_figures_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),          -- :201-202
```

**`academic_year` is part of the key; `source` is not.** Two years already
coexist per cell (four IC_AY years and one Scorecard year —
`CollegeFigures.kt:300-304`), and the year "is DATA here ... never a comment"
(`0083:23-25`, `:233-235`). `cohort_money_stats` keys on
`(college_id, measure,
population, residency_scope, aid_scope, income_band, vintage)`
`NULLS NOT
DISTINCT` (`:288-291`). The brief's "(source, academic_year)" is
therefore half right: the year is keyed, the publisher is first-write-wins data.

`db/src/main/kotlin/ed/unicoach/db/models/MoneySource.kt:27-42` — all members:
`IPEDS_SFA("ipeds_sfa")`, `IPEDS_IC_AY("ipeds_ic_ay")`,
`SCORECARD("scorecard")`, `COMMON_DATA_SET("common_data_set")`; the `CHECK IN`
lives in migration 0084 (`:11`). "Declaration order is NOT precedence"
(`:20-21`).

### The scripts

- `bin/fetch-ipeds:255-275` lists the archives as literals
  (`"IC2023_AY.zip", "ic2023_ay.csv", ..., "-Y", SURVEY_YEAR`); `_RV` revised
  members are refused except for SFA (`:56-67`); every digest and row count is
  pinned in `db/seed/ipeds/PROVENANCE.json` and again in `bin/scripts-tests`
  (`FETCH-NOTES.md:39-41`; `bin/scripts-tests:5829-5833, 5876`).
- `bin/ingest-colleges:200`
  (`-Y FILE: IPEDS IC_AY survey file, published
  charges (ic2023_ay.csv)`),
  `:371`, passes `--ic-ay` and `--survey-year` (`:484-488`);
  `IpedsChargeVocabulary.assertSurveyYear` (`IpedsChargeVocabulary.kt:186-194`)
  is **fatal** when the year is not the pinned one: "decoding a [$surveyYear]
  file against it would date every staged charge and every derived price [N]
  year(s) wrong; update IpedsChargeVocabulary.SURVEY_YEAR with the file".
- The CDS fetch (`bin/fetch-cds-seed`) names only `C.7xx` and `H.xxx` field ids
  (grep over the script: `C.701`–`C.718`, `H.201`–`H.807`; no `G.`), so the
  brief's "section G is not read" holds.

### What exactly changes to load IC2025_AY

There is **no "beside"** in the current model. `IpedsChargeVocabulary` pins one
four-year window ending at `SURVEY_YEAR` (`:20-27`: "`CHG2AY3` is the 2023-24
in-state price in `IC2023_AY.csv` and the 2024-25 one in `IC2024_AY.csv`"), and
the fill deletes and re-inserts the tables wholesale
(`CanonicalMoneyLoader.kt:66-70`). Loading `IC2025_AY` means **replacing**
`IC2023_AY` with a file whose window is 2022-23..2025-26 — which still keeps
2023-24 rows, so nothing is lost. Concretely:

1. `bin/fetch-ipeds:201, 255-260` — `SURVEY_YEAR` and the five literal
   archive/member/fixture names; the manifest regenerates itself.
2. `IpedsChargeVocabulary.kt:36` — `SURVEY_YEAR`.
3. Test fixtures pinned to 2023:
   `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardTestBase.kt:31`
   (`IC_AY_FIXTURE_SURVEY_YEAR = 2023`), `IpedsChargesLoaderTest.kt:22`
   ("VERBATIM subset of the published `ic2023_ay.csv`"),
   `IngestApplicationArgvTest.kt:95-137`,
   `bin/scripts-tests:5416, 5639, 5829-5833`.
4. **No migration**: `academic_year` is row data (`0083:190, 233-235`).
5. **The trap** — `ScorecardDictionaryPinTest.kt:298-313`:

   ```
   fun `the Scorecard's charges sit past IC_AY's staged window, so the two publishers cannot contend`() {
     ...an `IC2024_AY.csv` bump (edit `IpedsChargeVocabulary.SURVEY_YEAR` alone)
     puts IC_AY back on top of the Scorecard's key and silently restores the
     displacement this RFC measured at 29,704 rows. It fails HERE instead,
     where the fix is a decision someone makes.
     val ipedsNewestYear = AcademicYear(IpedsChargeVocabulary.SURVEY_YEAR)
     assertTrue(CanonicalMoneyLoader.PUBLISHED_PRICE_YEAR > ipedsNewestYear, ...)
   ```

   With `SURVEY_YEAR = 2025` (or 2024) and the Scorecard still at 2024 this
   fails by design, because IC_AY's 2024-25 row and the Scorecard's 2024-25 row
   would share a `price_figures` key and `ORDERED_SOURCES` would make IC_AY win
   it (`CanonicalMoneyLoader.kt:79-82`). `CollegeCostService.kt:508-513` states
   the same assumption in prose ("The two publishers are ONE YEAR APART and
   never contend (RFC 183)"). Bumping IC_AY is a precedence decision, not a
   constant edit.

6. The Scorecard half of C1 is a `bin/fetch-scorecard` run (release discovered),
   then the dictionary transcription (`dictionary-variable-sources.csv`) and the
   two constants (`CanonicalMoneyLoader.kt:1691, 1700`) move to whatever the new
   dictionary says — that is the "pin change" the brief names.

**What C1 would touch:** `bin/fetch-ipeds` (year + names),
`IpedsChargeVocabulary.SURVEY_YEAR`, four test fixture pins,
`ScorecardDictionaryPinTest` (a decision), and — for the Scorecard half — the
dictionary CSV plus two constants. Size: **seed bump plus one precedence
decision**; no table, no migration. The "school's own page beside the federal
one" part of C1 is a new source (`MoneySource` member + `CHECK` migration per
`MoneySource.kt:10-12`) and a new fetcher — a bigger slice, and the external
report's question.

---

## 3. Residency tiers

### How tiers are represented

- **Storage.**
  `price_figures.residency_basis TEXT NOT NULL REFERENCES
  residency_bases(slug)`
  (`0083:188`); the vocabulary table `residency_bases` (`0083:27-48`) is seeded
  from `db/data/money-vocabulary.json:2-14` with `in_district`, `in_state`,
  `out_of_state`. Kotlin: `ResidencyBasis`
  `IN_DISTRICT, IN_STATE, OUT_OF_STATE, NOT_APPLICABLE, UNKNOWN`
  (`db/src/main/kotlin/ed/unicoach/db/models/ResidencyBasis.kt:16-29`).
- **Migration 0075 is comments only.** `0075:3-6`: "COMMENTS ONLY: no column is
  added, dropped or retyped". It records that the blends are on an in-state
  basis and that "an out-of-state published price is only obtainable as
  tuition_and_fees_out_of_state_per_year_usd plus the components" (`:24-28`).
  The tiers themselves came with RFC 161's IC_AY ingest and migration 0083, not
  0075.
- **Which tier applies to this family.** `money_profiles.residency_state`
  (`db/schema/0046.create-money-profiles.sql:38-39`, `^[A-Z]{2}$` `:46-47`,
  tri-state status `:48-49`) → `TuitionApplicable`
  `IN_STATE / OUT_OF_STATE /
  UNKNOWN` (`CollegeCostService.kt:169-177`, public
  colleges only) → `applicableTuitionFor` picks the `CostField` every
  arrangement total is built on (`CollegeCostService.kt:2203-2210`).
- **On the wire.** The published tiers are emitted by iteration
  (`CollegeCostChatTool.kt:662-675`); `residency_tiers {basis, statement}` says
  which the school publishes (`ResidencyTierBasis.kt:247-255`), with six codes
  including `PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT` and `NO_PUBLISHED_TUITION`
  (`:70-74, 113-116`); `tuition_applicable` says which is the family's
  (`CollegeCostChatTool.kt:600`). The in-district precedent is the model for a
  labelled tier that is never the tuition line inside a total:
  `prompts/coach-system-prompt.md:28` "say it as a labelled price beside the
  other two, never as the tuition and fees line inside a total".

### Where a derived fourth tier would plug in

The design already exists in prose:
`product/0006-*/research/source-landscape.md:326-333` — "model an exchange as a
**derived price tier with a named basis** (e.g. 'WUE = 1.5 × in-state tuition,
if participating and admitted under WUE') — never a stored dollar pretending to
be a published price. Ingest = a small curated roster table (institution,
program scope, formula, source URL, as-of date); 'missing' here means 'not a
member', which is _not-applicable_, not not-reported." It was deferred "only
after residency tiers exist to hang the formula on" (`:405-407`). They do now.

Three seams, each with a precedent in the tree:

1. **The roster** — a new seeded table (Ian's DDL gate). The awards corpus is
   the closest shape: `db/seed/automatic-awards/awards.csv:1` already keys rows
   on
   `unit_id, ..., entry_year, residency_basis, ... publisher, source_url,
   archive_url, checked_on, expires_on`,
   and its provenance file already records a WUE refusal —
   `db/seed/automatic-awards/PROVENANCE.json:137-138`: "the non-resident page
   ... publishes two different programmes (WUE for 16 named states, Invitation
   to Idaho for the rest) whose values are given as approximations ... which one
   out_of_state key and an INTEGER per-year column cannot state honestly." A
   roster of
   `(college, home-state set, formula
   basis, condition copy, source URL, checked_on)`
   is the thing that file says it could not be.
2. **The derivation** — at read time in `:service`'s cost domain, never in the
   loader (`CanonicalMoneyLoader.kt:95-97`: "the fill maps and copies, it never
   does money arithmetic"; CLAUDE.md "Derived figures ... are computed at read
   time and labeled as derived — never stored"). The line-origin precedent for
   "a line whose amount is ours" is `LineOrigin.ASSUMED_BY_UNICOACH`
   (`CollegeCostService.kt:545-553`; wire key `assumed_by_unicoach`,
   `CollegeCostChatTool.kt:2131`; prompt `:279` "a line we assumed ourselves").
   A derived exchange line is the same category with a different reason.
3. **The tuition selection** — `applicableTuitionFor`
   (`CollegeCostService.kt:2203-2210`) is exhaustive over `TuitionApplicable`; a
   conditional tier ("if admitted") is not the family's applicable tuition, so
   the natural shape is a labelled tier beside `out_of_state`, as in-district is
   beside in-state, not a fourth `TuitionApplicable` member.

### Does `ForbiddenCostArithmeticTest` forbid tuition × 1.5?

No. The test scans for **subtraction only**.
`service/src/test/kotlin/ed/unicoach/coaching/costs/ForbiddenCostArithmeticTest.kt:8-11`:
"nothing in the cost package computes a net price minus tuition, or a net price
minus a component, or one component minus another." The pattern (`:115-119`):

```kotlin
private val subtraction =
  Regex(
    """$MONEY_OPERAND[A-Za-z0-9_.)\]]*\s*-\s*([A-Za-z_(]|${"$"})""" +
      """|[A-Za-z0-9_)\]]\s*-\s*[A-Za-z0-9_.(]*$MONEY_OPERAND""",
  )
```

and the assertion (`:202-213`) "no cost source subtracts a price from a price".
It sweeps `coaching/costs` (recursively), `coaching/aid` and `db/dao`
(`:30-66`). A `*` never matches. The precedent for multiplication in Kotlin is
`service/src/main/kotlin/ed/unicoach/family/MoneyPlan.kt:271-273` —
`Math.multiplyExact(figure.perYearUsd, years)`, "the ONE place a money-plan
figure is multiplied". The prompt-side rule is about adding years and
subtracting aid (`prompts/coach-system-prompt.md:129-130`), not scaling.

The brief's constraint 2 is unaffected: 1.5 × in-state tuition plus the school's
own components is the same shape as the out-of-state total the tool already
builds (`0075:26-28`), not out-of-state tuition plus a blend.

**What C2 would touch:** one new seeded roster table (DDL + `Ian approves`), a
`:db` model + DAO, a derived line in `coaching/costs` with its own origin and
sentence (`CollegeCostService`/`CostBreakdown`/`CollegeCostChatTool` emit +
description), one prompt paragraph (`prompts/coach-system-prompt.md` near `:28`
and `:221-226`), and a `CostReportPage` row if the page should match. Size:
**new table + new derived line**; the seed can start with WUE alone (170+
publics per `source-landscape.md:312-315`), and the first row set can be the six
schools' region.

---

## 4. Next-step facts

### `db/seed/cds/aid_policy.csv`

Header (`:1`):
`unit_id,source_year,fact,value,status,source_variable,source_url,archive_url`;
3,496 rows. The 22 distinct `fact` values (counted over the file):
`aid_awarded_freshmen_count` (293), `avg_need_met_percent` (277),
`graduating_class_count` (278), `need_fully_met_count` (276),
`avg_need_based_grant_usd` (275), `any_loan_borrower_count` (213),
`any_loan_debt_avg_usd` (213), `fafsa_required` (219),
`federal_loan_borrower_count` (209), `federal_loan_debt_avg_usd` (211),
`institutional_loan_borrower_count` (152), `state_loan_borrower_count` (144),
`institutional_form_required` (19), `state_aid_form_required` (36),
`private_loan_borrower_count` (207), `private_loan_debt_avg_usd` (209),
`institutional_loan_debt_avg_usd` (68), `other_institutional_form_required`
(33), `state_loan_debt_avg_usd` (48), `noncustodial_css_profile_required` (34),
`business_farm_supplement_required` (23), `css_profile_required` (59).

**None touches nonresidents.** A grep for `nonresident`, `non-resident`,
`out-of-state`, `out_of_state`, `international` over the file returns nothing.
The facts map to CDS section H fields in
`college/src/main/kotlin/ed/unicoach/college/CdsSeedLoader.kt:1825-1845`
(`H.201/204/208/209/211`, `H.401`, the `H.5xx` loan cells, `H.801-807` forms).
So the first of C3's three facts — "whether the school gives need-based aid to
nonresidents (its own stated policy)" — is **not held** and is not in the
section the fetcher parses. The cost tool already carries this file's facts as
`aid_policy` (`CollegeCostChatTool.kt:629-636`; prompt `:286-294`: need met,
`required_forms`).

Coverage for the six schools (rows per `unit_id`): aid_policy — UW 15, Western
Washington 12, Cal Poly SLO 8, UC Berkeley 19, San Diego State 1, Oregon State
12; `merit-aid.csv` — one row each except Berkeley (0).

### The automatic-awards seed

`db/seed/automatic-awards/awards.csv:1` columns:
`unit_id, college_name,
entry_year, residency_basis, award_kind, score_requirement, superscore_rule,
score_deadline, score_deadline_as_published, score_requirement_as_published,
superscore_as_published, last_sitting_through, last_sitting_precision,
last_sitting_as_published, publisher, source_url, archive_url, checked_on,
expires_on`;
60 rows over 40 colleges; `residency_basis` is `out_of_state` 21, `in_state` 20,
`not_applicable` 19. `bands.csv:1` carries the grid cells
(`award_name, test, score_low, score_high, gpa_low, gpa_high,
award_per_year_usd, award_as_published`).

- **Which tool emits them.** `college_automatic_awards`
  (`service/src/main/kotlin/ed/unicoach/coaching/testing/AutomaticAwardChatTool.kt:427`),
  registered at `StudentToolRegistry.kt:126`. It emits `residency_basis` and
  `residency_basis_label` per answer (`:205-206`; labels `:507-525`) and its
  description says "read residency_basis and residency_basis_label out loud and
  never apply one residency's grid to the other" (`:838-840`). The service
  "answers one row per (college, residency)" (`:119`) and refuses to pick the
  family's residency (`CollegeCostService.kt:420-422`).
- **Does the cost tool link to them?** The cost _service_ carries
  `automaticAwards: List<AutomaticAwardAnswer>`
  (`CollegeCostService.kt:412-444`, read through the student-free door
  `:1327-1337`), but `CollegeCostChatTool.kt` never emits it — the only renderer
  is the Family Cost Report
  (`public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt:1873`).
  In chat, the grid reaches the coach only via the testing paragraph
  (`prompts/coach-system-prompt.md:66, 68`), not via the cost answer.
- **Six schools.** Only UW has a row, and it is `not_applicable,none_published`
  (`awards.csv`, unit 236948); the four out-of-state schools have none. So the
  "out-of-state automatic merit grid" is a fact we hold for Alabama–Birmingham
  and Utah State (`awards.csv:2-3, 55-56`), not for this list.

### The net price calculator URL

`NPRICURL` is a column of HD2023
(`college/src/test/resources/ipeds-hd2023-fixture.csv:1` lists
`...,APPLURL,NPRICURL,VETURL,...`). Nothing reads it: `IpedsLoader`'s
`REQUIRED_HD_COLUMNS`
(`college/src/main/kotlin/ed/unicoach/college/IpedsLoader.kt:1001-1013`) are
`UNITID, CYACTIVE, DEATHYR, CLOSEDAT, NEWID, ICLEVEL, UGOFFER, SECTOR,
C21BASIC, C21SZSET, CBSA`
(plus the ROOM/ROOMCAP and other constants at `:980-990`); a case-insensitive
grep for `npricurl|npcurl|net_price_calculator` over `db/`, `college/`,
`service/`, `bin/`, `rest-server/`, `public-web/`, `prompts/` finds **no Kotlin,
SQL or script** hit — only the fixture headers. Brief 0006 already placed it:
"NPC — link-out only; never a stored figure" (`source-landscape.md:408`). And
`HD2023.csv` itself is not committed (`.gitignore:41`), so the link must be
loaded by the ingest, not read from a seed.

**What C3 would touch:** (a) the calculator link — a `college_ipeds` column
(migration, DDL gate) + `IpedsLoader.mapHd` read + a `CollegeCostChatTool` key

- one prompt line: **small, one column**; (b) the out-of-state grid — no new
  data, but the cost tool must emit what the service already carries
  (`CollegeCostService.kt:444` → a new key in `CollegeCostChatTool.kt`) and the
  prompt must say it beside the withheld-figure sentence: **copy + one
  emitter**; coverage for this list is nil until the awards corpus grows; (c)
  "need-based aid to nonresidents" — **not held**: a new CDS section or a
  school-page read, a new fact, a new source row. The honest first slice is
  (a)+(b).

---

## 5. Four-year totals

`service/src/main/kotlin/ed/unicoach/family/FamilyMoneyPlanChatTool.kt` is
`family_money_plan` (`:105`), a `SubjectScopedChatTool` (`:45`) registered
**only** for supporters
(`service/src/main/kotlin/ed/unicoach/family/SupporterToolRegistry.kt:65`); the
student roster (`StudentToolRegistry.kt:84-135`) has no equivalent. It takes no
argument (`:39-40, 48`).

What it computes (`:27-37`): per listed school, "the arithmetic done here, so
the model never multiplies, adds or estimates a figure itself". Inputs:

- the cost picture via `SubjectReadService.costPicture` (`:56-57`), i.e. the
  same `CollegeCostProfile` the cost tool renders (`:77-81`);
- **program length** from `college_ipeds` (`:83-85`,
  `CollegeIpedsDao.levelsByCollegeId`) → `CollegeLevel` from `HD.ICLEVEL`
  (`db/src/main/kotlin/ed/unicoach/db/models/CollegeLevel.kt:5-22`);
  `MoneyPlan.of`
  (`service/src/main/kotlin/ed/unicoach/family/MoneyPlan.kt:58-87`) gives
  `FOUR_YEAR` schools `years = FOUR_YEAR_SCHOOL_YEARS = 4` (`:51, 73-76`) and
  `NotTotalled(NOT_FOUR_YEAR | LEVEL_NOT_HELD)` otherwise (`:82-87`). There is
  no per-program length; "four years" is a flat constant.
- `all_years_usd = Math.multiplyExact(perYearUsd, years)` (`:271-273`).

Payload (`:109-121, 130-136`): `colleges[]` with `years`, `published_price[]`
(`way_of_living, arrangement_label, chosen, per_year_usd, academic_year,
provenance, assumed_by_unicoach, all_years_usd`),
`price_after_grants`
(`per_year_usd, academic_year?, basis, income_band_label, applies..., provenance,
all_years_usd`),
`all_years_statement`, `source`. The statement (`:124-127`): "Every
all_years_usd is ours, not a figure any school publishes: the one-year figure
beside it times the years, at that one year's prices. Many students take longer
than four years, and prices usually rise from year to year."

The student prompt (`prompts/coach-system-prompt.md`, 322 lines, read in full)
has no all-years or four-year rule; the cost tool emits per-year figures only
(`CollegeCostChatTool.kt:1555`: "The academic year one vintage names").

**What C4 would touch — two shapes:**

- **A new student tool** (smallest): a `money_plan` `ChatTool` that builds
  `CollegeCostProfile` through `CollegeCostService` and reuses `MoneyPlan.of` +
  `FamilyMoneyPlanChatTool.payloadOf` (public in the companion, `:130`), one
  line in `StudentToolRegistry.kt`, one prompt paragraph mirroring the parent
  coach's. Size: **new thin tool, no data, no table**.
- **A field on the cost tool**: `all_years_usd` beside every total would have to
  enter the `emitted`/vintage-label ordering (`CollegeCostChatTool.kt:609-623`
  is "load-bearing") and widen `CostField`; every cost call would carry it. A
  larger change for the same number.

---

## 6. The money profile

### Fields, bands, declinability

- `db/schema/0046.create-money-profiles.sql:18-55`: `income_band TEXT NULL` with
  `CHECK ... IN ('under_30k','30k_to_48k','48k_to_75k','75k_to_110k','over_110k')`
  (`:41-43`); `income_band_status IN ('unanswered','answered','declined')`
  (`:44-45`); `residency_state ~ '^[A-Z]{2}$'` (`:46-47`) and its status
  (`:48-49`); value-iff-answered CHECKs (`:52-55`); versions table + trigger
  (`:65-91`).
- `db/schema/0070.add-money-profile-living-plan.sql:21-31`: `living_plan` +
  `living_plan_status`, same tri-state and iff-CHECK.
- `db/schema/0078.add-money-profile-dependency-status.sql:29-38`:
  `dependency IN
  ('dependent','independent')` + `dependency_status`.
- `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt:21-40`: four
  `(value?, AnswerStatus)` pairs, versioned, soft-deletable.
- Bands with their spoken ranges: `IncomeBand.kt:24-36` (`UNDER_30K` "$0 to
  $30,000" … `OVER_110K` "$110,000 or more").
- Declinable: every field is tri-state; the prompt never re-asks after a decline
  (`prompts/coach-system-prompt.md:33-34, 206, 232`).
- **No household size, no assets**: a grep for `household_size|household|assets`
  over `db/schema/*.sql` (excluding "household income") returns nothing.

### `FederalAidPolicyService` (RFC 159)

`service/src/main/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyService.kt:80-89`
`PellParameters(awardYear, currency, maxAwardUsd, minAwardUsd, saiFloor,
dependentSingleParentPct, dependentNonSingleParentPct, sources)`;
`:92-98`
`UndergradLoanParameters(dependent: LoanTable, independent: LoanTable)`;
`:101-108` `GradLoanParameters`; `:111-114` `DependencyAnswer`. The parameter
vocabulary is
`db/src/main/kotlin/ed/unicoach/db/models/PolicyParameter.kt:21-44`
(`PELL_MAX_AWARD_USD`, `PELL_MIN_AWARD_USD`, the two max-Pell AGI percentage
tests, `SAI_FLOOR`, the Direct Loan annual and aggregate caps).

Its contract (`:132-136`): "Read-only, and it computes NOTHING with money: no
SAI estimate, no Pell amount, no loan-minus-anything. Every figure leaves
exactly as it was seeded"; and `:75-78`: "No estimate for any family is computed
here or anywhere". `ForbiddenCostArithmeticTest.kt:33-38` sweeps this package
for the same reason.

### Any SAI/EFC arithmetic?

A case-insensitive grep for
`student aid index|\bSAI\b|\bEFC\b|expected family
contribution` over
`service/`, `db/`, `college/`, `rest-server/`, `public-web/` main sources and
`prompts/` finds only copy:
`service/src/main/kotlin/ed/unicoach/coaching/aid/FafsaProcessCopy.kt:272, 387,
392, 398`
(what the FAFSA process does), `PolicyParameter.kt:12` (the floor is a
"dimensionless index"), `prompts/coach-system-prompt.md:233` (route the question
to the tool). **No arithmetic anywhere.**

**What C5 would touch:** a migration adding household size and asset fields (+
statuses, iff-CHECKs, versions trigger — DDL gate); `MoneyProfile`,
`MoneyProfileEdit`, `MoneyProfileUpsert`, `MoneyProfilesDao`, the REST routes,
`MoneyProfileChatTool` (a new `PrecisionOffer` member — `offerCopy` is
exhaustive, `CollegeCostChatTool.kt:1321-1358`) and RFC 270's web page; a
federal-formula seed; a **new computing service** that contradicts the stated
contract at `FederalAidPolicyService.kt:132-136` and the never-store list; a
fourth `NetPrice` case (the sealed hierarchy at `CollegeCostService.kt:115-161`
is exhaustive at every renderer); prompt copy. **Largest by far**; the only
candidate that puts our own number in front of a family.

---

## 7. Shape

### The Family Cost Report after brief 0009

`public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt:625-661`
renders, in order: `h1` (`:626`), the live line (`:627`), **`assumptions`**
(`:628`) — which prints `ComparisonBasis.statements` **verbatim** "above the
table" (`:812-838`: "The assumption lines above the table ... rendered VERBATIM
from [ComparisonBasis.statements], because they are the same sentences the coach
must say in chat") — then the summary table (`:636`), the per-school sections
(`:644-651`), the kinds-of-number section (`:655`) and the sources section
(`:661`). So the page, post-0009, is **provenance first, table second**, the
same order as the coach. `concise/07/quiet-provenance`, which would have
collapsed the sources block, was cancelled
(`product/0009-say-it-once/spec.md:964-966`).

The statements' order is fixed in
`service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt:74-88`:
population, residency, blended-figure basis, living arrangement, at-home
assumption, academic years, aid — "The list exists so a renderer cannot
hand-pick a subset" (`:64`).

### Is there a prompt rule that puts provenance before the table?

Yes, two, and a test pins them:

- `prompts/coach-system-prompt.md:167`: "say those five lines first, as ordinary
  sentences above the table, never as a note at the bottom."
- `:178`: "Where the tool gives a comparison_basis, those five sentences still
  come first as the rule above says, and the figures lead everything after
  them".
- `SystemPromptCatalogTest.kt:1533-1545` asserts the served comparison paragraph
  `contains("say those five lines first") && contains("above the
  table")`
  ("v11's whole point: the assumptions are said as ordinary copy above the
  table, never as a note beneath it"); `:1605-1620` asserts the served
  answer-shape paragraph
  `contains(COMPARISON_BASIS_KEY) && contains("still come
  first")` ("the
  committed comparison rule (RFC 151) outranks the new ordering rule"). Both
  read the **served** body (`paragraphAt` → `servedBody`, `:622-626, 683`), not
  a historical version.

**What C6 would touch:** `prompts/coach-system-prompt.md:167, 176-178` (and
`:185`, which keeps year and source beside the figure), regenerated into a v52
seed by `bin/prompt-seed`; the two `SystemPromptCatalogTest` assertions above
(they are the prompt half of RFC 151's decision and must be rewritten, not
deleted); `ComparisonBasis.statements` is unchanged (the sentences move, they do
not go). If the page should match, `CostReportPage.kt:628/636` swap. The
"follow-up that changes the answer most" half is `PrecisionOffer` declaration
order (`CollegeCostService.kt:1118-1129`) plus prompt `:27, 199-200` — and, for
a household-size question, a C5 field. Size: **copy-only** for the order;
**enum-order decision** for the follow-up.

---

## Summary table

| Candidate                                | Foundation strength                                                                                                                                                                                                                                                                                         | Smallest landable slice                                                                                                                                                                                                             | Trap found                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **C1 fresher prices**                    | **partial** — `academic_year` is keyed (`0083:198-200`), the loader already serves two publishers at two years, the fetch/ingest pair is parameterised by one constant                                                                                                                                      | Re-run `bin/fetch-scorecard` and move the dictionary CSV + the two constants (`CanonicalMoneyLoader.kt:1691, 1700`) to the new release; separately bump `IpedsChargeVocabulary.SURVEY_YEAR` + `bin/fetch-ipeds` + four fixture pins | `ScorecardDictionaryPinTest.kt:298-313` **fails by design** when IC_AY's newest year ≥ the Scorecard's: the two then share a `price_figures` key and `ORDERED_SOURCES` (`CanonicalMoneyLoader.kt:79-82`) hands it to IC_AY — a precedence decision, not a bump. The IPEDS CSVs are not committed (`.gitignore:36-43`), so the "seed" is a network fetch. The school's-own-page figure is a new `MoneySource` + `CHECK` migration + fetcher. |
| **C2 exchange tier**                     | **partial** — tiers, `tuition_applicable`, the `assumed_by_unicoach` line origin and the awards corpus's `residency_basis` keying all exist; no roster, no derivation                                                                                                                                       | A seeded WUE roster table (DDL approval) + one derived, labelled line in `coaching/costs` with its own sentence, emitted beside `out_of_state`, + one prompt paragraph                                                              | None forbids it: `ForbiddenCostArithmeticTest.kt:115-119` matches only `-`; `MoneyPlan.kt:271-273` is the multiplication precedent. The loader may not compute it (`CanonicalMoneyLoader.kt:95-97`). The awards `PROVENANCE.json:137-138` already records why a WUE figure cannot live in an integer per-year column — the roster must carry the condition as copy. Constraint 5 (no recommendation) governs the sentence.                  |
| **C3 next step at out-of-state schools** | **partial / none by part** — calculator link: HD column exists, nothing loads it; merit grid: service carries it (`CollegeCostService.kt:444`), chat tool does not emit it, none of the four schools has a row; nonresident need policy: **not held** (`aid_policy.csv` has 22 facts, none about residency) | One `college_ipeds` column for `NPRICURL` (migration + `IpedsLoader.mapHd`) + emit `automaticAwards` from `CollegeCostChatTool` + one prompt line beside the withheld-figure sentence                                               | The brief's third fact does not exist in the tree; a slice promising it is a new CDS section or school-page source. Coverage of the awards corpus is 40 colleges, 0 of the four schools in question.                                                                                                                                                                                                                                        |
| **C4 four-year total**                   | **strong** — `MoneyPlan.of` + `payloadOf` are reusable as-is (`MoneyPlan.kt:58-87, 271-273`; `FamilyMoneyPlanChatTool.kt:130`)                                                                                                                                                                              | A student-side `money_plan` tool wrapping `MoneyPlan.of` over `CollegeCostService`, one registry line, one prompt paragraph                                                                                                         | Length is a flat 4 from `HD.ICLEVEL` (`CollegeLevel.kt:16`); no per-program length. Putting it on the cost tool instead would touch the load-bearing emit order (`CollegeCostChatTool.kt:609-623`).                                                                                                                                                                                                                                         |
| **C5 aid inputs + SAI estimate**         | **none** — no household size, no assets (schema grep), no SAI arithmetic anywhere; `FederalAidPolicyService.kt:132-136` states the opposite contract                                                                                                                                                        | None small; the first honest step is the two profile fields and their asks, with no estimate                                                                                                                                        | A computed family figure breaks RFC 159's "computes NOTHING" contract and the never-store list; `NetPrice` is sealed and exhaustive at every renderer (`CollegeCostService.kt:115-161`); `ForbiddenCostArithmeticTest` sweeps `coaching/aid`.                                                                                                                                                                                               |
| **C6 table first, provenance after**     | **strong** for the order (copy), **partial** for the follow-up (enum order)                                                                                                                                                                                                                                 | Edit `prompts/coach-system-prompt.md:167, 176-178`, regenerate the seed, rewrite the two catalog assertions                                                                                                                         | `SystemPromptCatalogTest.kt:1541, 1617` pin "above the table" / "still come first" **in the served body** — a reorder is red until they are rewritten. `concise/07` is cancelled (`0009/spec.md:964-966`), not open, so the page's order is a settled decision to revisit, not an open slice to finish. The report page prints the same statements above its table (`CostReportPage.kt:628, 636`).                                          |
