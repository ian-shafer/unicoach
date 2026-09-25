-- college_automatic_awards / college_automatic_award_bands -- the published
-- automatic merit grid of a named school, and the cells in it (RFC 239, slice
-- test/07/a-score-is-money, brief 0010).
--
-- At a number of public flagships a test score band IS a price. Alabama pays a
-- Fall-2027 out-of-state freshman $6,000/yr at SAT 1200-1250 and $8,000/yr at
-- 1260-1290, requires a submitted score to qualify at all, and does not
-- superscore for scholarship purposes. This pair of tables is what lets the
-- coach say that about a named school on the student's list -- and refuse, in
-- distinguishable ways, everywhere else.
--
-- HAND-TYPED REFERENCE DATA (brief 0010 D19), and the FIRST such corpus loaded
-- by the INGEST RUN rather than by a seed migration (RFC 239 D-C). Every other
-- hand-typed corpus here -- the test calendar, the state programmes, the FAFSA
-- deadlines -- is keyed by a state or by nothing, so a migration can seed it.
-- This one is keyed by colleges(id), and colleges is filled by the import run,
-- so a migration-time seed would point at an empty table. The corpus lives at
-- db/seed/automatic-awards/{awards,bands,link-status}.csv with its
-- PROVENANCE.json, is reviewable in a diff, and is loaded by bin/ingest-colleges
-- behind bin/load-external-data.
--
-- THE TEMPLATE IS create-fafsa-filing-deadlines.sql, which already solves this
-- corpus's two hard requirements: a verified-absence kind with a value-iff-kind
-- CHECK, and an as_published column carrying the publisher's own words beside
-- our parsed value.
--
-- EVERY ROW EXPIRES on July 31 of its entry year (D-G), guarded 90 days out by
-- the one shared HandTypedReferenceData.REFRESH_LEAD in :db. These pages are
-- re-keyed per cycle in the spring and summer, so a Fall-2027 grid stops being
-- the answer once Fall-2028 applicants are reading. The expiry is NOT the score
-- deadline: a deadline is the fact the row states, not the day we stop
-- believing the page. Nothing in this file decides expiry -- AutomaticAwardService
-- answers Expired past expires_on, with the same `>` predicate the two landed
-- corpora share.
--
-- NO FigureStatus AND NO ASSURANCE TIER, for the reason RFC 197 gives: that
-- vocabulary belongs to the canonical money tables, whose rows record a
-- publisher's suppression and imputation flags (RFC 179). A published
-- scholarship grid is not an imputed money figure -- it is on the page, with
-- the page it was read from, or it is not here at all.
--
-- Reference-table shape (0060/0077/RFC 197/RFC 208 precedent): unversioned, no
-- soft delete, no optimistic concurrency, created_at/updated_at with the plain
-- update_colleges_timestamp() trigger.

