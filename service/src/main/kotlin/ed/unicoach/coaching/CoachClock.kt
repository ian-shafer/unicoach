package ed.unicoach.coaching

import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * The coach's date seam (RFC 186, RFC 197 D15): the zone every date the coach is
 * told is expressed in, the words it is said in, and the one way to turn an
 * injected `InstantSource` into "today".
 *
 * At package scope rather than private to one service, because D15 is ONE
 * decision and there are now two readers of it -- `CoachingService`'s date block
 * and the admission-test timeline. Two copies of `ZoneId.of("America/New_York")`
 * is two places for a coach-facing date to be computed in a different day.
 */

/**
 * The timezone every date the coach is told is expressed in: the one College
 * Board and ACT publish and enforce their deadlines in (RFC 186, brief 0010
 * D15). Per-student inference from the residency state was rejected -- it is
 * wrong for the split-timezone states and buys nothing at date granularity,
 * because the coach speaks dates and never hours.
 */
val COACH_ZONE: ZoneId = ZoneId.of("America/New_York")

/**
 * [COACH_ZONE] in the words the coach says it, DERIVED rather than typed:
 * "Eastern Time". A literal here would go on claiming Eastern after someone moved
 * the constant.
 *
 * The same words are also written into `prompts/coach-system-prompt.md`, which no
 * constant can reach: that copy says the coach talks in dates rather than hours
 * and names the publishers' timezone, and it has to be edited by hand if this
 * zone ever moves.
 */
val COACH_ZONE_LABEL: String = COACH_ZONE.getDisplayName(TextStyle.FULL, Locale.US)

/**
 * Today, as a date, in [COACH_ZONE]. The ONE home for the rule that the zone is
 * OURS and never the injected clock's.
 *
 * `LocalDate.now(clock)` would let a caller decide what day it is, and UTC-today
 * is not a US family's today -- between 20:00 and midnight Eastern, UTC has
 * already turned over, and a coach reading it would tell an East Coast student
 * that tonight's deadline passed yesterday. RFC 186's first attempt was written
 * that way and its test proved the opposite of what it claimed; an
 * [InstantSource] carries no zone at all, so there is no zone here to read but
 * this one.
 */
fun InstantSource.coachToday(): LocalDate = LocalDate.ofInstant(instant(), COACH_ZONE)
