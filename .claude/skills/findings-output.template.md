# Findings output template

The default output contract for every `code-review-*`, `design-review-*`, and
`impl-review-*` micro-skill.

**A micro-skill never restates this.** It states its review criteria, its
examples, and anything genuinely specific to its lens — then points here. The
contract is caller protocol, not lens knowledge: it is identical for
`no-sentinels` and `srp`, so a copy in each skill is one value with 39
dependents that drift apart silently. It has, twice.

**Precedence.** A caller's prompt may specify its own output instructions, and
those **win** — `code-review-chain` asks its leaves for a bare Verdict plus
Reasoning, `/manual-review-fix` asks for the blocks below. This file is the
fallback when a prompt says nothing, and what a standalone invocation gets.

## Template

````markdown
# Review Report: <lens name>

**Verdict:** 🟢 PASS / 🔴 FAIL / ⚪ N/A

## Findings

### <one-line title>

<20–40 word assessment — your case for why this matters, in your own voice.>

**RFC says** — <the verbatim excerpt> _(RFC-conformance lenses only; omit
otherwise)_

**Code** — `<file>:<line>`

```<lang>
<the offending lines, verbatim>
```

<one sentence on what is wrong>

**Options**

1. **(RECOMMENDED)** — <one-line reason>

   ```<lang>
   <the literal replacement>
   ```

2. <as above, 2..n in descending preference>
````

Emit `**Verdict:** 🟢 PASS` with no findings when the lens is satisfied, and
`⚪ N/A` when it does not apply to this change. Never stay silent — a lens that
returns nothing is indistinguishable from one that never ran.

## Field rules

- **Assessment** — the argument, not a restatement of the rule and not a
  description of the code. It leads because it is usually the only thing the
  reader needs, and it is the clearest evidence of whether the lens reasons
  well. Under 20 words has not made an argument; over 40 is the verbosity this
  format exists to prevent.
- **Code** — `file:line` always; a finding whose subject cannot be located is
  not actionable. **Put the offending lines in a fenced block, never inline in a
  sentence.** Prose studded with backticked fragments is unreadable at exactly
  the moment the reader is trying to see the code, and it loses the indentation
  and line structure that make the defect visible. Tag the fence with the
  language inferred from the file extension — `kotlin`, `sql`, `swift`, `bash`,
  `yaml`, `markdown` — so it actually highlights; an untagged fence renders as
  grey text. When a finding spans two sites, repeat the `file:line` + fence pair
  rather than merging them into one block.
- **Options** — at least two, each carrying the **actual code**, not a
  description of it, ordered by descending preference. Fence the replacement the
  same way whenever it is more than a short fragment: an option is something a
  fixer applies verbatim, so it has to be readable as code. **Option 1 is the
  recommendation**; there is no separate recommendation line, because one can
  name an option that does not exist and it lets the ranking of 2..n go
  unstated. Option 1 is what a fixer applies by default, so a lens that will not
  commit to a first choice has not finished its job.
- **Option code is production code.** An option is applied verbatim, so its code
  must already meet the standard the whole review suite enforces: author it
  under `/coding` and `/general-design`, and mirror the modeling conventions
  visible in the files under review. A lens is narrow in what it **flags**; it
  gets no narrowness exemption in what it **writes**. This does not widen any
  verdict: finding violations of other lenses stays out of scope — this rule
  governs only the code the lens authors itself.
