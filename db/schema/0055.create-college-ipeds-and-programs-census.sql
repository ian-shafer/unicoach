-- IPEDS institutional attributes and the 6-digit program census. RFC 144 (0004 S2).
--
-- Reference tables, RFC 84 composition: unversioned (gate-2 D15 — no history
-- trigger, no soft delete), upserted on their natural keys by
-- bin/ingest-colleges. Their history is college_index_build.
--
-- Raw IPEDS codes are stored as-is (brief 0004's raw-codes rule; word enums are
-- a tool-boundary concern). NULL means UNKNOWN: the -1 "not reported" and -3
-- "not available" sentinels, IC's '.' for a continuous column, and ADM's empty
-- string all land as NULL. The -2 "not applicable" sentinel is NOT unknown --
-- for these columns it is a real "no"/"none", so it is PRESERVED where the
-- column is a code (rel_affil, carnegie_basic, carnegie_size, cbsa,
-- football_conf) and mapped to FALSE where the column is a boolean.

-- One row per HD record whose UNITID matches an existing colleges.unit_id;
-- IC and ADM are left-joined in memory (749 of the 2,488 four-year universe
-- institutions have no ADM row at all, so test_policy is simply NULL for them).
CREATE TABLE college_ipeds (
    id                 UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    unit_id            INTEGER     NOT NULL,             -- joins colleges.unit_id
    survey_year        SMALLINT    NOT NULL,             -- --survey-year, e.g. 2023
    cy_active          BOOLEAN     NOT NULL,             -- HD.CYACTIVE 1=TRUE, 3=FALSE
    death_year         SMALLINT    NULL,                 -- HD.DEATHYR
    closed_at          DATE        NULL,                 -- HD.CLOSEDAT
    new_unit_id        INTEGER     NULL,                 -- HD.NEWID (merger successor)
    inst_level         SMALLINT    NULL,                 -- HD.ICLEVEL 1/2/3
    ug_offer           BOOLEAN     NULL,                 -- HD.UGOFFER
    sector             SMALLINT    NULL,                 -- HD.SECTOR (D23; 0 = admin unit)
    carnegie_basic     SMALLINT    NULL,                 -- HD.C21BASIC (-2 = not classified)
    carnegie_size      SMALLINT    NULL,                 -- HD.C21SZSET (-2 = not classified)
    cbsa               INTEGER     NULL,                 -- HD.CBSA (-2 = not in a CBSA)
    rel_affil          SMALLINT    NULL,                 -- IC.RELAFFIL (-2 = explicitly none)
    has_rotc           BOOLEAN     NULL,                 -- IC.SLO5
    has_study_abroad   BOOLEAN     NULL,                 -- IC.SLO6
    disability_band    SMALLINT    NULL,                 -- IC.DISAB 1 = <=3%, 2 = >3% (D24)
    disability_pct     DOUBLE PRECISION NULL,            -- IC.DISABPCT (only when band = 2)
    has_housing        BOOLEAN     NULL,                 -- IC.ROOM
    housing_capacity   INTEGER     NULL,                 -- IC.ROOMCAP
    application_fee    INTEGER     NULL,                 -- IC.APPLFEEU (0 is a real free app)
    athletic_assoc     SMALLINT[]  NOT NULL DEFAULT '{}',-- IC.ASSOC1..6 ordinals that are 1
    football_conf      SMALLINT    NULL,                 -- IC.CONFNO1 (-2 = none)
    test_policy        SMALLINT    NULL,                 -- ADM.ADMCON7 1 req / 3 blind / 5 optional
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT college_ipeds_unit_id_positive_check    CHECK (unit_id > 0),
    CONSTRAINT college_ipeds_survey_year_range_check   CHECK (survey_year BETWEEN 1980 AND 2100),
    CONSTRAINT college_ipeds_death_year_range_check
        CHECK (death_year IS NULL OR death_year BETWEEN 1980 AND 2100),
    CONSTRAINT college_ipeds_new_unit_id_positive_check
        CHECK (new_unit_id IS NULL OR new_unit_id > 0),
    CONSTRAINT college_ipeds_inst_level_domain_check
        CHECK (inst_level IS NULL OR inst_level IN (1, 2, 3)),
    -- The PUBLISHED code set, not a range: 0..9 plus 99 ("sector unknown, not
    -- active"). 10..98 are values IPEDS does not emit.
    CONSTRAINT college_ipeds_sector_domain_check
        CHECK (sector IS NULL OR sector BETWEEN 0 AND 9 OR sector = 99),
    CONSTRAINT college_ipeds_disability_band_domain_check
        CHECK (disability_band IS NULL OR disability_band IN (1, 2)),
    CONSTRAINT college_ipeds_disability_pct_range_check
        CHECK (disability_pct IS NULL OR (disability_pct >= 0 AND disability_pct <= 100)),
    CONSTRAINT college_ipeds_housing_capacity_nonneg_check
        CHECK (housing_capacity IS NULL OR housing_capacity >= 0),
    CONSTRAINT college_ipeds_application_fee_nonneg_check
        CHECK (application_fee IS NULL OR application_fee >= 0),
    CONSTRAINT college_ipeds_test_policy_domain_check
        CHECK (test_policy IS NULL OR test_policy IN (1, 3, 5))
);
CREATE UNIQUE INDEX college_ipeds_unit_id_unique_idx ON college_ipeds (unit_id);

-- Reserved enforce-updated_at slot (_03), per the trigger_NN convention; reuses
-- update_colleges_timestamp() (0015), which touches only updated_at. This is the
-- ONLY trigger either table carries: gate-2 D15 puts no versioning/history
-- trigger on reference tables, and an updated_at column with nothing advancing
-- it would be a stale lie rather than a saved trigger.
CREATE TRIGGER trigger_03_enforce_college_ipeds_updated_at
BEFORE UPDATE ON college_ipeds
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- Known tri-state gap, stated rather than hidden (RFC 144): athletic_assoc is
-- NOT NULL DEFAULT '{}', so the 6 institutions whose ASSOC1..6 are all -1
-- (unreported) are indistinguishable from "belongs to no association". Six rows
-- did not justify changing the approved shape.
COMMENT ON COLUMN college_ipeds.athletic_assoc IS
    'IC.ASSOC1..6 ordinals set to 1 (1 NCAA, 2 NAIA, 3 NJCAA, 4 NSCAA, 5 NCCAA, 6 other). '
    'An empty array means BOTH "belongs to none" and "all six were -1 unreported" (6 rows in 2023).';

-- 6-digit program census (IPEDS C_A), bachelor's first majors. RFC 144 (0004 S2).
-- The MAJORNUM = 1 ingest filter is what makes this unique key sound: over
-- AWLEVEL = 5 the second-major rows collide on (unitid, cipcode, awlevel)
-- 19,041 times and would double-count students.
CREATE TABLE college_programs_census (
    id           UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id   UUID        NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    cip_code     TEXT        NOT NULL,     -- 6-digit, dot stripped from C_A.CIPCODE
    award_level  SMALLINT    NOT NULL,     -- IPEDS AWLEVEL (5 = bachelor's)
    awards_total INTEGER     NOT NULL,     -- C_A.CTOTALT
    survey_year  SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT college_programs_census_cip_code_format_check
        CHECK (cip_code ~ '^[0-9]{6}$'),
    CONSTRAINT college_programs_census_award_level_positive_check
        CHECK (award_level > 0),
    CONSTRAINT college_programs_census_awards_total_nonneg_check
        CHECK (awards_total >= 0),
    CONSTRAINT college_programs_census_survey_year_range_check
        CHECK (survey_year BETWEEN 1980 AND 2100)
);
CREATE UNIQUE INDEX college_programs_census_unique_idx
    ON college_programs_census (college_id, cip_code, award_level);
CREATE INDEX college_programs_census_cip_idx ON college_programs_census (cip_code);

CREATE TRIGGER trigger_03_enforce_college_programs_census_updated_at
BEFORE UPDATE ON college_programs_census
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
