# Reference

## Supported platforms

The library — `io.github.dfa1.zstd:zstd` — ships as a pure-Java module plus one
native artifact per platform:

| OS      | aarch64 | x86_64 |
|---------|:-------:|:------:|
| macOS   |   ✅    |   ✅   |
| Linux   |   ✅    |   ✅   |
| Windows |   ✅    |   ✅   |

## API surface

| Type | Role |
|---|---|
| `Zstd` | one-shot `compress` / `decompress`, level + version queries, `compressBound`, `decompressedSize` |
| `ZstdCompressCtx` / `ZstdDecompressCtx` | reusable contexts; `byte[]` and `MemorySegment` overloads, dictionary variants |
| `ZstdDictionary` | train (`ZDICT`), load, persist, query dict id |
| `ZstdCompressDict` / `ZstdDecompressDict` | pre-digested dictionaries for hot paths |
| `ZstdFrame` | frame inspection: header, sizes, dict id, skippable frames |
| `ZstdException` / `ZstdErrorCode` | typed errors mapped from zstd's sentinels |

## Symbol coverage

Which zstd C symbols are bound (and which deprecated ones are intentionally not),
with a per-area breakdown and a comparison against zstd-jni:
[supported.md](supported.md).

## Runtime requirement

Native access requires `--enable-native-access=ALL-UNNAMED` (or your module name)
on the JVM command line.

## Build from source

Requires JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH`.

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java
mvn test
```

`scripts/build-zstd.sh` compiles `libzstd.{dylib,so,dll}` from the
`third_party/zstd` submodule (pinned to tag `v1.5.7`) with `zig cc`, cross-compiling
any of the six targets from any host.
