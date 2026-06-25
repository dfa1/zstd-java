# zstd-java

Java **Foreign Function & Memory (FFM)** bindings for [Zstandard](https://github.com/facebook/zstd).
No JNI, no hand-written C. Targets **JDK 25+** (stable `java.lang.foreign`).

The differentiator is **dictionary compression** — trained from your own data via
ZDICT — plus a **zero-copy `MemorySegment` API** for callers whose bytes are
already off-heap (e.g. an mmap slice in, an arena buffer out).

## Layout

Multi-module Maven build (`io.github.dfa1:zstd-java`):

- `core/` — pure-Java FFM bindings (package `io.github.dfa1.zstdffm`). The only
  module with Java sources.
- `native/<classifier>/` — one module per platform; each packages a
  `libzstd.{dylib,so,dll}` built from the `zstd/` submodule. No Java.
  Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
  `windows-x86_64`, `windows-aarch64`.
- `bom/` — dependency BOM.
- `zstd/` — vendored `facebook/zstd` git submodule (the C source of truth).

## Native build

`scripts/build-zstd.sh <output-resources-dir> <classifier>` compiles the zstd
library sources directly with **`zig cc`** — no zstd Makefile, no CMake. Zig
bundles clang + libc for every target, so any host cross-compiles any of the six
classifiers hermetically. The Maven `exec` plugin runs it in `generate-resources`;
it is idempotent (skips if the library already exists).

Key flags: `-DZSTD_DISABLE_ASM=1` (drops x86-only `.S`, identical codegen on every
target), `-fvisibility=hidden` on ELF/Mach-O, and lld `--export-all-symbols` on
Windows (do **not** use hidden visibility there — it suppresses the PE exports).

Built `.dylib/.so/.dll` are git-ignored; they are regenerated from the submodule.

## Code conventions

- Tabs for indentation (match existing files); checkstyle-clean.
- Native pointers wrap in `NativeObject` (`AutoCloseable`, idempotent close).
- All native handles live in `Bindings`; `size_t`/`unsigned long long` map to
  `JAVA_LONG`. Public methods guard zstd's negative sentinels.
- API is **segment-first for the zero-copy fast path, with thin `byte[]`
  overloads** for heap callers. Never allocate a `byte[]` for decode output on a
  hot path — see [docs/zero-copy.md](docs/zero-copy.md).
- Run with `--enable-native-access=ALL-UNNAMED`.

## Testing

- Cover happy path, negative cases (invalid input / errors), and corners (empty, zero, max,
  boundaries). Unit tests must be fast — no file I/O, network, or sleep; mock or use in-memory data.
- **Integration tests are ground truth** (no formal spec): interop with the Rust reference. Write
  one for every encoding round-trip and file-format boundary.
- JUnit 5 + Mockito (BDDMockito) + AssertJ. Class under test named `sut`. Every test has
  `// Given` / `// When` / `// Then`. BDDMockito only: `given(mock.m()).willReturn(v)` /
  `then(...)` (static-import only `given`/`then`, never `willReturn`/`willThrow`).
- Prefer `@ParameterizedTest` over copy-paste (`@ValueSource`, else `@ArgumentsSource`/named cases).
  For large input spaces use seeded-random `@MethodSource` generators — they find corners examples
  miss. Put generators in `RandomArrays` (integration) or a similar util; keep counts low (10–30)
  when the test does file I/O or JNI.
- `@Nested` groups related scenarios (`@BeforeEach` in a nested class applies only to it). Private
  helpers go after all `@Test` methods.
