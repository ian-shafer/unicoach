-- subscriptions: the server-side state of record for one Apple auto-renewable
-- subscription (RFC 110), keyed by Apple's originalTransactionId (stable across
-- renewals) and bound to the student who verified it. The budget gate's
-- subscribed branch reads the current entitling row's [period_start,
-- period_end) window as the subscription meter's bounds.
--
-- The entity is VERSIONED (the users/colleges mechanism: shared
-- enforce_versioning() plus a per-table history writer): the gate blocks on
-- what this row said at the time, so an entitlement dispute ("I paid and was
-- 402'd") is answered from the row's timeline — Apple's records can replay
-- Apple's belief, only versions preserve ours. The version bump is DAO-supplied
-- in SubscriptionsDao.upsert's conflict arm, never caller-supplied — versions
-- are an audit trail here, not caller-facing concurrency control.
--
-- No UNIQUE(student_id): a student who lapses and later resubscribes under a
-- different Apple ID gets a second row (new originalTransactionId);
-- SubscriptionsDao.findCurrent disambiguates. student_id rebinding is refused
-- at the DAO, not by trigger.
--
-- Reuses the shared guard functions (prevent_physical_delete,
-- prevent_immutable_updates, prevent_physical_timestamp_update,
-- update_timestamp, enforce_versioning) with one new function, the history
-- writer log_subscription_version(), modeled verbatim on log_user_version().

CREATE TABLE subscriptions (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- Apple's stable per-subscription identity; renewals keep it, so it is the
  -- upsert key. One Apple subscription maps to at most one student, ever.
  original_transaction_id TEXT NOT NULL,
  product_id              TEXT NOT NULL,

  -- Apple's status enum, snake_cased: 1=active, 2=expired, 3=billing_retry,
  -- 4=grace, 5=revoked. Entitling statuses are active + grace.
  status TEXT NOT NULL,

  -- The current entitlement window: the latest transaction's purchaseDate →
  -- expiresDate (grace: gracePeriodExpiresDate). Also the subscription meter's
  -- windowedCost bounds and the usage bar's reset date.
  period_start TIMESTAMPTZ NOT NULL,
  period_end   TIMESTAMPTZ NOT NULL,

  CONSTRAINT subscriptions_original_transaction_id_unique UNIQUE (original_transaction_id),
  CONSTRAINT subscriptions_original_transaction_id_length_check CHECK (length(original_transaction_id) <= 255),
  CONSTRAINT subscriptions_original_transaction_id_not_empty_check CHECK (length(original_transaction_id) > 0),
  CONSTRAINT subscriptions_product_id_length_check CHECK (length(product_id) <= 255),
  CONSTRAINT subscriptions_product_id_not_empty_check CHECK (length(product_id) > 0),
  CONSTRAINT subscriptions_status_check CHECK (status IN ('active','expired','billing_retry','grace','revoked')),
  -- Also guards StudentLlmCostDao.windowedCost's periodStart < periodEnd require.
  CONSTRAINT subscriptions_period_check CHECK (period_start < period_end)
);

-- The gate's read: the student's current entitling subscription.
CREATE INDEX subscriptions_student_current_idx
  ON subscriptions (student_id, period_end) WHERE status IN ('active','grace');
-- Admin/debug listing per student.
CREATE INDEX subscriptions_student_idx ON subscriptions (student_id, created_at);

-- History: every committed state the row has taken (the insert and each real
-- update), mirroring users_versions/colleges_versions — unique
-- original_transaction_id relaxed, (id, version) is the PK and the only index.
CREATE TABLE subscriptions_versions (
  id      UUID    NOT NULL REFERENCES subscriptions(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  student_id UUID NOT NULL,
  original_transaction_id TEXT NOT NULL,
  product_id              TEXT NOT NULL,
  status TEXT NOT NULL,
  period_start TIMESTAMPTZ NOT NULL,
  period_end   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (id, version)
);

-- The per-table history writer, modeled verbatim on log_user_version(): logs
-- the finalized row AFTER each insert or real update (the state-distinct guard
-- in SubscriptionsDao.upsert keeps no-op refreshes from firing an UPDATE at
-- all, so no history churn is written for them).
CREATE OR REPLACE FUNCTION log_subscription_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO subscriptions_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        student_id, original_transaction_id, product_id, status, period_start, period_end
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at,
        NEW.student_id, NEW.original_transaction_id, NEW.product_id, NEW.status, NEW.period_start, NEW.period_end
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- BEFORE triggers execute in alphabetical order by name if not specified.
CREATE TRIGGER trigger_00_prevent_subscriptions_physical_delete
BEFORE DELETE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_subscriptions_immutable_updates
BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();
CREATE TRIGGER trigger_01_enforce_subscriptions_versioning
BEFORE INSERT OR UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();
CREATE TRIGGER trigger_03_enforce_subscriptions_updated_at
BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

-- AFTER trigger to log the finalized row.
CREATE TRIGGER trigger_04_log_subscription_version
AFTER INSERT OR UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE log_subscription_version();
