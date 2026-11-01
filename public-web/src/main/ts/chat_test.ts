/**
 * The chat client's rules, driven without a browser (RFC 262 tests 27-30).
 *
 * The stream parser and the scroll rule are pure. The turn state machine is
 * driven through the narrow interfaces `ChatController` takes, with
 * hand-written structural fakes -- the `backToTop_test.ts` approach -- and
 * Deno's own `Response` and `ReadableStream` for the stream, so the reading
 * loop is exercised against the real web-platform types it meets in a browser.
 *
 * What this CANNOT prove: anything a browser decides -- that a template's
 * fragment parses, that focus lands, that a scroll position renders. The
 * server's half (the stream it writes, the notices, the fragment) is asserted
 * on the Kotlin side against the served bytes.
 */
import {
  ACCEPT_HEADER,
  ChatController,
  COACH_MESSAGE_CLASSES,
  type Composer,
  COMPOSER_SELECTOR,
  CSRF_FIELD,
  CSRF_HEADER,
  decodeEvent,
  domComposer,
  EMPTY_STATE_SELECTOR,
  EXCHANGE_CLASS,
  FOLLOW_THRESHOLD_PX,
  isNearBottom,
  NOT_ANSWERED_CLASS,
  NOTICE_HEADER,
  type Page,
  type PendingTurn,
  type Post,
  type RawEvent,
  RETRIABLE_FAILURE_ATTRIBUTE,
  ScrollFollower,
  SseParser,
  STUDENT_MESSAGE_CLASSES,
  type Transcript,
  TRANSCRIPT_SELECTOR,
  TURN_FAILED_CLASS,
  turnRequest,
  type TurnResponse,
  TYPING_CLASS,
  UNANSWERED_CLASS,
} from "./chat.ts";
// The contract `ChatClientContractTest` holds the SERVED page and stream to.
// Asserting this client against the same file is what joins the two halves:
// neither language can rename a hook or an event field alone.
import contract from "../../test/resources/chat-client-contract.json" with {
  type: "json",
};

function assert(condition: boolean, message: string): void {
  if (!condition) {
    throw new Error(message);
  }
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`${message}: expected ${e}, got ${a}`);
  }
}

