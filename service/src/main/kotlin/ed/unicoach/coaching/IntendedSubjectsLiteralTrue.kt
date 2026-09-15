package ed.unicoach.coaching

/**
 * The literal-true rule for the intended-subjects STATED keys — `declined` and
 * `undecided` — read by BOTH doors (RFC 205 D17).
 *
 * Both doors count the keys that are PRESENT, not the keys that are true. So a
 * present `false` is a MALFORMED call, not "leave that one alone": omitting the
 * key already says that, and a caller sending `false` has asked for something
 * neither door can honour. Absence is the only way to mean "not this one".
 *
 * Here, once, because the REST door and the chat tool must agree about what a
 * valid answer IS; a second copy of the rule is a second contract waiting to
 * drift, which is exactly what it did before. [keys] is given in the order the
 * caller wants its refusals, so a door that pools them names them all and a
 * door that states one takes the first.
 *
 * The RULE is shared; the sentence each door says is its own — the chat tool
 * writes a reason the model reads, the route writes its pooled `FieldError`s.
 */
fun literalTrueViolations(vararg keys: Pair<String, Boolean?>): List<String> = keys.filter { it.second == false }.map { it.first }
