-- external_documents -- one home for a url we cite, and whether it still
-- resolves (RFC 213, slice test/05.1/external-documents, brief 0010 D26).
--
-- A citation in this repo is a bare TEXT url column on the fact row, copied
-- from table to table. Four live tables carry such columns today, and the one
-- attempt to de-duplicate them -- source_documents (RFC 170 D13) -- is
-- `college_id UUID NOT NULL REFERENCES colleges(id)` with a `money_source`
-- publisher and an `academic_year`, so it cannot hold a citation about a
-- JURISDICTION at all. A state statute has no college, no money publisher and
-- no academic year.
--
-- A URL, AND WHETHER IT STILL RESOLVES. NOTHING ELSE (Ian, 2026-09-14, cutting
-- this design twice).
--
--   * No `type`/`kind` column. The format is derivable from the fetch and
--     belongs to whatever fetches; the genre is a judgement that varies by
--     reader; and the thing we actually need -- WHICH CLAIM a document backs --
--     is the owner's fact, not the document's, because the same document backs
--     different claims for different rows.
--   * No `publisher` column. `source_documents.source` is exactly that, typed
--     `money_source`, and that typing is WHY it cannot hold a state citation.
--     Worse, the FACT already knows its publisher (RFC 177 derived attribution
--     per figure, so one publisher name comes from one place); a publisher here
--     would be a second column that can disagree with it.
--   * No expiry. Expiry is a property of a READING of a document -- the owner's
--     own expires_on -- and never of the document.
--
-- AN ARCHIVED ADDRESS IS JUST ANOTHER ROW HERE. To this product a "capture" is
-- the url a human read when the publisher's own page would not open for us:
-- New Hampshire's and Arizona's education departments return 403 to every
-- client we have, so those rows were read through the Wayback Machine and that
-- address was written down. It is HOW the content was reached, not a kind of
-- object the product reasons about. So it is cited like any other document and
-- link-checked like any other, and the row that needed it points at it with a
-- second, nullable foreign key. A `document_captures` edge table, a
-- self-reference, a per-owner join table with a claims array -- all three were
-- drafted for this slice and all three were cut: a table for a concept the
-- product does not have is the concept arriving anyway.
--
-- THE URL IS THE NATURAL KEY. One published document, one row, whoever cites
-- it. Measured in our own committed CDS seed: 446 distinct (unit_id,
-- source_year) pairs resolve to only 441 distinct urls -- one University of
-- Washington filing backs three campuses, one Fairleigh Dickinson filing two
-- units, one Colorado page two years -- and the college-keyed table stores that
-- file once per college. Keying by url is what stops the repetition.
--
-- http IS ACCEPTED, https PREFERRED (Ian, at the RFC 208 gate: "https only --
-- why? We should prefer https, but not block insecure"). Six of the seven
-- archived addresses we already hold are plain http://web.archive.org/...; an
-- https-only CHECK would refuse data we have. The rule the citation needs is
-- that there IS a page, and that its scheme is STATED rather than guessed at.
--
-- Reference-table shape (0060/0077/0087 precedent): unversioned, no soft
-- delete, no optimistic concurrency, created_at/updated_at with the plain
-- update_colleges_timestamp() trigger.

-- THE LINK-ROT VOCABULARY, DECLARED ONCE (the `money_source` argument in 0087,
-- and 0054's own comment: declared ONCE as a DOMAIN, not eighteen edits).
-- RFC 200 made this a per-table CHECK because exactly one column asked the
-- question. Two tables ask it now, so it becomes a domain here;
-- source_documents keeps its own CHECK until test/05.2 folds it in, and that
-- slice converts the column rather than writing a third copy.
--
-- THREE STATES, NOT FOUR (Ian, at the RFC 200 gate), and the DATE carries the
-- fourth distinction:
--
--     unknown + NULL date  -- we never asked
--     unknown + a date     -- we asked and got no usable answer (a timeout, a
--                             DNS fault, a TLS fault, or a host refusing us)
--     found / not_found    -- we asked, and the date says when
--
-- RFC 182's rule, kept: a check that quietly did not run must never look like
-- one that passed. Only not_found drops an anchor; every other state keeps the
-- link, because an unreachable CHECK is our gap and never the publisher's
-- silence (RFC 166 s6).
--
-- TEXT + CHECK plus exactly one Kotlin enum with a fromValue companion, per
-- CLAUDE.md. The enum is the EXISTING SourceUrlStatus, reused rather than
-- duplicated: renaming it to match this column touches CdsRead, CdsSeedLoader
-- and a schema pin test for no behaviour, so it is test/05.2's job.
CREATE DOMAIN link_status AS TEXT
    CONSTRAINT link_status_check
        CHECK (VALUE IN ('unknown', 'found', 'not_found'));

COMMENT ON DOMAIN link_status IS
    'Whether a url we cite was still served when we last looked (RFC 200, '
    'generalised by RFC 213): found, not_found, or unknown. Mirrored by the '
    'Kotlin SourceUrlStatus enum and pinned to it by test. A DOMAIN, not a '
    'per-table CHECK: the list is one list, wherever the question is asked.';

CREATE TABLE external_documents (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The document itself, and the key. UNIQUE rather than a surrogate natural
    -- key of (publisher, year, ...) because a url IS the identity of a
    -- published document, and every alternative key we could invent here is a
    -- fact about the CITER rather than about the thing cited.
    url            TEXT        NOT NULL UNIQUE,

    url_status     link_status NOT NULL DEFAULT 'unknown',

    -- The day url_status was established. NULL only where the status is unknown
    -- AND we never asked -- an unknown WITH a date is a check that ran and got
    -- no usable answer, which must never look like one that passed (RFC 182).
    -- A DATE and not a TIMESTAMPTZ, on the two-clocks rule this brief already
    -- wrote down: a DATE is a day in the WORLD (the day a human, or a sweep an
    -- operator ran by hand, looked at the page), a TIMESTAMPTZ is a fact about
    -- OUR ROW.
    url_checked_on DATE        NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- NOT NULL is not enough: an empty string is a url-shaped absence, and a
    -- citation rendered from one is a link to nowhere (0087's own wording).
    CONSTRAINT external_documents_url_nonempty_check
        CHECK (url <> ''),

    -- The scheme is REQUIRED, not assumed (Ian, 2026-09-14: "Do urls require
    -- the scheme? I say yes"). Prepending https:// would be us inventing part
    -- of an address a human was meant to supply, and `example.com:8080/x`
    -- parses as a scheme named `example.com` -- so a schemeless input cannot be
    -- validated, only guessed at. bin/check-link refuses one for the same
    -- reason, so the checker and this table agree about what a url is.
    CONSTRAINT external_documents_url_shape_check
        CHECK (url ~ '^https?://'),

    -- You cannot claim to know without saying when you looked (RFC 200).
    CONSTRAINT external_documents_checked_when_known
        CHECK (url_status = 'unknown' OR url_checked_on IS NOT NULL)
);

COMMENT ON TABLE external_documents IS
    'One published document per row (RFC 213, brief 0010 D26): the url, and '
    'whether it still resolved when we last looked. Keyed by the url, because '
    'one document is cited by many rows and by many kinds of row. An archived '
    'address is an ordinary row here -- cited like any other document and '
    'link-checked like any other.';

COMMENT ON COLUMN external_documents.url_status IS
    'Whether the document was still served when we last looked (RFC 200): '
    'found, not_found, or unknown. Recorded by an operator sweep at seed time '
    'and never at render -- a family must never wait on somebody else''s web '
    'server. Only not_found justifies dropping an anchor.';

COMMENT ON COLUMN external_documents.url_checked_on IS
    'The day url_status was established. NULL only where the status is unknown '
    'AND we never asked -- an unknown WITH a date is a check that ran and got '
    'no usable answer, which must never look like one that passed (RFC 182).';

CREATE TRIGGER trigger_03_enforce_external_documents_updated_at
BEFORE UPDATE ON external_documents
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO INDEX beyond the two implicit ones (the primary key, and the UNIQUE on
-- url), on the reasoning create-admission-test-state-programmes.sql states for
-- itself: the only reads are by id (a join from an owner) and by url (the
-- seed's upsert), and both are served. An index describing a query nobody wrote
-- is worse than no index.
