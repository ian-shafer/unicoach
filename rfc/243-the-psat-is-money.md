# RFC 243 — the PSAT is money

Slice `test/08/the-psat-is-money` (brief 0010, handle `test`). Lane A. The last
slice of brief 0010.

No college ever sees a PSAT/NMSQT score. It is not an admissions test, and this
RFC's first job is to stop the coach ever calling it one. It is a **money**
event: it is the only gate to National Merit, and Finalist standing triggers
automatic university packages that dwarf every score-band award RFC 239 landed.
Measured on 2026-09-25, from each school's own page: **Oklahoma State publishes
$154,500** for a non-resident Finalist entering in 2027-28, Arizona State
$17,500 a year out of state, USC $20,000 a year, Mississippi State $48,000 over
four years, and Auburn $11,000 a year — **to Alabama residents only**. The
University of North Carolina at Chapel Hill says in its own words that it "no
longer participates in offering college-sponsored scholarships to National Merit
finalists", which is a different answer from silence and is stored as one.

**And the headline this RFC opened with in draft is gone, which is the point.**
The $153,450 the brief's research recorded at the University of Oklahoma is
published against **Fall 2026** entry; so are Texas Tech's (whose page still
prints a 2022 deadline), UT Dallas's, Maine's and Ole Miss's. Under this
design's own staleness rule they are not rows — they are re-reads owed. Four of
the five schools that made this slice look impressive in September could not be
seeded today, and a corpus that had trusted its own research would have quoted
last year's dollars at this year's family.

It is school-administered in an October window, the student does not register,
and **only the junior-year sitting qualifies**. A sophomore's sitting does not
count, and there is one window a year, so there is no retake — but there IS an
alternate entry route, by letter, and it has its own deadline. "Miss it and it
is gone forever" is false, and the brief corrected itself in writing before this
slice was designed.

Against that, the base rate: ~3.5M sit the test, ~1.2M meet entry requirements,
~16,000 reach Semifinalist, ~15,000 Finalist, ~6,700 win. **~1.25%.** Every
six-figure number above is a number the family will almost certainly not get,
and a coach that says the first without the second is selling a lottery ticket.

## What binds this design before it starts

Five corrections in the brief (2026-09-10, verified against NMSC's own
2028-cycle pages, `product/0010-when-do-i-test/research/psat-national-merit.md`)
bind every sentence here:

1. **There is an alternate entry route.** A student who misses the test through
   illness or emergency writes to NMSC (`ae@nmerit.net` or post), received or
   postmarked by **April 1, 2027** for the 2028 competition. The coach says so.
2. **nationalmerit.org was never blocked.** The earlier 403 was a User-Agent
   filter. Every NMSC claim is quoted from NMSC's own pages.
3. **The money is not NMSC's.** NMSC's own award is $2,500 once, and its
   college-sponsored awards are capped at $500-$2,000/yr and require naming that
   college first choice. The large numbers are **university-owned** packages,
   each verified on that university's own page.
4. **The base rate must be visible, and the cutoff is per STATE.** Semifinalist
   standing is allocated state-representationally; Commended is a single
   national number. NMSC publishes no cutoff in advance and says outright that
   qualifying scores "vary from state to state and from year to year".
5. **Currency risk is real.** Auburn cut its award to in-state only. Three
   schools publish nothing. Every row needs its source, its cycle and the day we
   read it, with an annual human re-check — this cannot be scraped unattended.

And two standing decisions of the brief, both approved at gate 1:

- **D9 (standing)** — never test prep, never score prediction, never chancing.
  This RFC stores no cutoff number and computes no probability beyond the
  published base rate.
- **D22** — the brief is not delivered against a calendar and is not designed
  for one user. Today is 2026-09-25 and the window opens in six days; that
  changes nothing about the design, and no test, figure or line of copy encodes
  a junior in a particular state with a particular list.

## Detailed Design

### What lands

1. **`national_merit_cycles`** — the programme's own dated facts, one row per
   competition year, seeded by a migration. The window, the alternate-entry
   route and its deadline, the funnel counts, and NMSC's own sentence saying the
   qualifying score is set per state and not published in advance.
2. **`college_national_merit_awards`** — what a named school pays a Finalist,
   loaded by the ingest run, keyed by `colleges(id)`, with no score column at
   all.
3. **One new chat tool, `psat_national_merit`** — the student's own
   year-of-school arm, the cycle facts, the base rate, and the schools'
   packages. It is the door for every reactive answer.
4. **One proactive moment** — a new reflection the sweep writes, in code, for a
   junior when the window is near. The one proactive moment in this brief.
5. **Coach prompt prose** — what the PSAT is, what it is not, the base-rate
   rule, and the cutoff refusal.

No table stores a cutoff. No code predicts a score. No figure the coach speaks
comes from the model's memory.

### The two data homes, and the rule that chose each

The repo already has a written rule for this, and it is a **key** test, not a
size or subject test (RFC 239 D-C, restated in
`create-college-automatic-awards.sql:12-20` and
`HandTypedReferenceData.kt:15-23`): a hand-typed corpus keyed by a state or by
nothing can be seeded by a migration; a corpus keyed by anything the import run
fills cannot, because a migration-time seed would point at an empty table. That
is D27's measured failure — 112 of 113 migrations applied and then
`Key (state)=(AL) is not present in table "us_states"`.

This slice has one corpus of each kind, so it uses both routes, and neither
choice is a preference:

| Corpus                          | Keyed by                            | Route                                                                          |
| ------------------------------- | ----------------------------------- | ------------------------------------------------------------------------------ |
| `national_merit_cycles`         | a competition year and nothing else | **A** — `bin/national-merit-seed` generates a committed `db/schema/seed-*.sql` |
| `college_national_merit_awards` | `colleges(id)`                      | **B** — the ingest run, beside RFC 239's awards                                |

Route A matters more than it looks: `bin/test` does `db-reset` (drop, create,
migrate) and **never ingests**, so Route B rows are absent from every test
database and from a fresh deployment until a full re-ingest. The programme facts
— the window, the deadline, the base rate — are therefore present in every
database by construction, and only the per-school dollars wait for an ingest.
That is also the honest shape of the two answers: "here is what this test is and
when it is" must never depend on an import having run.

Both are bound by **D19** (a committed source file plus `PROVENANCE.json`, so
hand-typed data is reviewable in a diff) and **D20** (staleness is a build
failure: a live row within 90 days of its expiry fails the suite).

### A. `national_merit_cycles`

One row per competition year, named the way NMSC names it: the **2028**
competition is the October 2026 sitting, taken by the class that finishes high
school in 2028. That is exactly `students.expected_high_school_graduation_year`,
so the join from a student to her cycle is her own stored graduation year and
nothing inferred.

The migration is verbatim below — this is the file, not a summary of it.

