# Tutorial: Getting started

This walks you from a clean checkout to your first compress/decompress round-trip.

## 1. Clone and build

You need JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH` (Zig is the C
compiler for the native lib).

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java
mvn install
```

The build invokes `scripts/build-zstd.sh`, compiling `libzstd` from the vendored
source — no autotools or CMake needed.

## 2. Your first round-trip

```java
import io.github.dfa1.zstd.Zstd;

byte[] original = "hello world".getBytes();
byte[] packed   = Zstd.compress(original);
byte[] restored = Zstd.decompress(packed);   // size read from the frame header

assert java.util.Arrays.equals(original, restored);
```

## 3. Run with native access enabled

The FFM API requires an explicit flag:

```bash
java --enable-native-access=ALL-UNNAMED Demo.java
```

## 4. Skip the heap: zero-copy with `MemorySegment`

The `byte[]` round-trip above copies your data onto the heap on the way in and
off it on the way out. When your bytes are **already off-heap** — an `mmap` slice,
an arena buffer — that copy is pure waste. This is the library's strong point:
the `MemorySegment` overloads hand zstd the segment address directly, so there is
**no copy in, no copy out, and no per-call heap allocation** (hence no GC churn).

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    MemorySegment frame = reader.mmapSlice();   // already native — never touches the heap
    long n = Zstd.decompressedSize(frame);      // read the header, no copy
    MemorySegment out = arena.allocate(n);      // this segment *is* the output buffer
    dctx.decompress(out, frame);                // native → native
}
```

In benchmarks this path allocates ~0 bytes/op regardless of payload size, while
the `byte[]` path allocates the full output every call. See
[docs/benchmarks.md](benchmarks.md) for numbers and [docs/zero-copy.md](zero-copy.md)
for when it pays.

## 5. Stream data that doesn't fit in memory

For large or unbounded data, don't buffer the whole thing — wrap an ordinary
`java.io` stream. `ZstdOutputStream` / `ZstdInputStream` compress and decompress
incrementally, so memory stays flat no matter how big the payload.

```java
// compress a file as you write it
try (var out = new ZstdOutputStream(Files.newOutputStream(packed), 9)) {
    Files.copy(source, out);
}

// decompress as you read it back (transferTo loops internally until EOF)
try (var in = new ZstdInputStream(Files.newInputStream(packed));
     var out = Files.newOutputStream(restored)) {
    in.transferTo(out);
}
```

They are plain `OutputStream` / `InputStream` subclasses — drop them into any code
that already speaks `java.io`.

That's the whole loop. From here, pick a [how-to guide](../README.md#how-to-guides)
for your actual task, or browse the [reference](../README.md#reference).