function frame(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/**
 * Runs [body] with `console[method]` recorded instead of printed: the client's
 * one diagnostic channel, so a test can assert a cause was reported rather
 * than dropped.
 */
async function recordingConsole(
  method: "warn" | "error",
  body: () => unknown,
): Promise<unknown[][]> {
  const original = console[method];
  const calls: unknown[][] = [];
  console[method] = (...args: unknown[]) => {
    calls.push(args);
  };
  try {
    await body();
  } finally {
    console[method] = original;
  }
  return calls;
}

// --- Test 27: the parser ----------------------------------------------------

Deno.test("one event parses", () => {
  assertEquals(
    new SseParser().push(frame("delta", { text: "hi" })),
    [{ event: "delta", data: '{"text":"hi"}' }],
    "one frame",
  );
});

Deno.test("several events in one chunk parse in order", () => {
  const events = new SseParser().push(
    frame("started", { conversationUrl: "/chat/1" }) +
      frame("delta", { text: "a" }),
  );
  assertEquals(events.map((e) => e.event), ["started", "delta"], "two frames");
});

Deno.test("a frame split at every offset parses identically", () => {
  const whole = frame("delta", { text: "Hello\nthere" }) +
    frame("completed", { html: "<div>x</div>" });
  const expected = new SseParser().push(whole);
  for (let cut = 0; cut <= whole.length; cut++) {
    const parser = new SseParser();
    const events = [
      ...parser.push(whole.slice(0, cut)),
      ...parser.push(whole.slice(cut)),
    ];
    assertEquals(events, expected, `split at ${cut}`);
  }
});

Deno.test("a trailing partial frame is held until the chunk that completes it", () => {
  const parser = new SseParser();
  assertEquals(
    parser.push('event: delta\ndata: {"te'),
    [],
    "nothing complete yet",
  );
  assertEquals(
    parser.push('xt":"a"}\n\n').map((e) => e.data),
    ['{"text":"a"}'],
    "completed by the next chunk",
  );
});

Deno.test("an unknown event is ignored, not fatal", async () => {
  const raw: RawEvent = { event: "heartbeat", data: "{}" };
  const warnings = await recordingConsole("warn", () => {
    assertEquals(decodeEvent(raw), null, "unknown event");
  });
  assertEquals(
    warnings.length,
    0,
    "a later server's event is skipped silently",
  );
});

Deno.test("a known event that does not decode is dropped and reported, never thrown", async () => {
  for (const data of ["not json", "null", "[]", "3", '"text"', '{"text":3}']) {
    const warnings = await recordingConsole("warn", () => {
      assertEquals(
        decodeEvent({ event: "delta", data }),
        null,
        `[${data}] decodes to nothing`,
      );
    });
    assertEquals(warnings.length, 1, `[${data}] is reported`);
  }
});

// --- Fakes --------------------------------------------------------------

interface TurnRecord {
  readonly studentText: string;
  deltas: string[];
  completed: string | null;
  failed: string | null;
  retry: (() => void) | null;
  notice: string | null;
}

class FakeComposer implements Composer {
  value = "";
  target = "/chat";
  sendingLog: boolean[] = [];
  text(): string {
    return this.value;
  }
  setText(text: string): void {
    this.value = text;
  }
  action(): string {
    return this.target;
  }
  setAction(url: string): void {
    this.target = url;
  }
  body(text: string): URLSearchParams {
    return new URLSearchParams({ message: text });
  }
  csrf = "session-csrf-token";
  csrfToken(): string {
    return this.csrf;
  }
  setSending(sending: boolean): void {
    this.sendingLog.push(sending);
  }
  get sending(): boolean {
    return this.sendingLog.at(-1) ?? false;
  }
}

class FakeTranscript implements Transcript {
  turns: TurnRecord[] = [];
  beginTurn(studentText: string): PendingTurn {
    const record: TurnRecord = {
      studentText,
      deltas: [],
      completed: null,
      failed: null,
      retry: null,
      notice: null,
    };
    this.turns.push(record);
    return {
      appendDelta: (text) => record.deltas.push(text),
      complete: (html) => {
        record.completed = html;
      },
      fail: (message, onRetry) => {
        record.deltas = [];
        record.failed = message;
        record.retry = onRetry;
      },
      notice: (html) => {
        record.notice = html;
      },
    };
  }
}

class FakePage implements Page {
  replaced: string[] = [];
  navigated: string[] = [];
  replaceUrl(url: string): void {
    this.replaced.push(url);
  }
  navigate(url: string): void {
    this.navigated.push(url);
  }
}

const RETRIABLE = "The coach is temporarily unavailable — try again.";

function streamOf(chunks: string[], fail = false): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      if (fail) {
        controller.error(new Error("connection reset"));
      } else {
        controller.close();
      }
    },
  });
}

function streamResponse(chunks: string[], fail = false): TurnResponse {
  return new Response(streamOf(chunks, fail), {
    status: 200,
    headers: { "content-type": "text/event-stream" },
  });
}

function harness(respond: Post) {
  const composer = new FakeComposer();
  const transcript = new FakeTranscript();
  const page = new FakePage();
  const posts: Array<{ url: string; body: string; csrfToken: string }> = [];
  const controller = new ChatController({
    composer,
    transcript,
    page,
    retriableFailure: RETRIABLE,
    post: (url, body, csrfToken) => {
      posts.push({ url, body: body.toString(), csrfToken });
      return respond(url, body, csrfToken);
    },
  });
  return { composer, transcript, page, posts, controller };
}

// --- Test 27, at the byte level: a chunk boundary inside a character --------

Deno.test("a multi-byte delta split at every byte offset is painted whole", async () => {
  // The em dash and the check mark are three UTF-8 bytes each, "é" two, so
  // some cuts land inside a character: the reading loop's decoder must hold
  // the partial bytes rather than paint U+FFFD.
  const text = "café — ✓";
  const bytes = new TextEncoder().encode(
    frame("delta", { text }) + frame("completed", { html: "<div></div>" }),
  );
  for (let cut = 1; cut < bytes.length; cut++) {
    const h = harness(() =>
      Promise.resolve(
        new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(bytes.slice(0, cut));
              controller.enqueue(bytes.slice(cut));
              controller.close();
            },
          }),
          { status: 200, headers: { "content-type": "text/event-stream" } },
        ),
      )
    );
    h.composer.value = "Q";
    await h.controller.submit();
    assertEquals(h.transcript.turns[0].deltas, [text], `cut at byte ${cut}`);
    assertEquals(
      h.transcript.turns[0].completed,
      "<div></div>",
      `cut at byte ${cut}`,
    );
  }
});

