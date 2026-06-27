# ADR 0006: Native compile flags

- **Status:** Completed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

Compiling zstd directly (ADR 0002) means owning the compile/link flags that the
zstd build system would otherwise set. The flags must produce identical codegen
across six targets and keep the exported symbol surface minimal.

## Decision

Compile with:

- `-O3 -DNDEBUG` — release codegen.
- `-DZSTD_DISABLE_ASM=1` — drop the x86-only `.S` files, giving identical C
  codegen on every target.
- `-DXXH_NAMESPACE=ZSTD_` — matches zstd's own build, avoids xxhash symbol
  clashes.
- `-fPIC`.

Visibility differs by platform:

- **ELF / Mach-O:** `-fvisibility=hidden`; zstd's `ZSTDLIB_VISIBLE` keeps only
  the public API exported.
- **Windows / MinGW:** *no* hidden visibility (it suppresses PE exports);
  instead `-Wl,--export-all-symbols` lets lld populate the PE export table.

Symbol/debug tables are stripped at link (`-s` on ELF); the lld-emitted `.pdb`
and import `.lib` on Windows are deleted after link so they are not bundled.

## Consequences

### Positive
- Deterministic, minimal-surface libraries; stripped ELF is ~6x smaller.

### Negative
- `DISABLE_ASM` may leave marginal x86 performance on the table vs the
  assembly paths (not measured to matter at level 3).

### Risks to manage
- The Windows-vs-ELF visibility split is non-obvious; a wrong flag silently
  empties the Windows export table. Documented inline in the build script.

## Alternatives considered

- **Keep `.S` asm per target:** divergent codegen, x86-only, build complexity.
- **Hidden visibility on Windows too:** breaks PE exports — rejected.

## References

- [scripts/build-zstd.sh](../scripts/build-zstd.sh)
- [ADR 0002 — zig cc native build](0002-zig-cc-native-build.md)
