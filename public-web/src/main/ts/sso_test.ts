/**
 * The SSO rules, and `mount` driven against hand-rolled fakes of the few DOM
 * members and provider globals it uses. As in `autosave_test.ts`: no network,
 * no assertion library.
 *
 * What the fakes CANNOT prove is anything a provider or a browser decides:
 * that Google's script renders its button, that Apple's popup opens, or that
 * the page's policy admits either. That is the RFC's browser check.
 */
import {
  APPLE_BUTTON_SELECTOR,
  cancelledSentence,
  chooseFormName,
  CLIENT_ID_ATTRIBUTE,
  couldNotLoadSentence,
  formSelector,
  GOOGLE_BUTTON_MAX_WIDTH,
  GOOGLE_BUTTON_MIN_WIDTH,
  GOOGLE_HOST_SELECTOR,
  googleButtonWidth,
  type GoogleIdApi,
  ID_TOKEN_FIELD,
  joinAppleName,
  mount,
  sentenceForAppleRejection,
  STATUS_SELECTOR,
} from "./sso.ts";

function assert(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

Deno.test("joinAppleName joins given and family name with one space", () => {
  const joined = joinAppleName({ firstName: "Ada", lastName: "Lovelace" });
  assert(joined === "Ada Lovelace", `got [${joined}]`);
});

Deno.test("joinAppleName keeps the one part Apple sent", () => {
  assert(joinAppleName({ firstName: "Ada" }) === "Ada", "given name alone");
  assert(
    joinAppleName({ firstName: null, lastName: "Lovelace" }) === "Lovelace",
    "family name alone",
  );
});

Deno.test("joinAppleName trims, and a blank or absent name is no name", () => {
  assert(
    joinAppleName({ firstName: "  Ada ", lastName: " Lovelace  " }) ===
      "Ada Lovelace",
    "whitespace was not trimmed",
  );
  assert(joinAppleName({ firstName: " ", lastName: "" }) === null, "blank");
  assert(joinAppleName({}) === null, "empty");
  assert(joinAppleName(undefined) === null, "absent");
  assert(joinAppleName(null) === null, "null");
});

Deno.test("the form posts Apple's name over the re-rendered one, and keeps the re-rendered one when Apple sent none", () => {
  assert(chooseFormName("Ada Lovelace", "Old") === "Ada Lovelace", "Apple's");
  assert(chooseFormName(null, "Ada Lovelace") === "Ada Lovelace", "kept");
  assert(chooseFormName(null, "") === "", "nothing to keep");
});

Deno.test("Apple's two dismissal errors are cancelled, anything else could not start", () => {
  for (const error of ["user_cancelled_authorize", "popup_closed_by_user"]) {
    assert(
      sentenceForAppleRejection({ error }) === cancelledSentence("Apple"),
      `[${error}] is not the cancelled sentence`,
    );
  }
  for (
    const rejection of [
      { error: "invalid_client" },
      new Error("boom"),
      "popup_closed_by_user",
      null,
      undefined,
    ]
  ) {
    assert(
      sentenceForAppleRejection(rejection) === couldNotLoadSentence("Apple"),
      `[${String(rejection)}] is not the could-not-start sentence`,
    );
  }
});

Deno.test("the sentences are the approved words", () => {
  assert(
    cancelledSentence("Google") === "Sign in with Google was cancelled.",
    cancelledSentence("Google"),
  );
  assert(
    couldNotLoadSentence("Apple") ===
      "Apple sign-in couldn't load. Check your connection or content blocker, or use your email and password.",
    couldNotLoadSentence("Apple"),
  );
});

Deno.test("Google's button is asked for the host's width, inside Google's range", () => {
  assert(googleButtonWidth(320.7) === 320, "a measured host");
  assert(googleButtonWidth(900) === GOOGLE_BUTTON_MAX_WIDTH, "too wide");
  assert(googleButtonWidth(120) === GOOGLE_BUTTON_MIN_WIDTH, "too narrow");
  assert(googleButtonWidth(0) === GOOGLE_BUTTON_MAX_WIDTH, "unmeasured");
  assert(googleButtonWidth(NaN) === GOOGLE_BUTTON_MAX_WIDTH, "not a number");
});

/** An element: its attributes and the text this module writes. */
interface FakeElement {
  textContent: string;
  value: string;
  clientWidth: number;
  getAttribute(name: string): string | null;
  addEventListener(type: string, handler: () => void): void;
}

function fakeElement(attributes: Record<string, string> = {}): FakeElement {
  return {
    textContent: "",
    value: "",
    clientWidth: 0,
    getAttribute: (name) => attributes[name] ?? null,
    addEventListener: () => {},
  };
}

/** A document answering only the selectors in [elements], viewed by a window holding [globals]. */
function fakeDocument(
  elements: Record<string, unknown>,
  globals: Record<string, unknown>,
): Document {
  const document = {
    defaultView: globals,
    querySelector: (selector: string) => elements[selector] ?? null,
  };
  // ONE cast, at the boundary, as `backToTop_test.ts` makes it.
  return document as unknown as Document;
}

Deno.test("mount on a page with no SSO hosts is a no-op", () => {
  let touched = false;
  const google: GoogleIdApi = {
    initialize: () => {
      touched = true;
    },
    renderButton: () => {
      touched = true;
    },
  };
  mount(fakeDocument({}, { google: { accounts: { id: google } } }));
  mount(fakeDocument({}, {}));
  assert(!touched, "a page with no Google host initialised Google");
});

Deno.test("a Google host whose script did not load writes the could-not-load sentence", () => {
  const status = fakeElement();
  const elements = {
    [GOOGLE_HOST_SELECTOR]: fakeElement({ [CLIENT_ID_ATTRIBUTE]: "web-id" }),
    [formSelector("Google")]: {},
    [STATUS_SELECTOR]: status,
  };
  mount(fakeDocument(elements, {}));
  assert(
    status.textContent === couldNotLoadSentence("Google"),
    `got [${status.textContent}]`,
  );
});

Deno.test("Google's credential is put in the form and the form is submitted", () => {
  const tokenField = fakeElement();
  let submits = 0;
  const form = {
    querySelector: (selector: string) =>
      selector === `input[name="${ID_TOKEN_FIELD}"]` ? tokenField : null,
    requestSubmit: () => {
      submits += 1;
    },
  };
  let callback: ((response: { credential?: string }) => void) | undefined;
  let clientId = "";
  let rendered = 0;
  const google: GoogleIdApi = {
    initialize: (config) => {
      clientId = config.client_id;
      callback = config.callback;
    },
    renderButton: () => {
      rendered += 1;
    },
  };
  const elements = {
    [GOOGLE_HOST_SELECTOR]: fakeElement({ [CLIENT_ID_ATTRIBUTE]: "web-id" }),
    [formSelector("Google")]: form,
  };
  mount(fakeDocument(elements, { google: { accounts: { id: google } } }));

  assert(clientId === "web-id", `initialised with [${clientId}]`);
  assert(rendered === 1, `rendered [${rendered}] buttons`);
  callback?.({ credential: "a.google.jwt" });
  assert(tokenField.value === "a.google.jwt", "the credential was not posted");
  assert(submits === 1, `expected one submit, got [${submits}]`);
});

Deno.test("an Apple button whose script did not load says so on click and submits nothing", () => {
  const status = fakeElement();
  const handlers: Array<() => void> = [];
  const button = {
    ...fakeElement({
      [CLIENT_ID_ATTRIBUTE]: "coach.uni.web",
      "data-redirect-uri": "https://app.example/sign-in",
    }),
    addEventListener: (_: string, handler: () => void) =>
      handlers.push(handler),
  };
  let submits = 0;
  const elements = {
    [APPLE_BUTTON_SELECTOR]: button,
    [formSelector("Apple")]: {
      requestSubmit: () => {
        submits += 1;
      },
    },
    [STATUS_SELECTOR]: status,
  };
  mount(fakeDocument(elements, {}));
  assert(
    handlers.length === 1,
    `expected one click handler, got [${handlers.length}]`,
  );
  handlers[0]();
  assert(
    status.textContent === couldNotLoadSentence("Apple"),
    `got [${status.textContent}]`,
  );
  assert(submits === 0, "a click with no Apple script submitted the form");
});
