# ADR 0007: Integration tests as ground truth

- **Status:** Completed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

There is no machine-checkable formal spec for the zstd frame format that a
binding can assert against. Unit tests prove internal consistency but not that
output is *correct zstd* that other tools accept.

## Decision

Treat integration tests as ground truth, on two pillars:

1. **Interop with `zstd-jni`** (luben — the zstd C library via JNI): every
   encoding round-trip is cross-checked — what we compress, JNI decompresses,
   and vice versa.
2. **The vendored golden corpus** under `third_party/zstd/tests/`
   (`golden-compression`, `golden-decompression`,
   `golden-decompression-errors`, `golden-dictionaries`) — the canonical,
   version-matched fixtures the C project itself ships.

Unit tests stay fast and pure (no I/O, network, sleep). Every encoding
round-trip and file-format boundary gets an integration test.

## Consequences

### Positive
- Correctness defined against an independent implementation and the upstream
  corpus, not our own assumptions.
- Error-case fixtures pin our handling of malformed frames.

### Negative
- Integration tests need the git submodule and the `zstd-jni` dependency.
- Slower than the unit suite; kept in a separate module.

### Risks to manage
- Corpus drifts with the submodule version; pinned via the submodule SHA.

## Alternatives considered

- **Unit tests only:** cannot prove cross-tool correctness.
- **Round-trip against ourselves only:** a self-consistent bug stays invisible.

## References

- [integration-tests/](../../integration-tests)
- [third_party/zstd/tests/](../../third_party/zstd/tests)
