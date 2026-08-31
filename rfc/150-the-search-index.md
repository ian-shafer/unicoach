# RFC 150 — The search index

Status: proposed Product brief: `product/0004-college-search-index`, slice
**`search/03b/the-index`** (S3 proper). Base: `main@848b14eb`. Lands on top of
RFC 147 (`search/03a/published-codebooks`), which supplies the reference tables
this slice resolves words against.

## Why this exists

Everything a student would search on is now in the database and none of it is
searchable. `CollegesDao.search` filters twelve `colleges` columns and joins
`college_programs` for a CIP prefix (`CollegesDao.kt:429-534`). The IPEDS
attribute table RFC 144 landed — religion, ROTC, study abroad, housing,
athletics, Carnegie class, test policy — is read by nothing on the serving path.
The 6-digit program census RFC 144 landed is read by nothing on the serving
path. The 1,710 CIP titles RFC 147 landed are read by nothing on the serving
path (`CodebooksDao` exposes typed reads for exactly two tables,
`CodebooksDao.kt:323` and `:338`).

And the one field-of-study filter that does work asks a language model to guess
a federal code. `cipPrefix` is the single free-form code string left in the
shared schema (`CollegeQueryVocabulary.kt:57-64`); "I want to study nursing"
becomes a prefix by model guess, validated against nothing, matched against the
Scorecard `college_programs` table. `5116` — the old nursing series — returns
zero codes in the 2023 vocabulary, and nothing today would say so.

This slice builds the derived table that answers "small public schools in Maine
with a literature program" from one row per college, gives the taxonomy that
turns _literature_ into a set of real CIP codes, and repoints both search entry
points at it. The old query paths are deleted, not kept alongside.

## Decisions

**D47 — the index is one derived table, rebuilt whole in a new ingest phase.**
`college_search_index` is created by an ordinary migration (brief 0004 D15): no
`version` column, no history trigger. It is derived state, and its history is
`college_index_build`, exactly as the schema comment on `college_ipeds` already
says of the reference tables (`0055...sql:3-5`).

It does, however, carry a real foreign key:
`college_id UUID NOT NULL PRIMARY KEY REFERENCES colleges (id) ON DELETE
CASCADE`.
Derived is not the same as unconstrained. The reference costs one index probe
per inserted row on a table that is written exactly once per ingest, and it buys
the one defect nobody would ever catch by reading a result: an index row
pointing at a college that no longer exists, served as a match, joined for
payload, and returning nothing. `ON DELETE CASCADE` matches `college_name_words`
(`0056...sql:101-106`) and `college_programs_census` (`0055...sql:92-112`), the
two derived tables that already key on `colleges.id`.

The rebuild is unaffected. DELETE-then-`INSERT ... SELECT` runs inside one
transaction against a `colleges` table the ingest itself has finished writing;
the DELETE removes every child row before any parent is referenced again, and
the INSERT selects its `college_id` values **from `colleges`**, so every key it
writes exists by construction. The foreign key can only fire if a concurrent
transaction deletes a college mid-rebuild — which the ingest's one-writer
discipline does not do, and which would be a fault worth the abort.

It is rebuilt by a new `phase("search-index")` in
`CollegeScorecardLoader.ingest`, slotted between the existing `name-words` phase
(`CollegeScorecardLoader.kt:561`) and `provenance` (`:589`), in one transaction:
DELETE, then `INSERT ... SELECT`, then the percentile `UPDATE`, then `ANALYZE`,
returning the row count. That is `CollegesDao.rebuildNameWords`
(`CollegesDao.kt:736-753`) in shape and in transaction discipline.
`METHOD_VERSION` goes 4 to 5 (`CollegeScorecardLoader.kt:1245`).

**D48 — provenance: `index_rows` is already taken, so name it honestly.**
_[Presented at the gate; default below.]_ RFC 146 repurposed
`college_index_build.index_rows` for the `college_name_words` rebuild count
(`CollegeScorecardLoader.kt:597`), so the 0052 comment promising it is "NULL
until the S3 derived table arrives" is now false. **Default:** migration 0064
renames `index_rows` to `name_words_rows`, adds
`search_index_rows INTEGER
NULL`, and deletes the stale comment. One column, one
meaning. **Alternative:** leave `index_rows` alone and add `search_index_rows`
beside it, accepting a column whose name describes a different table.

**D49 — the taxonomy is an authored, committed file validated against the CIP
table.** `db/data/subjects.json`, an authored array on the
`db/data/college-aliases.json` precedent (brief 0004 D16), loaded into a
`subjects` table by its own `subjects` ingest phase, running immediately after
`codebooks`. Each subject carries `slug` (the shared `slug` DOMAIN from
`0060...sql:51-53`), `name`, and `cip_prefixes[]` of 2, 4 or 6 digits
canonicalized by the existing `CipPrefix` parser. **Validation is fatal at
ingest**, per RFC 147 D43: a prefix matching zero rows in `cip_codes` aborts the
run and names the prefix, before any write, like every other loader
(`CollegeScorecardLoader.kt:494-511`). `db/data/*.json` is under no formatter
today — `bin/common:106` limits `deno fmt` to `**/*.md` and `.claude`, and
`college-aliases.json` does not currently pass `deno fmt --check`. This slice
does not widen the format gate; `subjects.json` is written in the two-space,
trailing-newline style the generator already emits for `codebooks.json`
(`bin/fetch-codebooks:826-830`).

**D50 — the taxonomy content is Ian's, and one spec example is wrong.**
_[Presented at the gate; default below.]_ Ian reviews the file itself (brief
0004 D16). Measured against the committed codebook, the spec's literature
mapping `{2301, 2313, 2314, 160104}` is wrong on one prefix: `2313` is
Writing/Rhetoric — `231301` "Writing, General" through `231399` "Rhetoric and
Composition/Writing Studies, Other", five codes — not literature. **Default:**
literature is `{2301, 2314, 160104}`, thirteen codes minus five, so eight real
CIP codes; writing and rhetoric becomes its own subject. **Alternative:** keep
the spec mapping and fold composition into literature.

**There is no size target.** Ian's ruling at this gate removes the spec's
"~60-100 subjects": the file is unlimited, and there is no cap in this RFC, none
in the schema, and none in the loader. `product/0004-college-search-index/`
still carries the old phrase; it is superseded by this ruling, and the brief
records the supersession as a new numbered decision at close-out.

What bounds the file is the data, not taste. Measured from
`db/data/codebooks.json`: **1,710** six-digit CIP codes, **405** distinct
four-digit series, **38** two-digit families. A taxonomy with one subject per
thing a student actually says therefore tops out in the low hundreds — it cannot
usefully exceed the vocabulary it partitions. A subject matching no college in
the census is allowed — that is a silence, and the tool reports it as zero
matches. A subject matching no CIP _code_ is fatal.

**D51 — subject expansion is materialised, not joined at query time.** The
rebuild expands each college's 6-digit `college_programs_census` codes against
the taxonomy prefixes and stores `subject_slugs TEXT[]` and `cip_codes TEXT[]`
on the index row, both GIN-indexed. The taxonomy is small and static between
builds; a query-time prefix join over the census on every search is precisely
what the index exists to avoid, and `college_programs_census_cip_idx` is a plain
btree with no `text_pattern_ops`, so a left-anchored `LIKE` cannot use it under
the deployed collation anyway. The consequence is stated plainly: **changing the
taxonomy requires a rebuild.** Editing `subjects.json` and restarting the server
changes nothing until `bin/ingest-colleges` runs.

**D52 — four percentile columns, computed over the default universe only.**
Brief 0004 D20 as ruled: enrollment, admission rate, SAT average, net price —
character, not outcomes. Outcome percentiles stay dropped. Each is
`PERCENT_RANK()` over the rows in the **default searchable universe** — all
three of its axes, from the one definition `search` itself uses
(`is_active AND is_four_year IS NOT FALSE AND sector IS DISTINCT FROM
'administrative_unit'`)
— that have a non-null input; every other row is NULL. A percentile taken
against the 2-year and for-profit rows describes a corpus no student is
searching, and S4's distance would inherit the distortion. The columns take the
unit suffix the landed convention requires for a 0..1 quantity:
`undergrad_enrollment_percentile_share`, `admission_rate_percentile_share`,
`sat_average_percentile_share`, `net_price_percentile_share`. These four are the
only index columns that exist in no source table, which is why D60 keeps them:
they are a computed fact of the build, not a copy of anything.
`sat_average_percentile_share` is the one whose input column,
`colleges.sat_average_equivalent_score`, is not itself carried — nothing filters
or sorts on it — so the percentile pass reads it from `colleges`.

