-- Regional tuition exchanges (RFC 277, slice shape/09/exchange-participation).
-- What a student's coach needs to say about one: who can use it, what it caps,
-- whether getting in is enough, what it takes and by when. Reference data with
-- no soft delete and no version history (the RFC 239 shape).

-- Who can use an exchange. One fact per exchange, so it lives once. Seeded here
-- because it is keyed by nothing the import fills.
CREATE TABLE tuition_exchange_states (
    exchange   TEXT NOT NULL,
    state_code TEXT NOT NULL,
    PRIMARY KEY (exchange, state_code),
    CONSTRAINT tuition_exchange_states_exchange_check
        CHECK (exchange IN ('wue')),
    CONSTRAINT tuition_exchange_states_state_code_check
        CHECK (state_code ~ '^[A-Z]{2}$')
);

-- What one school does with an exchange. Keyed by colleges(id), which the import
-- fills, so the rows load with bin/ingest-colleges from
-- db/seed/tuition-exchanges/ rather than from a migration (RFC 239 D-C).
--
-- No dollar column. A school's ceiling is a percentage of its own resident
-- tuition, so the capped figure is arithmetic done at read time over the year
-- being served, and stays labelled as ours.
CREATE TABLE college_tuition_exchanges (
    id                               UUID         NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id                       UUID         NOT NULL REFERENCES colleges(id),
    exchange                         TEXT         NOT NULL,
    max_percent_of_resident_tuition  SMALLINT     NOT NULL,
    -- Does meeting the criteria get the rate? 'selective' means some eligible
    -- students are turned down. 'unstated' here and below: the school's page
    -- does not say, and the coach sends the student to the page.
    selection                        TEXT         NOT NULL,
    -- Does the student have to ask for it?
    separate_application             TEXT         NOT NULL,
    minimum_unweighted_gpa           NUMERIC(3,2) NULL,
    percent_of_eligible_offered      SMALLINT     NULL,
    -- A deadline stated as a month and day is stored as its next occurrence
    -- after checked_on (RFC 239's reading-day rule).
    apply_by                         DATE         NULL,
    source_document_id               UUID         NOT NULL REFERENCES external_documents(id),
    checked_on                       DATE         NOT NULL,
    expires_on                       DATE         NOT NULL,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT college_tuition_exchanges_one_per_exchange
        UNIQUE (college_id, exchange),
    CONSTRAINT college_tuition_exchanges_exchange_check
        CHECK (exchange IN ('wue')),
    CONSTRAINT college_tuition_exchanges_max_percent_check
        CHECK (max_percent_of_resident_tuition >= 100),
    CONSTRAINT college_tuition_exchanges_selection_check
        CHECK (selection IN ('guaranteed', 'selective', 'unstated')),
    CONSTRAINT college_tuition_exchanges_separate_application_check
        CHECK (separate_application IN ('required', 'not_required', 'unstated')),
    CONSTRAINT college_tuition_exchanges_gpa_check
        CHECK (minimum_unweighted_gpa BETWEEN 0 AND 4),
    CONSTRAINT college_tuition_exchanges_offered_check
        CHECK (percent_of_eligible_offered BETWEEN 1 AND 100),
    CONSTRAINT college_tuition_exchanges_expiry_check
        CHECK (expires_on > checked_on)
);

CREATE TRIGGER trigger_03_enforce_college_tuition_exchanges_updated_at
BEFORE UPDATE ON college_tuition_exchanges
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- The Western Undergraduate Exchange's member states and territories, from
-- WICHE's own published list: https://www.wiche.edu/tuition-savings/wue/
INSERT INTO tuition_exchange_states (exchange, state_code)
VALUES
    ('wue', 'AK'),
    ('wue', 'AZ'),
    ('wue', 'CA'),
    ('wue', 'CO'),
    ('wue', 'HI'),
    ('wue', 'ID'),
    ('wue', 'MT'),
    ('wue', 'NV'),
    ('wue', 'NM'),
    ('wue', 'ND'),
    ('wue', 'OR'),
    ('wue', 'SD'),
    ('wue', 'UT'),
    ('wue', 'WA'),
    ('wue', 'WY'),
    ('wue', 'AS'),
    ('wue', 'MP'),
    ('wue', 'GU');
