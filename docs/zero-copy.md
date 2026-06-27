# Zero-copy with `MemorySegment`

zstd-java exposes two shapes of API:

- **`byte[]`** — convenient, for callers whose data is already on the heap.
- **`MemorySegment`** — zero-copy *at the call boundary*, for callers whose data
  is already off-heap.

This note explains why the segment shape exists and when it pays off. For the
recipes — sizing output, letting the codec allocate, `ByteBuffer` interop,
streaming, and pledging the size — see the [how-to guide](how-to.md).

## What "zero-copy" means here

It means **no copy at the Java↔native boundary** — the same sense as zero-copy
I/O, where bytes still move but not redundantly between buffers. Compression
itself always reads all input and writes all output; that is the work, not a
copy. "Zero-copy" is about the *boundary*, and applies only to the
`MemorySegment` path — the `byte[]` overloads copy twice (see the honest caveat).

## The core win: no copy at the call boundary

FFM downcalls need a *stable* native pointer. A heap `byte[]` can be relocated by
the GC, so the FFM runtime copies it into native memory for the duration of the
call — and copies the result back. **Two copies per call.**

A native `MemorySegment` already *is* a native address. You hand
`ZSTD_compress` / `ZSTD_decompress` the pointer directly. **No boundary copy.**

```text
byte[] path:   heap byte[] ──copy──▶ native scratch ──ZSTD──▶ native scratch ──copy──▶ heap byte[]
segment path:  native src ───────────────────────────ZSTD──▶ native dst        (no boundary copy)
```

## When it actually pays off (not always)

This only helps if the data is **already native** on both ends. The canonical
case is a memory-mapped reader (e.g. Vortex):

- **Compressed input** — the reader `mmap`s the file into one `MemorySegment`;
  the zstd frame is already a slice of it. A `byte[]` API forces
  `frame.toArray()` → `new byte[]` just to make the call. The segment API passes
  the mmap slice straight to `ZSTD_decompress`.
- **Decompressed output** — allocate the output in your arena
  (`arena.allocate(n)`) and let `ZSTD_decompress` write directly into it. That
  segment becomes the materialized backing buffer as-is — no temp `byte[]`, no
  `MemorySegment.copy`.

The decode path collapses from **mmap → byte[] → byte[] → arena** (three copies)
to **mmap-slice → arena** (no boundary copy).

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
copy for the downcall — no free lunch. A heap `ByteBuffer` is the same: its
`MemorySegment.ofBuffer(...)` wrap is a heap segment and still copies. Only data
that is *already native* avoids the boundary copy. So the API is **segment-first
for the zero-copy fast path, with a thin `byte[]` overload** for the rare heap
caller.

We deliberately do **not** add a parallel `ByteBuffer` API surface: FFM already
defines the conversions (`MemorySegment.ofBuffer` in, `segment.asByteBuffer()`
out), so a direct buffer reaches the same path with one wrapping call — see the
[how-to](how-to.md).

## Why a streamed frame can't be decoded zero-copy

The zero-copy decode path reads the frame's **decompressed-size** header field to
size the output arena in one shot. zstd writes that field only when the encoder
knows the total up front — trivially true for one-shot `ZSTD_compress`, but a
*streaming* encoder is fed incrementally and closes the frame without ever being
told the total. So a plain `ZstdOutputStream` frame omits the size, and a
consumer is forced back onto the bounded streaming decoder (allocate, decode a
chunk, grow, repeat) — the very heap-bounce the segment API exists to avoid.

The fix is to **pledge the size** before the first byte, which stamps the content
size into the header and lets a downstream reader size the arena exactly. This is
not a micro-optimization but a correctness gate: it is the difference between a
frame that participates in the zero-copy decode path and one that does not. The
recipe is in the [how-to](how-to.md#pledge-the-size-so-a-streamed-frame-decodes-in-one-shot).