```sql
-- national_merit_cycles -- the National Merit competition's OWN dated facts,
-- one row per competition year (RFC 243, slice test/08/the-psat-is-money,
-- brief 0010).
--
-- HAND-TYPED REFERENCE DATA (brief 0010 D19), ROUTE A: keyed by a competition
-- year and nothing else, so a migration can seed it -- and must, because
-- `bin/test` does db-reset and never ingests. "What this test is and when it
-- is" can never depend on an import having run. The corpus lives at
-- db/seed/national-merit/{cycles,link-status}.csv with its PROVENANCE.json and
-- is generated into a committed seed by bin/national-merit-seed.
--
-- THERE IS NO CUTOFF COLUMN, and that is the design (correction 4, D9). NMSC
-- allocates Semifinalist standing state-representationally, publishes no
-- qualifying score in advance, and says outright that the score varies by state
-- and by year. A column here would be filled with a third party's estimate and
-- spoken as hers. What we store instead is HOW the bar is set, plus NMSC's own
-- sentence saying it is not published.
--
-- EVERY ROW EXPIRES (D20), guarded 90 days out by the one shared
-- HandTypedReferenceData.REFRESH_LEAD, over LIVE cycles only -- a guard that
-- counts dead cycles can never be cleared.
--
-- Reference-table shape (0060/0077/RFC 197/RFC 208/RFC 239 precedent):
-- unversioned, no soft delete, no optimistic concurrency, created_at/updated_at
-- with the plain update_colleges_timestamp() trigger.

CREATE TABLE national_merit_cycles (
    -- NMSC's own name for the cycle, and the student's graduation year: the
    -- 2028 competition is the October 2026 sitting, taken by the class of 2028.
    -- That is exactly students.expected_high_school_graduation_year, so the
    -- join from a student is her own stored year and nothing inferred.
    --
    -- SMALLINT with its own CHECK and deliberately NOT the academic_year
    -- DOMAIN (0087): that domain means "the academic year beginning in", so
    -- borrowing it would make 2028 mean 2028-29 here and Fall 2028 next door.
    competition_year              SMALLINT    NOT NULL PRIMARY KEY,

    -- The only sitting that qualifies. Stored rather than hard-coded because it
    -- is a published rule of the programme and the coach must not speak it from
    -- memory. 11 in every row we hold.
    qualifying_grade              SMALLINT    NOT NULL,

    -- The in-school window. DATES ONLY WHERE NMSC PUBLISHES THEM, plus its own
    -- words always: a cycle two years out is described ("October of the junior
    -- year") long before it is dated, and the coach answering a sophomore needs
    -- the description without inventing a date.
    window_opens_on               DATE        NULL,
    window_closes_on              DATE        NULL,
    window_as_published           TEXT        NOT NULL,

    -- The alternate entry route (correction 1): a student who misses the window
    -- through illness or emergency writes to NMSC, and the letter has its own
    -- deadline. This is why "miss it and it is gone forever" is false, and the
    -- reason the coach can be honest about a missed October.
    alternate_entry_deadline      DATE        NULL,
    alternate_entry_contact       TEXT        NULL,
    alternate_entry_as_published  TEXT        NULL,

    -- What kind of number the score even is: the Selection Index scale.
    selection_index_low           SMALLINT    NOT NULL,
    selection_index_high          SMALLINT    NOT NULL,

    -- HOW the bar is set -- never what it is. See the header.
    semifinalist_cutoff_basis     TEXT        NOT NULL,
    commended_cutoff_basis        TEXT        NOT NULL,
    cutoff_as_published           TEXT        NOT NULL,

    -- Colleges never see a PSAT score. Quoted, because it is the one sentence
    -- that makes this a money event and not an admissions one.
    score_visibility_as_published TEXT        NOT NULL,

    -- The funnel (correction 4). Every stage NULL-or-positive, because NMSC
    -- publishes some stages and rounds others. THE BASE RATE IS NOT STORED: it
    -- is derived from two of these at read time and labelled as derived, which
    -- is the schema convention for anything computable from stored columns.
    test_takers                   INTEGER     NULL,
    met_entry_requirements        INTEGER     NULL,
    high_scorers_screened         INTEGER     NULL,
    commended                     INTEGER     NULL,
    semifinalists                 INTEGER     NULL,
    finalists                     INTEGER     NULL,
    winners                       INTEGER     NULL,
    funnel_as_published           TEXT        NOT NULL,

    -- NMSC's OWN money, which is small, and its cap on a sponsor college's
    -- award. Here so that "the big numbers are the university's, not NMSC's"
    -- (correction 3) is a stored fact rather than a sentence we remember.
    nmsc_award_usd                INTEGER     NULL,
    nmsc_award_as_published       TEXT        NULL,
    sponsor_cap_as_published      TEXT        NULL,

    -- TWO CITATIONS, because this row has two publishers and no page has all
    -- of it. NMSC sponsors the competition and publishes the entry rule, the
    -- alternate entry route, the funnel and the cutoff sentence. COLLEGE BOARD
    -- administers the test and is the only publisher of the window DATES (NMSC
    -- prints the month and never the days), the Selection Index scale, and the
    -- sentence saying a college never receives the score. Measured by the
    -- curation pass, not assumed: four cells per row are the administrator's.
    -- One citation column would attribute them to NMSC, which is exactly the
    -- mis-citation D26 and RFC 228 exist to prevent.
    publisher                     TEXT        NOT NULL,
    source_document_id            UUID        NOT NULL REFERENCES external_documents(id),
    archive_document_id           UUID        NULL     REFERENCES external_documents(id),
    administrator_publisher       TEXT        NOT NULL,
    administrator_document_id     UUID        NOT NULL REFERENCES external_documents(id),
    administrator_archive_document_id UUID    NULL     REFERENCES external_documents(id),
    checked_on                    DATE        NOT NULL,
    expires_on                    DATE        NOT NULL,

    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT national_merit_cycles_competition_year_check
        CHECK (competition_year BETWEEN 2020 AND 2100),

    CONSTRAINT national_merit_cycles_qualifying_grade_check
        CHECK (qualifying_grade BETWEEN 9 AND 12),

    -- One window date without the other is half a fact.
    CONSTRAINT national_merit_cycles_window_pair_check
        CHECK ((window_opens_on IS NULL) = (window_closes_on IS NULL)),

    CONSTRAINT national_merit_cycles_window_ordered_check
        CHECK (window_closes_on IS NULL OR window_opens_on <= window_closes_on),

    -- The qualifying sitting is in the autumn two calendar years before the
    -- competition year: October 2026 for the 2028 competition. A window typed
    -- into the wrong cycle is the error this refuses.
    CONSTRAINT national_merit_cycles_window_in_cycle_check
        CHECK (
            window_opens_on IS NULL
            OR window_opens_on BETWEEN make_date(competition_year - 2, 7, 1)
                                   AND make_date(competition_year - 1, 6, 30)
        ),

    -- A deadline or an address we cannot show the publisher's words for is a
    -- claim we made ourselves.
    CONSTRAINT national_merit_cycles_alternate_entry_trio_check
        CHECK (
            alternate_entry_as_published IS NOT NULL
            OR (alternate_entry_deadline IS NULL AND alternate_entry_contact IS NULL)
        ),

    CONSTRAINT national_merit_cycles_alternate_entry_in_cycle_check
        CHECK (
            alternate_entry_deadline IS NULL
            OR alternate_entry_deadline BETWEEN make_date(competition_year - 2, 10, 1)
                                            AND make_date(competition_year - 1, 12, 31)
        ),

    CONSTRAINT national_merit_cycles_selection_index_ordered_check
        CHECK (selection_index_low < selection_index_high),

    CONSTRAINT national_merit_cycles_selection_index_range_check
        CHECK (selection_index_low > 0 AND selection_index_high <= 500),

    -- Our own enumerations (TEXT + CHECK IN, the house rule), each with exactly
    -- one legal value today, because each has exactly one published basis.
    CONSTRAINT national_merit_cycles_semifinalist_cutoff_basis_check
        CHECK (semifinalist_cutoff_basis IN ('state_representational')),

    CONSTRAINT national_merit_cycles_commended_cutoff_basis_check
        CHECK (commended_cutoff_basis IN ('national')),

    CONSTRAINT national_merit_cycles_funnel_positive_check
        CHECK (
            COALESCE(test_takers, 1) > 0
            AND COALESCE(met_entry_requirements, 1) > 0
            AND COALESCE(high_scorers_screened, 1) > 0
            AND COALESCE(commended, 1) > 0
            AND COALESCE(semifinalists, 1) > 0
            AND COALESCE(finalists, 1) > 0
            AND COALESCE(winners, 1) > 0
        ),

    -- A funnel only narrows. Typing 15,000 Semifinalists and 16,000 Finalists
    -- inverts the base rate the coach is required to speak.
    CONSTRAINT national_merit_cycles_funnel_narrows_check
        CHECK (
            (met_entry_requirements IS NULL OR test_takers IS NULL
                OR met_entry_requirements <= test_takers)
            AND (high_scorers_screened IS NULL OR met_entry_requirements IS NULL
                OR high_scorers_screened <= met_entry_requirements)
            AND (commended IS NULL OR met_entry_requirements IS NULL
                OR commended <= met_entry_requirements)
            AND (semifinalists IS NULL OR met_entry_requirements IS NULL
                OR semifinalists <= met_entry_requirements)
            AND (finalists IS NULL OR semifinalists IS NULL
                OR finalists <= semifinalists)
            AND (winners IS NULL OR finalists IS NULL
                OR winners <= finalists)
        ),

    CONSTRAINT national_merit_cycles_nmsc_award_pair_check
        CHECK (
            nmsc_award_usd IS NULL
            OR (nmsc_award_usd > 0 AND nmsc_award_as_published IS NOT NULL)
        ),

    -- A quoted sentence that is blank is not a quotation.
    CONSTRAINT national_merit_cycles_words_not_blank_check
        CHECK (
            btrim(window_as_published) <> ''
            AND btrim(cutoff_as_published) <> ''
            AND btrim(score_visibility_as_published) <> ''
            AND btrim(funnel_as_published) <> ''
            AND btrim(publisher) <> ''
            AND btrim(COALESCE(alternate_entry_as_published, 'x')) <> ''
            AND btrim(COALESCE(alternate_entry_contact, 'x')) <> ''
            AND btrim(COALESCE(nmsc_award_as_published, 'x')) <> ''
            AND btrim(COALESCE(sponsor_cap_as_published, 'x')) <> ''
        ),

    CONSTRAINT national_merit_cycles_archive_differs_check
        CHECK (
            (archive_document_id IS NULL OR archive_document_id <> source_document_id)
            AND (
                administrator_archive_document_id IS NULL
                OR administrator_archive_document_id <> administrator_document_id
            )
        ),

    -- The sponsor and the administrator are different publishers. A row citing
    -- one page for both is a row that lost half its provenance.
    CONSTRAINT national_merit_cycles_two_publishers_check
        CHECK (
            administrator_document_id <> source_document_id
            AND btrim(administrator_publisher) <> ''
            AND administrator_publisher <> publisher
        ),

    -- The day we stop believing the page, never the day a deadline falls.
    CONSTRAINT national_merit_cycles_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

COMMENT ON TABLE national_merit_cycles IS
    'One National Merit competition year as NMSC publishes it (RFC 243): the '
    'in-school window, the alternate entry route and its deadline, the funnel '
    'the base rate is derived from, and NMSC''s own words saying the qualifying '
    'score is set per state and not published in advance. Keyed by the '
    'competition year, which is the class''s graduation year.';

CREATE TRIGGER trigger_03_enforce_national_merit_cycles_updated_at
BEFORE UPDATE ON national_merit_cycles
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO INDEX, deliberately, on the RFC 197 reading: the only read in the tree is
-- by primary key (the student's graduation year) or the whole table, over a
-- handful of rows.
```

