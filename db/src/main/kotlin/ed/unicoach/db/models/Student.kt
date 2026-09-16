package ed.unicoach.db.models

import java.time.Instant
import java.time.LocalDate

data class Student(
  override val id: StudentId,
  val userId: UserId,
  val expectedHighSchoolGraduationDate: PartialDate,
  /**
   * The student's date of birth, asked once at creation and never edited here
   * (RFC 222). A FULL date, not a [PartialDate]: "year only" cannot decide the
   * 13+ floor in the year the student turns 13.
   */
  val dateOfBirth: LocalDate,
  override val version: Int,
  override val createdAt: Instant,
  override val updatedAt: Instant,
  override val deletedAt: Instant?,
) : Identifiable<StudentId>,
  Created,
  Updated,
  Versioned,
  SoftDeletable
