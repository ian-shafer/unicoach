package ed.unicoach.db.dao

// The SQL half of PriceRuler -- the `college_search_index` columns each ruler
// reads, and the expressions that read them.
//
// It lives HERE, `internal` to `:db`, and not on the type itself. PriceRuler
// travels upward into the JSON tools for its wire vocabulary -- sort words,
// filter fields, result keys -- and a schema identifier reachable through it
// would be a column name `:college` and `:service` could name. These are the
// database's words; the type's own members are the model's.
//
// Every column is named ONCE, by a const below, and read by both the column set
// and the two emitters. That set decides which filters an `excluded_unknown` arm
// drops -- a ruler naming none counts zero by construction -- and a net kept in
// step with the SQL by hand is a net with a hole in it.
//
// Each `when` is exhaustive over the sealed type, so a third ruler fails this
// compile rather than reaching a database with no columns declared for it.
//
// A COMMENT, not a KDoc: a KDoc here would bind to the first declaration below
// and claim to document one String const.

import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.PriceRuler

/** The net ruler's value column on `college_search_index`. */
internal const val NET_PRICE_VALUE_COLUMN = "net_price_per_year_usd"

internal const val NET_PRICE_SHARE_COLUMN = "net_price_percentile_share"

internal const val PUBLISHED_IN_STATE_VALUE_COLUMN = "published_price_in_state_on_campus_per_year_usd"

internal const val PUBLISHED_OUT_OF_STATE_VALUE_COLUMN = "published_price_out_of_state_on_campus_per_year_usd"

internal const val PUBLISHED_IN_STATE_SHARE_COLUMN = "published_price_in_state_on_campus_ladder_share"

internal const val PUBLISHED_OUT_OF_STATE_SHARE_COLUMN = "published_price_out_of_state_on_campus_ladder_share"

/**
 * The index columns this ruler reads. `excluded_unknown` uses the set to decide
 * which filters an arm must drop, so a ruler that named none would count zero by
 * construction — the defect this set exists to prevent.
 */
internal val PriceRuler.columns: Set<String>
  get() =
    when (this) {
      is PriceRuler.NetPrice -> {
        setOf(NET_PRICE_VALUE_COLUMN, NET_PRICE_SHARE_COLUMN)
      }

      is PriceRuler.Published -> {
        setOf(
          PUBLISHED_IN_STATE_VALUE_COLUMN,
          PUBLISHED_OUT_OF_STATE_VALUE_COLUMN,
          PUBLISHED_IN_STATE_SHARE_COLUMN,
          PUBLISHED_OUT_OF_STATE_SHARE_COLUMN,
        )
      }
    }

/**
 * The residency-correct VALUE of one row, in SQL, over `college_search_index`
 * columns ONLY.
 *
 * Index columns only is load-bearing, not tidy: the filter and count statements
 * are pinned to name no table but `college_search_index`, and a ruler that
 * reached into `price_figures` at query time would put a join on the hot path of
 * every search.
 */
internal fun PriceRuler.valueSql(prefix: String = ""): String =
  when (this) {
    is PriceRuler.NetPrice -> {
      "$prefix$NET_PRICE_VALUE_COLUMN"
    }

    is PriceRuler.Published -> {
      tierCase(
        prefix,
        "$prefix$PUBLISHED_OUT_OF_STATE_VALUE_COLUMN",
        "$prefix$PUBLISHED_IN_STATE_VALUE_COLUMN",
      )
    }
  }

/** The same row's position on this ruler's ladder — the distance term's input. */
internal fun PriceRuler.shareSql(prefix: String = ""): String =
  when (this) {
    is PriceRuler.NetPrice -> {
      "$prefix$NET_PRICE_SHARE_COLUMN"
    }

    is PriceRuler.Published -> {
      tierCase(
        prefix,
        "$prefix$PUBLISHED_OUT_OF_STATE_SHARE_COLUMN",
        "$prefix$PUBLISHED_IN_STATE_SHARE_COLUMN",
      )
    }
  }

/**
 * The per-row tier choice, in SQL — the twin of [PriceRuler.Published.tierFor],
 * which every result key is named from.
 *
 * The family's state is written in rather than bound. It is checked for
 * MEMBERSHIP of `UsStateCodes.ALL` in `Published`'s own `init`, so it is a
 * closed vocabulary value like [InstitutionControl]'s label beside it, and the
 * same expression is needed in places that deliberately bind nothing at all
 * (the `excluded_unknown` condition and the bind-free shared-axis clause).
 */
private fun PriceRuler.Published.tierCase(
  prefix: String,
  outOfState: String,
  inState: String,
): String =
  "CASE WHEN ${prefix}control = '${InstitutionControl.PUBLIC.label}' " +
    "AND ${prefix}state IS DISTINCT FROM '$familyResidencyState' " +
    "THEN $outOfState ELSE $inState END"
