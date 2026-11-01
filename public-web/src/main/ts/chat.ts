/**
 * The web chat's client (RFC 262 §10): it submits the composer and paints the
 * coach's answer as it streams.
 *
 * The page is server-rendered and READS without this module; SENDING needs it
 * (D-D), and the page's `<noscript>` line says so. Everything here is split the
 * way `backToTop.ts` splits it: the rules -- the stream parser, the turn state
 * machine, the scroll-follow decision -- are plain TypeScript over narrow
 * interfaces the Deno test drives with structural fakes, and `mount` is the
 * thin wiring that hands them a real document.
 *
 * Two invariants carry the sanitisation policy (RFC 262 §9) to this side:
 *
 * - this module NEVER parses Markdown and never builds HTML from model text.
 *   A delta is painted as text (`textContent` / a text node).
 * - the only HTML it inserts is a fragment the SERVER rendered through kotlinx
 *   tag builders: the `completed` event's `html`, or a refusal notice body the
 *   server marked as its own ({@link NOTICE_HEADER}). Any other body -- a
 *   branded error page, a proxy's -- is never inserted.
 */

// The names below are the page's and the stream's, restated: the Kotlin side
// (`render/ChatPage.kt`, `chat/ChatStream.kt`) owns them, and no source is
// shared across the two languages. Each is held to the one contract fixture,
// `public-web/src/test/resources/chat-client-contract.json`, from BOTH sides:
// `chat_test.ts` asserts these constants and `decodeEvent` against it, and
// `ChatClientContractTest` asserts the served page and stream against it. A
// rename on either side alone fails one of the two.

/** The composer the page renders; the hook this module mounts on. */
export const COMPOSER_SELECTOR = "form.chat-composer";

/** The scrolling column of exchanges. */
export const TRANSCRIPT_SELECTOR = ".chat-transcript";

/** A new conversation's empty-state line, removed by the first send. */
export const EMPTY_STATE_SELECTOR = ".chat-empty";

/** The attribute the server writes the retriable failure sentence into (`:service`'s, one home). */
export const RETRIABLE_FAILURE_ATTRIBUTE = "data-retriable-failure";

/**
 * The response header that marks a non-stream body as one of the server's own
 * notices (RFC 262 §5). Only a body carrying it is put in the transcript.
 */
export const NOTICE_HEADER = "X-Chat-Notice";

/** One exchange: the student's message and the reply to it, as the page renders them. */
export const EXCHANGE_CLASS = "exchange";

/** The classes the page gives a student's message. */
export const STUDENT_MESSAGE_CLASSES = "message student";

/** The classes the page gives a coach message. */
export const COACH_MESSAGE_CLASSES = "message coach";

/** A reply that has not painted its first delta yet. */
export const TYPING_CLASS = "typing";

/** A student message the coach did not answer. */
export const UNANSWERED_CLASS = "unanswered";

/** The "Not answered" mark on such a message. */
export const NOT_ANSWERED_CLASS = "not-answered";

/** A failed turn's sentence and its Try again control. */
export const TURN_FAILED_CLASS = "turn-failed";

/** The Try again control itself. */
export const TRY_AGAIN_CLASS = "try-again";

/** Shown in the reply's place until its first delta. */
export const TYPING_LABEL = "Coach is typing…";

/** The mark on a student message the coach did not answer. */
export const NOT_ANSWERED_LABEL = "Not answered";

/** The control that re-sends a failed message. */
export const TRY_AGAIN_LABEL = "Try again";

/**
 * How close to the bottom, in CSS pixels, the transcript must be for a
 * streaming reply to keep it scrolled: a student who scrolled up to reread is
 * left where they are.
 */
export const FOLLOW_THRESHOLD_PX = 80;

/** What the POST asks for: the stream, or one of our own HTML notices. */
export const ACCEPT_HEADER = "text/event-stream, text/html";

/**
 * The composer's hidden field holding the session's CSRF token (RFC 265). The
 * message body is built from the text area alone, so the token is read from
 * here and sent as {@link CSRF_HEADER}.
 */
export const CSRF_FIELD = "_csrf";

/** The request header the server reads a `fetch`'s CSRF token from. */
export const CSRF_HEADER = "X-CSRF-Token";

/**
 * The `fetch` options for one turn's POST: the body, the session cookie, the
 * {@link ACCEPT_HEADER} and the session's CSRF token. Without the token the
 * server refuses the send before any stream opens (RFC 265).
 */
