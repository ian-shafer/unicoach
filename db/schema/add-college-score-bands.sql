-- Store the published 25th/75th percentile admission score bands (RFC 195,
-- brief 0010 slice `test/03/the-range-not-the-average`).
--
-- WHAT THIS IS. Six College Scorecard institution-file variables, on `colleges`
-- beside `sat_average_equivalent_score` (`SAT_AVG`): SATVR25/75 (SAT critical
-- reading), SATMT25/75 (SAT math) and ACTCM25/75 (ACT cumulative). A student
-- can locate herself in a RANGE; an admitted-student average reads as a bar.
-- The band is a fact about last year's admitted class, never a target -- brief
-- 0010's D9 (no test prep, no score prediction, no chancing) is inherited and
-- absolute, and is enforced above this table, in `ScoreBandCopy`.
--
-- THE COHORT. All six are dated by the publisher's own dictionary
-- (CollegeScorecardDataDictionary.xlsx, sheet `Most_Recent_Inst_Cohort_Map`) to
-- "Fall 2024, reported in IPEDS DCY2024-25", with sheet
-- `Institution_Data_Dictionary` giving SOURCE = IPEDS for all six. That is an
-- entering-cohort TERM, not an academic year, which is why the date is a named
-- constant in the score-band code rather than a row in the money seam's
-- academic-year vintage machinery. `db/seed/scorecard/FETCH-NOTES.md` records
-- the same cell and why these six are not added to
-- `dictionary-variable-sources.csv`.
--
-- MEASURED, not assumed, over the fetched snapshot (`PROVENANCE.json` release
-- `06102026`, 6,273 institution rows): SATVR25/75 and SATMT25/75 present on
-- the same 949 rows, ACTCM25/75 on 920, zero 25/75 mismatches in all three
-- families, every cell a plain integer with no `PrivacySuppressed` or `NA`
-- token, and no row with 25 > 75. The pairing and the ordering are therefore
-- stated as CHECKs rather than hoped for.
--
-- NO BACKFILL and NO INDEX. The next ingest fills them; existing
-- `colleges_versions` rows stay NULL, which is correct -- those ingests never
-- saw the fields. Nothing filters on a band, and `college_search_index`
-- deliberately does not carry the SAT average either (`0064:185`), so search
-- is untouched.
--
-- ORDER MATTERS INSIDE THIS TRANSACTION, and PART 3 IS NOT OPTIONAL.
-- `log_college_version()` names every `colleges` column literally on both
-- sides of its VALUES, and a plpgsql body is stored as TEXT and is not
-- dependency-tracked -- so ADD COLUMN neither rewrites it nor fails on it. A
-- stale body passes this migration and then kills the very next write to
-- `colleges` with `42703 record "new" has no field ...`, from inside the
-- trigger, in the middle of an ingest (`0094:57-62` records the same trap for
-- the drop direction).

