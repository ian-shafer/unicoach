# RFC 147 — Published codebooks as reference data

Status: proposed Product brief: `product/0004-college-search-index`. This RFC is
a **substrate slice**, split out of S3 at Ian's direction during the S3 design
gate: the index (S3 proper) becomes RFC 148 and lands on top of this.

## Why this exists

The repo stores federal codes faithfully — `region SMALLINT`,
`rel_affil
SMALLINT`, `carnegie_basic SMALLINT` — and has nowhere to say what
they mean. The only transcription of any federal codebook in this repo today is
**prose inside two prompt-seed migrations** (`0032:33`, `0037:72-73`), written
by hand, verified by nothing, and already a duplicate of itself. Every new coded
column makes that worse, and S3 would have added twelve.

The publisher already ships the answer. IPEDS distributes, next to every survey
file, a Stata `.do` syntax file whose value labels are plain text:

    label define label_oberegi 1 "New England CT ME MA NH RI VT"
    label define label_c21basic 15 "Doctoral Universities: Very High Research Activity"

So this slice fetches those artifacts, generates a reviewed codebook from them,
loads it as reference tables, and makes the search tool speak words instead of
numbers — in **both** directions. It deletes the hand-written prose rather than
adding a third copy.

## Decisions

**D37 — one publisher per domain, named in the file.** IPEDS is the authority
for every code it emits. `region` and `locale` reach us through the Scorecard
institutions file, but the code systems are IPEDS `OBEREG` and the NCES
urban-centric locale; the codes agree and only the wording differs (IPEDS "Other
U.S. jurisdictions" vs Scorecard "Outlying Areas"). We take the IPEDS wording
and record the choice in the file. The RFC originally carried a `definition_raw`
column for the publisher's own prose (NCES Exhibit A for locale). Implementation
found that prose is **not in the distributed zip**, so the column would have
been created, read, and always null. It is dropped rather than shipped as a
promise the data never keeps.

**D38 — `label_raw` is verbatim; `name` is repaired in the generator.** The
`.do` files encode an apostrophe as a caret (`Associate^s Colleges`, 146 labels
in HD2023). We store the published string untouched in `label_raw`, and the
generator produces the human `name` — caret repaired, structure parsed. Repair
happens where a human reviews a diff, never at ingest.

**D39 — a published label is a record, not a string.** The generator parses the
structure a label carries and stores it as columns, keeping `label_raw`
alongside. A parse failure is **fatal at generation time**. This buys:

- `ipeds_regions`: `1 "New England CT ME MA NH RI VT"` -> `name` + the state
  membership, which lands as `us_states.ipeds_region`.
- `nces_locales`: `"Rural: Fringe"` -> `type` + `detail`.
- `carnegie_2021_size_settings`: `"Four-year, small, highly residential"` ->
  `years`, `size`, `residential_character` — this is what finally makes "small
  residential college" a sourced filter instead of a guess at an enrollment
  number.
- `carnegie_2021_basic_classes`:
  `"Doctoral Universities: Very High Research
  Activity"` -> `degree_level` +
  `qualifier`.
- `religious_affiliations`: no reliable structure beyond the denomination name.
  Kept flat, deliberately.

**D40 — variable labels are data too.** `ASSOC1..6` is not a code list: it is
six yes/no flags whose meaning lives in the **variable** label ("Member of
National Collegiate Athletic Association (NCAA)"). So the generator emits
variable-label rows as well as value-label rows, and keys each domain by **our**
name, listing the source variables that feed it.

_Corrected during implementation:_ this RFC first asserted that `CONFNO1..4`
share one conference value set. They do not — the four sets hold 100/133/131/130
entries, and codes 202 and 359 carry **different** published labels between
them. Only `CONFNO1` (football) is emitted, because
`college_ipeds.football_conf` stores exactly that variable; the four set sizes
are recorded in the provenance notes so the divergence is on the record rather
than in someone's memory.

A second repair beside the caret was found the same way: `ASSOC3`'s variable
label contains a double space ("Athletic Association"). `label_raw` keeps it;
`name` collapses it.

**D41 — sentinels: `-2` is data, `-1`/`-3` are absence.** This follows the rule
`0055` already landed: `-1` (not reported) and `-3` (not applicable) become
`NULL`; `-2` is preserved as a real "none"/"not applicable" answer. The codebook
therefore carries the `-2` rows and declares `null_sentinels` per domain; the
loader asserts that no declared sentinel is also a codebook row.

**D42 — vintage lives in the name, not in a key.** `C21BASIC` already names its
vintage, so the table is `carnegie_2021_basic_classes` and the slug is the
primary key. Each domain records `source_vintage_year`. A relabelled code is a
reviewable diff on an existing row; a code whose **meaning** changes gets a new
slug. We do not key reference data by survey year — that would push a join onto
every read for a vocabulary that changes every few years.

**D43 — CIP titles are in scope.** `C2023_a.do` hands over the full 6-digit CIP
list with titles, for free — **1,710** codes across 38 families, once the
`99 "Grand total"` aggregate row is excluded by name (the RFC first said 1,711,
counting that aggregate). It closes RFC 144's real gap
(`college_programs_census` has codes and no titles) and it gives RFC 148's
subject taxonomy a validator: a taxonomy prefix matching zero real CIP codes
becomes a fatal error instead of a silently empty subject.

**D44 — one shared `slug` domain.** `0054` set the house rule: a Postgres
`DOMAIN` is a de-duplication device, justified when the same constraint would
otherwise be copied across three or more columns. Eight tables key on a slug
here, so:

    CREATE DOMAIN slug AS TEXT CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

`usd` and `share` domains are **not** introduced here. They belong with the
columns that need them (RFC 148's index), and retyping existing `colleges`
columns is a table rewrite this slice has no reason to run.

**D45 — the tool speaks words in and out.** `search_colleges` stops taking
`region: 1` and `locales: [11,12]` and starts taking `region: "new-england"`,
`locale_type: "city"`. `control` moves with them (`1/2/3` ->
`public`/`private_nonprofit`/`private_for_profit`), because the prompt sentence
being deleted carried that codebook too — deleting the prose without moving the
fact would lose it. The fit lens's second copy of the filter vocabulary
(`RECORD_COLLEGE_QUERY_TOOL` + `parseQuery`) is not merely moved but
**deleted**: both surfaces now parse one shared schema, so they cannot drift.
The codebook prose is then deleted from the fit-lens prompt seeds (the coach
prompt never carried one), since nothing needs a number-to-word table in its
context any more.

**D46 — `colleges` gets a report, not a foreign key.** `colleges.locale` has a
range check (`11..43`) that admits codes IPEDS never emits. Tightening it to a
real FK is right, but it is a constraint change on a live source table and it
belongs with the ingest that fills it (RFC 148). Here, the loader **reports**
every stored code with no codebook row, per column, with counts.

## Detailed Design

### `bin/fetch-codebooks`

Mirrors `bin/fetch-cds-seed` exactly — stdlib-only `python3`, `-o` outdir, `-F`
offline fixture dir, `[WARNING]`/`[FATAL]` prefixes, exit codes 10/11 shared
with the shell harness, retries only on transient HTTP. It is **operator-run**;
no ingest and no test ever fetches.

1. Download `HD2023_Stata.zip`, `IC2023_Stata.zip`, `ADM2023_Stata.zip`,
   `C2023_A_Stata.zip` from `nces.ed.gov/ipeds/datacenter/data/`.
2. Record each artifact under `db/seed/codebooks/` with URL, `fetched_at`, bytes
   and sha256 in `db/seed/codebooks/PROVENANCE.json`.
3. Parse the `.do` value-label and variable-label blocks (one regex matched
   6,779/6,779 label lines across the four files in research).
4. Emit `db/data/codebooks.json`, deterministically ordered.
5. Refuse to write on shrink: if any domain's label count falls below half the
   committed count — the `SEED_SHRINK_FLOOR` guard `fetch-cds-seed` already uses
   — it is fatal.

`HD2023` publishes `STABBR` names for exactly the 59 `OBEREG` members, so no
authored state-name table was needed. Only `jurisdiction_kind` is authored (DC
federal-district; AS/GU/MP/PR/VI territory; FM/MH/PW freely-associated-state),
and the generator fatal-checks the mapping in both directions plus "exactly 50
states".

Slugs come from a fixed slugify rule plus an explicit override map **in the
generator**, so regeneration is reproducible rather than re-edited.

```json
{
  "ipeds_region": {
    "source": "IPEDS HD2023 (OBEREG)",
    "source_file": "HD2023_Stata.zip",
    "source_sha256": "…",
    "source_vintage_year": 2023,
    "null_sentinels": [-1, -3],
    "codes": [
      {
        "code": 1,
        "slug": "new-england",
        "name": "New England",
        "label_raw": "New England CT ME MA NH RI VT",
        "member_states": ["CT", "ME", "MA", "NH", "RI", "VT"]
      }
    ]
  }
}
```

### Migration `0060` (number verified at run time)

Domain plus eleven reference tables. All follow one shape — `slug` primary key,
source `code`, parsed `name`, `label_raw`, and whatever structure the parse
recovered:

```sql
CREATE DOMAIN slug AS TEXT CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

CREATE TABLE ipeds_regions (
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE us_states (
    usps_code TEXT PRIMARY KEY CHECK (usps_code ~ '^[A-Z]{2}$'),
    name TEXT NOT NULL UNIQUE,
    jurisdiction_kind TEXT NOT NULL CHECK (jurisdiction_kind IN
        ('state','federal-district','territory','freely-associated-state')),
    ipeds_region slug NOT NULL REFERENCES ipeds_regions(slug));

CREATE TABLE nces_locales (
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    type TEXT NOT NULL CHECK (type IN ('city','suburb','town','rural')),
    detail TEXT NOT NULL CHECK (detail IN
        ('large','midsize','small','fringe','distant','remote')),
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE carnegie_2021_basic_classes (
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    degree_level TEXT NULL, qualifier TEXT NULL,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE carnegie_2021_size_settings (
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    years SMALLINT NULL, size TEXT NULL, residential_character TEXT NULL,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE religious_affiliations (
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE athletic_associations (          -- from ASSOC1..6 VARIABLE labels
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,  -- the ASSOC ordinal
    source_variable TEXT NOT NULL UNIQUE,                  -- college_ipeds.athletic_assoc stores it
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE football_conferences (           -- CONFNO1..4 share one set
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE admission_test_policies (        -- ADMCON7: 3 codes
    slug slug PRIMARY KEY, code SMALLINT NOT NULL UNIQUE,
    name TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE cip_codes (                      -- C2023_A: 1,710 six-digit codes
    code TEXT PRIMARY KEY CHECK (code ~ '^[0-9]{6}$'),
    title TEXT NOT NULL, label_raw TEXT NOT NULL);

CREATE TABLE codebook_sources (               -- provenance, one row per domain
    domain TEXT PRIMARY KEY, source TEXT NOT NULL, source_file TEXT NOT NULL,
    source_sha256 TEXT NOT NULL, source_vintage_year SMALLINT NOT NULL,
    null_sentinels SMALLINT[] NOT NULL DEFAULT '{}',
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
```

### Loading

A `codebooks` phase in `bin/ingest-colleges`, before the attribute phases — one
command, one run, matching the aliases precedent. It upserts every domain,
**refuses to delete** a row still referenced, asserts that no declared
`null_sentinel` appears as a code, and prints per-domain counts. It then
**reports** every code stored in `colleges`/`college_ipeds` with no codebook row
(D46).

### Boundary

`CollegeSearchTool` gains word-valued `region`, `locale_type`, `locale_detail`
inputs and emits words; a `Codebook` service resolves slug <-> code from the
loaded tables, and Kotlin enums cover only the small closed sets (locale type
and detail, test policy) with a test asserting they match the file. The codebook
prose is deleted from the coach and fit-lens prompt seeds, which means one new
immutable prompt version, the `service.conf` pin, and the two pinning tests.

## Files Modified

- `bin/fetch-codebooks` (new), `db/seed/codebooks/` + `PROVENANCE.json` (new)
- `db/data/codebooks.json` (new, generated)
- `db/schema/0060.create-codebook-reference-tables.sql` (new)
- `db/schema/0061.seed-fit-lens-query-prompt-v3.sql` (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt` (new) + models
- `college/src/main/kotlin/.../CodebookLoader.kt` (new), loader wiring
- `college/.../CollegeSearchTool.kt`, `service/.../FitLensService.kt`
- `service/src/main/resources/service.conf`, `bin/ingest-colleges`
- `bin/scripts-tests` (fetch/generate assertions), tests below

## Implementation Plan

1. `bin/fetch-codebooks`: fetch, digest, parse, emit, shrink guard. Offline
   fixtures for the harness.
2. Migration `0060` + `CodebookLoader` + the ingest phase + the mismatch report.
3. Boundary: words in and out, `Codebook` resolution, fit-lens vocabulary.
4. Prompt seed without codebook prose + conf pin + pinning tests.
5. Regenerate `db/data/codebooks.json` from the committed artifacts and confirm
   the byte-identical reproduction.

## Tests

- `bin/scripts-tests`: `ast.parse` gate; offline generation from fixtures;
  `PROVENANCE.json` read as JSON; the shrink guard refuses; **the committed
  `codebooks.json` matches the committed artifacts** (the anti-hand-edit guard,
  copied from the CDS seed pattern).
- Generator determinism: regeneration is byte-identical.
- `CodebookLoaderTest`: every domain loads; a sentinel declared and present as a
  code is fatal; deleting a referenced row is refused; the unknown-code report
  counts correctly.
- Parse tests over real quoted labels: `OBEREG` membership, locale type/detail,
  Carnegie size setting `years`/`size`/`residential_character`, the caret
  repair, `ASSOC1..6` variable labels, the shared conference set emitted once.
- `CollegeSearchToolTest`: words in and out;
  `no bare source code reaches a tool
  result` still passes; an unknown word is
  a clean error, not a silent no-op.
- `FitLensServiceTest`, `CoachingConfigTest`, `SystemPromptCatalogTest`.
- Gate: `nix develop -c bin/test` (unscoped), executed counts reported.

## Open items

- `colleges.locale`'s range check stays a range check until RFC 148 (D46).
- Scorecard's alternative wording for `REGION`/`LOCALE` is not stored; if a
  student-facing surface ever wants it, it is another `*_raw` column.
- No `usd`/`share` domains yet (D44).
