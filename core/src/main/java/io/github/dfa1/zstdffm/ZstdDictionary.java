package io.github.dfa1.zstdffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// An immutable zstd dictionary — the feature that makes compressing many small,
/// similar payloads (log lines, JSON records, protobufs) dramatically smaller
/// than compressing each one independently.
///
/// Obtain one by {@linkplain #train(List, int) training} on representative
/// samples, or wrap dictionary bytes you already have with {@link #of(byte[])}.
/// Pass it to {@link ZstdCompressCtx} / {@link ZstdDecompressCtx} to compress and
/// decompress against it. For a hot path, digest it once into a
/// {@link ZstdCompressDict} / {@link ZstdDecompressDict}.
///
/// {@snippet :
/// ZstdDictionary dict = ZstdDictionary.train(sampleRecords, 64 * 1024);
/// try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
///     byte[] packed = ctx.compress(record, dict);
/// }
/// }
public final class ZstdDictionary {

	private final byte[] bytes;

	private ZstdDictionary(byte[] bytes) {
		this.bytes = bytes;
	}

	/// Wraps existing dictionary content (e.g. trained elsewhere, or a shared
	/// dictionary shipped with your application).
	///
	/// @param raw dictionary bytes; defensively copied
	public static ZstdDictionary of(byte[] raw) {
		return new ZstdDictionary(raw.clone());
	}

	/// Trains a dictionary from representative samples using zstd's ZDICT trainer.
	///
	/// Aim for at least a few hundred samples totalling ~100× the target
	/// dictionary size; too little data yields a weak dictionary.
	///
	/// @param samples       representative payloads to learn from
	/// @param maxDictBytes  upper bound on the produced dictionary size (e.g. 110 KiB)
	/// @return the trained dictionary
	/// @throws ZstdException if training fails (commonly: not enough sample data)
	public static ZstdDictionary train(List<byte[]> samples, int maxDictBytes) {
		if (samples.isEmpty()) {
			throw new ZstdException("cannot train a dictionary from zero samples");
		}
		long total = 0;
		for (byte[] s : samples) {
			total += s.length;
		}
		try (Arena arena = Arena.ofConfined()) {
			// flatten all samples into one buffer + a parallel size_t[] of lengths
			MemorySegment flat = arena.allocate(Math.max(total, 1));
			MemorySegment sizes = arena.allocate(JAVA_LONG, samples.size());
			long offset = 0;
			for (int i = 0; i < samples.size(); i++) {
				byte[] s = samples.get(i);
				MemorySegment.copy(s, 0, flat, JAVA_BYTE, offset, s.length);
				sizes.setAtIndex(JAVA_LONG, i, s.length);
				offset += s.length;
			}
			MemorySegment dictBuf = arena.allocate(maxDictBytes);
			long produced;
			try {
				produced = (long) Bindings.ZDICT_TRAIN.invokeExact(
						dictBuf, (long) maxDictBytes, flat, sizes, samples.size());
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (zdictIsError(produced)) {
				throw new ZstdException("dictionary training failed: " + zdictErrorName(produced));
			}
			byte[] out = new byte[Math.toIntExact(produced)];
			MemorySegment.copy(dictBuf, JAVA_BYTE, 0, out, 0, out.length);
			return new ZstdDictionary(out);
		}
	}

	/// The dictionary id zstd stamps into frames compressed with this dictionary,
	/// or {@code 0} for a raw/content-only dictionary with no header.
	public int id() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = Zstd.copyIn(arena, bytes);
			return (int) Bindings.ZDICT_GET_DICT_ID.invokeExact(seg, (long) bytes.length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/// @return a copy of the raw dictionary bytes, suitable for persisting
	public byte[] toByteArray() {
		return bytes.clone();
	}

	/// @return the dictionary size in bytes
	public int size() {
		return bytes.length;
	}

	/// Internal: direct view of the bytes for native calls. Not exposed.
	byte[] raw() {
		return bytes;
	}

	private static boolean zdictIsError(long code) {
		try {
			return ((int) Bindings.ZDICT_IS_ERROR.invokeExact(code)) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static String zdictErrorName(long code) {
		try {
			MemorySegment p = (MemorySegment) Bindings.ZDICT_GET_ERROR_NAME.invokeExact(code);
			return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	@SuppressWarnings("unchecked")
	private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
		throw (E) t;
	}
}