-- ---------------------------------------------------------------------------
-- D-J: ('sat','act') becomes a DOMAIN
-- ---------------------------------------------------------------------------
-- The calendar table and the score tables each declare their own
-- CHECK (test IN ('sat','act')). The band column below is the THIRD to ask the
-- same question, which is the trigger RFC 213 already wrote down for exactly
-- this promotion ("A DOMAIN, not a per-table CHECK. Two tables now ask this
-- question."). Promoting costs four statements here and leaves ONE home for the
-- vocabulary; not promoting costs a third copy that can be spelled differently.
--
-- The Kotlin half already exists and does not move: AdmissionTest (RFC 197) is
-- the one enum, and this domain is its storage counterpart.
CREATE DOMAIN admission_test AS TEXT
    CONSTRAINT admission_test_check
    CHECK (VALUE IN ('sat', 'act'));

COMMENT ON DOMAIN admission_test IS
    'A college admission test we hold (RFC 197): ''sat'' or ''act''. One home '
    'for the vocabulary three tables now ask for -- the calendar, the stored '
    'scores and the automatic-award bands -- promoted from three identical '
    'per-table CHECKs on the RFC 213 trigger. The Kotlin counterpart is the '
    'AdmissionTest enum.';

-- The two landed columns take the domain, and their now-redundant CHECKs go
-- with it. A column carrying a domain whose constraint restates the dropped
-- CHECK is the same guarantee from one place instead of three.
ALTER TABLE admission_test_administrations
    ALTER COLUMN test TYPE admission_test,
    DROP CONSTRAINT admission_test_administrations_test_check;

ALTER TABLE admission_test_scores
    ALTER COLUMN test TYPE admission_test,
    DROP CONSTRAINT admission_test_scores_test_check;

-- admission_test_scores_versions keeps TEXT, following money_profiles_versions
-- (0046): a history row mirrors whatever was live and is never validated
-- independently. The live column is the boundary.

-- ---------------------------------------------------------------------------
-- college_automatic_awards
-- ---------------------------------------------------------------------------

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
    -- page never made. 'unstated' is the third honest state and it is the one
    -- the coach refuses on.
    score_requirement      TEXT          NOT NULL,

    -- The SCHOLARSHIP superscore rule, never the admissions one (D14.2).
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
    -- normalised to the start of the period plus a precision, decoded to
    -- PartialDate in the DAO and never escaping it. The service takes the period
    -- END, because "through December 2026" admits every December sitting.
    last_sitting_through   DATE          NULL,
    last_sitting_precision date_precision NULL,
    last_sitting_as_published TEXT       NULL,

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

    -- NO PARSED CLAIM WITHOUT THE PUBLISHER'S WORDS. The three nullable claim
    -- columns above are each tied to their own quote: a stored deadline, a
    -- stated superscore rule and a stated requirement each carry the sentence
    -- they were read from, and where the page said nothing the row says
    -- 'unstated' and stores no words. This is RFC 220's rule one table along --
    -- there, no citation without a check; here, no claim without a quote. It is
    -- what stops a row asserting a rule worth four figures on our authority.
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
    -- loader and asserted at read time by AutomaticAwardsDao).
    CONSTRAINT college_automatic_awards_none_has_no_deadline_check
        CHECK (award_kind <> 'none_published' OR score_deadline IS NULL),

    -- Every human-typed string is trimmed and non-empty (the 0001 shape). An
    -- empty as_published is a value-shaped absence, and a quote rendered from
    -- one is a claim with no words behind it.
    CONSTRAINT college_automatic_awards_publisher_nonempty_check
        CHECK (publisher = btrim(publisher) AND length(publisher) > 0),
    -- Nonempty WHERE PRESENT: NULL is the only way to say "the page did not say".
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
    CONSTRAINT college_automatic_awards_last_sitting_words_nonempty_check
        CHECK (last_sitting_as_published IS NULL
               OR (last_sitting_as_published = btrim(last_sitting_as_published)
                   AND length(last_sitting_as_published) > 0)),

    -- An archive of a page may not BE that page: an archive of itself is the
    -- same link printed twice.
    CONSTRAINT college_automatic_awards_archive_is_not_the_source_check
        CHECK (archive_document_id IS NULL
               OR archive_document_id <> source_document_id),

    CONSTRAINT college_automatic_awards_expiry_after_checked_check
        CHECK (expires_on > checked_on),

    -- A deadline belongs to its own cycle, and THE LOWER BOUND IS THE HALF THIS
    -- CHECK EARNS ITS KEEP ON: it refuses LAST cycle's date typed under THIS
    -- cycle's key, which is the one mistake that makes this table worse than no
    -- table, because a family acts on it.
    --
    -- The upper bound is 30 September OF THE ENTRY YEAR and not the June before
    -- it, and the difference is measured. West Virginia publishes, for a
    -- Fall-2027 entrant: "After you're admitted, you may submit an updated GPA
    -- or test score through August 1" -- a real, published, post-admission
    -- re-evaluation window that closes weeks before term start. A June bound
    -- refuses that row, and retyping it to June would store a date WVU never
    -- published, against this table's whole ethic. Nothing about a FRESHMAN
    -- award is still open once the term has begun, so the term start is where
    -- the window honestly closes.
    CONSTRAINT college_automatic_awards_deadline_in_cycle_check
        CHECK (score_deadline IS NULL
               OR score_deadline BETWEEN make_date(entry_year::INT - 1, 7, 1)
                                     AND make_date(entry_year::INT, 9, 30))
);

