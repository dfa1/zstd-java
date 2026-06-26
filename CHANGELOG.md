# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

## [0.1]

First release. Java 25 Foreign Function & Memory (FFM) bindings for
[Zstandard](https://github.com/facebook/zstd) 1.5.7, built hermetically from
vendored source with `zig cc` (no JNI, no prebuilt binaries). 68 of the public
zstd symbols are bound; see `docs/supported.md`.

### Added
- One-shot compression/decompression over `byte[]` and zero-copy `MemorySegment`
  (`Zstd`, `ZstdCompressCtx`, `ZstdDecompressCtx`).
- Dictionaries: training (`ZDICT_trainFromBuffer`, COVER / fast-COVER optimisers,
  `finalizeDictionary`), digested `ZstdCompressDict` / `ZstdDecompressDict`,
  dictionary ids and header size.
- Streaming: `ZstdOutputStream` / `ZstdInputStream` (java.io) and a zero-copy
  `MemorySegment` driver (`ZstdCompressStream` / `ZstdDecompressStream`), with
  dictionaries, `pledgedSrcSize`, and live `progress()`.
- All advanced parameters (`ZstdCompressParameter` / `ZstdDecompressParameter`)
  with bounds queries; checksum, long-distance matching, window log, etc.
- Frame inspection (`ZstdFrame`): header, content/compressed size, dictionary id,
  skippable frames.
- Typed errors (`ZstdException.code()` / `ZstdErrorCode`) and memory accounting
  (`sizeOf()`, `Zstd.estimate*Size`).
- Native artifacts for macOS, Linux and Windows on x86_64 and aarch64,
  cross-compiled from a single host with `zig cc`.
- Format-compatibility tests against the reference zstd-jni binding.

[0.1]: https://github.com/dfa1/zstd-java/releases/tag/v0.1
