# ADR 0013: Binding coverage scope — exclude legacy/deprecated/experimental

- **Status:** Accepted
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

Also **exclude the experimental tier** — symbols declared `ZSTDLIB_STATIC_API`.
These are exported by the build (the macro resolves to `visibility("default")`,
so the FFM layer could bind them by name), but they carry no API-stability
guarantee and may change or be removed between zstd releases. Notably this
covers the `ZSTD_CCtx_params` bundle (`ZSTD_createCCtxParams`,
`ZSTD_CCtxParams_setParameter`, `ZSTD_CCtx_setParametersUsingCCtxParams`, …):
it only saves a few `setParameter` calls at context init — never on a hot path —
so the experimental-API risk is not worth the marginal value. The stable,
already-bound per-context `ZSTD_CCtx_setParameter` path covers the same need.

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

- [docs/supported.md](../docs/supported.md),
  [Bindings.java](../zstd/src/main/java/io/github/dfa1/zstd/Bindings.java)
- [scripts/build-zstd.sh](../scripts/build-zstd.sh)
