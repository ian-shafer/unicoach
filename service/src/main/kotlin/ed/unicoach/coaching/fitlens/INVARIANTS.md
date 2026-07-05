# Invariants — `coaching/fitlens`

fit-lens (RFC 98): a per-student between-sessions pass that reaches into the
college dataset and proposes one real, novel school with a grounded rationale.

## Novelty is re-verified at write time, never trusted to the prompt

**Rule:** fit-lens MUST re-verify a proposed college's novelty at write time,
under the student advisory lock, against the student's structured college ids —
active `college_list` entries and prior `fit_suggestions`. The LLM exclusion set
is steering only, never the novelty guarantee.

**Why:** the LLM is non-deterministic and will periodically re-name a school the
student already knows; trusting its exclusion-set compliance re-suggests known
schools and erodes trust in the feature. The `UNIQUE(student_id, college_id)`
constraint is the backstop for prior suggestions, but the `college_list`
dimension is cross-table and only the write-time recheck covers it.
