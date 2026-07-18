# ADR 0014: Single-threaded native build (no `nbWorkers`)

- **Status:** Superseded
- **Date:** 2026-06-27
- **Deciders:** project maintainer
- **Superseded by:** [ADR 0015](0015-enable-zstd-multithread.md)

## Context

zstd can compress with background worker threads (`ZSTD_c_nbWorkers > 0`) when
built with `ZSTD_MULTITHREAD`. That speeds large-payload compression — but it
requires a threading library: pthread on POSIX, winpthreads on MinGW. Bundling
those into the `zig cc` hermetic cross-build (ADR 0002), especially for
`windows-gnu`, undermines the "any host builds any target with no system
toolchain" property.

## Decision

Build with `ZSTD_MULTITHREAD` **off**. The library is single-threaded;
`ZSTD_c_nbWorkers` is a no-op. Parallelism is a **Java-side** concern:
concurrent contexts across (virtual) threads (ADR 0010), or application-level
frame splitting — not native worker threads.

## Consequences

### Positive
- Hermetic, dependency-free cross-compilation holds for all six classifiers.
- No native thread-pool lifecycle inside the library; deterministic.

### Negative
- No native multithreaded compression. `zstd-jni` ships MT-enabled and will beat
  us on large-payload compress with workers. This is a deliberate trade.

### Risks to manage
- A contributor will ask "why is my `nbWorkers` ignored?" — this ADR is the
  answer.
- If a real workload needs native MT, revisiting means solving the
  pthread/winpthreads cross-build — recorded here so it is not rediscovered
  cold.

## Alternatives considered

- **Enable MT + bundle pthread/winpthreads:** breaks hermetic cross-compile.
- **App-level frame parallelism (split → compress concurrently → concat):**
  viable; pushed to the caller / a future helper.

## References

- [scripts/build-zstd.sh](../scripts/build-zstd.sh)
- [ADR 0002 — zig cc native build](0002-zig-cc-native-build.md)
- [ADR 0010 — native-context pool](0010-native-context-pool.md)
