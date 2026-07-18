# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

## [Unreleased]

### Added
- Native builds now decode legacy zstd frame formats v0.4-v0.7
  (`ZSTD_LEGACY_SUPPORT=4`, matching zstd-jni's default). v0.1-v0.3 stay
  unsupported — they predate zstd's 1.0 stabilization and are essentially
  never seen in practice. Verified against a real fixture of five concatenated
  legacy frames extracted from zstd's own test suite. ([#73](https://github.com/dfa1/zstd-java/pull/73))

### Changed
- `linux-x86_64`/`osx-x86_64`/`windows-x86_64` native builds now include
  zstd's hand-written BMI2 Huffman-decode assembly (previously disabled). It
  is a no-op on non-x86_64 targets and only activates via zstd's own runtime
  CPU detection; benchmarked as throughput-neutral on this project's
  synthetic workload but carries no measured downside either.
  ([#71](https://github.com/dfa1/zstd-java/pull/71))
- `aarch64` native builds now target an ARMv8-A + CRC baseline
  (`-mcpu=generic+crc`, zig's spelling of `-march=armv8-a+crc`), instead of
  the fully generic baseline. Measured +6.9% compress / +12-14% decompress
  throughput on Apple Silicon. ([#71](https://github.com/dfa1/zstd-java/pull/71))

### Security
- `linux-x86_64`/`linux-aarch64` native builds now link with full RELRO and
  immediate binding (`-Wl,-z,relro,-z,now`), closing off the classic
  GOT-overwrite exploit primitive. Verified with `llvm-readelf`.
  ([#71](https://github.com/dfa1/zstd-java/pull/71))

### Fixed
- Building the native library from source on Windows was silently broken:
  Maven's exec plugin tried to execute `build-zstd.sh` directly, which only
  works via a shebang on macOS/Linux. Windows builds now invoke it through
  `bash` explicitly. A second latent bug this surfaced — unrecognized/Windows
  host OS detection crashed the build script under `set -u` — is fixed
  alongside it. ([#75](https://github.com/dfa1/zstd-java/pull/75))

Investigated and **rejected** as part of the same effort (see
[#70](https://github.com/dfa1/zstd-java/issues/70) for full benchmark data):
LTO (real compress regression on x86_64, unsupported on macOS entirely — zig's
Mach-O linker has no LTO support) and an `x86-64-v3` baseline (mixed result,
hurts compress more than it helps decompress). Both would have traded away
this project's existing compress-side edge over zstd-jni for a smaller
decompress-side gain.

## [0.8] - 2026-07-12

### Added
- `module-info.java`: `zstd` now ships as a named JPMS module
  (`module io.github.dfa1.zstd`), exporting the single public API package.
  Module-path consumers grant `--enable-native-access=io.github.dfa1.zstd`
  instead of `ALL-UNNAMED`; classpath consumers are unaffected. See
  [ADR 0011](adr/0011-jpms-module-descriptor.md).

## [0.7] - 2026-06-28

### Changed
- **Breaking:** renamed public types to spell out abbreviations, matching the
  `Zstd<Compress|Decompress><Stream|Parameter>` family and zstd's own prose
  ("compression context", "dictionary"): `ZstdCompressCtx` → `ZstdCompressContext`,
  `ZstdDecompressCtx` → `ZstdDecompressContext`, `ZstdCompressDict` →
  `ZstdCompressDictionary`, `ZstdDecompressDict` → `ZstdDecompressDictionary`.

## [0.6] - 2026-06-27

### Added
- `ZstdCompressCtx.refPrefix(MemorySegment)` / `ZstdDecompressCtx.refPrefix(...)`
  — reference native content as a single-use prefix (raw-content dictionary) for
  the next frame only: the building block for delta compression (compress a new
  version against a similar previous one). The prefix is referenced, not copied
  or digested, and writes no dictionary ID; the decompressor must reference the
  same prefix to decode. Binds `ZSTD_CCtx_refPrefix` / `ZSTD_DCtx_refPrefix`.
  Segment-only by design — heap callers that need a copy should use
  `loadDictionary` instead.
- `Zstd.dictId(byte[])` / `Zstd.dictId(MemorySegment)` — read the dictionary id
  stamped in raw dictionary bytes without wrapping them in a `ZstdDictionary`.
  Binds `ZSTD_getDictID_fromDict`.
- `ZstdDictionaryId` value type — a `record` wrapping the 32-bit dictionary id
  with an unsigned `value()`, `isPresent()`, and the `NONE` sentinel for "no id".
- `ZstdFrame.decompressedSize(byte[])` / `ZstdFrame.decompressedSize(MemorySegment)`
  — the exact combined decompressed size of all concatenated frames, summed from
  each frame header (throws if any frame does not record its size). Complements
  `decompressedBound` (upper bound). Binds `ZSTD_findDecompressedSize`.
- `ZstdFrame.headerSize(byte[])` / `ZstdFrame.headerSize(MemorySegment)` — the size
  of a frame's header computed from just its leading bytes (as few as 5), without a
  full parse. Binds `ZSTD_frameHeaderSize`.
- `ZstdFrame.decompressionMargin(byte[])` / `ZstdFrame.decompressionMargin(MemorySegment)`
  — the extra room needed to decompress a frame **in place** (output buffer overlaps
  the compressed input at its tail), sized `decompressedSize + margin`. Binds
  `ZSTD_decompressionMargin`.
- `ZstdDictionary.compressDict(int)` / `compressDict()` / `decompressDict()` —
  factories for digested dictionaries, e.g. `dict.compressDict(19)` instead of
  `new ZstdCompressDict(dict, 19)`. They signal that the result is `AutoCloseable`
  and are for sharing one digest across contexts via `refDictionary`; a single
  context should prefer the context-owned `loadDictionary`.

### Changed
- Every dictionary-id accessor now returns `ZstdDictionaryId` instead of `int`:
  `ZstdDictionary.id()`, `ZstdCompressDict.id()`, `ZstdDecompressDict.id()`,
  `ZstdFrame.dictId(...)`, and `ZstdFrameHeader.dictId()`. The `0` sentinel is now
  `ZstdDictionaryId.NONE`, and the id reads as unsigned via `value()`.
- `Zstd.decompress(byte[])` now throws `ZstdException` (instead of letting a raw
  `ArithmeticException` escape) when a frame declares a content size larger than a
  Java array can hold. The size comes from the untrusted frame header; use
  `decompress(byte[], int)` to bound output for untrusted input.

## [0.5]

### Added
- `ZstdCompressCtx.reset(ZstdResetDirective)` / `ZstdDecompressCtx.reset(...)` —
  recycle a context's native state between frames without freeing and recreating
  it. `SESSION_ONLY` keeps the level, parameters, and dictionary; `PARAMETERS` /
  `SESSION_AND_PARAMETERS` restore the defaults. Binds `ZSTD_CCtx_reset` /
  `ZSTD_DCtx_reset`.
  ([3dfd5b8](https://github.com/dfa1/zstd-java/commit/3dfd5b8))
- `ZstdCompressCtx.loadDictionary(...)` / `ZstdDecompressCtx.loadDictionary(...)`
  (a `ZstdDictionary` or a native `MemorySegment`) and `refDictionary(...)` (a
  pre-digested `ZstdCompressDict` / `ZstdDecompressDict`, attached by reference,
  no copy). A sticky dictionary on the context lets compression combine a
  dictionary with the advanced parameters (checksum, window log, long-distance
  matching) — impossible through the per-call `compress(src, dict)` overloads,
  which route the legacy dictionary path. A parameter `reset(...)` clears it.
  Binds `ZSTD_CCtx_loadDictionary` / `ZSTD_DCtx_loadDictionary` (now on contexts,
  not just streams), `ZSTD_CCtx_refCDict`, `ZSTD_DCtx_refDDict`.
  ([3dfd5b8](https://github.com/dfa1/zstd-java/commit/3dfd5b8))

### Changed
- `NativeLibrary.classifier()` now throws a clear `UnsatisfiedLinkError` naming
  the unsupported CPU arch instead of silently mapping it to x86_64 (which
  deferred failure to a cryptic `dlopen` error). Added an explicit `amd64`
  branch so Linux JVMs (which report `os.arch=amd64`) still resolve x86_64.
  ([ea1ac84](https://github.com/dfa1/zstd-java/commit/ea1ac84))

### Fixed
- Native JARs are much smaller. The ELF shared library is now stripped at link
  time (`-s`), dropping debug info (`libzstd.so` 4.0M -> ~650K), and the
  multi-MB `.pdb` debug database and `.lib` import library that lld emits next
  to the Windows `.dll` are no longer bundled (neither is needed at runtime).
  Net: linux-x86_64 native jar 1.2M -> 285K, windows-x86_64 1.2M -> 372K.
  ([ea1ac84](https://github.com/dfa1/zstd-java/commit/ea1ac84))

## [0.4]

### Added
- `Zstd.versionNumber()` — the linked zstd version as a single integer
  (`MAJOR * 10000 + MINOR * 100 + PATCH`, e.g. `10507` for `1.5.7`), for
  programmatic version checks alongside `version()`.

### Changed
- `ZstdSkippableContent` is now a true immutable value: it defensively copies its
  bytes on the way in and out, and compares by content
  (`equals` / `hashCode` / `toString` over the payload, not array identity).
- Public methods fail fast with a named `NullPointerException` on null `byte[]`,
  dictionary, or sample arguments, instead of an opaque failure deep in native
  code. Streams are documented as not thread-safe; digested dictionaries
  (`ZstdCompressDict` / `ZstdDecompressDict`) as immutable and safe to share.

### Fixed
- A streaming wrapper that failed partway through construction (e.g. an invalid
  parameter or dictionary) leaked the native context. The context is now freed
  on every constructor error path.

### Security
- The bundled library is extracted into a directory created owner-only
  (`rwx------`) atomically at creation, not just by default. The third-party
  `setup-zig` CI action is pinned to a full commit SHA.

## [0.3]

- [`zstd-platform` is now an empty convenience jar (not a `pom`)](https://github.com/dfa1/zstd-java/commit/ba5593a)
- [Bundled-only native loader; fix Sonar vulnerability + bug](https://github.com/dfa1/zstd-java/commit/8d0ea6a)
- [`ByteBuffer` interop + pledged-size zero-copy decode](https://github.com/dfa1/zstd-java/commit/8bfe272)

## [0.2]

- [`zstd-platform` aggregator; off-heap `MemorySegment` dictionary constructors](https://github.com/dfa1/zstd-java/releases/tag/v0.2)

## [0.1]

- [First release: JDK 25 FFM bindings for Zstandard 1.5.7, built with `zig cc`](https://github.com/dfa1/zstd-java/releases/tag/v0.1)
