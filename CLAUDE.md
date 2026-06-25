# zstd-java

Java **Foreign Function & Memory (FFM)** bindings for [Zstandard](https://github.com/facebook/zstd).
No JNI, no hand-written C. Targets **JDK 25+** (stable `java.lang.foreign`).

The differentiator is **dictionary compression** — trained from your own data via
ZDICT — plus a **zero-copy `MemorySegment` API** for callers whose bytes are
already off-heap (e.g. an mmap slice in, an arena buffer out).

A core goal is a **hermetic build with no binary blobs**: the native library is
never checked in or downloaded prebuilt. It is compiled from the pinned
`third_party/zstd` source on every build via `zig cc`, which bundles its own
clang + libc for every target. Anyone with the repo and Zig reproduces the exact
`.dylib/.so/.dll` from source — no vendored binaries to trust, audit, or keep in
sync, and no host toolchain or sysroot to provision.

## Layout

Multi-module Maven build. Root aggregator artifactId is `parent`; the published
library is `io.github.dfa1:zstd-java`.

```
zstd-java/                 root pom, artifactId: parent
├─ zstd/                   library module, artifactId: zstd-java (only Java sources)
├─ native/<classifier>/    one module per platform, packages libzstd.<ext>
├─ bom/                    dependency BOM
├─ third_party/zstd/       vendored facebook/zstd submodule (C source of truth)
└─ scripts/build-zstd.sh   zig cc build, invoked by each native module
```

- `zstd/` — pure-Java FFM bindings, package `io.github.dfa1.zstdffm`.
- `native/<classifier>/` — no Java; each builds + bundles the shared library for
  one classifier: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
  `windows-x86_64`, `windows-aarch64`.
- `third_party/zstd/` — pinned submodule; the only source of native code.

## Build requirements

Deliberately minimal — part of the hermetic goal is a tiny prerequisite set:

- **JDK 25+** — required for the stable `java.lang.foreign` API. Tested on
  Temurin/Zulu 25.
- **Zig** on `PATH` — the C cross-compiler for the native library; this is the
  only native-toolchain dependency (no system clang/gcc, no CMake, no sysroot).
  Tested with `zig 0.16.0`.
- **Git** — to fetch the `third_party/zstd` submodule
  (`git clone --recurse-submodules`, or `git submodule update --init`).
- **A POSIX shell** — `scripts/build-zstd.sh` is bash; cross-builds run from a
  Unix host (macOS/Linux). Windows targets are produced by cross-compiling, not
  by building on Windows.

Maven itself is **not** required: use the bundled wrapper `./mvnw`, which pins
Maven 3.9.16. Runtime needs `--enable-native-access=ALL-UNNAMED` (already set for
the test run via the surefire `argLine`).

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

- Checkstyle-clean (`./mvnw validate` runs it); see the Code style section below.
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

# Code style

- 4-space indent, **zero SonarQube bugs/smells**, no `sun.misc.Unsafe` or internal JDK APIs.
- Prefer explicit over clever; fail fast on unhandled cases.
- Idiomatic modern Java: reuse the JDK (override `Iterator.forEachRemaining`, don't invent
  `forEachChunk`; use `Optional`, records, sealed types, pattern switches, virtual threads, FFM).
  New APIs should feel like JDK APIs.
- Always braces for `if`/`else`/`for`/`while`, even one-liners (`if (c) { return a; }`).
- **Time quantities use `java.time.Duration`, never `long`** (no `long timeoutMs`/`delayNanos`).
  Exception: low-level JDK interop taking `long ns` (`Thread.sleep`, `LockSupport.parkNanos`,
  `System.nanoTime` math) — convert at the call site via `duration.toNanos()`/`toMillis()`.

### Javadoc (build-enforced: `failOnError` + `failOnWarnings`)

- Every public method: main prose description, `@param` per parameter, `@return` (unless `void`).
  Every public record: `@param` per component on the class doc. `@see`-only counts as no description.
- All `///` Markdown — **no HTML** (checkstyle `RegexpSingleline` blocks `<p>`,`<ul>`,`<li>`,
  `<strong>`,`<pre>`,`<table>`, …). Use blank `///` for paragraphs, `- ` lists, ` ```java ``` `,
  `**bold**`. Cross-refs `[ClassName#method(ParamType)]` — verify the target exists (wrong refs are
  **errors**).
- Check: `./mvnw javadoc:javadoc -pl zstd` must produce zero output.
