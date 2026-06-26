package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A low-level, zero-copy streaming decompressor driven with native
/// {@link MemorySegment} buffers — no heap `byte[]` bounce.
///
/// Use this when both ends are already off-heap; for ordinary `java.io`
/// pipelines prefer [ZstdInputStream]. Feed compressed input, drain the
/// destination when it fills; a result is [ZstdStreamResult#isComplete()] when
/// the current frame is fully decoded.
public final class ZstdDecompressStream extends NativeObject {

    private final Arena arena = Arena.ofConfined();
    private final ZstdStreamBuffer in = new ZstdStreamBuffer(arena);
    private final ZstdStreamBuffer out = new ZstdStreamBuffer(arena);

    /// Creates a streaming decompressor.
    public ZstdDecompressStream() {
        super(create(null));
    }

    /// Creates a streaming decompressor for frames built with `dictionary`.
    ///
    /// @param dictionary the dictionary the frames were compressed against
    public ZstdDecompressStream(ZstdDictionary dictionary) {
        super(create(dictionary));
    }

    private static MemorySegment create(ZstdDictionary dictionary) {
        try {
            MemorySegment dctx = (MemorySegment) Bindings.CREATE_DCTX.invokeExact();
            if (MemorySegment.NULL.equals(dctx)) {
                throw new ZstdException("ZSTD_createDCtx returned NULL");
            }
            if (dictionary != null) {
                try (Arena staging = Arena.ofConfined()) {
                    byte[] raw = dictionary.raw();
                    MemorySegment d = Zstd.copyIn(staging, raw);
                    Zstd.call(() -> (long) Bindings.DCTX_LOAD_DICTIONARY.invokeExact(
                            dctx, d, (long) raw.length));
                }
            }
            return dctx;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Decompresses as much of `src` as fits into `dst` in one step.
    ///
    /// Advance the source by [ZstdStreamResult#bytesConsumed()] and read out
    /// [ZstdStreamResult#bytesProduced()] bytes of `dst` after each call. The
    /// frame is fully decoded once a result is [ZstdStreamResult#isComplete()].
    ///
    /// @param dst native destination buffer for decompressed bytes
    /// @param src native source frame bytes
    /// @return how much was consumed and produced, and the remaining hint
    /// @throws ZstdException if the frame is invalid
    public ZstdStreamResult decompress(MemorySegment dst, MemorySegment src) {
        in.set(src, src.byteSize(), 0);
        out.set(dst, dst.byteSize(), 0);
        long remaining = Zstd.call(() -> (long) Bindings.DECOMPRESS_STREAM.invokeExact(
                ptr(), out.segment(), in.segment()));
        return new ZstdStreamResult(in.pos(), out.pos(), remaining);
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            long ignored = (long) Bindings.FREE_DCTX.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
