-- The erasure itself: erasure_admits, soft_delete_entity, erase_account
-- (RFC 266, slice deletion/03/the-walk, brief 0002 D1/D3/D10).
--
-- WHY. A closed account is erased by the schema, not by a column list (D3):
-- every pii.* column NULL and deleted_at stamped, every key onto the account
-- moved onto its deleted_* sibling, a short named list physically deleted.
-- RFC 263 typed the columns and RFC 264 gave the keys somewhere to go; these
-- functions read both from the catalog on every call, so a column or key added
-- next year is erased without a change here.
--
-- Every reference to another object is schema-qualified, so a caller's
-- search_path cannot change what a function reads or writes.

-- ---------------------------------------------------------------------------
-- erasure_admits: the declared erasure (RFC 266 §5, decision D-C).
--
-- The walk names the account it is erasing in a transaction-local setting,
-- unicoach.erasure_account, and the guards that would refuse its writes ask
-- this predicate first. It is an integrity guard against application bugs, not
-- a security boundary: any session of the application's role could set the
-- marker, as it could run any other SQL. What keeps a stray marker from opening
-- a live account's rows is the second half: the named account must be closed.
--
-- Unset (NULL), cleared by the walk (''), or not a uuid: nothing is admitted.
-- The shape is checked rather than cast, so a malformed marker makes a guard
-- refuse as it always has instead of raising a cast error from inside it.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.erasure_admits()
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    marker text := pg_catalog.current_setting('unicoach.erasure_account', true);
BEGIN
    IF marker IS NULL OR marker = '' THEN
        RETURN FALSE;
    END IF;
    IF marker !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RETURN FALSE;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.users u
         WHERE u.id = marker::uuid AND u.deleted_at IS NOT NULL
    );
END;
$$;

-- ---------------------------------------------------------------------------
-- soft_delete_entity: erase the rows of one table that match one key
-- (RFC 266 §2).
--
-- In one UPDATE, on every matching row not already erased: every column whose
-- type lives in the pii schema is set NULL; deleted_at is stamped where the
-- table has one and it is NULL (a closed account keeps its close time); version
-- is bumped where the table carries enforce_versioning, which refuses any other
-- UPDATE. The column set is read from the catalog on every call.
--
-- A row is already erased when every pii.* column is NULL and deleted_at (if
-- the table has one) is set. Skipping those rows is what makes a second run
-- write nothing: no version bump, no history row. A table with neither a pii.*
-- column nor deleted_at (subscriptions, cost_report_shares, the support
-- tables) is never changed: the subscription outlives the person (D6).
--
-- The key arrives as text so one signature serves uuid and bigint keys. It is
-- cast to the key column's own type, not the column to text, so the key's
-- index serves the match: the walk calls this once per erased row.
--
-- REFUSAL. The target must be a table in schema public, and key_column one of
-- its keys: its single-column primary key, a single-column foreign key, or, on
-- a table with no single-column primary key (a history table), its id. Either
-- refusal raises 22023 before anything is written, so a call aimed at the
-- wrong relation or a non-key column cannot blank rows by accident.
-- ---------------------------------------------------------------------------

-- soft_delete_entity_admits_key: whether key_column is a key of target, by
-- soft_delete_entity's REFUSAL rule, read from pg_constraint.
CREATE FUNCTION public.soft_delete_entity_admits_key(
    target     regclass, -- the table
    key_column name      -- the column soft_delete_entity would match
)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
               SELECT 1 FROM pg_catalog.pg_constraint con
                 JOIN pg_catalog.pg_attribute a
                   ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
                WHERE con.conrelid = target AND con.contype IN ('p', 'f')
                  AND pg_catalog.cardinality(con.conkey) = 1
                  AND a.attname = key_column
           )
        OR (key_column = 'id'
            AND NOT EXISTS (
                SELECT 1 FROM pg_catalog.pg_constraint con
                 WHERE con.conrelid = target AND con.contype = 'p'
                   AND pg_catalog.cardinality(con.conkey) = 1
            ))
$$;

CREATE FUNCTION public.soft_delete_entity(
    target     regclass,  -- the table
    key_column name,      -- the column to match
    key_value  text       -- the value, cast to key_column's type
)
RETURNS bigint            -- rows changed
LANGUAGE plpgsql
AS $$
DECLARE
    qualified     text;
    key_type      text;
    personal      text[];
    has_deleted   boolean;
    versioned     boolean;
    assignments   text[];
    not_yet_erased text[];
    changed       bigint;
