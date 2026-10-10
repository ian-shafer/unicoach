-- national_merit_cycles -- the National Merit competition's OWN dated facts,
-- one row per competition year (RFC 243, slice test/08/the-psat-is-money,
-- brief 0010).
--
-- HAND-TYPED REFERENCE DATA (brief 0010 D19), ROUTE A: keyed by a competition
-- year and nothing else, so a migration can seed it -- and must, because
-- `bin/test` does db-reset and never ingests. "What this test is and when it
-- is" can never depend on an import having run. The corpus lives at
-- db/seed/national-merit/{cycles,link-status}.csv with its PROVENANCE.json and
-- is generated into a committed seed by bin/national-merit-seed.
--
-- THERE IS NO CUTOFF COLUMN, and that is the design (correction 4, D9). NMSC
-- allocates Semifinalist standing state-representationally, publishes no
-- qualifying score in advance, and says outright that the score varies by state
-- and by year. A column here would be filled with a third party's estimate and
-- spoken as hers. What we store instead is HOW the bar is set, plus NMSC's own
-- sentence saying it is not published.
--
-- EVERY ROW EXPIRES (D20), guarded 90 days out by the one shared
-- HandTypedReferenceData.REFRESH_LEAD, over LIVE cycles only -- a guard that
-- counts dead cycles can never be cleared.
--
-- Reference-table shape (0060/0077/RFC 197/RFC 208/RFC 239 precedent):
-- unversioned, no soft delete, no optimistic concurrency, created_at/updated_at
-- with the plain update_colleges_timestamp() trigger.

