-- CDS admissions reference tables: merit aid (H2A), the C7 admissions factor
-- grid, and application deadlines. RFC 140 (brief 0001 S4a).
--
-- Three reference tables seeded from the open collegedata.fyi CDS corpus via
-- `bin/fetch-cds-seed` (repo-committed CSVs under db/seed/cds/) and loaded by
-- `bin/ingest-colleges`. Like colleges/college_programs (0015) they are
-- externally-sourced data mutated only by re-ingestion (upsert on the natural
-- key), never by application request flow: no OCC versioning, no version
-- history, no soft-delete, and no delete guard -- they are droppable and
-- rebuilt from the committed seed at will. They carry only logical
-- created_at/updated_at with the plain update_colleges_timestamp() trigger
-- from 0015 (touches only updated_at), the reference-table sibling pattern.
--
-- Unversioned by design: `source_year` in each natural key makes history
-- explicit -- a new CDS cycle is a new row -- and a re-ingest of the same
-- cycle is a correction that overwrites in place. `source_year` is defined
-- once, by the `cds_source_year` domain below. `source_url` is the school's own
-- published CDS document; `archive_url` is the corpus's archived copy -- the
-- S4b chat tool renders per-fact citations from these two columns.
--
-- House rule (CLAUDE.md "Schema conventions"): our own enumerations are TEXT +
-- CHECK IN + one Kotlin enum(value)/fromValue (FactorRating,
-- ApplicationRound); raw source codes stay raw; derived figures (the merit-aid
-- share) are computed at read time and labeled, never stored.
--
-- No physical indexes beyond the UNIQUE constraints: every read is by
-- college_id (the leftmost prefix of each natural key) and row counts are
-- ~400-800 per table.

-- ---------------------------------------------------------------------------
-- Shared column domains.
--
-- THE ONE DEFINITION of source_year, for the schema and the Kotlin models
-- alike: the CDS cycle START year -- 2024 means the 2024-25 CDS. Its accepted
-- range is the same in all three tables, so it is declared ONCE as a
-- domain rather than copied as a per-table CHECK -- the same argument the
-- cds_factor_rating domain below makes about the rating vocabulary: a bound
-- written three times is an edit that can be missed in one of them.
-- ---------------------------------------------------------------------------

CREATE DOMAIN cds_source_year AS SMALLINT
    CONSTRAINT cds_source_year_check
    CHECK (VALUE BETWEEN 2015 AND 2100);

-- ---------------------------------------------------------------------------
-- college_merit_aid -- CDS section H2A: institutional non-need ("merit") aid
-- to first-time full-time freshmen who had NO financial need.
--
-- The user-facing share ("X% of freshmen without need got merit") is DERIVED
-- at read time as no_need_merit_count / freshmen_ft_total and labeled as such,
-- never stored.
-- ---------------------------------------------------------------------------

CREATE TABLE college_merit_aid (
    id                   UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id           UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year          cds_source_year NOT NULL,
    -- H.201 FRSH_FT_N: TOTAL degree-seeking first-time full-time freshmen
    -- enrolled (all of them, needy or not) -- the denominator for the derived
    -- share, which is therefore "share of ALL FT freshmen" and must be labeled
    -- that way (the % of specifically-no-need freshmen is NOT computable: CDS
    -- does not report that denominator).
    freshmen_ft_total    INTEGER     NULL,
    -- H.2A01 FRESH_FT_NN_NONEED_N: # of those freshmen who had NO financial
    -- need and were awarded institutional non-need ("merit") aid.
    no_need_merit_count  INTEGER     NULL,
    -- H.2A02 FRESH_FT_NN_NONEED_D: school-reported AVERAGE award in whole
    -- dollars. Source data, not derivable: no total-dollars column exists.
    no_need_merit_avg    INTEGER     NULL,
    source_url           TEXT        NOT NULL,
    archive_url          TEXT        NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (college_id, source_year),
    CONSTRAINT college_merit_aid_nonneg_check
        CHECK ((freshmen_ft_total   IS NULL OR freshmen_ft_total   >= 0) AND
               (no_need_merit_count IS NULL OR no_need_merit_count >= 0) AND
               (no_need_merit_avg   IS NULL OR no_need_merit_avg   >= 0)),
    CONSTRAINT college_merit_aid_count_le_total_check
        CHECK (freshmen_ft_total IS NULL OR no_need_merit_count IS NULL
               OR no_need_merit_count <= freshmen_ft_total)
);

CREATE TRIGGER trigger_03_enforce_college_merit_aid_updated_at
BEFORE UPDATE ON college_merit_aid
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- ---------------------------------------------------------------------------
-- college_admission_factors -- CDS C7 factor grid, wide: one rating per
-- factor, the house own-enumeration pattern (TEXT + CHECK IN, backed by the
-- Kotlin FactorRating enum -- CollegeListEntryStatus/IncomeBand precedent).
-- NULL = not reported, including extraction junk dropped by the fetcher's
-- whitelist ('x', stray numbers, ...): unreported, never guessed.
--
-- The vocabulary is one value shared by every factor column, so it is declared
-- ONCE as a DOMAIN over TEXT (still TEXT + CHECK IN -- the domain only stops
-- the value list from being copied per column, where a single missed edit
-- would silently admit a code FactorRating cannot read). Adding or renaming a
-- rating is one ALTER DOMAIN plus the Kotlin enum, not eighteen edits.
-- ---------------------------------------------------------------------------