BEGIN
    SELECT pg_catalog.format('%I.%I', n.nspname, c.relname) INTO qualified
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE c.oid = target AND n.nspname = 'public' AND c.relkind IN ('r', 'p');
    IF qualified IS NULL THEN
        RAISE EXCEPTION 'soft_delete_entity: [%] is not a table in schema public', target
            USING ERRCODE = '22023';
    END IF;

    SELECT pg_catalog.format_type(a.atttypid, a.atttypmod) INTO key_type
      FROM pg_catalog.pg_attribute a
     WHERE a.attrelid = target AND a.attname = key_column
       AND a.attnum > 0 AND NOT a.attisdropped;
    IF key_type IS NULL THEN
        RAISE EXCEPTION 'soft_delete_entity: table [%] has no column [%]', qualified, key_column
            USING ERRCODE = '42703';
    END IF;
    IF NOT public.soft_delete_entity_admits_key(target, key_column) THEN
        RAISE EXCEPTION 'soft_delete_entity: [%] is not a key of [%]; expected its single-column primary key, a single-column foreign key, or the id of a table with neither',
            key_column, qualified
            USING ERRCODE = '22023';
    END IF;

    SELECT pg_catalog.array_agg(pg_catalog.format('%I', a.attname) ORDER BY a.attnum) INTO personal
      FROM pg_catalog.pg_attribute a
      JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
      JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
     WHERE a.attrelid = target AND a.attnum > 0 AND NOT a.attisdropped
       AND tn.nspname = 'pii';
    personal := COALESCE(personal, '{}');

    has_deleted := EXISTS (
        SELECT 1 FROM pg_catalog.pg_attribute a
         WHERE a.attrelid = target AND a.attname = 'deleted_at'
           AND a.attnum > 0 AND NOT a.attisdropped
    );

    IF pg_catalog.cardinality(personal) = 0 AND NOT has_deleted THEN
        RETURN 0;
    END IF;

    versioned := EXISTS (
        SELECT 1 FROM pg_catalog.pg_trigger tg
          JOIN pg_catalog.pg_proc p ON p.oid = tg.tgfoid
          JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
         WHERE tg.tgrelid = target AND NOT tg.tgisinternal
           AND pn.nspname = 'public' AND p.proname = 'enforce_versioning'
    );

    assignments := ARRAY(SELECT col || ' = NULL' FROM pg_catalog.unnest(personal) AS col);
    not_yet_erased := ARRAY(SELECT col || ' IS NOT NULL' FROM pg_catalog.unnest(personal) AS col);
    IF has_deleted THEN
        assignments := assignments || 'deleted_at = COALESCE(deleted_at, pg_catalog.now())'::text;
        not_yet_erased := not_yet_erased || 'deleted_at IS NULL'::text;
    END IF;
    IF versioned THEN
        assignments := assignments || 'version = version + 1'::text;
    END IF;

    EXECUTE pg_catalog.format(
        'UPDATE %s SET %s WHERE %I = $1::%s AND (%s)',
        qualified,
        pg_catalog.array_to_string(assignments, ', '),
        key_column,
        key_type,
        pg_catalog.array_to_string(not_yet_erased, ' OR ')
    ) USING key_value;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$;

