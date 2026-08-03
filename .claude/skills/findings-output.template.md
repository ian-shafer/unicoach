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

```markdown
# Review Report: <lens name>

**Verdict:** 🟢 PASS / 🔴 FAIL / ⚪ N/A

## Findings

- **<one-line title>**
  - **Assessment**: 20–40 words — your case for why this matters, in your own
    voice.
  - **RFC says**: the verbatim excerpt _(RFC-conformance lenses only; omit
    otherwise)_.
  - **Code**: `<file>:<line>` — the offending lines, one sentence on what is
    wrong.
  - **Options**:
    1. **(RECOMMENDED)** the literal change to apply — one-line reason.
    2. the literal change. _(2..n in descending preference)_
```

Emit `**Verdict:** 🟢 PASS` with no findings when the lens is satisfied, and
`⚪ N/A` when it does not apply to this change. Never stay silent — a lens that
returns nothing is indistinguishable from one that never ran.

## Field rules

- **Assessment** — the argument, not a restatement of the rule and not a
  description of the code. It leads because it is usually the only thing the
  reader needs, and it is the clearest evidence of whether the lens reasons
  well. Under 20 words has not made an argument; over 40 is the verbosity this
  format exists to prevent.
- **Code** — `file:line` always. A finding whose subject cannot be located is
  not actionable.
- **Options** — at least two, each carrying the **actual code**, not a
  description of it, ordered by descending preference. **Option 1 is the
  recommendation**; there is no separate recommendation line, because one can
  name an option that does not exist and it lets the ranking of 2..n go
  unstated. Option 1 is what a fixer applies by default, so a lens that will not
  commit to a first choice has not finished its job.
