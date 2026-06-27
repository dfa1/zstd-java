# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

## [Unreleased]

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
