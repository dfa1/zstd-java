# zstd-java

[![CI](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml)
![zstd](https://img.shields.io/badge/zstd-1.5.7-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)

**zstd-java** is a Java wrapper for [Zstandard](https://github.com/facebook/zstd)
built on the **Foreign Function & Memory (FFM) API** — no JNI, no hand-written C.
It targets **JDK 25+** (for stable `java.lang.foreign`) and leads with the
feature missing from most JVM zstd bindings: **dictionary compression**, trained
straight from your own data.

> **AI-assisted development:** This project uses Claude Code for implementation —
> C header mapping, test generation, docs. Architecture, API design, and all
> decisions are human-driven.

## Documentation

The docs follow the [Diátaxis](https://diataxis.fr) framework — four kinds of
documentation, each serving a different need:

| | Purpose | Start here |
|---|---|---|
| **[Tutorial](docs/tutorial.md)** | Learning by doing | [Getting started](docs/tutorial.md) |
| **[How-to guides](#how-to-guides)** | Solving a specific task | [Hot paths](#compress-on-a-hot-path), [Dictionaries](#compress-many-small-payloads-with-a-dictionary), [Zero-copy](#avoid-heap-copies-with-memorysegment), [Self-built lib](#run-against-a-self-built-libzstd) |
| **[Reference](#reference)** | Looking up facts | [Platforms](#supported-platforms), [API surface](#api-surface), [Symbol coverage](docs/supported.md), [Build](#build-from-source) |
| **[Explanation](#explanation)** | Understanding the why | [Why FFM + Zig](#why-ffm-and-zig), [When zero-copy pays](docs/zero-copy.md), [Benchmarks](docs/benchmarks.md) |

---

## Tutorial: Getting started

New here? **[docs/tutorial.md](docs/tutorial.md)** takes you from a clean checkout
to your first compress/decompress round-trip, step by step.

## How-to guides

Task-focused recipes. Each assumes you have the library on the classpath (see the
[tutorial](#tutorial-getting-started)).

### Compress on a hot path

Reuse a context to amortise native allocation across many calls:

```java
try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19);
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(message);
    byte[] restored = dctx.decompress(packed, message.length);
}
```

Pick the level explicitly with `Zstd.maxCompressionLevel()` /
`minCompressionLevel()` when you need the extreme ends.

### Compress many small payloads with a dictionary

For many small, similar payloads (log lines, JSON records, protobufs), a
dictionary compresses each one far smaller than it could be alone. Train one on
representative samples:

```java
ZstdDictionary dict = ZstdDictionary.train(sampleRecords, 16 * 1024);

try (ZstdCompressCtx cctx = new ZstdCompressCtx();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(record, dict);
    byte[] restored = dctx.decompress(packed, record.length, dict);
}

byte[] persisted = dict.toByteArray();               // store / ship the dictionary
ZstdDictionary reloaded = ZstdDictionary.of(persisted);
```

On a hot path, digest the dictionary once to skip per-call setup:

```java
try (ZstdCompressDict cdict = new ZstdCompressDict(dict, 19);
     ZstdDecompressDict ddict = new ZstdDecompressDict(dict);
     ZstdCompressCtx cctx = new ZstdCompressCtx();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(record, cdict);
    byte[] restored = dctx.decompress(packed, record.length, ddict);
}
```

### Avoid heap copies with `MemorySegment`

When your data is already off-heap — an `mmap` slice in, an arena buffer out —
use the `MemorySegment` overloads to skip the heap `byte[]` bounce entirely. FFM
hands zstd the segment address directly: no copy in, no copy out, no GC churn.

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    MemorySegment frame = reader.mmapSlice();           // already native
    long n = Zstd.decompressedSize(frame);              // read header, no copy
    MemorySegment out = arena.allocate(n);              // becomes the backing buffer
    dctx.decompress(out, frame);                        // native → native
}
```

There are matching `compress(dst, src)` / `decompress(dst, src)` overloads (plus
dictionary variants) returning the number of bytes written. For *why and when*
this pays off, see the [explanation](docs/zero-copy.md).

### Run against a self-built libzstd

To use a `libzstd` you built yourself instead of the bundled one, point the
loader at it:

```bash
java -Dzstd.lib.path=/path/to/libzstd.dylib --enable-native-access=ALL-UNNAMED ...
```

Build any of the six targets from any host:

```bash
./scripts/build-zstd.sh <output-resources-dir> <classifier>
# classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#           | windows-x86_64 | windows-aarch64
```

## Reference

### Supported platforms

The library — `io.github.dfa1.zstd:zstd` — ships as a pure-Java module plus one
native artifact per platform:

| OS      | aarch64 | x86_64 |
|---------|:-------:|:------:|
| macOS   |   ✅    |   ✅   |
| Linux   |   ✅    |   ✅   |
| Windows |   ✅    |   ✅   |

### API surface

| Type | Role |
|---|---|
| `Zstd` | one-shot `compress` / `decompress`, level + version queries, `compressBound`, `decompressedSize` |
| `ZstdCompressCtx` / `ZstdDecompressCtx` | reusable contexts; `byte[]` and `MemorySegment` overloads, dictionary variants |
| `ZstdDictionary` | train (`ZDICT`), load, persist, query dict id |
| `ZstdCompressDict` / `ZstdDecompressDict` | pre-digested dictionaries for hot paths |
| `ZstdFrame` | frame inspection: header, sizes, dict id, skippable frames |
| `ZstdException` / `ZstdErrorCode` | typed errors mapped from zstd's sentinels |

### Symbol coverage

Which zstd C symbols are bound (and which deprecated ones are intentionally not),
with a per-area breakdown and a comparison against zstd-jni:
[docs/supported.md](docs/supported.md).

### Runtime requirement

Native access requires `--enable-native-access=ALL-UNNAMED` (or your module name)
on the JVM command line.

### Build from source

Requires JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH`.

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java
mvn test
```

`scripts/build-zstd.sh` compiles `libzstd.{dylib,so,dll}` from the
`third_party/zstd` submodule (pinned to tag `v1.5.7`) with `zig cc`, cross-compiling
any of the six targets from any host.

### License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).

## Explanation

### Why FFM and Zig

The bindings use the **Foreign Function & Memory API** rather than JNI: no
hand-written C glue, no separate native compile step for the binding layer, and a
direct path from Java to zstd's addresses — which is what makes the zero-copy
`MemorySegment` API possible.

The native library itself is built from vendored zstd source via **`zig cc`** as
a drop-in C compiler. zstd is pure C with no build-system dependencies, so the
sources are compiled directly — no autotools, no CMake. Zig bundles clang and
libc for every target, enabling hermetic cross-compilation without a sysroot:
any host can build all six platform artifacts.

### When zero-copy pays off

The `MemorySegment` fast path eliminates the heap `byte[]` bounce and the
per-call allocation it implies. The reasoning, and the cases where it does and
does not matter, is in [docs/zero-copy.md](docs/zero-copy.md).

### Benchmarks

Throughput and allocation versus zstd-jni (JNI) and aircompressor (pure Java),
including an async-profiler breakdown: [docs/benchmarks.md](docs/benchmarks.md).
