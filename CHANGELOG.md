# Changelog

Notable changes, one line each — follow the link to the commit for detail.
Versions are `v*` git tags, which trigger publication to Maven Central.

## [0.4]

- [Add `Zstd.versionNumber()` for programmatic version checks][16]
- [Fix a native context leak when a stream constructor fails mid-setup][13]
- [`ZstdSkippableContent` is a defensively-copied, value-equal record][21]
- [Fail fast with a named `NullPointerException` on null arguments; document stream / digested-dictionary thread-safety][22]
- [Harden the native loader (owner-only temp dir) and pin CI actions to a commit SHA][20]

## [0.3]

- [`zstd-platform` is now an empty convenience jar (not a `pom`)][9]
- [Bundled-only native loader; fix Sonar vulnerability + bug][8]
- [`ByteBuffer` interop + pledged-size zero-copy decode][6]

## [0.2]

- [`zstd-platform` aggregator; off-heap `MemorySegment` dictionary constructors][v0.2]

## [0.1]

- [First release: JDK 25 FFM bindings for Zstandard 1.5.7, built with `zig cc`][v0.1]

[16]: https://github.com/dfa1/zstd-java/commit/e6a3537
[13]: https://github.com/dfa1/zstd-java/commit/49e01b4
[21]: https://github.com/dfa1/zstd-java/commit/a5f4aa0
[22]: https://github.com/dfa1/zstd-java/commit/7dbad8e
[20]: https://github.com/dfa1/zstd-java/commit/c9929b9
[9]: https://github.com/dfa1/zstd-java/commit/ba5593a
[8]: https://github.com/dfa1/zstd-java/commit/8d0ea6a
[6]: https://github.com/dfa1/zstd-java/commit/8bfe272
[v0.2]: https://github.com/dfa1/zstd-java/releases/tag/v0.2
[v0.1]: https://github.com/dfa1/zstd-java/releases/tag/v0.1