// --- Test 28: the turn state machine --------------------------------------

Deno.test("started, deltas, completed paints the text, swaps the HTML and moves the form and history", async () => {
  const h = harness(() =>
    Promise.resolve(streamResponse([
      frame("started", { conversationUrl: "/chat/abc" }),
      frame("delta", { text: "Hel" }) + frame("delta", { text: "lo" }),
      frame("completed", { html: '<div class="message coach">Hello</div>' }),
    ]))
  );
  h.composer.value = "Hi coach";
  await h.controller.submit();

  const turn = h.transcript.turns[0];
  assertEquals(turn.studentText, "Hi coach", "the student's words are shown");
  assertEquals(turn.deltas, ["Hel", "lo"], "deltas painted in order");
  assertEquals(
    turn.completed,
    '<div class="message coach">Hello</div>',
    "the server's html swapped in",
  );
  assertEquals(
    h.composer.target,
    "/chat/abc",
    "the form continues the conversation",
  );
  assertEquals(
    h.page.replaced,
    ["/chat/abc"],
    "history names the conversation",
  );
  assertEquals(h.composer.value, "", "the composer was cleared on send");
  assertEquals(h.posts[0].body, "message=Hi+coach", "the form body");
});

Deno.test("failed removes the partial reply, shows the sentence and restores the text", async () => {
  const h = harness(() =>
    Promise.resolve(streamResponse([
      frame("started", { conversationUrl: "/chat/abc" }),
      frame("delta", { text: "Half" }),
      frame("failed", {
        retriable: false,
        message: "The coach could not respond to this message.",
      }),
    ]))
  );
  h.composer.value = "My question";
  await h.controller.submit();

  const turn = h.transcript.turns[0];
  assertEquals(turn.deltas, [], "no partial text left looking like an answer");
  assertEquals(
    turn.failed,
    "The coach could not respond to this message.",
    "the server's sentence",
  );
  assertEquals(
    h.composer.value,
    "My question",
    "the words are back in the composer",
  );
  assertEquals(
    h.composer.target,
    "/chat",
    "a failed first turn does not move the form",
  );
  assertEquals(h.page.replaced, [], "nor the history");
});

Deno.test("Try again re-sends the same words", async () => {
  let call = 0;
  const h = harness(() => {
    call++;
    return call === 1
      ? Promise.resolve(
        streamResponse([
          frame("failed", { retriable: true, message: RETRIABLE }),
        ]),
      )
      : Promise.resolve(
        streamResponse([frame("completed", { html: "<div>ok</div>" })]),
      );
  });
  h.composer.value = "Retry me";
  await h.controller.submit();
  h.transcript.turns[0].retry!();
  await new Promise((resolve) => setTimeout(resolve, 0));

  assertEquals(h.posts.map((p) => p.body), [
    "message=Retry+me",
    "message=Retry+me",
  ], "the same words, sent twice");
  assertEquals(
    h.transcript.turns[1].completed,
    "<div>ok</div>",
    "the retry completed",
  );
  assertEquals(h.composer.value, "", "the restored text was taken back out");
});

Deno.test("a stream that ends without a terminal is the retriable failure", async () => {
  const h = harness(() =>
    Promise.resolve(streamResponse([frame("delta", { text: "cut" })]))
  );
  h.composer.value = "Q";
  await h.controller.submit();
  assertEquals(
    h.transcript.turns[0].failed,
    RETRIABLE,
    "EOF without a terminal",
  );
  assertEquals(h.composer.value, "Q", "text restored");
});

Deno.test("a read error mid-stream is the retriable failure", async () => {
  const h = harness(() =>
    Promise.resolve(streamResponse([frame("delta", { text: "cut" })], true))
  );
  h.composer.value = "Q";
  const errors = await recordingConsole("error", () => h.controller.submit());
  assertEquals(h.transcript.turns[0].failed, RETRIABLE, "read error");
  assertEquals(errors.length, 1, "the read error is reported, not dropped");
});