`expires_on` follows the shared discipline: July 31 after the cycle's last
actionable day. For the 2028 competition that is **2027-07-31** — after the
April 1, 2027 alternate-entry deadline and before the next cycle's window. The
guard fires 90 days out, from the one `HandTypedReferenceData.REFRESH_LEAD`.

**A cycle is LIVE while its competition year is still ahead of the class now
graduating** — deliberately not "its window or deadline is still ahead", which
was this design's first wording and which the curation pass proved wrong. NMSC
dates only the cycle in progress: the 2029 row exists, is real, and carries no
dates at all, so a liveness rule keyed on dates would never look at it and it
would rot in silence. Keyed on the year, both rows expire together on
2027-07-31, and one re-read in the summer answers for both — which is also when
NMSC will have dated 2029.

**The undated cycle is the normal case, not an edge case.** Measured today: no
publisher has dated any cycle after 2028, and NMSC names 2029 in a single
sentence and nothing beyond it. So the row for a student two years out carries
the description ("take the test in your junior year, in October") and no dates,
and the coach says exactly that much.

### B. `college_national_merit_awards`

The migration is verbatim below — this is the file, not a summary of it.

```sql
-- college_national_merit_awards -- what a NAMED school pays a National Merit
-- Finalist (RFC 243, slice test/08/the-psat-is-money, brief 0010).
--
-- A SECOND TABLE, not a widening of college_automatic_awards (RFC 239), and the
-- reason is the qualification itself. That table models a price against a SCORE:
-- a test, a score range on the published scale, a GPA range, and a Kotlin
-- BandScoreAxis with exactly two arms. A National Merit Finalist is none of
-- those -- it is a STANDING that NMSC awards once, after a state-allocated
-- screen we do not hold and will never hold. Three more facts, any one of which
-- would be enough on its own:
--   1. The natural key collides. RFC 239's is (college_id, entry_year,
--      residency_basis) and Alabama already owns that row with its score grid.
--   2. Every reader of that table is an exhaustive when over score-shaped
--      answers. A third arm makes "a score moves the money here" start being
--      true of rows where no score is involved.
--   3. The columns do not carry over in either direction: first choice and the
--      standing are National Merit's shape, and superscore_rule and
--      score_deadline are meaningless for a standing awarded once.
-- What IS shared is every bit of discipline: the ingest-time loader and its
-- named refusals, the link-status sidecar, external_documents, checked_on /
-- expires_on, HandTypedReferenceData.REFRESH_LEAD, the as_published column
-- beside every parsed value, and the verified-absence kind.
--
-- HAND-TYPED REFERENCE DATA (brief 0010 D19), ROUTE B (RFC 239 D-C): keyed by
-- colleges(id), which the IMPORT fills, so a migration-time seed would point at
-- an empty table -- D27's measured failure. The corpus lives at
-- db/seed/national-merit-awards/{awards,link-status}.csv with its
-- PROVENANCE.json and is loaded by bin/ingest-colleges behind
-- bin/load-external-data.
--
-- EVERY ROW EXPIRES on July 31 of its entry year (D20), the same rule and the
-- same shared REFRESH_LEAD as the score grids. Auburn is why: it cut a $44,000
-- award to Alabama residents only, and a row with no read date would still be
-- telling a family in Oregon it was theirs.

CREATE TABLE college_national_merit_awards (
    id                          UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The school. REFERENCES colleges(id), never an IPEDS code of our own
    -- (D26), and the reason this corpus loads with the ingest.
    college_id                  UUID          NOT NULL REFERENCES colleges(id),

    -- The ENTRY year, named by its first calendar year: 2027 is Fall 2027
    -- entry. A school's National Merit page is re-keyed per cycle exactly as
    -- its score grid is. The domain owns the range (0087).
    entry_year                  academic_year NOT NULL,

    -- WHOSE award this is. Auburn is why it is in the key: $11,000/yr, Alabama
    -- residents only. Keyed into residency_bases (RFC 158 D4) rather than a
    -- private copy, and narrowed below to the three a university publishes a
    -- freshman award against.
    residency_basis             TEXT          NOT NULL REFERENCES residency_bases(slug),

    -- OUR OWN enumeration (TEXT + CHECK IN, one Kotlin enum with fromValue).
    -- 'automatic'      -- the page guarantees the award to anyone holding the
    --                     standing, with no committee and no separate contest.
    -- 'competitive'    -- the standing is CONSIDERED, not paid (UCF's ">$25,000
    --                     a year" is "considered for"; Tulsa adds a 4.0, a
    --                     visit and an interview). Calling that automatic is
    --                     the costly error this column exists to prevent.
    -- 'none_published' -- we READ this school's page on checked_on and it
    --                     publishes no National Merit award at all. A verified
    --                     absence WITH a citation, which is a different answer
    --                     from "we have not looked" -- the argument
    --                     fafsa_filing_deadlines makes for deadline_kind='none'
    --                     and RFC 239 makes for 'none_published'.
    award_kind                  TEXT          NOT NULL,

    -- WHICH standing the school pays. This is the column that made this a new
    -- table rather than a widening: a Finalist is not a score. Maine is why it
    -- is in the key -- it publishes a Semifinalist row AND a Finalist row.
    qualifying_standing         TEXT          NULL,

    award_name                  TEXT          NULL,

    -- The money, as the PUBLISHER expresses it. Oklahoma publishes a four-year
    -- total of $153,450; Alabama publishes a package of tuition, housing and
    -- cash; Texas Tech publishes "100% of cost of attendance" and no number at
    -- all. So the WORDS are required for every paying row and the numbers are
    -- optional -- never the other way round. RFC 239's review found the
    -- opposite arrangement ranking a top award priced in words as $0, and no
    -- test caught it. Nothing downstream may sort or rank on these columns.
    award_per_year_usd          INTEGER       NULL,
    award_total_usd             INTEGER       NULL,
    award_years                 SMALLINT      NULL,
    award_as_published          TEXT          NULL,

    -- The sentence that proves the absence, for a 'none_published' row only.
    -- Separate from award_as_published so that neither kind can borrow the
    -- other's words: a paying row with an absence quote, or an absence row
    -- carrying award words, is refused rather than rendered.
    absence_as_published        TEXT          NULL,

    -- NMSC's first-choice mechanism, per school, because it is the difference
    -- between an award and a commitment: naming a college first choice with
    -- NMSC is an act with consequences elsewhere. Maine requires nothing;
    -- Alabama wants it by May 1. NULL means the page does not say, which is
    -- neither yes nor no.
    first_choice_required       BOOLEAN       NULL,
    first_choice_deadline       DATE          NULL,
    first_choice_as_published   TEXT          NULL,

    -- The school's own admission or scholarship deadline for the package, and
    -- its own sentence for it.
    apply_deadline              DATE          NULL,
    apply_deadline_as_published TEXT          NULL,

    -- The citation. No url column (RFC 228; the build fails for one): the page
    -- and its optional mirror are foreign keys into external_documents, where
    -- every row carries its status and the day we asked.
    publisher                   TEXT          NOT NULL,
    source_document_id          UUID          NOT NULL REFERENCES external_documents(id),
    archive_document_id         UUID          NULL     REFERENCES external_documents(id),
    checked_on                  DATE          NOT NULL,
    expires_on                  DATE          NOT NULL,

    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- The natural key. NULLS NOT DISTINCT so that the one 'none_published' row
    -- per school, entry year and residency -- whose standing is NULL -- cannot
    -- be typed twice. It also serves every read: the tool looks up by
    -- college_id, and this index leads with it.
    CONSTRAINT college_national_merit_awards_natural_key
        UNIQUE NULLS NOT DISTINCT (college_id, entry_year, residency_basis, qualifying_standing),

    CONSTRAINT college_national_merit_awards_kind_check
        CHECK (award_kind IN ('automatic', 'competitive', 'none_published')),

    CONSTRAINT college_national_merit_awards_standing_check
        CHECK (
            qualifying_standing IS NULL
            OR qualifying_standing IN ('finalist', 'semifinalist', 'commended')
        ),

    -- A standing on a row that says the school publishes nothing, or a paying
    -- row that does not say WHICH standing it pays, are both incoherent.
    CONSTRAINT college_national_merit_awards_standing_iff_award_check
        CHECK ((award_kind = 'none_published') = (qualifying_standing IS NULL)),

    -- in_district and unknown are legal in the vocabulary and refused here, on
    -- RFC 239's argument: a community-college tier and an IPEDS headcount
    -- category are not things a university publishes a freshman award against.
    CONSTRAINT college_national_merit_awards_residency_basis_check
        CHECK (residency_basis IN ('in_state', 'out_of_state', 'not_applicable')),

    -- Every claim carries the page's own sentence, and the two kinds of
    -- sentence never cross.
    CONSTRAINT college_national_merit_awards_claim_has_words_check
        CHECK (
            award_kind = 'none_published'
            OR (award_as_published IS NOT NULL AND absence_as_published IS NULL)
        ),

    CONSTRAINT college_national_merit_awards_absence_has_words_check
        CHECK (
            award_kind <> 'none_published'
            OR (absence_as_published IS NOT NULL AND award_as_published IS NULL)
        ),

    -- A verified absence states nothing else. No name, no money, no deadline,
    -- no first-choice rule -- there is no award for any of them to describe.
    CONSTRAINT college_national_merit_awards_none_published_is_empty_check
        CHECK (
            award_kind <> 'none_published'
            OR (
                award_name IS NULL
                AND award_per_year_usd IS NULL
                AND award_total_usd IS NULL
                AND award_years IS NULL
                AND first_choice_required IS NULL
                AND first_choice_deadline IS NULL
                AND first_choice_as_published IS NULL
                AND apply_deadline IS NULL
                AND apply_deadline_as_published IS NULL
            )
        ),

    CONSTRAINT college_national_merit_awards_money_positive_check
        CHECK (
            COALESCE(award_per_year_usd, 1) > 0
            AND COALESCE(award_total_usd, 1) > 0
            AND COALESCE(award_years, 1) BETWEEN 1 AND 6
        ),

    -- A requirement with no words is our claim, not theirs; a first-choice
    -- DEADLINE on a school that does not require first choice is a date with
    -- nothing to be the deadline of.
    CONSTRAINT college_national_merit_awards_first_choice_trio_check
        CHECK (
            (first_choice_required IS NULL) = (first_choice_as_published IS NULL)
            AND (first_choice_deadline IS NULL OR first_choice_required IS TRUE)
        ),

    CONSTRAINT college_national_merit_awards_apply_deadline_pair_check
        CHECK (apply_deadline IS NULL OR apply_deadline_as_published IS NOT NULL),

    -- Both deadlines belong to the application cycle that ends in the entry
    -- year: July 1 the summer before, through September 30 of entry. RFC 239's
    -- deadline_in_cycle, unchanged -- it catches a date typed into the wrong
    -- cycle, which is the mistake a curator actually makes.
    CONSTRAINT college_national_merit_awards_deadlines_in_cycle_check
        CHECK (
            (
                apply_deadline IS NULL
                OR apply_deadline BETWEEN make_date(entry_year - 1, 7, 1)
                                      AND make_date(entry_year, 9, 30)
            )
            AND (
                first_choice_deadline IS NULL
                OR first_choice_deadline BETWEEN make_date(entry_year - 1, 7, 1)
                                             AND make_date(entry_year, 9, 30)
            )
        ),

    CONSTRAINT college_national_merit_awards_words_not_blank_check
        CHECK (
            btrim(publisher) <> ''
            AND btrim(COALESCE(award_as_published, 'x')) <> ''
            AND btrim(COALESCE(absence_as_published, 'x')) <> ''
            AND btrim(COALESCE(award_name, 'x')) <> ''
            AND btrim(COALESCE(first_choice_as_published, 'x')) <> ''
            AND btrim(COALESCE(apply_deadline_as_published, 'x')) <> ''
        ),

    CONSTRAINT college_national_merit_awards_archive_differs_check
        CHECK (archive_document_id IS NULL OR archive_document_id <> source_document_id),

    -- The day we stop believing the page, never a deadline the row states.
    CONSTRAINT college_national_merit_awards_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

COMMENT ON TABLE college_national_merit_awards IS
    'What a named school pays a National Merit Finalist, Semifinalist or '
    'Commended student (RFC 243): the school''s own words first, its numbers '
    'only where it publishes them, whether the award is automatic or merely '
    'competitive, its first-choice requirement, and the day we read the page. '
    'A standing is not a score, which is why this is not a row in '
    'college_automatic_awards.';

CREATE TRIGGER trigger_03_enforce_college_national_merit_awards_updated_at
BEFORE UPDATE ON college_national_merit_awards
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO SECOND INDEX. The only read is by college_id for up to fifty schools at
-- once, and the natural-key constraint above already leads with that column.
```

