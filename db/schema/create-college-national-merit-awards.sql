-- college_national_merit_awards -- what a NAMED school pays a National Merit
-- Finalist (RFC 243, slice test/08/the-psat-is-money, brief 0010).
--
-- A SECOND TABLE, not a widening of college_automatic_awards (RFC 239), and the
-- reason is the qualification itself. That table models a price against a SCORE:
-- a test, a score range on the published scale, a GPA range, and a Kotlin
-- BandScoreAxis with exactly two arms. A National Merit Finalist is none of
-- those -- it is a STANDING that NMSC awards once, after a state-allocated
-- screen we do not hold and will never hold. Three more facts, any one of which
-- would be enough on its own:
--   1. The natural key collides. RFC 239's is (college_id, entry_year,
--      residency_basis) and Alabama already owns that row with its score grid.
--   2. Every reader of that table is an exhaustive when over score-shaped
--      answers. A third arm makes "a score moves the money here" start being
--      true of rows where no score is involved.
--   3. The columns do not carry over in either direction: first choice and the
--      standing are National Merit's shape, and superscore_rule and
--      score_deadline are meaningless for a standing awarded once.
-- What IS shared is every bit of discipline: the ingest-time loader and its
-- named refusals, the link-status sidecar, external_documents, checked_on /
-- expires_on, HandTypedReferenceData.REFRESH_LEAD, the as_published column
-- beside every parsed value, and the verified-absence kind.
--
-- HAND-TYPED REFERENCE DATA (brief 0010 D19), ROUTE B (RFC 239 D-C): keyed by
-- colleges(id), which the IMPORT fills, so a migration-time seed would point at
-- an empty table -- D27's measured failure. The corpus lives at
-- db/seed/national-merit-awards/{awards,link-status}.csv with its
-- PROVENANCE.json and is loaded by bin/ingest-colleges behind
-- bin/load-external-data.
--
-- EVERY ROW EXPIRES on July 31 of its entry year (D20), the same rule and the
-- same shared REFRESH_LEAD as the score grids. Auburn is why: it cut a $44,000
-- award to Alabama residents only, and a row with no read date would still be
-- telling a family in Oregon it was theirs.

