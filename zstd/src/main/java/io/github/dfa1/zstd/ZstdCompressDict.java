package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A dictionary digested once for compression at a fixed level.
///
/// Building a `ZstdCompressDict` pre-processes the dictionary so that each
/// {@link ZstdCompressCtx#compress(byte[], ZstdCompressDict)} call skips that
/// cost — the right choice when compressing many payloads against the same
/// dictionary. The raw {@link ZstdDictionary} bytes are copied into native
/// memory, so the source may be discarded afterwards.
public final class ZstdCompressDict extends NativeObject {

    private final int level;

    /// Digests `dict` for compression at the given level.
    ///
    /// @param dict  the dictionary to digest
    /// @param level the compression level to fix for this digested dictionary
    public ZstdCompressDict(ZstdDictionary dict, int level) {
        super(create(dict, level));
        this.level = level;
    }

    /// Digests `dict` for compression at the library default level.
    ///
    /// @param dict the dictionary to digest
    public ZstdCompressDict(ZstdDictionary dict) {
        this(dict, Zstd.defaultCompressionLevel());
    }

    /// Digests a native dictionary segment for compression at the given level,
    /// without a heap copy.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment] — e.g. an mmap slice or
    /// an arena buffer. Its bytes are copied into the digested dictionary, so the
    /// segment may be released once the constructor returns. Heap-backed callers
    /// should use [ZstdCompressDict(ZstdDictionary, int)] instead.
    ///
    /// @param dict  native dictionary content
    /// @param level the compression level to fix for this digested dictionary
    public ZstdCompressDict(MemorySegment dict, int level) {
        super(create(dict, level));
        this.level = level;
    }

    /// Digests a native dictionary segment for compression at the library default
    /// level, without a heap copy.
    ///
    /// @param dict native dictionary content
    public ZstdCompressDict(MemorySegment dict) {
        this(dict, Zstd.defaultCompressionLevel());
    }

    private static MemorySegment create(ZstdDictionary dict, int level) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            MemorySegment d = Zstd.copyIn(arena, raw);
            MemorySegment p = (MemorySegment) Bindings.CREATE_CDICT.invokeExact(d, (long) raw.length, level);
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createCDict returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MemorySegment create(MemorySegment dict, int level) {
        Zstd.requireNative(dict, "dict");
        try {
            MemorySegment p = (MemorySegment) Bindings.CREATE_CDICT.invokeExact(dict, dict.byteSize(), level);
            if (MemorySegment.NULL.equals(p)) {
                throw new ZstdException("ZSTD_createCDict returned NULL");
            }
            return p;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// The level this dictionary was digested at.
    ///
    /// @return the fixed compression level
    public int level() {
        return level;
    }

    /// The dictionary id stamped into frames compressed with this dictionary.
    ///
    /// @return the dictionary id, or `0` for a content-only dictionary
    public int id() {
        try {
            return (int) Bindings.GET_DICT_ID_FROM_CDICT.invokeExact(ptr());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Current native memory used by this digested dictionary, in bytes.
    ///
    /// @return the live dictionary size
    public long sizeOf() {
        try {
            return (long) Bindings.SIZEOF_CDICT.invokeExact(ptr());
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_CDICT.invokeExact(ptr);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