-- ---------------------------------------------------------------------------
-- erase_account: erase one closed account, in the caller's transaction
-- (RFC 266 §3, §4; decision D-D).
--
-- Plpgsql rather than Kotlin driving per-row SQL: the walk is catalog reads
-- and set-based UPDATEs, one round trip, and the same function serves the
-- admin door and the sweep's job (deletion/04).
--
-- REFUSAL. The users row is locked FOR UPDATE. An unknown id raises with
-- CONSTRAINT erase_account_exists; a live account (deleted_at NULL) raises with
-- CONSTRAINT erase_account_closed. Both are P0001, so a caller routes on the
-- constraint name, never on the sentence.
--
-- THE ROOTS are the users row and, if present, the one students row and the
-- one supporters row whose user_id or deleted_user_id is the account. A second
-- such students or supporters row raises with CONSTRAINT erase_account_one_root
-- rather than being left behind unerased.
--
-- ORDER OF WORK, all inside one call. erase_account resolves the roots, sets
-- and clears the declaration, and runs each other step as a named function
-- below (erase_account_*), which only it calls:
--   1. read what the deletes need before anything is NULL: the account's
--      addresses (current and every historical one) and its session token
--      hashes, two of the identifiers the jobs predicate matches (§4);
--   2. declare the erasure (erasure_admits, above), snapshot the catalog the
--      walk reads (erase_account_prepare_walk), and record each root under
--      the account table it is a row of (erase_account_record_roots), which is
--      how every later step finds a table's roots: by that table, by name;
--   3. insert the deleted rows (D1, D9), ON CONFLICT DO NOTHING;
--   4. move the keys: every live key onto a root that a pair CHECK couples
--      with a sibling onto the matching deleted table (read from
--      pg_constraint.conkey, never from a name) moves onto the sibling, on
--      every row, the other party's rows included (D1);
--   5. collect what the account owns (below), then erase it;
--   6. delete D10's rows: sessions, verification_tokens, user_auth_identities
--      keyed on the account, supporter_invites keyed on any root, and the jobs
--      naming any of the account's identifiers, with their attempts;
--   7. clear the declaration, so the rest of the caller's transaction is
--      guarded again;
--   8. return one row per table with a non-zero count.
--
-- WHAT THE ACCOUNT OWNS (step 5) is built from the roots outward over
-- erasure_edge, to a fixpoint:
--   - a child-ward step (parent -> child, any edge that is not owned) reaches
--     every row whose key points at a row in the set; onto a root it matches
--     the live key OR its sibling, since step 4 has already moved it;
--   - a forward step (child -> parent, owned edges only) reaches the LLM call
--     an owned.* key of a row in the set points at; an owned key is never
--     followed backwards, so the walk never climbs from a log row into another
--     owner;
--   - THE OWNERSHIP RULE (decision D-A): a row that carries a key, live or
--     sibling, onto any account other than this one is never erased, by any
--     path; at most its key onto this account moved in step 4. Neither party's
--     erasure crosses into the other's rows;
--   - a conversation belongs to its party: on a supporter's conversation the
--     student key names whom it is about, not who wrote it, so it is not
--     counted (brief 0014 D9). `party` is read in one place,
--     erase_account_ownership_sql; D10's list is erase_account_walk_edges.
-- Edges onto or out of a deleted_* table (the sibling keys, and the deleted
-- rows' own keys) and edges into D10's tables are not walked. A key over more
-- than one column raises rather than being guessed at.
--
-- Each row in the set is erased by soft_delete_entity, by its single-column
-- primary key or, on a history table, by the entity id it copies (which erases
-- every version at once), tables in breadth-first order from the roots, so a
-- live table is erased before its _versions table. A table with no single key
-- is visited only if it holds nothing to erase and leads nowhere further (the
-- support tables); otherwise the walk raises.
--
-- WHAT THE WALK DOES NOT TOUCH: the deleted_* tables' own children; history
-- copies of a live key (they carry no foreign key and record what was true
-- then; D9 makes the id they name the deleted row's id anyway); an LLM log row
-- no owned.* key of an owned row points at (D11: kept).
--
-- IDEMPOTENT: the deleted rows conflict, a moved key has a NULL live value, an
-- erased row is skipped by soft_delete_entity, a deleted row is gone. A second
-- call returns no rows and writes nothing. Any error rolls back the caller's
-- transaction whole; nothing is half-erased.
--
-- The working sets are ON COMMIT DROP temp tables, emptied at the start of
-- every call, so a second call in one transaction starts clean. They are what
-- the steps share, so no step's signature carries the walk's state.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.erase_account(account uuid)  -- a users.id
RETURNS TABLE (
    table_name text,   -- a table the walk touched
    moved      bigint, -- rows whose live account key moved to its deleted sibling
    erased     bigint, -- rows soft_delete_entity changed
    deleted    bigint  -- rows physically deleted (D10)
)
LANGUAGE plpgsql
AS $$
DECLARE
    closed_at       timestamptz;
    student_root    uuid;
    supporter_root  uuid;
    user_roots      uuid[];
    student_roots   uuid[];
    supporter_roots uuid[];
    addresses       text[];
    token_hashes    text[];
BEGIN
    SELECT u.deleted_at INTO closed_at FROM public.users u WHERE u.id = account FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'erase_account: there is no account [%]', account
            USING ERRCODE = 'P0001', CONSTRAINT = 'erase_account_exists';
    END IF;
    IF closed_at IS NULL THEN
        RAISE EXCEPTION 'erase_account: account [%] is live; close it before erasing it', account
            USING ERRCODE = 'P0001', CONSTRAINT = 'erase_account_closed';
    END IF;

    student_root := public.erase_account_one_root(account, 'public.students'::regclass, ARRAY(
        SELECT s.id FROM public.students s
         WHERE s.user_id = account OR s.deleted_user_id = account));
    supporter_root := public.erase_account_one_root(account, 'public.supporters'::regclass, ARRAY(
        SELECT s.id FROM public.supporters s
         WHERE s.user_id = account OR s.deleted_user_id = account));
    user_roots := ARRAY[account];
    student_roots := pg_catalog.array_remove(ARRAY[student_root], NULL);
    supporter_roots := pg_catalog.array_remove(ARRAY[supporter_root], NULL);

    -- 1. What the deletes need, before anything is NULL.
    addresses := public.erase_account_addresses(account);
    token_hashes := public.erase_account_token_hashes(account);

    -- 2. Declare the erasure.
    PERFORM pg_catalog.set_config('unicoach.erasure_account', account::text, true);
    PERFORM public.erase_account_prepare_walk();
    PERFORM public.erase_account_record_roots(account, student_root, supporter_root);

    -- 3. The deleted rows (D1, D9).
    PERFORM public.erase_account_insert_deleted_rows(account, student_root, supporter_root);

    -- 4. Move the keys.
    PERFORM public.erase_account_move_keys();

    -- 5. What the account owns, then erase it.
    PERFORM public.erase_account_collect_owned();
    PERFORM public.erase_account_erase_owned();

    -- 6. D10's rows.
    PERFORM public.erase_account_delete_keyed(account, student_roots, supporter_roots);
    PERFORM public.erase_account_delete_jobs(user_roots || student_roots || supporter_roots, token_hashes, addresses);

    -- 7. Close the declaration.
    PERFORM pg_catalog.set_config('unicoach.erasure_account', '', true);

    -- 8. One row per table with a non-zero count.
    RETURN QUERY SELECT * FROM public.erase_account_tally();
END;
$$;

-- ---------------------------------------------------------------------------
-- erase_account's steps. Each runs inside erase_account's declaration, on the
-- temp tables erase_account_prepare_walk creates; none is meant to be called on
-- its own.
-- ---------------------------------------------------------------------------

-- erase_account_tables: the three account tables, each with its deleted
-- sibling (RFC 264). A table's roots are found by the table itself, in
-- erasure_walk_roots (erase_account_record_roots), never by a position.
CREATE FUNCTION public.erase_account_tables()
RETURNS TABLE (
    account_table regclass, -- users, students or supporters
    deleted_table regclass  -- its deleted_* sibling
)
LANGUAGE sql
STABLE
AS $$
    VALUES ('public.users'::regclass, 'public.deleted_users'::regclass),
           ('public.students'::regclass, 'public.deleted_students'::regclass),
           ('public.supporters'::regclass, 'public.deleted_supporters'::regclass)
$$;

-- erase_account_one_root: the account's one row of a students-or-supporters
-- table, or NULL when it has none. More than one raises with CONSTRAINT
-- erase_account_one_root: the walk erases one row per account table, and a
-- surplus row would be neither moved, collected nor erased, and not reported.
CREATE FUNCTION public.erase_account_one_root(
    account       uuid,     -- the users root
    account_table regclass, -- the table the candidates are rows of
    candidates    uuid[]    -- its ids whose user_id or deleted_user_id is the account
)
RETURNS uuid
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF pg_catalog.cardinality(candidates) > 1 THEN
        RAISE EXCEPTION 'erase_account: account [%] has [%] rows in [%]; expected at most one',
            account, pg_catalog.cardinality(candidates), account_table
            USING ERRCODE = 'P0001', CONSTRAINT = 'erase_account_one_root';
    END IF;
    RETURN candidates[1];
END;
$$;

-- erase_account_record_roots (step 2): each root under the account table it is
-- a row of, in erasure_walk_roots. Every account table erase_account_tables
-- names must be handled here by name; one that is not raises, rather than
-- leaving its keys unmoved and its rows unclaimed.
CREATE FUNCTION public.erase_account_record_roots(
    account        uuid, -- the users root
    student_root   uuid, -- the students root, or NULL
    supporter_root uuid  -- the supporters root, or NULL
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    acct record;
    root uuid;
BEGIN
    FOR acct IN SELECT a.account_table FROM public.erase_account_tables() a LOOP
        CASE acct.account_table
            WHEN 'public.users'::regclass THEN root := account;
            WHEN 'public.students'::regclass THEN root := student_root;
            WHEN 'public.supporters'::regclass THEN root := supporter_root;
            ELSE
                RAISE EXCEPTION 'erase_account: no root is resolved for account table [%]', acct.account_table
                    USING ERRCODE = 'P0001';
        END CASE;
        CONTINUE WHEN root IS NULL;
        INSERT INTO pg_temp.erasure_walk_roots (account_table, root) VALUES (acct.account_table, root);
    END LOOP;
END;
$$;

-- erase_account_count: add one step's row counts to a table's tally. A row of
-- all zeros is harmless: the tally reports only non-zero rows.
CREATE FUNCTION public.erase_account_count(
    counted      regclass, -- the table
    moved_rows   bigint,
    erased_rows  bigint,
    deleted_rows bigint
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO pg_temp.erasure_walk_counts AS k (rel, n_moved, n_erased, n_deleted)
    VALUES (counted, moved_rows, erased_rows, deleted_rows)
    ON CONFLICT (rel) DO UPDATE
       SET n_moved = k.n_moved + EXCLUDED.n_moved,
           n_erased = k.n_erased + EXCLUDED.n_erased,
           n_deleted = k.n_deleted + EXCLUDED.n_deleted;
END;
$$;

-- erase_account_addresses (step 1): every address the account has held,
-- current and historical, lower-cased.
CREATE FUNCTION public.erase_account_addresses(account uuid)
RETURNS text[]
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    addresses text[];
BEGIN
    SELECT pg_catalog.array_agg(DISTINCT pg_catalog.lower(e.address)) INTO addresses
      FROM (
        SELECT u.email::text AS address FROM public.users u WHERE u.id = account
        UNION ALL
        SELECT v.email::text FROM public.users_versions v WHERE v.id = account
      ) e
     WHERE e.address IS NOT NULL;
    RETURN COALESCE(addresses, '{}');
END;
$$;

-- erase_account_token_hashes (step 1): the account's session token hashes, in
-- the base64 form a job payload carries them.
CREATE FUNCTION public.erase_account_token_hashes(account uuid)
RETURNS text[]
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    token_hashes text[];
BEGIN
    SELECT pg_catalog.array_agg(pg_catalog.encode(s.token_hash, 'base64')) INTO token_hashes
      FROM public.sessions s WHERE s.user_id = account;
    RETURN COALESCE(token_hashes, '{}');
END;
$$;

-- erase_account_prepare_walk (step 2): an empty set of the walk's temp tables,
-- then the two catalog snapshots the walk reads.
CREATE FUNCTION public.erase_account_prepare_walk()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM public.erase_account_create_walk_tables();
    PERFORM public.erase_account_snapshot_tables();
    PERFORM public.erase_account_snapshot_pairs();
END;
$$;

-- erase_account_create_walk_tables (step 2): create the walk's temp tables
-- once per transaction (ON COMMIT DROP) and empty them, so a second call in the
-- same transaction starts clean.
CREATE FUNCTION public.erase_account_create_walk_tables()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_catalog.to_regclass('pg_temp.erasure_walk_rows') IS NULL THEN
        CREATE TEMP TABLE erasure_walk_counts (
            rel       regclass PRIMARY KEY,
            n_moved   bigint NOT NULL DEFAULT 0,
            n_erased  bigint NOT NULL DEFAULT 0,
            n_deleted bigint NOT NULL DEFAULT 0
        ) ON COMMIT DROP;
        -- One row per public table: its qualified name, its erase key (a single-
        -- column primary key, else a history table's entity id), and whether it
        -- carries enforce_versioning.
        CREATE TEMP TABLE erasure_walk_tables (
            rel         regclass PRIMARY KEY,
            qualified   text NOT NULL,
            row_key     name,
            row_key_type text,
            versioned   boolean NOT NULL,
            erasable    boolean NOT NULL
        ) ON COMMIT DROP;
        -- Every live key onto an account table with its CHECK-paired sibling.
        CREATE TEMP TABLE erasure_walk_pairs (
            child          regclass NOT NULL,
            live_column    name NOT NULL,
            sibling_column name NOT NULL,
            account_table  regclass NOT NULL
        ) ON COMMIT DROP;
        -- The account's roots, each under the account table it is a row of.
        CREATE TEMP TABLE erasure_walk_roots (
            account_table regclass NOT NULL,
            root          uuid     NOT NULL,
            PRIMARY KEY (account_table, root)
        ) ON COMMIT DROP;
        -- The rows the account owns, by erase key, with the round that reached them.
        CREATE TEMP TABLE erasure_walk_rows (
            rel     regclass NOT NULL,
            row_key text NOT NULL,
            depth   integer NOT NULL,
            PRIMARY KEY (rel, row_key)
        ) ON COMMIT DROP;
    END IF;
    TRUNCATE pg_temp.erasure_walk_counts, pg_temp.erasure_walk_tables,
             pg_temp.erasure_walk_pairs, pg_temp.erasure_walk_roots,
             pg_temp.erasure_walk_rows;
END;
$$;

-- erase_account_snapshot_tables (step 2): one row per public table in
-- erasure_walk_tables: its erase key (a single-column primary key, else a
-- history table's entity id), whether it carries enforce_versioning, and
-- whether it holds anything soft_delete_entity would change.
CREATE FUNCTION public.erase_account_snapshot_tables()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO pg_temp.erasure_walk_tables (rel, qualified, row_key, row_key_type, versioned, erasable)
    SELECT c.oid::regclass,
           pg_catalog.format('%I.%I', n.nspname, c.relname),
           k.attname,
           k.type_name,
           EXISTS (
               SELECT 1 FROM pg_catalog.pg_trigger tg
                 JOIN pg_catalog.pg_proc p ON p.oid = tg.tgfoid
                 JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                WHERE tg.tgrelid = c.oid AND NOT tg.tgisinternal
                  AND pn.nspname = 'public' AND p.proname = 'enforce_versioning'
           ),
           EXISTS (
               SELECT 1 FROM pg_catalog.pg_attribute a
                 JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                 JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
                WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                  AND (tn.nspname = 'pii' OR a.attname = 'deleted_at')
           )
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      LEFT JOIN LATERAL (
          SELECT key.attname, key.type_name
            FROM (
                SELECT 1 AS preference, a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type_name
                  FROM pg_catalog.pg_index i
                  JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                 WHERE i.indrelid = c.oid AND i.indisprimary AND i.indnkeyatts = 1
                UNION ALL
                SELECT 2, a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod)
                  FROM pg_catalog.pg_attribute a
                 WHERE a.attrelid = c.oid AND a.attname = 'id' AND a.attnum > 0 AND NOT a.attisdropped
            ) key
           ORDER BY key.preference
           LIMIT 1
      ) k ON TRUE
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p');
END;
$$;

-- erase_account_snapshot_pairs (step 2): every live key onto an account table
-- with the sibling a two-column CHECK pairs it with, in erasure_walk_pairs.
CREATE FUNCTION public.erase_account_snapshot_pairs()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO pg_temp.erasure_walk_pairs (child, live_column, sibling_column, account_table)
    SELECT live.child, live.child_column, sibling.child_column, live.parent
      FROM public.erasure_edge live
      JOIN public.erase_account_tables() acct ON acct.account_table = live.parent
      JOIN public.erasure_edge sibling
        ON sibling.child = live.child
       AND sibling.parent = acct.deleted_table
      JOIN pg_catalog.pg_attribute la ON la.attrelid = live.child AND la.attname = live.child_column
      JOIN pg_catalog.pg_attribute sa ON sa.attrelid = sibling.child AND sa.attname = sibling.child_column
     WHERE live.child_column IS NOT NULL AND sibling.child_column IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM pg_catalog.pg_constraint chk
            WHERE chk.conrelid = live.child AND chk.contype = 'c'
              AND pg_catalog.cardinality(chk.conkey) = 2
              AND la.attnum = ANY (chk.conkey) AND sa.attnum = ANY (chk.conkey)
       );
END;
$$;

-- erase_account_insert_deleted_rows (step 3): the deleted rows (D1, D9), one
-- per root, each with the root's own id.
CREATE FUNCTION public.erase_account_insert_deleted_rows(
    account        uuid, -- the users root
    student_root   uuid, -- the students root, or NULL
    supporter_root uuid  -- the supporters root, or NULL
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO public.deleted_users (id, created_at, closed_at)
    SELECT u.id, u.created_at, u.deleted_at FROM public.users u WHERE u.id = account
    ON CONFLICT (id) DO NOTHING;
    IF student_root IS NOT NULL THEN
        INSERT INTO public.deleted_students (id, user_id) VALUES (student_root, account)
        ON CONFLICT (id) DO NOTHING;
    END IF;
    IF supporter_root IS NOT NULL THEN
        INSERT INTO public.deleted_supporters (id, user_id) VALUES (supporter_root, account)
        ON CONFLICT (id) DO NOTHING;
    END IF;
END;
$$;

-- erase_account_move_keys (step 4): every live key onto a root moves onto its
-- sibling, on every row that carries it. A key's roots are those recorded
-- under the account table it points at.
CREATE FUNCTION public.erase_account_move_keys()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    pair    record;
    roots   uuid[];
    changed bigint;
BEGIN
    FOR pair IN
        SELECT p.*, t.qualified, t.versioned
          FROM pg_temp.erasure_walk_pairs p
          JOIN pg_temp.erasure_walk_tables t ON t.rel = p.child
         ORDER BY p.child::text, p.live_column
    LOOP
        roots := ARRAY(
            SELECT w.root FROM pg_temp.erasure_walk_roots w
             WHERE w.account_table = pair.account_table);
        CONTINUE WHEN pg_catalog.cardinality(roots) = 0;
        EXECUTE pg_catalog.format(
            'UPDATE %s SET %I = %I, %I = NULL%s WHERE %I = ANY ($1)',
            pair.qualified, pair.sibling_column, pair.live_column, pair.live_column,
            CASE WHEN pair.versioned THEN ', version = version + 1' ELSE '' END,
            pair.live_column
        ) USING roots;
        GET DIAGNOSTICS changed = ROW_COUNT;
        PERFORM public.erase_account_count(pair.child, changed, 0, 0);
    END LOOP;
END;
$$;

-- erase_account_collect_owned (step 5): the rows the account owns, from the
-- roots outward over erasure_edge to a fixpoint, into erasure_walk_rows. The
-- rules are erase_account's WHAT THE ACCOUNT OWNS, above. Each round takes
-- every walkable edge whose source table holds a collected row, and steps over
-- it; the walk ends on the first round that adds nothing.
CREATE FUNCTION public.erase_account_collect_owned()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    edge       public.erasure_edge;
    walk_round integer := 0;
    added      bigint;
BEGIN
    INSERT INTO pg_temp.erasure_walk_rows (rel, row_key, depth)
    SELECT w.account_table, w.root::text, 0 FROM pg_temp.erasure_walk_roots w;

    LOOP
        walk_round := walk_round + 1;
        added := 0;
        FOR edge IN
            SELECT e.* FROM public.erase_account_walk_edges() e
             ORDER BY e.child::text, e.child_column, e.parent::text
        LOOP
            -- A child-ward step leaves the parent; a forward (owned) step the child.
            CONTINUE WHEN NOT public.erase_account_has_collected(
                CASE WHEN edge.owned THEN edge.child ELSE edge.parent END);
            added := added + public.erase_account_step(edge, walk_round);
        END LOOP;
        EXIT WHEN added = 0;
    END LOOP;
END;
$$;

-- erase_account_walk_edges (step 5): the edges the walk may step over, which
-- is every erasure_edge except those onto or out of a deleted_* table and
-- those into D10's keyed tables. Those tables' rows erase_account_delete_keyed
-- deletes outright, so the walk does not enter them; ErasureClassificationTest's
-- NOT_KEYED carries the same reason for each. jobs carries no foreign key and
-- is matched by payload (erase_account_delete_jobs).
CREATE FUNCTION public.erase_account_walk_edges()
RETURNS SETOF public.erasure_edge
LANGUAGE sql
STABLE
AS $$
    SELECT e.*
      FROM public.erasure_edge e
     WHERE NOT EXISTS (
               SELECT 1 FROM public.erase_account_tables() acct
                WHERE acct.deleted_table IN (e.child, e.parent)
           )
       AND NOT (e.child = ANY (ARRAY[
               'public.sessions'::regclass,
               'public.verification_tokens'::regclass,
               'public.user_auth_identities'::regclass,
               'public.supporter_invites'::regclass
           ]))
$$;

-- erase_account_has_collected (step 5): whether erasure_walk_rows holds a row
-- of the table.
CREATE FUNCTION public.erase_account_has_collected(collected_in regclass)
RETURNS boolean
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM pg_temp.erasure_walk_rows r WHERE r.rel = collected_in);
END;
$$;

-- erase_account_step (step 5): one step over one edge. Collects into
-- erasure_walk_rows every row of the step's target table that the edge reaches
-- from a collected row and that the ownership rule admits; returns how many it
-- added. A key over more than one column raises; a target with no single key
-- adds nothing (erase_account_check_keyless_target).
CREATE FUNCTION public.erase_account_step(
    edge       public.erasure_edge, -- the edge stepped over
    walk_round integer              -- the round, recorded as the rows' depth
)
RETURNS bigint                      -- rows added
LANGUAGE plpgsql
AS $$
DECLARE
    target  record; -- the table the step reaches into
    item    record; -- the table the step leaves
    changed bigint;
BEGIN
    IF edge.child_column IS NULL THEN
        RAISE EXCEPTION 'erase_account: the walk reaches [%] -> [%] over a foreign key of more than one column',
            edge.child, edge.parent
            USING ERRCODE = 'P0001';
    END IF;

    SELECT t.* INTO target FROM pg_temp.erasure_walk_tables t
     WHERE t.rel = CASE WHEN edge.owned THEN edge.parent ELSE edge.child END;
    IF target.row_key IS NULL THEN
        PERFORM public.erase_account_check_keyless_target(target.rel, target.erasable);
        RETURN 0;
    END IF;
    SELECT t.* INTO item FROM pg_temp.erasure_walk_tables t
     WHERE t.rel = CASE WHEN edge.owned THEN edge.child ELSE edge.parent END;

    EXECUTE pg_catalog.format(
        'INSERT INTO pg_temp.erasure_walk_rows (rel, row_key, depth) '
        'SELECT %L::regclass, t.%I::text, %s FROM %s t WHERE %s AND %s '
        'ON CONFLICT (rel, row_key) DO NOTHING',
        target.rel, target.row_key, walk_round, target.qualified,
        public.erase_account_match_sql(edge, item.qualified, item.row_key, item.row_key_type),
        public.erase_account_ownership_sql(target.rel)
    );
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$;

-- erase_account_check_keyless_target (step 5): a table with no single key to
-- erase it by is acceptable only as a leaf that holds nothing to erase
-- (claim_support and its siblings); anything else raises.
CREATE FUNCTION public.erase_account_check_keyless_target(
    target   regclass, -- the table the walk reached
    erasable boolean   -- whether it holds a pii.* column or deleted_at
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF erasable OR EXISTS (
        SELECT 1 FROM public.erasure_edge e WHERE e.parent = target AND NOT e.owned
    ) THEN
        RAISE EXCEPTION 'erase_account: the walk reaches [%], which has no single-column key to erase it by',
            target
            USING ERRCODE = 'P0001';
    END IF;
END;
$$;

-- erase_account_match_sql (step 5): the predicate, over the target aliased t,
-- for the rows one step over edge reaches from the collected rows of the
-- table it leaves (item). A forward (owned) step matches the parent key the
-- collected children point at; a child-ward step matches the child key that
-- points at a collected parent, and, where step 4 has moved that key onto its
-- sibling, the sibling too, whose value is the same id (D9).
CREATE FUNCTION public.erase_account_match_sql(
    edge              public.erasure_edge, -- the edge stepped over
    item_qualified    text,                -- the table the step leaves, qualified
    item_row_key      name,                -- its erase key
    item_row_key_type text                 -- that key's type
)
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    reached     text;
    sibling_key name;
BEGIN
    IF edge.owned THEN
        RETURN pg_catalog.format(
            't.%I IN (SELECT f.%I FROM %s f WHERE f.%I IN '
            '(SELECT r.row_key::%s FROM pg_temp.erasure_walk_rows r WHERE r.rel = %L::regclass))',
            edge.parent_column, edge.child_column, item_qualified, item_row_key,
            item_row_key_type, edge.child
        );
    END IF;

    reached := pg_catalog.format(
        '(SELECT f.%I FROM %s f WHERE f.%I IN '
        '(SELECT r.row_key::%s FROM pg_temp.erasure_walk_rows r WHERE r.rel = %L::regclass))',
        edge.parent_column, item_qualified, item_row_key, item_row_key_type, edge.parent
    );
    SELECT p.sibling_column INTO sibling_key FROM pg_temp.erasure_walk_pairs p
     WHERE p.child = edge.child AND p.live_column = edge.child_column;
    IF NOT FOUND THEN
        RETURN pg_catalog.format('t.%I IN %s', edge.child_column, reached);
    END IF;
    RETURN pg_catalog.format('(t.%I IN %s OR t.%I IN %s)',
        edge.child_column, reached, sibling_key, reached);
END;
$$;

-- erase_account_ownership_sql (step 5): the ownership rule (D-A) for one
-- target table, as a predicate over the target aliased t: every account key
-- the row carries, live or sibling, is NULL or names one of the roots recorded
-- under the account table that key points at. On convos a student key is
-- exempt where the party is the supporter, since it names whom the
-- conversation is about, not who wrote it (brief 0014 D9). TRUE for a table
-- that carries no account key.
CREATE FUNCTION public.erase_account_ownership_sql(target regclass)
RETURNS text
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    owner_sql text;
BEGIN
    SELECT pg_catalog.string_agg(
               CASE
                   WHEN target = 'public.convos'::regclass
                    AND o.account_table = 'public.students'::regclass
                   THEN pg_catalog.format('(t.party = %L OR %s)', 'supporter', o.names_root)
                   ELSE o.names_root
               END,
               ' AND ' ORDER BY o.child_column)
      INTO owner_sql
      FROM (
          SELECT e.child_column,
                 acct.account_table,
                 pg_catalog.format(
                     '(t.%I IS NULL OR t.%I IN (SELECT w.root FROM pg_temp.erasure_walk_roots w '
                     'WHERE w.account_table = %L::regclass))',
                     e.child_column, e.child_column, acct.account_table
                 ) AS names_root
            FROM public.erasure_edge e
            JOIN public.erase_account_tables() acct
              ON e.parent IN (acct.account_table, acct.deleted_table)
           WHERE e.child = target AND e.child_column IS NOT NULL
      ) o;
    RETURN COALESCE(owner_sql, 'TRUE');
END;
$$;

-- erase_account_erase_owned (step 5): soft_delete_entity on every collected
-- row, table by table in breadth-first order from the roots.
CREATE FUNCTION public.erase_account_erase_owned()
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    item    record;
    changed bigint;
BEGIN
    FOR item IN
        SELECT r.rel, t.row_key, pg_catalog.min(r.depth) AS depth
          FROM pg_temp.erasure_walk_rows r
          JOIN pg_temp.erasure_walk_tables t ON t.rel = r.rel
         GROUP BY r.rel, t.row_key
         ORDER BY 3, r.rel::text
    LOOP
        SELECT pg_catalog.sum(public.soft_delete_entity(item.rel, item.row_key, r.row_key))
          INTO changed
          FROM pg_temp.erasure_walk_rows r WHERE r.rel = item.rel;
        PERFORM public.erase_account_count(item.rel, 0, changed, 0);
    END LOOP;
END;
$$;

-- erase_account_delete_keyed (step 6): D10's rows keyed on the account or a
-- root, deleted outright.
CREATE FUNCTION public.erase_account_delete_keyed(
    account         uuid,
    student_roots   uuid[],
    supporter_roots uuid[]
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    changed bigint;
BEGIN
    DELETE FROM public.sessions s WHERE s.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.sessions'::regclass, 0, 0, changed);

    DELETE FROM public.verification_tokens v WHERE v.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.verification_tokens'::regclass, 0, 0, changed);

    DELETE FROM public.user_auth_identities a WHERE a.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.user_auth_identities'::regclass, 0, 0, changed);

    DELETE FROM public.supporter_invites i
     WHERE i.inviter_user_id = account
        OR i.student_id = ANY (student_roots)
        OR i.supporter_id = ANY (supporter_roots);
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.supporter_invites'::regclass, 0, 0, changed);
END;
$$;

-- erase_account_delete_jobs (step 6): the jobs naming the account (§4,
-- decision D-F), with their attempts: a payload holding, at any depth, a JSON
-- string equal to a root id, an erased conversation's id, a session token
-- hash, or (lower-cased) one of its addresses. The matching jobs are chosen
-- once, so the attempts and the jobs deleted are the same set.
CREATE FUNCTION public.erase_account_delete_jobs(
    roots        uuid[], -- every root id
    token_hashes text[], -- erase_account_token_hashes
    addresses    text[]  -- erase_account_addresses
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    identifiers text[];
    job_ids     uuid[];
    changed     bigint;
BEGIN
    identifiers := roots::text[]
        || ARRAY(SELECT r.row_key FROM pg_temp.erasure_walk_rows r WHERE r.rel = 'public.convos'::regclass)
        || token_hashes;
    job_ids := ARRAY(
        SELECT j.id FROM public.jobs j
         WHERE EXISTS (
             SELECT 1 FROM pg_catalog.jsonb_path_query(j.payload, 'strict $.**') AS v (value)
              WHERE pg_catalog.jsonb_typeof(v.value) = 'string'
                AND ((v.value #>> '{}') = ANY (identifiers)
                     OR pg_catalog.lower(v.value #>> '{}') = ANY (addresses))
         )
    );

    DELETE FROM public.job_attempts a WHERE a.job_id = ANY (job_ids);
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.job_attempts'::regclass, 0, 0, changed);

    DELETE FROM public.jobs j WHERE j.id = ANY (job_ids);
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.jobs'::regclass, 0, 0, changed);
END;
$$;

-- erase_account_tally (step 8): one row per table with a non-zero count.
CREATE FUNCTION public.erase_account_tally()
RETURNS TABLE (
    table_name text,
    moved      bigint,
    erased     bigint,
    deleted    bigint
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
        SELECT c.relname::text, k.n_moved, k.n_erased, k.n_deleted
          FROM pg_temp.erasure_walk_counts k
          JOIN pg_catalog.pg_class c ON c.oid = k.rel
         WHERE k.n_moved + k.n_erased + k.n_deleted > 0
         ORDER BY c.relname;
END;
$$;