CREATE DOMAIN cds_factor_rating AS TEXT
    CONSTRAINT cds_factor_rating_check
    CHECK (VALUE IN ('very_important', 'important', 'considered', 'not_considered'));

CREATE TABLE college_admission_factors (
    id                    UUID              NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id            UUID              NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year           cds_source_year   NOT NULL,
    rigor                 cds_factor_rating NULL,  -- C.701 Rigor of secondary school record
    class_rank            cds_factor_rating NULL,  -- C.702 Class rank
    gpa                   cds_factor_rating NULL,  -- C.703 Academic GPA
    test_scores           cds_factor_rating NULL,  -- C.704 Standardized test scores
    essay                 cds_factor_rating NULL,  -- C.705 Application essay
    recommendations       cds_factor_rating NULL,  -- C.706 Recommendation(s)
    interview             cds_factor_rating NULL,  -- C.707 Interview
    extracurriculars      cds_factor_rating NULL,  -- C.708 Extracurricular activities
    talent                cds_factor_rating NULL,  -- C.709 Talent/ability
    character_qualities   cds_factor_rating NULL,  -- C.710 Character/personal qualities
    first_generation      cds_factor_rating NULL,  -- C.711 First generation
    alumni_relation       cds_factor_rating NULL,  -- C.712 Alumni/ae relation
    geography             cds_factor_rating NULL,  -- C.713 Geographical residence
    state_residency       cds_factor_rating NULL,  -- C.714 State residency
    religious_affiliation cds_factor_rating NULL,  -- C.715 Religious affiliation/commitment
    volunteer_work        cds_factor_rating NULL,  -- C.716 Volunteer work
    work_experience       cds_factor_rating NULL,  -- C.717 Work experience
    applicant_interest    cds_factor_rating NULL,  -- C.718 Level of applicant's interest
    source_url            TEXT              NOT NULL,
    archive_url           TEXT              NULL,
    created_at            TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    UNIQUE (college_id, source_year)
);

CREATE TRIGGER trigger_03_enforce_college_admission_factors_updated_at
BEFORE UPDATE ON college_admission_factors
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- ---------------------------------------------------------------------------
-- college_deadlines -- application rounds, long: one row per (college, cycle,
-- round). CDS gives month/day WITHOUT a year (cycle-relative), so the raw
-- MM/DD is stored and the render layer says "Jan 15" against the cycle --
-- never a fabricated DATE. `offered` is the reliable bit in the corpus
-- (C.2101/C.2201/C.1601/C.1401); the date columns are best-effort and sparse:
-- NULL means "not reported", never interpolated.
--
-- round is our own taxonomy, the same TEXT-enum pattern (Kotlin
-- ApplicationRound): 'regular' | 'priority' | 'early_decision_1' |
-- 'early_decision_2' | 'early_action' | 'rolling'.
-- ---------------------------------------------------------------------------

CREATE TABLE college_deadlines (
    id                 UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id         UUID        NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    source_year        cds_source_year NOT NULL,
    round              TEXT        NOT NULL,
    offered            BOOLEAN     NOT NULL,  -- the reliable bit (C.2101/C.2201/C.1601/C.1401)
    closing_month      SMALLINT    NULL,      -- best-effort (sparse in corpus)
    closing_day        SMALLINT    NULL,
    notification_month SMALLINT    NULL,
    notification_day   SMALLINT    NULL,
    source_url         TEXT        NOT NULL,
    archive_url        TEXT        NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (college_id, source_year, round),
    CONSTRAINT college_deadlines_round_check
        CHECK (round IN ('regular','priority','early_decision_1',
                         'early_decision_2','early_action','rolling')),
    CONSTRAINT college_deadlines_month_day_check
        CHECK ((closing_month      IS NULL OR closing_month      BETWEEN 1 AND 12) AND
               (closing_day        IS NULL OR closing_day        BETWEEN 1 AND 31) AND
               (notification_month IS NULL OR notification_month BETWEEN 1 AND 12) AND
               (notification_day   IS NULL OR notification_day   BETWEEN 1 AND 31)),
    -- A month without a day is real CDS reporting ("applications close in
    -- March", no day given) and is stored as-is; a DAY without a month is
    -- junk no render layer can use and the corpus never produces, so it is
    -- rejected here as well as being unrepresentable in Kotlin (CdsMonthDay).
    CONSTRAINT college_deadlines_day_requires_month_check
        CHECK ((closing_day      IS NULL OR closing_month      IS NOT NULL) AND
               (notification_day IS NULL OR notification_month IS NOT NULL))
);

CREATE TRIGGER trigger_03_enforce_college_deadlines_updated_at
BEFORE UPDATE ON college_deadlines
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
