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
import os
import sys

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
    comes through the script's own channel."""
    staged = []
    try:
        for target, body in files:
            partial = target.with_name(f".{target.name}.partial")
            partial.write_bytes(body)
            staged.append((partial, target))
        for partial, target in staged:
            os.replace(partial, target)
    except OSError as error:
        for partial, _ in staged:
            with contextlib.suppress(OSError):
                partial.unlink()
        fatal(
            f"{script_name}: writing [{len(files)}] files failed: [{type(error).__name__}] [{error}]. "
            f"Nothing partial was left behind; the previous contents of the directory "
            f"stand as they were."
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
