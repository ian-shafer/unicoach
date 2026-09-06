"""Shared helpers for the python3 scripts in bin/ -- the counterpart to
bin/functions, which the bash ones source.

bin/functions is where a bash script gets its EXIT_* band, its log-* family and
its fatal; there was no equivalent for python, so bin/fetch-ipeds,
bin/fetch-codebooks and bin/fetch-cds-seed each grew their own copy of the same
constants, the same four log helpers and the same ~55-line post-getopt guard
suite. Three copies is three chances to drift, and they had already drifted:
log_error existed in one of the three, and fetch-ipeds's "-o names something that
is not a directory" check existed in one of the three.

This is a MODULE, not a command: it is imported, never run, so it has no
argument grammar, no main and no executable bit. What stays in each script is
what genuinely differs -- its own GETOPT_SPEC, and the post-parse rules only it
has (fetch-ipeds's "-F requires -o" and its output-directory check).

Import it by resolving THIS directory from the importing script, never from the
caller's cwd:

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from pyfunctions import fatal, log_info, parse_short_options
"""

import contextlib
import getopt
import http.client
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

# The usage-error exit-code band bin/functions reserves (10-29), so a caller who
# invoked a script wrongly is distinguishable from a run that faulted. The values
# and the meanings are bin/functions'; keeping them in one python file means a
# python script cannot disagree with its bash siblings about what a 20 is.
EXIT_UNKNOWN_OPTION = 10
EXIT_OPTION_REQUIRES_VALUE = 11
EXIT_UNEXPECTED_ARG = 20
EXIT_MISSING_REQUIRED_ARG = 21
EXIT_INVALID_ARG_VALUE = 22


# The network bounds every fetcher in bin/ uses, named once here rather than
# three times. The timeout is per socket READ, not per download: a large artifact
# arrives in many reads, so this is a bound on a stalled connection rather than
# on a slow one. Retries cover throttling and server-side faults only -- every
# other status is the REQUEST being wrong (a renamed or retired file), and
# retrying that only delays the diagnosis.
HTTP_MAX_ATTEMPTS = 3
HTTP_TIMEOUT_SECONDS = 60
HTTP_RETRY_BACKOFF_SECONDS = 2
RETRYABLE_STATUS = frozenset({408, 429, 500, 502, 503, 504})
# How much of an error response body is quoted back. A publisher's own
# explanation lives in the first line or two; the rest is markup.
HTTP_ERROR_BODY_PREVIEW_BYTES = 200


def log_info(message):
    """Anything a script says, on the channel and with the (absent) prefix
    bin/functions' log-info uses: stderr, no prefix.

    LOGGING GOES TO STDERR, all of it -- progress, warnings and the closing
    summary. These scripts' PRODUCT is files on disk, not a data stream, so a
    normal run leaves stdout EMPTY; the help answering -h is the one thing a
    caller asks for by name, and parse_short_options prints that to stdout."""
    print(message, file=sys.stderr)


def log_warning(message):
    """A non-fatal fault, with the prefix bin/functions' log-warning uses, so one
    grep finds them across shell and python."""
    print(f"[WARNING] {message}", file=sys.stderr)


def log_error(message):
    """A fault the run did not die of but that must not be read past. Distinct
    from log_warning: a recovered fallback is a warning, while writing a seed
    with a baseline guard provably disarmed is an error."""
    print(f"[ERROR] {message}", file=sys.stderr)


def fatal(message, status=1):
    """Reports on the [FATAL] channel and EXITS: this call never returns, which
    is why the guards that call it end on a bare fatal() with no fall-through."""
    print(f"[FATAL] {message}", file=sys.stderr)
    sys.exit(status)


def bracketed(names):
    """Names for a message: each one in brackets, in a stable order.

    Interpolating a list directly renders a Python repr, which wraps every
    element in the single quotes this project's messages use for nothing --
    brackets are the one wrapper, so a name carrying a space or a quote is still
    read unambiguously."""
    return ", ".join(f"[{name}]" for name in sorted(names))


