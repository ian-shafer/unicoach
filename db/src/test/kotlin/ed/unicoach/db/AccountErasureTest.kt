package ed.unicoach.db

import ed.unicoach.db.dao.AccountLlmCostDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.LlmCostAggregate
import ed.unicoach.db.models.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The erasure walk, `erase_account`, held against the real schema (RFC 266
 * §3/§4, slice deletion/03/the-walk, brief 0002 D1/D3/D9/D10).
 *
 * Each test builds [ErasureFixture]'s account inside one transaction that is
 * rolled back. Which rows are "the account's" is never taken from the walk: it
 * is the set of rows the fixture's account build wrote, read as the primary
 * keys that appeared between a snapshot before the build and one after it. The
 * walk is then judged against that set, so a walk that missed a table cannot
 * also hide the miss.
 *
 * Test 1 asserts the fixture against the catalog: a table in `erasure_edge`'s
 * reach the fixture writes no row into fails the gate by name (decision D-E).
 */
class AccountErasureTest {
  companion object {
    private lateinit var connection: Connection

    private const val RAISED = "P0001"

    /** The deleted tables: the reach includes them, and the walk writes them (§3 step 3). */
    private val DELETED_TABLES = setOf("deleted_users", "deleted_students", "deleted_supporters")

    /** D10's tables, physically deleted by the walk. */
    private val PHYSICALLY_DELETED = setOf("sessions", "verification_tokens", "user_auth_identities", "supporter_invites")

    /**
     * The two tables whose fixture rows carry a key onto the OTHER account as
     * well (the supporter's link and its link request): by D-A they are never
     * erased, only their key onto the erased supporter moves.
     */
    private val SHARED_WITH_OTHER = setOf("supporter_student_links", "supporter_student_links_versions", "supporter_link_requests")

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
  }

  @BeforeEach
  fun begin() {
    connection.autoCommit = false
  }

