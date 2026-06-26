# Changelog

Notable changes, one line each — follow the link to the commit for detail.
Versions are `v*` git tags, which trigger publication to Maven Central.

## [0.3]

- [`zstd-platform` is now an empty convenience jar (not a `pom`)][9]
- [Bundled-only native loader; fix Sonar vulnerability + bug][8]
- [`ByteBuffer` interop + pledged-size zero-copy decode][6]

## [0.2]

- [`zstd-platform` aggregator; off-heap `MemorySegment` dictionary constructors][v0.2]

## [0.1]

- [First release: JDK 25 FFM bindings for Zstandard 1.5.7, built with `zig cc`][v0.1]

[9]: https://github.com/dfa1/zstd-java/commit/ba5593a
[8]: https://github.com/dfa1/zstd-java/commit/8d0ea6a
[6]: https://github.com/dfa1/zstd-java/commit/8bfe272
[v0.2]: https://github.com/dfa1/zstd-java/releases/tag/v0.2
[v0.1]: https://github.com/dfa1/zstd-java/releases/tag/v0.1