CREATE TABLE national_merit_cycles (
    -- NMSC's own name for the cycle, and the student's graduation year: the
    -- 2028 competition is the October 2026 sitting, taken by the class of 2028.
    -- That is exactly students.expected_high_school_graduation_year, so the
    -- join from a student is her own stored year and nothing inferred.
    --
    -- SMALLINT with its own CHECK and deliberately NOT the academic_year
    -- DOMAIN (0087): that domain means "the academic year beginning in", so
    -- borrowing it would make 2028 mean 2028-29 here and Fall 2028 next door.
    competition_year              SMALLINT    NOT NULL PRIMARY KEY,

    -- The only sitting that qualifies. Stored rather than hard-coded because it
    -- is a published rule of the programme and the coach must not speak it from
    -- memory. 11 in every row we hold.
    qualifying_grade              SMALLINT    NOT NULL,

    -- The in-school window. DATES ONLY WHERE NMSC PUBLISHES THEM, plus its own
    -- words always: a cycle two years out is described ("October of the junior
    -- year") long before it is dated, and the coach answering a sophomore needs
    -- the description without inventing a date.
    window_opens_on               DATE        NULL,
    window_closes_on              DATE        NULL,
    window_as_published           TEXT        NOT NULL,

    -- The alternate entry route (correction 1): a student who misses the window
    -- through illness or emergency writes to NMSC, and the letter has its own
    -- deadline. This is why "miss it and it is gone forever" is false, and the
    -- reason the coach can be honest about a missed October.
    alternate_entry_deadline      DATE        NULL,
    alternate_entry_contact       TEXT        NULL,
    alternate_entry_as_published  TEXT        NULL,

    -- What kind of number the score even is: the Selection Index scale.
    selection_index_low           SMALLINT    NOT NULL,
    selection_index_high          SMALLINT    NOT NULL,

    -- HOW the bar is set -- never what it is. See the header.
    semifinalist_cutoff_basis     TEXT        NOT NULL,
    commended_cutoff_basis        TEXT        NOT NULL,
    cutoff_as_published           TEXT        NOT NULL,

    -- Colleges never see a PSAT score. Quoted, because it is the one sentence
    -- that makes this a money event and not an admissions one.
    score_visibility_as_published TEXT        NOT NULL,

    -- The funnel (correction 4). Every stage NULL-or-positive, because NMSC
    -- publishes some stages and rounds others. THE BASE RATE IS NOT STORED: it
    -- is derived from two of these at read time and labelled as derived, which
    -- is the schema convention for anything computable from stored columns.
    test_takers                   INTEGER     NULL,
    met_entry_requirements        INTEGER     NULL,
    high_scorers_screened         INTEGER     NULL,
    commended                     INTEGER     NULL,
    semifinalists                 INTEGER     NULL,
    finalists                     INTEGER     NULL,
    winners                       INTEGER     NULL,
    funnel_as_published           TEXT        NOT NULL,

    -- NMSC's OWN money, which is small, and its cap on a sponsor college's
    -- award. Here so that "the big numbers are the university's, not NMSC's"
    -- (correction 3) is a stored fact rather than a sentence we remember.
    nmsc_award_usd                INTEGER     NULL,
    nmsc_award_as_published       TEXT        NULL,
    sponsor_cap_as_published      TEXT        NULL,

    -- TWO CITATIONS, because this row has two publishers and no page has all
    -- of it. NMSC sponsors the competition and publishes the entry rule, the
    -- alternate entry route, the funnel and the cutoff sentence. COLLEGE BOARD
    -- administers the test and is the only publisher of the window DATES (NMSC
    -- prints the month and never the days), the Selection Index scale, and the
    -- sentence saying a college never receives the score. Measured by the
    -- curation pass, not assumed: four cells per row are the administrator's.
    -- One citation column would attribute them to NMSC, which is exactly the
    -- mis-citation D26 and RFC 228 exist to prevent.
    publisher                     TEXT        NOT NULL,
    source_document_id            UUID        NOT NULL REFERENCES external_documents(id),
    archive_document_id           UUID        NULL     REFERENCES external_documents(id),
    administrator_publisher       TEXT        NOT NULL,
    administrator_document_id     UUID        NOT NULL REFERENCES external_documents(id),
    administrator_archive_document_id UUID    NULL     REFERENCES external_documents(id),
    checked_on                    DATE        NOT NULL,
    expires_on                    DATE        NOT NULL,

    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT national_merit_cycles_competition_year_check
        CHECK (competition_year BETWEEN 2020 AND 2100),

    CONSTRAINT national_merit_cycles_qualifying_grade_check
        CHECK (qualifying_grade BETWEEN 9 AND 12),

    -- One window date without the other is half a fact.
    CONSTRAINT national_merit_cycles_window_pair_check
        CHECK ((window_opens_on IS NULL) = (window_closes_on IS NULL)),

    CONSTRAINT national_merit_cycles_window_ordered_check
        CHECK (window_closes_on IS NULL OR window_opens_on <= window_closes_on),

    -- The qualifying sitting is in the autumn two calendar years before the
    -- competition year: October 2026 for the 2028 competition. A window typed
    -- into the wrong cycle is the error this refuses.
    CONSTRAINT national_merit_cycles_window_in_cycle_check
        CHECK (
            window_opens_on IS NULL
            OR window_opens_on BETWEEN make_date(competition_year - 2, 7, 1)
                                   AND make_date(competition_year - 1, 6, 30)
        ),

    -- A deadline or an address we cannot show the publisher's words for is a
    -- claim we made ourselves.
    CONSTRAINT national_merit_cycles_alternate_entry_trio_check
        CHECK (
            alternate_entry_as_published IS NOT NULL
            OR (alternate_entry_deadline IS NULL AND alternate_entry_contact IS NULL)
        ),

    CONSTRAINT national_merit_cycles_alternate_entry_in_cycle_check
        CHECK (
            alternate_entry_deadline IS NULL
            OR alternate_entry_deadline BETWEEN make_date(competition_year - 2, 10, 1)
                                            AND make_date(competition_year - 1, 12, 31)
        ),

    CONSTRAINT national_merit_cycles_selection_index_ordered_check
        CHECK (selection_index_low < selection_index_high),

    CONSTRAINT national_merit_cycles_selection_index_range_check
        CHECK (selection_index_low > 0 AND selection_index_high <= 500),

    -- Our own enumerations (TEXT + CHECK IN, the house rule), each with exactly
    -- one legal value today, because each has exactly one published basis.
    CONSTRAINT national_merit_cycles_semifinalist_cutoff_basis_check
        CHECK (semifinalist_cutoff_basis IN ('state_representational')),

    CONSTRAINT national_merit_cycles_commended_cutoff_basis_check
        CHECK (commended_cutoff_basis IN ('national')),

    CONSTRAINT national_merit_cycles_funnel_positive_check
        CHECK (
            COALESCE(test_takers, 1) > 0
            AND COALESCE(met_entry_requirements, 1) > 0
            AND COALESCE(high_scorers_screened, 1) > 0
            AND COALESCE(commended, 1) > 0
            AND COALESCE(semifinalists, 1) > 0
            AND COALESCE(finalists, 1) > 0
            AND COALESCE(winners, 1) > 0
        ),

    -- A funnel only narrows. Typing 15,000 Semifinalists and 16,000 Finalists
    -- inverts the base rate the coach is required to speak.
    CONSTRAINT national_merit_cycles_funnel_narrows_check
        CHECK (
            (met_entry_requirements IS NULL OR test_takers IS NULL
                OR met_entry_requirements <= test_takers)
            AND (high_scorers_screened IS NULL OR met_entry_requirements IS NULL
                OR high_scorers_screened <= met_entry_requirements)
            AND (commended IS NULL OR met_entry_requirements IS NULL
                OR commended <= met_entry_requirements)
            AND (semifinalists IS NULL OR met_entry_requirements IS NULL
                OR semifinalists <= met_entry_requirements)
            AND (finalists IS NULL OR semifinalists IS NULL
                OR finalists <= semifinalists)
            AND (winners IS NULL OR finalists IS NULL
                OR winners <= finalists)
        ),

    CONSTRAINT national_merit_cycles_nmsc_award_pair_check
        CHECK (
            nmsc_award_usd IS NULL
            OR (nmsc_award_usd > 0 AND nmsc_award_as_published IS NOT NULL)
        ),

    -- A quoted sentence that is blank is not a quotation.
    CONSTRAINT national_merit_cycles_words_not_blank_check
        CHECK (
            btrim(window_as_published) <> ''
            AND btrim(cutoff_as_published) <> ''
            AND btrim(score_visibility_as_published) <> ''
            AND btrim(funnel_as_published) <> ''
            AND btrim(publisher) <> ''
            AND btrim(COALESCE(alternate_entry_as_published, 'x')) <> ''
            AND btrim(COALESCE(alternate_entry_contact, 'x')) <> ''
            AND btrim(COALESCE(nmsc_award_as_published, 'x')) <> ''
            AND btrim(COALESCE(sponsor_cap_as_published, 'x')) <> ''
        ),

    CONSTRAINT national_merit_cycles_archive_differs_check
        CHECK (
            (archive_document_id IS NULL OR archive_document_id <> source_document_id)
            AND (
                administrator_archive_document_id IS NULL
                OR administrator_archive_document_id <> administrator_document_id
            )
        ),

    -- The sponsor and the administrator are different publishers. A row citing
    -- one page for both is a row that lost half its provenance.
    CONSTRAINT national_merit_cycles_two_publishers_check
        CHECK (
            administrator_document_id <> source_document_id
            AND btrim(administrator_publisher) <> ''
            AND administrator_publisher <> publisher
        ),

    -- The day we stop believing the page, never the day a deadline falls.
    CONSTRAINT national_merit_cycles_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

COMMENT ON TABLE national_merit_cycles IS
    'One National Merit competition year as NMSC publishes it (RFC 243): the '
    'in-school window, the alternate entry route and its deadline, the funnel '
    'the base rate is derived from, and NMSC''s own words saying the qualifying '
    'score is set per state and not published in advance. Keyed by the '
    'competition year, which is the class''s graduation year.';

CREATE TRIGGER trigger_03_enforce_national_merit_cycles_updated_at
BEFORE UPDATE ON national_merit_cycles
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO INDEX, deliberately, on the RFC 197 reading: the only read in the tree is
-- by primary key (the student's graduation year) or the whole table, over a
-- handful of rows.
