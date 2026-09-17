/**
 * The rule, and the DOM wiring around it, exercised without a browser.
 *
 * `bin/build-web-assets` runs this with `--no-remote --no-npm`, so there is no
 * `jsr:@std/assert` here and no `deno.lock` to review: the assertion helper is
 * six lines and the repo reaches no network to get it.
 *
 * `sync` and `mount` both take the document as a parameter, so they are driven
 * here against the hand-rolled fake below rather than left untested. Read the
 * comment on `fakePage` for what that fake does NOT prove.
 */
import {
  BACK_TO_TOP_CLASS,
  BACK_TO_TOP_LABEL,
  mount,
  type PageMetrics,
  shouldShowBackToTop,
  sync,
  VIEWPORTS_BEFORE_BACK_TO_TOP,
} from "./backToTop.ts";

function assert(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

Deno.test("a page taller than the threshold gets the link", () => {
  assert(
    shouldShowBackToTop({
      documentHeight: 1000 * VIEWPORTS_BEFORE_BACK_TO_TOP + 1,
      viewportHeight: 1000,
    }),
    "a document one pixel past the threshold should show the link",
  );
});

Deno.test("a page of exactly the threshold does not", () => {
  assert(
    !shouldShowBackToTop({
      documentHeight: 1000 * VIEWPORTS_BEFORE_BACK_TO_TOP,
      viewportHeight: 1000,
    }),
    "exactly the threshold is not longer than the threshold",
  );
});

Deno.test("a short page does not", () => {
  assert(
    !shouldShowBackToTop({ documentHeight: 900, viewportHeight: 1000 }),
    "a document shorter than the viewport should show nothing",
  );
});

Deno.test("an unmeasured or non-finite page does not throw and shows nothing", () => {
  const cases: Array<PageMetrics & { readonly what: string }> = [
    { documentHeight: 5000, viewportHeight: 0, what: "zero viewport" },
    { documentHeight: 5000, viewportHeight: -1, what: "negative viewport" },
    { documentHeight: NaN, viewportHeight: 1000, what: "NaN document" },
    {
      documentHeight: Infinity,
      viewportHeight: 1000,
      what: "infinite document",
    },
  ];
  for (const { documentHeight, viewportHeight, what } of cases) {
    assert(
      !shouldShowBackToTop({ documentHeight, viewportHeight }),
      `${what}: expected no link`,
    );
  }
});

/**
 * The single element this module ever creates.
 *
 * Only the members `backToTop.ts` actually touches are here; anything else a
 * real `HTMLAnchorElement` has is absent on purpose, so a test that starts
 * needing one fails to compile rather than passing against a pretend browser.
 */
interface FakeElement {
  className: string;
  textContent: string;
  href: string;
  remove(): void;
  addEventListener(type: string, handler: (event: Event) => void): void;
}

/** A fake page: the document under test plus the handles a test needs to drive it. */
interface FakePage {
  /** What `sync`/`mount` are called with. */
  readonly document: Document;
  /** Everything appended to the fake `<body>`, in order. */
  readonly children: readonly FakeElement[];
  setDocumentHeight(height: number): void;
  setViewportHeight(height: number): void;
  /** Invoke every handler registered for `resize`, as a browser would. */
  fireResize(): void;
  /** How many `resize` handlers were registered. */
  resizeListenerCount(): number;
}

/**
 * A ~40-line fake of the four DOM members this module uses: `body.appendChild`,
 * `documentElement.scrollHeight`, `defaultView.innerHeight`/`addEventListener`,
 * and `document.querySelector`/`createElement`.
 *
 * What it PROVES: the wiring's own logic -- which branch of `sync` runs for a
 * given pair of measurements, that a second `sync` does not append twice, that
 * the element carries the class and the label, and that `mount` subscribes to
 * `resize`.
 *
 * What it CANNOT prove: anything a browser decides. `querySelector` here
 * matches one exact class-selector string against the fake body's children, not
 * CSS; nothing lays out, so no `scrollHeight` is ever real; a click handler is
 * recorded and never fired by an event loop. That the bundle loads at all, and
 * that the server renders none of this markup, are asserted on the Kotlin side.
 */
function fakePage(
  documentHeight: number,
  viewportHeight: number,
  options: { readonly withDocumentElement?: boolean } = {},
): FakePage {
  const children: FakeElement[] = [];
  const resizeHandlers: Array<() => void> = [];
  const root = { scrollHeight: documentHeight };
  const view = {
    innerHeight: viewportHeight,
    addEventListener(type: string, handler: () => void): void {
      if (type === "resize") {
        resizeHandlers.push(handler);
      }
    },
    scrollTo(): void {},
  };
  const document = {
    body: {
      appendChild(child: FakeElement): FakeElement {
        children.push(child);
        return child;
      },
    },
    defaultView: view,
    documentElement: options.withDocumentElement === false ? null : root,
    querySelector(selector: string): FakeElement | null {
      return children.find((child) => "." + child.className === selector) ??
        null;
    },
    createElement(_tagName: string): FakeElement {
      const element: FakeElement = {
        className: "",
        textContent: "",
        href: "",
        remove(): void {
          const at = children.indexOf(element);
          if (at >= 0) {
            children.splice(at, 1);
          }
        },
        addEventListener(): void {},
      };
      return element;
    },
  };
  return {
    // ONE cast, at the boundary: the fake is structurally typed above, and this
    // is the single place the test claims it stands in for a `Document`. The
    // `recommended` lint tag bans `any`, and `unknown` keeps the claim narrow.
    document: document as unknown as Document,
    children,
    setDocumentHeight(height: number): void {
      root.scrollHeight = height;
    },
    setViewportHeight(height: number): void {
      view.innerHeight = height;
    },
    fireResize(): void {
      for (const handler of resizeHandlers) {
        handler();
      }
    },
    resizeListenerCount(): number {
      return resizeHandlers.length;
    },
  };
}

/** Run `body` with `console.warn` captured, and give back every line it wrote. */
function capturingWarnings(body: () => void): string[] {
  const captured: string[] = [];
  const original = console.warn;
  console.warn = (...args: unknown[]): void => {
    captured.push(args.map((arg) => String(arg)).join(" "));
  };
  try {
    body();
  } finally {
    console.warn = original;
  }
  return captured;
}

Deno.test("sync inserts the link on a long page", () => {
  const page = fakePage(5000, 1000);
  sync(page.document);
  assert(
    page.children.length === 1,
    `expected one child, got [${page.children.length}]`,
  );
  assert(
    page.children[0].className === BACK_TO_TOP_CLASS,
    `expected the [${BACK_TO_TOP_CLASS}] class, got [${
      page.children[0].className
    }]`,
  );
  assert(
    page.children[0].textContent === BACK_TO_TOP_LABEL,
    `expected the label [${BACK_TO_TOP_LABEL}], got [${
      page.children[0].textContent
    }]`,
  );
});

Deno.test("sync inserts nothing on a short page", () => {
  const page = fakePage(900, 1000);
  sync(page.document);
  assert(page.children.length === 0, "a short page should carry no link");
});

Deno.test("sync removes the link when a long page becomes short", () => {
  const page = fakePage(5000, 1000);
  sync(page.document);
  assert(page.children.length === 1, "the long page should have got a link");
  page.setDocumentHeight(900);
  sync(page.document);
  assert(
    page.children.length === 0,
    "the link should be gone once the page is short",
  );
});

Deno.test("sync run twice inserts one link, not two", () => {
  const page = fakePage(5000, 1000);
  sync(page.document);
  sync(page.document);
  assert(
    page.children.length === 1,
    `sync is not idempotent: [${page.children.length}] links`,
  );
});

Deno.test("mount syncs now and re-syncs on resize", () => {
  const page = fakePage(900, 1000);
  mount(page.document);
  assert(page.children.length === 0, "the page is short at mount time");
  assert(
    page.resizeListenerCount() === 1,
    "mount should register exactly one resize listener",
  );

  page.setDocumentHeight(5000);
  page.fireResize();
  assert(
    page.children.length === 1,
    "the resize should have re-evaluated the rule and inserted the link",
  );

  page.setDocumentHeight(900);
  page.fireResize();
  assert(
    page.children.length === 0,
    "the next resize should have removed it again",
  );
});

Deno.test("a document with no documentElement is a silent no-op", () => {
  const page = fakePage(5000, 1000, { withDocumentElement: false });
  const warnings = capturingWarnings(() => sync(page.document));
  assert(
    page.children.length === 0,
    "a document with no root element has no page to enhance",
  );
  assert(
    warnings.length === 0,
    `such a document is not a rendered page, so it warrants no warning: [${
      warnings.join(" | ")
    }]`,
  );
});

Deno.test("an unmeasurable page warns exactly once, however often sync runs", () => {
  const page = fakePage(NaN, 0);
  const warnings = capturingWarnings(() => {
    mount(page.document);
    page.fireResize();
    page.fireResize();
  });
  assert(page.children.length === 0, "an unmeasurable page gets no link");
  assert(
    warnings.length === 1,
    `expected one warning across three syncs, got [${warnings.length}]: [${
      warnings.join(" | ")
    }]`,
  );
  assert(
    warnings[0].includes(BACK_TO_TOP_CLASS),
    `the warning should name the enhancement: [${warnings[0]}]`,
  );
});