def write_atomically(files, script_name):
    """Write every (target, body) pair, or none of them.

    All-or-nothing as a SET, not per file. Writing each file and then the
    manifest one at a time means an OSError on the largest one (ENOSPC is real)
    leaves new data sitting beside stale data under the OLD manifest -- and the
    next run adopts that stale manifest as its baseline, which is exactly what
    the shrink and drift guards exist to prevent. These directories hold
    COMMITTED, REVIEWED files, so a half-written refresh is a half-reviewed diff.

    Every body is staged to a temp file in the SAME directory first, and only
    once all of them are on disk are they renamed into place: os.replace is
    atomic within a filesystem. On any fault the staged files are removed, so no
    orphan ".partial" is left in the operator's output directory, and the refusal
    comes through the script's own channel.

    The RENAME half cannot be undone. Staging is all-or-nothing, but a fault on
    the second or third os.replace (a target that exists as a directory, an
    unwritable or immutable target, EPERM on a shared mount) leaves the earlier
    targets already replaced and the later ones -- the manifest among them --
    standing as they were. That state is REPORTED rather than denied: the files
    already renamed are named, and the refusal says the directory is mixed and
    must be re-fetched. Claiming "nothing partial was left behind" there would
    tell an operator no repair is needed for exactly the half-written refresh
    these guards exist to prevent."""
    staged = []
    replaced = []
    try:
        for target, body in files:
            partial = target.with_name(f".{target.name}.partial")
            partial.write_bytes(body)
            staged.append((partial, target))
        for partial, target in staged:
            os.replace(partial, target)
            replaced.append(target)
    except OSError as error:
        for partial, _ in staged:
            with contextlib.suppress(OSError):
                partial.unlink()
        if replaced:
            left_on_disk = (
                f"[{len(replaced)}] of [{len(files)}] files had ALREADY been renamed into "
                f"place ({bracketed(str(target) for target in replaced)}) and the rest stand "
                f"as they were: the directory is MIXED -- some files are from this run, some "
                f"are not, and the manifest may describe neither set. Do not load from it; "
                f"re-run the fetch."
            )
        else:
            left_on_disk = (
                "Nothing partial was left behind; the previous contents of the directory "
                "stand as they were."
            )
        fatal(
            f"{script_name}: writing [{len(files)}] files failed: "
            f"[{type(error).__name__}] [{error}]. {left_on_disk}"
        )


# ---------------------------------------------------------------------------
# The download stack, shared by every fetcher that downloads anything
# ---------------------------------------------------------------------------
#
# The retry policy, the chunked read and the progress reporter were a private
# copy in each fetcher, beside the HTTP_* bounds they already imported from
# here. Nothing in them knows what any one publisher publishes, so they live
# with the constants they consume and the refusal channel takes the caller's
# name, exactly as write_atomically does.
#
# The body is read in CHUNKS rather than in one response.read(), so that a
# download the operator is waiting on reports as it goes. 64 KiB is a read size,
# not a progress interval -- how often anything is PRINTED is decided below.
DOWNLOAD_CHUNK_BYTES = 64 * 1024
# On a TTY the progress line is rewritten in place, at most this often: a fast
# local read arrives in hundreds of chunks a second and formatting every one of
# them is work spent on frames nobody sees.
PROGRESS_TTY_INTERVAL_SECONDS = 0.1
# Off a TTY (a log, a CI capture, bin/scripts-tests) there is no line to rewrite,
# so progress is printed at coarse milestones instead -- every this-many percent,
# plus the final line. A carriage-return update stream there is thousands of
# lines of noise in a captured log.
PROGRESS_MILESTONE_PERCENT = 25


def format_bytes(count):
    """A byte count an operator can read: [23.6 MB] rather than [23559465].

    Decimal units (1000), because that is what the publishers' pages and every
    download UI state a file size in, and this number is read beside them. The
    exact byte counts are still what a PROVENANCE.json records -- this is for the
    progress channel only, where "is it nearly done" is the whole question."""
    size = float(count)
    for unit in ("B", "KB", "MB", "GB"):
        if abs(size) < 1000 or unit == "GB":
            # Whole bytes stay whole: [512 B], not [512.0 B].
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1000
    raise AssertionError("unreachable")


