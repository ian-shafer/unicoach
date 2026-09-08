-- The published-price ruler for search (RFC 169, brief 0006 slice
-- shape/05/search-on-your-price).
--
-- `college_search_index` carries exactly one price column today,
-- `net_price_per_year_usd` (0064:138), copied verbatim off `colleges`. That
-- column is the Scorecard NPT4 blend: at a public school it is the IN-STATE,
-- after-federal-aid figure. Every price filter, every cheapest-first sort,
-- `similar_colleges`' price axis and `cheaper_than_anchor` therefore rank a
-- family from another state on a price it will never pay -- measured at
-- Kendall tau 0.561 against an out-of-state published ladder, i.e. ~22% of all
-- school pairs order the opposite way (brief 0005).
--
-- This migration gives search a SECOND ruler: the family's residency-correct
-- PUBLISHED on-campus total, materialised here from `price_figures` (RFC 158)
-- by the `search-index` rebuild, which now runs after `canonical-money`.
--
-- Two value columns, one per residency tier, because only the RATE TIER varies
-- per row -- never the measure. A private publishes one price, so its two
-- columns hold the same number. The value is all-or-nothing: tuition_and_fees
-- (tier) + housing_and_food (on campus) + books_and_supplies + other_expenses
-- (on campus), NULL if any part is missing. It is never a subtraction and
-- never a blend, so no aid ever enters it (RFC 149).
--
-- Two SHARE columns, both positions on ONE ladder: each school's OUT-OF-STATE
-- published on-campus total over the default search universe. The in-state
-- share is not a second corpus, it is the in-state value's place on the
-- out-of-state ladder -- which is what lets `similar_colleges` compare an
-- anchor and a candidate that sit on different tiers without mixing two
-- measures in one distance term.
--
-- Additive and nullable throughout: an index rebuilt with an empty
-- `price_figures` leaves all four NULL and the published ruler then drops,
-- counts and names every row rather than ranking wrongly. No new table, no new
-- column on `colleges`, so `log_college_version()` is untouched.
-- `net_price_per_year_usd` and `net_price_percentile_share` are NOT renamed and
-- NOT dropped -- the wire rename of the net axis is Kotlin-side, and the
-- column-shape end state belongs to the slice that proves reader-count zero.
ALTER TABLE college_search_index
    ADD COLUMN published_price_in_state_on_campus_per_year_usd     INTEGER      NULL,
    ADD COLUMN published_price_out_of_state_on_campus_per_year_usd INTEGER      NULL,
    ADD COLUMN published_price_in_state_on_campus_ladder_share     NUMERIC(5,4) NULL,
    ADD COLUMN published_price_out_of_state_on_campus_ladder_share NUMERIC(5,4) NULL;

COMMENT ON COLUMN college_search_index.published_price_in_state_on_campus_per_year_usd IS
    'Published on-campus total at the IN-STATE tuition tier: tuition_and_fees + '
    'housing_and_food(on campus) + books_and_supplies + other_expenses(on campus), '
    'summed from price_figures at the latest academic year that has all four. NULL '
    'if any component is missing -- four parts or no number. No aid is in it.';

COMMENT ON COLUMN college_search_index.published_price_out_of_state_on_campus_per_year_usd IS
    'The same total at the OUT-OF-STATE tuition tier, and the corpus of the '
    'published ladder. At a private the two columns hold the same number because '
    'a private publishes one price.';

COMMENT ON COLUMN college_search_index.published_price_in_state_on_campus_ladder_share IS
    'The in-state value''s position on the OUT-OF-STATE ladder over the default '
    'search universe -- not a second percentile over in-state values. One ruler.';

COMMENT ON COLUMN college_search_index.published_price_out_of_state_on_campus_ladder_share IS
    'The out-of-state value''s position on that same ladder; for a corpus member '
    'this equals percent_rank() over the corpus, which a test pins.';

-- The named 4-clause percentile CHECK (0064:192-200) becomes a 6-clause one. A
-- percentile column outside it is unpoliced, so the constraint is REPLACED, not
-- supplemented, and keeps its name.
ALTER TABLE college_search_index
    DROP CONSTRAINT college_search_index_percentile_range_check;

ALTER TABLE college_search_index
    ADD CONSTRAINT college_search_index_percentile_range_check CHECK (
        (undergrad_enrollment_percentile_share IS NULL
            OR undergrad_enrollment_percentile_share BETWEEN 0 AND 1)
        AND (admission_rate_percentile_share IS NULL
            OR admission_rate_percentile_share BETWEEN 0 AND 1)
        AND (sat_average_percentile_share IS NULL
            OR sat_average_percentile_share BETWEEN 0 AND 1)
        AND (net_price_percentile_share IS NULL
            OR net_price_percentile_share BETWEEN 0 AND 1)
        AND (published_price_in_state_on_campus_ladder_share IS NULL
            OR published_price_in_state_on_campus_ladder_share BETWEEN 0 AND 1)
        AND (published_price_out_of_state_on_campus_ladder_share IS NULL
            OR published_price_out_of_state_on_campus_ladder_share BETWEEN 0 AND 1));

-- NO index on either published value column, and that is the measured answer
-- rather than a saving of effort. Nothing can use one: every published-price
-- bound, the price `ORDER BY` and `cheaper_than_anchor` all read a CASE over the
-- TWO tier columns, which no single-column btree serves, and the ladder is
-- computed from a CTE over the whole default universe rather than probed. An
-- index here would be write cost on every rebuild of a ~3.3k-row table, paid by
-- no reader (RFC 169, tier-1 review).
