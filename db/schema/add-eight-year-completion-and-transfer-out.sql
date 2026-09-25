-- Store the eight-year award-completion figure, its Pell / non-Pell arms, and
-- the four-year transfer-out share (RFC 242, brief 0011 slice
-- `beyond/06/finish-like-me`).
--
-- WHAT THIS IS. Four College Scorecard institution-file variables, on
-- `colleges` beside `completion_rate_150pct_4yr_share` (`C150_4`):
-- OMAWDP8_ALL, OMAWDP8_PELL_ALL, OMAWDP8_NOPELL_ALL and TRANS_4. The figure
-- this corpus serves today for "do students finish there?" is `C150_4`, which
-- counts only students who started full-time, for the FIRST time, at a
-- four-year school, and which counts a student who transfers away and
-- graduates elsewhere as a failure. The publisher's Outcome Measures family
-- counts everyone who entered -- full-time and part-time, new to college and
-- transferring in -- over eight years, and splits that by federal Pell Grant
-- receipt. TRANS_4 says how many of the C150_4 cohort left for another school
-- instead of finishing there.
--
-- THE COHORT, from the publisher's own dictionary
-- (CollegeScorecardDataDictionary.xlsx, sheet `Most_Recent_Inst_Cohort_Map`,
-- read 2026-09-25; see `db/seed/scorecard/FETCH-NOTES.md`): the three
-- OMAWDP8_* variables are dated "AY 2016-17 cohort, reported in IPEDS
-- DCY2024-25" -- a full ACADEMIC-YEAR entering cohort, not a Fall term --
-- while TRANS_4 carries C150_4's own cell, "Fall 2018 or AcadYr 2018-19 cohort
-- (differs by institutional calendar)". Sheet `Institution_Data_Dictionary`
-- gives SOURCE = IPEDS for all four. Those cells are transcribed into
-- `db/seed/scorecard/dictionary-variable-sources.csv`, which is what the
-- served citations are pinned against; the years are NOT re-typed anywhere in
-- Kotlin. The eight-year figures and the six-year figure therefore describe
-- DIFFERENT entering cohorts as well as different populations, which is why no
-- code and no copy may read the difference between them as a school improving.
--
-- MEASURED, not assumed, over the fetched snapshot (`PROVENANCE.json` release
-- `06102026`, 6,273 institution rows): all four are shares in [0, 1] with zero
-- values outside it; the only silence token present is `NA` -- zero
-- `PrivacySuppressed`, zero blanks; OMAWDP8_ALL is present on 3,542 rows
-- against C150_4's 2,263; TRANS_4 is EXACTLY co-present with C150_4 (2,263
-- present, zero mixed cells); and the Pell arms are NOT co-present with the
-- headline -- 106 rows carry the all-student and non-Pell figures with no Pell
-- figure and 6 the reverse.
--
-- NO PAIRING CHECK, and that is the measurement above talking: a "both or
-- neither" CHECK between the Pell arms would reject 112 real rows. NO ORDERING
-- CHECK either -- the Pell figure is lower than the non-Pell figure at only
-- 65.8% of schools, so "Pell is lower" is not a fact about this data.
--
-- NO BACKFILL, NO INDEX and NO `college_search_index` COLUMN. The next ingest
-- fills them; existing `colleges_versions` rows stay NULL, which is correct --
-- those ingests never saw the fields. Nothing filters or sorts on these four
-- (0064's own rule: a column lives in the search index only if something
-- filters, sorts or indexes on it), and both search statements already join
-- `colleges` for their payload. The existing completion filter and sort keep
-- reading `completion_rate_150pct_4yr_share`; re-pointing them at a measure
-- that orders schools differently is a separate decision with its own
-- migration.
--
-- ORDER MATTERS INSIDE THIS TRANSACTION, and PART 3 IS NOT OPTIONAL.
-- `log_college_version()` names every `colleges` column literally on both
-- sides of its VALUES, and a plpgsql body is stored as TEXT and is not
-- dependency-tracked -- so ADD COLUMN neither rewrites it nor fails on it. A
-- stale body passes this migration and then kills the very next write to
-- `colleges` with `42703 record "new" has no field ...`, from inside the
-- trigger, in the middle of an ingest (`add-college-score-bands.sql:35-42`
-- records the same trap for the add direction, `0094:57-62` for the drop).

