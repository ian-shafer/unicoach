/**
 * The one progressive enhancement the TypeScript rail (RFC 225) carries: a
 * "Back to top" link that appears only on a page long enough to need it.
 *
 * The decision is a PURE function of the page's measurements, separated from the
 * DOM on purpose. `shouldShowBackToTop` is what the deno test exercises; `sync`
 * and `mount` are the thin wiring that reads those measurements out of a real
 * document. A rule buried inside a DOM callback can only be tested with a
 * browser, and there is no browser in this repo's gate.
 *
 * The server renders NO back-to-top markup. With JavaScript off, every page is
 * byte-identical to what it was before this slice apart from the module script
 * tag itself -- asserted from the Kotlin side, not claimed here.
 */

/** The class the stylesheet styles, named once. */
export const BACK_TO_TOP_CLASS = "back-to-top";

/** The visible label the link carries. */
export const BACK_TO_TOP_LABEL = "Back to top";

/**
 * The two measurements the rule reads.
 *
 * They are a named record rather than two positional numbers because they are
 * both plain heights: a caller that swaps them type-checks, and inverts the
 * rule, and no test can see it. A field name cannot be swapped by accident.
 */
export interface PageMetrics {
  readonly documentHeight: number;
  readonly viewportHeight: number;
}

/**
 * How many viewports a document must EXCEED before the link appears.
 *
 * One viewport is no scroll at all, and at just over one the browser's own
 * scrollbar is the shorter route back. Exported because this number IS the
 * rule: the tests, the stylesheet comment and the RFC all talk about it, and a
 * product tweak to three viewports should be one edit, not a hunt for a `2`.
 */
export const VIEWPORTS_BEFORE_BACK_TO_TOP = 2;

/**
 * Is the page long enough to deserve a back-to-top link?
 *
 * The threshold is {@link VIEWPORTS_BEFORE_BACK_TO_TOP} viewports, strictly
 * exceeded -- a page of exactly that many does not get a link.
 *
 * A non-finite or non-positive viewport height answers `false` rather than
 * throwing: a document being measured before layout reports 0, and an
 * enhancement that throws during page load is worse than one that does nothing.
 */
export function shouldShowBackToTop(metrics: PageMetrics): boolean {
  const { documentHeight, viewportHeight } = metrics;
  if (!Number.isFinite(documentHeight) || !Number.isFinite(viewportHeight)) {
    return false;
  }
  if (viewportHeight <= 0) {
    return false;
  }
  return documentHeight > viewportHeight * VIEWPORTS_BEFORE_BACK_TO_TOP;
}

/**
 * Bring the document into line with the rule, whatever state it is in.
 *
 * Idempotent in both directions: it inserts the link when the page deserves one
 * and has none, and REMOVES it when the page no longer deserves one. That is
 * what makes it safe to call on every resize -- a rotation halves the document
 * height and doubles the viewport, and the answer at load is not the answer for
 * the rest of the page's life.
 *
 * A no-op when any of the three things it needs is absent -- the host element
 * (`<body>`), the view to measure against, or the root element to measure. None
 * of the three is substituted for: a missing root element used to be coalesced
 * to a height of `0`, which invented a measurement and made a document that
 * CANNOT be measured indistinguishable from a page that is genuinely short.
 * Such a document is not a rendered page at all, so it is silently skipped.
 */
export function sync(document: Document): void {
  const body = document.body;
  const view = document.defaultView;
  const root = document.documentElement;
  if (body === null || view === null || root === null) {
    return;
  }

  const metrics: PageMetrics = {
    documentHeight: root.scrollHeight,
    viewportHeight: view.innerHeight,
  };
  warnOnceIfUnmeasurable(document, metrics);

  const existing = document.querySelector("." + BACK_TO_TOP_CLASS);
  const wanted = shouldShowBackToTop(metrics);
  if (wanted && existing === null) {
    body.appendChild(backToTopLink(document, view));
  } else if (!wanted && existing !== null) {
    existing.remove();
  }
}

/**
 * Apply the rule now, and again whenever the measurements can have changed.
 *
 * `sync` is idempotent, so a second `mount` inserts nothing extra; the listener
 * is deliberately undebounced, because `sync` does nothing at all unless the
 * answer changed.
 */
export function mount(document: Document): void {
  const view = document.defaultView;
  if (view === null) {
    return;
  }
  sync(document);
  view.addEventListener("resize", () => sync(document));
}

/**
 * Documents already told about their own unusable measurements.
 *
 * `sync` runs on every resize event, and a page whose metrics are unusable is
 * usually unusable for its whole life -- warning on each call would turn one
 * diagnosis into a console flood, which is how a real warning gets ignored. A
 * `WeakSet` keyed by the document holds no document alive.
 */
const warnedDocuments = new WeakSet<Document>();

/**
 * Say once, per document, that the page could not be measured.
 *
 * `shouldShowBackToTop` stays PURE -- it is the rule, and a rule that writes to
 * the console cannot be exercised without capturing the console. The report
 * belongs here, at the one place that reads the numbers out of a real DOM.
 *
 * Without it, a page whose `scrollHeight` is `NaN` or whose viewport measures
 * `0` looks exactly like a short page: the enhancement is simply absent, and
 * nothing anywhere says why.
 */
function warnOnceIfUnmeasurable(
  document: Document,
  metrics: PageMetrics,
): void {
  const measurable = Number.isFinite(metrics.documentHeight) &&
    Number.isFinite(metrics.viewportHeight) && metrics.viewportHeight > 0;
  if (measurable || warnedDocuments.has(document)) {
    return;
  }
  warnedDocuments.add(document);
  console.warn(
    `[${BACK_TO_TOP_CLASS}] the page could not be measured (document height ` +
      `[${metrics.documentHeight}], viewport height [${metrics.viewportHeight}]): no link inserted.`,
  );
}

/**
 * The link itself.
 *
 * A real href, so it works as a link; the listener only upgrades it to a scroll
 * that does not push a fragment onto the history stack. The handler is
 * ATTACHED, never an `onclick=` attribute -- the policy carries no
 * 'unsafe-inline' and an inline handler would be blocked in the browser.
 */
function backToTopLink(document: Document, view: Window): HTMLAnchorElement {
  const link = document.createElement("a");
  link.className = BACK_TO_TOP_CLASS;
  link.textContent = BACK_TO_TOP_LABEL;
  link.href = "#";
  link.addEventListener("click", (event: Event) => {
    event.preventDefault();
    view.scrollTo({ top: 0, behavior: "smooth" });
  });
  return link;
}
