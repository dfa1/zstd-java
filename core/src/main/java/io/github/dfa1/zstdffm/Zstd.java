package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// One-shot, stateless zstd compression and decompression over {@code byte[]}.
///
/// Every method is thread-safe and allocates its own native scratch buffers.
/// For repeated calls on a hot path, reuse a {@link ZstdCompressCtx} /
/// {@link ZstdDecompressCtx} instead to avoid re-allocating per call.
///
/// {@snippet :
/// byte[] packed   = Zstd.compress("hello world".getBytes());
/// byte[] restored = Zstd.decompress(packed);
/// }
public final class Zstd {

	/// Sentinel returned by zstd when a frame carries no decompressed-size header.
	private static final long CONTENTSIZE_UNKNOWN = -1L;
	/// Sentinel returned by zstd when the input is not a valid zstd frame.
	private static final long CONTENTSIZE_ERROR = -2L;

	/// Compresses {@code src} at the library default level.
	///
	/// @param src bytes to compress
	/// @return a self-describing zstd frame
	public static byte[] compress(byte[] src) {
		return compress(src, defaultCompressionLevel());
	}

	/// Compresses {@code src} at the given level.
	///
	/// @param src   bytes to compress
	/// @param level compression level in [{@link #minCompressionLevel()}, {@link #maxCompressionLevel()}];
	///              higher is smaller but slower
	/// @return a self-describing zstd frame
	public static byte[] compress(byte[] src, int level) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = copyIn(arena, src);
			long bound = compressBound(src.length);
			MemorySegment out = arena.allocate(bound);
			long written = call(() -> (long) Bindings.COMPRESS.invokeExact(
					out, bound, in, (long) src.length, level));
			return copyOut(out, written);
		}
	}

	/// Decompresses a complete zstd frame whose decompressed size is recorded in
	/// its header (the case for frames produced by this library).
	///
	/// @param compressed a complete zstd frame
	/// @return the original bytes
	/// @throws ZstdException if the frame is invalid or its content size is not stored;
	///                       use {@link #decompress(byte[], int)} for the latter
	public static byte[] decompress(byte[] compressed) {
		long size = frameContentSize(compressed);
		if (size == CONTENTSIZE_UNKNOWN) {
			throw new ZstdException("decompressed size not stored in frame; call decompress(src, maxSize)");
		}
		if (size == CONTENTSIZE_ERROR) {
			throw new ZstdException("not a valid zstd frame");
		}
		return decompress(compressed, Math.toIntExact(size));
	}

	/// Decompresses a zstd frame into a buffer of at most {@code maxSize} bytes.
	/// Use this when the original size is known out-of-band or not stored in the frame.
	///
	/// @param compressed a complete zstd frame
	/// @param maxSize    upper bound on the decompressed length
	/// @return the original bytes (length ≤ {@code maxSize})
	/// @throws ZstdException if the frame is invalid or larger than {@code maxSize}
	public static byte[] decompress(byte[] compressed, int maxSize) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = copyIn(arena, compressed);
			MemorySegment out = arena.allocate(Math.max(maxSize, 1));
			long written = call(() -> (long) Bindings.DECOMPRESS.invokeExact(
					out, (long) maxSize, in, (long) compressed.length));
			return copyOut(out, written);
		}
	}

	/// Maximum compressed size for an input of {@code srcSize} bytes — the buffer
	/// size guaranteed to never overflow during compression.
	public static long compressBound(long srcSize) {
		try {
			return (long) Bindings.COMPRESS_BOUND.invokeExact(srcSize);
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	/// Decompressed size stored in the frame header, or a negative sentinel.
	private static long frameContentSize(byte[] compressed) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment in = copyIn(arena, compressed);
			return (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(in, (long) compressed.length);
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	/// Highest supported compression level.
	public static int maxCompressionLevel() {
		try {
			return (int) Bindings.MAX_C_LEVEL.invokeExact();
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	/// Lowest supported compression level (negative levels trade ratio for speed).
	public static int minCompressionLevel() {
		try {
			return (int) Bindings.MIN_C_LEVEL.invokeExact();
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	/// The level used by {@link #compress(byte[])}.
	public static int defaultCompressionLevel() {
		try {
			return (int) Bindings.DEFAULT_C_LEVEL.invokeExact();
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	/// Runtime zstd version, e.g. {@code "1.6.0"}.
	public static String version() {
		try {
			MemorySegment p = (MemorySegment) Bindings.VERSION_STRING.invokeExact();
			return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	// --- package-private helpers shared with the context classes ---

	/// A native call returning a zstd {@code size_t} status that may encode an error.
	@FunctionalInterface
	interface SizeCall {
		long run() throws Throwable;
	}

	/// Invokes a size-returning zstd call and converts a zstd error code into a
	/// {@link ZstdException}.
	static long call(SizeCall c) {
		long code;
		try {
			code = c.run();
		} catch (Throwable t) {
			throw sneaky(t);
		}
		if (isError(code)) {
			throw new ZstdException(errorName(code));
		}
		return code;
	}

	private static boolean isError(long code) {
		try {
			return ((int) Bindings.IS_ERROR.invokeExact(code)) != 0;
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	private static String errorName(long code) {
		try {
			MemorySegment p = (MemorySegment) Bindings.GET_ERROR_NAME.invokeExact(code);
			return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
		} catch (Throwable t) {
			throw sneaky(t);
		}
	}

	static MemorySegment copyIn(Arena arena, byte[] src) {
		MemorySegment seg = arena.allocate(Math.max(src.length, 1));
		MemorySegment.copy(src, 0, seg, JAVA_BYTE, 0, src.length);
		return seg;
	}

	static byte[] copyOut(MemorySegment seg, long len) {
		byte[] out = new byte[Math.toIntExact(len)];
		MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
		return out;
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
		throw (E) t;
	}

	private Zstd() {
		// no instances
	}
}
