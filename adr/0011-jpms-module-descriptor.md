# ADR 0011: JPMS module descriptor

- **Status:** Accepted — implemented on `feat/jpms-module-info`, not yet merged to main
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

The library targets JDK 25 and should be usable both on the classpath and as a
named module on the module path. FFM downcalls are a restricted operation that
JPMS gates per-module.

## Decision

Ship a `module-info.java` declaring `module io.github.dfa1.zstd`, exporting only
the single public API package. The native library is loaded from the separate
`zstd-native-<classifier>` artifact at runtime (ADR 0004). Callers grant
`--enable-native-access=io.github.dfa1.zstd` on the module path, or
`ALL-UNNAMED` on the classpath.

The module name's `dfa1` component ends in a digit, which the module-name lint
flags as version-like; it mirrors the Sonatype-verified `io.github.dfa1`
namespace and the package name, so the advisory is suppressed rather than
diverging from the established coordinates.

## Consequences

### Positive
- First-class module-path support with a minimal, single-package export surface.
- Native-access requirement is explicit and scoped to this module.

### Negative
- Consumers on the module path must pass the native-access flag.

### Risks to manage
- The dash-containing classifier resource directory is intentionally not a valid
  package, so the native resource is loadable across modules (ADR 0004).

## Alternatives considered

- **No `module-info` (automatic module):** unstable derived name, no explicit
  export control.
- **Rename to drop the trailing digit:** would diverge from the published Maven
  coordinates and package name.

## References

- [zstd/src/main/java/module-info.java](../zstd/src/main/java/module-info.java)
- [ADR 0004 — per-classifier native jars](0004-per-classifier-native-jars.md)
