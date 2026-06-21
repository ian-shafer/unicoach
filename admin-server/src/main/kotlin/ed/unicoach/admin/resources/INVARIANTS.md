# INVARIANTS — admin-server/.../admin/resources

The concrete admin descriptors (`UsersResource`, `StudentsResource`,
`SessionsResource`, `SystemPromptsResource`) and the shared `occSoftDelete`
write-path helper ([OccDelete.kt](./OccDelete.kt)). Each descriptor marshals an
untyped HTML form into validated DAO input; the engine owns routing/rendering.

## Invariants

### `system_prompts` create passes `body` verbatim

**Rule:** `SystemPromptsResource`'s create handler MUST pass the submitted
`body` to `NewSystemPrompt` **byte-for-byte** — it MUST NOT `trim`, normalize
newlines, or otherwise canonicalize it. It MAY (and does) `trim` `name` and
`version`, which are identifiers.

**Why:** `body` is the exact text sent to the LLM, and the `system_prompts`
table deliberately exempts it from a `*_trimmed_check` while keeping no
raw-payload backup (schema 0007, D-5). The DB guarantees only non-empty-after-
trim and a size bound — it has no guard against over-trimming. Trailing
whitespace/newlines can be intentional, so any canonicalization here silently
alters what reaches the model with no surviving original — a correctness
corruption that neither a constraint nor the `name`/`version` trimmed-column
tests would catch.

## History

- [x] [RFC-63: Admin System Prompts](../../../../../../../../rfc/63-admin-system-prompts.md)