Deno.test("a response the fetch reached by a redirect navigates the page there", async () => {
  const redirected: TurnResponse = {
    status: 200,
    redirected: true,
    url: "https://app.example/get-started",
    headers: new Headers({ "content-type": "text/html" }),
    body: null,
    text: () => Promise.resolve("<html></html>"),
  };
  const h = harness(() => Promise.resolve(redirected));
  h.composer.value = "Q";
  await h.controller.submit();
  assertEquals(
    h.page.navigated,
    ["https://app.example/get-started"],
    "navigated",
  );
  assertEquals(
    h.transcript.turns[0].notice,
    null,
    "the page is not pasted into the transcript",
  );
});

Deno.test("a non-stream response is a notice in the reply's place, and the text returns", async () => {
  const notice = '<div class="chat-notice"><h2>Coaching is paused</h2></div>';
  const h = harness(() =>
    Promise.resolve(
      new Response(notice, {
        status: 402,
        headers: { "content-type": "text/html", [NOTICE_HEADER]: "1" },
      }),
    )
  );
  h.composer.value = "Q";
  await h.controller.submit();
  assertEquals(h.transcript.turns[0].notice, notice, "the server's notice");
  assertEquals(h.composer.value, "Q", "text restored");
  assertEquals(
    h.transcript.turns[0].failed,
    null,
    "a notice is not a failed turn",
  );
});

Deno.test("a non-stream response that is not our notice is the retriable failure, never pasted in", async () => {
  // The branded 503 page, a proxy's 502, a branded 404: whole pages, unmarked.
  for (const status of [503, 502, 404, 400]) {
    const page =
      "<!DOCTYPE html><html><body><h1>Something went wrong</h1></body></html>";
    const h = harness(() =>
      Promise.resolve(
        new Response(page, {
          status,
          headers: { "content-type": "text/html" },
        }),
      )
    );
    h.composer.value = "Q";
    const errors = await recordingConsole("error", () => h.controller.submit());
    assertEquals(
      h.transcript.turns[0].notice,
      null,
      `[${status}] not pasted into the transcript`,
    );
    assertEquals(
      h.transcript.turns[0].failed,
      RETRIABLE,
      `[${status}] the retriable failure`,
    );
    assertEquals(h.composer.value, "Q", `[${status}] text restored`);
    assertEquals(
      errors.length,
      1,
      `[${status}] the unexpected answer is reported`,
    );
  }
});

Deno.test("a notice whose body cannot be read is the retriable failure", async () => {
  const unreadable: TurnResponse = {
    status: 400,
    redirected: false,
    url: "https://app.example/chat",
    headers: new Headers({ "content-type": "text/html", [NOTICE_HEADER]: "1" }),
    body: null,
    text: () => Promise.reject(new TypeError("connection reset")),
  };
  const h = harness(() => Promise.resolve(unreadable));
  h.composer.value = "Q";
  const errors = await recordingConsole("error", () => h.controller.submit());
  assertEquals(h.transcript.turns[0].failed, RETRIABLE, "an unreadable notice");
  assertEquals(h.transcript.turns[0].notice, null, "nothing pasted");
  assertEquals(h.composer.value, "Q", "text restored");
  assertEquals(h.composer.sending, false, "Send re-enabled");
  assertEquals(errors.length, 1, "the read failure is reported");
});

Deno.test("a fetch that rejects is the retriable failure", async () => {
  const h = harness(() => Promise.reject(new TypeError("offline")));
  h.composer.value = "Q";
  const errors = await recordingConsole("error", () => h.controller.submit());
  assertEquals(h.transcript.turns[0].failed, RETRIABLE, "fetch rejection");
  assertEquals(h.composer.value, "Q", "text restored");
  assertEquals(errors.length, 1, "the rejection is reported, not dropped");
});

Deno.test("a blank message sends nothing", async () => {
  const h = harness(() => Promise.reject(new Error("must not be called")));
  h.composer.value = "   \n ";
  await h.controller.submit();
  assertEquals(h.posts.length, 0, "no POST");
  assertEquals(h.transcript.turns.length, 0, "no turn drawn");
});

