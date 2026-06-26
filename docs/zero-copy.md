# Zero-copy with `MemorySegment`

zstd-java exposes two shapes of API:

- **`byte[]`** — convenient, for callers whose data is already on the heap.
- **`MemorySegment`** — zero-copy, for callers whose data is already off-heap.

This note explains why the segment shape exists and when it pays off.

## The core win: no copy at the call boundary

FFM downcalls need a *stable* native pointer. A heap `byte[]` can be relocated by
the GC, so the FFM runtime copies it into native memory for the duration of the
call — and copies the result back. **Two copies per call.**

A native `MemorySegment` already *is* a native address. You hand
`ZSTD_compress` / `ZSTD_decompress` the pointer directly. **Zero copies.**

```text
byte[] path:   heap byte[] ──copy──▶ native scratch ──ZSTD──▶ native scratch ──copy──▶ heap byte[]
segment path:  native src ───────────────────────────ZSTD──▶ native dst        (no copy)
```

## When it actually pays off (not always)

Zero-copy only helps if the data is **already native** on both ends. The
canonical case is a memory-mapped reader (e.g. Vortex):

- **Compressed input** — the reader `mmap`s the file into one `MemorySegment`;
  the zstd frame is already a zero-copy slice of it. A `byte[]` API forces
  `frame.toArray()` → `new byte[]` just to make the call. The segment API passes
  the mmap slice straight to `ZSTD_decompress`.
- **Decompressed output** — allocate the output in your arena
  (`arena.allocate(n)`) and let `ZSTD_decompress` write directly into it. That
  segment becomes the materialized backing buffer as-is — no temp `byte[]`, no
  `MemorySegment.copy`.

The decode path collapses from **mmap → byte[] → byte[] → arena** (three copies)
to **mmap-slice → arena** (zero copies).

## Secondary wins

- **Zero GC** — off-heap, no allocation churn in a scan hot loop.
- **No 2 GiB cap** — `byte[]` maxes at `Integer.MAX_VALUE`; segments are
  `long`-indexed.
- **Lifetime safety** — bounds-checked, tied to a confined `Arena`; the same
  ownership model as the rest of an FFM reader, cleaner than raw pointers.
- **Typed reads** — read `JAVA_LONG` / `JAVA_DOUBLE` straight off the
  decompressed segment with no re-wrap.

## Honest caveat

If the caller hands you a heap `byte[]` (the aircompressor fallback path, or
external input), wrapping it with `MemorySegment.ofArray(...)` still triggers the
copy for the downcall — no free lunch. So the API is **segment-first for the
zero-copy fast path, with a thin `byte[]` overload** for the rare heap caller.

## API map

| Operation              | byte[] (convenience)                            | MemorySegment (zero-copy)                          |
|------------------------|-------------------------------------------------|----------------------------------------------------|
| compress               | `ZstdCompressCtx.compress(byte[])`              | `ZstdCompressCtx.compress(dst, src)`               |
| compress + dict        | `ZstdCompressCtx.compress(byte[], ZstdCompressDict)` | `ZstdCompressCtx.compress(dst, src, ZstdCompressDict)` |
| decompress             | `ZstdDecompressCtx.decompress(byte[], int)`     | `ZstdDecompressCtx.decompress(dst, src)`           |
| decompress + dict      | `ZstdDecompressCtx.decompress(byte[], int, ZstdDecompressDict)` | `ZstdDecompressCtx.decompress(dst, src, ZstdDecompressDict)` |
| size output (no copy)  | frame header via `Zstd.decompress(byte[])`      | `Zstd.decompressedSize(MemorySegment)`             |

The explicit-`dst` methods return the number of bytes written. Size `dst` with
`Zstd.compressBound(srcSize)` for compression, or `Zstd.decompressedSize(frame)`
for decompression.

### Let the codec allocate

If you don't want to size the destination yourself, pass an `Arena` and the codec
sizes, allocates, and writes the output for you — still zero-copy, since the
output is allocated in *your* arena and zstd writes into it directly. The
returned segment is owned by that arena.

```java
MemorySegment frame   = cctx.compress(arena, src);    // bound-sized, trimmed to frame length
MemorySegment decoded = dctx.decompress(arena, frame); // header-sized, exact length
```

| Operation   | explicit dst (you size)                       | arena (codec sizes)                        |
|-------------|-----------------------------------------------|--------------------------------------------|
| compress    | `compress(dst, src)` → bytes written          | `compress(arena, src)` → frame segment     |
| decompress  | `decompress(dst, src)` → bytes written        | `decompress(arena, frame)` → output segment |