CREATE TABLE college_national_merit_awards (
    id                          UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The school. REFERENCES colleges(id), never an IPEDS code of our own
    -- (D26), and the reason this corpus loads with the ingest.
    college_id                  UUID          NOT NULL REFERENCES colleges(id),

    -- The ENTRY year, named by its first calendar year: 2027 is Fall 2027
    -- entry. A school's National Merit page is re-keyed per cycle exactly as
    -- its score grid is. The domain owns the range (0087).
    entry_year                  academic_year NOT NULL,

    -- WHOSE award this is. Auburn is why it is in the key: $11,000/yr, Alabama
    -- residents only. Keyed into residency_bases (RFC 158 D4) rather than a
    -- private copy, and narrowed below to the three a university publishes a
    -- freshman award against.
    residency_basis             TEXT          NOT NULL REFERENCES residency_bases(slug),

    -- OUR OWN enumeration (TEXT + CHECK IN, one Kotlin enum with fromValue).
    -- 'automatic'      -- the page guarantees the award to anyone holding the
    --                     standing, with no committee and no separate contest.
    -- 'competitive'    -- the standing is CONSIDERED, not paid (UCF's ">$25,000
    --                     a year" is "considered for"; Tulsa adds a 4.0, a
    --                     visit and an interview). Calling that automatic is
    --                     the costly error this column exists to prevent.
    -- 'none_published' -- we READ this school's page on checked_on and it
    --                     publishes no National Merit award at all. A verified
    --                     absence WITH a citation, which is a different answer
    --                     from "we have not looked" -- the argument
    --                     fafsa_filing_deadlines makes for deadline_kind='none'
    --                     and RFC 239 makes for 'none_published'.
    award_kind                  TEXT          NOT NULL,

    -- WHICH standing the school pays. This is the column that made this a new
    -- table rather than a widening: a Finalist is not a score. Maine is why it
    -- is in the key -- it publishes a Semifinalist row AND a Finalist row.
    qualifying_standing         TEXT          NULL,

    award_name                  TEXT          NULL,

    -- The money, as the PUBLISHER expresses it. Oklahoma publishes a four-year
    -- total of $153,450; Alabama publishes a package of tuition, housing and
    -- cash; Texas Tech publishes "100% of cost of attendance" and no number at
    -- all. So the WORDS are required for every paying row and the numbers are
    -- optional -- never the other way round. RFC 239's review found the
    -- opposite arrangement ranking a top award priced in words as $0, and no
    -- test caught it. Nothing downstream may sort or rank on these columns.
    award_per_year_usd          INTEGER       NULL,
    award_total_usd             INTEGER       NULL,
    award_years                 SMALLINT      NULL,
    award_as_published          TEXT          NULL,

    -- The sentence that proves the absence, for a 'none_published' row only.
    -- Separate from award_as_published so that neither kind can borrow the
    -- other's words: a paying row with an absence quote, or an absence row
    -- carrying award words, is refused rather than rendered.
    absence_as_published        TEXT          NULL,

    -- NMSC's first-choice mechanism, per school, because it is the difference
    -- between an award and a commitment: naming a college first choice with
    -- NMSC is an act with consequences elsewhere. Maine requires nothing;
    -- Alabama wants it by May 1. NULL means the page does not say, which is
    -- neither yes nor no.
    first_choice_required       BOOLEAN       NULL,
    first_choice_deadline       DATE          NULL,
    first_choice_as_published   TEXT          NULL,

    -- The school's own admission or scholarship deadline for the package, and
    -- its own sentence for it.
    apply_deadline              DATE          NULL,
    apply_deadline_as_published TEXT          NULL,

    -- The citation. No url column (RFC 228; the build fails for one): the page
    -- and its optional mirror are foreign keys into external_documents, where
    -- every row carries its status and the day we asked.
    publisher                   TEXT          NOT NULL,
    source_document_id          UUID          NOT NULL REFERENCES external_documents(id),
    archive_document_id         UUID          NULL     REFERENCES external_documents(id),
    checked_on                  DATE          NOT NULL,
    expires_on                  DATE          NOT NULL,

    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- The natural key. NULLS NOT DISTINCT so that the one 'none_published' row
    -- per school, entry year and residency -- whose standing is NULL -- cannot
    -- be typed twice. It also serves every read: the tool looks up by
    -- college_id, and this index leads with it.
    CONSTRAINT college_national_merit_awards_natural_key
        UNIQUE NULLS NOT DISTINCT (college_id, entry_year, residency_basis, qualifying_standing),

    CONSTRAINT college_national_merit_awards_kind_check
        CHECK (award_kind IN ('automatic', 'competitive', 'none_published')),

    CONSTRAINT college_national_merit_awards_standing_check
        CHECK (
            qualifying_standing IS NULL
            OR qualifying_standing IN ('finalist', 'semifinalist', 'commended')
        ),

    -- A standing on a row that says the school publishes nothing, or a paying
    -- row that does not say WHICH standing it pays, are both incoherent.
    CONSTRAINT college_national_merit_awards_standing_iff_award_check
        CHECK ((award_kind = 'none_published') = (qualifying_standing IS NULL)),

    -- in_district and unknown are legal in the vocabulary and refused here, on
    -- RFC 239's argument: a community-college tier and an IPEDS headcount
    -- category are not things a university publishes a freshman award against.
    CONSTRAINT college_national_merit_awards_residency_basis_check
        CHECK (residency_basis IN ('in_state', 'out_of_state', 'not_applicable')),

    -- Every claim carries the page's own sentence, and the two kinds of
    -- sentence never cross.
    CONSTRAINT college_national_merit_awards_claim_has_words_check
        CHECK (
            award_kind = 'none_published'
            OR (award_as_published IS NOT NULL AND absence_as_published IS NULL)
        ),

    CONSTRAINT college_national_merit_awards_absence_has_words_check
        CHECK (
            award_kind <> 'none_published'
            OR (absence_as_published IS NOT NULL AND award_as_published IS NULL)
        ),

    -- A verified absence states nothing else. No name, no money, no deadline,
    -- no first-choice rule -- there is no award for any of them to describe.
    CONSTRAINT college_national_merit_awards_none_published_is_empty_check
        CHECK (
            award_kind <> 'none_published'
            OR (
                award_name IS NULL
                AND award_per_year_usd IS NULL
                AND award_total_usd IS NULL
                AND award_years IS NULL
                AND first_choice_required IS NULL
                AND first_choice_deadline IS NULL
                AND first_choice_as_published IS NULL
                AND apply_deadline IS NULL
                AND apply_deadline_as_published IS NULL
            )
        ),

    CONSTRAINT college_national_merit_awards_money_positive_check
        CHECK (
            COALESCE(award_per_year_usd, 1) > 0
            AND COALESCE(award_total_usd, 1) > 0
            AND COALESCE(award_years, 1) BETWEEN 1 AND 6
        ),

    -- A requirement with no words is our claim, not theirs; a first-choice
    -- DEADLINE on a school that does not require first choice is a date with
    -- nothing to be the deadline of.
    CONSTRAINT college_national_merit_awards_first_choice_trio_check
        CHECK (
            (first_choice_required IS NULL) = (first_choice_as_published IS NULL)
            AND (first_choice_deadline IS NULL OR first_choice_required IS TRUE)
        ),

    CONSTRAINT college_national_merit_awards_apply_deadline_pair_check
        CHECK (apply_deadline IS NULL OR apply_deadline_as_published IS NOT NULL),

    -- Both deadlines belong to the application cycle that ends in the entry
    -- year: July 1 the summer before, through September 30 of entry. RFC 239's
    -- deadline_in_cycle, unchanged -- it catches a date typed into the wrong
    -- cycle, which is the mistake a curator actually makes.
    CONSTRAINT college_national_merit_awards_deadlines_in_cycle_check
        CHECK (
            (
                apply_deadline IS NULL
                OR apply_deadline BETWEEN make_date(entry_year - 1, 7, 1)
                                      AND make_date(entry_year, 9, 30)
            )
            AND (
                first_choice_deadline IS NULL
                OR first_choice_deadline BETWEEN make_date(entry_year - 1, 7, 1)
                                             AND make_date(entry_year, 9, 30)
            )
        ),

    CONSTRAINT college_national_merit_awards_words_not_blank_check
        CHECK (
            btrim(publisher) <> ''
            AND btrim(COALESCE(award_as_published, 'x')) <> ''
            AND btrim(COALESCE(absence_as_published, 'x')) <> ''
            AND btrim(COALESCE(award_name, 'x')) <> ''
            AND btrim(COALESCE(first_choice_as_published, 'x')) <> ''
            AND btrim(COALESCE(apply_deadline_as_published, 'x')) <> ''
        ),

    CONSTRAINT college_national_merit_awards_archive_differs_check
        CHECK (archive_document_id IS NULL OR archive_document_id <> source_document_id),

    -- The day we stop believing the page, never a deadline the row states.
    CONSTRAINT college_national_merit_awards_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

COMMENT ON TABLE college_national_merit_awards IS
    'What a named school pays a National Merit Finalist, Semifinalist or '
    'Commended student (RFC 243): the school''s own words first, its numbers '
    'only where it publishes them, whether the award is automatic or merely '
    'competitive, its first-choice requirement, and the day we read the page. '
    'A standing is not a score, which is why this is not a row in '
    'college_automatic_awards.';

CREATE TRIGGER trigger_03_enforce_college_national_merit_awards_updated_at
BEFORE UPDATE ON college_national_merit_awards
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO SECOND INDEX. The only read is by college_id for up to fifty schools at
-- once, and the natural-key constraint above already leads with that column.