The key carries `qualifying_standing` because Maine pays Semifinalists **and**
Finalists and publishes both rows, and because a school that pays one and not
the other is the common shape.

**There is no score column, and that is the design.** RFC 239's bands table
models qualification as a score and/or GPA range: `test admission_test`,
`score_low/high` on the published scale, and a `BandScoreAxis` sealed type with
exactly two arms, `NoScore` and `Range`. A National Merit Finalist is neither.
So this corpus does not widen that seam, for four reasons, in order of weight:

1. **The natural key collides.** RFC 239's is
   `(college_id, entry_year, residency_basis)`, and Alabama already owns that
   row with its score grid. A National Merit package for Alabama has nowhere to
   go without changing the key of a landed, loaded, guarded table.
2. **Every reader would have to learn a non-score arm.** The landed tool answers
   five score-shaped answers, the cost report prints one `li` per band, and both
   are exhaustive `when`s over `BandScoreAxis`. A third arm makes every one of
   RFC 239's answers ambiguous — "a score moves the money here" would start
   being true of rows where no score is involved.
3. **The columns do not carry over.** First choice, the standing itself, and a
   publisher's four-year total are National Merit's shape; a score band's
   `superscore_rule` and `score_deadline` are meaningless for a standing NMSC
   awards once.
4. **Widening a table to hold a second meaning is how the nullables multiply.**
   Three coincident nullables on a write model was a tier-1 finding in RFC 239
   itself.

