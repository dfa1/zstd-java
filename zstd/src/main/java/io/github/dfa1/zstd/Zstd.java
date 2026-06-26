package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// One-shot, stateless zstd compression and decompression over `byte[]`.
///
/// Every method is thread-safe and allocates its own native scratch buffers.
/// For repeated calls on a hot path, reuse a {@link ZstdCompressCtx} /
/// {@link ZstdDecompressCtx} instead to avoid re-allocating per call.
///
/// {@snippet :
/// byte[] packed   = Zstd.compress("hello world".getBytes());
/// byte[] restored = Zstd.decompress(packed);
/// }
public final class Zstd {

    /// Sentinel returned by zstd when a frame carries no decompressed-size header.
    private static final long CONTENTSIZE_UNKNOWN = -1L;
    /// Sentinel returned by zstd when the input is not a valid zstd frame.
    private static final long CONTENTSIZE_ERROR = -2L;

    /// Compresses `src` at the library default level.
    ///
    /// @param src bytes to compress
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src) {
        return compress(src, defaultCompressionLevel());
    }

    /// Compresses `src` at the given level.
    ///
    /// @param src   bytes to compress
    /// @param level compression level in [{@link #minCompressionLevel()}, {@link #maxCompressionLevel()}];
    ///              higher is smaller but slower
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src, int level) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, src);
            long bound = compressBound(src.length);
            MemorySegment out = arena.allocate(bound);
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
                    out, bound, in, (long) src.length, level));
            return copyOut(out, written);
        }
    }

    /// Decompresses a complete zstd frame whose decompressed size is recorded in
    /// its header (the case for frames produced by this library).
    ///
    /// @param compressed a complete zstd frame
    /// @return the original bytes
    /// @throws ZstdException if the frame is invalid or its content size is not stored;
    ///                       use {@link #decompress(byte[], int)} for the latter
    public static byte[] decompress(byte[] compressed) {
        long size = frameContentSize(compressed);
        if (size == CONTENTSIZE_UNKNOWN) {
            throw new ZstdException("decompressed size not stored in frame; call decompress(src, maxSize)");
        }
        if (size == CONTENTSIZE_ERROR) {
            throw new ZstdException("not a valid zstd frame");
        }
        return decompress(compressed, Math.toIntExact(size));
    }

    /// Decompresses a zstd frame into a buffer of at most `maxSize` bytes.
    /// Use this when the original size is known out-of-band or not stored in the frame.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @return the original bytes (length ≤ `maxSize`)
    /// @throws ZstdException if the frame is invalid or larger than `maxSize`
    public static byte[] decompress(byte[] compressed, int maxSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            MemorySegment out = arena.allocate(Math.max(maxSize, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS.invokeExact(
                    out, (long) maxSize, in, (long) compressed.length));
            return copyOut(out, written);
        }
    }

    /// Decompressed size recorded in a zstd frame's header, read directly from a
    /// native {@link MemorySegment} with no copy — use it to size the destination
    /// for the zero-copy {@link ZstdDecompressCtx#decompress(MemorySegment, MemorySegment)}.
    ///
    /// @param frame a complete zstd frame
    /// @return the decompressed length in bytes
    /// @throws ZstdException if the frame is invalid or does not store its size
    public static long decompressedSize(MemorySegment frame) {
        NativeCall.requireNative(frame, "frame");
        long size;
        try {
            size = (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(frame, frame.byteSize());
        } catch (Throwable t) {
            throw sneaky(t);
        }
        if (size == CONTENTSIZE_UNKNOWN) {
            throw new ZstdException("decompressed size not stored in frame");
        }
        if (size == CONTENTSIZE_ERROR) {
            throw new ZstdException("not a valid zstd frame");
        }
        return size;
    }

    /// Maximum compressed size for an input of `srcSize` bytes — the buffer
    /// size guaranteed to never overflow during compression.
    ///
    /// @param srcSize the uncompressed input length in bytes
    /// @return the worst-case compressed size for that input
    public static long compressBound(long srcSize) {
        try {
            return (long) Bindings.COMPRESS_BOUND.invokeExact(srcSize);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Decompressed size stored in the frame header, or a negative sentinel.
    private static long frameContentSize(byte[] compressed) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            return (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(in, (long) compressed.length);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Highest supported compression level.
    ///
    /// @return the maximum valid compression level
    public static int maxCompressionLevel() {
        try {
            return (int) Bindings.MAX_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Lowest supported compression level (negative levels trade ratio for speed).
    ///
    /// @return the minimum valid compression level
    public static int minCompressionLevel() {
        try {
            return (int) Bindings.MIN_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// The level used by {@link #compress(byte[])}.
    ///
    /// @return the default compression level
    public static int defaultCompressionLevel() {
        try {
            return (int) Bindings.DEFAULT_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Estimates the memory a compression context will use at `level`, before
    /// creating one — useful for budgeting.
    ///
    /// @param level the compression level
    /// @return the estimated context size in bytes
    public static long estimateCompressContextSize(int level) {
        try {
            return (long) Bindings.ESTIMATE_CCTX_SIZE.invokeExact(level);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Estimates the memory a decompression context will use.
    ///
    /// @return the estimated context size in bytes
    public static long estimateDecompressContextSize() {
        try {
            return (long) Bindings.ESTIMATE_DCTX_SIZE.invokeExact();
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Estimates the memory a digested compression dictionary of `dictSize` bytes
    /// will use at `level`.
    ///
    /// @param dictSize the raw dictionary size in bytes
    /// @param level    the compression level
    /// @return the estimated digested-dictionary size in bytes
    public static long estimateCompressDictSize(long dictSize, int level) {
        try {
            return (long) Bindings.ESTIMATE_CDICT_SIZE.invokeExact(dictSize, level);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Estimates the memory a digested decompression dictionary of `dictSize`
    /// bytes will use.
    ///
    /// @param dictSize the raw dictionary size in bytes
    /// @return the estimated digested-dictionary size in bytes
    public static long estimateDecompressDictSize(long dictSize) {
        try {
            return (long) Bindings.ESTIMATE_DDICT_SIZE.invokeExact(dictSize, 0); // ZSTD_dlm_byCopy
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /// Runtime zstd version, e.g. `"1.6.0"`.
    ///
    /// @return the linked zstd library version as an `x.y.z` string
    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    public static String version() {
        try {
            MemorySegment p = (MemorySegment) Bindings.VERSION_STRING.invokeExact();
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    // --- package-private helpers shared with the context classes ---
    // Native-call status checking and segment guards live in NativeCall.

    static MemorySegment copyIn(Arena arena, byte[] src) {
        MemorySegment seg = arena.allocate(Math.max(src.length, 1));
        MemorySegment.copy(src, 0, seg, JAVA_BYTE, 0, src.length);
        return seg;
    }

    static byte[] copyOut(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    private Zstd() {
        // no instances
    }
}
