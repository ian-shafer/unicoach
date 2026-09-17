-- admission_test_state_programmes -- the state-run, school-day SAT and ACT
-- programmes (RFC 208, slice test/05/your-state-already-pays).
--
-- One row per US jurisdiction PER SCHOOL YEAR we have read a state source for:
-- whether the state requires or merely offers an admission test inside the
-- school day, who chooses WHICH test, which test that is where the state chose
-- it, the grade or grades it happens in, whether the state pays, the state page
-- it was read from, and the day a human read it. For a large share of students
-- the first sitting is already scheduled, in school, and free; telling that
-- family to pay for a Saturday is the trust-losing answer this table exists to
-- prevent.
--
-- IT IS A FACT ABOUT THE STATE AND NEVER ABOUT THE SCHOOL. Whether one named
-- high school runs its test day, and on what date, is not in here and cannot be
-- inferred from here: that is the single school-level question the coach may ask
-- (brief 0010 D10), it gates nothing, and "I don't know" is a complete answer.
--
-- IT IS APPEND-ONLY HISTORY, AND THAT IS THE LOAD-BEARING DECISION (Ian,
-- 2026-09-14: "one row per state, replaced yearly is an anti-pattern because it
-- makes data depend on outside processes; data should be able to stand on its
-- own and not lose value or correctness over time"). A re-read ADDS a row for
-- the new school year. Nothing is overwritten, so last year's answer stays true
-- about last year, and the corpus does not silently become wrong the moment
-- somebody stops running a script. Illinois moved from the SAT to the ACT and
-- Kentucky from the ACT to the SAT within two cycles, and both of those facts
-- are worth keeping rather than erasing.
--
-- THE READ TAKES THE NEWEST ROW PER STATE, and so does the staleness guard.
-- `DISTINCT ON (state) ... ORDER BY state, school_year DESC, checked_on DESC`.
-- Newest-per-state is what makes the guard both firing and clearable: a state
-- whose newest row is inside HandTypedReferenceData.REFRESH_LEAD of that row's
-- expires_on fails the build, and APPENDING this year's row for that state --
-- the one action the failure message asks for -- clears it. A minimum over ALL
-- rows would be the unclearable bar RFC 197's review caught, because in an
-- append-only corpus last year's row keeps its own expiry forever. And a
-- corpus-wide "some row is fresh" bar would let a state nobody re-checks hide
-- behind a fresher neighbour; per-state, it cannot.
--
-- EVERY COLUMN'S NULLABILITY WAS SETTLED BY READING THE PAGES, NOT BY DESIGNING
-- FIRST. Of the 31 jurisdictions actually sourced, most pages name no school
-- year at all and only 9 say who pays. A NOT NULL column there would have forced
-- either dropping a true row or inventing the missing half, and inventing a year
-- on a page that names none is the defect this brief exists to stop. So who pays
-- may be absent, and a school year the page did not state is recorded as OURS --
-- see school_year_basis.
--
-- HAND-MAINTAINED REFERENCE DATA (brief 0010 D19), on the RFC 197 pattern and
-- deliberately NOT the Common Data Set pattern: the CDS corpus loads through the
-- ingest binary, `bin/test` never ingests, and rows would therefore be absent
-- from every test database until a full re-ingest. So the authored source is
-- db/seed/test-state-programmes/programmes.csv, reviewable in a diff, and the
-- seed migration is GENERATED from it by bin/test-state-programme-seed, one seed
-- per reading session. Never hand-edit a generated seed; add rows to the CSV and
-- re-run the script.
--
-- ABSENCE MEANS WE DID NOT READ A SOURCE, NOT THAT THE STATE RUNS NOTHING.
-- There is no 'none' participation and no row asserting one. A confident "your
-- state runs no in-school test" is the same trust loss in the other direction
-- and needs a primary page saying so, which state assessment pages mostly do
-- not. A state with no row is reported as a gap in our data, in those words.
--
-- Reference-table shape (0060/0077 precedent, and its sibling
-- admission_test_administrations): unversioned, no soft delete, no OCC,
-- created_at/updated_at with the plain update_colleges_timestamp() trigger.
--
-- NO FigureStatus AND NO ASSURANCE TIER, for the reason RFC 197 states: that
-- vocabulary belongs to the canonical money tables and would claim a provenance
-- model this data does not have.

CREATE TABLE admission_test_state_programmes (
    id                UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- KEYED INTO us_states (RFC 229), the vocabulary this column always
    -- belonged in: usps_code TEXT PRIMARY KEY, 59 rows, with a
    -- jurisdiction_kind that already tells a state from DC, a territory and a
    -- freely-associated state. Ian asked for the key explicitly, and he was
    -- right: 'ZZ' passes a regex and is nobody's jurisdiction.
    --
    -- The key arrives with test/05.3/own-our-codebooks: RFC 229 seeds the
    -- published codebooks at MIGRATION time from db/data/codebooks.json, so a
    -- migration-time row may key into them. The interim format CHECK this column
    -- carried until then is DROPPED in the same migration rather than left
    -- beside the key -- a key is strictly stronger than a regex.
    state             TEXT          NOT NULL,

    -- The school year this row describes, under the `academic_year` domain
    -- (0087, RFC 170 D14): a SMALLINT START year. 2026 IS the 2026-27 school
    -- year, and the '2026-27' label is rendered at read time by AcademicYear in
    -- :common and never stored.
    --
    -- The first draft stored the LABEL as TEXT with a '^[0-9]{4}-[0-9]{2}$'
    -- CHECK plus arithmetic, and mirrored that rule into the generator's
    -- validator -- one rule typed twice, in two languages, which 0087's own
    -- comment exists to stop: "a regex cannot say the second half is the first
    -- plus one. A SMALLINT start year cannot spell that mistake at all." Both
    -- the CHECK and its mirror are deleted, not ported.
    school_year       academic_year NOT NULL,

    -- WHOSE year that is. 'stated_by_source': the state's page named it.
    -- 'reading_year': the page named none, so the row carries the school year we
    -- READ it in, and says so. Most of these pages describe a standing programme
    -- with no year on it at all, and the alternative to this column is inventing
    -- a year on the page's behalf -- which is exactly what this brief exists to
    -- refuse. The coach speaks the difference: "the page names the 2026-27 year"
    -- is a different sentence from "we read this in the 2026-27 year".
    school_year_basis TEXT          NOT NULL,

    participation     TEXT          NOT NULL,

    -- Whether the state requires every public high school to HOLD the sitting,
    -- which is a different fact from whether a student must sit it. Virginia is
    -- why the column exists: its law makes every school board run the SAT School
    -- Day and then says "no such student shall be required to participate", so
    -- `participation` is honestly 'offered' and that alone throws away the
    -- strongest sentence in the corpus -- in Virginia the day WILL be held at
    -- your school, which is nearly the school-level answer this table otherwise
    -- cannot give.
    --
    -- NULL is UNKNOWN and never false: we did not read this for most states, and
    -- "the state does not require schools to hold it" is a claim needing its own
    -- page, exactly like a 'none' programme row.
    school_must_offer BOOLEAN       NULL,

    test_chooser      TEXT          NOT NULL,
    test              TEXT          NULL,
    lowest_grade      SMALLINT      NULL,
    highest_grade     SMALLINT      NULL,
    -- Who pays, which is the headline claim of this slice -- so it is a column
    -- read from a page and never an inference from participation. NULL means the
    -- state's own page does not say, which is the commonest case (22 of 31), and
    -- the coach then says we do not know who pays rather than that the sitting is
    -- free. Nothing here records a price: "the state pays" and "the page does not
    -- say" are the only two things these sources support.
    state_funded      BOOLEAN       NULL,
    source_url        TEXT          NOT NULL,
    archive_url       TEXT          NULL,
    -- TWO CLOCKS, ON PURPOSE (Ian, 2026-09-14). A DATE is a day in the WORLD:
    -- the day a human read the state's page, and the day this reading stops
    -- being believed. Neither has an hour, and giving one a timezone would
    -- invent precision the fact does not have. A TIMESTAMPTZ is a fact about
    -- OUR ROW: when we wrote it and when we last touched it, which are instants
    -- and belong on a clock. The mixture is the rule, not an oversight.
    checked_on        DATE          NOT NULL,
    expires_on        DATE          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- The natural key of an append-only corpus, and what the generated seed
    -- upserts on. One state, one school year, one answer; a re-read of the same
    -- state in a LATER year is a new row, and a corrected re-read of the SAME
    -- year is a correction of that row rather than a second contradictory one.
    UNIQUE (state, school_year),

    CONSTRAINT admission_test_state_programmes_state_format_check
        CHECK (state ~ '^[A-Z]{2}$'),

    CONSTRAINT admission_test_state_programmes_school_year_basis_check
        CHECK (school_year_basis IN ('stated_by_source', 'reading_year')),

    -- A row that says the year is OURS must carry the year we actually read it
    -- in: the academic year of checked_on, under the same 1 July boundary the
    -- rest of this product's school years use. Without it, 'reading_year' would
    -- be an unfalsifiable label on any number in range.
    CONSTRAINT admission_test_state_programmes_reading_year_check
        CHECK (school_year_basis <> 'reading_year'
               OR school_year = EXTRACT(YEAR FROM checked_on)::INT
                                - CASE WHEN EXTRACT(MONTH FROM checked_on) < 7 THEN 1 ELSE 0 END),

    -- A page may legitimately name NEXT school year; it cannot name 2056. The
    -- bound matters more here than it looks: the read and the staleness guard
    -- both take the NEWEST row per state, so a typo'd future year would become
    -- that state's permanent answer AND would never be stale.
    CONSTRAINT admission_test_state_programmes_school_year_horizon_check
        CHECK (school_year <= EXTRACT(YEAR FROM checked_on)::INT
                              - CASE WHEN EXTRACT(MONTH FROM checked_on) < 7 THEN 1 ELSE 0 END
                              + 1),

    -- OUR OWN enumerations (CLAUDE.md), so TEXT + CHECK IN (...) plus exactly
    -- one Kotlin enum class each with a fromValue companion.
    --
    -- TWO AXES AND NOT ONE, and the data is what settled it. The first draft
    -- fused them into a single 'required | offered | district_choice' column,
    -- which cannot express either of the two states that broke it: Tennessee
    -- REQUIRES every eleventh grader to sit one and lets the DISTRICT pick
    -- which, and South Carolina OFFERS both and lets the STUDENT pick. Who must
    -- sit it and who chooses it are independent facts about a state, and a
    -- family hears them as different sentences.
    CONSTRAINT admission_test_state_programmes_participation_check
        CHECK (participation IN ('required', 'offered')),

    CONSTRAINT admission_test_state_programmes_test_chooser_check
        CHECK (test_chooser IN ('state', 'district', 'student')),

    CONSTRAINT admission_test_state_programmes_test_check
        CHECK (test IS NULL OR test IN ('sat', 'act')),

    -- The pairing, and it is the honesty of the row (the RFC 197
    -- score-release-citation precedent). The state names a test exactly when the
    -- state is the one that chose it. Either half alone is a claim no source
    -- supports: a district-chosen row carrying 'sat' says the state chose after
    -- all, and a state-chosen row carrying no test tells a family to sit
    -- something unnamed. Arkansas is why the third chooser exists: its 2025 law
    -- funds a MENU (ACT, SAT, CLT), so the state chose none of them, and the
    -- first draft would have recorded it as an ACT state it is not.
    CONSTRAINT admission_test_state_programmes_test_pairing_check
        CHECK ((test IS NOT NULL) = (test_chooser = 'state')),

    -- The grade or grades the sitting happens in -- almost always 11 alone, and
    -- Minnesota's statute covers grade 11 OR 12, which a single column could
    -- only have rendered as a guess at one of them. BOTH ends travel together or
    -- neither does: NULL is legal and means the source named no grade, and is
    -- never completed with a guess, because "your junior year" is the sentence a
    -- wrong guess here breaks.
    CONSTRAINT admission_test_state_programmes_grade_pairing_check
        CHECK ((lowest_grade IS NULL) = (highest_grade IS NULL)),

    CONSTRAINT admission_test_state_programmes_grade_range_check
        CHECK (lowest_grade IS NULL
               OR (lowest_grade BETWEEN 9 AND 12
                   AND highest_grade BETWEEN 9 AND 12
                   AND lowest_grade <= highest_grade)),

    -- The state's own page, read on checked_on, and an archive capture OF THAT
    -- SAME PAGE where one was needed -- the citation shape RFC 140 already uses
    -- for the Common Data Set (source_url + archive_url), reused rather than
    -- reinvented. Arizona's and New Hampshire's departments return 403 to every
    -- client we have, and those rows were read from captures of those official
    -- URLs; recording only the live URL would hide how the row was read, and
    -- recording only the capture would hide whose page it is.
    --
    -- http IS ACCEPTED (Ian, 2026-09-14: "https only -- why? We should prefer
    -- https, but not block insecure"). A state agency page served over plain
    -- http is still the state's own page, and refusing it would drop a true row
    -- over a transport detail. The rule the citation actually needs is that
    -- there IS a page: a non-empty URL that looks like one. Prefer https when
    -- the same page is reachable both ways; never reject a row for it.
    CONSTRAINT admission_test_state_programmes_source_url_check
        CHECK (source_url ~ '^https?://'),

    CONSTRAINT admission_test_state_programmes_archive_url_check
        CHECK (archive_url IS NULL OR archive_url ~ '^https?://'),

    -- The day this reading stops being believed. It is a fact about the READING
    -- and not a promise about the world, which is what keeps it honest in an
    -- append-only corpus: past it, the coach says we hold no current record for
    -- that state and points at the state's page, and the row still says what we
    -- believed and when.
    CONSTRAINT admission_test_state_programmes_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

-- NO INDEX, deliberately, on the RFC 197 reasoning. The only read in the tree is
-- AdmissionTestStateProgrammeDao.listNewestPerState -- one DISTINCT ON over a
-- table whose whole history is at most a few hundred rows -- which reads
-- everything whatever indexes exist. The staleness guard needs every state's
-- newest row, and the per-student lookup is a map over a list already in memory.
-- An index describing a query nobody wrote is worse than no index. When this
-- corpus has ten years of history and a real filtered read, the index arrives
-- with that read.

CREATE TRIGGER trigger_03_enforce_admission_test_state_programmes_updated_at
BEFORE UPDATE ON admission_test_state_programmes
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- A SECOND OFFICIAL DOCUMENT IS NOT MODELLED HERE, DELIBERATELY (Ian,
-- 2026-09-14). Two states in the first corpus -- Utah and Delaware -- have
-- claims split across two state publications, and this file briefly carried a
-- child table for them. It was dropped before landing: storing links to external
-- documents, and the archives of them, is a general need this repo meets in at
-- least eight places today with a pair of url columns, and it is getting a
-- general `external_documents` model with a mapping table in its own slice, plus
-- a cleanup that migrates every one of those columns -- these two included. A
-- per-corpus sources table would have been a ninth private answer to a question
-- about to get a public one.
--
-- So a row cites ONE document: the page the coach links, whose claims that page
-- supports. A state whose page does not support the whole row does not ship a
-- row (Utah, held out for exactly this reason); the second document is recorded
-- in the authored corpus's notes, where the human re-reading the row next year
-- is already looking.