-- ---------------------------------------------------------------------------
-- Part 1 -- six columns on the live table, with their domains.
-- ---------------------------------------------------------------------------
ALTER TABLE colleges
    ADD COLUMN sat_reading_25th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_reading_75th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_math_25th_percentile_score      INTEGER NULL,
    ADD COLUMN sat_math_75th_percentile_score      INTEGER NULL,
    ADD COLUMN act_composite_25th_percentile_score INTEGER NULL,
    ADD COLUMN act_composite_75th_percentile_score INTEGER NULL,
    -- The published scales, not a guess: an SAT section is 200-800, an ACT
    -- composite is 1-36. A value outside them is a loader fault, never data.
    ADD CONSTRAINT colleges_sat_reading_25th_percentile_score_domain_check
        CHECK (sat_reading_25th_percentile_score IS NULL
               OR sat_reading_25th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_reading_75th_percentile_score_domain_check
        CHECK (sat_reading_75th_percentile_score IS NULL
               OR sat_reading_75th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_math_25th_percentile_score_domain_check
        CHECK (sat_math_25th_percentile_score IS NULL
               OR sat_math_25th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_math_75th_percentile_score_domain_check
        CHECK (sat_math_75th_percentile_score IS NULL
               OR sat_math_75th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_act_composite_25th_percentile_score_domain_check
        CHECK (act_composite_25th_percentile_score IS NULL
               OR act_composite_25th_percentile_score BETWEEN 1 AND 36),
    ADD CONSTRAINT colleges_act_composite_75th_percentile_score_domain_check
        CHECK (act_composite_75th_percentile_score IS NULL
               OR act_composite_75th_percentile_score BETWEEN 1 AND 36),
    -- A band is a PAIR. Half a band is not a range, and the snapshot has none:
    -- 0 mismatches over 6,273 rows in all three families.
    ADD CONSTRAINT colleges_sat_reading_band_paired_check
        CHECK ((sat_reading_25th_percentile_score IS NULL)
               = (sat_reading_75th_percentile_score IS NULL)),
    ADD CONSTRAINT colleges_sat_math_band_paired_check
        CHECK ((sat_math_25th_percentile_score IS NULL)
               = (sat_math_75th_percentile_score IS NULL)),
    ADD CONSTRAINT colleges_act_composite_band_paired_check
        CHECK ((act_composite_25th_percentile_score IS NULL)
               = (act_composite_75th_percentile_score IS NULL)),
    -- ... and an ORDERED pair. `>=`, not `>`: a school where the 25th and 75th
    -- percentile land on the same score is a real narrow class, not a fault.
    ADD CONSTRAINT colleges_sat_reading_band_ordered_check
        CHECK (sat_reading_75th_percentile_score IS NULL
               OR sat_reading_75th_percentile_score >= sat_reading_25th_percentile_score),
    ADD CONSTRAINT colleges_sat_math_band_ordered_check
        CHECK (sat_math_75th_percentile_score IS NULL
               OR sat_math_75th_percentile_score >= sat_math_25th_percentile_score),
    ADD CONSTRAINT colleges_act_composite_band_ordered_check
        CHECK (act_composite_75th_percentile_score IS NULL
               OR act_composite_75th_percentile_score >= act_composite_25th_percentile_score);

-- The 0059 convention: the federal variable name and the definition live in the
-- COMMENT, never in the column name, and each names the cohort it describes.
COMMENT ON COLUMN colleges.sat_reading_25th_percentile_score IS
    'College Scorecard SATVR25: the 25th-percentile SAT critical reading score of '
    'students admitted for the Fall 2024 entering cohort (IPEDS DCY2024-25). A '
    'percentile of last year''s admitted class, never a cut-off or a target; '
    'paired with sat_reading_75th_percentile_score by CHECK.';
COMMENT ON COLUMN colleges.sat_reading_75th_percentile_score IS
    'College Scorecard SATVR75: the 75th-percentile SAT critical reading score of '
    'students admitted for the Fall 2024 entering cohort (IPEDS DCY2024-25). A '
    'percentile of last year''s admitted class, never a cut-off or a target; '
    'paired with sat_reading_25th_percentile_score by CHECK.';
COMMENT ON COLUMN colleges.sat_math_25th_percentile_score IS
    'College Scorecard SATMT25: the 25th-percentile SAT math score of students '
    'admitted for the Fall 2024 entering cohort (IPEDS DCY2024-25). A percentile '
    'of last year''s admitted class, never a cut-off or a target; paired with '
    'sat_math_75th_percentile_score by CHECK.';
COMMENT ON COLUMN colleges.sat_math_75th_percentile_score IS
    'College Scorecard SATMT75: the 75th-percentile SAT math score of students '
    'admitted for the Fall 2024 entering cohort (IPEDS DCY2024-25). A percentile '
    'of last year''s admitted class, never a cut-off or a target; paired with '
    'sat_math_25th_percentile_score by CHECK.';
COMMENT ON COLUMN colleges.act_composite_25th_percentile_score IS
    'College Scorecard ACTCM25: the 25th-percentile ACT cumulative (composite) '
    'score of students admitted for the Fall 2024 entering cohort (IPEDS '
    'DCY2024-25). A percentile of last year''s admitted class, never a cut-off or '
    'a target; paired with act_composite_75th_percentile_score by CHECK.';
COMMENT ON COLUMN colleges.act_composite_75th_percentile_score IS
    'College Scorecard ACTCM75: the 75th-percentile ACT cumulative (composite) '
    'score of students admitted for the Fall 2024 entering cohort (IPEDS '
    'DCY2024-25). A percentile of last year''s admitted class, never a cut-off or '
    'a target; paired with act_composite_25th_percentile_score by CHECK.';

-- ---------------------------------------------------------------------------
-- Part 2 -- the history mirror.
-- ---------------------------------------------------------------------------
--
-- NO constraints here, the 0045/0062/0094 pattern: the history table records
-- what was written, and a constraint on it would reject history the live table
-- accepted.
ALTER TABLE colleges_versions
    ADD COLUMN sat_reading_25th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_reading_75th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_math_25th_percentile_score      INTEGER NULL,
    ADD COLUMN sat_math_75th_percentile_score      INTEGER NULL,
    ADD COLUMN act_composite_25th_percentile_score INTEGER NULL,
    ADD COLUMN act_composite_75th_percentile_score INTEGER NULL;

-- ---------------------------------------------------------------------------
-- Part 3 -- restate the history writer.
-- ---------------------------------------------------------------------------
--
-- The 0094 body verbatim plus the six names on BOTH sides of the VALUES: 19
-- columns become 25. CREATE OR REPLACE, never DROP/CREATE -- the existing
-- `trigger_04_log_college_version` binds by name and must not be re-wired (the
-- 0023/0045/0051/0057/0059/0062/0094 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, ipeds_unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment_headcount,
        admission_rate_share, sat_average_equivalent_score,
        sat_reading_25th_percentile_score, sat_reading_75th_percentile_score,
        sat_math_25th_percentile_score, sat_math_75th_percentile_score,
        act_composite_25th_percentile_score, act_composite_75th_percentile_score,
        completion_rate_150pct_4yr_share, website,
        aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.ipeds_unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment_headcount, NEW.admission_rate_share,
        NEW.sat_average_equivalent_score,
        NEW.sat_reading_25th_percentile_score, NEW.sat_reading_75th_percentile_score,
        NEW.sat_math_25th_percentile_score, NEW.sat_math_75th_percentile_score,
        NEW.act_composite_25th_percentile_score, NEW.act_composite_75th_percentile_score,
        NEW.completion_rate_150pct_4yr_share,
        NEW.website, NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
