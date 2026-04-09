#!/usr/bin/env bash
export ENV_FILE="$(dirname "$0")/../../.env.test"
source "$(dirname "$0")/../../bin/common"
source "$(dirname "$0")/../../bin/tests-common"

trap 'bin/postgres-stop >/dev/null 2>&1 || true; [ -x "bin/docker-network-daemon-stop" ] && bin/docker-network-daemon-stop unicoach-network >/dev/null 2>&1 || true; docker network rm unicoach-network >/dev/null 2>&1 || true' EXIT INT TERM

wait_for_postgres() {
  local container_id="$1"
  for i in {1..15}; do
    if docker exec "$container_id" psql -U postgres -d "$POSTGRES_DB" -c '\q' >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Fail: Timed out waiting for database '$POSTGRES_DB' runtime to become queryable organically."
  exit 1
}

# Start clean
bin/postgres-stop -d >/dev/null 2>&1 || true
rm -rf "$POSTGRES_DATA_DIR" >/dev/null 2>&1 || true
bin/postgres-start >/dev/null
postgres_container="$(bin/docker-compose -f docker/postgres-compose.yaml ps -q postgres)"
wait_for_postgres "$postgres_container"

bin/db-init >/dev/null
bin/db-migrate >/dev/null

echo "==== Testing Users Table Initialization ===="

test_users_table_exists() {
  local out
  out=$(echo "SELECT count(*) FROM users;" | bin/db-query -tA 2>&1) || { echo "output was: $out"; return 1; }
}
assert_success "users table created" test_users_table_exists

test_users_versions_table_exists() {
  local out
  out=$(echo "SELECT count(*) FROM users_versions;" | bin/db-query -tA 2>&1) || { echo "output was: $out"; return 1; }
}
assert_success "users_versions table created" test_users_versions_table_exists


echo "==== Testing Users Table Constraints ===="

run_test_sql() {
  (
    echo "BEGIN;"
    cat
    echo "ROLLBACK;"
  ) | bin/db-run rw 2>&1
}

# Executes SQL and forces psql into "tuples-only" (-t) and "unaligned" (-A) mode.
# This disables ASCII table borders and column headers, returning clean 
# pipe-delimited raw row data that is easy to extract or evaluate in bash.
run_test_sql_ta() {
  (
    echo "BEGIN;"
    cat
    echo "ROLLBACK;"
  ) | bin/db-run rw -tA 2>&1
}

test_duplicate_email() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test@example.com', 'Test', 'hash');
    INSERT INTO users (email, name, password_hash) VALUES ('test@example.com', 'Test2', 'hash2');
EOF
  )
  echo "$out" | grep -q 'users_email_unique_active_idx' || { echo "output was: $out"; return 1; }
}
assert_success "duplicate active emails fail" test_duplicate_email

test_invalid_email_format() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('invalidemail', 'Test', 'hash');
EOF
  )
  echo "$out" | grep -q 'users_email_format_check' || { echo "output was: $out"; return 1; }
}
assert_success "invalid email formats fail" test_invalid_email_format

test_empty_name() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test@example.com', '   ', 'hash');
EOF
  )
  echo "$out" | grep -q 'users_name_not_empty_check' || { echo "output was: $out"; return 1; }
}
assert_success "empty names fail" test_empty_name

test_empty_display_name() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, display_name, password_hash) VALUES ('test@example.com', 'Test', '  ', 'hash');
EOF
  )
  echo "$out" | grep -q 'users_display_name_not_empty_check' || { echo "output was: $out"; return 1; }
}
assert_success "empty display_name fails" test_empty_display_name

test_empty_password_hash() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test@example.com', 'Test', '   ');
EOF
  )
  echo "$out" | grep -q 'users_password_hash_not_empty_check' || { echo "output was: $out"; return 1; }
}
assert_success "empty password_hash fails" test_empty_password_hash

test_lack_auth_mechanism() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name) VALUES ('test@example.com', 'Test');
EOF
  )
  echo "$out" | grep -q 'users_auth_method_check' || { echo "output was: $out"; return 1; }
}
assert_success "missing auth mechanism fails" test_lack_auth_mechanism


echo "==== Testing Triggers & Versioning ===="

test_string_trimming() {
  local out
  out=$(run_test_sql_ta << 'EOF'
    INSERT INTO users (email, name, display_name, password_hash) 
    VALUES ('  TEST@EXAMPLE.com  ', '  Test  ', '  Display  ', 'hash')
    RETURNING email || '|' || name || '|' || display_name;
EOF
  )
  echo "$out" | grep -q "test@example.com|Test|Display" || { echo "output was: $out"; return 1; }
}
assert_success "strings are auto-trimmed and lowercased" test_string_trimming

