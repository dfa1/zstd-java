package io.github.dfa1.zstdffm;

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
    public ZstdCompressDict(ZstdDictionary dict, int level) {
        super(create(dict, level));
        this.level = level;
    }

    /// Digests `dict` for compression at the library default level.
    public ZstdCompressDict(ZstdDictionary dict) {
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

    /// The level this dictionary was digested at.
    public int level() {
        return level;
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        long ignored = (long) Bindings.FREE_CDICT.invokeExact(ptr);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
