package ed.unicoach.db.dao

import ed.unicoach.db.models.FactTable

/**
 * The `source_documents` join every CDS read makes (RFC 170, D13): the two urls
 * are READ from the document, never stored beside a fact.
 *
 * One home, because it is one concern in two DAOs -- [CdsAdmissionsDao] cites
 * the three reference tables' documents and [AidPolicyDao] cites the canonical
 * ones', and a schema change to `source_documents` must not have to be found
 * twice. The fact side is aliased `f` and the document side `d`, so a caller's
 * own clauses qualify every column and nothing is ambiguous across the join.
 */
internal object SourceDocumentJoin {
  /** The projected citation columns. */
  const val CITATION_COLUMNS = "d.source_url, d.archive_url"

  /**
   * The FROM/JOIN clause for a fact source carrying `source_document_id`.
   *
   * [factSource] is a fixed DAO identifier -- an enum-held table name or a CTE
   * this object's caller declared in the same statement -- never caller data.
   * It is a `String` rather than [ed.unicoach.db.models.FactTable] because the
   * two callers name different things: the canonical read joins a CTE, and the
   * three CDS reference tables are not canonical fact tables. Each call site
   * holds its own identifier in its own enum.
   */
  fun withDocumentById(factSource: String): String = "FROM $factSource f JOIN source_documents d ON d.id = f.source_document_id"

  /** [withDocumentById] for a canonical fact table, named by the enum that owns it. */
  fun withDocumentById(table: FactTable): String = withDocumentById(table.tableName)
}
