package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A reusable compression context.
///
/// Reusing one context across many {@link #compress} calls amortises native
/// state allocation, making it cheaper than the stateless {@link Zstd#compress}
/// on hot paths. Not thread-safe: confine an instance to one thread or pool it.
///
/// {@snippet :
/// try (ZstdCompressCtx ctx = new ZstdCompressCtx().level(19)) {
///     for (byte[] msg : messages) {
///         sink.accept(ctx.compress(msg));
///     }
/// }
/// }
public final class ZstdCompressCtx extends NativeObject {

    private int level = Zstd.defaultCompressionLevel();

    /// Creates a new compression context at the default level.
    public ZstdCompressCtx() {
        super(create());
    }

    private static MemorySegment create() {
        try {
            MemorySegment p = (MemorySegment) Bindings.CREATE_CCTX.invokeExact();
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createCCtx returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Sets the compression level for subsequent {@link #compress} calls.
    ///
    /// @param level the compression level to use
    /// @return `this`, for chaining
    public ZstdCompressCtx level(int level) {
        this.level = level;
        return this;
    }

    /// Compresses `src` into a new zstd frame using this context.
    ///
    /// @param src the bytes to compress
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            long bound = Zstd.compressBound(src.length);
            MemorySegment out = arena.allocate(bound);
            long written = Zstd.call(() -> (long) Bindings.COMPRESS_CCTX.invokeExact(
                    ptr(), out, bound, in, (long) src.length, level));
            return Zstd.copyOut(out, written);
        }
    }

    /// Compresses `src` against `dict` at this context's level.
    ///
    /// The dictionary is re-digested on every call; for repeated compression
    /// against the same dictionary, digest it once into a {@link ZstdCompressDict}
    /// and use {@link #compress(byte[], ZstdCompressDict)}.
    ///
    /// @param src  the bytes to compress
    /// @param dict the dictionary to compress against
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src, ZstdDictionary dict) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            byte[] d = dict.raw();
            MemorySegment dseg = Zstd.copyIn(arena, d);
            long bound = Zstd.compressBound(src.length);
            MemorySegment out = arena.allocate(bound);
            long written = Zstd.call(() -> (long) Bindings.COMPRESS_USING_DICT.invokeExact(
                    ptr(), out, bound, in, (long) src.length, dseg, (long) d.length, level));
            return Zstd.copyOut(out, written);
        }
    }

    /// Compresses `src` against a pre-digested `dict` (the level was
    /// fixed when the {@link ZstdCompressDict} was built).
    ///
    /// @param src  the bytes to compress
    /// @param dict the pre-digested compression dictionary
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src, ZstdCompressDict dict) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            long bound = Zstd.compressBound(src.length);
            MemorySegment out = arena.allocate(bound);
            MemorySegment cdict = dict.ptr();
            long written = Zstd.call(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                    ptr(), out, bound, in, (long) src.length, cdict));
            return Zstd.copyOut(out, written);
        }
    }

    /// Zero-copy compression: reads `src` and writes the frame straight into
    /// `dst`, both native {@link MemorySegment}s the caller owns. No heap
    /// `byte[]` bounce — hand zstd the segment addresses directly. This is
    /// the fast path when your bytes are already off-heap (e.g. an mmap slice and
    /// an arena-allocated output); see `docs/zero-copy.md`.
    ///
    /// Size `dst` with {@link Zstd#compressBound(long)} to guarantee it fits.
    ///
    /// @param dst the native destination buffer to write the frame into
    /// @param src the native source bytes to compress
    /// @return the number of bytes written into `dst`
    /// @throws ZstdException if `dst` is too small or compression fails
    public long compress(MemorySegment dst, MemorySegment src) {
        return Zstd.call(() -> (long) Bindings.COMPRESS_CCTX.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize(), level));
    }

    /// Zero-copy compression against a pre-digested `dict`, segment to segment.
    ///
    /// @param dst  the native destination buffer to write the frame into
    /// @param src  the native source bytes to compress
    /// @param dict the pre-digested compression dictionary
    /// @return the number of bytes written into `dst`
    public long compress(MemorySegment dst, MemorySegment src, ZstdCompressDict dict) {
        MemorySegment cdict = dict.ptr();
        return Zstd.call(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize(), cdict));
    }

    /// Zero-copy compression that allocates the output for you: reserves a
    /// worst-case buffer ({@link Zstd#compressBound(long)}) in `arena`,
    /// compresses into it, and returns a slice trimmed to the actual frame length.
    /// The returned segment is owned by `arena`.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param src   the native source bytes to compress
    /// @return the zstd frame, a slice of an `arena`-owned segment
    public MemorySegment compress(Arena arena, MemorySegment src) {
        MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
        long written = compress(dst, src);
        return dst.asSlice(0, written);
    }

    /// Zero-copy compression against a pre-digested `dict`, allocating the
    /// output in `arena` and returning a slice trimmed to the frame length.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param src   the native source bytes to compress
    /// @param dict  the pre-digested compression dictionary
    /// @return the zstd frame, a slice of an `arena`-owned segment
    public MemorySegment compress(Arena arena, MemorySegment src, ZstdCompressDict dict) {
        MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
        long written = compress(dst, src, dict);
        return dst.asSlice(0, written);
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        long ignored = (long) Bindings.FREE_CCTX.invokeExact(ptr);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
