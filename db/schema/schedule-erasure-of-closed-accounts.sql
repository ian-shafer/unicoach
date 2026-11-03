-- One ERASE_ACCOUNT job for every account closed before the close scheduled
-- its own (RFC 267 §3, slice deletion/04/the-sweep, decision D-E).
--
-- WHY. From RFC 267 on, AccountClosureService enqueues the erase job in the
-- close transaction. An account closed before that has no job, and would never
-- be erased. This gives each such account -- closed, and not erased -- the job
-- its close would have scheduled: the payload the close writes (the account id
-- and its users.deleted_at, ISO-8601), due 7 days after its own close.
--
-- The `7 days` is D2's N (accountErasure.graceDays in service.conf) written
-- once, for accounts closed before the setting existed; this migration runs
-- once and never reads the setting again. An account closed more than 7 days
-- before the deploy is due at once, and is erased on the first worker poll
-- after it (D-E).
--
-- closedAt is written in this session's time zone, with its offset; the
-- handler reads it as an instant, so the zone does not matter.

INSERT INTO jobs (job_type, payload, scheduled_at)
SELECT 'ERASE_ACCOUNT',
       jsonb_build_object('userId', u.id::text, 'closedAt', to_jsonb(u.deleted_at) #>> '{}'),
       u.deleted_at + INTERVAL '7 days'
  FROM users u
 WHERE u.deleted_at IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM deleted_users du WHERE du.id = u.id);
