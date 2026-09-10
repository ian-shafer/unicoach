package ed.unicoach.college

import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.FigureStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RFC 158 phases inside a full ingest run: `money-vocabulary` after
 * `subjects`, `canonical-money` after `search-index`, the two new provenance
 * counts plus the per-status `canonical_money_summary` on the build row, and
 * run-level idempotency -- a second ingest of the same snapshot reports
 * UNCHANGED vocabulary and a byte-identical summary.
 */
class CanonicalMoneyIngestTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  private val moneyVocabularyJson: File = MoneyVocabularyFixture.COMMITTED_FILE

  private fun ingest(): CollegeScorecardLoader.IngestReport =
    runBlocking {
      loader.ingest(
        source(institutionCsv),
        source(fieldsCsv),
        source(aliasesJson),
        moneyVocabulary = source(moneyVocabularyJson),
      )
    }

  @Test
  fun `one ingest fills both fact tables and records counts, statuses and sources`() {
    val report = ingest()

    // The fixture: 5 loaded colleges x 7 mapped price cells and 10 cohort
    // cells. The eighth price cell -- the Scorecard's in-state tuition -- is
    // WITHHELD at every college on this run, because it supplies no IPEDS
    // group and so stages no residency evidence for the D1 rule to clear
    // anybody with (RFC 183; `CanonicalMoneyLoaderTest` proves the refusal).
    // The vocabulary was pre-seeded by the base fixture (P2), so this run's own
    // load reports it unchanged.
    assertEquals(35, report.canonicalMoney.priceFigureRows)
    assertEquals(50, report.canonicalMoney.cohortMoneyStatRows)
    val vocabulary = assertNotNull(report.moneyVocabulary, "the supplied vocabulary file is loaded and reported")
    assertEquals(0, vocabulary.inserted)
    assertEquals(vocabulary.rows, vocabulary.unchanged)

    // The fixture's PrivacySuppressed cells survive as their own status --
    // the slice's first-session fact, read from the run's own report.
    assertTrue((report.canonicalMoney.cohortMoneyStatStatusCounts[FigureStatus.SUPPRESSED_BY_PUBLISHER] ?: 0) > 0)

    // The tuition pair is ONE row per college on an IPEDS-less run -- the
    // out-of-state cell, which carries no residency ambiguity; the blends sit
    // in cohort_money_stats under their true population basis.
    assertEquals(
      5,
      scalar("SELECT count(*) FROM price_figures WHERE price_concept = 'tuition_and_fees'"),
    )
    assertEquals(
      5,
      scalar(
        "SELECT count(*) FROM cohort_money_stats WHERE measure = 'published_cost_blend' " +
          "AND population = 'title_iv_aided_undergraduates'",
      ),
    )

    // Debt, earnings and pell land under their true population bases too
    // (the RFC mapping table's undated triple), asserted end to end.
    withSession { session ->
      session
        .prepareStatement(
          "SELECT DISTINCT measure, population, residency_scope, aid_scope, vintage FROM cohort_money_stats " +
            "WHERE measure IN ('median_debt_at_completion', 'median_earnings_10y', 'pell_share') ORDER BY measure",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            val bases = mutableListOf<List<String?>>()
            while (rs.next()) {
              bases += listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
            }
            assertEquals(
              listOf(
                listOf("median_debt_at_completion", "federal_loan_borrowing_completers", "all", "federal_loan_borrowing", null),
                listOf("median_earnings_10y", "employed_not_enrolled_10y_after_entry", "all", "all", null),
                listOf("pell_share", "undergraduates", "all", "all", null),
              ),
              bases,
            )
          }
        }
    }

    // The vocabulary file is digested beside the other sources.
    assertTrue(report.sources.any { it.fileName == moneyVocabularyJson.name })
    assertEquals(4, report.sources.size)

    // Provenance (P11): both counts and the per-status summary, on the row.
    withSession { session ->
      val (priceRows, statRows, summary) = buildColumns(session, report.buildId)
      assertEquals(report.canonicalMoney.priceFigureRows, priceRows)
      assertEquals(report.canonicalMoney.cohortMoneyStatRows, statRows)
      assertNotNull(summary)
      assertTrue(summary.contains("\"price_figures\""), summary)
      assertTrue(summary.contains("\"suppressed_by_publisher\""), summary)
    }
  }

  @Test
  fun `a second ingest of the same snapshot is idempotent, summary byte for byte`() {
    val first = ingest()
    val second = ingest()

    assertEquals(0, second.colleges.changed, "the premise: nothing changed")
    val vocabulary = assertNotNull(second.moneyVocabulary)
    assertEquals(0, vocabulary.inserted)
    assertEquals(0, vocabulary.changed)
    assertEquals(0, vocabulary.deleted)
    assertEquals(vocabulary.rows, vocabulary.unchanged, "an unchanged vocabulary re-load is all UNCHANGED")

    assertEquals(first.canonicalMoney.priceFigureRows, second.canonicalMoney.priceFigureRows)
    assertEquals(first.canonicalMoney.cohortMoneyStatRows, second.canonicalMoney.cohortMoneyStatRows)
    assertEquals(first.canonicalMoney.priceFigureStatusCounts, second.canonicalMoney.priceFigureStatusCounts)
    assertEquals(first.canonicalMoney.cohortMoneyStatStatusCounts, second.canonicalMoney.cohortMoneyStatStatusCounts)

    withSession { session ->
      val (_, _, firstSummary) = buildColumns(session, first.buildId)
      val (_, _, secondSummary) = buildColumns(session, second.buildId)
      assertEquals(firstSummary, secondSummary, "the provenance summary is byte-identical across re-ingests")
    }
  }

  @Test
  fun `a run without a vocabulary file still fills, against the loaded tables`() {
    // Omit-vs-zero for the FILE; the TABLES are the precondition (P2). The
    // base fixture seeded them, standing in for any earlier run's load.
    val report =
      runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson)) }
    assertEquals(null, report.moneyVocabulary, "no file, no money-vocabulary phase, no fabricated zeros")
    // 35, not 40: no IPEDS group, so the in-state tuition cell is withheld at
    // all five colleges (RFC 183 D1 fails closed).
    assertEquals(35, report.canonicalMoney.priceFigureRows)
    assertEquals(3, report.sources.size, "no vocabulary digest was recorded for a file this run never read")
  }

  private fun scalar(sql: String): Int =
    withSession { session ->
      session.prepareStatement(sql).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    }

  private fun buildColumns(
    session: SqlSession,
    id: UUID,
  ): Triple<Int?, Int?, String?> =
    session
      .prepareStatement(
        "SELECT price_figure_rows, cohort_money_stat_rows, canonical_money_summary::text " +
          "FROM college_index_build WHERE id = ?",
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "no college_index_build row for [$id]")
          Triple(
            rs.getInt(1).takeUnless { rs.wasNull() },
            rs.getInt(2).takeUnless { rs.wasNull() },
            rs.getString(3),
          )
        }
      }
}
