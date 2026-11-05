/**
 * The autosave rule, and `mount` driven against hand-rolled fakes of the few
 * DOM members it uses. As in `backToTop_test.ts`, no network, no assertion
 * library: the helper is four lines.
 *
 * What the fakes CANNOT prove is anything a browser decides: that
 * `form[data-autosave]` matches the served markup (the Kotlin routing test reads
 * the attribute off the page) or that `requestSubmit` posts the form.
 */
import { AUTOSAVE_SELECTOR, mount, shouldSubmit } from "./autosave.ts";

function assert(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

Deno.test("a real option submits", () => {
  assert(shouldSubmit("over_110k"), "a chosen option should submit its form");
});

Deno.test("the blank option does not", () => {
  assert(!shouldSubmit(""), "choosing the blank option is not an answer");
});

/** A select: its value, and the change handlers `mount` attached. */
interface FakeSelect {
  value: string;
  readonly handlers: Array<() => void>;
  addEventListener(type: string, handler: () => void): void;
}

function fakeSelect(): FakeSelect {
  const handlers: Array<() => void> = [];
  return {
    value: "",
    handlers,
    addEventListener(type: string, handler: () => void): void {
      if (type === "change") {
        handlers.push(handler);
      }
    },
  };
}

/** A document whose `querySelectorAll` answers only [AUTOSAVE_SELECTOR], with one form per select given. */
function fakeDocument(
  selects: readonly FakeSelect[],
): { document: Document; submits: () => number } {
  let submits = 0;
  const forms = selects.map((select) => ({
    querySelectorAll(selector: string): FakeSelect[] {
      return selector === "select" ? [select] : [];
    },
    requestSubmit(): void {
      submits += 1;
    },
  }));
  const document = {
    querySelectorAll(selector: string): unknown[] {
      return selector === AUTOSAVE_SELECTOR ? forms : [];
    },
  };
  return {
    // ONE cast, at the boundary, as `backToTop_test.ts` makes it.
    document: document as unknown as Document,
    submits: () => submits,
  };
}

function fireChange(select: FakeSelect, value: string): void {
  select.value = value;
  for (const handler of select.handlers) {
    handler();
  }
}

Deno.test("mount on a page with no autosave form is a no-op", () => {
  const { document, submits } = fakeDocument([]);
  mount(document);
  assert(submits() === 0, "a page with no autosave form submitted something");
});

Deno.test("a change to a real option submits its form, and a change to blank does not", () => {
  const select = fakeSelect();
  const { document, submits } = fakeDocument([select]);
  mount(document);
  assert(
    select.handlers.length === 1,
    `expected one change handler, got [${select.handlers.length}]`,
  );

  fireChange(select, "");
  assert(submits() === 0, "a change to the blank option submitted the form");

  fireChange(select, "on_campus");
  assert(submits() === 1, `expected one submit, got [${submits()}]`);
});
