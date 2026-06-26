package io.github.dfa1.zstd;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// A streaming zstd compressor that writes a single zstd frame to an underlying
/// {@link OutputStream}.
///
/// Memory use is bounded and independent of the total payload size, so this is
/// the way to compress data that does not fit in memory or arrives incrementally
/// (files, sockets, serialization). For a whole in-memory payload of known size,
/// {@link Zstd#compress(byte[])} or {@link ZstdCompressCtx} is simpler.
///
/// Closing finishes the frame and closes the underlying stream.
///
/// {@snippet :
/// try (ZstdOutputStream zout = new ZstdOutputStream(Files.newOutputStream(path), 19)) {
///     source.transferTo(zout);
/// }
/// }
public final class ZstdOutputStream extends OutputStream {

    // ZSTD_cParameter / ZSTD_EndDirective values from zstd.h — see
    // https://facebook.github.io/zstd/doc/api_manual_latest.html
    private static final int ZSTD_C_COMPRESSION_LEVEL = 100;
    private static final int ZSTD_E_CONTINUE = 0;
    private static final int ZSTD_E_FLUSH = 1;
    private static final int ZSTD_E_END = 2;

    private final OutputStream out;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment cctx;
    private final MemorySegment inSeg;
    private final MemorySegment outSeg;
    private final long inCap;
    private final long outCap;
    private final ZstdStreamBuffer in = new ZstdStreamBuffer(arena);
    private final ZstdStreamBuffer outBuf = new ZstdStreamBuffer(arena);
    private final byte[] drain;
    private boolean closed;

    /// Wraps `out`, compressing at the library default level.
    ///
    /// @param out the stream to write the compressed frame to
    public ZstdOutputStream(OutputStream out) {
        this(out, Zstd.defaultCompressionLevel());
    }

    /// Wraps `out`, compressing at `level`.
    ///
    /// @param out   the stream to write the compressed frame to
    /// @param level the compression level
    public ZstdOutputStream(OutputStream out, int level) {
        this(out, level, null);
    }

    /// Wraps `out`, compressing against `dictionary` at the default level.
    ///
    /// @param out        the stream to write the compressed frame to
    /// @param dictionary the dictionary to compress against
    public ZstdOutputStream(OutputStream out, ZstdDictionary dictionary) {
        this(out, Zstd.defaultCompressionLevel(), dictionary);
    }

    /// Wraps `out` and declares the exact total number of bytes that will be
    /// written, so the frame header records the decompressed size — letting a
    /// reader use [Zstd#decompress(byte[])] (size known) instead of supplying a
    /// bound. Writing a different total raises an error when the stream closes.
    ///
    /// @param out            the stream to write the compressed frame to
    /// @param level          the compression level
    /// @param pledgedSrcSize the exact number of uncompressed bytes that will be written
    /// @return a stream that will stamp the content size into the frame
    public static ZstdOutputStream withPledgedSize(OutputStream out, int level, long pledgedSrcSize) {
        ZstdOutputStream stream = new ZstdOutputStream(out, level);
        stream.setPledgedSrcSize(pledgedSrcSize);
        return stream;
    }

    private void setPledgedSrcSize(long pledgedSrcSize) {
        Zstd.call(() -> (long) Bindings.CCTX_SET_PLEDGED_SRC_SIZE.invokeExact(cctx, pledgedSrcSize));
    }

    /// Wraps `out`, compressing against `dictionary` at `level`.
    ///
    /// @param out        the stream to write the compressed frame to
    /// @param level      the compression level
    /// @param dictionary the dictionary to compress against, or `null` for none
    public ZstdOutputStream(OutputStream out, int level, ZstdDictionary dictionary) {
        this.out = out;
        try {
            this.cctx = (MemorySegment) Bindings.CREATE_CCTX.invokeExact();
            if (MemorySegment.NULL.equals(cctx)) {
                throw new ZstdException("ZSTD_createCCtx returned NULL");
            }
            Zstd.call(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(
                    cctx, ZSTD_C_COMPRESSION_LEVEL, level));
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
            this.inCap = (long) Bindings.CSTREAM_IN_SIZE.invokeExact();
            this.outCap = (long) Bindings.CSTREAM_OUT_SIZE.invokeExact();
        } catch (Throwable t) {
            arena.close();
            throw rethrow(t);
        }
        this.inSeg = arena.allocate(inCap);
        this.outSeg = arena.allocate(outCap);
        this.drain = new byte[Math.toIntExact(outCap)];
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment dictSeg = Zstd.copyIn(staging, raw);
            Zstd.call(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(
                    cctx, dictSeg, (long) raw.length));
        }
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        int pos = off;
        int remaining = len;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, inCap);
            MemorySegment.copy(b, pos, inSeg, JAVA_BYTE, 0, chunk);
            in.set(inSeg, chunk, 0);
            do {
                drainOutput(ZSTD_E_CONTINUE);
            } while (in.pos() < chunk);
            pos += chunk;
            remaining -= chunk;
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        in.set(inSeg, 0, 0);
        long remainingHint;
        do {
            remainingHint = drainOutput(ZSTD_E_FLUSH);
        } while (remainingHint != 0);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            in.set(inSeg, 0, 0);
            long remainingHint;
            do {
                remainingHint = drainOutput(ZSTD_E_END);
            } while (remainingHint != 0);
            out.flush();
        } finally {
            closed = true;
            try {
                long ignored = (long) Bindings.FREE_CCTX.invokeExact(cctx);
            } catch (Throwable ignored) {
                // best-effort free
            }
            arena.close();
            out.close();
        }
    }

    /// Runs one compressStream2 call and writes whatever it produced to `out`.
    /// Returns the zstd "remaining" hint (0 means the directive is fully flushed).
    private long drainOutput(int directive) throws IOException {
        outBuf.set(outSeg, outCap, 0);
        long remainingHint = Zstd.call(() -> (long) Bindings.COMPRESS_STREAM2.invokeExact(
                cctx, outBuf.segment(), in.segment(), directive));
        int produced = Math.toIntExact(outBuf.pos());
        if (produced > 0) {
            MemorySegment.copy(outSeg, JAVA_BYTE, 0, drain, 0, produced);
            out.write(drain, 0, produced);
        }
        return remainingHint;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