What this corpus DOES reuse is everything that is discipline rather than shape:
the ingest-time loader and its named refusals, `LinkStatusSidecar`,
`ExternalDocumentsDao.citationIds`, `checked_on`/`expires_on`,
`HandTypedReferenceData.REFRESH_LEAD`, the `as_published` beside every parsed
value, and the verified-absence kind. That is what the slice's PREFER edge on
`test/07` was for: the second curation seam extends the first's discipline
instead of inventing it twice.

### The tool: `psat_national_merit`

One new tool, registered as one expression in `Application.kt`. Its college
argument is the shared `putCollegeIdsSchema()` every school-scoped tool uses,
and residency is read from the money profile — **not** an input field the model
can contradict.

**AMENDED IN REVIEW (tier 0, two lenses).** This section first said the tool
extends `ResidencyScopedChatTool`; it extends `StudentScopedChatTool` and reads
residency the way `FafsaDeadlineChatTool` does. The code is right and the design
was wrong, so the design moves: `ResidencyScopedChatTool` exists for the two
SEARCH doors — it wraps a chat-free `:college` tool behind
`search(input, familyResidencyState)` and merges a residency OFFER — and this
tool calls a `:service` read with three arguments. The landed precedent for "a
non-search tool that is residency-scoped" is the FAFSA deadline tool, and that
is what this copies. The claim the RFC actually makes is unchanged and still
true: residency is never an input field.

It answers three things in one response, because they are one question:

**1. Where this student stands in the cycle.** Derived from
`HighSchoolYear.of(today, graduation)` and the cycle row for her graduation year
— never from the model:

| `answer`               | When                                                              | What the coach can say                                      |
| ---------------------- | ----------------------------------------------------------------- | ----------------------------------------------------------- |
| `window_ahead`         | before grade 11, or a junior before the window opens              | names the window ahead and what it is worth                 |
| `window_open`          | today is inside the window                                        | it is happening now; ask the school how it runs it          |
| `alternate_entry_open` | after the window closed, before the alternate-entry deadline      | names the route, the address and the deadline               |
| `cycle_closed`         | past the alternate-entry deadline, or past grade 11 with no entry | says so plainly, and does not offer a route that has closed |
| `not_curated`          | we hold no cycle row for her graduation year                      | says we do not hold this year's dates, names no date        |

**A SIXTH ARM, added in review (tier 1): `cycle_expired`.** Past the row's
`expires_on` the tool answers that rather than any dated reading — and one line
had to be drawn where the two sides differ. Two answers outrank expiry, and both
name NO date of the row: a student past graduation, and an UNDATED row whose
qualifying year is already behind her. Everything a DATED row can answer stays
behind the expiry gate, because a dated row's closed answer is a reading of the
letter-route deadline — a date, not a year — and a reading we no longer believe
must not be spoken as one we do.

`PAST_GRADUATION` and `BEFORE_HIGH_SCHOOL` are the two out-of-range states
`HighSchoolYear` already models, and both resolve honestly: a student past
graduation gets `cycle_closed`, a student before high school gets `window_ahead`
for the cycle that matches her graduation year if we hold one.

**2. The base rate, whenever there is money in the payload.** The funnel counts
and one derived share, labelled as derived and never stored.

**From the most recent cycle that publishes one, named by its year.** NMSC
publishes a funnel only for the competition in progress, so the 2029 row holds
none — measured, not assumed. A sophomore therefore hears the 2028 competition's
figures, said as the 2028 competition's figures: "in the 2028 competition, about
15,000 of 1.2 million entrants reached Finalist". Carrying yesterday's funnel
silently into her year would be the fabrication this whole corpus exists to
avoid; naming the year it belongs to is honest and still answers the question.
This is a **contract, not a convention**: the response object is built so that a
school payment and the base rate are emitted by the same code path, and a test
asserts that no payload containing a dollar figure can lack the funnel.
Correction 4 exists because the six-figure numbers are the memorable part.

