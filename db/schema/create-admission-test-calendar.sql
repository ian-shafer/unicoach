-- admission_test_administrations -- the national SAT and ACT calendar
-- (RFC 197, slice test/04/the-testing-calendar).
--
-- One row per (test, test date): when the test is sat, when registration closes,
-- the publisher's later deadline where one exists, and when scores are released.
-- That is everything the coach needs to work backwards from a school's own
-- application deadline to the date the student must act.
--
-- HAND-MAINTAINED REFERENCE DATA (brief 0010 D19). There is no machine-readable
-- feed for this anywhere: College Board and ACT publish it as HTML tables and
-- nothing else. So every row is typed by a human from the publisher's own page,
-- committed as db/seed/test-calendar/administrations.csv so that a refresh is
-- reviewable in a diff, and GENERATED into a seed migration from that CSV by
-- bin/test-calendar-seed. Never hand-edit the generated seed; edit the CSV and
-- re-run the script. A superseded row is corrected by a NEW generated seed
-- upserting on the natural key, never by editing a landed one.
--
-- EVERY ROW EXPIRES (D7/D20). Past expires_on the coach REFUSES to name a date
-- and points at College Board and ACT, and a test fails when any committed row
-- that is still AHEAD of today is within AdmissionTestAdministration
-- HandTypedReferenceData.REFRESH_LEAD of its expires_on. That constant is the
-- one home for the number -- it moved there from this table's own model when a
-- second hand-typed corpus (RFC 208's state programmes) needed the same ninety
-- days; this comment names it rather than repeating it. Rot is the
-- competitor's failure mode -- the top-ranked article for this question is 2016 and
-- still quotes 2016-17 dates -- so we find ours in CI and not from a family who
-- missed a date.
--
-- Reference-table shape (0060/0077 precedent): unversioned, no soft delete, no
-- OCC, created_at/updated_at with the plain update_colleges_timestamp() trigger.
--
-- `test` and `cycle` are OUR OWN enumerations, so TEXT + CHECK IN (...) with
-- exactly one Kotlin `enum class AdmissionTest(val value: String)` carrying a
-- `fromValue` companion (CLAUDE.md schema conventions, the IncomeBand
-- precedent). The publisher-specific WORDS are in neither this table nor that
-- enum: they are coach-facing English and live in AdmissionTestCopy in
-- :service. The two agencies do not even share a deadline timezone (College
-- Board closes at 11:59 p.m. Eastern, ACT at 11:59 p.m. Central) and they name
-- their later deadline differently ("Deadline for Changes, Regular
-- Cancellation, and Late Registration" vs "Late Deadline"), so a caller that
-- branched on the code to fix its own wording is a caller that should have read
-- that file.
--
-- DERIVED DATES ARE NOT STORED (CLAUDE.md). The fee-waiver lead date is
-- registration_deadline minus TestTimelineService.FEE_WAIVER_LEAD, which is the
-- one home for that number -- College Board's own "ideally 1-2 weeks
-- before the registration deadline" -- and is computed at read time and
-- labelled as derived. "The last usable sitting for this deadline" is likewise
-- computed from test_date, registration_deadline and score_release_date against
-- today; it is an answer about one student, not a fact about a Saturday.
--
-- NO FigureStatus AND NO ASSURANCE TIER. That vocabulary belongs to the
-- canonical money tables, whose rows record a publisher's suppression and
-- imputation flags (RFC 179). A test date is not a money figure, and borrowing
-- the seam would claim a provenance model this data does not have.

CREATE TABLE admission_test_administrations (
    id                         UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    test                       TEXT        NOT NULL,
    cycle                      TEXT        NOT NULL,
    test_date                  DATE        NOT NULL,
    registration_deadline      DATE        NOT NULL,
    late_registration_deadline DATE        NULL,
    score_release_date         DATE        NULL,
    dates_source_url           TEXT        NOT NULL,
    score_release_source_url   TEXT        NULL,
    checked_on                 DATE        NOT NULL,
    expires_on                 DATE        NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- The natural key, and what the generated seed upserts on.
    UNIQUE (test, test_date),

    CONSTRAINT admission_test_administrations_test_check
        CHECK (test IN ('sat', 'act')),

    -- '2026-27': the academic year the publisher labels the calendar with.
    -- The shape alone admitted '2026-99' and '2026-00', which are not academic
    -- years at all, so the second half must be the first half's NEXT year mod
    -- 100 -- '2099-00' included, which is why the arithmetic is mod 100 rather
    -- than a plain increment.
    --
    -- MIRRORED, deliberately: bin/test-calendar-seed's awk validator enforces
    -- the identical rule on the authored CSV, so a bad cycle is refused before
    -- a seed is generated as well as when a row is written. The two are copies
    -- of one rule and have to be changed together.
    CONSTRAINT admission_test_administrations_cycle_format_check
        CHECK (cycle ~ '^[0-9]{4}-[0-9]{2}$'
               AND SUBSTRING(cycle FROM 6 FOR 2)::INT
                   = (SUBSTRING(cycle FROM 1 FOR 4)::INT + 1) % 100),

    -- Registration always closes before the test. A row that says otherwise is
    -- a typo, and a typo in this table is a family missing a deadline.
    CONSTRAINT admission_test_administrations_registration_before_test_check
        CHECK (registration_deadline < test_date),

    CONSTRAINT admission_test_administrations_late_deadline_check
        CHECK (late_registration_deadline IS NULL
               OR (late_registration_deadline > registration_deadline
                   AND late_registration_deadline <= test_date)),

    -- Scores are never released before the test, and an unannounced release is
    -- NULL rather than a guess: at the time of seeding College Board had
    -- published student score releases for fall 2026 only.
    CONSTRAINT admission_test_administrations_score_release_after_test_check
        CHECK (score_release_date IS NULL OR score_release_date > test_date),

    -- A score-release date and the page it was read from travel together: a
    -- citation with no fact under it is not data, and a fact with no citation
    -- is exactly what this brief exists to prevent.
    --
    -- The NULL pairing is not enough on its own. An empty string is not NULL,
    -- so score_release_source_url = '' used to satisfy this constraint AS a
    -- citation -- a date on the wire with nothing behind it. Both url columns
    -- therefore have to LOOK like the page they claim to be. The generator
    -- checks the same thing on the authored CSV; this table is the durable
    -- boundary and the fixtures' write path, so it checks it too.
    CONSTRAINT admission_test_administrations_score_release_citation_check
        CHECK ((score_release_date IS NULL) = (score_release_source_url IS NULL)),

    CONSTRAINT admission_test_administrations_dates_source_url_check
        CHECK (dates_source_url ~ '^https://'),

    CONSTRAINT admission_test_administrations_score_release_url_check
        CHECK (score_release_source_url IS NULL OR score_release_source_url ~ '^https://'),

    CONSTRAINT admission_test_administrations_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

-- NO INDEX ON test_date, deliberately. The only read in the tree is
-- AdmissionTestCalendarDao.list -- `SELECT * ... ORDER BY test_date, test` with
-- no WHERE clause, over fifteen rows -- which reads the whole table whatever
-- indexes exist. The index this file used to declare described a filtered read
-- ("the sittings from today forward") that nobody wrote, and contradicted the
-- DAO's own KDoc, which states that no per-cycle or per-test filter is offered.
-- An index whose comment describes an imaginary query is worse than no index.

CREATE TRIGGER trigger_03_enforce_admission_test_administrations_updated_at
BEFORE UPDATE ON admission_test_administrations
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
