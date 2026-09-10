package ed.unicoach.coaching.costs

import ed.unicoach.db.models.PublishedCell

/**
 * WHICH published cell one fixture PRICE row stands for: [publisher]'s own
 * column for [field], from that publisher's one naming home (RFC 184). A null
 * [publisher] is the SCORECARD -- the only publisher of a price row that is not
 * an IPEDS survey, and the default every fixture school has always had.
 *
 * Here, beside [ScorecardVariableNames] and [IpedsVariableNames], because this
 * is the rule those two homes exist for: it decides WHICH home names a row, and
 * both `:service`'s canonical fixtures and `:public-web`'s report-page fixture
 * build price rows. Written twice, the two copies immediately disagreed -- one
 * asked the IPEDS vocabulary for the field's real column while the other went on
 * seeding the literal `FIXTURE` under a survey.
 *
 * The choice is PER FIELD, and that is the whole rule. A Scorecard row has
 * always been named this way; an IPEDS row used to take one cell the caller
 * handed in and wear it on every line of the budget, which made a
 * books-and-supplies row claim the in-state tuition column's name. Nothing
 * caught it: a [PublishedCell.Surveyed] reads its tier off the SOURCE, so any
 * string at all is accepted, where [PublishedCell.ScorecardCell.of] refuses one
 * the tier table does not answer for.
 *
 * [location] names the row for the one arm that can refuse -- the Scorecard's --
 * and is the fixture's own description of itself.
 *
 * Not `publishedPriceCellOf`: the return type already says CELL, and the sibling
 * naming homes say `priceOf` for the same idea. `published` is what the name
 * adds -- the publisher's own answer, not this repo's.
 *
 * EXHAUSTIVE over [PublishedCell.Survey] with no `else`: SFA is a survey of AID
 * -- it publishes cohort statistics and no charge column at all -- so a price
 * row can never be one of its cells, and a third survey must say which column
 * names its rows before this compiles.
 */
fun publishedPriceOf(
  field: CostField,
  publisher: PublishedCell.Survey?,
  location: String,
): PublishedCell =
  when (publisher) {
    null -> {
      PublishedCell.ScorecardCell.of(ScorecardVariableNames.priceOf(field), location)
    }

    PublishedCell.Survey.IC_AY -> {
      PublishedCell.Surveyed(publisher, IpedsVariableNames.priceOf(field))
    }

    PublishedCell.Survey.SFA -> {
      error(
        "IPEDS SFA publishes no price column, so the `price_figures` row for [${field.wireName}] cannot be " +
          "one of its cells: name IC_AY, or the Scorecard (RFC 184)",
      )
    }
  }