**3. What each requested school pays**, one object per college, with the same
five-answer honesty RFC 239 established: `automatic`, `competitive`,
`none_published`, `not_curated`, `expired`. Each carries its publisher, its
source URL, and the day we read it; `expired` is answered past `expires_on` with
the same `>` predicate the two landed corpora share.

The tool **never ranks or sorts by money** and never names a "best" award: a
package priced in words has no comparable number, and RFC 239's review found
exactly that defect shipping as a $0 ranking.

### The proactive moment — the door

The slice's door is _"the chat, raised by the coach when the student is a junior
and the October window is near — the one proactive moment in this brief."_ The
repo already has exactly one shape for that, and this copies it rather than
inventing a second: the share-report nudge (RFC 160). Deterministic code writes
an explicit reflection row; the coach reads it into its next opener and raises
it once; the model cannot invent it.

What is copied, part for part:

- **A new lens**, `psat_window`, added to `CommitmentLens` and to the
  `commitments.lens` CHECK by a two-statement widening migration — the shape
  `0080.widen-commitments-lens-share-report.sql` already establishes.
- **The compile-time gate.** `LLM_PROPOSABLE_LENSES` is built by an exhaustive
  `when` over `CommitmentLens.entries`, so the new member does not compile until
  its proposability is decided, and it is decided `false`. The synthesis tool
  schema then never offers it and `parseOutput` rejects it as `invalid_field`.
- **A fixed statement**, a `const val`, never model output:

      "The PSAT/NMSQT window is close, and they are in the one year of school
       whose sitting counts for National Merit. When the moment is right, raise
       it once: what the test is worth, that their school decides the day and
       they do not register themselves, and that no college ever sees the score."

- **A verdict with named suppression reasons**, checked in this order:

  | Reason                | Suppresses when                                                                          |
  | --------------------- | ---------------------------------------------------------------------------------------- |
  | `DISABLED`            | the config key is off                                                                    |
  | `NOT_QUALIFYING_YEAR` | `HighSchoolYear.of(today, graduation)` is not the qualifying grade                       |
  | `NO_CYCLE_ROW`        | we hold no cycle row for her graduation year                                             |
  | `CYCLE_UNDATED`       | the row we hold describes the window in words and has no dates, so "near" has no meaning |
  | `WINDOW_NOT_NEAR`     | today is more than the lead days before the window opens                                 |
  | `WINDOW_PASSED`       | today is after the window closed                                                         |
  | `ALREADY_RAISED`      | this student already has a `psat_window` row, in ANY status                              |
  | `OPEN_SET_AT_CAP`     | the open-commitment cap is reached                                                       |
  | `CYCLE_EXPIRED`       | today is past the row's `expires_on`, so we no longer believe its dates                  |

**NINE reasons, not eight: `CYCLE_EXPIRED` was added in review (tier 1).** A
cycle row carried `expires_on` and no read path consulted it, so a stale reading
could still have raised a dated sitting — the refusal RFC 197's calendar already
makes for itself.

**The order is decided by ONE principle: OUR OWN FACTS COME BEFORE THE CORPUS'S
FRESHNESS** — `DISABLED`, `NO_CYCLE_ROW`, `NOT_QUALIFYING_YEAR`,
`CYCLE_EXPIRED`, `CYCLE_UNDATED`, `WINDOW_NOT_NEAR`, `WINDOW_PASSED`,
`ALREADY_RAISED`, `OPEN_SET_AT_CAP`. Her year of school is derived from her
stored graduation date (RFC 186) and is true whether or not we still believe two
publishers' pages; expiry is a statement about trusting the ROW'S DATES, so it
sits immediately before the first check that reads one. The first attempt put
expiry first, which made `NOT_QUALIFYING_YEAR` unobservable on any late clock.

**AMENDED IN REVIEW (tier 0): the order above is the ENUM's, not the
decision's.** The code checks `NO_CYCLE_ROW` before `NOT_QUALIFYING_YEAR`, and
that is right: the qualifying grade is the ROW's — a published rule the schema
stores so that nothing speaks it from memory — so the row has to be in hand
before the year of school can be judged against it. A hard-coded 11 in the
service would be a second opinion about a fact the corpus owns. For a student
who is neither in the qualifying year nor holds a cycle row, both reasons are
true and the log names `NO_CYCLE_ROW`.

`ALREADY_RAISED` needs no new DAO method: RFC 160 already landed
`CommitmentsDao.findLatestByStudentAndLens`, whose contract is "the most recent
row of that lens **in any status**" — exactly this predicate.

`ALREADY_RAISED` is why this needs **no cooldown and no config day count** of
the kind the share nudge has: the qualifying sitting happens once in a student's
life, so one row in any status — open, fulfilled or dropped — is the whole
repeat rule. A dropped nudge is a student who did not want it, and that answer
stands.

- **`WINDOW_PASSED` is a deliberate refusal, not an omission.** We do not know
  whether she sat the test — nothing in the product stores that — so an
  unprompted "you may have missed it, and there is a route" would be a guess
  about her life. The alternate-entry answer is fully available the moment she
  asks, through the tool's `alternate_entry_open` arm. Raising it unasked is
  not.

- **Where it runs**: the same step in `SynthesisService`'s read phase as the
  share nudge, under the same per-student advisory lock, in the same
  transaction, before every LLM-phase gate — so it still fires on a pass whose
  LLM half no-ops.

- **One config key**, in all three layers the repo requires: `service.conf` with
  its `${?VAR}` override (which IS the registration — there is no `.env` or
  `CONFIGURATION.md` to update), the typed `SynthesisConfig` property, and the
  consumer. Two keys, in fact: the on/off and the lead days.

**The honest limit, stated because it is the same one the share nudge has.**
Delivery rides the `SYNTHESIS_SWEEP` periodic job, whose seeded row is
`enabled = FALSE`; an operator turns it on per environment through the admin
toggle. Where it is off, no proactive moment fires — and every reactive answer
in this RFC still works, because those come from the tool. So the slice's value
is reachable with no operator step, and its proactive half is reachable with the
same one the already-shipped nudge needs.

### What the coach must never say

Behaviour goes in `prompts/coach-system-prompt.md` and nowhere else
(`prompts/README.md:5-28`, RFC 192): a tool description is a schema legend, not
copy. One sentence per line, appended to the testing run that already ends with
the school-day programme sentences (lines 91-103):

- The PSAT/NMSQT is **not** an admissions test — no college ever sees the score
  — and it is never described as test prep or practice for money purposes.
- Only the junior-year sitting qualifies, and which arm the student is in comes
  from the tool, never from arithmetic of your own.
- **Never state a qualifying score**, national or per state: NMSC publishes none
  in advance, and the stored sentence saying so is the answer to "what do I
  need".
- Whenever a package figure is spoken, the base rate is spoken in the same
  answer.
- The large numbers are the **university's own** award, not NMSC's; NMSC's own
  award is small and its sponsor awards are capped.
- Where the window has passed, name alternate entry and its deadline. Never say
  the chance is gone forever.
- Where the tool says we have not read a school, say that — never that the
  school pays nothing.

`bin/prompt-seed` owns the seed migration, the `db/schema/ORDER` line and the
`systemPromptVersion` pin; this run authors only the prose, and the pin moves to
whatever the generator computes at land (the base is at `v46` today).

### The corpus, and what will be re-read

