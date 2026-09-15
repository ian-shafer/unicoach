-- fafsa_filing_deadlines -- the FAFSA dates a family actually has to hit
-- (brief 0012 D4, slice fafsa/01/the-deadline-table, RFC 215).
--
-- One row per (award year, jurisdiction, program, event): when the form opens,
-- and the deadlines the federal government and each state publish for the aid
-- that FAFSA unlocks. This is the table the public page's state-by-state table
-- renders AND the table the coach speaks from -- one store, because a page and
-- a coach that hold separate copies of a date will eventually disagree about
-- one, and the family only sees one of them (D4).
--
-- HAND-MAINTAINED REFERENCE DATA (brief 0010 D19, the RFC 197 precedent).
-- There is no machine-readable feed for state FAFSA deadlines: each state
-- agency publishes HTML. So every row will be typed by a human from the
-- agency's own page, committed as db/seed/fafsa-deadlines/deadlines.csv so that
-- a refresh is reviewable in a diff, and GENERATED into a per-cycle seed
-- migration from that CSV. NONE OF THAT EXISTS YET: slice fafsa/02 writes the
-- CSV, the generator and the seed. This slice ships the table and no rows, so
-- every sentence here about the seed describes fafsa/02's work and not a
-- mechanism a reader can go and find today. Once it lands: never hand-edit a
-- generated seed; edit the CSV and re-run the generator, and a corrected row
-- lands as a NEW generated seed upserting on the natural key rather than as an
-- edit to a landed one.
--
-- THE AWARD YEAR IS THE ROW'S IDENTITY, NOT ITS DECORATION. State deadlines
-- RECUR ANNUALLY, so last cycle's row is not merely old -- it is a different
-- date that LOOKS like this cycle's. A passed deadline inside the current
-- cycle is still a fact worth showing ("Texas closed on 15 January"); a
-- 2026-27 row shown in the 2027-28 cycle is a lie a family can act on. So
-- every read filters on award_year first and expiry second -- which is why
-- FafsaFilingDeadlineDao offers listByAwardYear and no listAll -- and, once
-- fafsa/02 commits rows, its seed guard will fail the build when the committed
-- file has no rows for the cycle now being filed (D11).
--
-- THE CYCLE NOW BEING FILED IS NOT THE AWARD YEAR NOW BEING PAID, and it is
-- not "the only open cycle" either. FafsaFilingCycle's KDoc is the one place
-- that argues both, so this comment names the clock and stops: it is a
-- separate rule from FederalAidPolicyService.currentAwardYear()'s July 1
-- boundary, and it lives in :db because the seed guard that will enforce D11
-- is a :db test and cannot see :service.
--
-- EVERY ROW EXPIRES (D7), AND NEITHER HALF OF THAT IS ENFORCED YET, BY DESIGN.
-- This slice ships the column and no rows. Once fafsa/02 commits rows, its seed
-- test fails the build when a committed row of the cycle now being filed is
-- within HandTypedReferenceData.REFRESH_LEAD of its expires_on -- that constant
-- is the one home for the number, and this comment names it rather than
-- repeating it. Past expires_on it is fafsa/03's FafsaDeadlineService that
-- REFUSES to name a date and points at studentaid.gov and the state agency,
-- exactly as TestTimelineService refuses today. Nothing in this file and
-- nothing in the DAO decides expiry.
--
-- Reference-table shape (0060/0077/RFC 197/RFC 208 precedent): unversioned, no
-- soft delete, no optimistic concurrency, created_at/updated_at with the plain
-- update_colleges_timestamp() trigger.
--
-- `award_year` is the academic_year DOMAIN (0087 D14): the FIRST calendar year,
-- so 2027 IS the 2027-28 FAFSA. The "2027-28" label is rendered at read time by
-- AcademicYear and never stored -- unlike admission_test_administrations.cycle,
-- which stores a label because College Board labels its calendar with one.
--
-- NO FigureStatus AND NO ASSURANCE TIER, for the reason RFC 197 gives: that
-- vocabulary belongs to the canonical money tables, whose rows record a
-- publisher's suppression and imputation flags (RFC 179). A deadline is not a
-- money figure -- it is published, in which case it is here with the page it was
-- read from, or it is not, in which case the row says so in the publisher's own
-- words.
--
-- NO FOREIGN KEY TO us_states, DELIBERATELY, AND NOT FOR EVER. us_states is
-- filled by the codebook phase of bin/ingest-colleges (RFC 147), not by any
-- migration, so a freshly migrated database -- the test database that bin/test
-- recreates on every run included -- has none of its rows, and a seed migration
-- referencing it could not apply. This is the identical argument
-- create-admission-test-state-programmes.sql makes at its own `state` column,
-- and it has the identical fix: brief 0010's test/05.3/own-our-codebooks seeds
-- the codebooks at migration time and adds the key. That slice has not landed,
-- so this table takes the same shape its sibling took. Membership in the 59
-- published USPS codes is enforced where UsStateCodes.kt says it belongs: by
-- the fafsa/02 generator over the authored CSV and by the seed test. The regex
-- below is the coarser DB-level backstop. The region grouping the public page
-- needs is still us_states.ipeds_region -> ipeds_regions.name, joined at READ
-- time in a database that has been ingested.