def get_content_length(headers):
    """The Content-Length as an int, or None when the server did not send a
    usable one.

    MISSING is normal (a chunked response has no length at all) and malformed is
    possible, and neither may cost the download: an absent total means progress
    reports bytes so far with no percentage, never "None%" and never a
    traceback three quarters of the way through a large fetch."""
    raw = headers.get("Content-Length")
    if raw is None:
        return None
    try:
        total = int(raw.strip())
    except (AttributeError, ValueError) as error:
        # Said out loud rather than folded into "the server sent no length": a
        # publisher serving [Content-Length: 23,559,465] is not a chunked
        # response, and the difference is what an operator needs to know when
        # the progress line stops reporting a percentage.
        log_warning(
            f"the server sent an unusable [Content-Length: {raw}] "
            f"([{type(error).__name__}] [{error}]); the download proceeds, reporting bytes "
            f"so far with no percentage"
        )
        return None
    # A zero or negative length is not a total anything can be a fraction of.
    return total if total > 0 else None


class DownloadProgress:
    """The running report of one download, on log_info's channel.

    TWO shapes, because there are two readers. On a TTY a human is watching one
    line, so it is rewritten in place with \r and closed with a newline. Off a
    TTY -- a log file, CI, bin/scripts-tests -- there is nothing to rewrite, and
    a \r stream is thousands of unreadable lines in the capture, so the same
    facts are printed at milestones (every PROGRESS_MILESTONE_PERCENT) plus one
    final line. The final line is ALWAYS printed on both, so a download that
    reported nothing is a download that did not happen."""

    def __init__(self, label, total):
        self.label = label
        self.total = total
        self.tty = sys.stderr.isatty()
        self.last_emit = 0.0
        # The last milestone REPORTED, so a chunk that crosses two of them still
        # prints one line and a stalled read prints none.
        self.last_milestone = -1
        # The in-place line is padded back to the longest line already written:
        # "23.6 MB of 23.6 MB [100%]" is shorter than what preceded it, and
        # without the padding the tail of the previous line survives on screen.
        self.width = 0

    def format_line(self, seen):
        if self.total is None:
            return f"    [{self.label}] [{format_bytes(seen)}] (total not published)"
        percent = min(100, int(seen * 100 / self.total))
        return (
            f"    [{self.label}] [{format_bytes(seen)}] of [{format_bytes(self.total)}] "
            f"[{percent}%]"
        )

    def update(self, seen):
        """Report an in-flight position, or say nothing. Called once per chunk,
        so every path here is throttled."""
        if self.tty:
            now = time.monotonic()
            if now - self.last_emit < PROGRESS_TTY_INTERVAL_SECONDS:
                return
            self.last_emit = now
            self.write(seen, end="")
            return
        if self.total is None:
            # No total, so no milestone can be computed; the final line below
            # still reports what arrived. Guessing an interval in bytes would
            # print an unbounded number of lines for an unknown-size body.
            return
        milestone = int(seen * 100 / self.total) // PROGRESS_MILESTONE_PERCENT
        # 100% belongs to finish(), which prints it exactly once.
        if milestone <= self.last_milestone or milestone * PROGRESS_MILESTONE_PERCENT >= 100:
            return
        self.last_milestone = milestone
        log_info(self.format_line(seen))

    def finish(self, seen):
        """The final position, always printed, and on a TTY the newline that
        ends the line every update() has been overwriting."""
        if self.tty:
            self.write(seen, end="\n")
            return
        log_info(self.format_line(seen))

    def write(self, seen, end):
        text = self.format_line(seen)
        self.width = max(self.width, len(text))
        print(f"\r{text:<{self.width}}", end=end, file=sys.stderr, flush=True)