-- The natural key. No NULLS NOT DISTINCT is needed: residency_basis carries
-- 'not_applicable' as a real value, so no axis of this key is nullable.
CREATE UNIQUE INDEX college_automatic_awards_natural_key
    ON college_automatic_awards (college_id, entry_year, residency_basis);

COMMENT ON TABLE college_automatic_awards IS
    'What one school publishes about its AUTOMATIC merit awards for one entry '
    'year and one residency basis (RFC 239): whether a score changes the money '
    'at all, whether a score must be submitted to qualify, the scholarship '
    'superscore rule, the score deadline and the latest sitting considered -- '
    'each with the publisher''s own words. Hand-typed from the school''s page, '
    'cited into external_documents, and expiring per row.';

COMMENT ON COLUMN college_automatic_awards.award_kind IS
    'score_grid: bands are published and a score changes the money. '
    'gpa_only_grid: an automatic table is published and a score changes '
    'NOTHING in it -- a different TRUE answer, not a silence. none_published: '
    'we read the page on checked_on and this school publishes no automatic '
    'award at all, a verified absence with a citation.';

COMMENT ON COLUMN college_automatic_awards.score_requirement IS
    'Whether a score must be SUBMITTED to qualify for any automatic award. '
    'Three states and not a boolean: one curated reading does not say, and a '
    'boolean would have to answer for it with a claim the page never made. '
    'This is what makes test-optional-for-admission and test-optional-for-money '
    'different questions.';

COMMENT ON COLUMN college_automatic_awards.superscore_rule IS
    'The SCHOLARSHIP superscore rule (brief 0010 D14.2), never the admissions '
    'one -- the two are different facts and must never be one column. Only '
    'superscore_accepted permits AutomaticAwardService to compute a combined '
    'score against this grid.';

COMMENT ON COLUMN college_automatic_awards.last_sitting_through IS
    'The latest sitting this school will CONSIDER, which is not the receipt '
    'deadline: UT Knoxville receives scores until May 15 but considers no '
    'sitting after December 2026. Stored at RFC 209 precision because the '
    'published bound is usually a month; the service takes the period END, '
    'because "through December 2026" admits every December sitting. NULL with '
    'words present is legal and means the school published a cut-off we will '
    'not parse (UAB''s per-test rule) -- no sitting is excluded and the coach '
    'quotes the school.';

COMMENT ON COLUMN college_automatic_awards.source_document_id IS
    'The school''s own page, read on checked_on (RFC 213/228). NOT NULL: a row '
    'that cites nothing is the uncitable row this corpus exists to prevent.';

CREATE TRIGGER trigger_03_enforce_college_automatic_awards_updated_at
BEFORE UPDATE ON college_automatic_awards
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- ---------------------------------------------------------------------------
-- college_automatic_award_bands
-- ---------------------------------------------------------------------------

