## Executive Summary

This specification defines the schema, behavior, and lifecycle management for
the `users` entity table in the PostgreSQL database. The users table serves as
the central identity record for the system, storing core profile data and
authentication state. We will utilize robust database-level constraints and
triggers to enforce immutability of specific fields and manage timestamp updates
automatically, ensuring strong data integrity independent of application logic.

## Detailed Design

### Entity Configuration

| Setting        | Selection | Implementation Requirement                                                   |
| :------------- | :-------- | :--------------------------------------------------------------------------- |
| **ID Type**    | `UUIDv7`  | Use native `uuidv7()` as the `id` column default                             |
| **Mutability** | Mutable   | Include `updated_at` with the admin-bypass trigger configuration             |
| **Timestamps** | Advanced  | Include `row_created_at` and `row_updated_at`                                |
| **Versioning** | Enabled   | Include `version` column and `users_versions` table                          |
| **Deletions**  | Logical   | Include `deleted_at` timestamp column and `BEFORE DELETE` prevention trigger |

### Table Schema

The table will use the 4-timestamp advanced pattern to support decoupled logical
vs physical timestamp updates.

```sql
CREATE TABLE users (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,

  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMPTZ NULL,

  -- Core Fields
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  display_name TEXT NULL,

  -- Authentication
  password_hash TEXT NULL,
  sso_provider_id TEXT NULL,

  -- Constraints
  CONSTRAINT users_email_length_check CHECK (length(email) <= 254),
  CONSTRAINT users_name_length_check CHECK (length(name) <= 255),
  CONSTRAINT users_display_name_length_check CHECK (display_name IS NULL OR length(display_name) <= 255),
  CONSTRAINT users_password_hash_length_check CHECK (password_hash IS NULL OR length(password_hash) <= 255),
  CONSTRAINT users_sso_provider_id_length_check CHECK (sso_provider_id IS NULL OR length(sso_provider_id) <= 255),
  CONSTRAINT users_email_lowercase_check CHECK (email = LOWER(email)),
  CONSTRAINT users_email_format_check CHECK (email LIKE '%@%'),
  CONSTRAINT users_name_not_empty_check CHECK (length(trim(name)) > 0),
  CONSTRAINT users_email_not_empty_check CHECK (length(trim(email)) > 0),
  CONSTRAINT users_display_name_not_empty_check CHECK (display_name IS NULL OR length(trim(display_name)) > 0),
  CONSTRAINT users_password_hash_not_empty_check CHECK (password_hash IS NULL OR length(trim(password_hash)) > 0),
  CONSTRAINT users_sso_provider_id_not_empty_check CHECK (sso_provider_id IS NULL OR length(trim(sso_provider_id)) > 0),
  CONSTRAINT users_name_trim_check CHECK (name = trim(name)),
  CONSTRAINT users_email_trim_check CHECK (email = trim(email)),
  CONSTRAINT users_display_name_trim_check CHECK (display_name IS NULL OR display_name = trim(display_name)),
  CONSTRAINT users_auth_method_check CHECK (password_hash IS NOT NULL OR sso_provider_id IS NOT NULL)
);

-- Ensure an email can only be uniquely registered once among active users
CREATE UNIQUE INDEX users_email_unique_active_idx ON users (email) WHERE deleted_at IS NULL;

-- Index for fast SSO logins
CREATE INDEX users_sso_provider_id_idx ON users (sso_provider_id) WHERE sso_provider_id IS NOT NULL;

-- Version History Table
CREATE TABLE users_versions (
  id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  display_name TEXT NULL,
  password_hash TEXT NULL,
  sso_provider_id TEXT NULL,
  PRIMARY KEY (id, version)
);

-- Index for efficient date-range timeline queries on a specific user's history
CREATE INDEX users_versions_id_updated_at_idx ON users_versions (id, updated_at);
```

### Triggers and Functions

1. **Shared Functions (`update_timestamp`, `enforce_versioning`,
   `prevent_physical_delete`, `prevent_immutable_updates`)**: General PL/pgSQL
   functions instantiated once for all entities.

