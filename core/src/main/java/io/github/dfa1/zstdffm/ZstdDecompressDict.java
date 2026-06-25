package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A dictionary digested once for decompression.
///
/// Pre-processes the dictionary so each
/// {@link ZstdDecompressCtx#decompress(byte[], int, ZstdDecompressDict)} call
/// skips that cost. The raw {@link ZstdDictionary} bytes are copied into native
/// memory, so the source may be discarded afterwards.
public final class ZstdDecompressDict extends NativeObject {

	/// Digests {@code dict} for decompression.
	public ZstdDecompressDict(ZstdDictionary dict) {
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

	@Override
	protected void tryClose(MemorySegment ptr) throws Throwable {
		long ignored = (long) Bindings.FREE_DDICT.invokeExact(ptr);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
		throw (E) t;
	}
}