CREATE TABLE college_automatic_award_bands (
    id                 UUID    NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    award_id           UUID    NOT NULL REFERENCES college_automatic_awards(id)
                                        ON DELETE CASCADE,

    -- The school's own name for this award -- 'Crimson Legends', 'Presidential'.
    -- A source-defined name, stored raw (CLAUDE.md), and part of the key because
    -- one school publishes several named awards with overlapping bands.
    award_name         TEXT    NOT NULL,

    -- WHICH TEST -- RFC 197's AdmissionTest vocabulary, now the admission_test
    -- DOMAIN this migration promotes (D-J).
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
    -- rounds it to 3.75 -- so the two tiers would overlap at exactly 3.75 and a
    -- 3.75 student would match two cells paying different money, resolved by row
    -- order. Three decimals hold every bound in the curated corpus exactly,
    -- which is this table's whole ethic: store what the page said, and never our
    -- reading of it.
    -- AND A FOURTH DECIMAL IS REFUSED BY THE LOADER, NOT BY THIS COLUMN. The
    -- same rounding happens one decimal along -- 3.7499 stored here is 3.750 --
    -- and no CHECK can catch it, because PostgreSQL coerces a value to the
    -- column's scale BEFORE any constraint sees it, so the constraint is handed
    -- the already-rounded number. The seed loader
    -- (AutomaticAwardSeedLoader.getPublishedGpa) is the one door that still
    -- holds the cell as it was typed, and it refuses a bound this column would
    -- have to move.
    gpa_low            NUMERIC(4,3) NULL,
    gpa_high           NUMERIC(4,3) NULL,

    -- Whole dollars per year, INTEGER, unit in the name (0059). NULL where the
    -- school publishes a KIND of award rather than an amount -- 'tuition and
    -- fees', 'tuition plus housing' -- with the words in award_as_published.
    -- The pair is never both absent.
    award_per_year_usd INTEGER NULL,

    -- The school's own words where the award is not a plain per-year figure.
    -- These are the TOP bands: dropping them would make the coach name a
    -- school's second-best award as its best.
    award_as_published TEXT    NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT college_automatic_award_bands_score_iff_test_check
        CHECK ((score_low IS NOT NULL) = (test IS NOT NULL)
               AND (score_high IS NOT NULL) = (test IS NOT NULL)),

    CONSTRAINT college_automatic_award_bands_score_ordered_check
        CHECK (score_low IS NULL OR score_low <= score_high),

    -- THE PUBLISHED SCALES, not a guess: an SAT total is 400-1600 and an ACT
    -- composite is 1-36 (PublishedScoreScale, which add-college-score-bands.sql
    -- states the same way -- "a value outside them is a loader fault, never
    -- data"). A band edge outside its own test's scale is a typing fault --
    -- 126 for 1260 -- and a band no student's score can land in is
    -- indistinguishable downstream from a school that pays nothing there.
    -- The seed loader refuses it by CSV line; this is the door for every other
    -- writer.
    CONSTRAINT college_automatic_award_bands_score_scale_check
        CHECK (test IS NULL
               OR (test = 'sat' AND score_low BETWEEN 400 AND 1600
                                AND score_high BETWEEN 400 AND 1600)
               OR (test = 'act' AND score_low BETWEEN 1 AND 36
                                AND score_high BETWEEN 1 AND 36)),

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

-- The natural key, and the ONLY index this table needs: its leading column is
-- award_id, so `WHERE award_id IN (...)` -- the one band read this codebase
-- makes (AutomaticAwardsDao.listBandsOf) -- is served from it. A second plain
-- index on award_id alone would be a PREFIX of this one: never the cheaper
-- plan for any query here, and a write and a page on every corpus re-load.
CREATE UNIQUE INDEX college_automatic_award_bands_natural_key
    ON college_automatic_award_bands
       (award_id, award_name, test, score_low, gpa_low)
    NULLS NOT DISTINCT;

COMMENT ON TABLE college_automatic_award_bands IS
    'One row per PUBLISHED CELL of a school''s automatic award grid (RFC 239): '
    'the award''s own name, the test and score range, the GPA cell, and what '
    'it pays. A two-dimensional score x GPA matrix is one row per cell, which '
    'is what makes Oklahoma State''s and Northern State''s grids expressible. '
    'A none_published award has no bands -- refused by the loader and asserted '
    'by AutomaticAwardsDao, because a cross-table CHECK needs a trigger and a '
    'trigger on a corpus written only by the loader buys less (D28).';

COMMENT ON COLUMN college_automatic_award_bands.test IS
    'The test this band is on, or NULL for the published NO-SCORE row -- the '
    'tier a test-optional applicant lands in. That row is what makes "a score '
    'is worth $5,000 more than no score" sayable without inventing a zero band.';

COMMENT ON COLUMN college_automatic_award_bands.award_as_published IS
    'The school''s own words where the award is not a plain per-year figure -- '
    '"tuition and fees", "tuition plus housing". These are the TOP bands, so '
    'dropping them would make the coach name a school''s second-best award as '
    'its best.';

CREATE TRIGGER trigger_03_enforce_college_automatic_award_bands_updated_at
BEFORE UPDATE ON college_automatic_award_bands
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
