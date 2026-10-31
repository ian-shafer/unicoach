-- The erasure classification's types (RFC 263, slice
-- deletion/01/the-classification, brief 0002 D3/D8).
--
-- Erasure is driven by the schema, not a column list: whether a column holds a
-- person's data "lives in the type" (D8), and the walk that erases an account
-- reads the catalog (D3). These two schemas are where the type lives.
--
--   pii.*    a column of one of these types holds a person's data. Erasure
--            clears it on a row whose owner is erased.
--   owned.*  a key of one of these types leads from an owner row to that
--            person's words somewhere the walk would not reach child-ward (the
--            LLM log). Erasure follows it forward.
--
-- The schemas hold DOMAINS ONLY, and a domain here classifies and constrains
-- nothing: no CHECK, no NOT NULL, no DEFAULT. Presence while live is a
-- per-column CHECK on each table (D7), because a domain cannot see the row's
-- deleted_at.
--
-- One pii domain per BASE TYPE, not per meaning (`pii.email`, `pii.name`): the
-- format rules on users.email, users.name and user_auth_identities.email differ
-- per column today, so moving them into a domain would need one domain per
-- column and gain no guarantee. They stay on their columns.
--
-- pii.slug is over public.slug, not TEXT, so a retyped slug column keeps the
-- format its foreign key to subjects relies on.
--
-- No GRANT: a role reads and writes a domain-typed column without USAGE on the
-- domain's schema, and the domains keep PostgreSQL's default USAGE for PUBLIC.
-- Every reference to pii or owned inside a function body must be
-- schema-qualified (the 0056 search_path note).

CREATE SCHEMA pii;
COMMENT ON SCHEMA pii IS
    'Domains only: a column of one of these types holds a person''s data (RFC 263).';

CREATE SCHEMA owned;
COMMENT ON SCHEMA owned IS
    'Domains only: a key of one of these types leads to a person''s data (RFC 263).';

CREATE DOMAIN pii.text    AS TEXT;
CREATE DOMAIN pii.jsonb   AS JSONB;
CREATE DOMAIN pii.date    AS DATE;
CREATE DOMAIN pii.integer AS INTEGER;
CREATE DOMAIN pii.uuid    AS UUID;
CREATE DOMAIN pii.slug    AS public.slug;

CREATE DOMAIN owned.llm_request_id AS BIGINT;