export function turnRequest(
  body: URLSearchParams,
  csrfToken: string,
): RequestInit {
  return {
    method: "POST",
    body,
    credentials: "same-origin",
    headers: { Accept: ACCEPT_HEADER, [CSRF_HEADER]: csrfToken },
  };
}

// ---------------------------------------------------------------------------
// The stream format
// ---------------------------------------------------------------------------

/** One raw server-sent event: its name and its single `data:` line. */
export interface RawEvent {
  readonly event: string;
  readonly data: string;
}

/**
 * An incremental parser for the chat's stream framing (`event:` / `data:` /
 * blank line). Chunks arrive split ANYWHERE -- mid-frame, mid-line -- so it
 * holds the trailing partial frame until the chunk that completes it.
 */
export class SseParser {
  private buffer = "";

  /** The complete events [chunk] finishes, in order. */
  push(chunk: string): RawEvent[] {
    this.buffer += chunk;
    const events: RawEvent[] = [];
    let end = this.buffer.indexOf("\n\n");
    while (end >= 0) {
      const frame = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const parsed = parseFrame(frame);
      if (parsed !== null) {
        events.push(parsed);
      }
      end = this.buffer.indexOf("\n\n");
    }
    return events;
  }
}

function parseFrame(frame: string): RawEvent | null {
  let event = "";
  const data: string[] = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) {
      event = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice("data:".length).replace(/^ /, ""));
    }
  }
  return event === "" ? null : { event, data: data.join("\n") };
}

/** The four events the server writes (RFC 262 §6). */
export type ChatEvent =
  | { readonly kind: "started"; readonly conversationUrl: string }
  | { readonly kind: "delta"; readonly text: string }
  | { readonly kind: "completed"; readonly html: string }
  | {
    readonly kind: "failed";
    readonly retriable: boolean;
    readonly message: string;
  };

/** The two events that end a turn. */
export type Terminal = Extract<ChatEvent, { kind: "completed" | "failed" }>;

type Decoder = (data: Record<string, unknown>) => ChatEvent | null;

/** One decoder per event this client knows: the names here ARE the known set. */
const DECODERS: ReadonlyMap<string, Decoder> = new Map<string, Decoder>([
  [
    "started",
    (data) =>
      typeof data.conversationUrl === "string"
        ? { kind: "started", conversationUrl: data.conversationUrl }
        : null,
  ],
  [
    "delta",
    (data) =>
      typeof data.text === "string" ? { kind: "delta", text: data.text } : null,
  ],
  [
    "completed",
    (data) =>
      typeof data.html === "string"
        ? { kind: "completed", html: data.html }
        : null,
  ],
  [
    "failed",
    (data) =>
      typeof data.retriable === "boolean" && typeof data.message === "string"
        ? { kind: "failed", retriable: data.retriable, message: data.message }
        : null,
  ],
]);

/**
 * [raw] as a typed event, or null when it is none -- never a throw.
 *
 * An event NAME this client does not know is a later server's, and is skipped
 * silently (forward compatibility). A known name whose data does not decode is
 * a broken contract: it is skipped too, but reported, because the caller sees
 * only its absence -- a dropped `started` would leave the form posting to a new
 * conversation with nothing to say why.
 */
export function decodeEvent(raw: RawEvent): ChatEvent | null {
  const decode = DECODERS.get(raw.event);
  if (decode === undefined) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw.data);
  } catch (error) {
    console.warn(
      `chat: dropped a [${raw.event}] event that is not JSON`,
      raw.data,
      error,
    );
    return null;
  }
  const event = isRecord(parsed) ? decode(parsed) : null;
  if (event === null) {
    console.warn(
      `chat: dropped a [${raw.event}] event of the wrong shape`,
      raw.data,
    );
  }
  return event;
}

/** Is [value] a JSON object -- not null, an array or a primitive? */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * The decoded events of [body], in order. A read error ends the sequence as
 * end-of-stream does -- logged, because the caller sees only that no terminal
 * came -- and an event that does not decode is skipped ({@link decodeEvent}).
 */
