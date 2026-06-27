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

## Reset a context to recycle it

A context is already reusable across whole `compress` / `decompress` calls. Reset
goes further: it recycles the *native state* of one context — for pooled contexts,
or to abort a half-written frame and start clean — without freeing and recreating
it. Pick what to clear with `ZstdResetDirective`:

```java
try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19)) {
    byte[] a = cctx.compress(first);

    // Cheap: drop any unflushed frame state, keep the level and parameters.
    cctx.reset(ZstdResetDirective.SESSION_ONLY);
    byte[] b = cctx.compress(second);

    // Full wipe: parameters back to default, dictionary cleared, level reset to
    // Zstd.defaultCompressionLevel(). Only valid between frames, not mid-frame.
    cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
}
```

`ZstdDecompressCtx.reset(...)` works the same way. Reuse alone amortises
allocation; reset lets a long-lived or pooled context return to a known state
without churning native memory.

## Compress with a dictionary *and* advanced parameters

The per-call `compress(src, dict)` overloads take the legacy dictionary path,
which ignores the advanced parameters (checksum, window log, long-distance
matching) set on the context. To combine the two, make the dictionary *sticky*
with `loadDictionary` — then the normal `compress` path honours both:

```java
try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19).checksum(true)) {
    cctx.loadDictionary(dict);          // ZstdDictionary, or a native MemorySegment
    byte[] frame = cctx.compress(record); // dictionary + checksum, together
}
```

For a dictionary reused across a pool of contexts, digest it once and attach it
by reference — no per-call digesting, no copy. It pairs with `reset` for a
pooled, recycled context:

```java
try (ZstdCompressDict cdict = new ZstdCompressDict(dict, 19)) {
    // one cctx per pooled worker, all sharing the one digested dictionary
    try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
        cctx.refDictionary(cdict);          // borrowed; cdict must outlive cctx
        byte[] a = cctx.compress(first);
        cctx.reset(ZstdResetDirective.SESSION_ONLY); // recycle, keep the dictionary
        byte[] b = cctx.compress(second);
    }
}
```

A loaded or referenced dictionary stays until replaced, cleared with `null`, or
dropped by a parameter `reset`. `ZstdDecompressCtx` mirrors all of this.

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

## Compress a `ByteBuffer` (NIO / Netty) without copying

Much of the ecosystem speaks `ByteBuffer`. There is no separate `ByteBuffer` API —
wrap the buffer as a `MemorySegment` with `MemorySegment.ofBuffer(...)` and use the
segment overloads above. A **direct** buffer wraps with zero copy; a heap buffer is
rejected by the native guard (wrap is a heap segment), so copy it to a direct buffer
or a `byte[]` first.

```java
try (Arena arena = Arena.ofConfined();
     ZstdCompressCtx cctx = new ZstdCompressCtx()) {
    ByteBuffer src = channel.map(READ_ONLY, 0, size, arena); // direct, off-heap
    MemorySegment in  = MemorySegment.ofBuffer(src);         // zero-copy view
    MemorySegment out = cctx.compress(arena, in);            // arena-owned frame
    ByteBuffer frame  = out.asByteBuffer();                  // zero-copy hand-off
}
```

For the mechanics — `[position, limit)` coverage, read-only and lifetime rules,
and the `asByteBuffer()` byte-order wart on the way back — see
[zero-copy.md § ByteBuffer interop](zero-copy.md).

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
