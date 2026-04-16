---
name: postgres-entity-table-design
description: >-
  Opinionated guidance for PostgreSQL entity tables, prioritizing strong
  database-level guarantees and simplicity in application code. Use when
  designing tables for entities like users, orders, or items, and when strict
  data integrity is required.
---

# Postgres Entity Table Design

- [Intended Audience](#intended-audience)
- [Philosophy](#philosophy)
- [When to Use](#when-to-use)
- [What is an Entity?](#what-is-an-entity)
  - [Entity Options](#entity-options)
- [Unique Identifiers (IDs)](#unique-identifiers-ids)
  - [DDL Examples for IDs](#ddl-examples-for-ids)
- [Timestamps](#timestamps)
  - [Advanced: Physical vs. Logical Timestamps](#advanced-physical-vs-logical-timestamps)
  - [Application vs. Maintenance / Offline Mutation Timestamps](#application-vs-maintenance--offline-mutation-timestamps)
- [Versioning (Optional)](#versioning-optional)
- [Deletion](#deletion)
- [Examples](#examples)
  - [ID Generation via Feistel Cipher (Advanced)](#id-generation-via-feistel-cipher-advanced)
  - [Table Definitions](#table-definitions)
    - [Immutable Entity](#immutable-entity)
    - [Mutable Entity](#mutable-entity)
    - [Versioned Entity](#versioned-entity)
    - [Advanced created and updated timestamps](#advanced-created-and-updated-timestamps)
  - [Functions and Triggers](#functions-and-triggers)
    - [Enforcing Immutability Trigger](#enforcing-immutability-trigger)
    - [Mutable Entity updated_at Trigger](#mutable-entity-updated_at-trigger)
    - [Automated Versioning and OCC Trigger](#automated-versioning-and-occ-trigger)
    - [Populate Versions Table Trigger](#populate-versions-table-trigger)

This skill defines an opinionated design philosophy for defining PostgreSQL
entity tables. It prioritizes strong database-level guarantees over application
logic at the cost of PostgreSQL vendor lock-in.

This approach will work well for small to medium sized applications. Larger
applications may run into scaling issues.

## Intended Audience

This skill is intended for:

- _Backend Engineers_ designing new relational database schemas in PostgreSQL.
- _AI Agents_ generating database DDL and stored procedures, requiring an
  opinionated baseline instead of making infinite choices.
- _Full-stack Developers_ who want to leverage database-level guarantees to
  simplify application-layer state management.

## Philosophy

- _Strong Guarantees_: Use database-level constraints, triggers, and stored
  procedures to guarantee data integrity, even if it introduces vendor lock-in
  or slower integration tests.
- _Low-level Guarantees_: By placing logic in the database (via functions and
  triggers), it is harder to bypass data invariants.
- _Simplicity in Application Code_: Offload complex mutation logic (like ID
  generation and collision handling) to the database to keep application code
  simple and consistent.

## When to Use

Use this approach when: - You are building small to medium-sized applications
where database simplicity and strong integrity guarantees are more important
than horizontal scalability. - You want to guarantee data invariants directly in
the database to prevent application bugs from corrupting data. - You prefer
handling complexity in SQL/PL/pgSQL rather than in application code.

Do NOT use this approach when: - You expect massive scale requiring horizontal
sharding (Postgres triggers and procedures can become bottlenecks). - You need
to maintain strict database vendor independence (this pattern relies heavily on
Postgres-specific features). - You prefer generating IDs and managing timestamps
entirely in the application layer.

## What is an Entity?

An entity is an abstract concept that can be singularly identified (e.g., users,
orders, items).

### Entity Options

- _Mutable / Immutable_:
  - _Mutable_: Have an `updated_at` column.
  - _Immutable_: Do not have `updated_at`. Database triggers MUST enforce
    immutability by raising an exception on `UPDATE`.
- _Versioned / Unversioned_:
  - _Versioned_: Have a `version` column starting at 1 and incrementing. They
    have a sibling table `${entity}_versions` that has the same schema as the
    `${entity}` table, but the unique ID constraint is removed and a unique
    `(id, version)` constraint is added.
- _Logical / Physical Deletes_:
  - _Logical_: Use a `deleted_at` timestamp. Rows are never physically deleted.
    Application logic must filter for active entities.
  - _Physical_: Rows are physically deleted from the main table and the versions
    table (if applicable). This is an exception (noted in deletion section
    below).

## Unique Identifiers (IDs)

Every entity has a unique ID.

- _Format_: Choose one of the following based on requirements:
  - _UUID (v7 or v4): \*\*Strongly recommended_ for most use cases. UUIDv7 is
    preferred as it is time-ordered and friendly to database indexes.
  - _Obfuscated Sequence (Feistel Cipher)_: Niche use case. Use only when short,
    non-sequential, random-looking string IDs are strictly required (e.g., for
    public URLs) and you cannot use UUIDs.
- _Generation_:
  - UUIDs: Generated via standard Postgres extensions (like `uuid-ossp`) or
    natively using `uuidv7()` if on the latest version of Postgres.
  - Feistel Cipher: Derived from a standard Postgres sequence and encrypted
    using a PL/pgSQL function before encoding (e.g., to Base36).
- _Fallback for Short IDs_: If you do not want to manage the complexity of a
  Feistel cipher in PL/pgSQL, a valid alternative is to generate short IDs in
  the _application layer_ using libraries like Hashids or Sqids and store them,
  rather than doing it in the database.
- _App Layer Delegation Restrictions_: Application code should not manually
  generate standard IDs (like UUIDs) for new entities. It is always preferable
  to rely on the database to handle this. DAO input classes should typically not
  take an ID as a parameter to INSERT methods.
- _Invariant_: Entity IDs MUST NEVER change.

#### DDL Examples for IDs

_Option A: UUID (Recommended)_

```sql
-- Using extension (UUID v4)
CREATE TABLE users (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4()
);

-- Using native uuidv7() (PostgreSQL 18+)
CREATE TABLE users (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7()
);
```

_Option B: 12-character string via Feistel Cipher (Advanced/Niche)_

> [!NOTE] The Feistel cipher implementation below uses a 62-bit integer. It
> ensures the result is always a positive BIGINT, which is safe for Base36
> encoding.

> [!WARNING] The Feistel cipher output is a 62-bit integer, requiring up to 12
> characters in Base36. Ensure your database column is at least `VARCHAR(12)`.
> Application code must handle these as 64-bit integers (`BIGINT`) or strings,
> as 32-bit integers will overflow.

> _The Math:_ - A 62-bit integer max value: 2^62 = 4,611,686,018,427,387,904 -
> 11 characters in Base36 max value: 36^11 - 1 = 131,621,703,842,267,135 - 12
> characters in Base36 max value: 36^12 - 1 = 4,738,381,338,321,616,895 - Since
> 2^62 is larger than 36^11 - 1, 12 characters are required.

```sql
-- Create the sequence first
CREATE SEQUENCE users_id_seq;

-- Create table using the sequence in the default value
CREATE TABLE users (
  id VARCHAR(12) NOT NULL PRIMARY KEY DEFAULT base36_encode(obfuscate_id(nextval('users_id_seq')::BIGINT, 123456))
);
```

## Unstructured Data and JSONB

- **Native NULL over Sentinels**: For optional `JSONB` or unstructured data
  columns, ALWAYS use native SQL `NULL`. Do NOT use empty structural sentinels
  like `DEFAULT '{}'::jsonb` or `DEFAULT '[]'`.
- Using `NULL` unambiguously represents the absence of data, avoiding expensive
  parsing operations, preserving index efficiency, and sidestepping
  application-level evaluation logic to check if a valid object exists or if
  it's merely a default shell.

## String Types

- _Use TEXT and CHECK constraints_: Always use the unbounded `TEXT` type along
  with explicitly named `CHECK` constraints (e.g.,
  `CONSTRAINT users_email_length_check CHECK (length(email) <= 254)`) for
  bounded string data instead of `VARCHAR(n)`.
- _Forbid VARCHAR_: Do not use `VARCHAR` except for exceptional circumstances
  that must be clearly documented. In PostgreSQL, `TEXT` and `VARCHAR` have
  identical performance characteristics and physical storage backing.
- _Why?_: Changing a `CHECK` constraint is a zero-downtime metadata operation in
  Postgres, unlike `ALTER COLUMN type` which can lock tables. Furthermore, named
  constraints allow the application to programmatically catch specific
  constraint violations and return meaningful user errors.

## Timestamps

By default, entities should have `created_at` and `updated_at` timestamps.

- `created_at`: Logical entity creation time. Set on insert and never changed.
- `updated_at`: Logical entity last updated time. Updated automatically on every
  update. Note that this may not be updated on some writes e.g. migrations and
  backfills (see Advanced section below).
- **Source of Truth for Time**: Always use the database as the single source of
  truth for time. Do not use application times (e.g., `Instant.now()`) to set
  timestamps that will be stored in the database. Rely on database functions
  like `NOW()` to guarantee consistency.

### Advanced: Physical vs. Logical Timestamps

For tables that require strict auditing or need to distinguish between physical
database writes (e.g., data migrations, backfills) and true logical data
changes, you can use a 4-timestamp pattern:

- `row_created_at`: Physical row creation time.
- `created_at`: Logical entity creation time.
- `row_updated_at`: Physical row last updated time (always updated on any
  write).
- `updated_at`: Logical entity last updated time.

> [!NOTE] **`NOW()` Preference**: Prefer to use `NOW()` for all timestamps,
> including physical timestamps like `row_created_at` and `row_updated_at`. Use
> `clock_timestamp()` only when you need the current time of the statement
> execution. `NOW()` lock-steps to the start time of the transaction, which
> provides exact temporal consistency within bulk operations.

This ensures that administrative scripts can update rows (changing
`row_updated_at`) without affecting the business-meaningful `updated_at`
timestamp.

_Opinionated Pattern for `updated_at`:_ To distinguish between physical row
updates (like schema migrations or data cleanups) and true logical data changes,
the trigger updates `updated_at` by default on every write.

To bypass this for administrative scripts, we recommend checking if the session
user has a specific administrative role (Option A below). Alternatively, you can
use a session variable (Option B below), but **use caution if using a connection
pooler in transaction mode**.

### Application vs. Maintenance / Offline Mutation Timestamps

It is important to understand the difference between logical timestamps
(`created_at`, `updated_at`) and physical timestamps (`row_created_at`,
`row_updated_at`).

- _`row_created_at` vs `created_at`_: Usually identical on insert and never
  change. However, they can differ during _data migrations_ to preserve original
  business history while tracking physical insertion time. For example,
  migrating a user who registered on 2020-01-01 into this new database on
  2026-03-31 will result in `created_at = '2020-01-01'` and
  `row_created_at = '2026-03-31'`.
- _`row_updated_at` vs `updated_at`_: Usually identical on update. However, they
  can differ during _offline database updates_ (like schema backfills or data
  cleanups) that don't change business meaning. In those cases, `row_updated_at`
  should change to reflect the DB write, but `updated_at` should remain
  unchanged.

## Versioning (Optional)

Versioning stores every historic value that the row has taken. It allows the
application, or the debugger to look back in time to see what the state of a
given entity was at any point in time.

Versioning also allows the state model to point to entities at exact points in
time. If a user likes a comment, but then the comment changes, this is visible
with versioning.

If versioning is desired, follow this pattern:

- _Main Table_: Include a `version` column (integer), starting at `1` on insert.
- _Enforce Initial Version_: To prevent applications from bypassing the starting
  version, use a trigger to force `version = 1` on insert (or reject non-1
  values).
- _Concurrency Control_: Use Optimistic Concurrency Control (OCC) for updates.
  - _Application-managed_:
    `UPDATE {table} SET ..., version = version + 1 WHERE id = ? AND version = ?;`
  - *Database-automated (via Trigger): The application sends the *next version
    (incremented) in the ` SET ` clause:
    `UPDATE {table} SET ..., version = ? WHERE id = ?;`. A trigger checks if it
    matches ` old.version + 1 `. If the application forgets to send the version,
    it defaults to the old version and the check fails, preventing silent
    bypass.
- _Versions Table_: A separate table `{table}_versions` stores historical rows.
- _Trigger_: A trigger copies the new row to the versions table after any
  mutation (insert or update). This ensures that all versions, including the
  first one, are available in the versions table.
- _Maintenance Burden_: Note that the trigger function typically hardcodes
  column names (see examples in Functions and Triggers section). Every time you
  add or remove columns in the main table, you MUST update the versions table
  schema and the trigger function. This is a significant maintenance cost of
  this pattern.
- _Unique Constraints_: Unique constraints on the main table (like
  ` UNIQUE(email) `) MUST be removed or relaxed in the versions table (except
  for the composite primary key `(id, version)`). Keeping them would prevent
  inserting multiple versions of the same entity.
- _Index Strategy_: The primary key on `(id, version)` in the versions table
  automatically creates an index. This is typically sufficient for history
  lookups where you filter by `id` (and optionally `version`).
- _Foreign Keys_: Deciding how other tables reference a versioned entity is
  critical:
  - _Reference Current_: Point to `main_table(id)`. This always references the
    latest version.
  - _Reference Snapshot_: Point to `version_table(id, version)`. This references
    a specific point in time. Use this for immutable records like order items
    referencing a specific version of a product at the time of purchase.
  - _Example (Reference Current)_:
    `sql CREATE TABLE posts ( id UUID PRIMARY KEY DEFAULT uuidv7(), author_id UUID NOT NULL REFERENCES users(id), content TEXT );`
  - _Example (Reference Snapshot)_:
    `sql CREATE TABLE order_items ( order_id UUID NOT NULL, item_id VARCHAR(12) NOT NULL, item_version INTEGER NOT NULL, quantity INTEGER NOT NULL, FOREIGN KEY (item_id, item_version) REFERENCES item_versions(id, version) );`

## Deletion

- _Logical Deletion_: Use a `deleted_at` timestamp column to track if an entity
  is deleted. Rows are never physically deleted. Application logic must filter
  for active entities.
- _Physical Deletion_: Removes the row from the main `${entities}` table and
  `${entity}_versions` table (if applicable). Strongly discouraged, allowed only
  for compliance (e.g., GDPR right-to-be-forgotten).
- _Undelete_: Often an anti-pattern if not handled carefully, as it requires
  restoring state and handling references. However, if business requirements
  demand a "restore" feature, ensure triggers and foreign keys are designed to
  support it safely.
- _Indexes for Soft Deletes_: When using logical deletion, most of your queries
  will include `WHERE deleted_at IS NULL`. To optimize performance and save
  index space, use _partial indexes_:
  `sql CREATE INDEX ON users (email) WHERE deleted_at IS NULL;`

## Spec Presentation

When authoring a specification for a new entity table, you MUST include a strict
configuration table at the beginning of the `Detailed Design` block. This acts
as an unambiguous, at-a-glance contract for the implementor.

### Entity Configuration Template

```markdown
### Entity Configuration

| Setting        | Selection                       | Implementation Requirement                                 |
| :------------- | :------------------------------ | :--------------------------------------------------------- |
| **ID Type**    | `UUIDv7` / `UUIDv4` / `Feistel` | (e.g., Use `uuidv7()` as the `id` column default)          |
| **Mutability** | Mutable / Immutable             | (e.g., Include `updated_at` with the admin-bypass trigger) |
| **Timestamps** | Logical Only / Advanced         | (e.g., Logical only, omit `row_created_at`)                |
| **Versioning** | Enabled / Disabled              | (e.g., Disabled, no `_versions` table needed)              |
| **Deletions**  | Logical / Physical              | (e.g., Logical, requiring `deleted_at` column)             |
```

## Examples

### ID Generation via Feistel Cipher (Advanced)

> [!WARNING] This implementation is for _obfuscation only_, not security. It
> makes IDs random-looking for URLs but does not protect against a determined
> attacker. This implementation uses a 62-bit Feistel cipher to ensure the
> output is always a positive `BIGINT`, which is safe for Base36 encoding.

This function implements a 4-round Feistel cipher to obfuscate a 62-bit integer.
It is a bijection, meaning every input maps to a unique output within the
domain, guaranteeing no collisions.

```sql
-- Full implementation of a 62-bit Feistel cipher in PL/pgSQL
-- This ensures the result is always a positive BIGINT, safe for Base36 encoding.
CREATE OR REPLACE FUNCTION obfuscate_id(v BIGINT, secret_key BIGINT) RETURNS BIGINT AS $$
DECLARE
  l BIGINT;
  r BIGINT;
  temp BIGINT;
  f_func BIGINT;
  i INT;
BEGIN
  -- Split the 62-bit integer into two 31-bit halves
  l := (v >> 31) & x'7FFFFFFF'::bigint;
  r := v & x'7FFFFFFF'::bigint;

  -- Run 4 rounds
  FOR i IN 1..4 LOOP
    -- The round function (F). Using 64-bit math.
    f_func := ((r * 123456789) + secret_key + i) & x'7FFFFFFF'::bigint;

    -- XOR the left half with the output of the round function
    temp := l # f_func;

    -- Swap halves
    l := r;
    r := temp;
  END LOOP;

  -- Recombine the two 31-bit halves into a 62-bit positive integer
  RETURN (l << 31) | (r & x'7FFFFFFF'::bigint);
END;
$$ LANGUAGE plpgsql;

-- Helper function to encode integer to Base36
CREATE OR REPLACE FUNCTION base36_encode(val BIGINT) RETURNS TEXT AS $$
DECLARE
  chars TEXT := '0123456789abcdefghijklmnopqrstuvwxyz';
  result TEXT := '';
  remainder INT;
BEGIN
  IF val = 0 THEN
    RETURN '0';
  END IF;

  WHILE val > 0 LOOP
    remainder := val % 36;
    result := substr(chars, remainder + 1, 1) || result;
    val := val / 36;
  END LOOP;

  RETURN result;
END;
$$ LANGUAGE plpgsql;
```

### Table Definitions

#### Immutable Entity

Immutable entities do not have an ` updated_at ` column. A ` deleted_at ` column
is optional.

```sql
CREATE TABLE devices (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  serial_number TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  model TEXT NULL,
  CONSTRAINT devices_serial_number_length_check CHECK (length(serial_number) <= 255),
  CONSTRAINT devices_name_length_check CHECK (length(name) <= 255),
  CONSTRAINT devices_model_length_check CHECK (model IS NULL OR length(model) <= 255)
);
```

#### Mutable Entity

Mutable entities have an ` updated_at ` column.

```sql
CREATE TABLE users (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  email TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  display_name TEXT NULL,
  deleted_at TIMESTAMPTZ NULL,
  CONSTRAINT users_email_length_check CHECK (length(email) <= 254),
  CONSTRAINT users_name_length_check CHECK (length(name) <= 255),
  CONSTRAINT users_display_name_length_check CHECK (display_name IS NULL OR length(display_name) <= 255)
);
```

#### Versioned Entity

Versioned entities have a sibling ` ${entity}_versions ` table that is managed
with a trigger on mutations (` INSERT `, ` UPDATE `).

```sql
CREATE TABLE items (
  id VARCHAR(12) NOT NULL PRIMARY KEY DEFAULT base36_encode(obfuscate_id(nextval('items_id_seq')::BIGINT, 123456)),
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  title TEXT NOT NULL,
  description TEXT NULL,
  price_cents INTEGER NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  CONSTRAINT items_title_length_check CHECK (length(title) <= 255)
);

CREATE TABLE item_versions (
  id VARCHAR(12) NOT NULL,
  version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  title TEXT NOT NULL,
  description TEXT NULL,
  price_cents INTEGER NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  PRIMARY KEY (id, version)
);
```

#### Advanced created and updated timestamps

This example shows a table using the 4-timestamp pattern described in the
Advanced Timestamps section.

```sql
CREATE TABLE widgets (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  sku TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  description TEXT NULL,
  CONSTRAINT widgets_sku_length_check CHECK (length(sku) <= 255),
  CONSTRAINT widgets_name_length_check CHECK (length(name) <= 255)
);
```

### Functions and Triggers

#### Enforcing Immutability Trigger

This trigger raises an exception when an immutable entity is attempted to be
mutated.

```sql
CREATE OR REPLACE FUNCTION enforce_immutability()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'This entity is immutable and cannot be updated.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Apply to an immutable table
CREATE TRIGGER enforce_immutable_table_updates
BEFORE UPDATE ON devices
FOR EACH ROW
EXECUTE PROCEDURE enforce_immutability();
```

#### Mutable Entity updated_at Trigger

These examples show how to update a database row for backfills, data migrations,
and other out-of-band updates without changing the logical ` updated_at `
timestamp.

_Option A: Role-based Bypass (Recommended)_

This approach checks if the session user is an admin. It is safe for pooled
environments.

```sql
-- Trigger to update updated_at, bypassing if user is 'admin'
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    -- Physical timestamp is ALWAYS updated
    NEW.row_updated_at = NOW();

    -- Logical timestamp is updated unless bypassed by role
    IF SESSION_USER != 'admin' THEN
        NEW.updated_at = NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_widgets_timestamp
BEFORE UPDATE ON widgets
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();
```

_Option B: Session Variable Bypass (Use with Caution)_

> [!WARNING] Do not use session variables if using a connection pooler (like
> PgBouncer) in transaction mode. State can leak between different clients.

```sql
-- Trigger to update updated_at
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.row_updated_at = NOW();

    IF current_setting('app.ignore_logical_timestamps', true) IS DISTINCT FROM 'true' THEN
        NEW.updated_at = NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

To use Option B:

```sql
SET app.ignore_logical_timestamps = 'true';
-- Your updates here
UPDATE users SET some_col = 'val';
RESET app.ignore_logical_timestamps;
```

#### Automated Versioning and OCC Trigger

This trigger enforces that the initial version is 1 on insert, and enforces OCC
on update by checking if the version sent by the application matches the current
version in the database + 1.

```sql
CREATE OR REPLACE FUNCTION enforce_versioning()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Force version to 1 on insert, or raise exception if not 1
        IF NEW.version IS DISTINCT FROM 1 THEN
            RAISE EXCEPTION 'Initial version must be 1';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Check if the version provided by the application is exactly OLD.version + 1
        -- If the application forgets to send the version, NEW.version defaults to OLD.version,
        -- and this check will fail (since OLD.version != OLD.version + 1).
        IF NEW.version IS DISTINCT FROM (OLD.version + 1) THEN
            RAISE EXCEPTION 'Optimistic Concurrency Control conflict: row was modified by another transaction or version was not provided.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_items_versioning
BEFORE INSERT OR UPDATE ON items
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();
```

#### Populate Versions Table Trigger

This trigger populates the sibling ` *_versions ` table on entity mutations.

```sql
CREATE OR REPLACE FUNCTION log_item_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO item_versions (
        id, version, created_at, updated_at, title, description, price_cents, deleted_at
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.updated_at, NEW.title, NEW.description, NEW.price_cents, NEW.deleted_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER log_item_version_trigger
AFTER INSERT OR UPDATE ON items
FOR EACH ROW
EXECUTE PROCEDURE log_item_version();
```