export async function* chatEvents(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<ChatEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    while (true) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (error) {
        console.error("chat: the stream's read failed", error);
        return;
      }
      if (chunk.done) {
        return;
      }
      for (
        const raw of parser.push(decoder.decode(chunk.value, { stream: true }))
      ) {
        const event = decodeEvent(raw);
        if (event !== null) {
          yield event;
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

// ---------------------------------------------------------------------------
// The turn
// ---------------------------------------------------------------------------

/** The composer, as the turn sees it. */
export interface Composer {
  text(): string;
  setText(text: string): void;
  /** The form's current target: `/chat`, or the conversation it continues. */
  action(): string;
  setAction(url: string): void;
  /** The form's body for [text], built from the form's own field names. */
  body(text: string): URLSearchParams;
  /** The session's CSRF token, as the page rendered it in the composer. */
  csrfToken(): string;
  /** Send is disabled while a turn is in flight; the text area NEVER is (D-C). */
  setSending(sending: boolean): void;
}

/** One turn's place in the transcript. */
export interface PendingTurn {
  appendDelta(text: string): void;
  /** Replace the streamed text with the server's rendered message. */
  complete(html: string): void;
  /** Remove any partial reply, mark the student's words not answered, and offer [onRetry]. */
  fail(message: string, onRetry: () => void): void;
  /** Put the server's refusal notice where the reply would have gone. */
  notice(html: string): void;
}

export interface Transcript {
  beginTurn(studentText: string): PendingTurn;
}

/** The page outside the transcript. */
export interface Page {
  replaceUrl(url: string): void;
  navigate(url: string): void;
}

/** The parts of a fetch `Response` the turn reads; a real `Response` is one. */
export interface TurnResponse {
  readonly status: number;
  readonly redirected: boolean;
  readonly url: string;
  readonly headers: { get(name: string): string | null };
  readonly body: ReadableStream<Uint8Array> | null;
  text(): Promise<string>;
}

export type Post = (
  url: string,
  body: URLSearchParams,
  csrfToken: string,
) => Promise<TurnResponse>;

export interface ChatDeps {
  readonly composer: Composer;
  readonly transcript: Transcript;
  readonly page: Page;
  readonly post: Post;
  /** `:service`'s retriable sentence, for the failures no event reports. */
  readonly retriableFailure: string;
}

/**
 * Sends turns, one at a time per page: Send is disabled from the POST until
 * the terminal, so one conversation never has two turns in flight.
 */
export class ChatController {
  private inFlight = false;

  constructor(private readonly deps: ChatDeps) {}

  /** Send what the composer holds. A blank message sends nothing. */
  submit(): Promise<void> {
    return this.send(this.deps.composer.text());
  }

  async send(text: string): Promise<void> {
    if (this.inFlight || text.trim() === "") {
      return;
    }
    const { composer, transcript } = this.deps;
    this.inFlight = true;
    composer.setSending(true);
    // Cleared only if it still holds THIS text: a retry from a failed turn
    // must not wipe what the student has typed since.
    if (composer.text() === text) {
      composer.setText("");
    }
    const turn = transcript.beginTurn(text);
    try {
      await this.run(text, turn);
    } finally {
      this.inFlight = false;
      composer.setSending(false);
    }
  }

  private async run(text: string, turn: PendingTurn): Promise<void> {
    const { composer, page, post } = this.deps;
    const fail = (message: string) => {
      turn.fail(message, () => void this.send(text));
      this.restore(text);
    };

    let response: TurnResponse;
    try {
      response = await post(
        composer.action(),
        composer.body(text),
        composer.csrfToken(),
      );
    } catch (error) {
      console.error("chat: the send's POST failed", error);
      return fail(this.deps.retriableFailure);
    }

    // A redirect the fetch followed is the server sending the PAGE somewhere:
    // the student gate, or the sign-in form after a session expired.
    if (response.redirected) {
      return page.navigate(response.url);
    }

    const type = response.headers.get("content-type");
    if (
      response.status === 200 && type?.startsWith("text/event-stream") &&
      response.body !== null
    ) {
      const terminal = await this.consume(response.body, turn);
      if (terminal === null) {
        return fail(this.deps.retriableFailure);
      }
      switch (terminal.kind) {
        case "completed":
          return; // `consume` painted it.
        case "failed":
          return fail(terminal.message);
      }
    }

    // Only a body the server marked as its own notice goes in the transcript.
    // Anything else -- the branded 503 or 404 page, a proxy's error page -- is
    // the retriable failure, as a POST that never got a response is.
    if (response.headers.get(NOTICE_HEADER) === null) {
      console.error(
        `chat: the send was answered [${response.status}] with neither the stream nor a notice`,
      );
      return fail(this.deps.retriableFailure);
    }
    let notice: string;
    try {
      notice = await response.text();
    } catch (error) {
      console.error("chat: the notice's body could not be read", error);
      return fail(this.deps.retriableFailure);
    }
    turn.notice(notice);
    this.restore(text);
  }

  /** Relay the stream into [turn]: the terminal it ended on, or null when it ended without one. */
  private async consume(
    body: ReadableStream<Uint8Array>,
    turn: PendingTurn,
  ): Promise<Terminal | null> {
    let conversationUrl: string | null = null;
    for await (const event of chatEvents(body)) {
      switch (event.kind) {
        case "started":
          conversationUrl = event.conversationUrl;
          break;
        case "delta":
          turn.appendDelta(event.text);
          break;
        case "completed":
          turn.complete(event.html);
          this.adoptConversation(conversationUrl);
          return event;
        case "failed":
          return event;
      }
    }
    return null;
  }

  /**
   * Point the form and the address bar at the conversation [url] names. Called
   * only once a turn has completed: a failed FIRST turn's conversation no
   * longer exists.
   */
  private adoptConversation(url: string | null): void {
    if (url === null) {
      return;
    }
    this.deps.composer.setAction(url);
    this.deps.page.replaceUrl(url);
  }

  /** The student's words back in the composer, unless they have started typing something else. */
  private restore(text: string): void {
    if (this.deps.composer.text() === "") {
      this.deps.composer.setText(text);
    }
  }
}

// ---------------------------------------------------------------------------
// Scroll follow
// ---------------------------------------------------------------------------

/** The three measurements the follow rule reads. */
export interface ScrollMetrics {
  readonly scrollTop: number;
  readonly scrollHeight: number;
  readonly clientHeight: number;
}

/** Is the view within {@link FOLLOW_THRESHOLD_PX} of the bottom? */
export function isNearBottom(metrics: ScrollMetrics): boolean {
  return metrics.scrollHeight - metrics.scrollTop - metrics.clientHeight <=
    FOLLOW_THRESHOLD_PX;
}

/** A scrollable element, as the follower needs it. */
export interface Scrollable extends ScrollMetrics {
  scrollTop: number;
}

/**
 * Keeps a growing transcript scrolled to its end while the student is at the
 * end, and leaves it alone once they scroll up; scrolling back down resumes.
 */
export class ScrollFollower {
  private following = true;

  constructor(private readonly element: Scrollable) {}

  /** Call on every scroll event. */
  onScroll(): void {
    this.following = isNearBottom(this.element);
  }

  /** Call after the transcript grew. */
  afterGrowth(): void {
    if (this.following) {
      this.element.scrollTop = this.element.scrollHeight;
    }
  }
}

// ---------------------------------------------------------------------------
// The DOM wiring
// ---------------------------------------------------------------------------

/**
 * Wire the chat into [document]. A no-op on any page without the composer, so
 * `main.ts` mounts it everywhere, as it mounts `backToTop`.
 */
export function mount(document: Document): void {
  const form = document.querySelector<HTMLFormElement>(COMPOSER_SELECTOR);
  const transcriptElement = document.querySelector<HTMLElement>(
    TRANSCRIPT_SELECTOR,
  );
  const view = document.defaultView;
  if (form === null || transcriptElement === null || view === null) {
    return;
  }
  // The composer's parts. A composer missing one is a page the server did not
  // render as the contract says; it is reported and left to the plain form,
  // never driven with a blank sentence or a guessed destination.
  const textarea = form.querySelector("textarea");
  const button = form.querySelector<HTMLButtonElement>("button[type=submit]");
  const action = form.getAttribute("action");
  const retriableFailure = form.getAttribute(RETRIABLE_FAILURE_ATTRIBUTE);
  const csrf = form.querySelector<HTMLInputElement>(
    `input[name="${CSRF_FIELD}"]`,
  );
  if (
    textarea === null || button === null || action === null ||
    retriableFailure === null || csrf === null
  ) {
    console.error("chat: the composer lacks a part this client needs", {
      textarea,
      button,
      action,
      retriableFailure,
      csrf,
    });
    return;
  }

  const follower = new ScrollFollower(transcriptElement);
  transcriptElement.addEventListener("scroll", () => follower.onScroll());
  // Page load lands at the latest exchange.
  transcriptElement.scrollTop = transcriptElement.scrollHeight;

  const controller = new ChatController({
    composer: domComposer(form, textarea, button, action, csrf.value),
    transcript: domTranscript(document, transcriptElement, follower),
    page: {
      replaceUrl: (url) => view.history.replaceState(null, "", url),
      navigate: (url) => view.location.assign(url),
    },
    post: (url, body, csrfToken) =>
      view.fetch(url, turnRequest(body, csrfToken)),
    retriableFailure,
  });

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    void controller.submit();
    textarea.focus();
  });
  // Enter inserts a newline; Cmd/Ctrl+Enter sends, as the button does.
  textarea.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      void controller.submit();
    }
  });
}