test_immutable_updates() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test2@example.com', 'Test', 'hash');
    UPDATE users SET created_at = '2020-01-01T00:00:00Z'::TIMESTAMPTZ WHERE email = 'test2@example.com';
EOF
  )
  echo "$out" | grep -q 'The created_at field is immutable' || { echo "output was: $out"; return 1; }
}
assert_success "immutable updates rejected" test_immutable_updates

test_physical_delete_prevention() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test3@example.com', 'Test', 'hash');
    DELETE FROM users WHERE email = 'test3@example.com';
EOF
  )
  echo "$out" | grep -q 'Physical deletions are strictly blocked'
}
assert_success "physical delete prevention" test_physical_delete_prevention

test_initial_version() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash, version) VALUES ('test4@example.com', 'Test', 'hash', 2);
EOF
  )
  echo "$out" | grep -q 'Initial version must be 1'
}
assert_success "inserting version != 1 fails" test_initial_version

test_optimistic_concurrency_conflict() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test5@example.com', 'Test', 'hash');
    UPDATE users SET name = 'Test2' WHERE email = 'test5@example.com';
EOF
  )
  echo "$out" | grep -q 'Optimistic Concurrency Control conflict'
}
assert_success "UPDATE without version bump conflicts (OCC)" test_optimistic_concurrency_conflict

test_optimistic_concurrency_success() {
  local out
  out=$(run_test_sql_ta << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('test6@example.com', 'Test', 'hash');
    UPDATE users SET name = 'Test2', version = 2 WHERE email = 'test6@example.com' RETURNING version;
EOF
  )
  echo "$out" | grep -q "^2$" || { echo "output was: $out"; return 1; }
}
assert_success "UPDATE with version bump succeeds" test_optimistic_concurrency_success

test_versioning_log() {
  local out
  out=$(run_test_sql_ta << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('log@example.com', 'Test', 'hash');
    UPDATE users SET name = 'Test2', version = 2 WHERE email = 'log@example.com';
    SELECT count(*) FROM users_versions WHERE email = 'log@example.com';
EOF
  )
  echo "$out" | grep -q '^2$' || { echo "output was: $out"; return 1; }
}
assert_success "version logging pushes rows to users_versions" test_versioning_log

test_soft_delete_index() {
  local out
  out=$(run_test_sql << 'EOF'
    INSERT INTO users (email, name, password_hash) VALUES ('soft@example.com', 'Test', 'hash');
    UPDATE users SET deleted_at = NOW(), version = 2 WHERE email = 'soft@example.com';
    INSERT INTO users (email, name, password_hash) VALUES ('soft@example.com', 'Test', 'hash');
EOF
  )
  if echo "$out" | grep -q 'ERROR'; then 
    echo "output was $out"
    return 1
  fi
  return 0
}
assert_success "soft delete allows email reuse" test_soft_delete_index

test_timestamp_trigger() {
  bin/db-update "INSERT INTO users (email, name, password_hash) VALUES ('ts@example.com', 'Test', 'hash');"
  sleep 1
  bin/db-update "UPDATE users SET name = 'Test2', version = 2 WHERE email = 'ts@example.com';"
  local out
  out=$(echo "SELECT (created_at = updated_at)::text || '|' || (created_at = row_updated_at)::text FROM users WHERE email = 'ts@example.com';" | bin/db-query -tA)
  
  if [ "$out" = "false|false" ]; then return 0; else echo "out was $out"; return 1; fi
}
assert_success "updated_at and row_updated_at change on mutation" test_timestamp_trigger

test_timestamp_bypass() {
  bin/db-update "INSERT INTO users (email, name, password_hash) VALUES ('ts_bypass@example.com', 'Test', 'hash');"
  sleep 1
  
  local out
  out=$( (
    echo "BEGIN;"
    echo "SET LOCAL unicoach.bypass_logical_timestamp = 'true';"
    echo "UPDATE users SET name = 'Test2', version = 2 WHERE email = 'ts_bypass@example.com';"
    echo "SELECT (created_at = updated_at)::text || '|' || (created_at = row_updated_at)::text FROM users WHERE email = 'ts_bypass@example.com';"
  ) | bin/db-run rw -tA | grep -E '^(true|false)\|(true|false)$' )
  
  if [ "$out" = "true|false" ]; then return 0; else echo "out was $out"; return 1; fi
}
assert_success "bypass logical timestamp overrides updated_at but retains physical update" test_timestamp_bypass

end_tests