Nothing here is carried over from the September 10 research pass as fact. Every
row is re-read from the publisher's own page during implementation, on the run's
own date, and every URL goes through `bin/check-link` into `external_documents`
with its status and the day we asked — the discipline RFC 220 made unskippable
(`url_checked_on` is `NOT NULL`, and a dateless database is refused rather than
backfilled).

| Corpus                          | Target                                                                                                                |
| ------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `national_merit_cycles`         | every competition year we can source: the dated current cycle, plus each later year NMSC describes but has not dated  |
| `college_national_merit_awards` | the schools whose own pages publish a National Merit package, plus every school read and verified to publish **none** |

**What the re-read actually produced, on 2026-09-25.** D8's escape clause was
live until this table existed — _"if the curated set cannot reach a useful size,
this slice becomes a later brief rather than a vague one"_ — and the bar Ian was
given was roughly 12 schools with dollars:

| Measured, and seeded                                                     | Count                                          |
| ------------------------------------------------------------------------ | ---------------------------------------------- |
| Schools publishing a real National Merit award                           | **16**                                         |
| Colleges seeded, including the one verified absence                      | 17                                             |
| Award rows (school × residency × standing)                               | 26                                             |
| Rows the school publishes as **automatic**                               | 19                                             |
| Rows the school publishes as **competitive** — considered, not paid      | 6                                              |
| Verified absences, with the sentence that proves it                      | 1 (UNC)                                        |
| Rows priced in a figure we could store                                   | 8 schools                                      |
| Rows priced only in the publisher's **words**                            | 8 schools                                      |
| Source pages, each answered by `bin/check-link`                          | 19 — 17 found, 2 unknown with the day we asked |
| Cycle rows (NMSC + College Board)                                        | 2 — 2028 dated, 2029 described                 |
| Schools read and deliberately **not** seeded, each with a written reason | 12                                             |

Half the seeded schools publish no number at all — "full cost of tuition and
required fees, for four years", "a four-year award equal to the institutional
cost". That is the design's rule working rather than a gap, and it is why the
words are the required cell and the figures are the optional ones.

**Three curation rules the pass established, recorded here because the next
curator will need them and they are not derivable from the DDL:**

1. **A deadline date is stored only where the page prints the YEAR.** Twenty
   rows require first choice and exactly one (Alabama, "before May 1, 2027")
   dates it. The year is derivable from the cycle; it is not on the page, and
   deriving it would put our arithmetic in a citation.
2. **Bullets and table cells are joined with "; " and nothing else changes.**
   Value-less footnote markers are dropped. Anything more is editing a quote.
3. **A number and the sentence that qualifies it travel together.** Florida
   State publishes a $91,808 total and footnotes one component as being for
   students starting Fall 2024; the footnote rides in the quoted words, so the
   caveat is spoken wherever the figure is.

   And its **marker is kept**, which is the exception that proves rule 2.
   Dropping a value-less `*` is tidying; dropping the `*` that BINDS this
   footnote to the $16,000 component silently re-scopes FSU's caveat from one
   component to the whole package. That is a different claim from the one the
   publisher made — more conservative, and still not theirs. So: decoration is
   dropped, and a marker that binds a footnote to a value is kept.

**Absence is harder to source than money.** Only one school could be recorded as
publishing nothing, because a page that simply never mentions National Merit is
silence, and silence is not a citation. Baylor, Kentucky and LSU each have a
live freshman-scholarship page with zero mentions and are therefore _not_
absence rows — they are unread schools, which is the answer the tool already
gives for them.

### One measured limit, recorded rather than fixed

Every `nationalmerit.org` page answers **302** to `bin/check-link`, on both HEAD
and GET: the site bounces through its membership host and back with a session
id. A browser-User-Agent GET that follows redirects returns 200 and the right
content, so the curation pass recorded these as `found` with the 302 and the
reason beside each url. The consequence is worth writing down because nothing in
the code will say it later: **a future sweep that re-checks these urls without
following redirects will call NMSC `not_found`**, and the honest reading of that
result is "the checker did not follow a redirect", not "the programme's pages
are gone". `bin/check-link` fetches and does not judge — that is its contract —
so the judgement stays with the caller, here and next time.

The same reading applies to Oklahoma State, which is the best find of the
curation pass ("Up to $154,500", and its page names the 2027-2028 academic year
outright): `go.okstate.edu` answers **403** to `bin/check-link` on HEAD and GET,
with our User-Agent and with curl's, while returning 200 and full content to a
plain browser-shaped fetch. It filters on more than the User-Agent. Its two urls
are therefore recorded **`unknown`**, never `not_found`, with the day we asked —
"we asked and got no usable answer" is one of the three states
`external_documents` already has, and it is the true one here.

### Rollback

Three independent knobs, smallest first:

1. `psatWindowNudgeEnabled = false` — the proactive moment stops; every reactive
   answer stays.
2. `COACHING_SYSTEM_PROMPT_VERSION` set to the version immediately BELOW the pin
   — the coach loses the prose. The number is not written here on purpose
   (**amended in review**, where it still said `v46`): `bin/prompt-seed` cuts
   the label at land from whatever the base holds, and this run's own rebase
   moved it from v47 to **v48** when RFC 240 landed first. A literal in a
   document is a prediction; `service.conf`'s pin is the answer. **Partial**,
   exactly as RFC 239's was: the tool stays registered and the corpus stays in
   the database, so the coach can still answer if it chooses to call the tool.
3. Unregister the tool in `Application.kt` — the full stop. No schema change is
   needed for any of the three, and no migration is reverted.

## Files Modified

**New — schema**

- `db/schema/create-national-merit-cycles.sql` — the programme table, its CHECKs
  and its comments.
- `db/schema/create-college-national-merit-awards.sql` — the per-school table.
- `db/schema/widen-commitments-lens-psat-window.sql` — one lens added to the
  `commitments.lens` CHECK, following
  `0080.widen-commitments-lens-share-report.sql` statement for statement.
- `db/schema/seed-national-merit-cycles-<yyyy-mm-dd>.sql` — **GENERATED**,
  committed, never hand-edited. **AMENDED IN REVIEW (tier 0, three lenses):**
  the file is named by the READING DAY, not by the cycle. `<cycle>` cannot name
  one file, because one day's read holds TWO competition years — and a file per
  cycle would have to be rewritten when NMSC dates the 2029 cycle next summer,
  which a landed migration may never be. It matches the closest sibling,
  `seed-fafsa-filing-deadlines-<yyyy-mm-dd>.sql`.
- `db/schema/ORDER` — three authored lines plus the generated seed's, placed by
  `ship-order` at land.

**New — corpus**

- `db/seed/national-merit/cycles.csv`, `link-status.csv`, `PROVENANCE.json`
- `db/seed/national-merit-awards/awards.csv`, `link-status.csv`,
  `PROVENANCE.json`

**New — scripts**

- `bin/national-merit-seed` — Route A generator: reads the committed CSV, emits
  the seed migration, appends its `ORDER` line, upserts on conflict, mirrors
  every DDL CHECK with a `MIRRORS <name>` tag, and `-n` is the byte-identity
  drift guard (exit 3). `getopts`, short options only; all logging to stderr.
- `bin/national-merit-seed-scripts-tests` — its harness. **`bin/gate-scope` must
  name it** (RFC 214): a `bin/*scripts-tests` the map does not know fails the
  gate by name, which is the correct failure and one this run must not leave for
  someone else.

**New — Kotlin**

