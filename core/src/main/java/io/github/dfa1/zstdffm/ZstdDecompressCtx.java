package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A reusable decompression context.
///
/// Reusing one context across many {@link #decompress} calls amortises native
/// state allocation. Not thread-safe: confine an instance to one thread or pool it.
public final class ZstdDecompressCtx extends NativeObject {

	public ZstdDecompressCtx() {
		super(create());
	}

	private static MemorySegment create() {
		try {
			MemorySegment p = (MemorySegment) Bindings.CREATE_DCTX.invokeExact();
			if (MemorySegment.NULL.equals(p)) {
				throw new ZstdException("ZSTD_createDCtx returned NULL");
			}
			return p;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/// Decompresses a frame into a buffer of at most {@code maxSize} bytes.
	public byte[] decompress(byte[] compressed, int maxSize) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, compressed);
			MemorySegment out = arena.allocate(Math.max(maxSize, 1));
			long written = Zstd.call(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
					ptr(), out, (long) maxSize, in, (long) compressed.length));
			return Zstd.copyOut(out, written);
		}
	}

	/// Decompresses a frame that was compressed against {@code dict}.
	///
	/// The dictionary is re-digested on every call; for repeated use digest it
	/// once into a {@link ZstdDecompressDict} and use
	/// {@link #decompress(byte[], int, ZstdDecompressDict)}.
	public byte[] decompress(byte[] compressed, int maxSize, ZstdDictionary dict) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, compressed);
			byte[] d = dict.raw();
			MemorySegment dseg = Zstd.copyIn(arena, d);
			MemorySegment out = arena.allocate(Math.max(maxSize, 1));
			long written = Zstd.call(() -> (long) Bindings.DECOMPRESS_USING_DICT.invokeExact(
					ptr(), out, (long) maxSize, in, (long) compressed.length, dseg, (long) d.length));
			return Zstd.copyOut(out, written);
		}
	}

	/// Decompresses a frame against a pre-digested {@code dict}.
	public byte[] decompress(byte[] compressed, int maxSize, ZstdDecompressDict dict) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, compressed);
			MemorySegment out = arena.allocate(Math.max(maxSize, 1));
			MemorySegment ddict = dict.ptr();
			long written = Zstd.call(() -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
					ptr(), out, (long) maxSize, in, (long) compressed.length, ddict));
			return Zstd.copyOut(out, written);
		}
	}

	/// Zero-copy decompression: reads the frame from {@code src} and writes the
	/// result straight into {@code dst}, both native {@link MemorySegment}s the
	/// caller owns. No heap {@code byte[]} bounce — the segment addresses go
	/// directly to zstd. This is the fast path when input is an mmap slice and
	/// output is an arena buffer that becomes the materialized array as-is;
	/// see {@code docs/zero-copy.md}.
	///
	/// Size {@code dst} to the decompressed length (read it from the frame with
	/// {@link Zstd#decompress(byte[])}'s header logic, or known out-of-band).
	///
	/// @return the number of bytes written into {@code dst}
	/// @throws ZstdException if {@code dst} is too small or the frame is invalid
	public long decompress(MemorySegment dst, MemorySegment src) {
		return Zstd.call(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
				ptr(), dst, dst.byteSize(), src, src.byteSize()));
	}

	/// Zero-copy decompression against a pre-digested {@code dict}, segment to segment.
	///
	/// @return the number of bytes written into {@code dst}
	public long decompress(MemorySegment dst, MemorySegment src, ZstdDecompressDict dict) {
		MemorySegment ddict = dict.ptr();
		return Zstd.call(() -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
				ptr(), dst, dst.byteSize(), src, src.byteSize(), ddict));
	}

	@Override
	protected void tryClose(MemorySegment ptr) throws Throwable {
		long ignored = (long) Bindings.FREE_DCTX.invokeExact(ptr);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
		throw (E) t;
	}
}
