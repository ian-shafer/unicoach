# RFC 239 — a score is money

Slice `test/07/a-score-is-money` (brief 0010, handle `test`). Lane A.

At a number of public flagships a test score band **is** a price. The University
of Alabama pays a Fall-2027 out-of-state freshman $6,000/yr at SAT 1200–1250 and
$8,000/yr at 1260–1290, requires a submitted score to qualify at all, and **does
not superscore for scholarship purposes** — so the grid reads one sitting. Its
scores are due December 4, and the December 5 SAT reports on December 18, which
makes November 7 the last usable sitting and its registration closed October 23.
A ten-point miss is $8,000 over four years, lost on a date nobody announces.

This RFC lands the corpus that lets the coach say that about a named school on
the student's list, and refuse — in four distinguishable ways — everywhere else.

## What was measured before this was designed

Two curation passes read the primary pages of 48 institutions on 2026-09-24 and
recorded only what the page itself said, with the sentence quoted:

| Measured, and seeded                                                        | Count                                |
| --------------------------------------------------------------------------- | ------------------------------------ |
| Institutions read                                                           | 48                                   |
| Institutions seeded                                                         | **40**                               |
| Schools where a test score changes the dollars                              | **18**                               |
| Award rows (school × entry year × residency basis)                          | 60                                   |
| Published bands, one row per cell                                           | **571**                              |
| Schools publishing an automatic table where a score is worth **$0**         | 5 schools, 8 rows                    |
| Schools verified to publish **no** automatic award, with a quote proving it | 21 schools, 25 rows                  |
| Rows carrying a published score-receipt deadline                            | 25                                   |
| Rows carrying a last-considered-sitting bound                               | 5 (3 dated, 2 words only)            |
| Source pages, each re-checked with `bin/check-link`                         | 53 — 50 found, 3 unknown with a date |