- `db/.../models/NationalMeritCycle.kt`, `NationalMeritAward.kt`,
  `NationalMeritAwardKind.kt`, `QualifyingStanding.kt` — TEXT + CHECK enums with
  `fromValue`, the house rule.
- `db/.../dao/NationalMeritCyclesDao.kt`, `NationalMeritAwardsDao.kt`
- `college/.../NationalMeritAwardSeedLoader.kt` — the Route B loader, refusals
  by file, line and cell.
- `service/.../coaching/testing/NationalMeritService.kt` — the arm selection,
  the base-rate contract, the five per-school answers.
- `service/.../coaching/testing/NationalMeritChatTool.kt` — the tool.
- **AMENDED IN REVIEW (tier 0):** there is no `PsatWindowNudge.kt`. The verdict,
  its suppression enum and the step live inside
  `service/.../coaching/synthesis/SynthesisService.kt`, which is where the share
  nudge (RFC 160) and the invite nudge (RFC 240) already live. A separate file
  would have made this the only one of three deterministic nudges kept somewhere
  else, and they share the write (`insertNudge`) and the router
  (`writeDeterministicNudges`) anyway.

**Changed**

- `rest-server/.../Application.kt` — one registration expression.
- `college/.../CollegeScorecardLoader.kt` — one `phase("national-merit-awards")`
  after the automatic-awards phase, plus the ingest wiring that phase needs to
  be reachable at all: an injected loader, a source parameter, the up-front
  header assertion, a provenance digest, the `--national-merit-awards` flag with
  its `-source` partner and a dangling-source refusal, `bin/ingest-colleges -N`,
  and two `require_input` lines in `bin/load-external-data`. **Named in review
  (tier 0):** a phase with no source is dead wiring, and the corpus is loaded
  with `upsert` + `deleteExcept` — a PRUNE, so a row dropped from the committed
  CSV is deleted from the database on the next ingest. That is RFC 239's landed
  seam reused unchanged, and it is the behaviour a reader of this document
  should expect.
- `service/.../SynthesisService.kt` — the new verdict beside the share nudge,
  and the lens excluded from `LLM_PROPOSABLE_LENSES` by the same exhaustive
  `when`.
- `service/.../report/CostReportShareService.kt` — **one arm, declared in review
  (tier 0).** `OFFER_LENSES` is built by an exhaustive `when` that refuses to
  compile until a new commitment lens's status is decided, so this run had to
  answer it: **`psat_window` is NOT an offer lens.** The share opt-out is
  recorded in the sharing flow and its sentence to the family is "stop
  suggesting sharing" — one stop over two ways of asking for the same thing. A
  dated qualification that happens once in a student's life is not an ask, and
  reading that opt-out as consent to stay silent about it would let one
  unrelated preference cost real money. The window nudge has its own stop and
  can only ever fire once.
- `service/src/main/resources/service.conf` — two config keys; the
  `systemPromptVersion` pin is `bin/prompt-seed`'s, not this run's.
- `service/.../CoachingConfig.kt` (or the synthesis config it lives beside) —
  the two keys typed.
- `prompts/coach-system-prompt.md` — the prose above.
- `bin/shell-tests`, `bin/gate-scope` — the new harness named in both.
- `db/data/` — untouched. This corpus is not a codebook.

## Implementation Plan

1. **Curate, first and in parallel.** Two curation passes: one reads NMSC's own
   pages for the cycle rows, one reads each university's own page for the award
   rows. Each records only what the page says, quotes the sentence, and carries
   the day it read it. Every URL goes through `bin/check-link`. The pass reports
   the counts, and the counts decide whether the slice ships (D8's escape
   clause). A school whose page still shows last cycle is a re-read owed, not a
   row — RFC 239 left eight such schools out with written reasons, and that is
   the standard.
2. **Schema.** The two tables, the lens widening, and the DAOs with their
   models. Every CHECK gets a test that proves it refuses.
3. **Route A seed.** `bin/national-merit-seed` plus its harness, the generated
   seed committed, `bin/gate-scope` and `bin/shell-tests` updated in the same
   commit as the script.
4. **Route B loader.** `NationalMeritAwardSeedLoader` with its named refusals
   and the committed-corpus test that loads the real CSV into a real database
   with zero refusals.
5. **Service and tool.** Arm selection over year-of-school and today, the
   base-rate contract, the five per-school answers, then the registration.
6. **The proactive moment.** The verdict, the fixed statement, the config keys,
   and the exclusion from the model-proposable lenses.
7. **Prompt prose**, then `bin/prompt-seed -b "$BASE_SHA"` before the suite
   runs.
8. **Suite, review, gate, land.**

## Tests

**Schema and corpus**

- One refusal test per CHECK on both tables, including: a `none_published` row
  carrying money; a paying row with no publisher's words; a standing on a
  `none_published` row; a first-choice deadline on a row that says first choice
  is not required; a funnel that widens; a window whose close precedes its open.
- `NationalMeritCycleSeedTest` — the committed cycles are neither near expiring
  nor half-written, over **live rows only**, and
  `a stale cycle cannot hide behind
  a fresh neighbour` (the state-programme
  test's sibling assertion, which is the one that catches a corpus refreshed in
  part).
- `NationalMeritAwardCommittedCorpusTest` — the committed CSV loads into a real
  database with **zero** refusals, and the row and cell counts meet their
  floors.
- One loader test per named refusal, each asserting the file, line and cell in
  the message.
- `NoUrlColumnOutsideExternalDocumentsTest` — unchanged and must stay green:
  neither new table declares a URL-shaped column.

**Service**

- Arm selection as a table over (year of school × today × cycle row): before
  grade 11; a junior before the window; inside the window; after the window and
  before the alternate-entry deadline; after that deadline; past graduation; and
  **no cycle row for her class** — which must answer `not_curated` and name no
  date. D22 applies: the table carries a senior, an empty list and a student
  with no money profile, not one junior.
- **The base-rate contract**: a payload that contains any dollar figure and no
  funnel fails the test. Proved by deleting the funnel from the response builder
  and reading a named red.
- No cutoff anywhere: a test greps the rendered tool payload and the prompt body
  for a Selection Index figure presented as a requirement.
- Residency scoping: an out-of-state student is not shown Auburn's in-state-only
  row as hers.
- Expiry: past `expires_on` the school answers `expired`, and the payload
  carries no dollars.

**The proactive moment**

- It fires once for a junior inside the lead window, and a second sweep on the
  next day adds nothing.
- One test per suppression reason, by name.
- The model cannot propose the lens: the exhaustive `when` and the parse
  rejection, as the share-report nudge already proves for itself.
- A student who is not a junior gets nothing, including a sophomore whose window
  is a year away and a senior past the cycle.
- `CommitmentsDaoTest` — the widened lens CHECK accepts `psat_window` and the
  row round-trips, mirroring the three tests RFC 160 added for its own lens.
- `CoachingServiceTest` — the row rides the opener and is fulfilled like any
  other commitment. RFC 160's own comment is the standard to keep: "delivery is
  untouched — the nudge lens gets no special-casing."

**Prompt**

- `SystemPromptCatalogTest` — the pinned body is byte-identical to the authored
  file (this is what makes `bin/prompt-seed` mandatory before the suite).

**Shell**

- `bin/national-merit-seed-scripts-tests` — the generator's output is
  byte-identical on a second run, `-n` exits 3 on drift, every DDL CHECK it
  mirrors is tagged, usage errors take the reserved exit codes, and a normal run
  writes **nothing** to stdout.
