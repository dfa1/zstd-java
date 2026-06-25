package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// Inspection of zstd frames without decompressing them — useful for parsing a
/// stream of concatenated frames, sizing buffers, or routing by dictionary id.
///
/// Each method has a `byte[]` form and a zero-copy {@link MemorySegment} form for
/// data already off-heap (e.g. an mmap slice); see `docs/zero-copy.md`.
public final class ZstdFrame {

    /// Sentinel returned by `ZSTD_decompressBound` when the input is not valid.
    private static final long CONTENTSIZE_ERROR = -2L;

    /// Tests whether `data` begins with a valid zstd frame (standard or skippable).
    ///
    /// @param data the bytes to inspect
    /// @return `true` if `data` starts with a zstd frame
    public static boolean isZstdFrame(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return isZstdFrame(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Tests whether the native `data` begins with a valid zstd frame.
    ///
    /// @param data the native bytes to inspect
    /// @return `true` if `data` starts with a zstd frame
    public static boolean isZstdFrame(MemorySegment data) {
        return isZstdFrame(data, data.byteSize());
    }

    /// Compressed size of the first frame in `data`, including its header and
    /// epilogue — i.e. where the next frame would begin.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the byte length of the first frame
    /// @throws ZstdException if the input is not a valid frame
    public static long compressedSize(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return compressedSize(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Compressed size of the first frame in native `data`.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the byte length of the first frame
    /// @throws ZstdException if the input is not a valid frame
    public static long compressedSize(MemorySegment data) {
        return compressedSize(data, data.byteSize());
    }

    /// Upper bound on the total decompressed size of all frames in `data`,
    /// large enough to never overflow during decompression.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return an upper bound on the combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data
    public static long decompressedBound(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return decompressedBound(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Upper bound on the total decompressed size of all frames in native `data`.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return an upper bound on the combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data
    public static long decompressedBound(MemorySegment data) {
        return decompressedBound(data, data.byteSize());
    }

    /// Dictionary id recorded in the first frame's header.
    ///
    /// @param data a zstd frame
    /// @return the dictionary id, or `0` if the frame uses no dictionary or does
    ///         not record one
    public static int dictId(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return dictId(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Dictionary id recorded in native frame `data`.
    ///
    /// @param data a zstd frame
    /// @return the dictionary id, or `0` if none
    public static int dictId(MemorySegment data) {
        return dictId(data, data.byteSize());
    }

    private static boolean isZstdFrame(MemorySegment data, long size) {
        try {
            return ((int) Bindings.IS_FRAME.invokeExact(data, size)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static long compressedSize(MemorySegment data, long size) {
        return Zstd.call(() -> (long) Bindings.FIND_FRAME_COMPRESSED_SIZE.invokeExact(data, size));
    }

    private static long decompressedBound(MemorySegment data, long size) {
        long bound;
        try {
            bound = (long) Bindings.DECOMPRESS_BOUND.invokeExact(data, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (bound == CONTENTSIZE_ERROR) {
            throw new ZstdException("not valid zstd data");
        }
        return bound;
    }

    private static int dictId(MemorySegment data, long size) {
        try {
            return (int) Bindings.GET_DICT_ID_FROM_FRAME.invokeExact(data, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    private ZstdFrame() {
        // no instances
    }
}