Deno.test("restoring never overwrites what the student typed while the turn was out", async () => {
  let release: (r: TurnResponse) => void = () => {};
  const h = harness(() => new Promise((resolve) => (release = resolve)));
  h.composer.value = "First";
  const sending = h.controller.submit();
  h.composer.value = "Typed meanwhile";
  release(
    new Response("<div></div>", {
      status: 400,
      headers: { "content-type": "text/html", [NOTICE_HEADER]: "1" },
    }),
  );
  await sending;
  assertEquals(h.composer.value, "Typed meanwhile", "the new typing is kept");
});

// --- Test 29: one turn in flight; Send, never the text area ---------------

Deno.test("Send is disabled while a turn is in flight and re-enabled on every terminal", async () => {
  const outcomes: Array<() => Promise<TurnResponse>> = [
    () =>
      Promise.resolve(
        streamResponse([frame("completed", { html: "<div></div>" })]),
      ),
    () =>
      Promise.resolve(
        streamResponse([
          frame("failed", { retriable: true, message: RETRIABLE }),
        ]),
      ),
    () => Promise.resolve(streamResponse([])),
    () =>
      Promise.resolve(
        new Response("<div></div>", {
          status: 404,
          headers: { "content-type": "text/html", [NOTICE_HEADER]: "1" },
        }),
      ),
    () => Promise.reject(new TypeError("offline")),
  ];
  for (const [index, outcome] of outcomes.entries()) {
    let sendingDuringPost: boolean | null = null;
    const h = harness(() => {
      sendingDuringPost = h.composer.sending;
      return outcome();
    });
    h.composer.value = "Q";
    await recordingConsole("error", () => h.controller.submit());
    assertEquals(
      sendingDuringPost,
      true,
      `outcome ${index}: Send disabled while in flight`,
    );
    assertEquals(
      h.composer.sending,
      false,
      `outcome ${index}: Send re-enabled at the terminal`,
    );
  }
});

Deno.test("a second send while a turn is in flight sends nothing, and the composer stays writable", async () => {
  let release: (r: TurnResponse) => void = () => {};
  const h = harness(() => new Promise((resolve) => (release = resolve)));
  h.composer.value = "First";
  const first = h.controller.submit();
  h.composer.value = "Second";
  await h.controller.submit();
  assertEquals(h.posts.length, 1, "one turn in flight per conversation");
  assertEquals(h.composer.value, "Second", "typing during a stream is kept");
  release(streamResponse([frame("completed", { html: "<div></div>" })]));
  await first;
});

Deno.test("the page's composer disables Send in flight and never the text area", () => {
  // Structural stand-ins for the three elements `domComposer` touches.
  const form = {
    getAttribute: (_name: string) => "/chat",
    setAttribute: (_name: string, _value: string) => {},
  } as unknown as HTMLFormElement;
  const textarea = {
    value: "",
    name: "message",
    disabled: false,
  } as unknown as HTMLTextAreaElement;
  const button = { disabled: false } as unknown as HTMLButtonElement;
  const composer = domComposer(form, textarea, button, "/chat", "tok");

  composer.setSending(true);
  assertEquals(button.disabled, true, "Send disabled while in flight");
  assertEquals(textarea.disabled, false, "the text area stays writable");
  composer.setSending(false);
  assertEquals(button.disabled, false, "Send re-enabled at the terminal");
  assertEquals(textarea.disabled, false, "the text area stays writable");
});

Deno.test("the POST asks for the stream or an HTML notice", () => {
  assertEquals(ACCEPT_HEADER, "text/event-stream, text/html", "Accept");
});

// --- RFC 265: the CSRF token ---------------------------------------------------

Deno.test("a turn posts the composer's CSRF token", async () => {
  const h = harness(() =>
    Promise.resolve(
      streamResponse([frame("completed", { html: "<div></div>" })]),
    )
  );
  h.composer.value = "Hi";
  h.composer.csrf = "the-composers-token";
  await h.controller.submit();
  assertEquals(
    h.posts.map((p) => p.csrfToken),
    ["the-composers-token"],
    "the POST did not carry the composer's token",
  );
});

