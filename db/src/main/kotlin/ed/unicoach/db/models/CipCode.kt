package ed.unicoach.db.models

/**
 * One `college_programs` row's CIP code -- the publisher's grain, and a
 * program's identity at a fixed credential level (RFC 231).
 *
 * A TYPE rather than a bare `String`, for the reason [CollegeId] and
 * [ed.unicoach.common.util.CalendarYear] are types: a CIP code and a subject
 * slug are two different facts that share one representation, they are `TEXT`
 * in the schema, and they sit side by side as adjacent parameters on the key
 * the two program reads join on
 * ([ed.unicoach.db.dao.SubjectProgramMatch]) and on the address every refusal
 * on that path prints. While both were `String`, transposing them compiled: the
 * money read joined the program read on a key that matches nothing, and the
 * operator's address named the school's major as its code.
 *
 * Carries no format guard. `college_programs_cip_code_format_check`
 * (`CHECK (cip_code ~ '^([0-9]{2}){1,3}$')`, `db/schema/0021`) is the schema's,
 * `CipPrefix` canonicalizes what a MODEL writes, and a second copy of either
 * here would be one more place for the same rule to drift -- this type's whole
 * job is to say WHICH of two strings this is.
 *
 * It refuses the EMPTY string, and only that. This is not a third statement of
 * the format rule: it is the one value that makes the type meaningless, because
 * a program's identity that names no program is not a narrower identity but no
 * identity at all -- it would render as a `programs[]` entry with an empty
 * `cip_code`, which is the shape this slice exists to make impossible. The
 * sibling type this run added ([ed.unicoach.common.util.CalendarYear]) excludes
 * its own sentinels for the same reason, and an empty CIP code is the sentinel
 * of a string identity.
 *
 * Digits-only, as the column stores it (`'0901'`, never `'09.01'`): the loaders
 * strip the conventional dot before the value reaches the store, so nothing
 * that reads a row has to.
 */
@JvmInline
value class CipCode(
  val value: String,
) {
  init {
    require(value.isNotEmpty()) { "a CIP code is a program's identity and is never empty" }
  }
}
