# ADR 0013: Binding coverage scope — exclude legacy/deprecated

- **Status:** Completed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

zstd's public API is large and includes deprecated functions and a `legacy/`
decoder for pre-1.0 frame formats. Binding everything inflates surface, test
burden, and the native library size for little value.

## Decision

Bind the current, supported API: core compress/decompress, the advanced
context-parameter API, streaming, dictionaries (raw, digested, and ZDICT
training), and frame/skippable-frame introspection. **Exclude** deprecated
functions and the `legacy/` and `deprecated/` source trees (the native build
compiles only `common`, `compress`, `decompress`, `dictBuilder`).

The full coverage map — every public symbol, what is bound, and what is
intentionally omitted — is maintained in `docs/supported.md`.

## Consequences

### Positive
- Smaller, current API surface; smaller native library.
- One document (`supported.md`) answers "is X bound?".

### Negative
- Cannot decode pre-1.0 legacy frames (effectively extinct).
- Deprecated convenience functions are unavailable; the supported replacements
  are bound.

### Risks to manage
- If a real need for a legacy/deprecated symbol appears, revisit by adding the
  source dir to the build and the symbol to `Bindings` — recorded so it is not
  rediscovered cold.

## Alternatives considered

- **Bind everything incl. legacy:** larger surface and binary, maintaining dead
  paths.
- **Minimal core only:** would drop dictionaries — the project's reason to
  exist.

## References

- [docs/supported.md](../supported.md), [Bindings.java]
- [scripts/build-zstd.sh](../../scripts/build-zstd.sh)