/**
 * The page's composer as the turn sees it. Exported for its test: `setSending`
 * is where "Send, never the text area" (D-C) is actually decided. [action] is
 * the form's destination as the page rendered it; [Composer.setAction] moves
 * both it and the form's attribute. [csrfToken] is the composer's
 * {@link CSRF_FIELD} value, read once: it is the session's, and a page lives
 * inside one session.
 */
export function domComposer(
  form: HTMLFormElement,
  textarea: HTMLTextAreaElement,
  button: HTMLButtonElement,
  action: string,
  csrfToken: string,
): Composer {
  let destination = action;
  return {
    text: () => textarea.value,
    setText: (text) => {
      textarea.value = text;
    },
    action: () => destination,
    setAction: (url) => {
      destination = url;
      form.setAttribute("action", url);
    },
    body: (text) => {
      // The field name is the form's own, so it is never restated here.
      const body = new URLSearchParams();
      body.set(textarea.name, text);
      return body;
    },
    csrfToken: () => csrfToken,
    setSending: (sending) => {
      button.disabled = sending;
    },
  };
}

function domTranscript(
  document: Document,
  transcript: HTMLElement,
  follower: ScrollFollower,
): Transcript {
  return {
    beginTurn(studentText) {
      transcript.querySelector(EMPTY_STATE_SELECTOR)?.remove();
      const built = buildExchange(document, studentText);
      const { exchange, student } = built;
      let reply: HTMLElement = built.reply;
      let painted = false;
      transcript.append(exchange);
      follower.afterGrowth();

      const replaceReply = (html: string) => {
        reply.replaceWith(serverFragment(document, html));
        follower.afterGrowth();
      };

      return {
        appendDelta(text) {
          if (!painted) {
            painted = true;
            reply.classList.remove(TYPING_CLASS);
            reply.textContent = "";
          }
          reply.append(document.createTextNode(text));
          follower.afterGrowth();
        },
        complete(html) {
          replaceReply(html);
        },
        fail(message, onRetry) {
          // A half-painted reply is removed, never left looking like an answer.
          reply.remove();
          markUnanswered(document, student);
          reply = buildFailure(document, message, () => {
            exchange.remove();
            onRetry();
          });
          exchange.append(reply);
          follower.afterGrowth();
        },
        notice(html) {
          markUnanswered(document, student);
          replaceReply(html);
        },
      };
    },
  };
}

