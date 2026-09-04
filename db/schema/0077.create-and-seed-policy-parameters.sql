-- Federal aid policy parameters: the Pell and Direct Loan figures the coach
-- may state (RFC 159, slice shape/06/pell-and-loans).
--
-- A narrow reference table: one row per (award_year, parameter), one INTEGER
-- value. The house measure_qualifier_unit naming rule lives in the PARAMETER
-- NAME, not in a typed column per concept -- a statutory change is a new row,
-- never a new column. The vocabulary is a closed CHECK mirrored by exactly one
-- Kotlin enum (PolicyParameter, db/models), the IncomeBand precedent.
--
-- Reference-table shape (0060 precedent): unversioned, no soft delete, no OCC,
-- created_at/updated_at with the plain update_colleges_timestamp() trigger.
-- Award year is the STARTING calendar year (2026 = "2026-27"); rendering goes
-- through AcademicYear in :service.
--
-- Every row carries its citation (source_name is the SPOKEN document name, the
-- CdsCitation cited_as precedent; source_url is the primary FSA page), so the
-- coach can attribute each figure to the document it was verified against.
--
-- The seed is AUTHORED IN THIS MIGRATION (prompt-seed precedent, 0044-0076):
-- every figure was verified live against fsapartners.ed.gov on 2026-09-03
-- (quotes archived in the RFC and its verified-facts record). A wrong or
-- superseded figure is corrected by a NEW migration upserting on the natural
-- key, never by editing this one.
--
-- Two award years, deliberately asymmetric:
--   * award_year 2026 (AY 2026-27, current): the five Pell/SAI parameters
--     (DCL GEN-26-01; 2026-27 FSA Handbook AVG Ch 3). The 2026-27 Direct Loan
--     limits are DELIBERATELY ABSENT: Volume 8 of the 2026-27 Handbook is not
--     yet published, and 2025 reconciliation legislation (OBBBA) changes
--     graduate/PLUS lending from July 1, 2026 -- seeding last year's numbers
--     under this year's key would be a fabricated fact.
--   * award_year 2025 (AY 2025-26, prior): the same five (DCL GEN-25-02;
--     2025-26 AVG Ch 3) plus all nineteen Direct Loan limits from the
--     2025-26 FSA Handbook, Volume 8 Chapter 4, Tables 1A/1B/1C/4.
--
-- sai_floor is a dimensionless index (no _usd/_pct suffix); see COMMENT below.

