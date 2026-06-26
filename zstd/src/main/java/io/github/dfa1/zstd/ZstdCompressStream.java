package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// A low-level, zero-copy streaming compressor driven with native
/// {@link MemorySegment} buffers — no heap `byte[]` bounce.
///
/// Use this when both ends are already off-heap and you want full control over
/// buffering; for ordinary `java.io` pipelines prefer [ZstdOutputStream].
///
/// Drive it like the C API: feed a source segment, drain the destination when it
/// fills, and finish with [ZstdEndDirective#END] until [ZstdStreamResult#isComplete()].
///
/// {@snippet :
/// try (ZstdCompressStream cs = new ZstdCompressStream(level)) {
///     ZstdStreamResult r;
///     do {
///         r = cs.compress(dst, src.asSlice(srcOff), ZstdEndDirective.END);
///         srcOff += r.bytesConsumed();
///         sink.write(dst.asSlice(0, r.bytesProduced()));
///     } while (!r.isComplete());
/// }
/// }
public final class ZstdCompressStream extends NativeObject {

    private final Arena arena = Arena.ofConfined();
    private final ZstdStreamBuffer in = new ZstdStreamBuffer(arena);
    private final ZstdStreamBuffer out = new ZstdStreamBuffer(arena);

    /// Creates a streaming compressor at the default level.
    public ZstdCompressStream() {
        this(Zstd.defaultCompressionLevel());
    }

    /// Creates a streaming compressor at `level`.
    ///
    /// @param level the compression level
    public ZstdCompressStream(int level) {
        super(create(level, null));
    }

    /// Creates a streaming compressor at `level` using `dictionary`.
    ///
    /// @param level      the compression level
    /// @param dictionary the dictionary to compress against
    public ZstdCompressStream(int level, ZstdDictionary dictionary) {
        super(create(level, dictionary));
    }

    private static MemorySegment create(int level, ZstdDictionary dictionary) {
        try {
            MemorySegment cctx = (MemorySegment) Bindings.CREATE_CCTX.invokeExact();
            if (MemorySegment.NULL.equals(cctx)) {
                throw new ZstdException("ZSTD_createCCtx returned NULL");
            }
            Zstd.call(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(
                    cctx, ZstdCompressParameter.COMPRESSION_LEVEL.value(), level));
            if (dictionary != null) {
                try (Arena staging = Arena.ofConfined()) {
                    byte[] raw = dictionary.raw();
                    MemorySegment d = Zstd.copyIn(staging, raw);
                    Zstd.call(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(
                            cctx, d, (long) raw.length));
                }
            }
            return cctx;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Compresses as much of `src` as fits into `dst` in one step.
    ///
    /// Advance the source by [ZstdStreamResult#bytesConsumed()] and write out
    /// [ZstdStreamResult#bytesProduced()] bytes of `dst` after each call. With
    /// [ZstdEndDirective#END], repeat until [ZstdStreamResult#isComplete()].
    ///
    /// @param dst       native destination buffer for compressed bytes
    /// @param src       native source bytes to compress
    /// @param directive whether to continue, flush, or end the frame
    /// @return how much was consumed and produced, and the remaining hint
    /// @throws ZstdException if compression fails
    public ZstdStreamResult compress(MemorySegment dst, MemorySegment src, ZstdEndDirective directive) {
        in.set(src, src.byteSize(), 0);
        out.set(dst, dst.byteSize(), 0);
        long remaining = Zstd.call(() -> (long) Bindings.COMPRESS_STREAM2.invokeExact(
                ptr(), out.segment(), in.segment(), directive.value()));
        return new ZstdStreamResult(in.pos(), out.pos(), remaining);
    }

    /// A live snapshot of how much this stream has ingested, compressed, produced,
    /// and flushed so far — useful for progress reporting on a long stream.
    ///
    /// @return the current frame progression
    public ZstdFrameProgression progress() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) Bindings.GET_FRAME_PROGRESSION.invokeExact(
                    (SegmentAllocator) scratch, ptr());
            return new ZstdFrameProgression(
                    p.get(JAVA_LONG, 0),    // ingested
                    p.get(JAVA_LONG, 8),    // consumed
                    p.get(JAVA_LONG, 16),   // produced
                    p.get(JAVA_LONG, 24),   // flushed
                    p.get(JAVA_INT, 32),    // currentJobID
                    p.get(JAVA_INT, 36));   // nbActiveWorkers
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Current native memory used by this stream's context, in bytes.
    ///
    /// @return the live context size
    public long sizeOf() {
        try {
            return (long) Bindings.SIZEOF_CCTX.invokeExact(ptr());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            long ignored = (long) Bindings.FREE_CCTX.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
