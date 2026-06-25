package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A reusable compression context.
///
/// Reusing one context across many {@link #compress} calls amortises native
/// state allocation, making it cheaper than the stateless {@link Zstd#compress}
/// on hot paths. Not thread-safe: confine an instance to one thread or pool it.
///
/// {@snippet :
/// try (ZstdCompressCtx ctx = new ZstdCompressCtx().level(19)) {
///     for (byte[] msg : messages) {
///         sink.accept(ctx.compress(msg));
///     }
/// }
/// }
public final class ZstdCompressCtx extends NativeObject {

	private int level = Zstd.defaultCompressionLevel();

	public ZstdCompressCtx() {
		super(create());
	}

	private static MemorySegment create() {
		try {
			MemorySegment p = (MemorySegment) Bindings.CREATE_CCTX.invokeExact();
			if (MemorySegment.NULL.equals(p)) {
				throw new ZstdException("ZSTD_createCCtx returned NULL");
			}
			return p;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/// Sets the compression level for subsequent {@link #compress} calls.
	///
	/// @return {@code this}, for chaining
	public ZstdCompressCtx level(int level) {
		this.level = level;
		return this;
	}

	/// Compresses {@code src} into a new zstd frame using this context.
	public byte[] compress(byte[] src) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, src);
			long bound = Zstd.compressBound(src.length);
			MemorySegment out = arena.allocate(bound);
			long written = Zstd.call(() -> (long) Bindings.COMPRESS_CCTX.invokeExact(
					ptr(), out, bound, in, (long) src.length, level));
			return Zstd.copyOut(out, written);
		}
	}

	/// Compresses {@code src} against {@code dict} at this context's level.
	///
	/// The dictionary is re-digested on every call; for repeated compression
	/// against the same dictionary, digest it once into a {@link ZstdCompressDict}
	/// and use {@link #compress(byte[], ZstdCompressDict)}.
	public byte[] compress(byte[] src, ZstdDictionary dict) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, src);
			byte[] d = dict.raw();
			MemorySegment dseg = Zstd.copyIn(arena, d);
			long bound = Zstd.compressBound(src.length);
			MemorySegment out = arena.allocate(bound);
			long written = Zstd.call(() -> (long) Bindings.COMPRESS_USING_DICT.invokeExact(
					ptr(), out, bound, in, (long) src.length, dseg, (long) d.length, level));
			return Zstd.copyOut(out, written);
		}
	}

	/// Compresses {@code src} against a pre-digested {@code dict} (the level was
	/// fixed when the {@link ZstdCompressDict} was built).
	public byte[] compress(byte[] src, ZstdCompressDict dict) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = Zstd.copyIn(arena, src);
			long bound = Zstd.compressBound(src.length);
			MemorySegment out = arena.allocate(bound);
			MemorySegment cdict = dict.ptr();
			long written = Zstd.call(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
					ptr(), out, bound, in, (long) src.length, cdict));
			return Zstd.copyOut(out, written);
		}
	}

	/// Zero-copy compression: reads {@code src} and writes the frame straight into
	/// {@code dst}, both native {@link MemorySegment}s the caller owns. No heap
	/// {@code byte[]} bounce — hand zstd the segment addresses directly. This is
	/// the fast path when your bytes are already off-heap (e.g. an mmap slice and
	/// an arena-allocated output); see {@code docs/zero-copy.md}.
	///
	/// Size {@code dst} with {@link Zstd#compressBound(long)} to guarantee it fits.
	///
	/// @return the number of bytes written into {@code dst}
	/// @throws ZstdException if {@code dst} is too small or compression fails
	public long compress(MemorySegment dst, MemorySegment src) {
		return Zstd.call(() -> (long) Bindings.COMPRESS_CCTX.invokeExact(
				ptr(), dst, dst.byteSize(), src, src.byteSize(), level));
	}

	/// Zero-copy compression against a pre-digested {@code dict}, segment to segment.
	///
	/// @return the number of bytes written into {@code dst}
	public long compress(MemorySegment dst, MemorySegment src, ZstdCompressDict dict) {
		MemorySegment cdict = dict.ptr();
		return Zstd.call(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
				ptr(), dst, dst.byteSize(), src, src.byteSize(), cdict));
	}

	@Override
	protected void tryClose(MemorySegment ptr) throws Throwable {
		long ignored = (long) Bindings.FREE_CCTX.invokeExact(ptr);
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
		throw (E) t;
	}
}
