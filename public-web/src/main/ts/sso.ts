/**
 * Google and Apple sign-in on the web (RFC 275): each provider's own
 * JavaScript, in popup mode, and the ID token it hands back posted to our own
 * origin through a form the server rendered.
 *
 * Split the way `backToTop.ts` is: the rules -- joining Apple's name, choosing
 * the name the form posts, the sentence for a rejection -- are pure functions
 * the Deno test exercises, and `mount` is the thin wiring that hands a real
 * document and the providers' globals to them.
 *
 * This module never builds a request of its own. It fills the hidden field of
 * a form the server rendered (`render/SsoButtons.kt`) and submits it, so the
 * post is an ordinary same-origin form submission: the same-origin guard sees
 * it as it sees every other one, the invite and report tokens ride the form's
 * own hidden fields, and the server's post-redirect-get is the answer.
 *
 * The provider scripts are `defer` tags placed BEFORE the bundle, so they have
 * run -- or failed -- by the time `mount` does: a global that is absent here is
 * a script that did not load, never one still loading.
 */

// The names below are the page's (`render/SsoButtons.kt`), restated here as
// literals. `SignInRoutingTest`'s configured-page test pins the same literals
// on the served pages -- the host, button, status and client-ID/return-URL
// attributes, the `google`/`apple` form segments (each `Provider` lower-cased)
// and each form's `id_token` and Apple's `name` field -- so a Kotlin rename
// fails that test. A rename here needs the same edit to those pins.

/** Google's button host; the provider script fills it with its own iframe. */
export const GOOGLE_HOST_SELECTOR = "[data-sso-google]";

/** Our Apple button. */
export const APPLE_BUTTON_SELECTOR = "button[data-sso-apple]";

/** The line this module writes its sentences into. */
export const STATUS_SELECTOR = "[data-sso-status]";

/** The client ID on each provider's element. */
export const CLIENT_ID_ATTRIBUTE = "data-client-id";

/** The return URL Apple requires, on the Apple button. */
export const REDIRECT_URI_ATTRIBUTE = "data-redirect-uri";

/** The posted field carrying the provider's ID token. */
export const ID_TOKEN_FIELD = "id_token";

/** The posted field carrying Apple's first-authorization name. */
export const NAME_FIELD = "name";

/** A provider, by the name its sentences call it. */
export type Provider = "Google" | "Apple";

/** The hidden form a provider's token is submitted through, by its door's segment. */
export function formSelector(provider: Provider): string {
  return `form[data-sso-form="${provider.toLowerCase()}"]`;
}

/** Apple's `user.name`, as its JS resolves it on a FIRST authorization only. */
export interface AppleName {
  readonly firstName?: string | null;
  readonly lastName?: string | null;
}

/**
 * Apple's two name parts as one name, joined the way the iPhone joins them:
 * given name, a space, family name, trimmed. A blank result is no name
 * (`null`), so the server falls back to the email's local part instead of
 * naming an account with nothing.
 */
export function joinAppleName(
  name: AppleName | null | undefined,
): string | null {
  const parts = [name?.firstName, name?.lastName]
    .map((part) => (part ?? "").trim())
    .filter((part) => part !== "");
  return parts.length === 0 ? null : parts.join(" ");
}

/**
 * The name the Apple form posts: the one Apple just sent, when it sent one;
 * otherwise the one the server re-rendered into the field after a refused
 * attempt -- the retry's token carries no name, so dropping that value would
 * lose the user's name for good.
 */
export function chooseFormName(
  fromApple: string | null,
  rerendered: string,
): string {
  return fromApple ?? rerendered;
}

/** The two errors Apple's JS rejects with when the user dismisses its popup. */
const APPLE_CANCELLED_ERRORS: ReadonlySet<string> = new Set([
  "user_cancelled_authorize",
  "popup_closed_by_user",
]);

/** The user cancelled or closed the provider's popup (D-F). */
export function cancelledSentence(provider: Provider): string {
  return `Sign in with ${provider} was cancelled.`;
}

/** The provider's script did not load, or would not start (D-F). */
export function couldNotLoadSentence(provider: Provider): string {
  return `${provider} sign-in couldn't load. Check your connection or content blocker, or use your email and password.`;
}

/** The user dismissed Apple's popup: [rejection] is one of its two dismissal errors. */
function isAppleCancellation(rejection: unknown): boolean {
  const error = typeof rejection === "object" && rejection !== null
    ? (rejection as { error?: unknown }).error
    : undefined;
  return typeof error === "string" && APPLE_CANCELLED_ERRORS.has(error);
}

