package io.github.dfa1.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// A streaming zstd decompressor that reads a zstd frame from an underlying
/// {@link InputStream}.
///
/// Memory use is bounded and independent of the frame size, so this decodes
/// arbitrarily large or incrementally-arriving compressed data. For a complete
/// in-memory frame, {@link Zstd#decompress(byte[])} or {@link ZstdDecompressCtx}
/// is simpler.
///
/// Closing closes the underlying stream.
///
/// {@snippet :
/// try (ZstdInputStream zin = new ZstdInputStream(Files.newInputStream(path))) {
///     zin.transferTo(sink);
/// }
/// }
public final class ZstdInputStream extends InputStream {

    private final InputStream in;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment dctx;
    private final MemorySegment inSeg;
    private final MemorySegment outSeg;
    private final long inCap;
    private final long outCap;
    private final ZstdStreamBuffer inBuf = new ZstdStreamBuffer(arena);
    private final ZstdStreamBuffer outBufView = new ZstdStreamBuffer(arena);
    private final byte[] feed;
    private final byte[] hold;
    private final byte[] single = new byte[1];
    private int holdStart;
    private int holdEnd;
    private boolean inputEof;
    private boolean closed;
    /// zstd's "bytes still expected" hint from the last decompressStream call;
    /// non-zero at EOF means the final frame was truncated.
    private long lastHint;

    /// Wraps `in`, decompressing the zstd frame it carries.
    ///
    /// @param in the stream to read the compressed frame from
    public ZstdInputStream(InputStream in) {
        this(in, null);
    }

    /// Wraps `in`, decompressing a frame compressed against `dictionary`.
    ///
    /// @param in         the stream to read the compressed frame from
    /// @param dictionary the dictionary the frame was compressed against, or `null` for none
    public ZstdInputStream(InputStream in, ZstdDictionary dictionary) {
        this.in = in;
        try {
            this.dctx = (MemorySegment) Bindings.CREATE_DCTX.invokeExact();
            if (MemorySegment.NULL.equals(dctx)) {
                throw new ZstdException("ZSTD_createDCtx returned NULL");
            }
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
            this.inCap = (long) Bindings.DSTREAM_IN_SIZE.invokeExact();
            this.outCap = (long) Bindings.DSTREAM_OUT_SIZE.invokeExact();
        } catch (Throwable t) {
            arena.close();
            throw rethrow(t);
        }
        this.inSeg = arena.allocate(inCap);
        this.outSeg = arena.allocate(outCap);
        this.feed = new byte[Math.toIntExact(inCap)];
        this.hold = new byte[Math.toIntExact(outCap)];
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment dictSeg = Zstd.copyIn(staging, raw);
            Zstd.call(() -> (long) Bindings.DCTX_LOAD_DICTIONARY.invokeExact(
                    dctx, dictSeg, (long) raw.length));
        }
    }

    @Override
    public int read() throws IOException {
        int n = read(single, 0, 1);
        return n == -1 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (holdStart == holdEnd && !produce()) {
            return -1;
        }
        int n = Math.min(len, holdEnd - holdStart);
        System.arraycopy(hold, holdStart, b, off, n);
        holdStart += n;
        return n;
    }

    /// Decodes the next slice of output into `hold`. Returns false at end of stream.
    private boolean produce() throws IOException {
        while (true) {
            if (inBuf.pos() == inBuf.size()) {
                int r = inputEof ? -1 : in.read(feed);
                if (r == -1) {
                    inputEof = true;
                    // The frame boundary is clean only when the last decompressStream
                    // call reported nothing outstanding; otherwise the stream was cut
                    // mid-frame and the remaining bytes are lost.
                    if (lastHint != 0) {
                        throw new ZstdException("truncated zstd stream: " + lastHint
                                + " more input byte(s) expected");
                    }
                    return false;
                }
                MemorySegment.copy(feed, 0, inSeg, JAVA_BYTE, 0, r);
                inBuf.set(inSeg, r, 0);
            }
            outBufView.set(outSeg, outCap, 0);
            lastHint = Zstd.call(() -> (long) Bindings.DECOMPRESS_STREAM.invokeExact(
                    dctx, outBufView.segment(), inBuf.segment()));
            int produced = Math.toIntExact(outBufView.pos());
            if (produced > 0) {
                MemorySegment.copy(outSeg, JAVA_BYTE, 0, hold, 0, produced);
                holdStart = 0;
                holdEnd = produced;
                return true;
            }
            // Nothing produced. If the decoder neither advanced its input nor wants
            // more, it cannot make progress on this input — stop to avoid spinning.
            if (inBuf.pos() == inBuf.size()) {
                if (inputEof) {
                    if (lastHint != 0) {
                        throw new ZstdException("truncated zstd stream: " + lastHint
                                + " more input byte(s) expected");
                    }
                    return false;
                }
                // input drained but frame wants more: loop to refill from `in`.
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            long ignored = (long) Bindings.FREE_DCTX.invokeExact(dctx);
        } catch (Throwable ignored) {
            // best-effort free
        }
        arena.close();
        in.close();
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
