package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/// A reusable decompression context.
///
/// Reusing one context across many {@link #decompress} calls amortises native
/// state allocation. Not thread-safe: confine an instance to one thread or pool it.
public final class ZstdDecompressCtx extends NativeObject {

    /// Creates a new decompression context.
    public ZstdDecompressCtx() {
        super(create());
    }

    private static MemorySegment create() {
        try {
            MemorySegment p = (MemorySegment) Bindings.CREATE_DCTX.invokeExact();
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createDCtx returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Sets an advanced decompression parameter, sticky across subsequent calls.
    ///
    /// @param parameter the parameter to set
    /// @param value     the value, validated natively against the parameter's bounds
    /// @return `this`, for chaining
    /// @throws ZstdException if the value is out of range for the parameter
    public ZstdDecompressCtx parameter(ZstdDecompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_SET_PARAMETER.invokeExact(ptr(), parameter.value(), value));
        return this;
    }

    /// Sets the largest back-reference window the decoder will accept, as a
    /// power of two. Raise it to decode frames built with a large `windowLog`.
    ///
    /// @param windowLogMax the base-2 log of the maximum accepted window size
    /// @return `this`, for chaining
    public ZstdDecompressCtx windowLogMax(int windowLogMax) {
        return parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, windowLogMax);
    }

    /// Decompresses a frame into a buffer of at most `maxSize` bytes.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @return the original bytes
    public byte[] decompress(byte[] compressed, int maxSize) {
        Objects.requireNonNull(compressed, "compressed");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            MemorySegment out = arena.allocate(Math.max(maxSize, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
                    ptr(), out, (long) maxSize, in, (long) compressed.length));
            return Zstd.copyOut(out, written);
        }
    }

    /// Decompresses a frame that was compressed against `dict`.
    ///
    /// The dictionary is re-digested on every call; for repeated use digest it
    /// once into a {@link ZstdDecompressDict} and use
    /// {@link #decompress(byte[], int, ZstdDecompressDict)}.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @param dict       the dictionary the frame was compressed against
    /// @return the original bytes
    public byte[] decompress(byte[] compressed, int maxSize, ZstdDictionary dict) {
        Objects.requireNonNull(compressed, "compressed");
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            byte[] d = dict.raw();
            MemorySegment dseg = Zstd.copyIn(arena, d);
            MemorySegment out = arena.allocate(Math.max(maxSize, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_USING_DICT.invokeExact(
                    ptr(), out, (long) maxSize, in, (long) compressed.length, dseg, (long) d.length));
            return Zstd.copyOut(out, written);
        }
    }

    /// Decompresses a frame against a pre-digested `dict`.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @param dict       the pre-digested decompression dictionary
    /// @return the original bytes
    public byte[] decompress(byte[] compressed, int maxSize, ZstdDecompressDict dict) {
        Objects.requireNonNull(compressed, "compressed");
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            MemorySegment out = arena.allocate(Math.max(maxSize, 1));
            MemorySegment ddict = dict.ptr();
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
                    ptr(), out, (long) maxSize, in, (long) compressed.length, ddict));
            return Zstd.copyOut(out, written);
        }
    }

    /// Zero-copy decompression: reads the frame from `src` and writes the
    /// result straight into `dst`, both native {@link MemorySegment}s the
    /// caller owns. No heap `byte[]` bounce — the segment addresses go
    /// directly to zstd. This is the fast path when input is an mmap slice and
    /// output is an arena buffer that becomes the materialized array as-is;
    /// see `docs/zero-copy.md`.
    ///
    /// Size `dst` to the decompressed length (read it from the frame with
    /// {@link Zstd#decompress(byte[])}'s header logic, or known out-of-band).
    ///
    /// @param dst the native destination buffer to write the result into
    /// @param src the native source frame to decompress
    /// @return the number of bytes written into `dst`
    /// @throws ZstdException if `dst` is too small or the frame is invalid
    public long decompress(MemorySegment dst, MemorySegment src) {
        NativeCall.requireNative(dst, "dst");
        NativeCall.requireNative(src, "src");
        return NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize()));
    }

    /// Zero-copy decompression against a pre-digested `dict`, segment to segment.
    ///
    /// @param dst  the native destination buffer to write the result into
    /// @param src  the native source frame to decompress
    /// @param dict the pre-digested decompression dictionary
    /// @return the number of bytes written into `dst`
    public long decompress(MemorySegment dst, MemorySegment src, ZstdDecompressDict dict) {
        NativeCall.requireNative(dst, "dst");
        NativeCall.requireNative(src, "src");
        MemorySegment ddict = dict.ptr();
        return NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize(), ddict));
    }

    /// Zero-copy decompression that sizes and allocates the output for you: reads
    /// the decompressed length from `frame`'s header, allocates a segment of
    /// exactly that size in `arena`, and decompresses into it. The returned
    /// segment is owned by `arena` and ready to use as a backing buffer.
    ///
    /// Requires the frame to store its decompressed size (frames produced by this
    /// library do); for size-less frames use {@link #decompress(MemorySegment, MemorySegment)}
    /// with a destination you size yourself.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param frame a complete zstd frame storing its decompressed size
    /// @return a segment of exactly the decompressed length, allocated in `arena`
    /// @throws ZstdException if the frame is invalid or stores no size
    public MemorySegment decompress(Arena arena, MemorySegment frame) {
        long size = Zstd.decompressedSize(frame);
        MemorySegment out = arena.allocate(size);
        decompress(out, frame);
        return out;
    }

    /// Zero-copy decompression against a pre-digested `dict`, allocating the
    /// exact-sized output in `arena`.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param frame a complete zstd frame storing its decompressed size
    /// @param dict  the pre-digested decompression dictionary
    /// @return a segment of exactly the decompressed length, allocated in `arena`
    public MemorySegment decompress(Arena arena, MemorySegment frame, ZstdDecompressDict dict) {
        long size = Zstd.decompressedSize(frame);
        MemorySegment out = arena.allocate(size);
        decompress(out, frame, dict);
        return out;
    }

    /// Current native memory used by this context, in bytes.
    ///
    /// @return the live context size
    public long sizeOf() {
        try {
            return (long) Bindings.SIZEOF_DCTX.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_DCTX.invokeExact(ptr);
    }
}
