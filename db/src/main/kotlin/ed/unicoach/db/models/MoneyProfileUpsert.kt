package ed.unicoach.db.models

/**
 * Input for the atomic create-or-update of a student's single active money
 * profile row ([ed.unicoach.db.dao.MoneyProfilesDao.upsertForStudent],
 * RFC 134). Each profile field is apply-or-keep: a non-null [FieldWrite] is
 * written, a null one is left untouched -- kept as-is when the row exists,
 * defaulted to NULL/`unanswered` when the row is created.
 */
data class MoneyProfileUpsert(
  val studentId: StudentId,
  val income: FieldWrite<IncomeBand>? = null,
  val residency: FieldWrite<String>? = null,
  val living: FieldWrite<LivingArrangement>? = null,
) {
  /**
   * One field's write operation, sealed so the schema's value-iff-answered CHECK is
   * a compile-time fact: only [Answer] carries a value, making a valued
   * decline or a value-less answer unrepresentable. The DAO derives the
   * (value, status) column pair from this at the SQL edge.
   */
  sealed interface FieldWrite<out T> {
    data class Answer<T>(
      val value: T,
    ) : FieldWrite<T>

    data object Declined : FieldWrite<Nothing>

    data object Cleared : FieldWrite<Nothing>
  }
}