**D53 — one search path; both entry points repoint.** Brief 0004 D17.
`CollegesDao.search` reads `college_search_index` and nothing else: the
`college_programs` join (`CollegesDao.kt:489-495`) and every `colleges` filter
clause (`:433-487`) are **deleted**, not kept in parallel. `searchByName` keeps
the RFC 146 matching CTE over `college_name_words` and `one_keystroke_off()`
verbatim — that is name matching, not filtering — and takes its substring arm
and its enrollment tiebreak from the index, projecting the returned summary
fields from `colleges` (D60). The REST contract does not change:
`GET /api/v1/colleges`, `PublicCollegeSummary.id` (not `college_id` — see the
comment at `PublicCollegeSummary.kt:8-23`), `api-specs/openapi.yaml:831-880`,
and `ios-app/UnicoachiOS/CollegeListClient.swift:69` all see the same fields
under the same names. The `FitLensService` interface does not change either: it
keeps calling
`collegeSearchService.search(query.copy(limit = config.searchLimit))`
(`FitLensService.kt:266`).

One casualty is named rather than papered over. `credential_level`
(`CollegeSearchTool.kt:65-73`, `CollegeQuery.kt:33`, refused without a
`cipPrefix` at `CollegeSearchService.kt:28-35`) is served today by
`college_programs.credential_level`. The index derives its programs from
`college_programs_census`, which the ingest filters to `AWLEVEL = 5`,
`MAJORNUM = 1` (`IpedsLoader.kt:77`, `0055...sql:88-91`) — bachelor's first
majors only. The column exists there but is constant, so the filter would be a
tautology for "bachelors" and a falsehood for everything else.
`credential_level` is therefore **removed** from `search_colleges` and from
`CollegeQuery`, along with the pairing rule that guarded it. It returns when the
census carries more than one award level.

**D54 — new filters speak words, through the one shared vocabulary.** RFC 147
D45 left exactly one description of the filter schema, `CollegeQueryVocabulary`
(`schemaProperties` `:55-115`, `parse` `:126-184`, `FIELD_NAMES` `:299-313`). It
is extended — never copied — with `subject`, `test_policy`,
`religious_affiliation`, `carnegie_class`, `carnegie_size`,
`athletic_association`, and the tri-state `has_rotc`, `has_study_abroad`,
`has_housing`. Every coded filter takes a word from the 0060 reference tables
and — after D61 — **binds that word directly**, because the index stores it:
there is no code lookup at query time and no code-to-word rendering on the way
out. **No bare source code reaches a tool result** (brief 0003, RFC 143) stops
being a boundary convention and becomes a property of the schema.

`Codebook.load` still grows past the two tables it reads today
(`Codebook.kt:226-227`), but by less than the first draft claimed: what it needs
is the closed **slug list** per domain, for advertising the vocabulary and
rejecting an unknown word, which is one generic read over the existing
`CodebookTable` allowlist rather than six typed row readers. The detail is under
"The tool contract". `cipPrefix` stays as a free-form escape hatch, now
validated against `cip_codes`; `subject` is what the prompt teaches.

**D55 — every filter declares its treatment of unknowns and reports the count.**
Brief 0004 D11. The tool response carries `total_matches` (already there,
`CollegeSearchTool.kt:104`), `excluded_unknown` as per-filter counts, and
`source_years`. Unknown is never silently "no": a filter over a column that can
be unknown — NULL, or an empty array where absence of reporting is an empty set
— excludes those rows and reports how many it excluded — "142 matched; 61
unjudgeable — admission rate unreported". The counts are aggregate
`FILTER (WHERE ...)` expressions added to the existing single count statement
(`CollegesDao.kt:527-540`), not N extra round trips. Results stay at 25 or fewer
with `sort_by` (brief 0004 D21).

`source_years` is **kept**, and read at result time rather than stored. D60
takes the two vintage columns off the index, so the payload join over the
returned rows — at most 25 — also reaches `college_ipeds.survey_year` and the
`max(survey_year)` of the row's `college_programs_census` entries. That is the
honest answer, because it describes the rows actually returned; a stored per-row
copy of a per-build constant would be the same defect D48 is fixing.

**D56 — the default universe is a default, not a wall.** Brief 0004 D2/D18.
`is_active = TRUE AND is_four_year IS NOT FALSE AND sector IS DISTINCT FROM
'administrative_unit'`,
all three overridable per call. Unknown level is **included**; inactive is not;
an unknown sector is included, which is what `IS DISTINCT FROM` buys over `<>`.
The acceptance query returns the five UMaine campuses and not the System Central
Office, and that exclusion comes from RFC 144's `HD.SECTOR` — a system office is
an administrative unit, not a school — never from a name heuristic. D61b carries
that fact as our own word, not as the raw code.

**D57 — RFC 147's deferred `colleges` codebook constraints stay open.** RFC 147
D46 deferred tightening `colleges.locale` from a range check admitting codes
IPEDS never emits (`colleges_locale_range_check`, `0015...sql:68`) to this
slice, and the same argument reaches `colleges.state`, which carries only
`colleges_state_length_check CHECK (length(state) = 2)` (`0015...sql:40`, `:65`)
while `us_states` is keyed by `usps_code` — exactly what the column stores.

The corpus measurement this slice made the constraints conditional on
**passed**: over the real 6,273-row snapshot every stored locale code has an
`nces_locales` row and every stored state code has a `us_states` row. That is
not the blocker. The blocker is the premise underneath the foreign key.
`us_states` and `nces_locales` are **ingest-loaded** reference tables — empty in
a migrated-but-never-ingested database, and truncated before every test in
`CollegeScorecardTestBase`. A foreign key on `colleges.state` or
`colleges.locale` therefore makes the `institutions` phase, and every direct
`colleges` insert, fail against an unseeded codebook table. It turns "the
`codebooks` phase runs first" from an ordering into a hard precondition of
writing any college row at all, which is a larger change than D46 argued for and
not one this slice makes in passing.

So `colleges_locale_codebook_fkey` and `colleges_state_codebook_fkey` do not
ship, `colleges_locale_range_check` and `colleges_state_length_check` stay
exactly as 0015 wrote them, and `college_search_index.state` carries no foreign
key either — its soundness argument was "satisfiable by construction", and that
depended on the `colleges.state` constraint landing. This is an **open operator
decision**, reopened as its own slice with the measurement now on the record,
not a requirement this slice satisfied.

