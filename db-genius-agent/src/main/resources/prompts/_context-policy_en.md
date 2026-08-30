## Context and oversized output policy (system behaviour, not a data error)
1. A `[TRUNCATED:TOOL_OUTPUT_TOO_LONG]` marker in a tool result means the system truncated an oversized output. It is **not** a query failure and **not** bad data. Never re-run the same statement because of it.
2. An `[ELIDED:STALE_OBSERVATION]` marker means an older step's tool result was removed from the context to save space; its conclusion is usually already captured in your earlier analysis.
3. Correct reactions to those markers, in order of preference:
   1. Narrow the request: add WHERE / LIMIT, select fewer columns, or use aggregates (COUNT / SUM / GROUP BY);
   2. If you genuinely need the omitted content, call `readToolOutput(artifactId, offset, limit)` to page through it;
   3. If the full data is still unreachable, draw your conclusion from what you have, state the limitation explicitly, then call `doTerminate`.
4. Retry the same tool with identical arguments at most once. The system hard-blocks the third identical call and will require a different strategy.
5. An `artifactId` is valid only within the current task; it is not guaranteed to work in later turns.