-- ---------------------------------------------------------------------------
-- Part 1 -- four columns on the live table, with their domains.
-- ---------------------------------------------------------------------------
ALTER TABLE colleges
    ADD COLUMN completion_rate_8yr_share          DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_pell_share     DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_non_pell_share DOUBLE PRECISION NULL,
    ADD COLUMN transfer_out_rate_4yr_share        DOUBLE PRECISION NULL,
    -- Shares, on `completion_rate_150pct_4yr_share`'s own terms (`0015:77`).
    -- A value outside [0, 1] is a loader fault, never data: the loader's own
    -- RATE_MIN/RATE_MAX guard coerces one to NULL first, and this is the
    -- deliberate second copy of that bound.
    ADD CONSTRAINT colleges_completion_rate_8yr_share_range_check
        CHECK (completion_rate_8yr_share IS NULL
               OR completion_rate_8yr_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_completion_rate_8yr_pell_share_range_check
        CHECK (completion_rate_8yr_pell_share IS NULL
               OR completion_rate_8yr_pell_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_completion_rate_8yr_non_pell_share_range_check
        CHECK (completion_rate_8yr_non_pell_share IS NULL
               OR completion_rate_8yr_non_pell_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_transfer_out_rate_4yr_share_range_check
        CHECK (transfer_out_rate_4yr_share IS NULL
               OR transfer_out_rate_4yr_share BETWEEN 0 AND 1);

-- The 0059 convention: the federal variable name and the definition live in the
-- COMMENT, never in the column name, and each names the cohort it describes.
COMMENT ON COLUMN colleges.completion_rate_8yr_share IS
    'OMAWDP8_ALL: the share (0-1) of ALL students who entered the institution in '
    'the AY 2016-17 cohort (IPEDS DCY2024-25) -- full-time and part-time, new to '
    'college and transferring in -- who had been awarded a degree or certificate '
    'within 8 years. A DIFFERENT measure over a DIFFERENT population and a '
    'DIFFERENT entering cohort from completion_rate_150pct_4yr_share, not a '
    'later or better version of it: the difference between the two moves both '
    'ways and is never a school improving.';
COMMENT ON COLUMN colleges.completion_rate_8yr_pell_share IS
    'OMAWDP8_PELL_ALL: completion_rate_8yr_share for the students in that AY '
    '2016-17 cohort who received a federal Pell Grant. Pell receipt is not '
    'income and is not inferable from a stored income band; this column is one '
    'group the publisher counted, never a prediction about a student. Absent '
    'INDEPENDENTLY of the other two columns -- 112 snapshot rows carry one arm '
    'without the other -- so there is no pairing CHECK.';
COMMENT ON COLUMN colleges.completion_rate_8yr_non_pell_share IS
    'OMAWDP8_NOPELL_ALL: completion_rate_8yr_share for the students in that AY '
    '2016-17 cohort who did not receive a federal Pell Grant. Not an ordered '
    'pair with completion_rate_8yr_pell_share: measured, the Pell figure is the '
    'lower of the two at only 65.8% of institutions, so neither the schema nor '
    'any copy above it may state a gap.';
COMMENT ON COLUMN colleges.transfer_out_rate_4yr_share IS
    'TRANS_4: the share (0-1) of the C150_4 cohort -- FIRST-TIME, FULL-TIME '
    'degree-seeking students at a FOUR-YEAR institution, Fall 2018 or AcadYr '
    '2018-19 by institutional calendar -- who transferred to another institution '
    'within 150% of normal time instead of completing there. Leaving is not the '
    'same as not finishing. Its denominator is the SIX-year cohort, not the '
    'eight-year one, so it is not subtractable from completion_rate_8yr_share. '
    'KNOWN GAP: inherited from C150_4 verbatim -- the loader reads TRANS_4 only, '
    'with no TRANS_L4 fallback, so every less-than-four-year institution is NULL '
    'here -- absent, not zero, and not comparable across the two sectors.';

-- ---------------------------------------------------------------------------
-- Part 2 -- the history mirror.
-- ---------------------------------------------------------------------------
--
-- NO constraints here, the 0045/0062/0094/score-bands pattern: the history
-- table records what was written, and a constraint on it would reject history
-- the live table accepted.
ALTER TABLE colleges_versions
    ADD COLUMN completion_rate_8yr_share          DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_pell_share     DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_non_pell_share DOUBLE PRECISION NULL,
    ADD COLUMN transfer_out_rate_4yr_share        DOUBLE PRECISION NULL;

-- ---------------------------------------------------------------------------
-- Part 3 -- restate the history writer.
-- ---------------------------------------------------------------------------
--
-- The `add-college-score-bands.sql` body verbatim plus the four names on BOTH
-- sides of the VALUES: 26 columns become 30 (counted; the score-bands comment
-- this one was copied from is itself off by one). CREATE OR REPLACE, never
-- DROP/CREATE -- the existing `trigger_04_log_college_version` binds by name
-- and must not be re-wired (the 0023/0045/0051/0057/0059/0062/0094/score-bands
-- pattern).
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
        completion_rate_150pct_4yr_share,
        completion_rate_8yr_share, completion_rate_8yr_pell_share,
        completion_rate_8yr_non_pell_share, transfer_out_rate_4yr_share,
        website,
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
        NEW.completion_rate_8yr_share, NEW.completion_rate_8yr_pell_share,
        NEW.completion_rate_8yr_non_pell_share, NEW.transfer_out_rate_4yr_share,
        NEW.website, NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
