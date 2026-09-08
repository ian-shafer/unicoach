-- Borrowing at graduation (RFC 175, brief 0006 slice shape/07b): the money
-- vocabulary the CDS H4/H5 borrowing cells land in. No new table, no new
-- column, no new axis -- four CHECK lists re-added, and nothing else.
--
-- Loan type is part of the MEASURE (D1): the grant-mix rule from 0085, applied
-- to borrowing. 'debt' here is the CDS's cumulative principal borrowed by the
-- time a student graduated -- not an annual amount (that is
-- student_loan_average_amount, IPEDS) and not the Scorecard's federal-only
-- median (median_debt_at_completion, a different cohort and a different stat).
-- A sixth axis would force every price and every existing statistic to answer
-- a loan-type question it is not about.
--
-- DROP + re-ADD, never "ALTER ... ADD another CHECK": the enum mirrors are
-- pinned to these lists by set equality BOTH ways, so the list must stay one
-- list (the 0085 rule).
--
-- cohort_money_stats_share_range_check is DELIBERATELY UNCHANGED. None of the
-- five new measures is a share: the share of a graduating class that borrowed
-- is DERIVED at read time from the borrower count over the graduating class
-- (D2), because the source's own percent cells are corpus-typed text carrying
-- 0..100 for some schools and 0..1 for others. It is not an omission, and
-- adding a borrowing measure to the range list would be the error.
--
-- WHICH year a row carries is DATA (`vintage`), never a comment: the 0062 rule
-- that a landed migration must not restate a fact the snapshot moves.

-- ---------------------------------------------------------------------------
-- cohort_money_stats -- five measures, three aid scopes, six populations.
-- ---------------------------------------------------------------------------
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_measure_check,
    ADD CONSTRAINT cohort_money_stats_measure_check
        CHECK (measure IN ('avg_net_price', 'published_cost_blend',
                           'pell_share', 'median_debt_at_completion',
                           'median_earnings_10y',
                           'pell_average_award',
                           'federal_grant_share', 'federal_grant_average_award',
                           'state_local_grant_share',
                           'state_local_grant_average_award',
                           'institutional_grant_share',
                           'institutional_grant_average_award',
                           'student_loan_share', 'student_loan_average_amount',
                           'avg_need_based_grant', 'avg_need_met_share',
                           'any_loan_debt_average', 'federal_loan_debt_average',
                           'institutional_loan_debt_average',
                           'state_loan_debt_average',
                           'private_loan_debt_average')),
    -- The aid scope follows the DENOMINATOR (the 0085 rule). Every new measure
    -- is an average over the borrowers OF ITS OWN LOAN TYPE, so it carries
    -- that type's receiving scope. 'loan_receiving' (borrowed a loan of ANY
    -- kind) and 'federal_loan_borrowing' already name two of the five sets
    -- exactly, and are REUSED rather than duplicated (D4): an address is
    -- (measure, population, aid_scope), so the Scorecard's completer debt and
    -- the CDS federal average share a denominator without colliding. Minting a
    -- cds_-prefixed twin would make the store say the publisher, which the
    -- source columns already do.
    DROP CONSTRAINT cohort_money_stats_aid_scope_check,
    ADD CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving',
                             'federal_loan_borrowing', 'all',
                             'grant_aided', 'pell_receiving',
                             'federal_grant_receiving',
                             'state_local_grant_receiving',
                             'institutional_grant_receiving',
                             'loan_receiving',
                             'need_based_aid_receiving',
                             'institutional_loan_borrowing',
                             'state_loan_borrowing',
                             'private_loan_borrowing')),
    -- Six populations, and the SAME six land on cohort_population_counts
    -- below: the two lists are one list, and 0087 already re-added them
    -- together for that reason. The graduating class is the cohort every H4/H5
    -- figure is reported over; the five borrower cohorts carry the loan type
    -- in the slug because cohort_population_counts' natural key has no measure
    -- column, so five borrower counts for one school-year would otherwise
    -- collide (D1).
    DROP CONSTRAINT cohort_money_stats_population_check,
    ADD CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates',
                              'first_time_full_time_freshmen_awarded_any_aid',
                              'first_time_full_time_freshmen_awarded_need_based_grant',
                              'first_time_full_time_freshmen_need_fully_met',
                              'graduating_class',
                              'graduating_class_borrowers_any_loan',
                              'graduating_class_borrowers_federal_loan',
                              'graduating_class_borrowers_institutional_loan',
                              'graduating_class_borrowers_state_loan',
                              'graduating_class_borrowers_private_loan'));

ALTER TABLE cohort_population_counts
    DROP CONSTRAINT cohort_population_counts_population_check,
    ADD CONSTRAINT cohort_population_counts_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates',
                              'first_time_full_time_freshmen_awarded_any_aid',
                              'first_time_full_time_freshmen_awarded_need_based_grant',
                              'first_time_full_time_freshmen_need_fully_met',
                              'graduating_class',
                              'graduating_class_borrowers_any_loan',
                              'graduating_class_borrowers_federal_loan',
                              'graduating_class_borrowers_institutional_loan',
                              'graduating_class_borrowers_state_loan',
                              'graduating_class_borrowers_private_loan'));
