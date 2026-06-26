# Explanation

## Why FFM and Zig

The bindings use the **Foreign Function & Memory API** rather than JNI: no
hand-written C glue, no separate native compile step for the binding layer, and a
direct path from Java to zstd's addresses — which is what makes the zero-copy
`MemorySegment` API possible.

The native library itself is built from vendored zstd source via **`zig cc`** as
a drop-in C compiler. zstd is pure C with no build-system dependencies, so the
sources are compiled directly — no autotools, no CMake. Zig bundles clang and
libc for every target, enabling hermetic cross-compilation without a sysroot:
any host can build all six platform artifacts.

## When zero-copy pays off

The `MemorySegment` fast path eliminates the heap `byte[]` bounce and the
per-call allocation it implies. The reasoning, and the cases where it does and
does not matter, is in [zero-copy.md](zero-copy.md).

## Benchmarks

Throughput and allocation versus zstd-jni (JNI) and aircompressor (pure Java),
including an async-profiler breakdown: [benchmarks.md](benchmarks.md).
