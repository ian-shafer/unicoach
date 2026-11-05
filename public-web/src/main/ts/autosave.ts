/**
 * Your details (RFC 270): each answer saves as soon as it is picked, as the iOS
 * screen does, so a page of selects needs no Save button.
 *
 * Split the way `backToTop.ts` is: the rule -- submit for a real option, not
 * for the blank one -- is a pure function the Deno test exercises, and `mount`
 * is the thin wiring that hands it a real document.
 *
 * The server renders every form this module touches and posts nothing itself:
 * `requestSubmit()` submits the form exactly as its own submit button would,
 * CSRF field and all, and the server answers with its post-redirect-get.
 */

/**
 * The forms this module submits. The attribute is the page's
 * (`render/YourDetailsPage.kt`, `AUTOSAVE_ATTRIBUTE`), restated; the Kotlin
 * routing test asserts the served page carries it.
 */
export const AUTOSAVE_SELECTOR = "form[data-autosave]";

/**
 * Should a select that changed to [value] submit its form?
 *
 * Only for a real option. The blank "Select" option posts `""`, and choosing it
 * is not an answer -- removing an answer has its own button, as on iOS -- so it
 * submits nothing.
 */
export function shouldSubmit(value: string): boolean {
  return value !== "";
}

/**
 * Submit each autosave form when one of its selects changes to a real option.
 * A no-op on a page with no such form.
 */
export function mount(document: Document): void {
  for (
    const form of document.querySelectorAll<HTMLFormElement>(AUTOSAVE_SELECTOR)
  ) {
    for (const select of form.querySelectorAll("select")) {
      select.addEventListener("change", () => {
        if (shouldSubmit(select.value)) {
          form.requestSubmit();
        }
      });
    }
  }
}
