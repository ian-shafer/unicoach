-- The `colleges` codebook foreign keys (RFC 150 D57), deferred by that RFC and
-- landed here.
--
-- WHY THIS EXISTS. `colleges.state` and `colleges.locale` are source-defined
-- codes with a published vocabulary — IPEDS `STABBR` and `LOCALE` — and RFC 147
-- put both vocabularies in real tables (`us_states`, `nces_locales`, 0060).
-- Until now the schema only checked their SHAPE: `colleges_state_length_check`
-- says "two characters", `colleges_locale_range_check` says "11..43". Both
-- admit codes that name nothing. `ZZ` is two characters; `14` is inside 11..43
-- and is not a locale NCES publishes. A shape check that a real foreign key
-- subsumes is a weaker statement of the same rule, so the checks go and the
-- foreign keys arrive.
--
-- WHY IT WAS DEFERRED, AND WHAT CHANGED. 0064 declined to ship this. The
-- corpus measurement passed then and passes now — re-measured against the
-- 6,273-row Scorecard snapshot for this migration: 59 distinct `STABBR` values,
-- all 59 resolving to a `us_states` row, and 5,728 rows carrying a `LOCALE`
-- over 12 distinct codes, all 12 resolving to an `nces_locales` row. What
-- stopped it was the consequence, not the data: `us_states` and `nces_locales`
-- are INGEST-LOADED, so an FK makes the `codebooks` phase a hard PRECONDITION
-- of writing any `colleges` row rather than merely the phase that runs first.
-- That is exactly the decision this migration makes, deliberately and with the
-- operator's approval, and two changes carry it:
--
--   `--codebooks` becomes a REQUIRED argument of the ingest binary. The
--   launcher `bin/ingest-colleges` always supplied it; the JVM entry point
--   treated it as optional, so the contract lived only in the shell. It now
--   lives in the binary, which refuses at argv parse.
--
--   `CollegeScorecardLoader` refuses the `institutions` phase up front, with a
--   named error, when the reference tables are EMPTY. That is a different
--   failure from the one above — a database that was migrated but never
--   ingested, or a test that truncated the tables — and it is reported as a
--   precondition instead of surfacing as a raw FK violation from the middle of
--   a phase.
--
-- WHY `colleges_versions` IS LEFT ALONE. The history table records what WAS
-- stored, at the version it was stored. Constraining it to today's codebook
-- would let a later codebook EDIT — a jurisdiction renamed, a locale code
-- retired — invalidate a row that was correct when it was written, and either
-- block the edit or destroy history. A history row is a fact about the past;
-- it is not a reference into the present.

-- ---------------------------------------------------------------------------
-- colleges — the two shape checks out, the two foreign keys in.
-- ---------------------------------------------------------------------------

ALTER TABLE colleges DROP CONSTRAINT colleges_locale_range_check;
ALTER TABLE colleges DROP CONSTRAINT colleges_state_length_check;

ALTER TABLE colleges ADD CONSTRAINT colleges_locale_codebook_fkey
    FOREIGN KEY (locale) REFERENCES nces_locales (code);
ALTER TABLE colleges ADD CONSTRAINT colleges_state_codebook_fkey
    FOREIGN KEY (state) REFERENCES us_states (usps_code);

-- ---------------------------------------------------------------------------
-- college_search_index — the eighth foreign key 0064 could not add.
--
-- 0064 left `college_search_index.state` unreferenced because its soundness
-- argument was "satisfiable by construction": the index is rebuilt wholesale
-- from `colleges`, so its `state` can only be a value `colleges` already holds.
-- That argument depended on `colleges.state` being constrained, which it now
-- is, so the index gets the matching reference and the rebuild can no longer
-- write a state the codebook does not name.
-- ---------------------------------------------------------------------------

ALTER TABLE college_search_index ADD CONSTRAINT college_search_index_state_fkey
    FOREIGN KEY (state) REFERENCES us_states (usps_code);
