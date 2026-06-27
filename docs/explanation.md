# Explanation

## Why FFM and Zig

The bindings use the **Foreign Function & Memory API** rather than JNI: no
hand-written C glue, no separate native compile step for the binding layer, and a
direct path from Java to zstd's addresses — which is what makes the zero-copy
`MemorySegment` API possible. See
[ADR 0001 — FFM over JNI](../adr/0001-ffm-over-jni.md).

The native library itself is built from vendored zstd source via **`zig cc`** as
a drop-in C compiler. zstd is pure C with no build-system dependencies, so the
sources are compiled directly — no autotools, no CMake. Zig bundles clang and
libc for every target, enabling hermetic cross-compilation without a sysroot:
any host can build all six platform artifacts. See
[ADR 0002 — zig cc native build](../adr/0002-zig-cc-native-build.md).

## When zero-copy pays off

The `MemorySegment` fast path eliminates the heap `byte[]` bounce and the
per-call allocation it implies. The reasoning, and the cases where it does and
does not matter, is in [zero-copy.md](zero-copy.md).

## Benchmarks

Throughput and allocation versus zstd-jni (JNI) and aircompressor (pure Java),
including an async-profiler breakdown: [benchmarks.md](benchmarks.md).

## Architecture decisions

The reasoning above is distilled from the full set of Architecture Decision
Records — every foundational choice, its alternatives, and its trade-offs, one
file per decision: [adr/ADR.md](../adr/ADR.md).
