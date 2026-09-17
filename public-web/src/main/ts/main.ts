/**
 * The one bundle entry point (RFC 225).
 *
 * It does no work at import time beyond mounting each enhancement, and every
 * `mount` is a no-op when its host is absent -- so a page that carries none of
 * the features pays nothing and throws nothing. A second enhancement is a
 * second call here; nothing else in the page changes, because the page
 * references the bundle by the name the manifest gives it.
 */
import { mount as mountBackToTop } from "./backToTop.ts";

// `type="module"` implies defer, so the document is parsed by the time this
// runs and no DOMContentLoaded dance is needed.
mountBackToTop(document);