**D58 — the door: the coach, taught to use subjects and report counts.**
Reachability is part of this slice. Migration 0065 seeds coach system prompt
**v10** on the additive shape of 0058, with the rollback knob
`COACHING_SYSTEM_PROMPT_VERSION=v9` and the `service.conf` pin moved to v10. The
appended paragraph teaches: search by **subject**, never by a raw code; always
report `total_matches` and the excluded-unknown counts, in words ("61 schools
could not be judged — they do not report an admission rate"); never state a
source code to a student. Brief 0003's money vocabulary applies verbatim to any
price the tool returns — tuition and fees, housing and food, the published
price, a financial aid offer, never loans subtracted from a price. The fit-lens
query prompt is at v3 (`0061.seed-fit-lens-query-prompt-v3.sql`) and carries no
mention of CIP or fields of study at all; it gets a v4 **if and only if** its
schema changed in a way v3's body contradicts. That is checked against the file,
not assumed.

**D59 — reproducibility is an assertion, not a hope.** Dropping and re-ingesting
the same snapshot at the same `method_version` reproduces the index. This is a
test that rebuilds twice and compares **every column**, with no exclusions — D60
removes `build_id`, which was the one column that would have had to be exempted.
Ordering is deterministic everywhere — every `array_agg` carries an explicit
`ORDER BY` — and no row contains `NOW()`.

**D60 — the index is a filter surface, not a copy of `colleges`.** Ian's ruling
at the approval gate: a column earns its place on the index only if something
**filters, sorts, or indexes** on it. Everything else is payload and is read
from the source of truth at result time. Thirty columns survive that test; the
first draft carried forty-six.

Cut as payload: `city`, `sat_average_equivalent_score`,
`net_price_per_year_income_q1..q5_usd`, `cost_of_attendance_per_year_usd`,
`tuition_and_fees_in_state_per_year_usd`,
`tuition_and_fees_out_of_state_per_year_usd`,
`median_earnings_10y_after_entry_usd`, `median_debt_at_completion_usd`,
`pell_share`, `housing_capacity_headcount`, `disability_band`,
`registered_disability_percent`, `application_fee_usd`, `football_conf`,
`ipeds_survey_year`, `programs_census_survey_year`. None of them appears in a
`WHERE` clause, in `orderBy` (`CollegesDao.kt:549-556`), or in the D54 filter
table. `sat_average_equivalent_score` is the instructive case: it feeds a
percentile and nothing else, so the percentile column stays and the raw column
goes back to `colleges`.

`build_id` goes too, on its own reasoning. The whole table is one build — every
row would carry the same UUID — and `college_index_build.search_index_rows`
already records that build's relationship to this table. A per-row stamp of a
per-table constant is exactly the misnaming D48 exists to fix. Its removal also
means the rebuild no longer needs a second pass to stamp a value it could not
know at insert time, and D59's comparison loses its one exclusion.

This is not a saving in bytes; it is a saving in **truth**. Sixteen columns of
`colleges` copied into a second table are sixteen chances for the two to
disagree between a schema change and the next ingest. The architectural
consequence is stated in full under "The read path" below: filtering and
counting touch the index alone, so the hot path carries no join at all, and only
the returned page reaches `colleges`.

**D61 — the derived index stores slugs, not codes.** Ian's second ruling. RFC
147 landed every codebook reference table keyed by
`slug slug NOT NULL PRIMARY KEY` with the publisher's
`code SMALLINT NOT NULL UNIQUE` beside it (`0060...sql:65-66`, `:129-131`,
`:156-158`, `:183-185`, `:212-214`, `:238-240`, `:292-294`). `colleges` and
`college_ipeds` keep storing the raw code — that is CLAUDE.md's rule, a
source-defined code is stored as the source publishes it. `college_search_index`
is **derived**, is under no such obligation, and storing a raw code there only
forces a code-to-word mapping at the tool boundary that the reference tables
already do better. So every coded index column with a landed slug-keyed
reference table holds the **slug**, typed with the `slug` DOMAIN, with a real
foreign key:

| index column            | type     | references                        |
| ----------------------- | -------- | --------------------------------- |
| `state`                 | `TEXT`   | `us_states (usps_code)`           |
| `region`                | `slug`   | `ipeds_regions (slug)`            |
| `locale`                | `slug`   | `nces_locales (slug)`             |
| `carnegie_class`        | `slug`   | `carnegie_2021_basic_classes`     |
| `carnegie_size`         | `slug`   | `carnegie_2021_size_settings`     |
| `religious_affiliation` | `slug`   | `religious_affiliations (slug)`   |
| `test_policy`           | `slug`   | `admission_test_policies (slug)`  |
| `athletic_associations` | `slug[]` | resolved, but see the array limit |

Two columns are renamed, because they no longer hold IPEDS's variable — they
hold our vocabulary. `carnegie_basic` becomes `carnegie_class`; `rel_affil`
becomes `religious_affiliation`; `athletic_assoc` becomes the plural
`athletic_associations`, which is what a `slug[]` holds.

**The array has no foreign key, and cannot.** Postgres does not constrain array
_elements_, so `athletic_associations slug[]` is unreferenced by the database.
What replaces the constraint is the rebuild: the `INSERT ... SELECT` resolves
each ASSOC ordinal through `athletic_associations` before it lands, so an
ordinal with no codebook row cannot enter the column at all. A test asserts the
shape — every element of every row's array is a slug present in the reference
table — because the database will not.

**Join discipline, stated once and binding.** The rebuild resolves code to slug
with **LEFT JOINs**, never inner ones. A college is never dropped from the index
because one of its codes has no codebook row; the column goes NULL, the college
stays searchable, and RFC 147 D46's unknown-code report is the thing that names
the gap. An INNER JOIN here would silently delete colleges from search, which is
the worst failure this table can have and the hardest to notice.

**Two columns have no reference table, so this slice authors their
vocabularies.** Both are settled — Ian ruled on them at the gate — and both
follow CLAUDE.md's own-enumeration pattern: `TEXT` plus `CHECK IN (...)` in the
schema, plus exactly one Kotlin `enum class` in
`db/src/main/kotlin/ed/unicoach/db/models/`. Neither is a published codebook, so
neither belongs in RFC 147's reference tables.

**D61a — `control` stays words, not a slug.** There is no `control` codebook
table; the vocabulary is the Kotlin `InstitutionControl` enum
(`InstitutionControl.kt:21-23`), whose labels are underscored — `public`,
`private_nonprofit`, `private_for_profit` — and the `slug` DOMAIN forbids an
underscore (`^[a-z0-9]+(-[a-z0-9]+)*$`, `0060...sql:51-53`). Those three words
are already the tool's public vocabulary and the value that goes on the wire, so
hyphenating them would be a user-visible contract change this slice has no
business making. The index stores
`control TEXT NOT NULL CHECK (control IN ('public', 'private_nonprofit',
'private_for_profit'))`,
matching the enum exactly. The enum already exists and is not touched.

**D61b — `sector` gets its own enumeration, on `control`'s model.** The index
stores the sector as **our** word, not IPEDS's number, and this slice authors
the enum that does not exist yet: `InstitutionSector` in
`db/src/main/kotlin/ed/unicoach/db/models/`, carrying `(code, value)` and a
`fromValue` companion exactly as `InstitutionControl` does, plus a `fromCode`
for the rebuild. Underscored words, so the two enums speak one dialect.

The value list is taken from the authoritative code set, not invented:
`SECTOR_CODES = (0..9).toSet() + 99` (`IpedsLoader.kt:957-963`), mirrored by
`college_ipeds_sector_domain_check` (`0055...sql:53-56`). That is **eleven**
values — `10..98` are values IPEDS does not publish, and accepting them would
store junk indistinguishable from a real sector:

| code | word                                    |
| ---- | --------------------------------------- |
| 0    | `administrative_unit`                   |
| 1    | `public_four_year`                      |
| 2    | `private_nonprofit_four_year`           |
| 3    | `private_for_profit_four_year`          |
| 4    | `public_two_year`                       |
| 5    | `private_nonprofit_two_year`            |
| 6    | `private_for_profit_two_year`           |
| 7    | `public_less_than_two_year`             |
| 8    | `private_nonprofit_less_than_two_year`  |
| 9    | `private_for_profit_less_than_two_year` |
| 99   | `unknown`                               |

**`99` and NULL are different things, and are stored differently.** `99` is the
publisher saying "sector unknown (not active)" — a reported fact — and it maps
to the explicit word `unknown`. A college with **no** `college_ipeds` row at all
leaves the column NULL — an absence, nothing was reported either way. This is
the same distinction RFC 148 D10 drew for the honest denominator: a field the
source names as unreported is not the same as a field the source never reached.
The pattern is cited, not re-argued.

Sector earns its place under D60 because the **default universe uses it**: the
acceptance criterion — return the five UMaine campuses, not the System Central
Office — is `sector IS DISTINCT FROM 'administrative_unit'`, and
`IS DISTINCT
FROM` is what keeps a NULL-sector college in the universe rather
than filtering it out on an absence.

It is **not** advertised as a model-facing filter. Sector is `control` crossed
with institution level, both of which the model can already say, so publishing
it would give the model two ways to express one thing and two ways to contradict
itself. It is a column the default universe reads and the payload may render; it
is not a word in the tool schema.

## Detailed Design

### `db/data/subjects.json`

An authored array, one object per subject, grouped by family and unique by
`slug` — a human reviews this file, and a slug sort would scatter every family:

```json
[
  {
    "slug": "literature",
    "name": "Literature",
    "cip_prefixes": ["2301", "2314", "160104"]
  }
]
```

`cip_prefixes` are digits only, 2, 4 or 6 of them, dot-free — the canonical form
`CipPrefix.parseOrNull` already produces. The loader parses the whole file,
canonicalizes every prefix, and checks each one against the parsed `cip_code`
domain before the first write. A prefix matching zero codes is fatal and names
the prefix and the subject. A duplicate slug is fatal.

**The `synonyms` column was dropped at Ian's ruling, at the close of review.**
The design drafted a `synonyms[]` per subject and a fatal rule against a word
naming two subjects. Neither survives, for one reason: nothing reads a synonym.
D54 advertises the closed SLUG enum in the tool schema, so the model chooses a
slug and no query, loader or tool ever consults a synonym — the words were
authored, loaded, collision-warned and then ignored. The fatal rule could not
have shipped either: the taxonomy is deliberately hierarchical, so 44 words
collided by design and the rule would have refused the very file approved at the
gate. Synonyms return with the deferred free-text resolver below, from data
authored for it.

### Migration `0064`

RFC 149 landed `0062` and `0063` and took the coach prompt name `v9` while this
RFC was in flight, so this slice's two migrations are `0064` and `0065` and its
coach prompt is `v10`.

The DDL in `product/0004-college-search-index/spec.md:110-190` predates two
landed renames (`0057` unit_id to `ipeds_unit_id`, `0059` unit-last numeric
names) and RFC 147. It is stale; the column names below are the landed ones and
the spec's are not repeated.

```sql
-- The authored subject taxonomy, in loaded form. Reference data: no version
-- column, no history trigger (brief 0004 D15). Its history is the ingest.
CREATE TABLE subjects (
    slug         slug        NOT NULL PRIMARY KEY,
    name         TEXT        NOT NULL,
    cip_prefixes TEXT[]      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT subjects_cip_prefixes_nonempty_check
        CHECK (cardinality(cip_prefixes) > 0),
    -- A CHECK cannot contain a subquery, so the array is validated as one
    -- string: 2, 4 or 6 digits per element, comma-separated.
    CONSTRAINT subjects_cip_prefixes_format_check
        CHECK (array_to_string(cip_prefixes, ',')
               ~ '^([0-9]{2}){1,3}(,([0-9]{2}){1,3})*$')
);

CREATE TRIGGER trigger_03_enforce_subjects_updated_at
    BEFORE UPDATE ON subjects
    FOR EACH ROW EXECUTE PROCEDURE update_colleges_timestamp();

-- THE derived search table. Rebuilt whole by the `search-index` ingest phase;
-- never hand-written, never versioned. Thirty columns, every one of them
-- filtered, sorted or indexed (D60); payload is read from `colleges` at
-- result time. The key is a real FK to the source of truth (D47), and every
-- coded column holds OUR vocabulary, not the publisher's number (D61).
CREATE TABLE college_search_index (
    college_id                            UUID     NOT NULL PRIMARY KEY
        REFERENCES colleges (id) ON DELETE CASCADE,
    ipeds_unit_id                         INTEGER  NOT NULL,  -- ORDER BY tiebreak
    name                                  TEXT     NOT NULL,  -- NAME_ASC sort key
    search_text                           TEXT     NOT NULL,  -- the ILIKE arm
    -- coded columns: slugs with real FKs (D61). `state` carries no FK: its
    -- soundness argument was "satisfiable by construction", which depended on
    -- the matching constraint on `colleges.state` that D57 leaves open.
    state                                 TEXT     NOT NULL,
    region                                slug     NULL
        REFERENCES ipeds_regions (slug),
    locale                                slug     NULL
        REFERENCES nces_locales (slug),
    -- no codebook table; the InstitutionControl vocabulary, verbatim (D61a)
    control                               TEXT     NOT NULL
        CONSTRAINT college_search_index_control_check
        CHECK (control IN ('public', 'private_nonprofit', 'private_for_profit')),
    -- the default universe (brief 0004 D2/D18): derived here, raw at source
    is_active                             BOOLEAN  NOT NULL,
    is_four_year                          BOOLEAN  NULL,
    is_degree_granting                    BOOLEAN  NULL,
    -- our own enumeration of HD.SECTOR; NULL only when no IPEDS row exists,
    -- while the publisher's own "unknown" is the word `unknown` (D61b)
    sector                                TEXT     NULL
        CONSTRAINT college_search_index_sector_check
        CHECK (sector IN ('administrative_unit', 'public_four_year',
            'private_nonprofit_four_year', 'private_for_profit_four_year',
            'public_two_year', 'private_nonprofit_two_year',
            'private_for_profit_two_year', 'public_less_than_two_year',
            'private_nonprofit_less_than_two_year',
            'private_for_profit_less_than_two_year', 'unknown')),
    -- filter AND sort keys, all four, tri-state (brief 0004 D11)
    undergrad_enrollment_headcount        INTEGER  NULL,
    admission_rate_share                  DOUBLE PRECISION NULL,
    net_price_per_year_usd                INTEGER  NULL,
    completion_rate_150pct_4yr_share      DOUBLE PRECISION NULL,
    -- IPEDS attribute filters (D54): slugs and tri-state booleans
    test_policy                           slug     NULL
        REFERENCES admission_test_policies (slug),
    religious_affiliation                 slug     NULL
        REFERENCES religious_affiliations (slug),
    carnegie_class                        slug     NULL
        REFERENCES carnegie_2021_basic_classes (slug),
    carnegie_size                         slug     NULL
        REFERENCES carnegie_2021_size_settings (slug),
    has_rotc                              BOOLEAN  NULL,
    has_study_abroad                      BOOLEAN  NULL,
    offers_housing                        BOOLEAN  NULL,
    -- Postgres cannot FK array ELEMENTS; the rebuild resolves each ordinal
    -- through athletic_associations, and a test asserts the shape (D61).
    athletic_associations                 slug[]   NOT NULL DEFAULT '{}',
    -- program rollup and its taxonomy expansion (D51). cip_codes stays 6-digit
    -- codes: cip_codes is keyed by `code`, not by a slug, and these are a
    -- filter key that never reaches a result as a code.
    cip_codes                             TEXT[]   NOT NULL DEFAULT '{}',
    subject_slugs                         slug[]   NOT NULL DEFAULT '{}',
    -- percentiles over the DEFAULT universe only (D52). These four exist in no
    -- source table, which is why D60 keeps them.
    undergrad_enrollment_percentile_share DOUBLE PRECISION NULL,
    admission_rate_percentile_share       DOUBLE PRECISION NULL,
    sat_average_percentile_share          DOUBLE PRECISION NULL,
    net_price_percentile_share            DOUBLE PRECISION NULL,
    CONSTRAINT college_search_index_percentile_range_check CHECK (
        (undergrad_enrollment_percentile_share IS NULL
            OR undergrad_enrollment_percentile_share BETWEEN 0 AND 1)
        AND (admission_rate_percentile_share IS NULL
            OR admission_rate_percentile_share BETWEEN 0 AND 1)
        AND (sat_average_percentile_share IS NULL
            OR sat_average_percentile_share BETWEEN 0 AND 1)
        AND (net_price_percentile_share IS NULL
            OR net_price_percentile_share BETWEEN 0 AND 1))
);

-- Nine indexes beside the primary key. `college_id` is the PK, so it is
-- indexed by that; the FKs' parent sides need nothing new. `search_text` gets
-- no trigram index: 0056 dropped `colleges_search_text_trgm_idx` and the
-- `pg_trgm` extension with it, having measured the ILIKE arm as microseconds
-- over 6,273 rows, so `gin_trgm_ops` does not exist in this database and the
-- DDL would not compile.
CREATE INDEX college_search_index_subject_slugs_idx
    ON college_search_index USING gin (subject_slugs);
CREATE INDEX college_search_index_cip_codes_idx
    ON college_search_index USING gin (cip_codes);
CREATE INDEX college_search_index_athletic_associations_idx
    ON college_search_index USING gin (athletic_associations);
CREATE INDEX college_search_index_universe_idx
    ON college_search_index (is_active, is_four_year, sector);
CREATE INDEX college_search_index_state_idx ON college_search_index (state);
CREATE INDEX college_search_index_enrollment_idx
    ON college_search_index (undergrad_enrollment_headcount);
CREATE INDEX college_search_index_admission_rate_idx
    ON college_search_index (admission_rate_share);
CREATE INDEX college_search_index_net_price_idx
    ON college_search_index (net_price_per_year_usd);
CREATE INDEX college_search_index_completion_rate_idx
    ON college_search_index (completion_rate_150pct_4yr_share);


-- `name` gets no btree of its own: NAME_ASC scans the whole result set the
-- filters already reduced, and the low-cardinality slug columns (region,
-- locale, test_policy, religious_affiliation, carnegie_class, carnegie_size,
-- the three tri-state booleans) are cheaper to filter on a heap scan of 6,273
-- rows than through an index the planner would decline to use. Slug text
-- instead of SMALLINT costs a few tens of kilobytes across 6,273 rows; it is
-- not a consideration.
--
-- `control` is that same case and so gets no index either: three distinct
-- values over 6,273 rows, and a two-of-three `control = ANY (...)` selects
-- most of the table. `state` keeps its btree — ~59 values, ~2% of the rows for
-- one state, which is the selectivity a btree is for.


-- Provenance (D48). RFC 146 gave `index_rows` to college_name_words; say so.
ALTER TABLE college_index_build RENAME COLUMN index_rows TO name_words_rows;
ALTER TABLE college_index_build
    RENAME CONSTRAINT college_index_build_index_rows_nonneg_check
        TO college_index_build_name_words_rows_nonneg_check;
ALTER TABLE college_index_build
    ADD COLUMN search_index_rows INTEGER NULL,
    ADD CONSTRAINT college_index_build_search_index_rows_nonneg_check
        CHECK (search_index_rows IS NULL OR search_index_rows >= 0);
COMMENT ON COLUMN college_index_build.name_words_rows IS
    'Rows written by the college_name_words rebuild (RFC 146). NULL for every '
    'RFC 139-era row, written before that table existed.';
COMMENT ON COLUMN college_index_build.search_index_rows IS
    'Rows written by the college_search_index rebuild (RFC 150). NULL for '
    'every build row written before that table existed.';

-- RFC 147 D46's `colleges.locale` and `colleges.state` codebook foreign keys,
-- and the two shape checks they would subsume, are NOT part of this migration.
-- The reason is at D57.
```

`colleges_versions` keeps its unconstrained `locale SMALLINT` column
(`0023...sql:124`): a history table records what was stored, and a later
codebook edit must not be able to invalidate an old row.

### The rebuild

One function, `CollegesDao.rebuildSearchIndex(session): Result<Int>`, in the
caller's transaction, in four statements. There is no fifth: D60 removes
`build_id`, so nothing has to be stamped after the fact.

**1. Empty it.** `DELETE FROM college_search_index` — not `TRUNCATE`, which
takes an ACCESS EXCLUSIVE lock against live readers. That is the reasoning
already recorded on `rebuildNameWords` (`CollegesDao.kt:736-737`). The DELETE
also settles the foreign key (D47): every child row is gone before the INSERT
re-references a parent, and the INSERT draws its keys from `colleges` itself, so
inside this one transaction the constraint can never be the thing that fails.

**2. Fill it.** One `INSERT ... SELECT`, thirty columns. `college_ipeds` joins
by `ipeds_unit_id`; `college_programs_census` joins by `college_id` — the
derivation bridges both. **Every join is a LEFT JOIN**, both the source joins (a
Scorecard-only ingest has no IPEDS rows at all) and the seven code-to-slug
resolutions (D61): a code with no codebook row yields NULL, not a missing
college.

```sql
INSERT INTO college_search_index (
    college_id, ipeds_unit_id, name, search_text, state, region, locale,
    control, is_active, is_four_year, is_degree_granting, sector,
    undergrad_enrollment_headcount, admission_rate_share,
    net_price_per_year_usd, completion_rate_150pct_4yr_share,
    test_policy, religious_affiliation, carnegie_class, carnegie_size,
    has_rotc, has_study_abroad, offers_housing, athletic_associations,
    cip_codes, subject_slugs)
SELECT
    c.id,
    c.ipeds_unit_id,
    c.name,
    college_search_text(c.name, c.aliases),
    c.state,
    reg.slug,                     -- LEFT JOIN: unknown code -> NULL, not a drop
    loc.slug,
    ctl.label,
    -- absent IPEDS row means "not known to be closed", so TRUE
    (coalesce(ci.cy_active, TRUE) AND ci.death_year IS NULL
        AND ci.closed_at IS NULL),
    (ci.inst_level = 1),          -- NULL when unreported: unknown, not "no"
    ci.ug_offer,
    sec.value,                    -- our word for HD.SECTOR; NULL when no row
    c.undergrad_enrollment_headcount,
    c.admission_rate_share,
    c.net_price_per_year_usd,
    c.completion_rate_150pct_4yr_share,
    pol.slug, rel.slug, cbc.slug, csz.slug,
    ci.has_rotc, ci.has_study_abroad, ci.offers_housing,
    coalesce(aso.slugs, '{}'),
    coalesce(pr.cip_codes, '{}'),
    coalesce(sub.subject_slugs, '{}')
FROM colleges c
LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = c.ipeds_unit_id
LEFT JOIN ipeds_regions                reg ON reg.code = c.region
LEFT JOIN nces_locales                 loc ON loc.code = c.locale
LEFT JOIN admission_test_policies      pol ON pol.code = ci.test_policy
LEFT JOIN religious_affiliations       rel ON rel.code = ci.rel_affil
LEFT JOIN carnegie_2021_basic_classes  cbc ON cbc.code = ci.carnegie_basic
LEFT JOIN carnegie_2021_size_settings  csz ON csz.code = ci.carnegie_size
-- InstitutionSector is authored Kotlin, not a table: bound per row, or an
-- inline CASE over the eleven published codes whose arms a test pins.
LEFT JOIN LATERAL (SELECT sector_word(ci.sector) AS value) sec ON TRUE
LEFT JOIN LATERAL (
    SELECT array_agg(a.slug ORDER BY a.code) AS slugs
    FROM unnest(coalesce(ci.athletic_assoc, '{}')) AS ord
    JOIN athletic_associations a ON a.code = ord
) aso ON TRUE
LEFT JOIN LATERAL (
    SELECT array_agg(DISTINCT pc.cip_code ORDER BY pc.cip_code) AS cip_codes
    FROM college_programs_census pc
    WHERE pc.college_id = c.id
) pr ON TRUE
LEFT JOIN LATERAL (
    SELECT array_agg(DISTINCT s.slug ORDER BY s.slug) AS subject_slugs
    FROM subjects s
    WHERE EXISTS (
        SELECT 1 FROM college_programs_census pc
        WHERE pc.college_id = c.id
          AND EXISTS (SELECT 1 FROM unnest(s.cip_prefixes) p
                      WHERE pc.cip_code LIKE p || '%'))
) sub ON TRUE;
```

`ctl.label` and `sec.value` are the two resolutions with no reference table
(D61a, D61b): both are authored Kotlin enums, so each is either bound per row
from `InstitutionControl` / `InstitutionSector` or written as an inline `CASE`
over the published codes whose arms a test pins against the enum. A control code
the enum does not define **fails the rebuild** rather than storing NULL, because
`control` is NOT NULL and a school with no control is not a searchable school; a
sector code outside the eleven published values cannot reach the index, because
`college_ipeds_sector_domain_check` already refused it at ingest
(`0055...sql:53-56`).

The `athletic_associations` LATERAL is the array's replacement for a foreign key
(D61): an ordinal with no `athletic_associations` row is dropped by the inner
join _inside_ the LATERAL, so it never enters the column, while the college
itself is kept by the outer LEFT JOIN. That asymmetry is deliberate.

`death_year` is a real closure year or NULL: the loader maps DEATHYR's `-2`
("still alive") to NULL rather than to a year (`IpedsLoader.kt:490`).
`is_four_year` is deliberately `(ci.inst_level = 1)` with no `coalesce` — a
missing level is NULL, and D56's default includes it. `sector` follows the same
rule and one more: a college with no `college_ipeds` row gets NULL, while
`HD.SECTOR = 99` gets the word `unknown`, because the publisher reported
something (D61b).

**3. Percentiles.** One `UPDATE`, over the default universe only, four ranks
computed independently so a row missing one input still ranks on the others. The
universe CTE joins `colleges` for `sat_average_equivalent_score`, which D60 does
not carry on the index: it is the input to a percentile and nothing else.

```sql
WITH universe AS (
    SELECT i.college_id, i.undergrad_enrollment_headcount,
           i.admission_rate_share, i.net_price_per_year_usd,
           c.sat_average_equivalent_score
    FROM college_search_index i
    JOIN colleges c ON c.id = i.college_id
    -- The DEFAULT UNIVERSE, from its one home (D52/D56): a percentile must
    -- describe exactly the corpus a default search returns.
    WHERE i.is_active AND i.is_four_year IS NOT FALSE
      AND i.sector IS DISTINCT FROM 'administrative_unit'
),
enrollment AS (
    SELECT college_id,
           percent_rank() OVER (ORDER BY undergrad_enrollment_headcount) AS v
    FROM universe WHERE undergrad_enrollment_headcount IS NOT NULL),
admission AS (
    SELECT college_id, percent_rank() OVER (ORDER BY admission_rate_share) AS v
    FROM universe WHERE admission_rate_share IS NOT NULL),
sat AS (
    SELECT college_id,
           percent_rank() OVER (ORDER BY sat_average_equivalent_score) AS v
    FROM universe WHERE sat_average_equivalent_score IS NOT NULL),
price AS (
    SELECT college_id, percent_rank() OVER (ORDER BY net_price_per_year_usd) AS v
    FROM universe WHERE net_price_per_year_usd IS NOT NULL)
UPDATE college_search_index t
SET undergrad_enrollment_percentile_share = e.v,
    admission_rate_percentile_share       = a.v,
    sat_average_percentile_share          = s.v,
    net_price_percentile_share            = p.v
FROM universe u
LEFT JOIN enrollment e ON e.college_id = u.college_id
LEFT JOIN admission  a ON a.college_id = u.college_id
LEFT JOIN sat        s ON s.college_id = u.college_id
LEFT JOIN price      p ON p.college_id = u.college_id
WHERE t.college_id = u.college_id;
```

`percent_rank()` is deterministic under ties, so the result does not depend on
scan order (D59). Rows outside the universe are never touched and keep NULL.

**4. `ANALYZE college_search_index`**, inside the same transaction — permitted
there, unlike `VACUUM` — because the table was just emptied and refilled and the
planner's statistics otherwise describe the previous build. The function returns
the row count from step 2, which is what `search_index_rows` records.

### The read path

This is the architectural point of D60, so it is stated as a rule rather than
left to the SQL.

**Filtering and counting touch `college_search_index` alone.** Every `WHERE`
clause the vocabulary can build, every `FILTER (WHERE ...)` unknown count, and
every `ORDER BY` key resolves inside one table with no join at all. The hot path
— the one that reads 6,273 rows to find 142 — got narrower, not wider.

**Only the returned page reaches the source of truth.** After `LIMIT`, at most
25 rows join back:

```sql
... FROM ( <the filtered, sorted, limited index query> ) i
JOIN colleges c ON c.id = i.college_id
LEFT JOIN college_ipeds ci ON ci.ipeds_unit_id = c.ipeds_unit_id
LEFT JOIN LATERAL (
    SELECT array_agg(cc.title ORDER BY cc.code) AS titles,
           max(pc.survey_year)                  AS census_year
    FROM college_programs_census pc
    JOIN cip_codes cc ON cc.code = pc.cip_code
    WHERE pc.college_id = i.college_id
      AND pc.cip_code = ANY (?)      -- the filter's expanded code set
) t ON TRUE
```

`colleges` supplies `city` and every money and outcome field the payload
carries; `college_ipeds` supplies its `survey_year`, which is the `ipeds` key of
`source_years`; the LATERAL supplies the program titles and the census vintage.
Three joins over 25 rows, on primary and unique keys, in place of sixteen
duplicated columns.

**No code-to-word step remains for the filtered columns.** `region`, `locale`,
`state`, `control`, `sector`, `test_policy`, `religious_affiliation`,
`carnegie_class`, `carnegie_size` and `athletic_associations` come off the index
already in the vocabulary the result speaks, so the payload emits `i.region`
directly. Brief 0003's "no bare source code reaches a tool result" stops being a
convention the boundary enforces and becomes **structural**: there is no code on
the index to leak. `locale` is the one word the boundary still assembles rather
than emits, because `locale_type` and `locale_detail` are structure the payload
renders as a pair; `Codebook.localeOf` therefore survives, rekeyed by slug.

The count statement does **not** join any of this. It never returns a row.

### Phase position and provenance

```kotlin
val nameWords    = phase("name-words",    committedPhases) { rebuildNameWords() }
val searchIndex  = phase("search-index",  committedPhases) { rebuildSearchIndex() }
// unknown-code report (read-only, never fails the run)
val build        = phase("provenance",    committedPhases) { insertBuildRow(...) }
```

`search-index` sits at `CollegeScorecardLoader.kt:561`+1, after every row phase
has committed and before the read-only unknown-code report at `:575`.
`insertBuildRow` passes `nameWordsRows = nameWords` and
`searchIndexRows = searchIndex` (D48). `METHOD_VERSION` becomes 5
(`CollegeScorecardLoader.kt:1245`): the derivation is new, so a build row from
this ingest is not comparable to a build row from the last one.

The taxonomy loads as its own `subjects` phase, running immediately after
`codebooks`. It validates against exactly the `cip_codes` rows that phase just
wrote, so it can never run against a stale CIP vocabulary, and a dead prefix is
fatal before any build row exists. It is a phase rather than a step inside
`codebooks` because sharing that transaction would make `CodebookLoader.load`
know the taxonomy exists; the phase name is honest about the transaction
boundary the load actually has. `--subjects=PATH` joins `--codebooks=PATH` on
`bin/ingest-colleges` with the same shape: a repo default of
`$PROJECT_ROOT/db/data/subjects.json`, an explicitly-empty value fatal, the
caller's original argument forwarded as `--subjects-source=` for provenance
(`bin/ingest-colleges:224-225`, `:252-259`), and the digest recorded in the
build row's `sources` array.

### The tool contract

`CollegeQueryVocabulary` gains the fields below in `schemaProperties`, `parse`
and `FIELD_NAMES`. After D61 every one of them **binds the word itself**: the
slug the model says is the value the SQL binds, with no code lookup at query
time and nothing to convert on the way back out.

| field                   | words                                          | binds     | clause                                                                                                                              |
| ----------------------- | ---------------------------------------------- | --------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `subject`               | `subjects.slug` (the advertised enum)          | the slug  | `subject_slugs @> ARRAY[?]`                                                                                                         |
| `test_policy`           | `admission_test_policies.slug` (3)             | the slug  | `test_policy = ?`                                                                                                                   |
| `religious_affiliation` | `religious_affiliations.slug` (63)             | the slug  | `religious_affiliation = ?`                                                                                                         |
| `carnegie_class`        | `carnegie_2021_basic_classes.slug` (34)        | the slug  | `carnegie_class = ?`                                                                                                                |
| `carnegie_size`         | `carnegie_2021_size_settings.slug` (19)        | the slug  | `carnegie_size = ?`                                                                                                                 |
| `athletic_association`  | `athletic_associations.slug` (6)               | the slug  | `athletic_associations @> ARRAY[?]`                                                                                                 |
| `region`                | `ipeds_regions.slug` (10)                      | the slug  | `region = ?`                                                                                                                        |
| `locale_type` (+detail) | the two closed enums, expanded to locale slugs | slug list | `locale = ANY (?)`                                                                                                                  |
| `control`               | `InstitutionControl.label` (3)                 | the word  | `control = ?`                                                                                                                       |
| `has_rotc`              | boolean                                        | —         | `has_rotc = ?`                                                                                                                      |
| `has_study_abroad`      | boolean                                        | —         | `has_study_abroad = ?`                                                                                                              |
| `has_housing`           | boolean                                        | —         | `offers_housing = ?`                                                                                                                |
| `is_active`             | boolean, default `true`                        | —         | `is_active = ?`                                                                                                                     |
| `is_four_year`          | boolean, default "not false"                   | —         | `is_four_year IS NOT FALSE` by default; an explicit value is an ordinary filter (`is_four_year = ?`) and reports `excluded_unknown` |

`control` is the one axis with no codebook table behind it —
`InstitutionControl` IS the vocabulary — so `CollegeQuery` carries the enum
itself and the label is produced at the bind, where the SQL word is needed
anyway. A caller cannot hand the DAO a control word the vocabulary never
approved.

**The one cost that scales with the taxonomy is context, not query time.** Every
subject slug is advertised as an enum value in the `search_colleges` tool
definition, and that definition sits in the model's context on **every** call.
The arithmetic is small: 109 slugs render as roughly 1,711 characters, about 430
tokens. A few hundred subjects is affordable and buys a vocabulary the model can
see rather than guess at. If it ever stops being affordable, the escape hatch is
to stop inlining the enum and resolve free text server-side against slugs and
synonyms, with a named error listing near matches — the shape RFC 147 already
uses for an unknown region word. That is not done in this slice; it is recorded
under `## Deferred`.

**What `Codebook` still has to do, and what D61 deleted.** It still loads, and
it still grows — but the job changed. What it needs is a **slug vocabulary per
domain**: the closed word list that the schema advertises to the model and that
`parse` validates a word against. That is one generic read,
`CodebooksDao.slugs(session, table)` over the existing `CodebookTable`
identifier allowlist (`CodebooksDao.kt:460-477`), not six new typed row readers.
`nces_locales` keeps its typed read (`CodebooksDao.kt:338-340`) because
`locale_type`/`locale_detail` are structure, not a flat list: the two closed
enums expand to a **set of locale slugs**.

A vocabulary with **no values is not advertised at all**. `Codebook` names the
filter fields it carries no word for, the schema drops exactly those fields, and
the boot-time health check reports them — so the partially-loaded database an
ingest without the `subjects` phase leaves cannot look healthy while offering a
`subject` filter that refuses every word sent to it.

What is deleted is the whole code-facing half. `Codebook.regionCode(slug)`,
`Codebook.localeCodes(type, detail)` returning codes, and the output functions
`regionWord(code)` and `unknownLocaleWord(code)`
(`CollegeQueryVocabulary.kt:186-202`) have no caller left on the search path:
nothing needs a code, because the index holds none. `localeOf` is the exception
and is **rekeyed** by slug rather than deleted, for the same reason
`nces_locales` keeps its typed read: the payload still renders `locale_type` and
`locale_detail`. `ipeds_regions`'s typed read degrades to the generic slug read.
The `unknown (region [N])` rendering goes with them — an index row's `region` is
either a slug the codebook defines or NULL, enforced by the foreign key, so the
"code with no word" case it existed to describe cannot occur on this path.

`cipPrefix` keeps its schema property and its permissive parse
(`CollegeQueryVocabulary.kt:282-320`) but changes meaning: the prefix is
expanded against `cip_codes` to a 6-digit set and bound as
`cip_codes && ARRAY[...]`. A prefix matching no real CIP code is now a named
error — "5116 is not a CIP code in the 2023 vocabulary" — instead of a silent
empty result.

That refusal travels as a **domain outcome, not a fault**: `search` returns a
`CollegeSearchOutcome` — a page, or an `UnresolvableProgramFilter` carrying the
field, the word and the cause — and `Result.failure` stays reserved for the
database failing. The tool renders the refusal in the same plain
`{ "error": "..." }` shape an unknown `region` word gets, so a coach never tells
a family the search broke when the word simply is not in the vocabulary.

**A `subject` and a `cipPrefix` that share no CIP code are that same refusal.**
Each word is readable alone; together they name no program at all. The two
clauses are independent on the index (`cip_codes &&` and `subject_slugs @>`), so
running the pair would match a college offering one program in each — an answer
to a question nobody asked, handed back with `programs: []` and no reason for
the emptiness. The pair is refused with its own cause, naming both words.

**Unknown handling is per filter and is declared.** An unresolvable word is a
named refusal listing the vocabulary, exactly as `region` already behaves
(`CollegeQueryVocabulary.kt:208-213`); it is never dropped. A filter over a
column that can be unknown excludes those rows and counts them. Unknown is NULL
on a nullable column, and the empty array on the three `NOT NULL DEFAULT '{}'`
array columns — `athletic_associations`, `subject_slugs` and `cip_codes` — where
a college that reports nothing is stored as a college that has nothing. The
`subject` axis is the one that does not read its own column for this: an empty
`subject_slugs` beside a non-empty `cip_codes` is a judged NO — the programs are
known and none of them is this subject — so the unjudgeable condition is
`cardinality(cip_codes) = 0`, "we do not know this school's programs at all".
The count statement (`CollegesDao.kt:527-540`) becomes one statement with
aggregates:

```sql
SELECT count(*) FILTER (WHERE <all filters>)                 AS total,
       count(*) FILTER (WHERE <universe> AND has_rotc IS NULL) AS unk_has_rotc,
       count(*) FILTER (WHERE <universe> AND admission_rate_share IS NULL)
                                                             AS unk_admission
FROM college_search_index
```

one `FILTER` arm per supplied filter whose column can be unknown, evaluated
against the universe rather than against the other filters, so the number
answers "how many schools could not be judged on this axis" and not an
order-dependent residue.

The success payload keeps every key it has today
(`CollegeSearchTool.kt:146-197`, `college_id` first) and adds three:

```json
{
  "colleges": [ ... ],
  "count": 12,
  "total_matches": 142,
  "excluded_unknown": { "admission_rate_share": 61, "has_rotc": 8 },
  "source_years": { "ipeds": 2023, "programs_census": { "earliest": 2022, "latest": 2023 } }
}
```

`excluded_unknown` is keyed by the **index column** the filter is bound to, not
by the model-facing field name, which is what the example above already shows. A
min/max pair — `min_admission_rate` and `max_admission_rate` — is two bound
fields over one column, and keying by field name would report the same number
twice under two names. It carries only the filters the call actually supplied,
and is `{}` when no supplied filter can exclude an unknown.

`source_years` is kept and is read at result time, not from the index (D55,
D60). The payload join already reaches `college_ipeds` for the attribute values
the result renders as words, so `ipeds` is the `survey_year` over the returned
rows; `programs_census` is the `max(survey_year)` the same LATERAL already
computes for the titles. A key is absent when no returned row carries that
vintage — an empty page reports no years, which is the truthful answer.

**A page whose rows carry DIFFERENT vintages reports the span, not nothing.**
The key is the plain year when the returned rows agree, and
`{ "earliest": Y, "latest": Z }` when they do not. Reporting nothing for a mixed
page — the first reading, a `singleOrNull()` — made it indistinguishable from a
page carrying no vintage at all, so the one fact a reader would want about the
mixture was the fact that got deleted. The alternative, printing one of the two
years, would describe part of the answer as if it described all of it; the span
describes all of it.

`programs` means the titles that matched your program filter, sourced from the
same LATERAL: `array_agg(cc.title ORDER BY cc.code)` over `cip_codes cc`,
restricted to the intersection of the row's census codes and the filter's
expanded code set. **The key is absent when no program filter was written**: it
answers a question that was not asked, and `programs: []` on every non-program
search reads as "this college offers nothing" rather than "you did not ask". The
empty array keeps its own, different meaning — your filter matched none of this
college's programs. Titles are not stored on the index row; they are one join
over a 1,710-row table for at most 25 returned rows, and a stored copy would be
a second place for a CIP title to live.

### `searchByName`

The matching CTE at `CollegesDao.kt:634-650` is unchanged, down to the
raw/escaped bind split documented at `:657-664`. The **matching and ranking**
move to the index: `FROM college_search_index i`,
`i.search_text ILIKE '%' || ? || '%'` in place of the
`college_search_text(c.name, c.aliases)` call, `i.name ILIKE ? || '%'` as the
prefix boost, `i.undergrad_enrollment_headcount` and `i.name` and
`i.ipeds_unit_id` as the remaining ranking keys.

The **projection** stays on the source of truth, per D60:
`JOIN colleges c ON c.id = i.college_id` supplies
`c.id, c.name, c.city,
c.state` — `city` is not on the index and does not need
to be, because nothing matches or sorts on it. `college_name_words.college_id`
and `college_search_index.college_id` are both `colleges.id`, so the join needs
no translation and `PublicCollegeSummary.id` keeps carrying the same value.
`GET /api/v1/colleges` returns the same four fields under the same names, and
`api-specs/openapi.yaml:831-880` and `CollegeListClient.swift:69` are untouched.

## Files Modified

- `db/schema/0064.create-search-index-and-subjects.sql` (new)
- `db/schema/0065.seed-coach-system-prompt-v10.sql` (new)
- `db/data/subjects.json` (new, authored — reviewed at the gate)
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — `rebuildSearchIndex`
  (new), `search` rewritten onto the index with the payload join over the
  returned page only, `searchByName` matched on the index and projected from
  `colleges`, `credential_level` clause deleted, count statement widened
- `db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt` — one generic
  `slugs(session, table)` read over the existing `CodebookTable` allowlist, a
  `subjects` read, `upsertSubject`
- `db/src/main/kotlin/ed/unicoach/db/models/InstitutionSector.kt` (new) — the
  eleven `HD.SECTOR` values as `(code, value)` with `fromValue` / `fromCode`,
  modelled on `InstitutionControl.kt` (D61b)
- `db/src/main/kotlin/ed/unicoach/db/models/` — `CollegeQuery` (new filters,
  `credentialLevel` removed), `CollegeMatch`, `CollegeSearchPage`
  (`excludedUnknown`, `sourceYears`), `NewCollegeIndexBuild`, `NewSubject` (new)
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` — the
  `subjects` and `search-index` phases, `METHOD_VERSION` 5
- `college/src/main/kotlin/ed/unicoach/college/SubjectLoader.kt` (new) — parse,
  validate against `cip_codes`, load
- `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt` —
  `--subjects` / `--subjects-source`
- `college/src/main/kotlin/ed/unicoach/college/Codebook.kt` — slug vocabularies
  in, the code-facing half (`regionCode`, `regionWord`, `unknownLocaleWord`)
  out, `localeOf` rekeyed by slug; `CollegeQueryVocabulary.kt`,
  `CollegeSearchTool.kt`, `CollegeSearchService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt` —
  interface unchanged; only the plain-text render at `:641-664` follows
  `CollegeMatch`
- `service/src/main/resources/service.conf` — coach prompt pin v10
- `bin/ingest-colleges` — the `--subjects` flag and its forwarding
- `bin/scripts-tests` — flag grammar assertions for `--subjects`
- tests below

## Implementation Plan

1. Migration `0064`: `subjects`, `college_search_index` (thirty columns, seven
   foreign keys, nine indexes), and the `college_index_build` provenance change
   (D48). The `colleges.locale` and `colleges.state` codebook FKs are measured
   against the real snapshot and, per D57, left out.
2. `InstitutionSector`, the eleven values pinned to `SECTOR_CODES`
   (`IpedsLoader.kt:957-963`) by test before anything reads it.
3. `db/data/subjects.json` plus `SubjectLoader`: parse, canonicalize, validate
   against the parsed `cip_code` domain, load in its own `subjects` phase.
   `--subjects` on `bin/ingest-colleges` and `IngestApplication`.
4. `CollegesDao.rebuildSearchIndex` and the `search-index` phase;
   `METHOD_VERSION` 5. Reproducibility test first — it is the acceptance
   criterion, and it fails loudly on any non-determinism introduced later.
5. `CodebooksDao.slugs` plus the `subjects` read; `Codebook` carries the slug
   vocabularies and loses its code-facing half; `CollegeQueryVocabulary` gains
   the filters, binding words straight through (D61).
6. `CollegesDao.search` rewritten onto the index; the `college_programs` join
   and the `colleges` filter clauses deleted; `credential_level` removed; the
   payload join over the returned page added; `excluded_unknown` and
   `source_years` through `CollegeSearchPage` to the tool payload.
   `searchByName` matched on the index, projected from `colleges`.
7. Migration `0065` (coach v10) plus the `service.conf` pin; check whether the
   fit-lens query prompt v3 body is now contradicted and seed v4 only if it is.
8. Rebase onto whatever RFC 149 landed, re-check the migration numbers, run the
   gate.

## Tests

- `CollegesDaoTest`: the Maine acceptance query returns the five UMaine campuses
  and not the System Central Office; the default universe includes an unknown
  level and excludes an inactive school; each override works; every new filter
  matches, excludes NULL, and reports the excluded count; a subject filter
  matches through the materialised `subject_slugs`; the one-keystroke suite
  (`:684-978`) still passes against the index projection.
- Reproducibility (D59): ingest the fixture snapshot, snapshot every column,
  drop and re-ingest at the same `method_version`, assert equality on **every**
  column — no exclusions, because no column is build-specific.
- `SubjectLoaderTest`: invariants only, never a subject count — the file has no
  size target (D50). A prefix matching zero CIP codes is fatal and names the
  prefix; every prefix in the committed `subjects.json` matches at least one
  real CIP code; every slug satisfies the `slug` DOMAIN; slugs are unique; the
  loaded row count equals the file's entry count; the whole file is validated
  before the first write.
- `CollegeScorecardIngestTest`: the `search-index` phase writes one row per
  college; a Scorecard-only ingest (no IPEDS group) still fills the index with
  NULL attributes and `is_active = TRUE`; a failed later phase leaves no build
  row; `search_index_rows` and `name_words_rows` both land; deleting a college
  cascades its index row away (D47).
- `CollegesDaoTest` (D61): every coded filter binds the slug string it was given
  — asserted on the generated SQL, so no code lookup can creep back; a college
  whose `region` code has no codebook row is still in the index with `region`
  NULL and is still returned by an unfiltered search (the LEFT-JOIN discipline,
  and the assertion that catches an INNER JOIN regression); every element of
  every `athletic_associations` array is a slug present in
  `athletic_associations`, the test standing in for the foreign key Postgres
  cannot give an array; `control` matches `InstitutionControl.label` for all
  three codes, and an undefined control code fails the rebuild rather than
  reaching the CHECK.
- `InstitutionSectorTest`: the enum's value set is exactly
  `IpedsLoader.SECTOR_CODES` — eleven codes, `0..9` plus `99` — so a published
  code the enum forgets breaks this test rather than the ingest; `fromCode(99)`
  is `unknown` and an absent IPEDS row stores NULL, asserted as two distinct
  outcomes; the default universe excludes `administrative_unit` and keeps a
  NULL-sector college, which is the Maine acceptance criterion in miniature.
- `CollegesDaoTest` (D60): the filter and the count statements name no table
  other than `college_search_index` — asserted on the generated SQL, so a future
  payload column cannot quietly rejoin the hot path; the payload join returns
  `city` and the money fields unchanged; `has_study_abroad` and `has_housing`
  match end to end, each against a college that carries the other attribute and
  not this one, so a bind pointed at the wrong column — `has_housing` reads
  `offers_housing` — cannot pass.
- `CollegeSearchToolTest`: every new word filter round-trips; no bare source
  code appears in any payload; an unknown subject word is a named error, not a
  silent no-op; `excluded_unknown` and `source_years` shapes; `credential_level`
  is refused as an unknown field.
- `FitLensServiceTest`, `FitLensHandlerTest`: the service interface is unchanged
  and retrieval still returns matches.
- `CollegeSearchRoutingTest`, `OpenApiCollegeSearchTest`: `GET /api/v1/colleges`
  is byte-identical in shape; an empty index returns an empty list rather than
  an error.
- `CoachingConfigTest`, `SystemPromptCatalogTest`: v10 is pinned, v9 remains
  rollable.
- `bin/scripts-tests`: `--subjects` default forwarded, explicit value replaces
  it, empty value refused, nonexistent path fatal — the
  `test_ingest_colleges_source_flags` pattern at `:1200-1239`.
- Gate: `nix develop -c bin/test` unscoped, executed counts reported.

## Risks

**RFC 149 is live on the same two files.** `money/02/component-split`
(`pipeline/rfc-149`, migration 0062) edits `bin/ingest-colleges` phases and
`CollegesDao.search` — the two files this slice rewrites hardest. The conflict
is mechanical, not a design collision, and the slice graph records it as
CONFLICTS, not an ordering. Mitigation: rebase onto `main` early and often, and
recompute the migration numbers immediately before the commit. If 149 lands its
cost-component columns on `colleges`, D60 keeps them **off** the index unless a
filter or a sort uses one — a new price component is payload, read through the
existing join — so the rebase surface is the filter list, not the DDL.

**Changing the taxonomy requires a rebuild (D51).** `subject_slugs` is
materialised, so an edit to `db/data/subjects.json` has no effect on search
until `bin/ingest-colleges` runs. This is a deliberate trade — a query-time
prefix join over the census on every search is the cost the index exists to
remove — but it means the taxonomy is not a hot-editable knob, and the coach
will keep answering with the previous taxonomy until the next ingest. The
migration cannot help; the fix is to say it in the operator documentation and to
have the loader print the subject count and the total expanded code count on
every run, so a rebuild is visibly a rebuild.

**The codebook FKs make the `codebooks` phase a hard prerequisite of the
`search-index` phase (D61).** Six of the index's seven foreign keys point at
reference tables the `codebooks` phase fills. The ordering already holds:
`codebooks` is phase 1 (`CollegeScorecardLoader.kt:531`), deliberately before
the attribute phases (comment `:523-528`), and `search-index` is the
second-to-last. What changes is the failure mode — an ingest that skips
`--codebooks` against an unseeded database now fails in the `search-index` phase
instead of building an index of NULLs. `bin/ingest-colleges` always passes
`--codebooks` with a repo default, so the scripted path is safe.

`state` is the seventh column and the one without a foreign key. The reference
would have been satisfiable by construction only if `colleges.state` carried the
same one, and D57 leaves that open; on its own, the index-side FK would make the
`search-index` rebuild fail outright against an unseeded `us_states`, which is
the failure D57 declines to introduce in this slice.

**The index becomes the serving table, and the deployment order is
migrate-then-ingest.** A migrated but never-ingested database has an EMPTY
index, and both search entry points read it. There is deliberately no backfill
migration: the index is derived from `colleges`, `college_ipeds`, the codebook
tables and the taxonomy, and a migration that recomputed it would be a second
implementation of the `search-index` phase in SQL — one that could drift from
the phase, and one that would run before the taxonomy the same run loads. So the
operational contract is stated instead: **migrate, then run
`bin/ingest-colleges`**, in that order, and the window between them is a window
in which college search does not work.

What that window must NOT do is answer "0 colleges match". A zero is a truthful
shape carrying a false fact, and no reader — the coach, a family, an operator
reading a log — can tell it from a genuinely empty result; name search is worse
still, because before this slice it degraded to a `colleges` substring scan and
so was better than the index makes it. The unbuilt state is therefore DETECTED
(no index rows AND no `college_index_build` row carrying `search_index_rows`)
and NAMED: `CollegeSearchOutcome.IndexNotBuilt` on the structured path, which
the tool renders as "the search index has not been built yet"; a
`SearchIndexNotBuiltException` (a `TransientError`, because the next ingest
fixes it) on the name path, which the REST layer answers with 503. Both log an
ERROR naming the phase to run. The honest empty case is kept apart by the build
row: a database whose `colleges` table really is empty has a BUILT index with no
rows, and that search answers zero.

This is the same class of fact as `Codebook.EMPTY` (`Codebook.kt:178`) — a
database that never ran the ingest has no vocabulary and now has no index either
— and it is asserted rather than discovered: both entry points are tested
against an unbuilt index, and against a built-but-empty one.

## Deferred

**`is_active` asserts "open" about a college it knows nothing about.** The
column is `NOT NULL` and coalesces a missing `college_ipeds` row to TRUE
(`coalesce(ci.cy_active, TRUE)`), so on a Scorecard-only ingest — where there
are no IPEDS rows at all — it reads TRUE for every row and carries no
information. This is the one place in the index where unknown is silently "yes",
which is what brief 0004 D11 exists to prevent; `is_four_year` on the adjacent
line does it correctly, with NULL.

The shape of the fix, not taken here: a tri-state `is_operating BOOLEAN NULL` —
TRUE when IPEDS reports the college active for the survey year with no death
year and no closure date, FALSE otherwise, NULL when there is no IPEDS row —
with the default universe reading `is_operating IS NOT FALSE`. That is
behaviour-preserving for every real query, and it would let a result say "closed
in 2019" from `college_ipeds.closed_at`, which the new read path already
reaches.

Deferred by Ian at the RFC 150 approval gate, to the product backlog. It is not
a defect this slice introduces: it is brief 0004 D18 carried forward unchanged.

**Inlining every subject slug in the tool schema does not scale forever.** The
taxonomy has no size cap (D50), and every slug is an enum value in the
`search_colleges` definition that ships in the model's context on every call —
roughly 430 tokens at 109 slugs. A few hundred is affordable, so this slice
inlines them. If the file ever grows past that, the fix is to stop inlining the
enum and resolve free text server-side against slugs and synonyms, returning a
named error that lists near matches. Not done here; recorded so the trade is on
the record when the file gets large.

**Subject synonyms return with that resolver.** They are dropped from this slice
entirely — column, file key, model field and loader warn (D49) — because nothing
reads them while the tool advertises the slug enum. The resolver above is the
first thing that would, and it needs synonyms authored for it: unambiguous where
the current hierarchical taxonomy is not, since a word resolving to two subjects
must resolve to none.

**The `colleges` codebook foreign keys (D57).** `colleges_state_codebook_fkey`,
`colleges_locale_codebook_fkey`, the two shape checks they would replace, and
the matching `college_search_index.state` reference are an open operator
decision. The corpus measurement passed; what remains is deciding whether the
`codebooks` phase becomes a hard precondition of writing any `colleges` row,
which is its own slice.
