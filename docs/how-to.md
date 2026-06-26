# How-to guides

Task-focused recipes. Each assumes you have the library on the classpath (see the
[tutorial](tutorial.md)).

## Compress on a hot path

Reuse a context to amortise native allocation across many calls:

```java
try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19);
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(message);
    byte[] restored = dctx.decompress(packed, message.length);
}
```

Pick the level explicitly with `Zstd.maxCompressionLevel()` /
`minCompressionLevel()` when you need the extreme ends.

## Compress many small payloads with a dictionary

For many small, similar payloads (log lines, JSON records, protobufs), a
dictionary compresses each one far smaller than it could be alone. Train one on
representative samples:

```java
ZstdDictionary dict = ZstdDictionary.train(sampleRecords, 16 * 1024);

try (ZstdCompressCtx cctx = new ZstdCompressCtx();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(record, dict);
    byte[] restored = dctx.decompress(packed, record.length, dict);
}

byte[] persisted = dict.toByteArray();               // store / ship the dictionary
ZstdDictionary reloaded = ZstdDictionary.of(persisted);
```

On a hot path, digest the dictionary once to skip per-call setup:

```java
try (ZstdCompressDict cdict = new ZstdCompressDict(dict, 19);
     ZstdDecompressDict ddict = new ZstdDecompressDict(dict);
     ZstdCompressCtx cctx = new ZstdCompressCtx();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    byte[] packed   = cctx.compress(record, cdict);
    byte[] restored = dctx.decompress(packed, record.length, ddict);
}
```

## Avoid heap copies with `MemorySegment`

When your data is already off-heap — an `mmap` slice in, an arena buffer out —
use the `MemorySegment` overloads to skip the heap `byte[]` bounce entirely. FFM
hands zstd the segment address directly: no copy in, no copy out, no GC churn.

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
    MemorySegment frame = reader.mmapSlice();           // already native
    long n = Zstd.decompressedSize(frame);              // read header, no copy
    MemorySegment out = arena.allocate(n);              // becomes the backing buffer
    dctx.decompress(out, frame);                        // native → native
}
```

There are matching `compress(dst, src)` / `decompress(dst, src)` overloads (plus
dictionary variants) returning the number of bytes written. For *why and when*
this pays off, see the [explanation](zero-copy.md).

## Run against a self-built libzstd

The loader only ever loads the library bundled in the platform native jar on the
classpath — there is no path override. Loading a caller-supplied native library
would be arbitrary native code execution in the JVM, so to use a `libzstd` you
built yourself, build it *into* that resource and rebuild the jar:

```bash
# write the library into the matching native module's resources
./scripts/build-zstd.sh native/<classifier>/src/main/resources <classifier>
# classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#           | windows-x86_64 | windows-aarch64

./mvnw -pl native/<classifier> install   # repackage the native jar
```

The bundled `.dylib/.so/.dll` are git-ignored and regenerated from the submodule,
so this just overwrites the artifact the loader already trusts.
