-- erasure_edge: every foreign key in public, as the edge set an erasure walk
-- runs over (RFC 266, slice deletion/03/the-walk, brief 0002 D3).
--
-- WHY. Two things must agree on what an erasure can reach from an account: the
-- drift guard (ErasureClassificationTest), which proves every personal column is
-- in the reach, and the walk (erase_account, erasure-functions.sql), which
-- erases it. Each holding its own catalog query is two copies of one fact, and
-- a guard that proves a reach the walk does not take proves nothing. So the
-- edge set lives here, once, and both read it.
--
-- One row per foreign key. A child-ward step runs parent -> child on every
-- edge; a forward step runs child -> parent on an `owned` edge only (the key's
-- type lives in the owned schema, RFC 263). Read live from the catalog, so a
-- key added next year is an edge the moment it exists.
--
-- A key over more than one column appears with child_column and parent_column
-- NULL and owned FALSE: the walk refuses such an edge rather than guess which
-- column pairs with which (none exists today). Types are matched by the NAME of
-- their schema, never by a cast, so the view reads the same before and after
-- any one domain exists.

CREATE VIEW public.erasure_edge AS
SELECT
    con.conrelid::regclass  AS child,
    CASE WHEN cardinality(con.conkey) = 1 THEN ca.attname END AS child_column,
    con.confrelid::regclass AS parent,
    CASE WHEN cardinality(con.confkey) = 1 THEN pa.attname END AS parent_column,
    COALESCE(cardinality(con.conkey) = 1 AND tn.nspname = 'owned', FALSE) AS owned
FROM pg_catalog.pg_constraint con
JOIN pg_catalog.pg_class cc ON cc.oid = con.conrelid
JOIN pg_catalog.pg_namespace cn ON cn.oid = cc.relnamespace
LEFT JOIN pg_catalog.pg_attribute ca
    ON ca.attrelid = con.conrelid AND ca.attnum = con.conkey[1]
LEFT JOIN pg_catalog.pg_attribute pa
    ON pa.attrelid = con.confrelid AND pa.attnum = con.confkey[1]
LEFT JOIN pg_catalog.pg_type t ON t.oid = ca.atttypid
LEFT JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
WHERE con.contype = 'f'
  AND cn.nspname = 'public';
