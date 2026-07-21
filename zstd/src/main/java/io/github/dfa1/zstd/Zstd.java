package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// One-shot, stateless zstd compression and decompression over `byte[]`.
///
/// Every method is thread-safe and allocates its own native scratch buffers.
/// For repeated calls on a hot path, reuse a [ZstdCompressContext] /
/// [ZstdDecompressContext] instead to avoid re-allocating per call.
///
/// {@snippet :
/// byte[] packed   = Zstd.compress("hello world".getBytes());
/// byte[] restored = Zstd.decompress(packed);
/// }
public final class Zstd {

    /// Sentinel returned by zstd when a frame carries no decompressed-size header.
    static final long CONTENTSIZE_UNKNOWN = -1L;
    /// Sentinel returned by zstd when the input is not a valid zstd frame.
    static final long CONTENTSIZE_ERROR = -2L;

    /// Compresses `src` at the library default level.
    ///
    /// @param src bytes to compress
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src) {
        return compress(src, ZstdCompressionLevel.DEFAULT);
    }

    /// Compresses `src` at the given level.
    ///
    /// @param src   bytes to compress
    /// @param level the compression level to use; higher is smaller but slower
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src, ZstdCompressionLevel level) {
        Objects.requireNonNull(src, "src");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, src);
            long bound = compressBound(src.length);
            MemorySegment out = arena.allocate(bound);
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
                    out, bound, in, (long) src.length, level.value()));
            return copyOut(out, written);
        }
    }

    /// Decompresses a complete zstd frame whose decompressed size is recorded in
    /// its header (the case for frames produced by this library).
    ///
    /// **Security:** this overload trusts the content size declared in the frame
    /// header and allocates a buffer of that size. The header is part of the input,
    /// so a hostile frame can declare a large size (up to the maximum array length)
    /// and force a correspondingly large allocation — a decompression-bomb denial of
    /// service. For input you do not control, use [#decompress(byte[], int)] with a
    /// sane bound instead.
    ///
    /// @param compressed a complete zstd frame
    /// @return the original bytes
    /// @throws ZstdException if the frame is invalid, its content size is not stored
    ///                       (use [#decompress(byte[], int)] for the latter), or the
    ///                       declared size exceeds the maximum array length
    public static byte[] decompress(byte[] compressed) {
        Objects.requireNonNull(compressed, "compressed");
        long size = requireStoredContentSize(frameContentSize(compressed));
        return decompress(compressed, toArrayLength(size));
    }

    /// Narrows a frame-declared content size to a `byte[]` length, rejecting sizes
    /// that exceed what a Java array can hold. The size comes from the (untrusted)
    /// frame header, so this fails with a [ZstdException] rather than letting a raw
    /// `ArithmeticException` escape.
    ///
    /// @param size a non-negative content size from a frame header
    /// @return `size` as an `int`
    /// @throws ZstdException if `size` exceeds [Integer#MAX_VALUE]
    private static int toArrayLength(long size) {
        if (size > Integer.MAX_VALUE) {
            throw new ZstdException("decompressed size " + size
                    + " exceeds the maximum array length; use decompress(byte[], int) to bound it");
        }
        return (int) size;
    }

    /// Decompresses a zstd frame into a buffer of at most `maxSize` bytes.
    /// Use this when the original size is known out-of-band or not stored in the frame.
    ///
    /// This is the safe entry point for **untrusted** input: `maxSize` caps the
    /// allocation and decode, so a hostile frame cannot trigger an oversized
    /// allocation the way [#decompress(byte[])] can. Pick a bound your caller can
    /// afford; the frame is rejected if its content exceeds it.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @return the original bytes (length ≤ `maxSize`)
    /// @throws ZstdException if the frame is invalid or larger than `maxSize`
    public static byte[] decompress(byte[] compressed, int maxSize) {
        Objects.requireNonNull(compressed, "compressed");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            MemorySegment out = arena.allocate(Math.max(maxSize, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS.invokeExact(
                    out, (long) maxSize, in, (long) compressed.length));
            return copyOut(out, written);
        }
    }

    /// Decompressed size recorded in a zstd frame's header, read directly from a
    /// native [MemorySegment] with no copy — use it to size the destination
    /// for the zero-copy [ZstdDecompressContext#decompress(MemorySegment, MemorySegment)].
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
            throw NativeCall.rethrow(t);
        }
        return requireStoredContentSize(size);
    }

    /// Maps a raw `ZSTD_getFrameContentSize` / `ZSTD_findDecompressedSize` result to
    /// a usable length, turning zstd's negative sentinels into exceptions.
    ///
    /// @param size a content size, or a `CONTENTSIZE_UNKNOWN` / `CONTENTSIZE_ERROR` sentinel
    /// @return `size` when it is a real length
    /// @throws ZstdException if the size is not stored, or the input is not valid zstd data
    static long requireStoredContentSize(long size) {
        if (size == CONTENTSIZE_UNKNOWN) {
            throw new ZstdException("decompressed size not stored in frame");
        }
        if (size == CONTENTSIZE_ERROR) {
            throw new ZstdException("not a valid zstd frame");
        }
        return size;
    }

    /// Dictionary id stamped in raw dictionary `bytes`, read with the core
    /// `libzstd` reader.
    ///
    /// This reads the id a *dictionary* records, not the id a frame references —
    /// for the latter use [ZstdFrame#dictId(byte[])]. It returns the same value as
    /// [ZstdDictionary#id()] (which uses the equivalent `ZDICT` reader); prefer this
    /// when you only hold raw bytes and do not want to wrap them in a
    /// [ZstdDictionary].
    ///
    /// @param bytes raw dictionary content
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if `bytes` is not a standard zstd
    ///         dictionary (for example a raw/content-only dictionary with no header)
    public static ZstdDictionaryId dictId(byte[] bytes) {
        try (Arena arena = Arena.ofConfined()) {
            return dictId(copyIn(arena, bytes), bytes.length);
        }
    }

    /// Dictionary id stamped in native dictionary `bytes`, read with no copy.
    /// Otherwise identical to [#dictId(byte[])].
    ///
    /// @param bytes native dictionary content
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if `bytes` is not a standard zstd dictionary
    public static ZstdDictionaryId dictId(MemorySegment bytes) {
        NativeCall.requireNative(bytes, "bytes");
        return dictId(bytes, bytes.byteSize());
    }

    private static ZstdDictionaryId dictId(MemorySegment bytes, long size) {
        try {
            return ZstdDictionaryId.of((int) Bindings.GET_DICT_ID_FROM_DICT.invokeExact(bytes, size));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
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
            throw NativeCall.rethrow(t);
        }
    }

    /// Decompressed size stored in the frame header, or a negative sentinel.
    private static long frameContentSize(byte[] compressed) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            return (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(in, (long) compressed.length);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Highest supported compression level.
    ///
    /// @return the maximum valid compression level
    public static int maxCompressionLevel() {
        try {
            return (int) Bindings.MAX_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Lowest supported compression level (negative levels trade ratio for speed).
    ///
    /// @return the minimum valid compression level
    public static int minCompressionLevel() {
        try {
            return (int) Bindings.MIN_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// The level used by [#compress(byte[])].
    ///
    /// @return the default compression level
    public static int defaultCompressionLevel() {
        try {
            return (int) Bindings.DEFAULT_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a compression context will use at `level`, before
    /// creating one — useful for budgeting.
    ///
    /// @param level the compression level to use
    /// @return the estimated context size in bytes
    public static long estimateCompressContextSize(ZstdCompressionLevel level) {
        try {
            return (long) Bindings.ESTIMATE_CCTX_SIZE.invokeExact(level.value());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a decompression context will use.
    ///
    /// @return the estimated context size in bytes
    public static long estimateDecompressContextSize() {
        try {
            return (long) Bindings.ESTIMATE_DCTX_SIZE.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a digested compression dictionary of `dictSize` bytes
    /// will use at `level`.
    ///
    /// @param dictSize the raw dictionary size in bytes
    /// @param level    the compression level to use
    /// @return the estimated digested-dictionary size in bytes
    public static long estimateCompressDictSize(long dictSize, ZstdCompressionLevel level) {
        try {
            return (long) Bindings.ESTIMATE_CDICT_SIZE.invokeExact(dictSize, level.value());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
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
            throw NativeCall.rethrow(t);
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
            throw NativeCall.rethrow(t);
        }
    }

    /// Runtime zstd version as a single number for programmatic comparison,
    /// encoded `MAJOR * 10000 + MINOR * 100 + PATCH` — e.g. `10507` for `1.5.7`.
    ///
    /// @return the linked zstd library version number
    public static int versionNumber() {
        try {
            return (int) Bindings.VERSION_NUMBER.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
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

    private Zstd() {
        // no instances
    }
}