Eight institutions were read and **not** seeded, each for a stated reason in
`PROVENANCE.json`: one page refuses every client we have (UVA, 403), one
publishes "coming soon" for this cycle (Mississippi State), one publishes no
grid in any year (Arizona), and five still publish only their **Fall 2026** grid
(Ole Miss in-state, Oklahoma, Wyoming, Montana State, Utah — Montana State says
so outright: "Fall 2027 scholarship information will be available later this
fall"). Last cycle's dollars spoken as this cycle's is the wrong answer this
corpus exists to prevent, so they are owed re-reads rather than rows.

D8's escape clause — "if the curated set cannot reach a useful size, this slice
becomes a later brief rather than a vague one" — is therefore not taken: 18
schools and 571 bands is a narrow, sourced, useful corpus.

The measurement also produced the shape. Three facts in it are not in the slice
text and each one breaks a naive design:

1. **An automatic grid does not imply a score-driven grid.** At Utah, Idaho,
   Montana State, Kansas, Miami (Ohio) and the out-of-state halves of Kansas
   State and Iowa State, the published table pays on GPA alone. "This school
   publishes an automatic award and your score does not change it" is a
   different, true, useful answer — and saying "we do not know" there would be
   false.
2. **Residency inverts the rule.** Iowa State and Kansas State gate an in-state
   award on a score and pay the out-of-state award on GPA alone. UAB's last
   usable sitting is December for a resident and the February ACT for a
   non-resident. Residency is part of the key, not a display detail.
3. **Some grids are two-dimensional.** Oklahoma State and Northern State publish
   a score × GPA matrix, so the honest answer quotes a CELL. A row-shaped table
   with one GPA floor per band cannot hold them.

And one measured fact about our own repo, which is why the loader below refuses
rather than skips: a fuzzy name match of those 48 schools against the loaded
`colleges` table returned **8 wrong schools** — Arizona State matched Arizona
Christian, Ohio State matched Shawnee State, Michigan matched Michigan-Flint. A
mis-keyed row in this corpus is not a missing answer, it is another school's
money under this school's name.

## Decisions

Defaults are pre-chosen; approving costs one word.

**D-A — two tables, and the DDL is in the gate.** `college_automatic_awards`
(one row per college × entry year × residency basis: what the school publishes)
and `college_automatic_award_bands` (one row per published cell). Full DDL below
and in the approval message. _Default: YES._

**D-B — a new chat tool, not a sixth section on `college_admissions_profile`.**
That profile is an always-on whole-list payload; curated coverage is 18 schools,
and the answer needs clock arithmetic the profile service has no clock for. The
house has ruled this way twice already (`Application.kt:418-423`,
`FafsaProcessChatTool.kt:22-31`: "a sibling of the tool above rather than more
payload on it"). _Default: a new tool, `college_automatic_awards`._

**D-C — the corpus is loaded by the ingest run, not by a migration.** Every
other hand-typed corpus in this repo (the test calendar, the state programmes,
the FAFSA deadlines) is keyed by a state or by nothing, so a migration can seed
it. This one is keyed by `colleges(id)`, and `colleges` is filled by the import
run — a migration-time seed would point at an empty table, which is exactly the
failure D27 diagnosed. So the committed CSV pair joins `db/seed/cds/*` and is
loaded by `bin/ingest-colleges` behind `bin/load-external-data`, with D19's
reviewable-diff guarantee intact and D20's staleness guard unchanged (it reads
the CSV from disk, never the database). _Default: ingest-time, by the CDS path._

**D-D — four answers, one sealed type, no nullable.** `ScoreBands` (a score
moves the money here), `NoScoreEffect` (published grid, score irrelevant),
`NonePublished` (we read the page; this school publishes no automatic award),
`NotCurated` (we have not read this school). An exhaustive `when` with no
`else`, the `BorrowingCoverage` precedent. _Default: four._

**D-E — the cost report states the SCHOOL's grid, not the student's score.** The
shared report page holds no test score today and a logged-out parent reads it,
so putting "your student scored 1250, so this school pays $6,000" there is a new
RFC 155 D-F privacy ruling rather than an inherited one. The report gets a new
`report-award-grid` block naming the school's published bands, the submission
requirement and the score deadline; the student's own position in the grid is
the chat's answer. _Default: school-only on the report._

**D-F — the coach prompt retracts two promises it currently makes.**
`prompts/coach-system-prompt.md:64` says scholarship score rules "are not things
we hold" and `:82` says a superscore rule is held "for no school". Both go false
the day this lands, and three executed assertions pin that wording
(`AdmissionTestTimelineChatToolTest.kt:347-353`,
`SystemPromptCatalogTest.kt:3457-3469`, and the tool's own `DESCRIPTION`). All
three are narrowed in this change to "for the schools we have read, the tool
says so; for every other school, the old sentence still holds". _Default:
narrow, do not delete._

FOUR prompt lines, not three: `:62`, `:64`, `:82` and `:88`. Line 88 — _"Scores
from different sittings are never added together, and never presented as a
school's superscore"_ — is made FALSE by D-H, which computes a combination
exactly where the school's own page says it accepts one, so leaving it would put
the prompt in direct contradiction with the tool. It is narrowed with the other
three and not retracted: the coach still never does that arithmetic from memory,
our figure is never presented as a score the school holds, and it is never
called an ACT Superscore. `TestScoreCopyTest` asserts that sentence verbatim and
follows it.

**D-G — a row expires on July 31 of its entry year**, guarded by the one shared
`HandTypedReferenceData.REFRESH_LEAD` (90 days). These pages are re-keyed per
cycle in the spring and summer, so a Fall-2027 grid stops being the answer once
Fall-2028 applicants are reading. The expiry is NOT the score deadline: a
deadline is the fact the row states, not the day we stop believing the page.
_Default: July 31 of the entry year, authored per row._

**D-H — we DO combine sittings where the school's own page says it combines, and
we compute the combination from SECTION scores only.** (Ian, 2026-09-24, on the
first draft's refusal to compute one: _"Why would you not do B? Am I missing
something? Math is easy, right?"_ — he is right, and the draft's reason was
wrong.)

The SAT combination is arithmetic: the highest `sat_reading_writing` across
sittings plus the highest `sat_math`. The scale makes that sum the total by
definition, so nothing is estimated. The draft's objection — a stored total may
itself be a student-typed superscore — is an objection to combining TOTALS, and
`admission_test_scores` stores one row per COMPONENT per sitting (RFC 207), so
the section-level combination is available and the objection does not reach it.

Three narrow guardrails survive, and each one is a way to be confidently wrong:

1. **Only where `superscore_rule = 'superscore_accepted'`.** Alabama, UAB and
   Kentucky state in writing that a scholarship grid reads one sitting. A
   combination shown against those grids is money the school will not pay.
2. **The ACT composite is an AVERAGE, rounded — and that rounding is ACT's
   published rule, not our arithmetic.** So the ACT combination carries a cited
   source page in `external_documents` like every other claim (RFC 220), and is
   not computed until that citation exists. Combine only within one test; never
   average a sitting's missing section into a composite.
3. **Only sections we hold.** A sitting the student entered as a bare total has
   no sections and contributes nothing to a combination. That is absent data,
   not a refusal, and the answer says which sittings it used.

Both figures ride in the answer: the best single sitting, and the combination
where one is permitted and computable. The combination is labelled as OURS over
the school's stated rule — "combining your best sections across your two
sittings gives 1290, and this school's page says it accepts a combined score for
scholarships" — never as a score the school holds. _Default: compute it, under
those three guardrails._

**D-I — one run, both doors.** The cost-report half is one line plus one render
function; splitting it into `test/07a`/`test/07b` would cost two reviews and two
gates for that. _Default: one run._

**D-J — promote `('sat','act')` to a DOMAIN, or write a third CHECK.** The
calendar table and the score tables each declare their own
`CHECK (test IN ('sat','act'))`. This corpus's band column is the third to ask
the same question, which is the trigger RFC 213 already wrote down for exactly
this promotion. Promoting costs four statements in this run's migration — the
`CREATE DOMAIN admission_test`, two `ALTER COLUMN ... TYPE`, two dropped CHECKs
— and leaves one home for the vocabulary. Not promoting costs a third copy.
_Default: promote, in this run._

It costs one landed TEST as well, and the cost is measured: the corrupt-row
fixture in `AdmissionTestCalendarDaoTest` plants `'psat'` by DROPPING the very
CHECK this promotion deletes, and a DOMAIN constraint fires at value COERCION,
so that value cannot be written at all while the column carries the domain. The
fixture moves the column TYPE instead (`TEXT` → plant → delete the row →
`admission_test`), with an unconditional restore, and its assertion is
unchanged. It is the only `.kt` file in the tree that names either dropped
constraint.

**D-K — the corpus expands to the schools already read, and no further.** The
curation passes read 48 institutions and this run seeds what they verified, as
committed: 18 score-grid schools (27 rows), 5 grid-but-no-score-effect (8 rows),
21 verified-none (25 rows) — 44 kind labels over 40 schools, because residency
splits four of them across two kinds. Widening the set is a later re-read, not
this run's work (D28). _Default: seed what was read._

## Detailed Design

### The tables

Two, in one migration `db/schema/create-college-automatic-awards.sql`. The
template is `create-fafsa-filing-deadlines.sql`, which already solves this
corpus's two hard requirements: a verified-absence kind with a value-iff-kind
CHECK, and an `as_published` column carrying the publisher's own words beside
our parsed value.

```sql
CREATE TABLE college_automatic_awards (
    id                     UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The school. NOT NULL REFERENCES colleges(id) and not an IPEDS code of our
    -- own: D26's guarantee, kept. This is also why the corpus loads with the
    -- ingest and not from a migration (D-C) -- colleges arrive at import time.
    college_id             UUID          NOT NULL REFERENCES colleges(id),

    -- The ENTRY year, named by its first calendar year: 2027 is Fall 2027 entry,
    -- the 2027-28 academic year. The domain owns the range (0087).
    entry_year             academic_year NOT NULL,

    -- WHOSE grid this is. The existing vocabulary, keyed into residency_bases
    -- (RFC 158 D4) rather than a fourth private copy of in_state/out_of_state:
    -- 'not_applicable' is the real member for a grid that does not vary by
    -- residency, and it is not a NULL anybody has to remember. in_district and
    -- unknown are legal in the vocabulary and refused by the CHECK below --
    -- a community-college tier and an IPEDS headcount category are not things a
    -- university publishes a freshman award against.
    residency_basis        TEXT          NOT NULL REFERENCES residency_bases(slug),

    -- OUR OWN enumeration (TEXT + CHECK IN, one Kotlin enum with fromValue).
    -- 'score_grid':    the school publishes bands and a score changes the money.
    -- 'gpa_only_grid': the school publishes an automatic table and a score
    --                  changes NOTHING in it. A different true answer, and the
    --                  reason this is not a boolean: schools we have read are
    --                  this, and "we do not know" would be false for them. How
    --                  MANY is db/seed/automatic-awards/awards.csv's to say and
    --                  it moves every time a curator adds a row, so it is not
    --                  restated here or in AutomaticAwardKind.
    -- 'none_published': we READ this school's page on checked_on and it
    --                  publishes no automatic award at all. A verified absence
    --                  with a citation, not our silence -- the argument
    --                  fafsa_filing_deadlines makes for deadline_kind='none'.
    award_kind             TEXT          NOT NULL,

    -- Must a score be submitted to qualify for ANY automatic award? The one fact
    -- that makes test-optional-for-admission and test-optional-for-money
    -- different questions, and it is NOT derivable from award_kind: a gpa_only
    -- school can still demand a score from a home-schooled applicant.
    --
    -- THREE STATES AND NOT A BOOLEAN, measured: of the curated score-grid
    -- readings, all but one quote the school on this and one does not. A boolean
    -- would have to answer for that one, and both answers would be a claim the
    -- page never made. 'unstated' is the third honest state and it is the one the
    -- coach refuses on.
    score_requirement      TEXT          NOT NULL,

    -- The SCHOLARSHIP superscore rule, never the admissions one (D14.2).
    -- 'single_sitting_only' | 'superscore_accepted' | 'unstated'.
    superscore_rule        TEXT          NOT NULL,

    -- The day official scores must be with the school for the award, when the
    -- school publishes one, and the publisher's own sentence for it. The words
    -- matter for the reason fafsa_filing_deadlines states: a reader who saw only
    -- our parsed date could attribute OUR reading to the publisher.
    score_deadline         DATE          NULL,
    score_deadline_as_published TEXT     NULL,

    -- The submission requirement and the superscore rule in the school's own
    -- words, because the coach must state them and must not paraphrase a rule
    -- worth four figures.
    score_requirement_as_published TEXT  NULL,
    superscore_as_published TEXT         NULL,

    -- THE LATEST SITTING THE SCHOOL WILL CONSIDER AT ALL, which is NOT the same
    -- fact as the receipt deadline above and is the one that decides the slice's
    -- headline sentence. UT Knoxville publishes both and they disagree: scores
    -- are received "by May 15", but "Only scores through December 2026 will be
    -- considered" -- so the February and April sittings cannot raise its award
    -- however early the report arrives. A model with only a receipt deadline
    -- would tell that student to retake in February.
    --
    -- D23's shape, because the published bound is usually a MONTH: a DATE
    -- normalised to the start of the period plus a precision. It is the (date,
    -- precision) PAIR that never escapes the DAO -- the DAO decodes the two
    -- columns into ONE value, `LastSittingConsidered` (declared below), whose
    -- Through arm carries a `PartialDate`. `PartialDate` is the type ABOVE the
    -- pair (brief 0010 D23) and it does travel: a sitting the student remembers
    -- to the month is a month everywhere. The service takes the period END,
    -- because "through December 2026" admits every December sitting.
    last_sitting_through   DATE          NULL,
    last_sitting_precision date_precision NULL,
    last_sitting_as_published TEXT       NULL,

    -- NO PARSED CLAIM WITHOUT THE PUBLISHER'S WORDS. The three columns above are
    -- nullable and the three CHECKs below tie each one to its own parsed value:
    -- a stored deadline, a stated superscore rule and a stated requirement each
    -- carry the sentence they were read from, and where the page said nothing the
    -- row says 'unstated' and stores no words. This is RFC 220's rule one table
    -- along -- there, no citation without a check; here, no claim without a
    -- quote. It is what stops a row asserting a rule worth four figures on our
    -- authority. Measured on the committed corpus: of the 27 score-grid rows, 22
    -- carry deadline words and 22 carry superscore words.

    -- Who published it, as a family would recognise it on the page.
    publisher              TEXT          NOT NULL,

    -- THE CITATION IS FOREIGN KEYS AND NEVER A URL COLUMN (RFC 228; there is
    -- exactly one url column in this schema and it is external_documents.url).
    source_document_id     UUID          NOT NULL REFERENCES external_documents(id),
    archive_document_id    UUID          NULL     REFERENCES external_documents(id),

    checked_on             DATE          NOT NULL,
    expires_on             DATE          NOT NULL,

    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT college_automatic_awards_kind_check
        CHECK (award_kind IN ('score_grid', 'gpa_only_grid', 'none_published')),

    CONSTRAINT college_automatic_awards_superscore_rule_check
        CHECK (superscore_rule IN ('single_sitting_only', 'superscore_accepted',
                                   'unstated')),

    CONSTRAINT college_automatic_awards_score_requirement_check
        CHECK (score_requirement IN ('required', 'not_required', 'unstated')),

    -- No parsed claim without the publisher's words (see above).
    CONSTRAINT college_automatic_awards_deadline_has_words_check
        CHECK (score_deadline IS NULL OR score_deadline_as_published IS NOT NULL),
    -- AN IFF AND NOT AN IMPLICATION, both ways round. "No claim without the
    -- publisher's words" is the half that matters, and the other half is not
    -- decoration: a row that says 'unstated' AND carries a quote is a sentence
    -- with no rule attached to it, so a renderer would print the school's words
    -- under a rule the row denies having read. The Kotlin models state the same
    -- pairing as an `init` require, and the two must be the SAME rule -- a CHECK
    -- weaker than the model turns a legal row into an unlocated
    -- IllegalArgumentException out of a data class constructor on the READ path.
    -- The DEADLINE pair above stays one-way deliberately: words with no parsed
    -- date is a legal state there, and that asymmetry carries its own test.
    CONSTRAINT college_automatic_awards_superscore_has_words_check
        CHECK ((superscore_rule = 'unstated') = (superscore_as_published IS NULL)),
    CONSTRAINT college_automatic_awards_requirement_has_words_check
        CHECK ((score_requirement = 'unstated') = (score_requirement_as_published IS NULL)),
    CONSTRAINT college_automatic_awards_last_sitting_has_words_check
        CHECK (last_sitting_through IS NULL
               OR last_sitting_as_published IS NOT NULL),

    -- The pair is a pair (D23), and the date is its period's start.
    CONSTRAINT college_automatic_awards_last_sitting_precision_check
        CHECK ((last_sitting_through IS NULL) = (last_sitting_precision IS NULL)),
    CONSTRAINT college_automatic_awards_last_sitting_period_start_check
        CHECK (last_sitting_through IS NULL
               OR is_period_start(last_sitting_through, last_sitting_precision)),

    -- WORDS WITH NO DATE IS A LEGAL AND MEANINGFUL STATE, and it is why there is
    -- no child table here. UAB publishes a cut-off that differs BY TEST --
    -- "February ACT or March SAT scores ... will be the final scores considered"
    -- -- which one column cannot parse without picking one test's date for both.
    -- Such a row carries the school's words and NO date, and the coach quotes the
    -- school instead of computing. Two rows in the curated corpus are this shape.
    -- THE TRIGGER, written down so the promotion is not a reader's guess: the day
    -- a SECOND school publishes per-test windows, this pair becomes a child table
    -- keyed by (award_id, test) -- not a second pair of columns.

    -- A freshman award is published for a resident, a non-resident, or everyone.
    CONSTRAINT college_automatic_awards_residency_basis_check
        CHECK (residency_basis IN ('in_state', 'out_of_state', 'not_applicable')),

    -- Value-iff-kind, the 0087 shape: a school that publishes NO automatic award
    -- cannot publish a deadline for one, and cannot have bands (enforced by the
    -- band table's own check below).
    CONSTRAINT college_automatic_awards_none_has_no_deadline_check
        CHECK (award_kind <> 'none_published' OR score_deadline IS NULL),

    -- Every human-typed string is trimmed and non-empty (the 0001 shape). An
    -- empty as_published is a value-shaped absence, and a quote rendered from
    -- one is a claim with no words behind it.
    CONSTRAINT college_automatic_awards_publisher_nonempty_check
        CHECK (publisher = btrim(publisher) AND length(publisher) > 0),
    -- Nonempty WHERE PRESENT: an empty string is a value-shaped absence, and a
    -- quote rendered from one is a claim with no words behind it. NULL is the
    -- only way to say "the page did not say".
    CONSTRAINT college_automatic_awards_deadline_words_nonempty_check
        CHECK (score_deadline_as_published IS NULL
               OR (score_deadline_as_published = btrim(score_deadline_as_published)
                   AND length(score_deadline_as_published) > 0)),
    CONSTRAINT college_automatic_awards_requirement_words_nonempty_check
        CHECK (score_requirement_as_published IS NULL
               OR (score_requirement_as_published = btrim(score_requirement_as_published)
                   AND length(score_requirement_as_published) > 0)),
    CONSTRAINT college_automatic_awards_superscore_words_nonempty_check
        CHECK (superscore_as_published IS NULL
               OR (superscore_as_published = btrim(superscore_as_published)
                   AND length(superscore_as_published) > 0)),
    -- FOUR quote columns, so FOUR of these and not three: the cut-off's words
    -- are a quote like the other three, and an empty one is a value-shaped
    -- absence the coach would render as a claim with no words behind it.
    CONSTRAINT college_automatic_awards_last_sitting_words_nonempty_check
        CHECK (last_sitting_as_published IS NULL
               OR (last_sitting_as_published = btrim(last_sitting_as_published)
                   AND length(last_sitting_as_published) > 0)),

    CONSTRAINT college_automatic_awards_archive_is_not_the_source_check
        CHECK (archive_document_id IS NULL
               OR archive_document_id <> source_document_id),

    CONSTRAINT college_automatic_awards_expiry_after_checked_check
        CHECK (expires_on > checked_on),

    -- A deadline belongs to its own cycle. The LOWER bound is the half that
    -- earns this CHECK its keep: it catches last cycle's date typed under this
    -- cycle's key, which is the one mistake that makes this table worse than no
    -- table.
    --
    -- The upper bound is 30 SEPTEMBER OF THE ENTRY YEAR and not the June before
    -- it, and the difference is measured. West Virginia publishes, for a
    -- Fall-2027 entrant: "After you're admitted, you may submit an updated GPA
    -- or test score through August 1" -- a real, published, post-admission
    -- re-evaluation window. A June bound refuses that published row, and
    -- retyping it to June would store a date WVU never published, against this
    -- table's whole ethic. Nothing about a FRESHMAN award is still open once the
    -- term has begun, so the term start is where the window honestly closes.
    CONSTRAINT college_automatic_awards_deadline_in_cycle_check
        CHECK (score_deadline IS NULL
               OR score_deadline BETWEEN make_date(entry_year::INT - 1, 7, 1)
                                     AND make_date(entry_year::INT, 9, 30))
);

-- The natural key. No NULLS NOT DISTINCT is needed: residency_basis carries
-- 'not_applicable' as a real value, so no axis of this key is nullable.
CREATE UNIQUE INDEX college_automatic_awards_natural_key
    ON college_automatic_awards (college_id, entry_year, residency_basis);

CREATE TABLE college_automatic_award_bands (
    id                 UUID    NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    award_id           UUID    NOT NULL REFERENCES college_automatic_awards(id)
                                        ON DELETE CASCADE,

    -- The school's own name for this award -- 'Crimson Legends', 'Presidential'.
    -- A source-defined name, stored raw (CLAUDE.md), and part of the key because
    -- one school publishes several named awards with overlapping bands.
    award_name         TEXT    NOT NULL,

    -- WHICH TEST -- RFC 197's AdmissionTest vocabulary, reused. There is no
    -- admission_tests table: the calendar and the score tables each write their
    -- own TEXT + CHECK IN ('sat','act'), and this column would be the third
    -- copy, which is the trigger RFC 213 states for promoting a CHECK to a
    -- DOMAIN. D-J decides whether the promotion happens in this run; the
    -- column's TYPE is `admission_test` if it does and TEXT + its own CHECK if
    -- it does not, and nothing else in this design changes either way.
    --
    -- NULL is the published NO-SCORE row -- the tier a test-optional applicant
    -- lands in. That row is why this is nullable: dropping it would lose the
    -- number that makes "a score is worth $5,000 more than no score" sayable,
    -- and inventing a zero band would be our arithmetic.
    test               admission_test NULL,

    -- The band, on the test's own published scale, inclusive both ends. NULL
    -- exactly when test is NULL.
    score_low          INTEGER NULL,
    score_high         INTEGER NULL,

    -- The GPA cell. Both bounds nullable because a school may publish a floor
    -- with no ceiling ('3.50 and above'), and a school may publish no GPA axis
    -- at all. A two-dimensional score x GPA matrix is stored as one row per
    -- CELL, which is what makes Oklahoma State's and Northern State's grids
    -- expressible.
    -- NUMERIC(4,3) AND NOT (3,2), BECAUSE A ROUNDED GPA BOUND IS A MOVED PRICE.
    -- Louisiana Tech publishes two tiers, 3.0-3.749 and 3.75-4.0, at $8,000 and
    -- $8,500 for the same 32 ACT. NUMERIC(3,2) does not REFUSE 3.749 -- it
    -- ROUNDS it to 3.75 -- so the two tiers would overlap at exactly 3.75 and a
    -- 3.75 student would match two cells paying different money, resolved by row
    -- order. Three decimals hold every bound in the curated corpus exactly,
    -- which is this table's whole ethic: store what the page said, and never our
    -- reading of it. (Known limit: a FOURTH decimal would still round, and no
    -- school in the corpus publishes one.)
    gpa_low            NUMERIC(4,3) NULL,
    gpa_high           NUMERIC(4,3) NULL,

    -- Whole dollars per year, INTEGER, unit in the name (0059). NULL where the
    -- school publishes a KIND of award rather than an amount -- 'tuition and
    -- fees', 'tuition plus housing' -- with the words in award_as_published.
    -- The pair is never both absent.
    award_per_year_usd INTEGER NULL,

    -- The school's own words where the award is not a plain per-year figure --
    -- 'tuition and fees', 'tuition plus housing and a stipend'. Of the 571
    -- committed bands, 19 are this shape, and they are the TOP bands: dropping
    -- them would make the coach name a school's second-best award as its best.
    award_as_published TEXT    NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT college_automatic_award_bands_score_iff_test_check
        CHECK ((score_low IS NOT NULL) = (test IS NOT NULL)
               AND (score_high IS NOT NULL) = (test IS NOT NULL)),

    CONSTRAINT college_automatic_award_bands_score_ordered_check
        CHECK (score_low IS NULL OR score_low <= score_high),

    CONSTRAINT college_automatic_award_bands_gpa_ordered_check
        CHECK (gpa_low IS NULL OR gpa_high IS NULL OR gpa_low <= gpa_high),

    CONSTRAINT college_automatic_award_bands_gpa_range_check
        CHECK ((gpa_low IS NULL OR gpa_low BETWEEN 0 AND 5)
               AND (gpa_high IS NULL OR gpa_high BETWEEN 0 AND 5)),

    CONSTRAINT college_automatic_award_bands_award_positive_check
        CHECK (award_per_year_usd IS NULL OR award_per_year_usd > 0),

    -- A band states an amount or the school's words for what it pays. Never
    -- neither: a named band with no value is a heading over nothing.
    CONSTRAINT college_automatic_award_bands_award_has_value_check
        CHECK (award_per_year_usd IS NOT NULL OR award_as_published IS NOT NULL),

    CONSTRAINT college_automatic_award_bands_award_words_nonempty_check
        CHECK (award_as_published IS NULL
               OR (award_as_published = btrim(award_as_published)
                   AND length(award_as_published) > 0)),

    CONSTRAINT college_automatic_award_bands_award_name_nonempty_check
        CHECK (award_name = btrim(award_name) AND length(award_name) > 0)
);

-- The natural key is the ONLY index this table needs. `award_id` leads it, so
-- Postgres serves the one band read this codebase makes from it; a second plain
-- index on `award_id` alone would be a prefix of this one, never the cheaper
-- plan, and one more write on every corpus re-load.
CREATE UNIQUE INDEX college_automatic_award_bands_natural_key
    ON college_automatic_award_bands
       (award_id, award_name, test, score_low, gpa_low)
    NULLS NOT DISTINCT;
```

**A `none_published` row has no bands**, and that is enforced where it can be:
the band table's insert path is the loader, and `AutomaticAwardsDao` refuses at
read time with a named error rather than silently rendering an empty grid. A
cross-table CHECK is not available in Postgres without a trigger, and a trigger
on a corpus written only by the loader buys less than the loader's own refusal
plus the read-side assertion (D28).

### The models, in `:db`

- `enum class AutomaticAwardKind(val value: String)` — `SCORE_GRID`,
  `GPA_ONLY_GRID`, `NONE_PUBLISHED`, with `fromValue`.
- `enum class ScholarshipSuperscoreRule(val value: String)` —
  `SINGLE_SITTING_ONLY`, `SUPERSCORE_ACCEPTED`, `UNSTATED`. The name says
  `Scholarship` because the admissions rule is a different fact and the two must
  never be one type.
- `enum class ScoreRequirement(val value: String)` — `REQUIRED`, `NOT_REQUIRED`,
  `UNSTATED`. Three states, not a boolean, for the reason the DDL states.
- `data class AutomaticAward(...)` and `data class AutomaticAwardBand(...)`,
  mirroring the columns, with `residencyBasis: ResidencyBasis` reusing the
  existing vocabulary. `AutomaticAward` carries its own `bands`, so nothing
  above it holds a second band list that could disagree with the award's own.
- **The READ band's score axis is ONE value, not three nullables.**
  `sealed interface BandScoreAxis` — `NoScore`, the published tier a
  test-optional applicant lands in, and `Range(test: AdmissionTest, low, high)`.
  It is `LastSittingConsidered`'s argument one column along: `test`, `score_low`
  and `score_high` must agree, the schema's score-iff-test CHECK already makes
  the contradiction unstorable, and a model that can still hold it makes every
  reader re-derive "this is the no-score row" from a null. With the axis closed,
  the model's own score-iff-test `require` is DELETED as unreachable and the DAO
  refuses a corrupt triple with a LOCATED error naming the CHECK — which is what
  the `require` could not do.
- **The WRITE model keeps the three nullables, deliberately.**
  `NewAutomaticAwardBand` still carries `test: AdmissionTest?`, `scoreLow` and
  `scoreHigh`, because the loader must be able to HOLD a contradictory CSV row
  in order to REFUSE it at the door with a defect naming the file, the line and
  the cell. A write model that could not express the bad row would report it as
  a parse failure with no cell in it. So the closed value sits on the model
  nothing hand-types, and the open one on the model that reads what a human
  typed.
- `New…` counterparts for the loader, the house pattern, and they carry no
  `require` — a landed test states that as the design, and the loader's located
  refusals are where a hand-typed row is judged.
- `sealed interface LastSittingConsidered` — the school's cut-off as ONE value
  rather than three nullables side by side:

  ```kotlin
  sealed interface LastSittingConsidered {
    /** The school's own sentence for the cut-off. On every arm; never empty. */
    val asPublished: String

    /** The school names a period it reads sittings through, at RFC 209 precision. */
    data class Through(val through: PartialDate, override val asPublished: String) : LastSittingConsidered

    /** A cut-off we will not parse (UAB's per-test rule). No sitting is excluded; the words are the answer. */
    data class InWords(override val asPublished: String) : LastSittingConsidered
  }
  ```

  ONE VALUE AND NOT THREE NULLABLES, because "words with no date" is a legal row
  (UAB) and a flat clump of a nullable period beside a nullable quote would let
  Kotlin hold a state the schema's pair CHECK rejects. `PublishedDeadline`, one
  corpus along, is the shape this follows and for the same reason.

### The read: `AutomaticAwardsDao` in `:db`

`listByColleges(session, collegeIds): Result<List<AutomaticAward>>` — one
statement for the awards, one for their bands, joined in Kotlin, and **no
live/expired filtering in SQL**: expiry is a product decision the service makes
and states, exactly as `AdmissionTestCalendarDao` hands the whole calendar to
`TestTimelineService`. There is deliberately **no `today` parameter**: a DAO
that took one would be asked to use it, and a DAO that could not return a stale
row could not let the service tell `Expired` from `NotCurated`. It does not
filter by entry year either, for the same reason — which cycle a student is
applying for is a clock question, and this layer has no clock.

The write half is the loader's door: `upsertAward` and `upsertBand`, each
answering a `WrittenRow<ID : Id>` — the id it wrote and whether the write
changed anything, a named pair rather than a `Pair` that reads identically to
its own reverse at a call site — plus one composed `upsert(row, bands)` that
adds no SQL of its own and is the fixtures' door — so there is still exactly one
statement per table in the file. `upsertBand` refuses a band under a
`none_published` award **in SQL**, by selecting from the award with
`award_kind <> 'none_published'`, so the guarantee is part of the statement at
the only door that writes a band.

And `deleteBandsExcept(session, awardId, keep)`, which is a **correctness
requirement rather than tidiness**: a natural-key upsert is idempotent for an
UNCHANGED corpus and not for a CORRECTED one. A re-read that DROPS a published
cell leaves the old cell behind, and the coach then quotes a school paying money
it stopped paying — the same class of defect as a mis-keyed unit id, and in the
same direction. With the prune the loader is a true replace-in-place per award,
inside one transaction, with the natural key still catching a cell duplicated
within the CSV, and the run summary states `bands_pruned`.

### The answer: `AutomaticAwardService` in `:service`

Package `ed.unicoach.coaching.testing`, beside `TestTimelineService`, because
the date half of the answer is that service's arithmetic.

```kotlin
sealed interface AutomaticAwardAnswer {
  val collegeId: CollegeId

  /** We have not read this school. The old sentence still holds. */
  data class NotCurated(override val collegeId: CollegeId) : AutomaticAwardAnswer

  /** We read the page and the school publishes no automatic award. */
  data class NonePublished(...) : AutomaticAwardAnswer

  /** The school publishes an automatic table and a score does not change it. */
  data class NoScoreEffect(...) : AutomaticAwardAnswer

  /** A score is money here. */
  data class ScoreBands(
    override val collegeId: CollegeId,
    val award: AutomaticAward,           // carries its own bands
    /** One standing per test the school publishes bands for; empty when no score is stored. */
    val standings: List<StudentStanding>,
    val lastUsableSitting: TestSitting?, // null when no score deadline is published
    /** WHICH of the school's two bounds excluded the next sitting after it. */
    val boundBy: Set<AwardDateBound>,
    val closedSittings: List<TestSitting>,
    /** The school's cut-off in words where we will not parse it (UAB): it excludes no sitting and is quoted. */
    val quotedCutOff: String?,
  ) : AutomaticAwardAnswer

  /** The row exists but is no longer believed (expires_on has passed). */
  data class Expired(...) : AutomaticAwardAnswer
}
```

Five arms, not four: `Expired` is the D7/D20 runtime refusal and it must not
masquerade as `NotCurated`, because "we read this school and our reading is
stale" and "we never read this school" send a family to two different places.

**The date half reuses the landed engine and adds nothing.**
`TestTimelineService.sittingOf(row, today, anchorDate)` is `internal`, takes an
arbitrary anchor and mentions no college, application or CDS concept, so the
scholarship score deadline passes straight in and `SittingVerdict`/`TestSitting`
come back unchanged. `sittingOf` and `SittingVerdict` are already `internal` in
this module, so the new service reaches them with **no edit to that file** — it
is not in the Files Modified table below. Their arm ORDER is a stated product
decision (`TestTimelineService.kt:148-154`) and is reused, not re-decided. The
last usable sitting is
`sittings.filter { it.verdict.usableForAnchor }.maxByOrNull { it.testDate }` —
computed at read time and labelled derived, never stored
(`AdmissionTestAdministration.kt:60-62`).

**Two constraints, not one, and the tighter one wins.** A sitting is usable only
if BOTH hold: its score release is not after the school's receipt deadline, and
its test date is not after the school's last-considered sitting. The second is
the one a receipt-deadline-only model gets wrong — UT Knoxville receives scores
until May 15 but considers no sitting after December 2026, so the February and
April sittings are worthless there. Where the school publishes a cut-off in
words we cannot parse (UAB's per-test rule), no sitting is excluded by it and
the answer carries the school's sentence for the student to read. The answer
says WHICH constraint bound it, because "registration has closed" and "this
school stopped counting sittings in December" send a student to two different
decisions.

`TestTimeline.Resolved`'s own `anchor` field is application-typed and is **not**
widened: this service holds its own anchor. That keeps
`TestTimelineService.READ_STATEMENTS = 7` untouched.

**The student's standing in the grid (D-H).** `AdmissionTestScoresDao`'s rows
ARE sittings, one row per component. Two figures, both typed, never conflated:

```kotlin
/**
 * ONE STANDING PER TEST, because the two scales are not comparable: a 36 and a
 * 1450 have no ordering, so a single `maxByOrNull` over the SAT_TOTAL and
 * ACT_COMPOSITE rows together would always be the SAT row and a student's ACT
 * would silently never be read — against D13, which says the SAT is never the
 * default noun.
 */
data class StudentStanding(
  val test: AdmissionTest,
  val bestSingleSitting: SittingScore?,     // null where she stored sections and no total
  val combined: CombinedScore?,             // null unless permitted AND computable
  val combinationRefusal: CombinationRefusal?, // WHY there is no combination, named
  val figureRead: Int?,                     // the figure the SCHOOL's rule reads
  /**
   * Every cell that figure reaches. A LIST AND NOT ONE CELL: a two-dimensional
   * score x GPA grid (Oklahoma State, Northern State) has one cell per GPA row
   * at a given score and WE HOLD NO GPA, so naming one would be picking a GPA
   * the student never gave us.
   */
  val bandsReached: List<AutomaticAwardBand>,
  val nextBand: NextBand?,                  // per test, because the gap is on that test's scale
)

/** OUR arithmetic over the school's stated rule, never a score the school holds. */
data class CombinedScore(
  val test: AdmissionTest,
  val score: Int,
  val sittingsUsed: List<PartialDate>,      // at the precision she remembers (RFC 209)
  val rule: ScholarshipSuperscoreRule,      // always SUPERSCORE_ACCEPTED here
  val actSubjectsAveraged: Int?,            // ACT's own divisor: 3 enhanced, 4 legacy
)
```

`sittingsUsed` is `PartialDate` and not `LocalDate` because
`admission_test_scores` stores a sitting at the precision the student remembers
(RFC 209/D23) and a year-only sitting has no day; `LocalDate` would invent one.

A standing exists exactly when we hold something of hers on that test — a
refusal-only standing is a real answer, because a `MixedActSectionHistory`
computed and then dropped on the floor is the reason we could not answer, lost.
It is NOT "no figure and no refusal": `RuleDoesNotPermit` is answered for every
single-sitting school whether or not anything is stored, so that rule alone
would invent a standing for a student we hold no score for.

`bestSingleSitting` is `maxByOrNull` over that test's own `SAT_TOTAL` /
`ACT_COMPOSITE` rows. `combined` is computed only when the award's
`superscore_rule = 'superscore_accepted'` AND the sections exist:

- **SAT** — `max(sat_reading_writing) + max(sat_math)` across sittings.
  Arithmetic on the published scale; no citation needed beyond the scale itself
  (`PublishedScoreScale`).
- **ACT** — read ACT's own pages on 2026-09-24 (all HTTP 200, recorded in
  `<run scratch>/design/act-composite-rule.md`) and the naive four-subject
  average is **wrong today**:

  > "Your Composite score is the average of your English, math, and reading
  > scores, rounded to the nearest whole number." — act.org, _Understanding your
  > scores_

  > "Fractions less than one-half are rounded down; fractions one-half or more
  > are rounded up." — act.org

  Three consequences, and each one is a defect if missed:

  1. **Science left the Composite** between April and September 2025 for
     national testing, and state and district testing completes the change in
     spring 2026. So the divisor is three for an enhanced sitting and four for a
     legacy one.
  2. **A mixed history has no published divisor.** ACT states both rules and
     never says which wins for a student whose sittings span the change. So a
     mixed set is **refused with a named reason** and no number — choosing a
     divisor there would be our interpretation wearing ACT's authority. The
     section set stored per sitting is what decides: all contributing sittings
     must carry the same set, and that set must be `{english, math, reading}` or
     `{english, math, reading, science}`.
  3. **Rounding is half-UP, not half-even.** `Math.round`/`HALF_UP`, never
     `BigDecimal`'s default and never Kotlin's `round` on a `Double` — 28.5 must
     become 29. Asserted directly, because the wrong rounding mode is silent and
     costs a band.

  And a naming rule: **we never call our figure an "ACT Superscore".** ACT's
  Superscore is ACT-computed and ACT-reported; ours is "a combined score from
  your best sections", labelled as ours.

  The rule's source page is a row in `external_documents` with its link status
  (RFC 220), resolved by url at read time; the ACT combination is refused while
  that row is absent, so the citation cannot silently go missing. ACT's own
  higher-education page still prints the stale four-subject sentence with no
  qualifier and **must not be cited** for this rule.

Where the figures disagree, the answer carries both and names which one the
school's rule reads. The cells are resolved from the figure the SCHOOL permits:
the single sitting for `single_sitting_only` and `unstated`, the combination
where one was computed. Each of the refusals above is an arm of
`sealed interface CombinationRefusal` — `RuleDoesNotPermit`, `NoSectionsHeld`,
`MixedActSectionHistory`, `IncompleteActSections`, `ActRuleNotCited` — because
"this school reads one sitting" and "we cannot divide your history" are
different sentences, and one is about the school's money while the other is
about our arithmetic.

`nextBand` is the slice's headline sentence: the lowest band above the one the
student's figure reaches, with the point gap and the dollar step. It rides on
the standing for that test, because the gap is on that test's own scale. It is
absent when no figure is read, when the student is already in the top band, and
when the school is not a `score_grid`. Where a combination is permitted, the gap
is measured from the figure the school reads — a student ten points short on a
single sitting may be zero points short once their sections are combined, and
saying otherwise would send them to a retake they do not need.

### The coach door: `college_automatic_awards` chat tool

`StudentScopedChatTool`, input schema `putCollegeIdsSchema()` only — the same
shape as `admission_test_timeline`. Registered in `Application.kt` beside it.

Wire shape, one object per requested college, and inside it one object per GRID
— a school can publish several, one per `residency_basis` — each grid carrying
its own `answer` discriminator, so neither one school's rule nor one residency's
grid can be read onto another. Two contracts this tool must honour deliberately:

- **It emits dollar figures, which `admission_test_timeline` never does**
  (`NUMBERS_BY_CONTRACT = emptySet()`). This tool declares its numbers by
  contract explicitly — the award amounts, the score bands and the point gap —
  so the concise-answer sweep (RFC 203) knows they are intended.
- **Cite once per block** (RFC 190): the publisher and the read date ride ONCE
  per GRID, at that grid's head, never per band. Once per grid and not once per
  school, because each grid is its own award row with its own
  `source_document_id` — a school publishing an in-state and an out-of-state
  grid states two citations, and merging them would attribute one page's words
  to the other.

### The report door: `report-award-grid`

A new sibling block inside `report-school`, between `meritBlock` and `debtBlock`
(`CostReportPage.kt:671-672`). It is NOT nested inside `report-merit`, which
carries one citation for everything under it — a second publisher under that
header breaks RFC 190.

It renders the school's own bands, the submission requirement, the score
deadline in `UsDate` form, and nothing about the student (D-E). Its data
dependency is a new `automaticAwards: List<AutomaticAwardAnswer>` field on
`CollegeCost`, read in `CollegeCostService.readInSession` beside the batched
reads already there, and the render function returns before opening its section
on any arm but `ScoreBands` and `NoScoreEffect` — the `borrowingParagraphs`
exhaustive-`when` shape, no `else`.

Those answers come from `AutomaticAwardService.gridAnswersInSession`, which
takes no `StudentId` and reads no score — so D-E's ruling is enforceable at the
SOURCE and not only in the renderer, and the five-arm rule has one home rather
than a second `award_kind`-to-arm mapping inside `CollegeCostService`.

A LIST and not one nullable answer, because residency is part of the corpus's
key: of the 40 curated colleges, 20 publish TWO grids, an in-state one and an
out-of-state one. A single field would have to pick a residency, and residency
for tuition purposes is a legal status the SCHOOL determines — the service
refuses to pick it for exactly that reason and this field refuses with it. One
labelled `report-award-grid` block per grid, each with its own citation.

The standing sentence **"An automatic scholarship is published by the school and
is not a financial aid offer."** goes in `report-kinds`, once for the page (RFC
191), never per school. "Scholarship" and not "award": this page may not say
"award" at all — RFC 141's vocabulary, executed by the sweep in
`CostReportPageTest` — because that word makes published money sound granted,
which is the exact confusion this sentence exists to prevent. A school's own
band names and its own published words are quoted verbatim and are a different
thing: RFC 141 governs our vocabulary, not a quotation of theirs.

### The corpus and its loader

    db/seed/automatic-awards/awards.csv        # one row per college x entry year x residency
    db/seed/automatic-awards/bands.csv         # one row per published cell
    db/seed/automatic-awards/link-status.csv   # what bin/check-link answered per url
    db/seed/automatic-awards/PROVENANCE.json   # the corpus record, incl. expires_on

`awards.csv` keys the school by `unit_id` **and carries the school's name as
typed**, which the loader asserts against `colleges.name` for that unit id and
**refuses** on disagreement. That guard is not decoration: a fuzzy match of
these 48 names returned 8 wrong schools, and a mis-keyed row here is another
school's money under this school's name. A `unit_id` absent from `colleges` is
also a refusal, not a skip — 60 hand-typed rows over 40 schools have no
acceptable silent-drop.

Loading joins the CDS path: `bin/ingest-colleges` gains one all-or-nothing flag
group (`-w`/`-b`), defaulted in `bin/load-external-data` with no flag to
override. `bin/ingest-colleges` is a JVM launcher, so the PHASE itself is Kotlin
in `college/` beside the CDS loader, exactly as every other ingest phase is; the
shell half is the two flags and their resolution. The urls are upserted into
`external_documents` first with the `link-status.csv` answers (RFC 220: a
citation states its evidence or it cannot be stored). `link-status.csv` is read
from BESIDE the awards CSV and is not a third flag, so it cannot be paired with
another corpus's answers, and a missing one is fatal rather than tolerated.

The band load is a **replace-in-place per award**, not an append: the phase
deletes every band of an award this run did not write, in the same transaction,
and the run summary states `bands_pruned`. The reason is in
`AutomaticAwardsDao.deleteBandsExcept` above — a corrected re-read that drops a
cell must drop the row, or the coach quotes money the school stopped paying.

The corpus's urls are checked with **`bin/check-link` directly** and the answers
are committed, exactly as the two landed hand-typed corpora
(`test-state-programmes`, `fafsa-deadlines`) commit theirs: a THREE-column
`url,url_status,url_checked_on` file, which is `external_documents`' own column
names and what the landed reader
`AdmissionTestStateProgrammeFixture.committedLinkStatuses()` reads.
**`bin/cds-link-sweep` is untouched**: it is hard-wired to `db/seed/cds` —
harvesting `source_url`/`archive_url` out of that directory — and writes a
five-column file of its own shape. Giving this corpus the same producer is a
later run's work, not a licence to widen that script here.

### Staleness (D20, D-G)

`AutomaticAwardSeedTest` in `:db` reads the CSVs **from disk**, never the
database, and fails the build when any row is within
`HandTypedReferenceData.REFRESH_LEAD` (90 days) of its `expires_on`, when the
corpus is below a minimum authored size, or when `PROVENANCE.json` disagrees
with the rows. This is `AdmissionTestCalendarSeedTest:210-237` one corpus along,
with the same shared constant and no private copy of the number.

At runtime the service answers `Expired` for a row past `expires_on`, with the
`>` and not `>=` predicate the two landed corpora already share.

## Files Modified

**New**

| Path                                                                   | What                                                                                 |
| ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `db/schema/create-college-automatic-awards.sql`                        | the two tables, the natural keys, and (D-J) the `admission_test` domain              |
| `db/seed/automatic-awards/awards.csv`                                  | one row per college × entry year × residency, hand-typed, reviewable in a diff (D19) |
| `db/seed/automatic-awards/bands.csv`                                   | one row per published cell                                                           |
| `db/seed/automatic-awards/link-status.csv`                             | `bin/check-link`'s answer per url (RFC 220)                                          |
| `db/seed/automatic-awards/PROVENANCE.json`                             | the corpus record, including `expires_on`                                            |
| `db/src/main/kotlin/…/models/AutomaticAward.kt`                        | the two row types, both enums, `New…` counterparts                                   |
| `db/src/main/kotlin/…/dao/AutomaticAwardsDao.kt`                       | the two-statement read, plus the loader's write door and its band prune              |
| `db/src/testFixtures/…/AutomaticAwardFixture.kt`                       | fixture + the committed-CSV reader the seed test uses                                |
| `db/src/test/…/dao/AutomaticAwardsDaoTest.kt`                          | DAO + every CHECK                                                                    |
| `db/src/test/…/dao/AutomaticAwardSeedTest.kt`                          | the D20 staleness guard over the CSVs on disk                                        |
| `college/src/main/kotlin/…/college/AutomaticAwardSeedLoader.kt`        | the loading phase itself: the three-file read, the closed defect set, the refusals   |
| `college/src/test/kotlin/…/college/AutomaticAwardSeedLoaderTest.kt`    | the loader's refusals, asserted with a real wrong unit-id/name pair                  |
| `service/src/main/kotlin/…/coaching/testing/AutomaticAwardService.kt`  | the five-arm answer and the date arithmetic                                          |
| `service/src/main/kotlin/…/coaching/testing/AutomaticAwardChatTool.kt` | the `college_automatic_awards` tool                                                  |
| `service/src/test/…/coaching/testing/AutomaticAwardServiceTest.kt`     |                                                                                      |
| `service/src/test/…/coaching/testing/AutomaticAwardChatToolTest.kt`    |                                                                                      |
| `service/src/test/…/coaching/testing/AutomaticAwardTestDb.kt`          | the `:service` fixture database for the two suites above                             |
| `db/schema/seed-coach-system-prompt-v45.sql`                           | minted by `bin/prompt-seed` for the D-F prompt change; never hand-written            |

**Changed**

| Path                                                                          | What                                                                                                                                                                                                          |
| ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/ORDER`                                                             | one line, placed by `ship-order` at land (RFC 180)                                                                                                                                                            |
| `bin/ingest-colleges`                                                         | the `-w`/`-b` all-or-nothing flag group only, resolved and forwarded as `--awards`/`--award-bands`; it is a JVM launcher, so the phase itself is Kotlin                                                       |
| `college/src/main/kotlin/…/college/IngestApplication.kt`                      | the `--awards`/`--award-bands` group and its run report; the CDS two-token parse loop generalised to one shared `PAIRED_FLAGS`, so both groups share one repeated-flag and empty-value refusal                |
| `college/src/main/kotlin/…/college/CollegeScorecardLoader.kt`                 | the phase, ordered beside the CDS one, and its result on the run report                                                                                                                                       |
| `bin/load-external-data`                                                      | the two new paths defaulted, no flag to override (RFC 172)                                                                                                                                                    |
| `bin/scripts-tests`                                                           | the harness over the new `bin/ingest-colleges` group                                                                                                                                                          |
| `db/src/main/kotlin/…/models/HandTypedReferenceData.kt`                       | its KDoc says a hand-typed corpus ships as a generated seed migration; one now ships with the ingest, so the sentence widens                                                                                  |
| `db/src/main/kotlin/…/models/ExternalDocument.kt`                             | a read-shaped model, as its own KDoc said it would arrive with the first caller that needed it                                                                                                                |
| `db/src/main/kotlin/…/dao/ExternalDocumentsDao.kt`                            | `findByUrl`: the ACT rounding citation must be ASKED about without being created, and `idOf` would create it                                                                                                  |
| `db/src/test/…/dao/AdmissionTestCalendarDaoTest.kt`                           | D-J's cost: its corrupt-row fixture moves the column TYPE instead of dropping the CHECK the domain deletes; assertion unchanged                                                                               |
| `service/src/main/kotlin/…/coaching/testing/AdmissionTestTimelineChatTool.kt` | its `DESCRIPTION` stops saying we hold no scholarship score rule and points at the new tool                                                                                                                   |
| `service/src/main/kotlin/…/coaching/costs/CollegeCostService.kt`              | `CollegeCost.automaticAwards`, one batched read in `readInSession`                                                                                                                                            |
| `public-web/src/main/kotlin/…/render/CostReportPage.kt`                       | the `report-award-grid` block and one sentence in `report-kinds`                                                                                                                                              |
| `public-web/src/test/…/FakeCostReportSource.kt`                               | the new field on the fake, so the renderer's tests can set it                                                                                                                                                 |
| `rest-server/src/main/kotlin/…/Application.kt`                                | the new tool registered beside `admission_test_timeline`                                                                                                                                                      |
| `prompts/coach-system-prompt.md`                                              | lines 62/64/82/88 narrowed (D-F); `bin/prompt-seed` mints the version at land                                                                                                                                 |
| `service/src/main/resources/service.conf`                                     | the `systemPromptVersion` pin and its rollback note, minted by `bin/prompt-seed`; never hand-written                                                                                                          |
| `service/src/test/…/coaching/testing/AdmissionTestTimelineChatToolTest.kt`    | the assertion at `:347-353` follows the narrowed wording                                                                                                                                                      |
| `service/src/test/…/coaching/SystemPromptCatalogTest.kt`                      | the assertion at `:3457-3469` follows the narrowed wording; the "award" sweep is asked of PROSE, so the tool's snake_case name is stripped before the check — and both callers assert the strip did real work |
| `service/src/test/…/coaching/testing/TestScoreCopyTest.kt`                    | the verbatim line 88 sentence follows its narrowing                                                                                                                                                           |
| `public-web/src/test/…/CostReportPageTest.kt`                                 | the new block, present and absent                                                                                                                                                                             |
| `bin/gate-scope`                                                              | nothing, unless a new `bin/*scripts-tests` is added — then it is named there or the gate fails by name                                                                                                        |

## Implementation Plan

1. **The migration.** The two tables, the natural keys, and D-J's domain
   promotion if approved. `nix develop -c bin/db-migrate` against the test
   database, then `bin/db-query` to confirm every CHECK refuses what it should.
2. **The models and the DAO**, with `AutomaticAwardsDaoTest` proving each CHECK
   by a failed write, not by reading the DDL.
3. **The corpus.** Convert the two curation JSON files into the two CSVs. Every
   `unit_id` is already resolved against the loaded `colleges` table and
   confirmed by state and name — that step has the measured failure mode (8 of
   48 fuzzy matches were the wrong school), and the loader asserts the pair
   again at load time.

   Two bounded re-reads finish the corpus, both against pages we already hold
   the url for:

   - **20 bands with no dollar figure** (the top bands: 'Presidential Elite',
     'tuition and fees') need the publisher's own words for what they pay, or
     the coach names a school's second-best award as its best. 19 were filled
     from the publisher's own page, and 1 was deleted: Iowa State's out-of-state
     `Academic Achievement Award` at GPA 3.00–3.09, where the page prints `--`
     because the award pays NOTHING at that GPA. The schema has no zero band
     (`…_award_positive_check` requires `> 0`) and refuses a band with neither
     figure nor words, so the row is gone and the gap is named in
     `PROVENANCE.json`. That leaves **571 committed bands, 19 of them carrying
     the school's words instead of a figure**.
   - **The ACT composite rounding rule** (D-H) needs ACT's own page before any
     ACT combination is computed.

   Neither is a judgement call; both are a page and a quote.
4. **The loader phase**, in `college/` behind `bin/ingest-colleges`' new
   `-w`/`-b` group and defaulted in `bin/load-external-data`:
   `external_documents` first, then awards, then bands, with the award's stale
   bands pruned in the same transaction; refuse an unknown `unit_id`, a name
   disagreement, a band under a `none_published` row, or a url with no committed
   link status.
5. **The service**, with the reused `sittingOf` arithmetic and the five arms.
6. **The chat tool**, its wire shape and its declared numbers.
7. **The cost-report block** and the one `report-kinds` sentence.
8. **The prompt**, then `nix develop -c bin/prompt-seed -b "$BASE_SHA"` before
   the suite — `SystemPromptCatalogTest` cannot go green until the seed is
   generated.
9. **The suite**, through `ship-run … -g`, whole and unscoped.

## Tests

**Schema and corpus**

- Every CHECK refuses: a band with a score and no test, a `none_published` row
  with a deadline, a deadline outside its cycle window,
  `expires_on <= checked_on`, an empty `as_published`, an archive that is its
  own source, a fourth residency basis, an unordered band.
- The natural keys collide on a second row for the same school/year/residency
  and for the same band cell.
- `AutomaticAwardSeedTest`: the 90-day staleness bar fails on a row near expiry
  and passes once re-read; `PROVENANCE.json` agrees with the rows; the fixture
  header equals the loader's expected header; a minimum authored size.
- The loader refuses an unknown `unit_id` and refuses a name disagreement — the
  measured failure mode, asserted with a real wrong pair — and a refused row
  leaves nothing behind, not even the rows that read cleanly.
- A second run of the same corpus writes nothing and changes nothing; a re-read
  that DROPS a band drops it from the table too, which is what the prune is for.

**Service**

- A `score_grid` school with a stored score: the band, the dollars, the
  requirement, the scholarship superscore rule, and the last usable sitting.
- The same school with **no** stored score: bands and deadline still answered,
  `standings` empty, nothing gated (0001 D11).
- `NoScoreEffect`, `NonePublished`, `NotCurated` and `Expired` each answered
  distinctly — and a test that the four are not collapsed, because the whole
  point is that a family is sent to four different places.
- `nextBand`: the point gap and the dollar step; absent in the top band.
- The combination (D-H): at a school whose page accepts one, two sittings whose
  best sections cross a band produce the higher band, with the dates used; at a
  single-sitting school the SAME two sittings produce the lower band and the
  answer says why. A sitting stored as a bare total contributes no sections. The
  ACT combination is refused with a named reason while its rounding-rule
  citation is absent, and computed once it is present.
- The ACT rules specifically: a three-section history averages three and a
  four-section history averages four; a MIXED history is refused with its own
  named reason and no number; 28.5 rounds to 29 (half-up, asserted directly,
  because half-even silently costs a band); and no answer ever calls our figure
  an "ACT Superscore".
- The three-state fields, each read as the school published it: a school that
  publishes **no superscore rule** is read one sitting at a time — the
  `unstated` arm refuses a combination with `RuleDoesNotPermit`, on the same two
  sittings that DO combine at a school whose page accepts one; and a school that
  states **neither** rule says so on the wire and quotes nothing, both
  three-state fields `unstated` and both `_as_published` keys `JsonNull`.
- The expiry predicate is `>` and not `>=`, pinned by the PAIR: a row is still
  believed on its expiry day (2027-07-31 answers) and stale the morning after
  (2027-08-01 answers `Expired`).
- The date arithmetic: a deadline the December sitting cannot make; a residency
  that changes the last usable sitting (the measured UAB case); registration
  already closed; a score release not yet announced; no published deadline at
  all.
- Both constraints, separately and together (the measured UT Knoxville case): a
  sitting that makes the receipt deadline but falls after the last-considered
  sitting is NOT usable, and the answer names which constraint bound it. A
  words-only cut-off excludes no sitting and is quoted instead.
- A case deliberately unlike the worked example (D22): a senior past every
  deadline, a student with an empty list, a school in a state with no grid.

**Wire and doors**

- The tool: one object per requested college, one grid object per published
  residency grid inside it, the `answer` discriminator on the grid, the citation
  once per grid (RFC 190), the declared numbers, an unknown college id.
- The cost report: the block present for a `ScoreBands` school, absent for
  `NotCurated`, and carrying **no** student score (D-E) — asserted, because that
  is the privacy ruling this RFC declines to make.
- The prompt: the narrowed sentences, and the catalog test green after
  `bin/prompt-seed`.

**Not tested here**: the coach's actual prose. That is the model's, and the
tool's contract is the wire shape plus the prompt rule.
