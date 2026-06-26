package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A dictionary digested once for decompression.
///
/// Pre-processes the dictionary so each
/// {@link ZstdDecompressCtx#decompress(byte[], int, ZstdDecompressDict)} call
/// skips that cost. The raw {@link ZstdDictionary} bytes are copied into native
/// memory, so the source may be discarded afterwards.
public final class ZstdDecompressDict extends NativeObject {

    /// Digests `dict` for decompression.
    ///
    /// @param dict the dictionary to digest
    public ZstdDecompressDict(ZstdDictionary dict) {
        super(create(dict));
    }

    /// Digests a native dictionary segment for decompression, without a heap copy.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment] — e.g. an mmap slice or
    /// an arena buffer. Its bytes are copied into the digested dictionary, so the
    /// segment may be released once the constructor returns. Heap-backed callers
    /// should use [ZstdDecompressDict(ZstdDictionary)] instead.
    ///
    /// @param dict native dictionary content
    public ZstdDecompressDict(MemorySegment dict) {
        super(create(dict));
    }

    private static MemorySegment create(ZstdDictionary dict) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            MemorySegment d = Zstd.copyIn(arena, raw);
            MemorySegment p = (MemorySegment) Bindings.CREATE_DDICT.invokeExact(d, (long) raw.length);
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createDDict returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MemorySegment create(MemorySegment dict) {
        Zstd.requireNative(dict, "dict");
        try {
            MemorySegment p = (MemorySegment) Bindings.CREATE_DDICT.invokeExact(dict, dict.byteSize());
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createDDict returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// The dictionary id this dictionary decodes frames for.
    ///
    /// @return the dictionary id, or `0` for a content-only dictionary
    public int id() {
        try {
            return (int) Bindings.GET_DICT_ID_FROM_DDICT.invokeExact(ptr());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Current native memory used by this digested dictionary, in bytes.
    ///
    /// @return the live dictionary size
    public long sizeOf() {
        try {
            return (long) Bindings.SIZEOF_DDICT.invokeExact(ptr());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        long ignored = (long) Bindings.FREE_DDICT.invokeExact(ptr);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