-- THE CYCLE'S OPENING BOUND, NAMED ONCE. Two CHECKs below ask the same
-- question -- "could this cycle have been published yet?" -- and the arithmetic
-- was typed twice, so tightening one would silently not carry to the other.
-- IMMUTABLE and STRICT, which is what a CHECK constraint requires; it takes
-- the academic_year domain's SMALLINT and casts inside, because make_date
-- takes int.
--
-- July 1 of the year before the award year: the FAFSA for 2027-28 opens in
-- October 2026, so nothing about that cycle -- no deadline, no reading of a
-- page -- belongs before that summer.
CREATE FUNCTION fafsa_cycle_opens_on(award_year academic_year) RETURNS DATE
    LANGUAGE SQL IMMUTABLE STRICT
    RETURN make_date(award_year::INT - 1, 7, 1);

COMMENT ON FUNCTION fafsa_cycle_opens_on(academic_year) IS
    'The earliest date any fact about a FAFSA cycle may carry: 1 July of the '
    'year before the award year, the summer before the form opens. One home '
    'for a bound two CHECK constraints on fafsa_filing_deadlines ask for.';

CREATE TABLE fafsa_filing_deadlines (
    id                  UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    award_year          academic_year NOT NULL,

    -- The jurisdiction. NULL means FEDERAL: the federal open date and the
    -- federal deadline belong in this table because the page and the coach read
    -- the two together, and a `scope` column beside this one would be a stored
    -- derivation of `state IS NULL` (CLAUDE.md: derived figures are computed at
    -- read time, never stored). Kotlin models the pair as one sealed value, so
    -- "federal" is not a null anybody has to remember.
    state               TEXT          NULL,

    -- The program as ITS OWN publisher names it -- 'Cal Grant', 'TEXAS Grant',
    -- 'Federal student aid'. A source-defined name, stored raw (CLAUDE.md), so
    -- it is neither a slug nor an enum. It is in the natural key because a
    -- state with three grant programs has three deadlines, and collapsing them
    -- to one would pick a winner no source picked.
    program             TEXT          NOT NULL,

    -- OUR OWN enumeration, so TEXT + CHECK IN (...) with exactly one Kotlin
    -- enum class AidDeadlineEvent(val value: String) carrying a fromValue
    -- companion (CLAUDE.md, the IncomeBand/AidForm precedent).
    --
    -- AND IT STAYS A PER-TABLE CHECK UNTIL A SECOND TABLE ASKS THE SAME
    -- QUESTION, which is the rule RFC 213 applied to the link-rot vocabulary:
    -- "A DOMAIN, not a per-table CHECK. Two tables now ask this question."
    -- One column asks this one. The day a second table needs the same event
    -- vocabulary, this CHECK becomes CREATE DOMAIN aid_deadline_event and both
    -- columns take it -- not a second copy of the list. Written down here so
    -- the promotion has a stated trigger rather than a reader's guess.
    --
    -- 'corrections_deadline' is the federal cycle's fourth date and the reason
    -- the list is four long: the last day to fix an error on a form already
    -- filed (11 September 2027 for the 2026-27 cycle, per the Federal
    -- Register). It is a date families act on, and studentaid.gov prints 12
    -- September for the same deadline -- a one-day disagreement this table
    -- STORES with its source, one row per publisher, rather than resolving on
    -- their behalf.
    event               TEXT          NOT NULL,

    -- OUR OWN enumeration too, and the reason this table exists in this shape.
    -- 'date': the publisher names a day, and it is in deadline_date.
    -- 'wording': the publisher names a RULE and not a day -- "as soon as
    --            possible after the form opens", "until funds are exhausted".
    --            It is carried as those words and is NEVER flattened into a
    --            fake date; a made-up 1 March under a "check with your state"
    --            rule is precisely the invented fact this table exists to
    --            prevent (D4).
    -- 'none':   we READ the state's page on checked_on and it publishes no
    --           deadline for this program at all. A verified absence, with a
    --           citation -- not our silence. It is a row and not a missing row
    --           because the public page prints every state, so an empty cell
    --           would be unreadable: "this state has none" and "nobody checked"
    --           must not look the same to a family (D9).
    deadline_kind       TEXT          NOT NULL,

    -- The day, when there is one. NULL for every other kind; see the
    -- value-iff-kind CHECK below.
    deadline_date       DATE          NULL,

    -- THE DEADLINE IN THE PUBLISHER'S OWN WORDS, on every row whatever its
    -- kind: "March 2, 2027, 11:59 p.m. Pacific", "as soon as possible after
    -- October 1", "no state deadline is published".
    --
    -- The NAME is the repo's existing one for exactly this idea:
    -- AdmissionTestTimelineChatTool carries `closingAsPublished` -- the
    -- school's own words -- beside the parsed `closingResolved`, and emits it
    -- as the JSON key `closing_as_published`. This column is that pattern one
    -- table along, so it takes that pattern's name.
    --
    -- Both halves ride for that same reason: a reader that saw only our parsed
    -- date could attribute OUR reading to the publisher, and a DATE column
    -- cannot hold the hour or the time zone the state actually enforces.
    deadline_as_published TEXT        NOT NULL,

    -- WHO published it, in the words a family would recognise on the page --
    -- 'California Student Aid Commission'. This is a fact about the ROW and not
    -- about the document, which is exactly why external_documents refuses to
    -- carry it -- create-external-documents.sql:22-24, in its own words: "the
    -- FACT already knows its publisher (RFC 177 derived attribution per
    -- figure, so one publisher name comes from one place); a publisher here
    -- would be a second column that can disagree with it". The public page
    -- renders this as the link text, so without it every row would cite a bare
    -- url.
    publisher           TEXT          NOT NULL,

    -- THE CITATION IS TWO FOREIGN KEYS AND NEVER A URL COLUMN OF OUR OWN
    -- (RFC 213, brief 0012 D16, the shape adopt-external-documents-for-state-
    -- programmes.sql already put on this table's closest sibling).
    -- source_document_id is the agency's own page and is NOT NULL: a row that
    -- cites nothing is the uncitable row this corpus exists to prevent.
    -- archive_document_id is the archived address the row was read through,
    -- where the agency's page opens for no client we have -- 7 of the 148 rows
    -- the harvest produced need one, and two of the jurisdictions it could not
    -- verify at all, Delaware and New Hampshire, refuse every client we have
    -- with a 403.
    source_document_id  UUID          NOT NULL REFERENCES external_documents(id),
    archive_document_id UUID          NULL     REFERENCES external_documents(id),

    -- The day a human read the source, and the day this row stops being
    -- believed.
    checked_on          DATE          NOT NULL,
    expires_on          DATE          NOT NULL,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- The coarse DB-level backstop UsStateCodes.kt asks for. ^[A-Z]{2}$ admits
    -- 676 strings and 59 of them are legal, so MEMBERSHIP is checked by the
    -- generator and the seed test with UsStateCodes.parse; this stops the
    -- lower-case and three-letter cases at the write boundary.
    CONSTRAINT fafsa_filing_deadlines_state_format_check
        CHECK (state IS NULL OR state ~ '^[A-Z]{2}$'),

    CONSTRAINT fafsa_filing_deadlines_event_check
        CHECK (event IN ('form_opens', 'priority_deadline', 'final_deadline',
                         'corrections_deadline')),

    CONSTRAINT fafsa_filing_deadlines_kind_check
        CHECK (deadline_kind IN ('date', 'wording', 'none')),

    -- Value-iff-kind (the 0087 value-iff-status shape): a date exists exactly
    -- when the kind bears one. This is what stops a 'wording' row quietly
    -- growing a date somebody guessed, and stops a 'date' row with nothing in
    -- it reaching the page as a blank cell.
    CONSTRAINT fafsa_filing_deadlines_date_iff_kind_check
        CHECK ((deadline_date IS NOT NULL) = (deadline_kind = 'date')),

    -- A form that opens does open. 'none' is a statement about a deadline a
    -- publisher does not set; it is not a thing to say about an opening date,
    -- and a row that said it would render as "the FAFSA does not open".
    CONSTRAINT fafsa_filing_deadlines_opening_is_dated_check
        CHECK (event <> 'form_opens' OR deadline_kind <> 'none'),

    -- EVERY ROW'S DATE BELONGS TO ITS OWN CYCLE, and the LOWER bound is the
    -- half that earns this constraint. The 2027-28 FAFSA opens in October 2026
    -- and its corrections window closes in September 2028, so the window is
    -- [award_year - 1 July 1, award_year + 1 Dec 31]: the lower bound admits
    -- that October opening and REFUSES last cycle's 2 March priority deadline,
    -- which is the year-shift typo this check exists for -- last cycle's date
    -- typed under this cycle's key, the one mistake that makes this table worse
    -- than no table, because a family acts on it. make_date is IMMUTABLE, so
    -- the bound is checkable here and not only in the generator.
    --
    -- IT IS A COARSE WINDOW AND CANNOT BE TIGHT, because two cycles genuinely
    -- overlap in the calendar: the 2026-27 corrections deadline (11 September
    -- 2027) falls ELEVEN MONTHS AFTER the 2027-28 form opened. No pair of dates
    -- separates the two cycles, so a check that refused every neighbouring
    -- cycle's date would refuse true rows. This catches the common typo, and
    -- the generator's per-row validator and the seed review catch the rest.
    CONSTRAINT fafsa_filing_deadlines_date_in_cycle_check
        CHECK (deadline_date IS NULL
               OR deadline_date BETWEEN fafsa_cycle_opens_on(award_year)
                                    AND make_date(award_year::INT + 1, 12, 31)),

    -- NOT NULL IS NOT ENOUGH, AND NEITHER IS `<> ''`. An empty string is a
    -- value-shaped absence, and a citation rendered from one is a link to
    -- nowhere (0087's words, 0077's CHECKs) -- but '   ' is the same absence
    -- spelt differently, and 'Cal Grant ' is WORSE than either on `program`,
    -- which is part of the natural key: the trailing space makes one programme
    -- two rows and the public page prints it twice. Every one of these three
    -- columns is typed by a human from a page, so all three are trimmed AND
    -- non-empty, the 0001.create-users.sql shape.
    CONSTRAINT fafsa_filing_deadlines_program_nonempty_check
        CHECK (program = btrim(program) AND length(program) > 0),
    CONSTRAINT fafsa_filing_deadlines_deadline_as_published_nonempty_check
        CHECK (deadline_as_published = btrim(deadline_as_published)
               AND length(deadline_as_published) > 0),
    CONSTRAINT fafsa_filing_deadlines_publisher_nonempty_check
        CHECK (publisher = btrim(publisher) AND length(publisher) > 0),

    -- An archive of a page may not BE that page: an archive of itself is the
    -- same link printed twice, and a page told to "offer both links" would show
    -- one url as two. The sibling corpus states this guarantee in the same
    -- words (adopt-external-documents-for-state-programmes.sql).
    CONSTRAINT fafsa_filing_deadlines_archive_is_not_the_source_check
        CHECK (archive_document_id IS NULL
               OR archive_document_id <> source_document_id),

    CONSTRAINT fafsa_filing_deadlines_expiry_after_checked_check
        CHECK (expires_on > checked_on),

    -- A row cannot have been read from a page before the page could have said
    -- it: the same lower bound as the date window, one bound only, because a
    -- human may legitimately re-read a page years later to confirm it. An
    -- agency publishes a cycle's deadline in the months before that cycle
    -- opens, never a year and a half before -- so a checked_on earlier than
    -- July of the year the form opens is a mistyped award_year, not a diligent
    -- reader.
    CONSTRAINT fafsa_filing_deadlines_checked_in_or_after_cycle_check
        CHECK (checked_on >= fafsa_cycle_opens_on(award_year))
);

-- The natural key, and what the generated seed upserts on. It is a UNIQUE
-- INDEX and not an inline constraint because it needs NULLS NOT DISTINCT, and
-- that is how this repo already writes such a key -- cohort_money_stats
-- (0083:286-291) and cohort_population_counts (0085:165-171) both do it, for
-- the same reason: an axis whose NULL MEANS something ("the overall figure"
-- there, "federal" here) must collide with itself rather than land twice.
-- Without it, two disagreeing federal rows would both insert.
CREATE UNIQUE INDEX fafsa_filing_deadlines_natural_key
    ON fafsa_filing_deadlines (award_year, state, program, event)
    NULLS NOT DISTINCT;

COMMENT ON TABLE fafsa_filing_deadlines IS
    'The FAFSA dates for one award year (brief 0012 D4): the federal open date '
    'and deadline, and each state''s own deadlines for the aid the FAFSA '
    'unlocks. Hand-typed from each publisher''s page, cited per row into '
    'external_documents, and expiring per row. One store for the public page '
    'and the coach, so the two cannot disagree.';

COMMENT ON COLUMN fafsa_filing_deadlines.state IS
    'The USPS code of the state whose agency published this, or NULL for the '
    'FEDERAL row. Not a foreign key YET: us_states is loaded by the codebook '
    'ingest and is empty in a freshly migrated database, so test/05.3 adds the '
    'key here and on admission_test_state_programmes together. Membership in '
    'the 59 published codes is UsStateCodes.parse''s job, in the generator and '
    'the seed test.';

COMMENT ON COLUMN fafsa_filing_deadlines.deadline_kind IS
    'date: a published day, in deadline_date. wording: a published RULE and no '
    'day ("as soon as possible", "until funds are exhausted"), carried as '
    'those words and never flattened into a date. none: we read the page and '
    'it publishes no deadline -- a verified absence with a citation, not our '
    'silence.';

COMMENT ON COLUMN fafsa_filing_deadlines.event IS
    'What kind of date this is: form_opens, priority_deadline, '
    'final_deadline, or corrections_deadline (the last day to fix an error on '
    'a filed form). A per-table CHECK plus the Kotlin AidDeadlineEvent enum, '
    'and it becomes a DOMAIN the day a SECOND table needs the same vocabulary '
    '-- the rule RFC 213 states for link_status, written down here so the '
    'promotion has a trigger rather than a guess.';

COMMENT ON COLUMN fafsa_filing_deadlines.deadline_as_published IS
    'The publisher''s own words for this deadline, on every row whatever the '
    'kind -- including the hour and time zone a DATE cannot hold. The parsed '
    'deadline_date is OURS; this is theirs, and the page shows both. Named '
    'for the landed closingAsPublished / closingResolved pair one table '
    'along.';

COMMENT ON COLUMN fafsa_filing_deadlines.publisher IS
    'Who published this row, as a family would recognise them on the page -- '
    'the link text the public table renders. A fact about the ROW, which is '
    'why external_documents deliberately carries no publisher of its own '
    '(RFC 213).';

COMMENT ON COLUMN fafsa_filing_deadlines.source_document_id IS
    'The agency''s own page, read on checked_on (RFC 213). NOT NULL: a row '
    'that cites nothing is the uncitable row this corpus exists to prevent.';

COMMENT ON COLUMN fafsa_filing_deadlines.archive_document_id IS
    'The archived address the row was read through, where the agency''s own '
    'page would not open for any client we have. NULL where no archive was '
    'needed. Never equal to source_document_id, and never present without it.';

-- NO INDEX beyond the natural key, deliberately, and for the RFC 197 reason:
-- the only read is "every row of one award year" over ~60 rows, which reads
-- the whole table whatever indexes exist. The natural key LEADS with
-- award_year, so a by-year probe walks it (the 0085 rule).

CREATE TRIGGER trigger_03_enforce_fafsa_filing_deadlines_updated_at
BEFORE UPDATE ON fafsa_filing_deadlines
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
