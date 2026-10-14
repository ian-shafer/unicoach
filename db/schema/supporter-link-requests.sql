-- RFC 254: a parent's REQUEST to connect with the student whose Family Cost
-- Report link they opened (brief 0014 D19). Claiming from the report never links
-- anybody: it records a pending request here, and the student approves or
-- declines it from their own Family & Supporters list.
--
-- The supporter_invites shape (RFC 232): neither a versioned aggregate nor an
-- append-only log. A request is written once and resolved once, by a
-- compare-and-swap on `resolution IS NULL`, so it carries no version column and
-- no history table.
--
-- States, all derived from the columns:
--   pending   -- resolution IS NULL. At most one per pair (the partial unique
--                index below); resolved rows are history.
--   approved  -- a live link was made and link_id names it.
--   declined  -- the student said no. Final for the pair (RFC 254 decision 4).
--   cancelled -- withdrawn while pending: either account closed, or the report
--                link it came from (cost_report_share_id) was turned off.
--
-- notified_at records when the student's notice was DECIDED: the email was
-- enqueued, or the run decided not to send one (an opted-out, unverified or
-- closed student), so the notice is decided at most once. It is not proof that
-- an email went out. A request from an UNVERIFIED claimant is never notified
-- and never listed: anyone can register as somebody else's address.
--
-- Every endpoint CASCADES, stated on each side, for the reason supporter_invites
-- gives: a request is a pointer to the two parties and the report it came from,
-- and the cascades exist only for an operator's hard delete. App code closes
-- accounts by soft delete and cancels the pending requests itself.

CREATE TABLE supporter_link_requests (
  id                   UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  supporter_id         UUID        NOT NULL REFERENCES supporters(id) ON DELETE CASCADE,
  student_id           UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  cost_report_share_id UUID        NOT NULL REFERENCES cost_report_shares(id) ON DELETE CASCADE,
  -- when the student's notice was DECIDED, sent or not; see the header
  notified_at          TIMESTAMPTZ NULL,
  -- Owned by the Kotlin enum SupporterLinkRequestResolution
  -- (db/src/main/kotlin/ed/unicoach/db/models).
  resolution           TEXT        NULL,
  resolved_at          TIMESTAMPTZ NULL,
  link_id              UUID        NULL REFERENCES supporter_student_links(id),
  CONSTRAINT supporter_link_requests_resolution_check
    CHECK (resolution IN ('approved', 'declined', 'cancelled')),
  CONSTRAINT supporter_link_requests_resolved_pair_check
    CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
  CONSTRAINT supporter_link_requests_link_check
    CHECK ((link_id IS NOT NULL) = (resolution IS NOT DISTINCT FROM 'approved'))
);

-- at most one PENDING request per pair; resolved rows are history
CREATE UNIQUE INDEX supporter_link_requests_pending_pair_idx
  ON supporter_link_requests (supporter_id, student_id) WHERE resolution IS NULL;

-- the student's list of pending requests
CREATE INDEX supporter_link_requests_student_pending_idx
  ON supporter_link_requests (student_id) WHERE resolution IS NULL;

-- ---------------------------------------------------------------------------
-- Guards. A request is history the moment it exists: who asked, of whom, from
-- which report, and when are frozen, and the resolution is written once.
--
-- No physical delete. Not the shared prevent_physical_delete(), whose message
-- tells a reader to set a deleted_at this table does not have.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_supporter_link_request_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Supporter link request [%] cannot be deleted (resolution [%]); resolve it instead.',
        OLD.id, OLD.resolution USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

-- The shared prevent_immutable_updates() guards id and created_at only (0023),
-- so the business columns need this table's own function.
--
-- resolution, resolved_at and link_id change only FROM NULL, once: an approved
-- request cannot be re-pointed at another link, and a decline cannot be turned
-- into an approval behind the student's back. The resolving write sets all three
-- in one statement, so the CHECKs above hold at every row the table ever stores.
CREATE OR REPLACE FUNCTION prevent_supporter_link_request_column_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: supporter_id is immutable ([%] -> [%]).',
            OLD.id, OLD.supporter_id, NEW.supporter_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: student_id is immutable ([%] -> [%]).',
            OLD.id, OLD.student_id, NEW.student_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.cost_report_share_id IS DISTINCT FROM OLD.cost_report_share_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: cost_report_share_id is immutable ([%] -> [%]).',
            OLD.id, OLD.cost_report_share_id, NEW.cost_report_share_id USING ERRCODE = 'P0001';
    END IF;
    IF OLD.resolution IS NOT NULL AND (
        NEW.resolution IS DISTINCT FROM OLD.resolution OR
        NEW.resolved_at IS DISTINCT FROM OLD.resolved_at OR
        NEW.link_id IS DISTINCT FROM OLD.link_id
    ) THEN
        RAISE EXCEPTION
            'Supporter link request [%] is already resolved as [%] at [%] (link [%]); refused write: resolution [%], resolved_at [%], link [%].',
            OLD.id, OLD.resolution, OLD.resolved_at, OLD.link_id, NEW.resolution, NEW.resolved_at, NEW.link_id
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- No self-request: the supporters-and-student-links.sql self-link rule, at the
-- door before the link. It names its own CONSTRAINT so a DAO routes on the name,
-- never on the sentence.
CREATE OR REPLACE FUNCTION prevent_supporter_link_request_self()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM 1
       FROM supporters sup
       JOIN students stu ON stu.user_id = sup.user_id
      WHERE sup.id = NEW.supporter_id
        AND stu.id = NEW.student_id;

    IF FOUND THEN
        RAISE EXCEPTION
            'A student may not ask to connect with themselves: supporter [%] and student [%] are one user account.',
            NEW.supporter_id, NEW.student_id
            USING ERRCODE = 'P0001',
                  CONSTRAINT = 'supporter_link_requests_not_self';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_00_prevent_supporter_link_requests_delete
BEFORE DELETE ON supporter_link_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_link_request_delete();

CREATE TRIGGER trigger_00a_prevent_supporter_link_requests_immutable_updates
BEFORE UPDATE ON supporter_link_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON supporter_link_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_supporter_link_requests_column_updates
BEFORE UPDATE ON supporter_link_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_link_request_column_updates();

CREATE TRIGGER trigger_00d_prevent_supporter_link_requests_self
BEFORE INSERT OR UPDATE ON supporter_link_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_link_request_self();
