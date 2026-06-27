# ADR 0002: Build the native library with `zig cc`

- **Status:** Completed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

The project bundles `libzstd` for six classifiers (`{osx,linux,windows}` ×
`{x86_64,aarch64}`). zstd is pure C with no build-system dependency. The build
must produce all six from CI without per-platform runners or system toolchains.

## Decision

Compile the zstd library sources directly with `zig cc`
(`scripts/build-zstd.sh`). Zig bundles clang + libc + headers for every target,
so any host cross-compiles any classifier hermetically. No zstd Makefile, no
CMake, no sysroot. The Maven `exec` plugin runs it in `generate-resources`;
it is idempotent (skips if the library exists).

## Consequences

### Positive
- One CI host (`ubuntu-latest`) builds all six classifiers — including the ones
  with no free CI runner (windows-aarch64, linux-aarch64).
- Hermetic and reproducible: no dependence on the host's installed toolchain.
- Compiles `.c` directly, bypassing zstd's own build entirely.

### Negative
- Adds a Zig toolchain dependency to the build.
- `zig cc` is a clang wrapper, not the zstd-blessed build path.

### Risks to manage
- Zig is pre-1.0; `zig cc` flag behaviour can shift between versions. The Zig
  version is **pinned** (0.16.0) in CI; upgrades require a re-test.

## Alternatives considered

- **GitHub matrix runners:** no free windows-aarch64 / fragmented, slow.
- **CMake/Make + cross sysroots:** per-target toolchain setup, the misery this
  decision exists to avoid.

## References

- [scripts/build-zstd.sh](../scripts/build-zstd.sh)
- [ADR 0006 — native compile flags](0006-native-compile-flags.md)
- [ADR 0014 — single-threaded native build](0014-single-threaded-native-build.md)