/**
 * The sentence for what `AppleID.auth.signIn()` rejected with: cancelled for
 * the two dismissal errors, could-not-start for anything else -- an
 * unregistered domain, a bad Services ID, an exception from `init`.
 */
export function sentenceForAppleRejection(rejection: unknown): string {
  return isAppleCancellation(rejection)
    ? cancelledSentence("Apple")
    : couldNotLoadSentence("Apple");
}

/** Google's widest button, in px (its `renderButton` caps `width` here). */
export const GOOGLE_BUTTON_MAX_WIDTH = 400;

/** Google's narrowest button, in px. */
export const GOOGLE_BUTTON_MIN_WIDTH = 200;

/**
 * The width Google's button is asked for: the host's own width, so it lines up
 * with the Apple button and the email form, inside the range Google renders.
 * An unmeasured host (0, or not a number) asks for the widest.
 */
export function googleButtonWidth(hostWidth: number): number {
  if (!Number.isFinite(hostWidth) || hostWidth <= 0) {
    return GOOGLE_BUTTON_MAX_WIDTH;
  }
  return Math.max(
    GOOGLE_BUTTON_MIN_WIDTH,
    Math.min(GOOGLE_BUTTON_MAX_WIDTH, Math.floor(hostWidth)),
  );
}

/** The slice of Google Identity Services this module calls. */
export interface GoogleIdApi {
  initialize(config: {
    client_id: string;
    callback: (response: { credential?: string }) => void;
    ux_mode: "popup";
  }): void;
  renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
}

/** What `AppleID.auth.signIn()` resolves to in popup mode. */
export interface AppleSignInResponse {
  readonly authorization?: { readonly id_token?: string };
  readonly user?: { readonly name?: AppleName };
}

/** The slice of Sign in with Apple JS this module calls. */
export interface AppleAuthApi {
  init(config: {
    clientId: string;
    scope: string;
    redirectURI: string;
    usePopup: boolean;
  }): void;
  signIn(): Promise<AppleSignInResponse>;
}

/** The two globals the provider scripts define, either absent when its script did not load. */
export interface ProviderGlobals {
  readonly google?: { readonly accounts?: { readonly id?: GoogleIdApi } };
  readonly AppleID?: { readonly auth?: AppleAuthApi };
}

/**
 * Wire whichever provider the page renders a host for. A no-op on a page with
 * neither -- every page but sign-in and Create account.
 */
export function mount(document: Document): void {
  const view = document.defaultView as (Window & ProviderGlobals) | null;
  if (view === null) {
    return;
  }
  const status = document.querySelector<HTMLElement>(STATUS_SELECTOR);
  mountGoogle(document, view, status);
  mountApple(document, view, status);
}

function mountGoogle(
  document: Document,
  view: ProviderGlobals,
  status: HTMLElement | null,
): void {
  const host = document.querySelector<HTMLElement>(GOOGLE_HOST_SELECTOR);
  const form = document.querySelector<HTMLFormElement>(formSelector("Google"));
  const clientId = host?.getAttribute(CLIENT_ID_ATTRIBUTE);
  if (!host || !form || !clientId) {
    return;
  }
  const gis = view.google?.accounts?.id;
  if (gis === undefined) {
    say(status, couldNotLoadSentence("Google"));
    return;
  }
  try {
    gis.initialize({
      client_id: clientId,
      // Popup mode answers only a completed sign-in; a closed popup calls
      // nothing, so there is no cancelled sentence for Google to write.
      callback: (response) => {
        if (response.credential) {
          submitToken(form, status, "Google", response.credential);
        } else {
          // The response's field names only: its values are the provider's.
          fail(
            status,
            couldNotLoadSentence("Google"),
            "Google",
            "callback: no credential",
            Object.keys(response),
          );
        }
      },
      ux_mode: "popup",
    });
    gis.renderButton(host, {
      type: "standard",
      theme: "outline",
      size: "large",
      text: "continue_with",
      shape: "rectangular",
      width: googleButtonWidth(host.clientWidth),
    });
  } catch (error) {
    fail(
      status,
      couldNotLoadSentence("Google"),
      "Google",
      "initialize/renderButton",
      error,
    );
  }
}

