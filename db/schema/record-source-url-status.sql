-- RFC 200 -- no link to a grave.
--
-- The Family Cost Report links every Common Data Set citation to the college's
-- own filing (RFC 196), and `AidPolicyWire` hands the coach that url beside the
-- byte-frozen sentence "We link to the college's own file so you can see it."
-- Measured 2026-09-13 over the committed seed (db/seed/cds/source-url-status.csv,
-- 441 distinct documents): 417 found, 15 NOT FOUND in two independent passes,
-- and 9 unknown. A parent who clicks a citation and meets a 404 learns that we
-- do not check what we point at.
--
-- So the CHECK runs at ingest (`bin/cds-link-check`, joined in by
-- `CdsSeedLoader`) and its answer is RECORDED HERE, on the row it is about. A
-- family must never wait on a college's web server, and a report must never be
-- slower because a school's site is down: nothing on the render path asks the
-- network anything.
--
-- THREE states, not four (Ian, at the RFC 200 gate). An earlier draft had a
-- fourth for "we asked and got no usable answer", which did no work: it behaves
-- exactly like "we never asked" -- both KEEP the link -- and the two are already
-- told apart by the date beside them. Two states distinguished only by another
-- column is one state too many.
--
--     unknown + NULL date  -- we never asked
--     unknown + a date     -- we asked and got no usable answer (a timeout, a
--                             DNS fault, or a school refusing a script with
--                             403/400)
--     found / not_found    -- we asked, and the date says when
--
-- RFC 182's rule still holds: a check that quietly did not run must never look
-- like one that passed. "Never asked" and "asked and failed" stay
-- distinguishable in the data, so a BROKEN checker (many dated unknowns) cannot
-- be mistaken for a stale one (many undated unknowns), and the ingest summary
-- prints the two counts separately.
--
-- `found`/`not_found` mirror the HTTP codes they are derived from -- 404 IS Not
-- Found -- so the mapping is obvious to whoever debugs this later. ONLY
-- `not_found` drops an anchor; every other state keeps the link, because an
-- unreachable CHECK is our gap and never the school's silence (RFC 166 s6).
--
-- TEXT + CHECK IN (...) plus exactly one Kotlin `enum class SourceUrlStatus`
-- with a `fromValue` companion, per CLAUDE.md's schema convention for an own
-- enumeration. A per-table CHECK rather than a DOMAIN: unlike `money_source`,
-- this list describes ONE column on ONE table -- the status of a document's own
-- url -- and nothing else in the schema asks the question.
ALTER TABLE source_documents
    ADD COLUMN source_url_status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (source_url_status IN ('unknown', 'found', 'not_found')),
    ADD COLUMN source_url_checked_on DATE NULL,
    -- You cannot claim to know without saying when you looked. `found` and
    -- `not_found` both REQUIRE a date; `unknown` may carry one or not, and
    -- which it carries is the "never asked" / "asked and failed" distinction
    -- above.
    ADD CONSTRAINT source_documents_checked_when_known
        CHECK (source_url_status = 'unknown' OR source_url_checked_on IS NOT NULL);

COMMENT ON COLUMN source_documents.source_url_status IS
    'Whether the school''s own file was still served when we last looked '
    '(RFC 200): found, not_found, or unknown. Recorded at INGEST by '
    'bin/cds-link-check and never at render. Only not_found drops the anchor '
    'on a citation; the citation itself is still said either way.';

COMMENT ON COLUMN source_documents.source_url_checked_on IS
    'The day source_url_status was established. NULL only where the status is '
    'unknown AND we never asked -- an unknown WITH a date is a check that ran '
    'and got no usable answer, which must never look like one that passed '
    '(RFC 182).';
