package ed.unicoach.web.render

/**
 * The class every DATA TABLE on this site carries, on top of its own page's
 * class.
 *
 * ONE MECHANISM, WRITTEN ONCE. A data table is a grid on a laptop and one card
 * per row on a phone, each value re-labelled with the column it came from
 * (`data-label`). The Family Cost Report's money grid (RFC 155) and the guides'
 * cited tables (RFC 238) ARE that table, so `site.css` states the mechanism
 * against this class and each page's own class tunes only what that page
 * actually differs in -- a fix to the phone card then cannot be half applied.
 *
 * It is a constant and not a literal in two renderers for the reason every
 * class name here is: the stylesheet is the other half of the contract, and a
 * spelling with two homes is one rename away from an unstyled table.
 */
const val DATA_TABLE_CLASS = "data-table"

/**
 * The attribute a stacked cell of a [DATA_TABLE_CLASS] table re-labels itself
 * with on a phone.
 *
 * BESIDE THE CLASS IT IS HALF OF, because the two are one mechanism: the
 * stylesheet reads the attribute off a cell of that class
 * (`content: attr(data-label)`), and both renderers write both. The other half
 * of the contract is `site.css`, which cannot read a Kotlin constant; nothing IN
 * KOTLIN restates the spelling, so a rename is one edit on each side of the
 * contract and not three plus a silent loss of every phone-card label.
 */
const val DATA_LABEL = "data-label"