def read_body_with_progress(response, label):
    """The whole response body, read in chunks so that the wait is visible.

    A single response.read() is one call that returns after however long the
    body takes; the operator sees nothing until it does, and the largest
    artifact any of these fetchers takes is tens of megabytes. Chunking changes
    nothing about what is returned -- the bytes are joined and hashed exactly as
    before -- only about what is said while it arrives.

    A body SHORTER than the declared Content-Length is a dropped connection, and
    it is raised here as the transient fault http_get already retries. It has to
    be detected here: http.client's own source says a bounded read("amt") returns
    a short body rather than raising IncompleteRead ("Ideally, we would raise
    IncompleteRead if the content-length wasn't satisfied, but it might break
    compatibility"), so chunked reading turns the one fault the payload guards
    below cannot re-derive -- did the response finish -- into a silent
    success."""
    total = get_content_length(response.headers)
    progress = DownloadProgress(label, total)
    chunks = []
    seen = 0
    while True:
        chunk = response.read(DOWNLOAD_CHUNK_BYTES)
        if not chunk:
            break
        chunks.append(chunk)
        seen += len(chunk)
        progress.update(seen)
    progress.finish(seen)
    body = b"".join(chunks)
    if total is not None and seen != total:
        # An UNDER-read is a dropped connection; an over-read is a response that
        # contradicts its own header. Neither is the artifact, and neither may be
        # returned as one: a truncated zip loses its end-of-central-directory and
        # is then misdiagnosed as an error page served with a 200, while a
        # truncated CSV simply parses.
        raise http.client.IncompleteRead(body, total - seen)
    return body


