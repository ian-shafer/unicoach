# `prompts/`

Authored model copy. One file per prompt body; the body IS the file, verbatim.

## Two homes, and the rule that divides them

A coach instruction has exactly ONE home (RFC 192, product slice
`concise/03/one-rule-one-home`):

- **This directory is the single home for BEHAVIOUR** — anything that tells the
  model what to say, what never to say, what order to say it in, or which
  sentence to speak verbatim.
- **A tool's `DESCRIPTION` is a schema legend** — what each wire key means and
  what values it takes, and at most how to READ a key (which key a total already
  includes; that an entry's own value wins over the default). Never what to say
  to the family.

Both artifacts are sent to the model in the SAME context window. Before RFC 192
the `college_cost_profile` description was 18,580 bytes on that slice's base,
`main@d2fc8bdf` — largely a second copy of this prompt's rules, so the model was
told the same thing twice and the two copies drifted. (The RFC's own inventory
measured it at 18,386 bytes on the earlier `main@493dd34f`; RFC 191 appended a
194-byte tier-grouping sentence to the description in between.) The fix is not tidiness: a rule with two homes gets edited in one
of them.

**So when you add a behavioural rule, add it here — not to a tool description.**
If the rule is about reading a key, it belongs to the legend. If it is about what
the family hears, it belongs in this directory.

## Authoring rules

- **One sentence per line.** That line granularity is why two runs editing
  different paragraphs merge without a conflict.
- `prompts/` is excluded from `deno fmt` in `deno.json` — a reflow would silently
  rewrite the prompt. Keep it excluded.
- Markdown works and a line break is body text. A tab and an interior double
  space are ordinary text.
- **Never reword a paragraph's FIRST sentence.** `SystemPromptCatalogTest` locates
  every paragraph by its opening words, and a reworded opener silently widens
  another paragraph's scoped assertions instead of failing.
- Nine sentences are BYTE-FROZEN against the immutable v19 catalog row (six
  `FigureStatusCopy`, three `AssuranceTierCopy`). Compress by deleting whole
  redundant sentences, never by rewriting one.
- The seed migration under `db/schema/` is a GENERATED artifact. Edit the file
  here and let `bin/prompt-seed` regenerate at land; a hand-written seed is
  overwritten and a hand-edited one is refused (RFC 181, RFC 185). Do not touch
  the `systemPromptVersion` pin — `bin/prompt-seed` owns it, because the version
  label is only knowable at land.