Deno.test("the turn's request sends the token as X-CSRF-Token", () => {
  const init = turnRequest(new URLSearchParams({ message: "Hi" }), "tok-123");
  const headers = new Headers(init.headers);
  assertEquals(headers.get(CSRF_HEADER), "tok-123", "X-CSRF-Token");
  assertEquals(headers.get("Accept"), ACCEPT_HEADER, "Accept");
  assertEquals(init.method, "POST", "method");
  assertEquals(init.credentials, "same-origin", "credentials");
});

Deno.test("the page's composer hands the turn the token it was mounted with", () => {
  const form = {
    getAttribute: (_name: string) => "/chat",
    setAttribute: (_name: string, _value: string) => {},
  } as unknown as HTMLFormElement;
  const textarea = {
    value: "",
    name: "message",
  } as unknown as HTMLTextAreaElement;
  const button = { disabled: false } as unknown as HTMLButtonElement;
  const composer = domComposer(
    form,
    textarea,
    button,
    "/chat",
    "from-the-field",
  );
  assertEquals(composer.csrfToken(), "from-the-field", "csrfToken");
  assertEquals(
    composer.body("Hi").has(CSRF_FIELD),
    false,
    "the token travels in the header, not the message body",
  );
});

Deno.test("the CSRF field and header are the contract's", () => {
  assertEquals(
    { field: CSRF_FIELD, header: CSRF_HEADER },
    contract.csrf,
    "the CSRF names differ from the ones the server is held to",
  );
});

// --- Test 30: scroll follow --------------------------------------------------

Deno.test("the transcript follows within the threshold and not beyond it", () => {
  assert(
    isNearBottom({ scrollTop: 920, scrollHeight: 2000, clientHeight: 1000 }),
    "80px from the bottom follows",
  );
  assert(
    !isNearBottom({
      scrollTop: 920 - 1,
      scrollHeight: 2000,
      clientHeight: 1000,
    }),
    `${FOLLOW_THRESHOLD_PX + 1}px from the bottom does not`,
  );
});

Deno.test("scrolling up stops the follow and scrolling back down resumes it", () => {
  const element = { scrollTop: 1000, scrollHeight: 2000, clientHeight: 1000 };
  const follower = new ScrollFollower(element);

  element.scrollHeight = 2100;
  follower.afterGrowth();
  assertEquals(element.scrollTop, 2100, "at the bottom, growth is followed");

  element.scrollTop = 200;
  follower.onScroll();
  element.scrollHeight = 2300;
  follower.afterGrowth();
  assertEquals(element.scrollTop, 200, "scrolled up, the view is left alone");

  element.scrollTop = 1300;
  follower.onScroll();
  element.scrollHeight = 2400;
  follower.afterGrowth();
  assertEquals(
    element.scrollTop,
    2400,
    "back at the bottom, following resumes",
  );
});

// --- The contract with the server --------------------------------------------

Deno.test("the hooks this client mounts on are the contract's", () => {
  assertEquals(
    {
      composerSelector: COMPOSER_SELECTOR,
      transcriptSelector: TRANSCRIPT_SELECTOR,
      emptyStateSelector: EMPTY_STATE_SELECTOR,
      retriableFailureAttribute: RETRIABLE_FAILURE_ATTRIBUTE,
      exchangeClass: EXCHANGE_CLASS,
      studentMessageClasses: STUDENT_MESSAGE_CLASSES,
      coachMessageClasses: COACH_MESSAGE_CLASSES,
    },
    contract.page,
    "a page hook differs from the one the server is held to",
  );
});

Deno.test("the notice marker this client trusts is the contract's", () => {
  assertEquals(
    NOTICE_HEADER,
    contract.notice.header,
    "the notice header differs from the one the server is held to",
  );
});

Deno.test("the styled classes this client builds are the contract's", () => {
  assertEquals(
    {
      typing: TYPING_CLASS,
      unanswered: UNANSWERED_CLASS,
      notAnswered: NOT_ANSWERED_CLASS,
      turnFailed: TURN_FAILED_CLASS,
    },
    contract.styledClientClasses,
    "a class the stylesheet is held to differs from the one this client builds",
  );
});

Deno.test("every event the server is held to decodes, field for field", () => {
  for (const { event, data } of contract.events) {
    const [raw] = new SseParser().push(frame(event, data));
    assertEquals<unknown>(
      decodeEvent(raw),
      { kind: event, ...data },
      `the contract's [${event}] event`,
    );
  }
});