def http_get(url, label, script_name):
    """The response body, retrying only TRANSIENT faults. A permanent fault is
    reported immediately on the [FATAL] channel -- naming the attempt it died
    on and carrying the head of the response body, which is where a publisher's
    own explanation lives -- and the swallowed causes of the earlier attempts
    are logged rather than discarded.

    `label` names the artifact in the progress report: the URL is already on the
    start line, and a 23 MB download reporting itself against the full URL wraps
    the terminal line it is trying to rewrite in place.

    `script_name` prefixes every refusal, because the fetchers all run under one
    orchestrator (bin/fetch-external-data) and a bare "[FATAL] GET ... failed"
    costs the reader a lookup to find out which one died."""
    request = urllib.request.Request(url, headers={"User-Agent": "unicoach"})
    for attempt in range(HTTP_MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
                # Read INSIDE the try: a connection that drops mid-body now
                # faults here, in a chunk read, rather than in one read() call,
                # and must still be caught as the transient fault it is.
                return read_body_with_progress(response, label)
        except urllib.error.HTTPError as error:
            # Bound to a `with`: the body is read and the connection released
            # here, rather than held open across the retry sleep below.
            with error:
                # One byte MORE than is quoted: reading the preview plus one
                # says whether the body was truncated without holding the rest
                # of a publisher's error page in memory.
                #
                # Best-effort, because this read is network I/O on a connection
                # that has already misbehaved AND it sits inside an except
                # clause, where a raised fault is caught by none of the branches
                # below it. The STATUS is what is being reported; a body that
                # will not come off a broken connection must not replace a clean
                # "HTTP 503, retrying" with a traceback.
                try:
                    raw = error.read()[: HTTP_ERROR_BODY_PREVIEW_BYTES + 1]
                except (http.client.HTTPException, OSError) as body_error:
                    raw = (
                        f"<body unreadable: [{type(body_error).__name__}] [{body_error}]>"
                    ).encode("utf-8")
            body = raw[:HTTP_ERROR_BODY_PREVIEW_BYTES].decode("utf-8", errors="replace")
            if len(raw) > HTTP_ERROR_BODY_PREVIEW_BYTES:
                body += f"... (truncated at [{HTTP_ERROR_BODY_PREVIEW_BYTES}] bytes)"
            cause = f"HTTP [{error.code}] [{error.reason}] body [{body}]"
            if error.code not in RETRYABLE_STATUS:
                fatal(
                    f"{script_name}: GET [{url}] failed permanently on attempt "
                    f"[{attempt + 1}]: {cause}"
                )
        except TimeoutError as error:
            # Its own branch, so the bound that was exceeded is named: a socket
            # timeout reports as an empty message otherwise.
            cause = f"[{type(error).__name__}] timed out after [{HTTP_TIMEOUT_SECONDS}]s [{error}]"
        except urllib.error.URLError as error:
            cause = f"[{type(error).__name__}] [{error}]"
        except http.client.IncompleteRead as error:
            # The body did not match the declared Content-Length -- SHORT (the
            # connection dropped mid-body) or LONG (the response contradicts its
            # own header); read_body_with_progress raises this for both, because
            # neither body is the artifact. Neither a URLError nor a
            # TimeoutError, and as transient as either: retried, not escaped.
            cause = (
                f"[{type(error).__name__}] body did not match the declared "
                f"Content-Length [{error}]"
            )
        except (http.client.HTTPException, ssl.SSLError, ConnectionResetError) as error:
            # The rest of the transport layer: a malformed status line, an
            # over-long header, a TLS record fault mid-body, a reset. urllib wraps
            # NONE of these, and HTTPException is not even an OSError, so an
            # uncaught one leaves the calling script as a stdlib traceback naming
            # a /nix/store path -- off the [FATAL] channel bin/scripts-tests and
            # every operator grep on. The family is caught, not one member of it.
            cause = f"[{type(error).__name__}] transport fault [{error}]"
        if attempt == HTTP_MAX_ATTEMPTS - 1:
            fatal(
                f"{script_name}: GET [{url}] failed after [{HTTP_MAX_ATTEMPTS}] attempts: {cause}"
            )
        log_warning(
            f"GET [{url}] attempt [{attempt + 1}]/[{HTTP_MAX_ATTEMPTS}] failed: {cause}; retrying"
        )
        time.sleep(HTTP_RETRY_BACKOFF_SECONDS * (attempt + 1))
    raise AssertionError("unreachable")


def decode(body, filename, script_name):
    """utf-8-sig, falling back to the Windows codepage the publishers' tooling
    emits. utf-8-sig strips a BOM where there is one and is plain UTF-8 where
    there is not, so every payload is decoded the same way and a BOM never
    survives into the first header name a loader matches on.

    The fallback is GUARDED rather than trusted: cp1252 leaves five byte values
    undefined (0x81, 0x8d, 0x8f, 0x90, 0x9d), so a payload that is neither UTF-8
    nor cp1252 would raise from inside the recovery path. What this returns is
    what gets WRITTEN and hashed, so an undecodable payload is a refusal, not a
    traceback."""
    try:
        return body.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        log_warning(
            f"[{filename}] is not UTF-8 ([{error.reason}] at byte [{error.start}]); "
            f"decoding as cp1252"
        )
    try:
        return body.decode("cp1252")
    except UnicodeDecodeError as error:
        fatal(
            f"{script_name}: [{filename}] decodes as neither UTF-8 nor cp1252 "
            f"([{error.reason}] at byte [{error.start}]); the payload is not the text this "
            f"expects, and its bytes are what the recorded digest describes"
        )


def value_options(spec):
    """The options in `spec` that take a value, as ["-o", "-F"].

    Derived FROM the spec rather than listed beside it: a second hand-written
    list of the letters is a declaration that can drift from the one getopt
    actually parses with, and it would drift into reporting a missing value as an
    unknown option."""
    return [
        f"-{letter}"
        for index, letter in enumerate(spec)
        if letter != ":" and spec[index + 1 : index + 2] == ":"
    ]


def parse_short_options(argv, spec, doc, script_name):
    """{option: value} from argv for a script whose grammar is short options and
    NO positionals, fataling in the reserved band on any usage mistake.

    The GRAMMAR is getopt.getopt -- the stdlib's model of the getopts builtin
    every bash script in bin/ parses with, so a python3 script in bin/ follows
    the same house rule rather than hand-rolling a loop that reimplements it
    slightly differently.

    getopt gives the grammar and NOTHING else, so every check the grammar cannot
    make is made here, after it parses. A value beginning with "-" is a dropped
    operand -- getopt will happily take "-F" as the value of "-o" and make a
    directory named "-F"; an empty value silently means the current directory;
    and a repeated option is a last-wins guess about which of two paths the
    operator meant. In scripts that overwrite reviewed, committed artifacts, none
    of those may pass quietly.

    `doc` is the script's own module docstring, printed for -h. `script_name` names
    the script in every message, because these three run each other and a
    refusal must say which one refused.

    The values come back as plain strings: turning one into a Path (or an int, or
    a JWT) is the caller's decision, and the caller is the only one who knows.
    """
    takes_value = value_options(spec)
    try:
        parsed, positional = getopt.getopt(argv[1:], spec)
    except getopt.GetoptError as error:
        # ONE exception class for two different operator mistakes, so it is the
        # OFFENDING OPTION that separates them, not the message text: an option
        # the spec declares a value for faulted because the value is missing
        # (11), and anything else faulted because the option is not one of ours
        # (10). Matching on error.msg would tie these exit codes to the stdlib's
        # wording.
        offender = f"-{error.opt}" if error.opt else ""
        if offender in takes_value:
            fatal(
                f"{script_name}: option [{offender}] requires a value",
                status=EXIT_OPTION_REQUIRES_VALUE,
            )
        # The stdlib's own wording is never interpolated into what an operator
        # reads: getopt phrases things its way ("option --anon-key not
        # recognized"), and a message assembled here says what THIS project says.
        # Where getopt could not even name the offender, the argv it choked on is
        # the actionable datum.
        if offender:
            fatal(
                f"{script_name}: unknown option [{offender}]; this script takes "
                f"[{spec.replace(':', ' <value>')}]",
                status=EXIT_UNKNOWN_OPTION,
            )
        fatal(
            f"{script_name}: cannot parse the arguments {bracketed(argv[1:])}; this script takes "
            f"[{spec.replace(':', ' <value>')}]",
            status=EXIT_UNKNOWN_OPTION,
        )

    # -h is answered BEFORE the rest of the argv is judged: a caller reaching for
    # the help is a caller who does not yet know the grammar, and refusing them
    # the text over a stray word in the same command line helps nobody. The help
    # goes to STDOUT, the one thing here that does -- it is what that invocation
    # ASKED FOR, not a diagnostic printed alongside one.
    if any(option == "-h" for option, _ in parsed):
        print(doc)
        sys.exit(0)

    # getopt hands back everything after the options as operands. These scripts
    # declare none, so any of them is an argument beyond the grammar -- 20, the
    # code bin/functions reserves for exactly that, and NOT the 10 an unknown
    # option gets: a mistyped option and a stray word are different mistakes.
    if positional:
        fatal(
            f"{script_name}: unexpected argument {bracketed(positional)}; this script takes options "
            f"only [{spec.replace(':', ' <value>')}]",
            status=EXIT_UNEXPECTED_ARG,
        )

    options = {}
    for option, raw in parsed:
        if option in options:
            # BOTH values are quoted back. Declining to choose between two values
            # while withholding them tells the operator only that they typed the
            # option twice, which they can see; which two values are in conflict
            # is the part they cannot.
            fatal(
                f"{script_name}: option [{option}] was given twice, as [{options[option]}] and "
                f"[{raw}]; which one was meant is not a guess this script makes",
                status=EXIT_INVALID_ARG_VALUE,
            )
        if option in takes_value:
            if raw == "":
                fatal(
                    f"{script_name}: option [{option}] was given an empty value; if the current "
                    f"directory is meant, say so with [.]",
                    status=EXIT_INVALID_ARG_VALUE,
                )
            if raw.startswith("-"):
                fatal(
                    f"{script_name}: option [{option}] was given [{raw}], which is another option: "
                    f"its value was left out",
                    status=EXIT_INVALID_ARG_VALUE,
                )
        options[option] = raw
    return options
