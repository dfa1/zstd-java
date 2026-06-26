# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

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
