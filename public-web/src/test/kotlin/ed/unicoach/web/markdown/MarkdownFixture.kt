package ed.unicoach.web.markdown

/**
 * The worst-case coach reply, translated from the iOS client's
 * `MarkdownFixture.worstCaseReply` (`MarkdownView.swift`): an h2, bold and
 * italic prose, a nested bullet list with task items, a five-column aligned
 * table, a fenced block with a line continuation, a quote and a link — every
 * block type the renderer draws but a rule, in one message.
 *
 * The Swift literal's lone trailing `\` is a LINE CONTINUATION, so the second
 * paragraph is one line here; its `\\` is a literal backslash, kept.
 */
object MarkdownFixture {
  val worstCaseReply =
    """
    ## Your next steps

    You have **three deadlines** inside the next month, and the _Common App_ essay is the one that gates the rest.

    - Applications
      - Michigan — supplement not started
      - Purdue — essay in draft
    - Financial aid
      - [x] FSA ID created
      - [ ] FAFSA submitted

    | College | Deadline | Type | Essays | Status |
    | --- | --- | :---: | ---: | --- |
    | Michigan | Nov 1 | EA | 2 | Draft |
    | Purdue | Nov 15 | EA | 1 | Not started |
    | Illinois | Dec 1 | RD | 0 | Submitted |

    Run this to check your list against the deadlines:

    ```sh
    bin/deadlines --within 30d \
        --school michigan
    ```

    > Anything you submit early can still be revised until the deadline.

    See [the Common App checklist](https://example.com) when you start.
    """.trimIndent()
}