The arena form of `decompress` requires the frame to store its decompressed size
(one-shot `compress` always stamps it; a *streamed* frame only does so when you
pledge the size up front — see [Pledged size](#pledged-size-unlocks-zero-copy-decode)).
For size-less frames, size `dst` yourself.

## ByteBuffer interop

Much of the Java ecosystem speaks `ByteBuffer`, not `MemorySegment` — NIO
channels, Netty, and `FileChannel.map`'s `MappedByteBuffer`. We deliberately do
**not** add a third set of `ByteBuffer` overloads: the segment API already
bridges both directions of the FFM↔NIO boundary at zero copy, because FFM defines
the conversions.

- **`ByteBuffer` in** — wrap a *direct* buffer as a segment with
  `MemorySegment.ofBuffer(buf)` (zero copy; a heap-backed buffer copies, the same
  caveat as `byte[]`). Hand the segment to `compress` / `decompress`.
- **`MemorySegment` out to `ByteBuffer`** — `segment.asByteBuffer()` returns a
  buffer view over the native bytes, no copy. The decompressed arena segment is
  consumable by an existing `ByteBuffer` pipeline as-is.

```java
// an mmap'd frame is already a direct ByteBuffer (FileChannel.map)
MemorySegment frame  = MemorySegment.ofBuffer(mappedByteBuffer);
MemorySegment out    = dctx.decompress(arena, frame); // zero-copy decode
ByteBuffer    result = out.asByteBuffer();             // zero-copy hand-off
```

**Gap / proposed sugar.** The one-liner above is the supported path today, but it
leaks two FFM details onto the caller: `asByteBuffer()` returns a `BIG_ENDIAN`
buffer regardless of platform, and the segment's lifetime is the arena's, not the
buffer's. A thin `toByteBuffer()` convenience on the arena-returning results would
fix both in one place — set native byte order, document the borrowed lifetime:

```java
ByteBuffer result = dctx.decompress(arena, frame).toByteBuffer(); // proposed
```

This keeps the API segment-first (no parallel `ByteBuffer` surface to maintain);
it is purely an output adapter for callers already living in NIO.

## Zero-copy streaming

The one-shot segment methods above need the whole input in one segment. When data
is large or arrives incrementally but both ends are still off-heap, use the
segment **stream driver** — `ZstdCompressStream` / `ZstdDecompressStream` — which
drives `ZSTD_compressStream2` / `ZSTD_decompressStream` directly over native
buffers, in bounded memory, with no heap bounce (unlike `ZstdOutputStream` /
`ZstdInputStream`, which copy through `byte[]` to fit `java.io`).

Each step compresses/decompresses as much of `src` as fits in `dst` and reports a
`ZstdStreamResult` (`bytesConsumed`, `bytesProduced`, `remaining`). Advance the
source by `bytesConsumed`, drain `bytesProduced` from `dst`, and for compression
finish with `ZstdEndDirective.END` until `isComplete()`:

```java
try (ZstdCompressStream cs = new ZstdCompressStream(level)) {
    long off = 0;
    ZstdStreamResult r;
    do {
        r = cs.compress(dst, src.asSlice(off), ZstdEndDirective.END);
        off += r.bytesConsumed();
        sink.write(dst.asSlice(0, r.bytesProduced()));
    } while (!r.isComplete());
}
```

Both drivers take an optional `ZstdDictionary`. Decompression mirrors the loop,
calling `decompress(dst, src)` until a result `isComplete()` (frame fully decoded).

## Pledged size unlocks zero-copy decode

Streaming compression has a hidden cost the one-shot path does not: **a streamed
frame does not record its decompressed size.** zstd writes the content-size field
in the frame header only when the encoder knows the total up front — trivially
true for `ZSTD_compress`, but a streaming encoder is fed incrementally and closes
the frame without ever being told the total.

That field is exactly what the zero-copy decode path reads to size the output
arena. So a plain `ZstdOutputStream` frame **cannot be decoded zero-copy**:

```java
byte[] frame = streamCompress(data);          // no pledged size
Zstd.decompressedSize(segmentOf(frame));      // throws: "decompressed size not stored in frame"
dctx.decompress(arena, segmentOf(frame));     // same — it can't size the arena
```

The consumer is forced back onto the bounded streaming decoder (allocate, decode a
chunk, grow, repeat) or a guessed `maxSize` — the very heap-bounce the segment API
exists to avoid.

`ZstdOutputStream.withPledgedSize(out, level, total)` closes the loop. Tell the
encoder the total before the first byte and it stamps the content size into the
header, so a downstream reader can size the output arena exactly and decode in one
shot:

```java
try (var zout = ZstdOutputStream.withPledgedSize(sink, 19, data.length)) {
    zout.write(data);                          // pledge must match the bytes written
}
byte[] frame = sink.toByteArray();

// downstream, in a memory-mapped reader:
MemorySegment src = MemorySegment.ofBuffer(mmap);
MemorySegment out = dctx.decompress(arena, src);  // one allocation, zero copies
```

This is the case where pledging is not a micro-optimization but a correctness
gate: it is the difference between a frame that participates in the zero-copy
decode path and one that does not. Pledge whenever the producer streams but the
total is known (file length, serialized record count, `Content-Length`). The pledge
must equal the bytes actually written — a mismatch raises an error on close.