  @AfterEach
  fun rollback() {
    connection.rollback()
    connection.autoCommit = true
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Catalog and row reads
  // ---------------------------------------------------------------------------

  private fun <T> query(
    sql: String,
    vararg args: Any?,
    row: (ResultSet) -> T,
  ): List<T> = RawSql.list(connection, sql, *args, read = row)

  private fun long(
    sql: String,
    vararg args: Any?,
  ): Long = query(sql, *args) { it.getLong(1) }.single()

  /** Every table in `erasure_edge`'s reach from the three account tables, less the deleted tables. */
  private fun reach(): Set<String> =
    query(
      """
      WITH RECURSIVE
        edge(src, dst) AS (
          SELECT parent::oid, child::oid FROM erasure_edge
          UNION
          SELECT child::oid, parent::oid FROM erasure_edge WHERE owned
        ),
        reach(rel) AS (
          SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname IN ('users', 'students', 'supporters')
          UNION
          SELECT e.dst FROM edge e JOIN reach r ON e.src = r.rel
        )
      SELECT c.relname FROM reach r JOIN pg_class c ON c.oid = r.rel
      """.trimIndent(),
    ) { it.getString(1) }.toSet() - DELETED_TABLES

  /** Every base table in `public`. */
  private fun tables(): Set<String> =
    query(
      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
        "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')",
    ) { it.getString(1) }.toSet()

  private fun columns(
    table: String,
    schemaOfType: String? = null,
  ): List<String> =
    query(
      """
      SELECT a.attname FROM pg_attribute a
      JOIN pg_type t ON t.oid = a.atttypid JOIN pg_namespace tn ON tn.oid = t.typnamespace
      WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped AND (?::text IS NULL OR tn.nspname = ?)
      ORDER BY a.attnum
      """.trimIndent(),
      table,
      schemaOfType,
      schemaOfType,
    ) { it.getString(1) }

  private fun personalColumns(table: String) = columns(table, "pii")

  /** The table's primary key as one text value per row, or every column when it has none. */
  private fun keyExpression(table: String): String {
    val pk =
      query(
        "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey) " +
          "WHERE i.indrelid = ?::regclass AND i.indisprimary ORDER BY a.attnum",
        table,
      ) { it.getString(1) }
    return "ROW(${pk.ifEmpty { columns(table) }.joinToString(", ") { "t.\"$it\"" }})::text"
  }

  private fun keys(table: String): Set<String> = query("SELECT ${keyExpression(table)} FROM \"$table\" t") { it.getString(1) }.toSet()

  private fun snapshotKeys(tables: Set<String>): Map<String, Set<String>> = tables.associateWith { keys(it) }

  /** The rows of [table] whose key is in [rowKeys], as JSON objects. */
  private fun rows(
    table: String,
    rowKeys: Set<String>,
  ): List<JsonObject> =
    if (rowKeys.isEmpty()) {
      emptyList()
    } else {
      query(
        "SELECT to_jsonb(t)::text FROM \"$table\" t WHERE ${keyExpression(table)} = ANY (?)",
        connection.createArrayOf("text", rowKeys.toTypedArray()),
      ) { Json.parseToJsonElement(it.getString(1)).jsonObject }
    }

  /** Every row of [table], by key, as its full JSON. */
  private fun allRows(table: String): Map<String, String> =
    query("SELECT ${keyExpression(table)}, to_jsonb(t)::text FROM \"$table\" t") { it.getString(1) to it.getString(2) }.toMap()

  private fun counts(): Map<String, Long> = tables().associateWith { long("SELECT count(*) FROM \"$it\"") }

  /** Every versioned table's `id:version` list, as one digest per table. */
  private fun versions(): Map<String, String> =
    tables()
      .filter { "version" in columns(it) && "id" in columns(it) && !it.endsWith("_versions") }
      .associateWith {
        query(
          "SELECT md5(coalesce(string_agg(id::text || ':' || version, ',' ORDER BY id), '')) FROM \"$it\"",
        ) { rs -> rs.getString(1) }.single()
      }

  private data class Touched(
    val table: String,
    val moved: Long,
    val erased: Long,
    val deleted: Long,
  )

  private fun erase(userId: UserId): List<Touched> =
    query("SELECT table_name, moved, erased, deleted FROM erase_account(?)", userId.value) {
      Touched(it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4))
    }

  private fun JsonObject.isNull(column: String): Boolean = this[column] == null || this[column] is JsonNull

  /** The live account keys and their CHECK-paired siblings, as `(table, live, sibling, account table)`. */
  private fun keyPairs(): List<List<String>> =
    query(
      """
      SELECT live.child::text, live.child_column, sibling.child_column, live.parent::text
      FROM erasure_edge live
      JOIN erasure_edge sibling ON sibling.child = live.child
       AND sibling.parent::text = 'deleted_' || live.parent::text
      JOIN pg_attribute la ON la.attrelid = live.child AND la.attname = live.child_column
      JOIN pg_attribute sa ON sa.attrelid = sibling.child AND sa.attname = sibling.child_column
      WHERE live.parent::text IN ('users', 'students', 'supporters')
        AND EXISTS (SELECT 1 FROM pg_constraint chk WHERE chk.conrelid = live.child AND chk.contype = 'c'
                    AND cardinality(chk.conkey) = 2 AND la.attnum = ANY (chk.conkey) AND sa.attnum = ANY (chk.conkey))
      """.trimIndent(),
    ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

  /** Builds the other student, snapshots, builds the account; returns the account and the rows it wrote. */
  private fun buildAccount(): Pair<ErasureFixture.Account, Map<String, Set<String>>> {
    val other = ErasureFixture.otherStudent(session)
    val reach = reach()
    val before = snapshotKeys(reach)
    val account = ErasureFixture.account(session, other)
    val written = reach.associateWith { keys(it) - before.getValue(it) }
    return account to written
  }

  private fun closeAndErase(account: ErasureFixture.Account): List<Touched> {
    ErasureFixture.close(session, account.userId)
    return erase(account.userId)
  }

  // ---------------------------------------------------------------------------
  // 1: the fixture covers the reach
  // ---------------------------------------------------------------------------

  @Test
  fun `the fixture covers the reach`() {
    val (_, written) = buildAccount()
    val uncovered = written.filterValues { it.isEmpty() }.keys.sorted()
    assertTrue(written.size > 40, "the reach must be read from the catalog; it held ${written.keys.sorted()}")
    assertEquals(
      emptyList(),
      uncovered,
      "${uncovered.size} table(s) in erasure_edge's reach hold no row the ErasureFixture account wrote: $uncovered. " +
        "Extend ErasureFixture.account so the account reaches a row in each.",
    )
  }

  // ---------------------------------------------------------------------------
  // 2: no personal value survives on anything the account owns
  // ---------------------------------------------------------------------------

  @Test
  fun `erasure leaves no personal value on anything the account owns`() {
    val (account, written) = buildAccount()
    val roots = listOf(account.userId.value, account.studentId.value, account.supporterId.value).map { it.toString() }
    val pairs = keyPairs()
    val keyedBefore =
      pairs.associateWith { (table, live) ->
        long("SELECT count(*) FROM \"$table\" WHERE \"$live\"::text = ANY (?)", connection.createArrayOf("text", roots.toTypedArray()))
      }

    closeAndErase(account)

    val offenders = mutableListOf<String>()
    for ((table, rowKeys) in written) {
      if (table in PHYSICALLY_DELETED || table in SHARED_WITH_OTHER) continue
      val personal = personalColumns(table)
      val hasDeletedAt = "deleted_at" in columns(table)
      // A history table is judged by every version of each entity the build
      // wrote, so the rows the walk's own UPDATEs wrote are judged too.
      val judged =
        if (table.endsWith("_versions")) {
          val ids = rows(table, rowKeys).map { it.getValue("id").jsonPrimitive.content }.toSet()
          query(
            "SELECT to_jsonb(t)::text FROM \"$table\" t WHERE t.id::text = ANY (?)",
            connection.createArrayOf("text", ids.toTypedArray()),
          ) {
            Json.parseToJsonElement(it.getString(1)).jsonObject
          }
        } else {
          rows(table, rowKeys)
        }
      for (row in judged) {
        personal.filterNot { row.isNull(it) }.forEach { offenders += "$table.$it is still set on ${row["id"] ?: row}" }
        if (hasDeletedAt && row.isNull("deleted_at")) offenders += "$table.deleted_at is NULL on ${row["id"] ?: row}"
      }
    }
    assertEquals(emptyList(), offenders, "every pii.* column must be NULL and deleted_at set on every row the account owns")

    for ((pair, before) in keyedBefore) {
      val (table, live, sibling) = pair
      val array = connection.createArrayOf("text", roots.toTypedArray())
      assertEquals(
        0L,
        long("SELECT count(*) FROM \"$table\" WHERE \"$live\"::text = ANY (?)", array),
        "$table.$live must not name the erased account",
      )
      assertEquals(
        before,
        long("SELECT count(*) FROM \"$table\" WHERE \"$sibling\"::text = ANY (?)", array),
        "$table.$sibling must hold every id $live held",
      )
    }
    assertTrue(keyedBefore.values.sum() > 20, "the fixture must key many rows on the account; it keyed ${keyedBefore.values.sum()}")

    val closedAt = query("SELECT deleted_at FROM users WHERE id = ?", account.userId.value) { it.getTimestamp(1) }.single()
    assertEquals(closedAt, query("SELECT closed_at FROM deleted_users WHERE id = ?", account.userId.value) { it.getTimestamp(1) }.single())
    assertEquals(
      1L,
      long("SELECT count(*) FROM deleted_students WHERE id = ? AND user_id = ?", account.studentId.value, account.userId.value),
    )
    assertEquals(
      1L,
      long("SELECT count(*) FROM deleted_supporters WHERE id = ? AND user_id = ?", account.supporterId.value, account.userId.value),
    )
  }

  // ---------------------------------------------------------------------------
  // 3: the LLM log keeps its tokens and cost
  // ---------------------------------------------------------------------------

  @Test
  fun `the LLM log keeps its tokens and cost`() {
    val (account, _) = buildAccount()
    val measures = "input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost_nanodollars, cost_is_estimated"
    val read = { id: Long ->
      query("SELECT $measures FROM llm_responses WHERE request_id = ?", id) { rs -> (1..6).map { rs.getObject(it) } }.single()
    }
    val before = account.llmRequestIds.associateWith { read(it.value) }

    closeAndErase(account)

    for (id in account.llmRequestIds) {
      assertEquals(before.getValue(id), read(id.value), "call [${id.value}] must keep its tokens and cost")
      query("SELECT content, reason, deleted_at FROM llm_responses WHERE request_id = ?", id.value) { rs ->
        assertNull(rs.getString("content"), "call [${id.value}]'s response content must be NULL")
        assertNotNull(rs.getTimestamp("deleted_at"))
      }
      assertEquals(
        1L,
        long("SELECT count(*) FROM llm_requests WHERE id = ? AND content IS NULL AND system IS NULL AND deleted_at IS NOT NULL", id.value),
      )
    }
  }

  // ---------------------------------------------------------------------------
  // 4: only D10's rows are deleted
  // ---------------------------------------------------------------------------

  @Test
  fun `only D10's rows are deleted`() {
    val other = ErasureFixture.otherStudent(session)
    val untouchedBefore = (reach() + "jobs").associateWith { allRows(it) }
    val account = ErasureFixture.account(session, other)
    ErasureFixture.close(session, account.userId)
    val user = account.userId.value
    val accountD10 =
      mapOf(
        "sessions" to long("SELECT count(*) FROM sessions WHERE user_id = ?", user),
        "verification_tokens" to long("SELECT count(*) FROM verification_tokens WHERE user_id = ?", user),
        "user_auth_identities" to long("SELECT count(*) FROM user_auth_identities WHERE user_id = ?", user),
        "supporter_invites" to long("SELECT count(*) FROM supporter_invites WHERE inviter_user_id = ?", user),
      )
    accountD10.forEach { (table, n) -> assertTrue(n > 0, "the fixture must write the account a $table row") }
    val versionSumsBefore = versionSums()
    val countsBefore = counts()

    erase(account.userId)

    val countsAfter = counts()
    val versionGain = versionSums().mapValues { (table, sum) -> sum - versionSumsBefore.getValue(table) }
    val unexpected = mutableListOf<String>()
    for ((table, before) in countsBefore) {
      val after = countsAfter.getValue(table)
      val expected =
        when {
          table in accountD10 -> before - accountD10.getValue(table)

          table in DELETED_TABLES -> before + 1

          table in setOf("jobs", "job_attempts") -> after

          // judged by test 5
          table.endsWith("_versions") -> before + (versionGain[table.removeSuffix("_versions")] ?: 0L)

          else -> before
        }
      if (after != expected) unexpected += "$table: $before -> $after (expected $expected)"
    }
    assertEquals(emptyList(), unexpected, "row counts must change only in D10's tables, the deleted tables and the history the walk wrote")

    // Nothing that existed before the account was built changed: the other
    // student's rows, and every other row on this database.
    val changed =
      untouchedBefore.flatMap { (table, before) ->
        val after = allRows(table)
        before.filter { (key, json) -> after[key] != json }.keys.map { "$table $it" }
      }
    assertEquals(emptyList(), changed, "no row that predates the account may change")

    // The link onto the other student keeps its row; only the erased supporter's key moved.
    query(
      "SELECT supporter_id, deleted_supporter_id, student_id, deleted_at FROM supporter_student_links WHERE deleted_supporter_id = ?",
      account.supporterId.value,
    ) { rs ->
      assertNull(rs.getObject("supporter_id"))
      assertEquals(other.studentId.value, rs.getObject("student_id", UUID::class.java))
      assertNull(rs.getTimestamp("deleted_at"), "the link is the other student's too, and is not erased (D-A)")
    }.also { assertEquals(1, it.size) }
  }

  /** Each versioned live table's sum of `version`: what it gains is the history rows its UPDATEs wrote. */
  private fun versionSums(): Map<String, Long> =
    tables()
      .filter { "${it}_versions" in tables() && "version" in columns(it) }
      .associateWith { long("SELECT coalesce(sum(version), 0) FROM \"$it\"") }

  // ---------------------------------------------------------------------------
  // 5: the jobs naming the account are deleted, in every status
  // ---------------------------------------------------------------------------

  @Test
  fun `the jobs naming the account are deleted, in every status`() {
    val (account, _) = buildAccount()
    val caseBlind =
      listOf(account.userId.value, account.studentId.value, account.supporterId.value, account.studentConvoId.value)
        .map { it.toString() } + listOf(account.email.lowercase(), account.previousEmail.lowercase())
    val jobs =
      query("SELECT id, status, payload::text FROM jobs") { Triple(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3)) }
    val naming =
      jobs.filter { (_, _, payload) -> caseBlind.any { it in payload.lowercase() } || account.sessionTokenHash in payload }
    assertEquals(setOf("SCHEDULED", "RUNNING", "COMPLETED", "DEAD_LETTERED"), naming.map { it.second }.toSet())
    assertTrue(naming.size >= 28, "the fixture must write a job per identifier per status; it wrote ${naming.size}")
    val survivors =
      jobs.filter { (_, _, payload) ->
        account.other.studentId.value
          .toString() in payload || account.originalTransactionId in payload
      }
    assertEquals(2, survivors.size)

    closeAndErase(account)

    val ids = connection.createArrayOf("uuid", naming.map { it.first }.toTypedArray())
    assertEquals(0L, long("SELECT count(*) FROM jobs WHERE id = ANY (?)", ids), "every job naming the account must be gone")
    assertEquals(0L, long("SELECT count(*) FROM job_attempts WHERE job_id = ANY (?)", ids), "with its attempts")
    val kept = connection.createArrayOf("uuid", survivors.map { it.first }.toTypedArray())
    assertEquals(
      2L,
      long("SELECT count(*) FROM jobs WHERE id = ANY (?)", kept),
      "the other account's job and the subscription refresh survive",
    )
  }

  // ---------------------------------------------------------------------------
  // 6: a second erasure writes nothing
  // ---------------------------------------------------------------------------

  @Test
  fun `a second erasure writes nothing`() {
    val (account, _) = buildAccount()
    assertTrue(closeAndErase(account).isNotEmpty(), "the first erasure must touch rows")
    val countsBefore = counts()
    val versionsBefore = versions()

    assertEquals(emptyList(), erase(account.userId), "a second erasure must report nothing")
    assertEquals(countsBefore, counts(), "a second erasure must add or remove no row")
    assertEquals(versionsBefore, versions(), "a second erasure must bump no version")
  }

  // ---------------------------------------------------------------------------
  // 7: the walk refuses a live account and an unknown id
  // ---------------------------------------------------------------------------

  private fun refusal(write: () -> Unit): PSQLException {
    val savepoint = connection.setSavepoint()
    try {
      write()
    } catch (e: SQLException) {
      connection.rollback(savepoint)
      return e as? PSQLException ?: fail("expected a PSQLException, got [$e]")
    }
    connection.releaseSavepoint(savepoint)
    fail("the erasure was accepted; it must be refused")
  }

  @Test
  fun `the walk refuses a live account and an unknown id`() {
    val account = ErasureFixture.build(session)
    val before = counts()

    val live = refusal { erase(account.userId) }
    assertEquals(RAISED, live.sqlState)
    assertEquals("erase_account_closed", live.serverErrorMessage?.constraint)

    val unknown = refusal { erase(UserId(UUID.randomUUID())) }
    assertEquals(RAISED, unknown.sqlState)
    assertEquals("erase_account_exists", unknown.serverErrorMessage?.constraint)

    assertEquals(before, counts(), "a refused erasure writes nothing")
    assertEquals(0L, long("SELECT count(*) FROM deleted_users WHERE id = ?", account.userId.value))
  }

  @Test
  fun `the walk refuses an account with a second students row`() {
    val account = ErasureFixture.build(session)
    ErasureFixture.close(session, account.userId)
    // A second students row whose key has already moved onto the account's
    // deleted sibling: the live row's uniqueness does not bound it.
    connection
      .prepareStatement(
        "INSERT INTO deleted_users (id, created_at, closed_at) SELECT id, created_at, deleted_at FROM users WHERE id = ?",
      ).use { stmt ->
        stmt.setObject(1, account.userId.value)
        stmt.executeUpdate()
      }
    connection
      .prepareStatement(
        "INSERT INTO students (id, deleted_user_id, expected_high_school_graduation_on, " +
          "expected_high_school_graduation_precision, date_of_birth) VALUES (?, ?, DATE '2028-01-01', 'year', DATE '2009-07-10')",
      ).use { stmt ->
        stmt.setObject(1, UUID.randomUUID())
        stmt.setObject(2, account.userId.value)
        stmt.executeUpdate()
      }
    val before = counts()

    val surplus = refusal { erase(account.userId) }
    assertEquals(RAISED, surplus.sqlState)
    assertEquals("erase_account_one_root", surplus.serverErrorMessage?.constraint)
    assertEquals(before, counts(), "a refused erasure writes nothing")
  }

  // ---------------------------------------------------------------------------
  // 8: spend is the same before and after erasure
  // ---------------------------------------------------------------------------

  @Test
  fun `spend is the same before and after erasure`() {
    val (account, _) = buildAccount()
    val now = Instant.now()
    val windows =
      listOf(
        now.minus(Duration.ofDays(1)) to now.plus(Duration.ofHours(1)),
        now.minus(Duration.ofDays(60)) to now.minus(Duration.ofDays(30)),
        now.minus(Duration.ofDays(365)) to now.plus(Duration.ofDays(365)),
      )
    val read = {
      listOf(AccountLlmCostDao.lifetimeCost(session, account.userId).getOrThrow()) +
        windows.map { (start, end) -> AccountLlmCostDao.windowedCost(session, account.userId, start, end).getOrThrow() }
    }
    val before = read().map(::figures)
    assertEquals(
      ErasureFixture.CALL_COST_NANODOLLARS * account.llmRequestIds.size,
      before.first().first,
      "the meter must count every call the account owns, on both sides",
    )

    closeAndErase(account)

    assertEquals(before, read().map(::figures), "spend must read the same, under the same id, after erasure (D1)")
  }

  private fun figures(aggregate: LlmCostAggregate) =
    Triple(aggregate.costNanodollars.value, aggregate.uncostedCalls, aggregate.estimatedCalls)

  // ---------------------------------------------------------------------------
  // 9: a parent's conversation survives the student's erasure
  // ---------------------------------------------------------------------------

  @Test
  fun `a parent's conversation survives the student's erasure`() {
    val pair = ErasureFixture.parentAndStudent(session)
    val convo = pair.convoId.value
    val nameBefore = query("SELECT name FROM convos WHERE id = ?", convo) { it.getString(1) }.single()

    ErasureFixture.close(session, pair.studentUserId)
    erase(pair.studentUserId)

    query("SELECT student_id, deleted_student_id, supporter_id, name, deleted_at FROM convos WHERE id = ?", convo) { rs ->
      assertNull(rs.getObject("student_id"))
      assertEquals(pair.studentId.value, rs.getObject("deleted_student_id", UUID::class.java))
      assertEquals(pair.supporterId.value, rs.getObject("supporter_id", UUID::class.java))
      assertEquals(nameBefore, rs.getString("name"), "the parent's own words are not the student's to erase")
      assertNull(rs.getTimestamp("deleted_at"))
    }.also { assertEquals(1, it.size) }
    assertEquals(
      1L,
      long("SELECT count(*) FROM llm_requests WHERE id = ? AND content IS NOT NULL AND deleted_at IS NULL", pair.llmRequestId.value),
      "the parent's LLM text is intact",
    )
    val listed = ConvosDao.listBySupporterWithActivity(session, pair.supporterId).getOrThrow()
    assertEquals(listOf(pair.convoId), listed.map { it.convo.id })
    assertEquals(pair.studentId, listed.single().convo.studentId, "the conversation still names the student it is about (D9)")

    ErasureFixture.close(session, pair.parentUserId)
    erase(pair.parentUserId)

    query("SELECT supporter_id, deleted_supporter_id, name, deleted_at FROM convos WHERE id = ?", convo) { rs ->
      assertNull(rs.getObject("supporter_id"))
      assertEquals(pair.supporterId.value, rs.getObject("deleted_supporter_id", UUID::class.java))
      assertNull(rs.getString("name"), "erasing the parent erases the parent's conversation")
      assertNotNull(rs.getTimestamp("deleted_at"))
    }
    assertEquals(
      1L,
      long("SELECT count(*) FROM llm_requests WHERE id = ? AND content IS NULL AND deleted_at IS NOT NULL", pair.llmRequestId.value),
    )
  }

  // ---------------------------------------------------------------------------
  // 10: the declaration is closed when the walk returns
  // ---------------------------------------------------------------------------

  @Test
  fun `the declaration is closed when the walk returns`() {
    val account = ErasureFixture.build(session)
    closeAndErase(account)
    assertFalse(query("SELECT erasure_admits()") { it.getBoolean(1) }.single(), "the rest of the transaction must be guarded again")
    assertEquals("", query("SELECT current_setting('unicoach.erasure_account', true)") { it.getString(1) }.single())
  }
}