CREATE TABLE policy_parameters (
    award_year INTEGER NOT NULL
        CONSTRAINT policy_parameters_award_year_check
        CHECK (award_year BETWEEN 2024 AND 2100),
    parameter TEXT NOT NULL
        CONSTRAINT policy_parameters_parameter_check
        CHECK (parameter IN (
            'pell_max_award_usd',
            'pell_min_award_usd',
            'pell_max_agi_dependent_single_parent_pct',
            'pell_max_agi_dependent_non_single_parent_pct',
            'sai_floor',
            'direct_loan_annual_dependent_y1_total_usd',
            'direct_loan_annual_dependent_y1_subsidized_usd',
            'direct_loan_annual_dependent_y2_total_usd',
            'direct_loan_annual_dependent_y2_subsidized_usd',
            'direct_loan_annual_dependent_y3_plus_total_usd',
            'direct_loan_annual_dependent_y3_plus_subsidized_usd',
            'direct_loan_annual_independent_y1_total_usd',
            'direct_loan_annual_independent_y1_subsidized_usd',
            'direct_loan_annual_independent_y2_total_usd',
            'direct_loan_annual_independent_y2_subsidized_usd',
            'direct_loan_annual_independent_y3_plus_total_usd',
            'direct_loan_annual_independent_y3_plus_subsidized_usd',
            'direct_loan_annual_grad_unsubsidized_usd',
            'direct_loan_aggregate_dependent_total_usd',
            'direct_loan_aggregate_dependent_subsidized_usd',
            'direct_loan_aggregate_independent_total_usd',
            'direct_loan_aggregate_independent_subsidized_usd',
            'direct_loan_aggregate_grad_total_usd',
            'direct_loan_aggregate_grad_subsidized_usd'
        )),
    value INTEGER NOT NULL
        -- Only the SAI floor is legitimately negative; a mis-authored
        -- correction migration must not seed a negative dollar or percent.
        CONSTRAINT policy_parameters_value_check
        CHECK (parameter = 'sai_floor' OR value >= 0),
    source_name TEXT NOT NULL
        CONSTRAINT policy_parameters_source_name_check
        CHECK (source_name <> ''),
    source_url TEXT NOT NULL
        CONSTRAINT policy_parameters_source_url_check
        CHECK (source_url <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (award_year, parameter)
);

COMMENT ON COLUMN policy_parameters.value IS
    'Whole dollars for _usd parameters, percent points for _pct parameters, '
    'and a dimensionless index for sai_floor (the statutory SAI floor, which '
    'may be negative; the figure is the seeded sai_floor row, never this '
    'comment).';

CREATE TRIGGER trigger_01_enforce_policy_parameters_updated_at
BEFORE UPDATE ON policy_parameters
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

INSERT INTO policy_parameters (award_year, parameter, value, source_name, source_url)
VALUES
    (2026, 'pell_max_award_usd', 7395,
     'Federal Student Aid Dear Colleague Letter GEN-26-01',
     'https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2026-01-30/2026-27-federal-pell-grant-maximum-and-minimum-award-amounts'),
    (2026, 'pell_min_award_usd', 740,
     'Federal Student Aid Dear Colleague Letter GEN-26-01',
     'https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2026-01-30/2026-27-federal-pell-grant-maximum-and-minimum-award-amounts'),
    (2026, 'pell_max_agi_dependent_single_parent_pct', 225,
     'Federal Student Aid Handbook, 2026-2027, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2026-2027/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2026, 'pell_max_agi_dependent_non_single_parent_pct', 175,
     'Federal Student Aid Handbook, 2026-2027, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2026-2027/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2026, 'sai_floor', -1500,
     'Federal Student Aid Handbook, 2026-2027, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2026-2027/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2025, 'pell_max_award_usd', 7395,
     'Federal Student Aid Dear Colleague Letter GEN-25-02',
     'https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2025-01-31/2025-2026-federal-pell-grant-maximum-and-minimum-award-amounts-updated-may-29-2025'),
    (2025, 'pell_min_award_usd', 740,
     'Federal Student Aid Dear Colleague Letter GEN-25-02',
     'https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2025-01-31/2025-2026-federal-pell-grant-maximum-and-minimum-award-amounts-updated-may-29-2025'),
    (2025, 'pell_max_agi_dependent_single_parent_pct', 225,
     'Federal Student Aid Handbook, 2025-2026, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2025, 'pell_max_agi_dependent_non_single_parent_pct', 175,
     'Federal Student Aid Handbook, 2025-2026, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2025, 'sai_floor', -1500,
     'Federal Student Aid Handbook, 2025-2026, Application and Verification Guide Chapter 3',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility'),
    (2025, 'direct_loan_annual_dependent_y1_total_usd', 5500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_dependent_y1_subsidized_usd', 3500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_dependent_y2_total_usd', 6500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_dependent_y2_subsidized_usd', 4500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_dependent_y3_plus_total_usd', 7500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_dependent_y3_plus_subsidized_usd', 5500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y1_total_usd', 9500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y1_subsidized_usd', 3500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y2_total_usd', 10500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y2_subsidized_usd', 4500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y3_plus_total_usd', 12500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_independent_y3_plus_subsidized_usd', 5500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_annual_grad_unsubsidized_usd', 20500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_dependent_total_usd', 31000,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_dependent_subsidized_usd', 23000,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_independent_total_usd', 57500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_independent_subsidized_usd', 23000,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_grad_total_usd', 138500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits'),
    (2025, 'direct_loan_aggregate_grad_subsidized_usd', 65500,
     'Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4',
     'https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits');
