package ed.unicoach.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The erasure classification, held to the live catalog (RFC 263, slice
 * deletion/01/the-classification, brief 0002 D3/D8).
 *
 * Erasure is driven by the schema: a column of a `pii.*` type holds a person's
 * data, and a key of an `owned.*` type leads from an owner row to that person's
 * words in the LLM log. Nothing in production reads a column list. So the only
 * thing that keeps a column added next year from escaping erasure is this test,
 * which walks the catalog and names every column, key and table that breaks one
 * of four rules.
 *
 * THE REACH is what an erasure walk starting at an account can get to: the
 * seeds `users`, `students`, `supporters`; every table with a foreign key onto a
 * table in the reach (child-ward); and every table an `owned.*` key of a table
 * in the reach points at (forward). A table is "reached child-ward" when it is
 * reached from a seed by child-ward steps alone. The LLM log tables are in the
 * reach only forward, through `owned.*`.
 *
 * Rule 2 checks keys FROM a table reached child-ward, because those are the
 * rows the walk finds by owner. A key from inside the forward-reached log
 * (`llm_responses.request_id`) points back up the path the walk came down, at
 * a row already walked, and needs no annotation.
 *
 * Rule 4 (RFC 264, slice deletion/02/the-deleted-tables, D1/D3) checks keys
 * ONTO an account table. Erasure keeps the row that holds such a key and moves
 * the key onto `deleted_users`, `deleted_students` or `deleted_supporters`, so
 * every such key needs a sibling key onto the matching deleted table and a
 * CHECK over exactly that pair. The pair is read from the CHECK's columns
 * (`pg_constraint.conkey`), never from a column name (D8).
 *
 * ANTI-VACUITY IS PROVEN, not asserted: a probe column and three probe tables,
 * created inside a rolled-back transaction, are each named by their rule, so a
 * typo in a catalog query cannot produce a guard that always passes.
 */
class ErasureClassificationTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }

    /** The account tables an erasure walk starts from. */
    private val SEEDS = listOf("users", "students", "supporters")

    /** Each account table, mapped to the table its keys move onto at erasure (D1). */
    private val DELETED_TABLE =
      mapOf("users" to "deleted_users", "students" to "deleted_students", "supporters" to "deleted_supporters")

    // One reason per KIND of column. Each says why the value, on a row whose
    // owner is erased, says nothing the person told us or we wrote about them
    // beyond the fact that the row existed and when.
    private const val KEY =
      "a key: it says which row, not anything the person told us"
    private const val OWNED_KEY =
      "a key to an LLM call, typed owned.* so the erasure walk follows it to the person's words"
    private const val LIFECYCLE =
      "a lifecycle timestamp: when the row was made, changed, ended or deleted"
    private const val VERSION =
      "the row's version counter"
    private const val VOCABULARY =
      "a value from our own fixed vocabulary that classifies the row"
    private const val ANSWER_STATE =
      "whether a question was answered, declined or left, or the precision a date was given at -- not the answer"
    private const val MEASURE =
      "a count, size, score or cost our own code computed"
    private const val FLAG =
      "a flag our own code sets"
    private const val PROVIDER =
      "a provider or model identifier, or a parameter we chose for the call"
    private const val TOKEN_HASH =
      "a hash of a secret token we issued; it reveals nothing about the person"
    private const val KEPT_FOR_OTHER_PARTY =
      "the link's kind, kept for the other party to the link (brief 0014 D9)"
    private const val SUBSCRIPTION =
      "the subscription's Apple identifiers and period, kept under gate-1 decision D6"
    private const val OUR_ADDRESS =
      "our own sending address"

    /** The four timestamps every entity table carries. */
    private val ENTITY_TIMESTAMPS = listOf("created_at", "row_created_at", "updated_at", "row_updated_at")

    private fun entries(
      tables: List<String>,
      vararg groups: Pair<String, List<String>>,
    ): List<Pair<String, String>> =
      tables.flatMap { table ->
        groups.flatMap { (reason, columns) -> columns.map { "$table.$it" to reason } }
      }

    /** A live table and its `_versions` history, which copies every column. */
    private fun versioned(table: String) = listOf(table, "${table}_versions")

    /**
     * Every column in the reach that is NOT personal, as `table.column`, with
     * its reason. Keyed by column, not by name pattern (Ian, RFC 263 approval):
     * every column a new table adds needs its own entry, and a personal column
     * that happens to reuse an exempt name cannot pass.
     */
    private val NOT_PERSONAL: Map<String, String> =
      listOf(
        entries(
          versioned("users"),
          KEY to listOf("id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at", "email_verified_at"),
          FLAG to listOf("is_admin"),
        ),
        entries(
          listOf("sessions"),
          KEY to listOf("id", "user_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("expires_at"),
          TOKEN_HASH to listOf("token_hash"),
          FLAG to listOf("is_revoked"),
          VOCABULARY to listOf("login_method"),
        ),
        entries(
          listOf("user_auth_identities"),
          KEY to listOf("id", "user_id"),
          LIFECYCLE to listOf("created_at", "row_created_at"),
          VOCABULARY to listOf("provider"),
          FLAG to listOf("email_verified"),
        ),
        entries(
          listOf("verification_tokens"),
          KEY to listOf("id", "user_id"),
          LIFECYCLE to listOf("created_at", "row_created_at", "expires_at", "consumed_at"),
          TOKEN_HASH to listOf("token_hash"),
        ),
        entries(
          listOf("email_sends"),
          KEY to listOf("id", "user_id", "deleted_user_id"),
          LIFECYCLE to listOf("created_at", "row_created_at", "deleted_at"),
          OUR_ADDRESS to listOf("sender_email"),
          VOCABULARY to listOf("status"),
          PROVIDER to listOf("provider", "provider_message_id"),
        ),
        entries(
          versioned("subscriptions"),
          KEY to listOf("id", "user_id", "deleted_user_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS,
          SUBSCRIPTION to listOf("original_transaction_id", "product_id", "status", "period_start", "period_end"),
        ),
        entries(
          versioned("students"),
          KEY to listOf("id", "user_id", "deleted_user_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          ANSWER_STATE to listOf("expected_high_school_graduation_precision"),
        ),
        entries(
          versioned("supporters"),
          KEY to listOf("id", "user_id", "deleted_user_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
        ),
        entries(
          versioned("supporter_student_links"),
          KEY to listOf("id", "supporter_id", "student_id", "deleted_supporter_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          KEPT_FOR_OTHER_PARTY to listOf("relationship"),
        ),
        entries(
          listOf("supporter_invites"),
          KEY to listOf("id", "inviter_user_id", "student_id", "supporter_id"),
          LIFECYCLE to listOf("created_at", "row_created_at", "expires_at", "consumed_at", "revoked_at"),
          TOKEN_HASH to listOf("token_hash"),
          VOCABULARY to listOf("inviter_role"),
          KEPT_FOR_OTHER_PARTY to listOf("relationship"),
        ),
        entries(
          listOf("supporter_link_requests"),
          KEY to
            listOf(
              "id",
              "supporter_id",
              "student_id",
              "cost_report_share_id",
              "link_id",
              "deleted_supporter_id",
              "deleted_student_id",
            ),
          LIFECYCLE to listOf("created_at", "row_created_at", "notified_at", "resolved_at"),
          VOCABULARY to listOf("resolution"),
        ),
        entries(
          listOf("supporter_llm_costs"),
          OWNED_KEY to listOf("llm_request_id"),
          KEY to listOf("supporter_id", "deleted_supporter_id"),
          LIFECYCLE to listOf("created_at"),
        ),
        entries(
          versioned("sharing_permissions"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          VOCABULARY to listOf("permission", "status"),
        ),
        entries(
          listOf("cost_report_shares"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          LIFECYCLE to listOf("created_at", "row_created_at", "revoked_at"),
          TOKEN_HASH to listOf("token_hash"),
        ),
        entries(
          listOf("share_events"),
          KEY to listOf("id", "student_id", "share_id", "deleted_student_id"),
          LIFECYCLE to listOf("created_at"),
          VOCABULARY to listOf("kind"),
        ),
        entries(
          listOf("convos"),
          KEY to listOf("id", "student_id", "supporter_id", "deleted_student_id", "deleted_supporter_id"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at", "archived_at"),
          VOCABULARY to listOf("party"),
        ),
        entries(
          listOf("convo_requests"),
          KEY to listOf("id", "convo_id", "system_prompt_id", "turn_id"),
          OWNED_KEY to listOf("llm_request_id"),
          LIFECYCLE to listOf("created_at"),
          VOCABULARY to listOf("kind"),
        ),
        entries(
          listOf("observations"),
          KEY to listOf("id", "student_id", "convo_id", "source_request_id", "deleted_student_id"),
          LIFECYCLE to listOf("created_at", "uttered_at", "deleted_at"),
        ),
        entries(
          listOf("claims"),
          KEY to listOf("id", "student_id", "superseded_by_id", "deleted_student_id"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("superseded_at", "retracted_at", "deleted_at"),
          VOCABULARY to listOf("origin", "status", "kind", "subject", "topic", "visibility"),
          MEASURE to listOf("confidence"),
        ),
        entries(
          listOf("claim_support"),
          KEY to listOf("claim_id", "observation_id"),
          LIFECYCLE to listOf("created_at"),
        ),
        entries(
          listOf("commitments"),
          KEY to listOf("id", "student_id", "disclosed_in_convo_id", "deleted_student_id"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("trigger_at", "fulfilled_at", "dropped_at", "deleted_at"),
          VOCABULARY to listOf("lens", "disclosure", "status", "trigger_kind"),
        ),
        entries(
          listOf("commitment_support"),
          KEY to listOf("commitment_id", "claim_id"),
          LIFECYCLE to listOf("created_at"),
        ),
        entries(
          listOf("extraction_runs"),
          KEY to listOf("id", "convo_id", "student_id", "through_request_id", "system_prompt_id", "deleted_student_id"),
          OWNED_KEY to listOf("llm_request_id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
          VOCABULARY to listOf("outcome", "failure_category"),
          MEASURE to listOf("observations_written", "claims_written", "claims_superseded"),
        ),
        entries(
          listOf("synthesis_runs"),
          KEY to listOf("id", "student_id", "system_prompt_id", "deleted_student_id"),
          OWNED_KEY to listOf("llm_request_id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
          VOCABULARY to listOf("outcome", "failure_category"),
          MEASURE to listOf("commitments_written", "commitments_dropped"),
        ),
        entries(
          listOf("fit_lens_runs"),
          KEY to listOf("id", "student_id", "query_system_prompt_id", "reason_system_prompt_id", "deleted_student_id"),
          OWNED_KEY to listOf("query_llm_request_id", "reason_llm_request_id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
          VOCABULARY to listOf("outcome", "failure_category"),
          MEASURE to listOf("suggestions_written", "matches_considered"),
        ),
        entries(
          listOf("fit_suggestions"),
          KEY to listOf("id", "student_id", "surfaced_in_convo_id", "deleted_student_id"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("surfaced_at", "deleted_at"),
          VOCABULARY to listOf("status"),
        ),
        entries(
          versioned("college_list_entries"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
        ),
        entries(
          listOf("college_list_entry_support"),
          KEY to listOf("entry_id", "observation_id"),
          LIFECYCLE to listOf("created_at"),
        ),
        entries(
          versioned("money_profiles"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          ANSWER_STATE to listOf("income_band_status", "residency_status", "living_plan_status", "dependency_status"),
        ),
        entries(
          versioned("admission_test_scores"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          VOCABULARY to listOf("test", "component"),
          ANSWER_STATE to listOf("taken_precision"),
        ),
        entries(
          listOf("admission_test_score_declines"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          LIFECYCLE to listOf("created_at", "row_created_at", "withdrawn_at"),
        ),
        entries(
          versioned("intended_subjects"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
        ),
        entries(
          versioned("intended_subject_status"),
          KEY to listOf("id", "student_id", "deleted_student_id"),
          VERSION to listOf("version"),
          LIFECYCLE to ENTITY_TIMESTAMPS + listOf("deleted_at"),
          ANSWER_STATE to listOf("status"),
        ),
        entries(
          listOf("deleted_users"),
          KEY to listOf("id"),
          LIFECYCLE to listOf("created_at", "closed_at", "erased_at"),
        ),
        entries(
          listOf("deleted_students", "deleted_supporters"),
          KEY to listOf("id", "user_id"),
          LIFECYCLE to listOf("erased_at"),
        ),
        entries(
          listOf("llm_requests"),
          KEY to listOf("id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
          PROVIDER to listOf("provider", "model_requested", "max_tokens", "tool_choice"),
        ),
        entries(
          listOf("llm_responses"),
          KEY to listOf("id", "request_id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
          VOCABULARY to listOf("outcome"),
          PROVIDER to listOf("model_resolved", "stop_reason", "provider_request_id"),
          MEASURE to
            listOf(
              "input_tokens",
              "output_tokens",
              "cache_read_tokens",
              "cache_write_tokens",
              "latency_ms",
              "cost_nanodollars",
            ),
          FLAG to listOf("cost_is_estimated"),
        ),
        entries(
          listOf("llm_responses_raw"),
          KEY to listOf("response_id"),
          LIFECYCLE to listOf("created_at", "deleted_at"),
        ),
      ).flatten().toMap()

    /**
     * Keys onto a personal table the walk does not reach child-ward that are
     * nevertheless NOT a way to a person's data, as `table.column`, with the
     * reason. Empty: every such key today is an `owned.*` key (RFC 263 D-C).
     */
    private val NOT_OWNED: Map<String, String> = emptyMap()

    private const val PHYSICALLY_DELETED =
      "physically deleted at erasure (D10); the row never survives to have its key moved"
    private const val ROOT_HISTORY =
      "the account table's own history, keyed by the account's id as part of its primary key; " +
        "the account row is kept by D1, so the id it names always exists"
    private const val DELETED_ROW_ID =
      "the deleted row's own id is the account's id (D9); the account row is kept by D1"

    /**
     * Keys onto an account table that need no sibling key onto a deleted table,
     * as `table.column`, with the reason (RFC 264 D-A, D-B).
     */
    private val NOT_KEYED: Map<String, String> =
      listOf(
        listOf("sessions.user_id", "verification_tokens.user_id", "user_auth_identities.user_id")
          .map { it to PHYSICALLY_DELETED },
        listOf("supporter_invites.inviter_user_id", "supporter_invites.student_id", "supporter_invites.supporter_id")
          .map { it to PHYSICALLY_DELETED },
        listOf("users_versions.id", "students_versions.id", "supporters_versions.id").map { it to ROOT_HISTORY },
        listOf("deleted_users.id", "deleted_students.id", "deleted_supporters.id").map { it to DELETED_ROW_ID },
      ).flatten().toMap()
  }

  private data class Column(
    val table: String,
    val name: String,
    val personal: Boolean,
  ) {
    val qualified get() = "$table.$name"
  }

  private data class Key(
    val table: String,
    val column: String,
    val target: String,
  ) {
    val qualified get() = "$table.$column"
  }

  /** A CHECK constraint, by the set of columns it reads. */
  private data class Check(
    val table: String,
    val columns: Set<String>,
  )

  /** What the catalog says, read in one place so every rule reads the same snapshot. */
  private data class Catalog(
    /** Every table in the reach, mapped to whether it is reached child-ward. */
    val reach: Map<String, Boolean>,
    val columns: List<Column>,
    val keys: List<Key>,
    val checks: List<Check>,
  ) {
    val childward: Set<String> get() = reach.filterValues { it }.keys
    val personalTables: Set<String> get() = columns.filter { it.personal }.map { it.table }.toSet()

    /** Rule 1: every column in the reach that is neither `pii.*` nor named not personal. */
    fun unclassified(): List<String> =
      columns
        .filter { it.table in reach && !it.personal && it.qualified !in NOT_PERSONAL }
        .map { it.qualified }

    /** The keys rule 2 inspects: from a table reached child-ward onto a personal table that is not. */
    fun keysOntoUnreachedWords(): List<Key> =
      keys.filter { it.table in childward && it.target in personalTables && it.target !in childward }

    /** Rule 2: every inspected key that is neither `owned.*` nor named not owned. */
    fun unownedKeys(ownedColumns: Set<String>): List<String> =
      keysOntoUnreachedWords()
        .map { it.qualified }
        .filter { it !in ownedColumns && it !in NOT_OWNED }

    /** Rule 3: every table holding a personal column that the walk cannot get to. */
    fun unreachedPersonalTables(): List<String> = personalTables.filter { it !in reach }.sorted()

    /** The keys rule 4 inspects: every key onto an account table. */
    fun keysOntoAccounts(): List<Key> = keys.filter { it.target in DELETED_TABLE }.distinct()

    /** Rule 4: every inspected key with no CHECK-paired sibling onto its deleted table, and not named not keyed. */
    fun keysWithoutSibling(): List<String> =
      keysOntoAccounts()
        .filter { it.qualified !in NOT_KEYED && !hasSibling(it) }
        .map { it.qualified }

    /**
     * Whether [live]'s table holds a key onto the matching deleted table that a
     * CHECK pairs with [live]: a CHECK whose columns are exactly the two.
     */
    private fun hasSibling(live: Key): Boolean {
      val deletedTable = DELETED_TABLE.getValue(live.target)
      val siblings = keys.filter { it.table == live.table && it.target == deletedTable }.map { it.column }
      return checks.any { check ->
        check.table == live.table && siblings.any { check.columns == setOf(live.column, it) }
      }
    }
  }

  /** The `owned.*`-typed columns, as `table.column`. */
  private fun ownedColumns(): Set<String> =
    query(
      """
      SELECT c.relname || '.' || a.attname
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace cn ON cn.oid = c.relnamespace
      JOIN pg_type t ON t.oid = a.atttypid
      JOIN pg_namespace tn ON tn.oid = t.typnamespace
      WHERE cn.nspname = 'public' AND tn.nspname = 'owned'
        AND a.attnum > 0 AND NOT a.attisdropped
      """.trimIndent(),
    ) { it.getString(1) }.toSet()

  private fun catalog(): Catalog = Catalog(readReach(), readColumns(), readKeys(), readChecks())

  // The reach, in one recursive query. A child-ward edge runs from a
  // referenced table to the table holding the key; a forward edge runs from
  // the table holding an owned.* key to the table it references. A table
  // stays "child-ward" only while every step to it was child-ward.
  //
  // Types are matched by the NAME of their schema (pg_namespace), never by a
  // cast to `pii.text`: before the schemas exist the query must still run,
  // and simply find nothing personal.
  private fun readReach(): Map<String, Boolean> =
    query(
      """
      WITH RECURSIVE
        key_column AS (
          SELECT con.conrelid AS child, con.confrelid AS parent, tn.nspname = 'owned' AS owned
          FROM pg_constraint con
          JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
          JOIN pg_type t ON t.oid = a.atttypid
          JOIN pg_namespace tn ON tn.oid = t.typnamespace
          WHERE con.contype = 'f'
        ),
        edge(src, dst, childward) AS (
          SELECT parent, child, TRUE FROM key_column
          UNION
          SELECT child, parent, FALSE FROM key_column WHERE owned
        ),
        reach(rel, childward) AS (
          SELECT c.oid, TRUE
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname = ANY (?)
          UNION
          SELECT e.dst, r.childward AND e.childward
          FROM edge e JOIN reach r ON e.src = r.rel
        )
      SELECT c.relname, bool_or(r.childward)
      FROM reach r JOIN pg_class c ON c.oid = r.rel
      GROUP BY c.relname
      """.trimIndent(),
      { stmt -> stmt.setArray(1, connection.createArrayOf("text", SEEDS.toTypedArray())) },
    ) { it.getString(1) to it.getBoolean(2) }.toMap()

  /** Every column of every table in `public`, with whether its type is `pii.*`. */
  private fun readColumns(): List<Column> =
    query(
      """
      SELECT c.relname, a.attname, tn.nspname = 'pii'
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace cn ON cn.oid = c.relnamespace
      JOIN pg_type t ON t.oid = a.atttypid
      JOIN pg_namespace tn ON tn.oid = t.typnamespace
      WHERE cn.nspname = 'public' AND c.relkind IN ('r', 'p')
        AND a.attnum > 0 AND NOT a.attisdropped
      ORDER BY c.relname, a.attnum
      """.trimIndent(),
    ) { Column(it.getString(1), it.getString(2), it.getBoolean(3)) }

  /** Every foreign-key column in `public`, with the table it references. */
  private fun readKeys(): List<Key> =
    query(
      """
      SELECT c.relname, a.attname, p.relname
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
      JOIN pg_class p ON p.oid = con.confrelid
      JOIN pg_namespace cn ON cn.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
      WHERE con.contype = 'f' AND cn.nspname = 'public'
      ORDER BY c.relname, a.attname
      """.trimIndent(),
    ) { Key(it.getString(1), it.getString(2), it.getString(3)) }

  /** Every CHECK constraint in `public`, with the columns it reads (`conkey`). */
  private fun readChecks(): List<Check> =
    query(
      """
      SELECT c.relname, array_agg(a.attname::text)
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
      JOIN pg_namespace cn ON cn.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey)
      WHERE con.contype = 'c' AND cn.nspname = 'public'
      GROUP BY con.oid, c.relname
      """.trimIndent(),
    ) { rs ->
      @Suppress("UNCHECKED_CAST")
      val columns = (rs.getArray(2).array as Array<String>).toSet()
      Check(rs.getString(1), columns)
    }

  private fun <T> query(
    sql: String,
    bind: (java.sql.PreparedStatement) -> Unit = {},
    row: (java.sql.ResultSet) -> T,
  ): List<T> =
    connection.prepareStatement(sql).use { stmt ->
      bind(stmt)
      stmt.executeQuery().use { rs ->
        buildList { while (rs.next()) add(row(rs)) }
      }
    }

  @Test
  fun `every column in the erasure reach is personal or named not personal`() {
    val offenders = catalog().unclassified()
    assertEquals(
      emptyList(),
      offenders,
      "${offenders.size} column(s) in the erasure reach are neither of a pii.* type nor on NOT_PERSONAL: " +
        "$offenders. A column that holds a person's data gets a pii.* type; any other column gets a " +
        "NOT_PERSONAL entry with its reason.",
    )
  }

  @Test
  fun `every key onto a person's words is owned or named not owned`() {
    val catalog = catalog()
    // Anti-vacuity by identity: the conversation log's key to the LLM call is
    // the one key this rule exists for. If the guard cannot see it, a typo has
    // made the rule check nothing.
    val inspected = catalog.keysOntoUnreachedWords().map { it.qualified }
    assertTrue(
      "convo_requests.llm_request_id" in inspected,
      "rule 2 must inspect convo_requests.llm_request_id (a key onto the personal llm_requests, " +
        "which the walk does not reach child-ward); it inspected $inspected",
    )
    val offenders = catalog.unownedKeys(ownedColumns())
    assertEquals(
      emptyList(),
      offenders,
      "${offenders.size} key(s) lead from an account's rows to a person's data the walk cannot reach " +
        "child-ward, and are neither owned.* nor on NOT_OWNED: $offenders. Type the key " +
        "owned.<kind> so erasure follows it, or add it to NOT_OWNED with its reason.",
    )
  }

  @Test
  fun `every table with a personal column is in the erasure reach`() {
    val catalog = catalog()
    // Anti-vacuity by identity: the account row itself and the far end of the
    // LLM log both hold personal columns. A guard that sees neither checks
    // nothing.
    assertTrue(
      catalog.personalTables.containsAll(listOf("users", "llm_responses_raw")),
      "rule 3 must see users and llm_responses_raw as personal tables; it saw ${catalog.personalTables.sorted()}",
    )
    val offenders = catalog.unreachedPersonalTables()
    assertEquals(
      emptyList(),
      offenders,
      "${offenders.size} table(s) hold a pii.* column but no erasure walk from an account reaches them: " +
        "$offenders. Give each a foreign key onto a table in the reach, or an owned.* key leading to it.",
    )
  }

  @Test
  fun `every key onto an account has its sibling`() {
    val catalog = catalog()
    // Anti-vacuity by identity: a student's account key, and both keys a
    // conversation holds onto a person. If the guard cannot see them, a typo has
    // made the rule check nothing.
    val inspected = catalog.keysOntoAccounts().map { it.qualified }
    assertTrue(
      inspected.containsAll(listOf("students.user_id", "convos.student_id", "convos.supporter_id")),
      "rule 4 must inspect students.user_id, convos.student_id and convos.supporter_id; it inspected $inspected",
    )
    val offenders = catalog.keysWithoutSibling()
    assertEquals(
      emptyList(),
      offenders,
      "${offenders.size} key(s) onto an account table have no sibling key onto the matching deleted_* " +
        "table paired with them by a CHECK over exactly the two columns, and are not on NOT_KEYED: " +
        "$offenders. Add the sibling and its pair CHECK, or add the key to NOT_KEYED with its reason.",
    )
  }

  @Test
  fun `the named lists give a reason for every entry`() {
    assertTrue(NOT_PERSONAL.isNotEmpty(), "NOT_PERSONAL must not be empty")
    val blank = (NOT_PERSONAL + NOT_OWNED + NOT_KEYED).filterValues { it.isBlank() }.keys
    assertEquals(emptySet(), blank, "every named entry needs a reason: $blank")
  }

  @Test
  fun `no named entry is stale`() {
    val catalog = catalog()
    val inReach = catalog.columns.filter { it.table in catalog.reach }
    val notPersonalInReach = inReach.filter { !it.personal }.map { it.qualified }.toSet()
    val stalePersonal = NOT_PERSONAL.keys - notPersonalInReach
    assertEquals(
      emptySet(),
      stalePersonal,
      "NOT_PERSONAL names column(s) that are not a non-personal column in the erasure reach: " +
        "$stalePersonal. Delete each entry (or, if it is now pii.*, it never belonged here).",
    )
    val staleOwned = NOT_OWNED.keys - catalog.keysOntoUnreachedWords().map { it.qualified }.toSet()
    assertEquals(
      emptySet(),
      staleOwned,
      "NOT_OWNED names column(s) that are no longer a key onto a personal table the walk does not " +
        "reach child-ward: $staleOwned. Delete each entry.",
    )
    val staleKeyed = NOT_KEYED.keys - catalog.keysOntoAccounts().map { it.qualified }.toSet()
    assertEquals(
      emptySet(),
      staleKeyed,
      "NOT_KEYED names column(s) that are no longer a key onto an account table: $staleKeyed. Delete each entry.",
    )
  }

  @Test
  fun `each rule can fail, proven inside a rolled-back transaction`() {
    connection.autoCommit = false
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("ALTER TABLE claims ADD COLUMN probe_note TEXT")
        stmt.execute(
          "CREATE TABLE probe_owner (student_id UUID REFERENCES students(id), call_id BIGINT REFERENCES llm_requests(id))",
        )
        stmt.execute("CREATE TABLE probe_keyless (note pii.text)")
        stmt.execute("CREATE TABLE probe_keyed (student_id UUID REFERENCES students(id))")
      }
      val probed = catalog()
      assertTrue(
        "claims.probe_note" in probed.unclassified(),
        "rule 1 must name claims.probe_note; it named ${probed.unclassified()}",
      )
      assertTrue(
        "probe_owner.call_id" in probed.unownedKeys(ownedColumns()),
        "rule 2 must name probe_owner.call_id; it named ${probed.unownedKeys(ownedColumns())}",
      )
      assertTrue(
        "probe_keyless" in probed.unreachedPersonalTables(),
        "rule 3 must name probe_keyless; it named ${probed.unreachedPersonalTables()}",
      )
      assertTrue(
        "probe_keyed.student_id" in probed.keysWithoutSibling(),
        "rule 4 must name probe_keyed.student_id; it named ${probed.keysWithoutSibling()}",
      )
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
    val after = catalog()
    assertFalse("claims.probe_note" in after.unclassified(), "the rolled-back probe column must be gone")
    assertFalse("probe_owner.call_id" in after.unownedKeys(ownedColumns()), "the rolled-back probe key must be gone")
    assertFalse("probe_keyless" in after.unreachedPersonalTables(), "the rolled-back probe table must be gone")
    assertFalse("probe_keyed.student_id" in after.keysWithoutSibling(), "the rolled-back probe key must be gone")
  }

  @Test
  fun `the reach follows owned keys`() {
    val reach = catalog().reach
    assertTrue(
      reach.keys.containsAll(listOf("users", "claims", "llm_requests", "llm_responses_raw")),
      "the reach must contain users, claims, llm_requests and llm_responses_raw; it holds ${reach.keys.sorted()}",
    )
    assertEquals(true, reach["claims"], "claims must be reached child-ward")
    assertEquals(false, reach["llm_requests"], "llm_requests must be reached only through an owned.* key")
    assertEquals(false, reach["llm_responses_raw"], "llm_responses_raw must be reached only through an owned.* key")
  }
}
