# ADR 0010: Bounded native-context pool for virtual threads

- **Status:** Proposed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

`ZstdCompressContext`/`ZstdDecompressContext` wrap native CCtx/DCtx — expensive to
create (off-heap malloc + init) and **not thread-safe**. The library targets
JDK 25, where virtual threads are the default concurrency model: many, cheap,
short-lived. The question is how to reuse contexts across them.

`ThreadLocal<ZstdCompressContext>` is the wrong answer under virtual threads: one
context per vthread means millions of native contexts (native-memory
explosion), and short vthread lifetimes mean the cached context is never
reused. Java's own guidance: **pool the scarce resource, not the thread.**

## Decision

*(Proposed — not yet implemented.)* Introduce a **bounded checkout pool** of
bare native contexts, decoupling `#contexts` from `#threads`:

- **Exclusive checkout** — a borrowed CCtx is used by one thread at a time
  (CCtx is not thread-safe).
- **Reset on return** — `ZSTD_CCtx_reset(session_and_parameters)` between
  borrowers so level/pledged-size/dict do not leak across calls.
- **Bounded + blocking** — semaphore-gated; the cap is a native-memory budget,
  not a thread count. Excess vthreads block (backpressure) instead of OOM.
- **Leak-proof borrow** — a `withContext(...)` form that always returns the
  context, even on exception.
- **`NB_WORKERS` banned** *(amendment, [ADR 0015](0015-enable-zstd-multithread.md))* —
  pooled contexts must reject `NB_WORKERS > 0` (or be closed instead of
  recycled once it was set). `ZSTD_CCtx_reset` never frees the lazily-created
  worker-thread pool (`cctx->mtctx`) — only `ZSTD_freeCCtx` does — so one
  borrower enabling workers would permanently attach N live OS threads and
  tens of MB of job buffers to that pool slot, silently inherited by every
  future borrower. Multithreaded compression requires a dedicated,
  caller-owned, unpooled context.

Dictionaries are **not** pooled: a `const` CDict/DDict is thread-safe for
concurrent use, so it is a shared singleton. The efficient composition is
*shared CDict (unpooled) + pooled bare CCtx + `ZSTD_CCtx_refCDict` per borrow* —
a cheap reference, not a re-digest, and dict-agnostic pooling.

Output `byte[]` is **not** pooled: it escapes to the caller and cannot be
safely reclaimed. The zero-copy segment API (ADR 0003) is the allocation answer.

## Consequences

### Positive
- Native-memory use is capped regardless of vthread count.
- Reuses expensive contexts without per-thread leakage.

### Negative
- Adds concurrency surface (checkout/return, reset correctness).
- Caller must reason about pool sizing vs throughput.

### Risks to manage
- Borrow-without-return leaks native handles — mitigated by `withContext`.
- Reset omission leaks state across borrowers.

## Alternatives considered

- **`ThreadLocal` cache:** breaks under virtual threads (see Context).
- **No pooling, caller-managed reuse:** fine for platform threads, insufficient
  for vthread workloads.
- **Pool everything (byte[], dicts):** byte[] escapes; dicts are already
  concurrent-safe — both rejected.

## References

- [ADR 0003 — segment-first API](0003-segment-first-api.md)
- [ADR 0009 — NativeObject idempotent close](0009-nativeobject-idempotent-close.md)