function mountApple(
  document: Document,
  view: ProviderGlobals,
  status: HTMLElement | null,
): void {
  const button = document.querySelector<HTMLButtonElement>(
    APPLE_BUTTON_SELECTOR,
  );
  const form = document.querySelector<HTMLFormElement>(formSelector("Apple"));
  const clientId = button?.getAttribute(CLIENT_ID_ATTRIBUTE);
  const redirectURI = button?.getAttribute(REDIRECT_URI_ATTRIBUTE);
  if (!button || !form || !clientId || !redirectURI) {
    return;
  }
  const startSignIn = appleSignInStarter(clientId, redirectURI);
  button.addEventListener("click", () => {
    say(status, "");
    const auth = view.AppleID?.auth;
    if (auth === undefined) {
      say(status, couldNotLoadSentence("Apple"));
      return;
    }
    // `init` and `signIn` run in the click's own task, with no `await`
    // between them and the click, so the browser lets the popup open.
    let signingIn: Promise<AppleSignInResponse>;
    try {
      signingIn = startSignIn(auth);
    } catch (error) {
      refuseApple(status, "init/signIn", error);
      return;
    }
    signingIn.then(
      (response) => completeAppleSignIn(form, status, response),
      (rejection: unknown) => refuseApple(status, "signIn", rejection),
    );
  });
}

/**
 * `AppleID.auth.signIn()`, after `init` on the first click only: Apple's JS is
 * configured once per page, and every later click reuses it.
 */
function appleSignInStarter(
  clientId: string,
  redirectURI: string,
): (auth: AppleAuthApi) => Promise<AppleSignInResponse> {
  let initialised = false;
  return (auth) => {
    if (!initialised) {
      auth.init({
        clientId,
        scope: "name email",
        redirectURI,
        usePopup: true,
      });
      initialised = true;
    }
    return auth.signIn();
  };
}

/** Put Apple's name and token in [form] and submit it. */
function completeAppleSignIn(
  form: HTMLFormElement,
  status: HTMLElement | null,
  response: AppleSignInResponse,
): void {
  const idToken = response.authorization?.id_token;
  if (!idToken) {
    // The response's field names only: its values carry the user's name.
    fail(
      status,
      couldNotLoadSentence("Apple"),
      "Apple",
      "signIn: no id_token",
      Object.keys(response.authorization ?? {}),
    );
    return;
  }
  fillAppleName(form, response.user?.name);
  submitToken(form, status, "Apple", idToken);
}

/** Put the name [form] should post in its name field, when it has one. */
function fillAppleName(
  form: HTMLFormElement,
  name: AppleName | undefined,
): void {
  const nameField = form.querySelector<HTMLInputElement>(
    `input[name="${NAME_FIELD}"]`,
  );
  if (nameField !== null) {
    nameField.value = chooseFormName(joinAppleName(name), nameField.value);
  }
}

/**
 * Say what Apple's rejection or thrown [error] means. A dismissal is the user's
 * own choice and is not logged; anything else is Apple's failure and is.
 */
function refuseApple(
  status: HTMLElement | null,
  stage: string,
  error: unknown,
): void {
  const sentence = sentenceForAppleRejection(error);
  if (isAppleCancellation(error)) {
    say(status, sentence);
  } else {
    fail(status, sentence, "Apple", stage, error);
  }
}

/** Put [token] in [form]'s ID-token field and submit it as its own button would. */
function submitToken(
  form: HTMLFormElement,
  status: HTMLElement | null,
  provider: Provider,
  token: string,
): void {
  const field = form.querySelector<HTMLInputElement>(
    `input[name="${ID_TOKEN_FIELD}"]`,
  );
  if (field === null) {
    fail(
      status,
      couldNotLoadSentence(provider),
      provider,
      `submit: the form has no ${ID_TOKEN_FIELD} field`,
      form.action,
    );
    return;
  }
  field.value = token;
  form.requestSubmit();
}

/**
 * Say [sentence] to the user, and leave the provider's own failure -- [detail],
 * at [stage] -- in the console. A misconfigured provider fails in the browser
 * and never reaches the server, so the console is the only place it can be
 * read. [detail] never carries the ID token or the user's name.
 */
function fail(
  status: HTMLElement | null,
  sentence: string,
  provider: Provider,
  stage: string,
  detail: unknown,
): void {
  console.error(`sso: ${provider} sign-in failed at [${stage}]`, detail);
  say(status, sentence);
}

/** Write [sentence] into the status line, when the page has one. */
function say(status: HTMLElement | null, sentence: string): void {
  if (status !== null) {
    status.textContent = sentence;
  }
}
