package ed.unicoach.family

import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * The adults the family-side tests read as: an account, an account linked to a
 * student, and a subject-scoped tool run as one of them -- ONE home, on the
 * [CostsTestDb] session every family test already shares, so a change to how a
 * linked adult is built (a new NOT NULL column on `users`, a new link field)
 * is made once.
 */
object FamilyTestDb {
  /** A fresh adult account with no link to anyone. */
  fun createUser(): UserId {
    val userId = UUID.randomUUID()
    CostsTestDb.sqlSession
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Parent Jane', 'hash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "parent-$userId@example.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  /** A fresh adult account with a live link to [studentId]. */
  fun linkedAdult(studentId: StudentId): Pair<UserId, SupporterStudentLink> {
    val userId = createUser()
    val supporter = SupportersDao.create(CostsTestDb.sqlSession, NewSupporter(userId)).getOrThrow()
    val link =
      SupporterStudentLinksDao
        .create(CostsTestDb.sqlSession, NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.PARENT))
        .getOrThrow()
    return userId to link
  }

  /** [tool] run with no input, as [viewer] asking about [studentId]. */
  fun runTool(
    tool: SubjectScopedChatTool,
    viewer: UserId,
    studentId: StudentId,
  ): JsonObject = runBlocking { tool.execute(Subject(viewer, studentId), buildJsonObject {}) }
}
