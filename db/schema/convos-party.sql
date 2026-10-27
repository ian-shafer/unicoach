-- convos gains a party and an author: who a conversation is WITH, beside whom
-- it is ABOUT. RFC 258, product slice family/06/the-parent-coach (brief 0014).
--
-- WHY. A linked parent now holds coach conversations of their own about the
-- student they support. `convos.student_id` keeps its meaning of "about whom",
-- so the cost spine and every index on it survive (brief 0014 X9); what is new
-- is WHO the conversation is with. Party lives here and not on
-- `convo_requests.kind`, which is the tool-result loop's discriminator (X11).
--
-- WHAT IT WOULD COST WITHOUT THIS. A parent conversation stored as a plain
-- `convos` row would be billed to the student by `student_llm_cost` and would be
-- listed, read and extracted as the student's own -- the student's coach would
-- distil the parent's words into the student's claims.

-- The default exists only to backfill the rows that exist today, every one of
-- which is the student's own conversation. It is dropped at once, so every
-- insert from here on names its party.
ALTER TABLE convos ADD COLUMN party TEXT NOT NULL DEFAULT 'student';
ALTER TABLE convos ALTER COLUMN party DROP DEFAULT;
ALTER TABLE convos ADD CONSTRAINT convos_party_check
  CHECK (party IN ('student', 'supporter'));

-- The author on the adult side. No ON DELETE action, like every reference to
-- `supporters`: that table refuses a physical delete, so there is nothing for a
-- cascade to follow.
ALTER TABLE convos ADD COLUMN supporter_id UUID NULL REFERENCES supporters(id);
-- A supporter conversation has an author and a student conversation has none:
-- the two columns are one fact, and this is what keeps them one.
ALTER TABLE convos ADD CONSTRAINT convos_party_author_check
  CHECK ((party = 'supporter') = (supporter_id IS NOT NULL));

-- The parent's list read: this supporter's conversations, by student.
CREATE INDEX convos_supporter_id_idx ON convos (supporter_id, student_id)
  WHERE supporter_id IS NOT NULL;

-- party, supporter_id and student_id are frozen after insert. A conversation
-- that changed whose it was would carry one party's words into the other's
-- list, cost and memory, which is the exact leak this migration closes.
CREATE OR REPLACE FUNCTION prevent_convos_party_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.party IS DISTINCT FROM OLD.party THEN
        RAISE EXCEPTION 'The party field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'party',
                  DETAIL = format('convos row [%s] party [%s] -> [%s]', OLD.id, OLD.party, NEW.party);
    END IF;
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'The supporter_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'supporter_id',
                  DETAIL = format('convos row [%s] supporter_id [%s] -> [%s]', OLD.id, OLD.supporter_id, NEW.supporter_id);
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'student_id',
                  DETAIL = format('convos row [%s] student_id [%s] -> [%s]', OLD.id, OLD.student_id, NEW.student_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 00c: after 00a (the shared immutable guard) and 00b (row_created_at, 0023),
-- before 03 (updated_at) -- the sharing_permissions order.
CREATE TRIGGER trigger_00c_prevent_convos_party_updates
BEFORE UPDATE ON convos
FOR EACH ROW EXECUTE PROCEDURE prevent_convos_party_updates();

-- ---------------------------------------------------------------------------
-- student_llm_cost -- 0041's body, with the convo_requests arm narrowed to the
-- student's own conversations.
--
-- A supporter's conversation is ABOUT the student but is not the student's
-- spend: its calls are attributed to the supporter through
-- `supporter_llm_costs`, which `account_llm_cost` re-keys onto the supporter's
-- account. Without this predicate the same call would be counted twice, once
-- on each side of the house.
--
-- The column list is unchanged, so CREATE OR REPLACE leaves `account_llm_cost`
-- (which selects from this view) standing. Every other arm is 0041's verbatim.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW student_llm_cost AS
WITH per_call AS (
  SELECT c.student_id, cr.llm_request_id
  FROM convo_requests cr JOIN convos c ON c.id = cr.convo_id
  WHERE c.party = 'student'
  UNION ALL
  SELECT er.student_id, er.llm_request_id FROM extraction_runs er
  UNION ALL
  SELECT sr.student_id, sr.llm_request_id FROM synthesis_runs sr
  UNION ALL
  SELECT flr.student_id, flr.query_llm_request_id AS llm_request_id FROM fit_lens_runs flr
  UNION ALL
  SELECT flr.student_id, flr.reason_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr WHERE flr.reason_llm_request_id IS NOT NULL
)
SELECT
  pc.student_id,
  resp.created_at,
  resp.cost_nanodollars,
  resp.cost_is_estimated,
  resp.input_tokens,
  resp.output_tokens,
  resp.cache_read_tokens,
  resp.cache_write_tokens
FROM per_call pc
JOIN llm_responses resp ON resp.request_id = pc.llm_request_id;
