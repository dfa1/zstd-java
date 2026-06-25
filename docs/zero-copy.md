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

The segment methods return the number of bytes written into `dst`. Size `dst`
with `Zstd.compressBound(srcSize)` for compression, or
`Zstd.decompressedSize(frame)` for decompression.
