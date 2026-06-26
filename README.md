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

The native library is built from vendored zstd source via **`zig cc`** as a
drop-in C compiler. zstd is pure C with no build-system dependencies, so the
sources are compiled directly — no autotools, no CMake. Zig bundles clang and
libc for every target, enabling hermetic cross-compilation without a sysroot.

## Supported platforms

The library — `io.github.dfa1:zstd-java` — ships as a pure-Java module plus one
native artifact per platform:

| OS      | aarch64 | x86_64 |
|---------|:-------:|:------:|
| macOS   |   ✅    |   ✅   |
| Linux   |   ✅    |   ✅   |
| Windows |   ✅    |   ✅   |

## Usage

### One-shot

```java
byte[] packed   = Zstd.compress("hello world".getBytes());
byte[] restored = Zstd.decompress(packed);          // size read from frame header

byte[] hard     = Zstd.compress(data, Zstd.maxCompressionLevel());
```

### Reusable contexts (hot paths)

Reuse a context to amortise native allocation across many calls:

```java
try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19);
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(message);
    byte[] restored = dctx.decompress(packed, message.length);
}
```

### Dictionaries

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

### Zero-copy (`MemorySegment`)

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
dictionary variants) returning the number of bytes written. See
[docs/zero-copy.md](docs/zero-copy.md) for why and when this pays off.

> Native access requires `--enable-native-access=ALL-UNNAMED` (or your module) on
> the JVM command line.

## Building

Requires JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH`.

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java
mvn test
```

The `mvn` build invokes `scripts/build-zstd.sh`, which compiles
`libzstd.{dylib,so,dll}` from the `third_party/zstd` submodule with `zig cc`. The script
cross-compiles any of the six targets from any host:

```bash
./scripts/build-zstd.sh <output-resources-dir> <classifier>
# classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#           | windows-x86_64 | windows-aarch64
```

To run against a self-built library instead of the bundled one:

```bash
-Dzstd.lib.path=/path/to/libzstd.dylib
```

## License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).
