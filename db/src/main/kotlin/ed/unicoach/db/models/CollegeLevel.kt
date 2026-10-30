package ed.unicoach.db.models

/**
 * Whether a college is a four-year school, read from `college_ipeds.inst_level`
 * (IPEDS `HD.ICLEVEL`) -- or that we hold no level for it at all (RFC 261).
 *
 * THREE cases, never a `Boolean?`: "not four-year" is a level IPEDS reported,
 * and "not held" is our silence (no `college_ipeds` row, or a row that does
 * not report the level). A reader that gives an all-years figure only at a
 * four-year school must say a different sentence for each, so each is a name.
 *
 * Not stored anywhere: `college_ipeds` keeps the raw code (CLAUDE.md's rule
 * for a source-defined code), and this is the reading of it.
 */
enum class CollegeLevel {
  /** `HD.ICLEVEL` = 1, "four or more years". */
  FOUR_YEAR,

  /** `HD.ICLEVEL` = 2 or 3: the school's programs run shorter than four years. */
  NOT_FOUR_YEAR,

  /** No `college_ipeds` row for the school, or a row that does not report `HD.ICLEVEL`. */
  NOT_HELD,
  ;

  companion object {
    /**
     * `HD.ICLEVEL` = 1, "four or more years": the one `college_ipeds.inst_level`
     * code that makes a college four-year -- ONE home for it, read by college
     * search's default universe (`CollegesDao`) and by the level read
     * (`CollegeIpedsDao.levelsByCollegeId`), so the two can never disagree about
     * which schools are four-year.
     *
     * Named rather than typed as a bare `1`: `= 1` beside `= 2` (two-year) and
     * `= 3` (less than two years) is one keystroke from dropping every
     * four-year school out of the default universe. The domain is the 0055
     * `college_ipeds_inst_level_domain_check`, which `IpedsLoader` bounds the
     * ingest against.
     */
    const val FOUR_OR_MORE_YEARS_CODE = 1

    /** `HD.ICLEVEL` = 2, "at least two but less than four years". */
    const val TWO_TO_FOUR_YEARS_CODE = 2

    /** `HD.ICLEVEL` = 3, "less than two years". */
    const val LESS_THAN_TWO_YEARS_CODE = 3

    /**
     * The reading of [college]'s one `inst_level` cell: a NULL is [NOT_HELD],
     * never "not four-year".
     *
     * An allowlist, not a fold: each published code is named, and a code
     * outside the 0055 domain throws rather than being read as "not
     * four-year" -- a false claim a family would hear about the school. The
     * CHECK keeps such a code out today; if that domain is ever widened, this
     * read fails loudly until the new code is given a meaning here.
     */
    fun of(
      instLevel: Int?,
      college: CollegeId,
    ): CollegeLevel =
      when (instLevel) {
        null -> {
          NOT_HELD
        }

        FOUR_OR_MORE_YEARS_CODE -> {
          FOUR_YEAR
        }

        TWO_TO_FOUR_YEARS_CODE, LESS_THAN_TWO_YEARS_CODE -> {
          NOT_FOUR_YEAR
        }

        else -> {
          throw IllegalStateException(
            "college_ipeds.inst_level outside the published HD.ICLEVEL set " +
              "{$FOUR_OR_MORE_YEARS_CODE, $TWO_TO_FOUR_YEARS_CODE, $LESS_THAN_TWO_YEARS_CODE}: " +
              "inst_level=[$instLevel] college_id=[${college.value}]",
          )
        }
      }
  }
}