/** A new exchange: the student's words as typed, and a reply showing the coach is typing. */
function buildExchange(document: Document, studentText: string): {
  exchange: HTMLElement;
  student: HTMLElement;
  reply: HTMLElement;
} {
  const exchange = element(document, "div", EXCHANGE_CLASS);
  const student = element(document, "div", STUDENT_MESSAGE_CLASSES);
  student.textContent = studentText;
  const reply = element(
    document,
    "div",
    `${COACH_MESSAGE_CLASSES} ${TYPING_CLASS}`,
  );
  reply.textContent = TYPING_LABEL;
  exchange.append(student, reply);
  return { exchange, student, reply };
}

/** Mark [student]'s message as one the coach did not answer. */
function markUnanswered(document: Document, student: HTMLElement): void {
  student.classList.add(UNANSWERED_CLASS);
  const mark = element(document, "span", NOT_ANSWERED_CLASS);
  mark.textContent = NOT_ANSWERED_LABEL;
  student.append(mark);
}

/** A failed turn's sentence and its Try again control, which calls [onRetry]. */
function buildFailure(
  document: Document,
  message: string,
  onRetry: () => void,
): HTMLElement {
  const failed = element(document, "div", TURN_FAILED_CLASS);
  const sentence = element(document, "p");
  sentence.textContent = message;
  const retry = element(
    document,
    "button",
    TRY_AGAIN_CLASS,
  ) as HTMLButtonElement;
  retry.type = "button";
  retry.textContent = TRY_AGAIN_LABEL;
  retry.addEventListener("click", () => onRetry());
  failed.append(sentence, retry);
  return failed;
}

/** [html] -- a fragment the SERVER rendered, never model text -- as nodes to insert. */
function serverFragment(document: Document, html: string): DocumentFragment {
  const template = document.createElement("template");
  template.innerHTML = html;
  return template.content;
}

function element(
  document: Document,
  tag: string,
  classes?: string,
): HTMLElement {
  const created = document.createElement(tag);
  if (classes !== undefined) {
    created.className = classes;
  }
  return created;
}
