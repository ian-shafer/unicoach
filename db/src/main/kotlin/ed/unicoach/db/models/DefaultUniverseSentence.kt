package ed.unicoach.db.models

/**
 * The default search universe (RFC 150 D52/D56) in the WORDS a coach says
 * aloud — the sentence form of the predicate `CollegesDao` enforces.
 *
 * It lives beside the models every module above `:db` already reads, and the
 * SQL that enforces it stays private to the dao package: a tool that reports
 * "the constraints I ran under" (RFC 153 D70) needs the SENTENCE, and giving it
 * the sentence by making the predicate FACTORY public exported a SQL-text
 * generator — and an `error()` that throws across a module boundary — to every
 * caller in the repo. The two still change together, because the private object
 * reads this constant rather than restating it.
 */
const val DEFAULT_UNIVERSE_SENTENCE: String = "active four-year institutions, excluding system central offices"
