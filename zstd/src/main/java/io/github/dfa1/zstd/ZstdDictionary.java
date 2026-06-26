package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
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

    private static final String FIELD_NB_THREADS = "nbThreads";
    private static final String FIELD_COMPRESSION_LEVEL = "compressionLevel";

    // ZDICT_cover_params_t fields: unsigned k, d, steps, nbThreads; double
    // splitPoint; unsigned shrinkDict, shrinkDictMaxRegression; then a nested
    // ZDICT_params_t of int compressionLevel, unsigned notificationLevel, dictID.
    private static final MemoryLayout COVER_PARAMS = MemoryLayout.structLayout(
            JAVA_INT.withName("k"),
            JAVA_INT.withName("d"),
            JAVA_INT.withName("steps"),
            JAVA_INT.withName(FIELD_NB_THREADS),
            JAVA_DOUBLE.withName("splitPoint"),
            JAVA_INT.withName("shrinkDict"),
            JAVA_INT.withName("shrinkDictMaxRegression"),
            JAVA_INT.withName(FIELD_COMPRESSION_LEVEL),
            JAVA_INT.withName("notificationLevel"),
            JAVA_INT.withName("dictID"),
            MemoryLayout.paddingLayout(4)); // trailing pad to the C struct's 8-byte alignment

    // ZDICT_fastCover_params_t adds `unsigned f` after d and `unsigned accel`
    // after splitPoint; the 4-byte gap before the 8-aligned double is explicit.
    private static final MemoryLayout FASTCOVER_PARAMS = MemoryLayout.structLayout(
            JAVA_INT.withName("k"),
            JAVA_INT.withName("d"),
            JAVA_INT.withName("f"),
            JAVA_INT.withName("steps"),
            JAVA_INT.withName(FIELD_NB_THREADS),
            MemoryLayout.paddingLayout(4),
            JAVA_DOUBLE.withName("splitPoint"),
            JAVA_INT.withName("accel"),
            JAVA_INT.withName("shrinkDict"),
            JAVA_INT.withName("shrinkDictMaxRegression"),
            JAVA_INT.withName(FIELD_COMPRESSION_LEVEL),
            JAVA_INT.withName("notificationLevel"),
            JAVA_INT.withName("dictID"));

    private final byte[] bytes;

    private ZstdDictionary(byte[] bytes) {
        this.bytes = bytes;
    }

    /// Wraps existing dictionary content (e.g. trained elsewhere, or a shared
    /// dictionary shipped with your application).
    ///
    /// @param raw dictionary bytes; defensively copied
    /// @return a dictionary wrapping a copy of `raw`
    public static ZstdDictionary of(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
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
        Objects.requireNonNull(samples, "samples");
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
                throw NativeCall.rethrow(t);
            }
            if (zdictIsError(produced)) {
                throw new ZstdException("dictionary training failed: " + zdictErrorName(produced));
            }
            byte[] out = new byte[Math.toIntExact(produced)];
            MemorySegment.copy(dictBuf, JAVA_BYTE, 0, out, 0, out.length);
            return new ZstdDictionary(out);
        }
    }

    /// Trains a dictionary with the COVER algorithm, auto-tuning its parameters
    /// for the best dictionary it can find. Higher quality than {@link #train},
    /// but slower; for a faster near-equal result use {@link #trainFastCover}.
    ///
    /// @param samples      representative payloads to learn from
    /// @param maxDictBytes upper bound on the produced dictionary size
    /// @return the trained dictionary
    /// @throws ZstdException if training fails
    public static ZstdDictionary trainCover(List<byte[]> samples, int maxDictBytes) {
        return trainCover(samples, maxDictBytes, 0);
    }

    /// Trains a COVER dictionary optimised for a specific compression level.
    ///
    /// @param samples          representative payloads to learn from
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (0 = default)
    /// @return the trained dictionary
    /// @throws ZstdException if training fails
    public static ZstdDictionary trainCover(List<byte[]> samples, int maxDictBytes, int compressionLevel) {
        return optimize(samples, maxDictBytes, compressionLevel, false);
    }

    /// Trains a dictionary with the fast COVER algorithm, auto-tuning its
    /// parameters. The recommended optimiser: nearly the quality of
    /// {@link #trainCover} at a fraction of the time.
    ///
    /// @param samples      representative payloads to learn from
    /// @param maxDictBytes upper bound on the produced dictionary size
    /// @return the trained dictionary
    /// @throws ZstdException if training fails
    public static ZstdDictionary trainFastCover(List<byte[]> samples, int maxDictBytes) {
        return trainFastCover(samples, maxDictBytes, 0);
    }

    /// Trains a fast COVER dictionary optimised for a specific compression level.
    ///
    /// @param samples          representative payloads to learn from
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (0 = default)
    /// @return the trained dictionary
    /// @throws ZstdException if training fails
    public static ZstdDictionary trainFastCover(List<byte[]> samples, int maxDictBytes, int compressionLevel) {
        return optimize(samples, maxDictBytes, compressionLevel, true);
    }

    private static ZstdDictionary optimize(List<byte[]> samples, int maxDictBytes,
                                           int compressionLevel, boolean fast) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) {
            throw new ZstdException("cannot train a dictionary from zero samples");
        }
        try (Arena arena = Arena.ofConfined()) {
            long total = 0;
            for (byte[] s : samples) {
                total += s.length;
            }
            MemorySegment flat = arena.allocate(Math.max(total, 1));
            MemorySegment sizes = arena.allocate(JAVA_LONG, samples.size());
            long offset = 0;
            for (int i = 0; i < samples.size(); i++) {
                byte[] s = samples.get(i);
                MemorySegment.copy(s, 0, flat, JAVA_BYTE, offset, s.length);
                sizes.setAtIndex(JAVA_LONG, i, s.length);
                offset += s.length;
            }
            // zeroed params (auto-tune k/d/steps); set single-threaded + target level.
            MemoryLayout layout = fast ? FASTCOVER_PARAMS : COVER_PARAMS;
            MemorySegment params = arena.allocate(layout);
            params.set(JAVA_INT, layout.byteOffset(PathElement.groupElement(FIELD_NB_THREADS)), 1);
            params.set(JAVA_INT, layout.byteOffset(PathElement.groupElement(FIELD_COMPRESSION_LEVEL)), compressionLevel);
            MethodHandle handle = fast ? Bindings.ZDICT_OPTIMIZE_FASTCOVER : Bindings.ZDICT_OPTIMIZE_COVER;
            MemorySegment dictBuf = arena.allocate(maxDictBytes);
            long produced;
            try {
                produced = (long) handle.invokeExact(
                        dictBuf, (long) maxDictBytes, flat, sizes, samples.size(), params);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            if (zdictIsError(produced)) {
                throw new ZstdException("dictionary training failed: " + zdictErrorName(produced));
            }
            byte[] out = new byte[Math.toIntExact(produced)];
            MemorySegment.copy(dictBuf, JAVA_BYTE, 0, out, 0, out.length);
            return new ZstdDictionary(out);
        }
    }

    /// Turns raw dictionary `content` you supply (e.g. hand-picked common bytes,
    /// or a prefix from elsewhere) into a usable zstd dictionary by adding a
    /// header and entropy tables tuned on `samples`. Use this when you control the
    /// dictionary content and only want zstd to finalise it.
    ///
    /// @param content          the raw dictionary content to wrap
    /// @param samples          representative payloads to tune entropy tables on
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (0 = default)
    /// @return the finalised dictionary
    /// @throws ZstdException if finalisation fails
    public static ZstdDictionary finalizeFrom(byte[] content, List<byte[]> samples,
                                              int maxDictBytes, int compressionLevel) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) {
            throw new ZstdException("cannot finalise a dictionary from zero samples");
        }
        try (Arena arena = Arena.ofConfined()) {
            long total = 0;
            for (byte[] s : samples) {
                total += s.length;
            }
            MemorySegment flat = arena.allocate(Math.max(total, 1));
            MemorySegment sizes = arena.allocate(JAVA_LONG, samples.size());
            long offset = 0;
            for (int i = 0; i < samples.size(); i++) {
                byte[] s = samples.get(i);
                MemorySegment.copy(s, 0, flat, JAVA_BYTE, offset, s.length);
                sizes.setAtIndex(JAVA_LONG, i, s.length);
                offset += s.length;
            }
            MemorySegment contentSeg = Zstd.copyIn(arena, content);
            MemorySegment params = arena.allocate(Bindings.ZDICT_PARAMS_LAYOUT);
            params.set(JAVA_INT, 0, compressionLevel);  // compressionLevel; notificationLevel/dictID = 0
            MemorySegment dictBuf = arena.allocate(maxDictBytes);
            long produced;
            try {
                produced = (long) Bindings.ZDICT_FINALIZE_DICTIONARY.invokeExact(
                        dictBuf, (long) maxDictBytes, contentSeg, (long) content.length,
                        flat, sizes, samples.size(), params);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            if (zdictIsError(produced)) {
                throw new ZstdException("dictionary finalisation failed: " + zdictErrorName(produced));
            }
            byte[] out = new byte[Math.toIntExact(produced)];
            MemorySegment.copy(dictBuf, JAVA_BYTE, 0, out, 0, out.length);
            return new ZstdDictionary(out);
        }
    }

    /// The dictionary id zstd stamps into frames compressed with this dictionary,
    /// or `0` for a raw/content-only dictionary with no header.
    ///
    /// @return the dictionary id, or `0` if none
    public int id() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Zstd.copyIn(arena, bytes);
            return (int) Bindings.ZDICT_GET_DICT_ID.invokeExact(seg, (long) bytes.length);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Size of this dictionary's header — the entropy-table and id prefix that
    /// precedes the raw content.
    ///
    /// @return the header size in bytes
    /// @throws ZstdException if this is not a valid zstd dictionary
    public int headerSize() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Zstd.copyIn(arena, bytes);
            long size = (long) Bindings.ZDICT_GET_DICT_HEADER_SIZE.invokeExact(seg, (long) bytes.length);
            if (zdictIsError(size)) {
                throw new ZstdException("not a valid dictionary: " + zdictErrorName(size));
            }
            return Math.toIntExact(size);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Serialises this dictionary to a fresh byte array.
    ///
    /// @return a copy of the raw dictionary bytes, suitable for persisting
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /// The size of this dictionary.
    ///
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
            throw NativeCall.rethrow(t);
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String zdictErrorName(long code) {
        try {
            MemorySegment p = (MemorySegment) Bindings.ZDICT_GET_ERROR_NAME.invokeExact(code);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }
}
