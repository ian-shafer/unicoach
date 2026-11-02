-- Expression indexes that give the per-account spend meter its index scans back
-- (RFC 266 §7, slice deletion/03/the-walk, decision D-G).
--
-- WHY. spend-views-read-deleted-keys.sql (RFC 264) made the spend views read
-- each owner key as the live-or-deleted pair, so a row's spend keeps its id
-- after erasure moves the key (D9): account_llm_cost filters on
-- COALESCE(st.user_id, st.deleted_user_id) and COALESCE(sp.user_id,
-- sp.deleted_user_id), and supporter_llm_cost joins on
-- COALESCE(slc.supporter_id, slc.deleted_supporter_id). No index on either
-- column alone serves that expression, so the meter -- read on every coaching
-- turn -- lost its index scans. An index on the same expression restores them.
--
-- Each expression matches its view's exactly; if a view's expression is
-- rewritten, the planner stops using its index, and SpendViewIndexTest fails.
--
-- Not partial and not unique: every row has exactly one of the pair set (the
-- pair CHECKs), so the expression is never NULL and the index covers every row;
-- uniqueness is already carried by the live unique indexes and by
-- deleted_students.user_id / deleted_supporters.user_id.

CREATE INDEX students_account_idx            ON students            ((COALESCE(user_id, deleted_user_id)));
CREATE INDEX supporters_account_idx          ON supporters          ((COALESCE(user_id, deleted_user_id)));
CREATE INDEX supporter_llm_costs_account_idx ON supporter_llm_costs ((COALESCE(supporter_id, deleted_supporter_id)));
