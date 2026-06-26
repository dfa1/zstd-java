package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

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

    /// Parses the header of the first frame in `data` without decompressing it.
    ///
    /// @param data a complete zstd frame (or at least its header)
    /// @return the parsed frame header
    /// @throws ZstdException if the input is not a valid frame or the header is incomplete
    public static ZstdFrameHeader header(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return header(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Parses the header of the first frame in native `data`.
    ///
    /// @param data a complete zstd frame (or at least its header)
    /// @return the parsed frame header
    /// @throws ZstdException if the input is not a valid frame or the header is incomplete
    public static ZstdFrameHeader header(MemorySegment data) {
        return header(data, data.byteSize());
    }

    /// Tests whether `data` begins with a skippable frame (user data a decoder ignores).
    ///
    /// @param data the bytes to inspect
    /// @return `true` if `data` starts with a skippable frame
    public static boolean isSkippableFrame(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return isSkippableFrame(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Tests whether native `data` begins with a skippable frame.
    ///
    /// @param data the native bytes to inspect
    /// @return `true` if `data` starts with a skippable frame
    public static boolean isSkippableFrame(MemorySegment data) {
        return isSkippableFrame(data, data.byteSize());
    }

    /// Wraps `content` in a skippable frame — arbitrary metadata that a zstd
    /// decoder skips over, letting you interleave it with compressed frames.
    ///
    /// @param content      the user bytes to embed
    /// @param magicVariant the variant 0..15 selecting one of the skippable magic numbers
    /// @return the skippable frame bytes
    /// @throws ZstdException if `magicVariant` is out of range
    public static byte[] writeSkippableFrame(byte[] content, int magicVariant) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = Zstd.copyIn(arena, content);
            long cap = content.length + 8L;
            MemorySegment dst = arena.allocate(cap);
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.WRITE_SKIPPABLE_FRAME.invokeExact(
                    dst, cap, src, (long) content.length, magicVariant));
            return Zstd.copyOut(dst, written);
        }
    }

    /// Reads the content of a skippable frame produced by
    /// [#writeSkippableFrame(byte[], int)].
    ///
    /// @param frame a skippable frame
    /// @return the embedded content and its magic variant
    /// @throws ZstdException if `frame` is not a skippable frame
    public static ZstdSkippableContent readSkippableFrame(byte[] frame) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = Zstd.copyIn(arena, frame);
            MemorySegment magic = arena.allocate(JAVA_INT);
            MemorySegment dst = arena.allocate(Math.max(frame.length, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.READ_SKIPPABLE_FRAME.invokeExact(
                    dst, (long) frame.length, magic, src, (long) frame.length));
            return new ZstdSkippableContent(Zstd.copyOut(dst, written), magic.get(JAVA_INT, 0));
        }
    }

    private static ZstdFrameHeader header(MemorySegment data, long size) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment zfh = arena.allocate(48);
            long remaining = NativeCall.checkReturnValue(() -> (long) Bindings.GET_FRAME_HEADER.invokeExact(zfh, data, size));
            if (remaining != 0) {
                throw new ZstdException("incomplete frame header: need " + remaining + " more bytes");
            }
            return new ZstdFrameHeader(
                    zfh.get(JAVA_LONG, 0),                       // frameContentSize
                    zfh.get(JAVA_LONG, 8),                       // windowSize
                    zfh.get(JAVA_INT, 16) & 0xFFFFFFFFL,         // blockSizeMax
                    ZstdFrameType.of(zfh.get(JAVA_INT, 20)),     // frameType
                    zfh.get(JAVA_INT, 24),                       // headerSize
                    zfh.get(JAVA_INT, 28),                       // dictID
                    zfh.get(JAVA_INT, 32) != 0);                 // checksumFlag
        }
    }

    private static boolean isSkippableFrame(MemorySegment data, long size) {
        try {
            return ((int) Bindings.IS_SKIPPABLE_FRAME.invokeExact(data, size)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static boolean isZstdFrame(MemorySegment data, long size) {
        try {
            return ((int) Bindings.IS_FRAME.invokeExact(data, size)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static long compressedSize(MemorySegment data, long size) {
        return NativeCall.checkReturnValue(() -> (long) Bindings.FIND_FRAME_COMPRESSED_SIZE.invokeExact(data, size));
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