```sql
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    -- Physical timestamp is ALWAYS updated
    NEW.row_updated_at = NOW();

    -- Logical timestamp is updated to the transaction time unless bypassed by role
    -- Controlled via a custom session configuration parameter
    IF current_setting('unicoach.bypass_logical_timestamp', true) IS DISTINCT FROM 'true' THEN
        NEW.updated_at = NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION enforce_versioning()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'Initial version must be 1' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.version IS DISTINCT FROM (OLD.version + 1) THEN
            RAISE EXCEPTION 'Optimistic Concurrency Control conflict: row was modified by another transaction or version was not provided.'
            USING ERRCODE = '40001'; -- serialization_failure
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_physical_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Physical deletions are strictly blocked. Use soft deletes by setting deleted_at.' USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'The id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.row_created_at IS DISTINCT FROM OLD.row_created_at THEN
        RAISE EXCEPTION 'The row_created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

> [!WARNING] **Application Integration Requirement for OCC**: The
> `enforce_versioning` trigger requires the application explicitly send the
> calculated next version number (e.g., `SET version = 2`). The application MUST
> NOT use relative SQL statements like `SET version = version + 1`, as doing so
> during a concurrency race condition could bypass the trigger check (since
> Postgres evaluates `version + 1` against the _newest committed row_).

2. **Entity Specific Functions (`trim_users_strings`, `log_user_version`)**:

```sql
CREATE OR REPLACE FUNCTION trim_users_strings()
RETURNS TRIGGER AS $$
BEGIN
    NEW.email = LOWER(trim(NEW.email));
    NEW.name = trim(NEW.name);
    IF NEW.display_name IS NOT NULL THEN
        NEW.display_name = trim(NEW.display_name);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO users_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        email, name, display_name, password_hash, sso_provider_id
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.email, NEW.name, NEW.display_name, NEW.password_hash, NEW.sso_provider_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

3. **Trigger Attachments**: Execute functions on row mutation.

```sql
-- BEFORE triggers execute in alphabetical order by name if not specified.
CREATE TRIGGER trigger_00_prevent_physical_delete
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_immutable_updates
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_01_enforce_users_versioning
BEFORE INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_02_trim_users_strings
BEFORE INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE trim_users_strings();

CREATE TRIGGER trigger_03_enforce_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

-- AFTER trigger to log the finalized row
CREATE TRIGGER trigger_04_log_user_version
AFTER INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE log_user_version();
```

## Tests

All database test cases MUST be wrapped in an isolated SQL transaction that ends
with a `ROLLBACK` statement to ensure the testing environment remains pure.

- **Migration test**: Verify the `users` and `users_versions` tables are created
  successfully.
- **Constraints tests**: Attempt to insert duplicate active emails, empty names,
  empty strings for `display_name`, `password_hash`, or `sso_provider_id`,
  invalid email formats, and rows lacking an auth mechanism. Verify all fail
  appropriately.
- **Immutable Updates test**: Attempt to `UPDATE` a row's `id`, `created_at`, or
  `row_created_at` fields and verify that it strictly fails.
- **Delete test**: Attempt to run `DELETE FROM users` and assert it strictly
  fails due to the physical delete prevention trigger.
- **Trimming test**: Insert a user with padded spaces on email/name, and ensure
  the saved entity automatically truncated the space via the
  `trim_users_strings` trigger.
- **Versioning test**: Ensure that an `INSERT` starts with `version = 1`, and
  that `UPDATE` requires explicit `version = 2` to bypass OCC limitations.
  Verify `40001` ERRCODE is raised on conflict. Ensure `users_versions` has two
  rows after the update.
- **Timestamp trigger test**: Update a field (e.g., name) and assert that
  `updated_at` and `row_updated_at` change.
- **Timestamp bypass test**: Set session configuration
  `unicoach.bypass_logical_timestamp` to `'true'`, update a user's name, and
  assert that `row_updated_at` changes but `updated_at` does not.
- **Soft delete index test**: Ensure the partial unique index allows
  re-registration of a soft-deleted email.

## Implementation Plan

1. Create `db/schema/0000.shared-functions.sql` schema file to:
   - Instantiate the generalized `update_timestamp` (using session config for
     bypass), `enforce_versioning` (raising serialization_failure ERRCODEs),
     `prevent_physical_delete`, and `prevent_immutable_updates` PL/pgSQL
     functions so they are available system-wide.
2. Create `db/schema/0001.create-users.sql` schema file to:
   - Create the `users` and `users_versions` tables with all constraints and
     indices.
   - Create the entity-specific functions `trim_users_strings` and
     `log_user_version`.
   - Run the `CREATE TRIGGER` attachments on `users` for immutability,
     versioning, timestamps, trimming, logging, and delete prevention.
3. Write integration tests in `bin/db-scripts-tests` or a standalone
   `tests/db/test_users_table.sh` script validating isolated constraint,
   versioning, trimming, deletion prevention, immutable updates rejection, and
   trigger conditions.

## Files Modified

- `db/schema/0000.shared-functions.sql` [NEW]
- `db/schema/0001.create-users.sql` [NEW]
- `tests/db/test_users_table.sh` [NEW]
