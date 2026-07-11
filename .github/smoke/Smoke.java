///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
// Dependencies are supplied at run time via `jbang --deps ...` so the workflow
// can pin the released version and the per-arch native jar from the matrix.

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdBounds;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdCompressParameter;
import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressParameter;
import io.github.dfa1.zstd.ZstdDecompressStream;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdDictionaryId;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdErrorCode;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdFrameHeader;
import io.github.dfa1.zstd.ZstdFrameProgression;
import io.github.dfa1.zstd.ZstdFrameType;
import io.github.dfa1.zstd.ZstdInputStream;
import io.github.dfa1.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdResetDirective;
import io.github.dfa1.zstd.ZstdSkippableContent;
import io.github.dfa1.zstd.ZstdStreamResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// Smoke test for a published zstd-java release: proves the bundled native
/// library loads on the host OS/arch and every public entry point works.
/// Run against a release from Maven Central, one arch per CI matrix leg.
///
/// Deliberately excludes multithreaded compression (`NB_WORKERS` > 0): the
/// bundled native library is single-threaded, so exercising it would only
/// prove the parameter is a no-op, not that threading works.
public class Smoke {

    private static final String PLATFORM = System.getProperty("os.name") + "/" + System.getProperty("os.arch");

    public static void main(String[] args) throws IOException {
        versionAndSizing();
        coreRoundTrip();
        explicitBoundDecompress();
        zeroCopyFrameSize();
        corruptAndInvalidInput();
        frameIntrospection();
        skippableFrames();
        compressContextAdvancedParameters();
        decompressContextAdvanced();
        prefixCompression();
        parameterBounds();
        errorCodeDescriptions();

        List<byte[]> samples = jsonSamples();
        ZstdDictionary dict = ZstdDictionary.train(samples, 8 * 1024);
        byte[] message = samples.get(7);

        dictionaryIdApi(dict);
        dictionaryTrainingVariants(samples);
        contextDictionaryPaths(dict, message);
        digestedDictionaries(dict, message);
        zeroCopyContextCompression(dict, message);
        streamingZeroCopy(dict, message);
        streamingIo(dict, message);

        System.out.println("OK " + PLATFORM + " | zstd " + Zstd.version() + " | all smoke checks passed");
    }

    private static void versionAndSizing() {
        check(!Zstd.version().isBlank(), "version() returned blank");
        check(Zstd.versionNumber() > 0, "versionNumber() not positive");

        int min = Zstd.minCompressionLevel();
        int max = Zstd.maxCompressionLevel();
        int def = Zstd.defaultCompressionLevel();
        check(min < max, "minCompressionLevel() >= maxCompressionLevel()");
        check(def >= min && def <= max, "defaultCompressionLevel() out of [min,max]");

        check(Zstd.compressBound(1000) >= 1000, "compressBound() below input size");
        check(Zstd.estimateCompressContextSize(def) > 0, "estimateCompressContextSize() not positive");
        check(Zstd.estimateDecompressContextSize() > 0, "estimateDecompressContextSize() not positive");
        check(Zstd.estimateCompressDictSize(4096, def) > 0, "estimateCompressDictSize() not positive");
        check(Zstd.estimateDecompressDictSize(4096) > 0, "estimateDecompressDictSize() not positive");
    }

    private static void coreRoundTrip() {
        byte[] original = sampleText();

        byte[] compressedDefault = Zstd.compress(original);
        check(Arrays.equals(original, Zstd.decompress(compressedDefault)), "compress(byte[]) round-trip mismatch");

        byte[] compressed = Zstd.compress(original, Zstd.maxCompressionLevel());
        check(Arrays.equals(original, Zstd.decompress(compressed)), "compress(byte[], level) round-trip mismatch");
        check(compressed.length < original.length, "expected compression to shrink the input");
    }

    private static void explicitBoundDecompress() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);

        byte[] restored = Zstd.decompress(compressed, original.length);
        check(Arrays.equals(original, restored), "decompress(byte[], maxSize) mismatch");

        try {
            Zstd.decompress(compressed, 1);
            throw new AssertionError("expected ZstdException for an undersized maxSize on " + PLATFORM);
        } catch (ZstdException expected) {
            check(expected.code() == ZstdErrorCode.DST_SIZE_TOO_SMALL,
                    "expected DST_SIZE_TOO_SMALL, got " + expected.code());
        }
    }

    private static void zeroCopyFrameSize() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment frame = toNative(arena, compressed);
            check(Zstd.decompressedSize(frame) == original.length, "decompressedSize(MemorySegment) mismatch");
        }
    }

    private static void corruptAndInvalidInput() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = new ZstdCompressContext().checksum(true)) {
            byte[] framed = cctx.compress(original);
            byte[] corrupted = framed.clone();
            // The last byte is part of the 4-byte content checksum trailer, so
            // flipping it corrupts the checksum itself rather than the payload —
            // deterministically triggers CHECKSUM_WRONG regardless of how the
            // payload happens to compress on a given platform.
            corrupted[corrupted.length - 1] ^= 0xFF;
            try {
                Zstd.decompress(corrupted, original.length);
                throw new AssertionError("expected ZstdException for a corrupted checksum on " + PLATFORM);
            } catch (ZstdException expected) {
                check(expected.code() == ZstdErrorCode.CHECKSUM_WRONG,
                        "expected CHECKSUM_WRONG, got " + expected.code());
            }
        }

        try {
            Zstd.decompress("not a zstd frame".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("expected ZstdException for non-zstd input on " + PLATFORM);
        } catch (ZstdException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(),
                    "ZstdException message was blank for non-zstd input");
        }
    }

    private static void frameIntrospection() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);

        check(ZstdFrame.isZstdFrame(compressed), "isZstdFrame(byte[]) false for a real frame");
        check(!ZstdFrame.isZstdFrame("plain text, not zstd".getBytes(StandardCharsets.UTF_8)),
                "isZstdFrame(byte[]) true for non-zstd data");
        check(ZstdFrame.compressedSize(compressed) == compressed.length, "compressedSize(byte[]) mismatch");
        check(ZstdFrame.decompressedSize(compressed) == original.length, "decompressedSize(byte[]) mismatch");
        check(ZstdFrame.decompressedBound(compressed) >= original.length, "decompressedBound(byte[]) too small");
        check(ZstdFrame.decompressionMargin(compressed) >= 0, "decompressionMargin(byte[]) negative");
        check(!ZstdFrame.dictId(compressed).isPresent(), "dictId(byte[]) expected NONE for a non-dictionary frame");
        check(!ZstdFrame.isSkippableFrame(compressed), "isSkippableFrame(byte[]) true for a standard frame");

        long headerSize = ZstdFrame.headerSize(compressed);
        check(headerSize > 0 && headerSize <= compressed.length, "headerSize(byte[]) out of range");

        ZstdFrameHeader header = ZstdFrame.header(compressed);
        check(header.contentSize().isPresent() && header.contentSize().getAsLong() == original.length,
                "header(byte[]).contentSize() mismatch");
        check(header.frameType() == ZstdFrameType.STANDARD, "header(byte[]).frameType() expected STANDARD");
        check(header.headerSize() == headerSize, "header(byte[]).headerSize() disagreed with headerSize(byte[])");
        check(!header.hasChecksum(), "header(byte[]).hasChecksum() expected false (checksum not enabled)");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = toNative(arena, compressed);
            check(ZstdFrame.isZstdFrame(seg), "isZstdFrame(MemorySegment) false for a real frame");
            check(ZstdFrame.compressedSize(seg) == compressed.length, "compressedSize(MemorySegment) mismatch");
            check(ZstdFrame.decompressedSize(seg) == original.length, "decompressedSize(MemorySegment) mismatch");
            check(ZstdFrame.decompressedBound(seg) >= original.length, "decompressedBound(MemorySegment) too small");
            check(ZstdFrame.decompressionMargin(seg) >= 0, "decompressionMargin(MemorySegment) negative");
            check(!ZstdFrame.dictId(seg).isPresent(), "dictId(MemorySegment) expected NONE");
            check(ZstdFrame.headerSize(seg) == headerSize, "headerSize(MemorySegment) disagreed with byte[] form");
            check(ZstdFrame.header(seg).equals(header), "header(MemorySegment) disagreed with byte[] form");
            check(!ZstdFrame.isSkippableFrame(seg), "isSkippableFrame(MemorySegment) true for a standard frame");
        }
    }

    private static void skippableFrames() {
        byte[] payload = "smoke-test skippable payload".getBytes(StandardCharsets.UTF_8);
        byte[] frame = ZstdFrame.writeSkippableFrame(payload, 3);

        check(ZstdFrame.isSkippableFrame(frame), "isSkippableFrame(byte[]) false for a written skippable frame");
        check(ZstdFrame.isZstdFrame(frame), "isZstdFrame(byte[]) should be true for a skippable frame too");

        ZstdSkippableContent read = ZstdFrame.readSkippableFrame(frame);
        check(Arrays.equals(read.content(), payload), "readSkippableFrame() content mismatch");
        check(read.magicVariant() == 3, "readSkippableFrame() magicVariant mismatch");
        ZstdSkippableContent same = new ZstdSkippableContent(payload, 3);
        check(read.equals(same), "ZstdSkippableContent.equals() mismatch");
        check(read.hashCode() == same.hashCode(), "ZstdSkippableContent.hashCode() mismatch");
    }

    private static void compressContextAdvancedParameters() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            cctx.level(5)
                    .checksum(true)
                    .longDistanceMatching(true)
                    .windowLog(20)
                    .parameter(ZstdCompressParameter.STRATEGY, 3);

            byte[] compressed = cctx.compress(original);
            check(Arrays.equals(original, Zstd.decompress(compressed, original.length)),
                    "advanced-parameter round-trip mismatch");
            check(cctx.sizeOf() > 0, "cctx.sizeOf() not positive");

            cctx.reset(ZstdResetDirective.SESSION_ONLY);
            byte[] afterSessionReset = cctx.compress(original);
            check(Arrays.equals(original, Zstd.decompress(afterSessionReset, original.length)),
                    "compress after SESSION_ONLY reset mismatch");

            cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
            byte[] afterFullReset = cctx.level(3).compress(original);
            check(Arrays.equals(original, Zstd.decompress(afterFullReset, original.length)),
                    "compress after SESSION_AND_PARAMETERS reset mismatch");
        }
    }

    private static void decompressContextAdvanced() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = new ZstdCompressContext().windowLog(23);
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            dctx.windowLogMax(24);
            byte[] restored = dctx.decompress(cctx.compress(original), original.length);
            check(Arrays.equals(original, restored), "windowLogMax() decompress round-trip mismatch");
            check(dctx.sizeOf() > 0, "dctx.sizeOf() not positive");

            dctx.reset(ZstdResetDirective.SESSION_ONLY);
            byte[] restoredAfterSessionReset = dctx.decompress(cctx.compress(original), original.length);
            check(Arrays.equals(original, restoredAfterSessionReset),
                    "decompress after SESSION_ONLY reset mismatch");

            dctx.reset(ZstdResetDirective.PARAMETERS);
            dctx.parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, 24);
            byte[] restoredAfterParamReset = dctx.decompress(cctx.compress(original), original.length);
            check(Arrays.equals(original, restoredAfterParamReset),
                    "decompress after PARAMETERS reset + parameter() mismatch");
        }
    }

    private static void prefixCompression() {
        byte[] previousVersion = sampleText();
        byte[] newVersion = (new String(previousVersion, StandardCharsets.UTF_8) + " and a tiny delta at the end")
                .getBytes(StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            MemorySegment prefix = toNative(arena, previousVersion);

            cctx.refPrefix(prefix);
            byte[] delta = cctx.compress(newVersion);
            check(delta.length < newVersion.length, "prefix-compressed delta not smaller than the input");

            dctx.refPrefix(prefix);
            byte[] restored = dctx.decompress(delta, newVersion.length);
            check(Arrays.equals(newVersion, restored), "prefix compression round-trip mismatch");

            // A prefix is single-use and already consumed by the calls above;
            // clear it explicitly to exercise the null-clearing path too.
            cctx.refPrefix(MemorySegment.NULL);
            dctx.refPrefix(null);
        }
    }

    private static void parameterBounds() {
        for (ZstdCompressParameter parameter : ZstdCompressParameter.values()) {
            ZstdBounds bounds = parameter.bounds();
            check(bounds.lowerBound() <= bounds.upperBound(),
                    "ZstdCompressParameter." + parameter + ".bounds() has lower > upper");
        }
        for (ZstdDecompressParameter parameter : ZstdDecompressParameter.values()) {
            ZstdBounds bounds = parameter.bounds();
            check(bounds.lowerBound() <= bounds.upperBound(),
                    "ZstdDecompressParameter." + parameter + ".bounds() has lower > upper");
        }
    }

    private static void errorCodeDescriptions() {
        for (ZstdErrorCode code : ZstdErrorCode.values()) {
            String description = code.description();
            check(description != null && !description.isBlank(),
                    "ZstdErrorCode." + code + ".description() was blank");
        }
    }

    private static void dictionaryIdApi(ZstdDictionary dict) {
        check(!ZstdDictionaryId.NONE.isPresent(), "NONE.isPresent() should be false");
        check(ZstdDictionaryId.of(0).equals(ZstdDictionaryId.NONE), "of(0) should equal NONE");
        ZstdDictionaryId id = ZstdDictionaryId.of(42);
        check(id.isPresent(), "of(42).isPresent() should be true");
        check(id.value() == 42L, "of(42).value() mismatch");

        byte[] raw = dict.toByteArray();
        ZstdDictionaryId fromBytes = Zstd.dictId(raw);
        check(fromBytes.isPresent(), "Zstd.dictId(byte[]) expected a present id for a trained dictionary");
        check(fromBytes.equals(dict.id()), "Zstd.dictId(byte[]) disagreed with ZstdDictionary.id()");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = toNative(arena, raw);
            check(Zstd.dictId(seg).equals(fromBytes), "Zstd.dictId(MemorySegment) disagreed with Zstd.dictId(byte[])");
        }
    }

    private static void dictionaryTrainingVariants(List<byte[]> samples) {
        ZstdDictionary trained = ZstdDictionary.train(samples, 8 * 1024);
        check(trained.size() > 0, "train() produced an empty dictionary");
        check(trained.headerSize() > 0, "headerSize() not positive");
        check(trained.id().isPresent(), "train().id() expected a present dictionary id");

        check(ZstdDictionary.trainCover(samples, 8 * 1024).size() > 0, "trainCover() produced an empty dictionary");
        check(ZstdDictionary.trainCover(samples, 8 * 1024, 5).size() > 0,
                "trainCover(level) produced an empty dictionary");
        check(ZstdDictionary.trainFastCover(samples, 8 * 1024).size() > 0,
                "trainFastCover() produced an empty dictionary");
        check(ZstdDictionary.trainFastCover(samples, 8 * 1024, 5).size() > 0,
                "trainFastCover(level) produced an empty dictionary");

        byte[] content = samples.get(0);
        ZstdDictionary finalized = ZstdDictionary.finalizeFrom(content, samples, 8 * 1024, 5);
        check(finalized.size() > 0, "finalizeFrom() produced an empty dictionary");

        ZstdDictionary wrapped = ZstdDictionary.of(trained.toByteArray());
        check(Arrays.equals(wrapped.toByteArray(), trained.toByteArray()), "of(byte[]) did not preserve the bytes");
    }

    private static void contextDictionaryPaths(ZstdDictionary dict, byte[] message) {
        try (ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            byte[] compressed = cctx.compress(message, dict);
            byte[] restored = dctx.decompress(compressed, message.length, dict);
            check(Arrays.equals(message, restored), "per-call dictionary round-trip mismatch");

            cctx.loadDictionary(dict);
            dctx.loadDictionary(dict);
            byte[] compressedSticky = cctx.compress(message);
            byte[] restoredSticky = dctx.decompress(compressedSticky, message.length);
            check(Arrays.equals(message, restoredSticky), "sticky-dictionary round-trip mismatch");

            cctx.loadDictionary((ZstdDictionary) null);
            dctx.loadDictionary((ZstdDictionary) null);
        }

        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            MemorySegment dictSeg = toNative(arena, dict.toByteArray());
            cctx.loadDictionary(dictSeg);
            dctx.loadDictionary(dictSeg);
            byte[] compressed = cctx.compress(message);
            byte[] restored = dctx.decompress(compressed, message.length);
            check(Arrays.equals(message, restored), "MemorySegment sticky-dictionary round-trip mismatch");

            cctx.loadDictionary(MemorySegment.NULL);
            dctx.loadDictionary(MemorySegment.NULL);
        }
    }

    private static void digestedDictionaries(ZstdDictionary dict, byte[] message) {
        try (ZstdCompressDictionary cdict = dict.compressDict(9);
             ZstdDecompressDictionary ddict = dict.decompressDict()) {
            check(cdict.level() == 9, "compressDict(level).level() mismatch");
            check(cdict.id().equals(dict.id()), "compressDict().id() disagreed with ZstdDictionary.id()");
            check(cdict.sizeOf() > 0, "ZstdCompressDictionary.sizeOf() not positive");
            check(ddict.id().equals(dict.id()), "decompressDict().id() disagreed with ZstdDictionary.id()");
            check(ddict.sizeOf() > 0, "ZstdDecompressDictionary.sizeOf() not positive");

            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                byte[] compressed = cctx.compress(message, cdict);
                byte[] restored = dctx.decompress(compressed, message.length, ddict);
                check(Arrays.equals(message, restored), "digested-dictionary round-trip mismatch");

                cctx.refDictionary(cdict);
                dctx.refDictionary(ddict);
                byte[] viaRef = cctx.compress(message);
                byte[] restoredViaRef = dctx.decompress(viaRef, message.length);
                check(Arrays.equals(message, restoredViaRef), "refDictionary round-trip mismatch");
            }
        }

        try (ZstdCompressDictionary cdict = dict.compressDict()) {
            check(cdict.level() == Zstd.defaultCompressionLevel(), "compressDict().level() expected the default");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dictSeg = toNative(arena, dict.toByteArray());
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dictSeg, 5);
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dictSeg)) {
                check(cdict.level() == 5, "ZstdCompressDictionary(MemorySegment, level).level() mismatch");
                check(cdict.id().equals(dict.id()), "ZstdCompressDictionary(MemorySegment).id() mismatch");
                check(ddict.id().equals(dict.id()), "ZstdDecompressDictionary(MemorySegment).id() mismatch");
            }
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dictSeg)) {
                check(cdict.level() == Zstd.defaultCompressionLevel(),
                        "ZstdCompressDictionary(MemorySegment).level() expected the default");
            }
        }
    }

    private static void zeroCopyContextCompression(ZstdDictionary dict, byte[] message) {
        byte[] original = sampleText();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            MemorySegment src = toNative(arena, original);

            MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
            long written = cctx.compress(dst, src);
            MemorySegment restored = arena.allocate(original.length);
            long read = dctx.decompress(restored, dst.asSlice(0, written));
            check(read == original.length, "compress/decompress(MemorySegment, MemorySegment) length mismatch");
            check(Arrays.equals(original, toByteArray(restored, read)),
                    "compress/decompress(MemorySegment, MemorySegment) content mismatch");

            MemorySegment frame = cctx.compress(arena, src);
            MemorySegment out = dctx.decompress(arena, frame);
            check(Arrays.equals(original, toByteArray(out, out.byteSize())),
                    "compress/decompress(Arena, MemorySegment) content mismatch");
        }

        try (Arena arena = Arena.ofConfined();
             ZstdCompressDictionary cdict = dict.compressDict();
             ZstdDecompressDictionary ddict = dict.decompressDict();
             ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            MemorySegment src = toNative(arena, message);

            MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
            long written = cctx.compress(dst, src, cdict);
            MemorySegment restored = arena.allocate(message.length);
            long read = dctx.decompress(restored, dst.asSlice(0, written), ddict);
            check(Arrays.equals(message, toByteArray(restored, read)),
                    "compress/decompress(MemorySegment, MemorySegment, dict) content mismatch");

            MemorySegment frame = cctx.compress(arena, src, cdict);
            MemorySegment out = dctx.decompress(arena, frame, ddict);
            check(Arrays.equals(message, toByteArray(out, out.byteSize())),
                    "compress/decompress(Arena, MemorySegment, dict) content mismatch");
        }
    }

    private static void streamingZeroCopy(ZstdDictionary dict, byte[] message) {
        try (ZstdCompressStream cs = new ZstdCompressStream()) {
            check(cs.sizeOf() > 0, "no-arg ZstdCompressStream.sizeOf() not positive");
        }

        byte[] original = sampleText();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressStream cs = new ZstdCompressStream(Zstd.defaultCompressionLevel())) {
            MemorySegment src = toNative(arena, original);
            MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
            ZstdStreamResult result = cs.compress(dst, src, ZstdEndDirective.END);
            check(result.isComplete(), "single-shot ZstdCompressStream.compress(END) did not complete");
            check(result.bytesConsumed() == original.length, "ZstdCompressStream consumed byte count mismatch");
            check(cs.sizeOf() > 0, "ZstdCompressStream.sizeOf() not positive");

            ZstdFrameProgression progression = cs.progress();
            check(progression.produced() >= result.bytesProduced(),
                    "ZstdFrameProgression.produced() smaller than bytes actually produced");

            byte[] compressed = toByteArray(dst, result.bytesProduced());
            try (ZstdDecompressStream ds = new ZstdDecompressStream()) {
                MemorySegment frameSeg = toNative(arena, compressed);
                MemorySegment out = arena.allocate(original.length);
                ZstdStreamResult decodeResult = ds.decompress(out, frameSeg);
                check(decodeResult.isComplete(), "ZstdDecompressStream.decompress() did not complete");
                check(Arrays.equals(original, toByteArray(out, decodeResult.bytesProduced())),
                        "zero-copy streaming round-trip mismatch");
                check(ds.sizeOf() > 0, "ZstdDecompressStream.sizeOf() not positive");
            }
        }

        try (Arena arena = Arena.ofConfined();
             ZstdCompressStream cs = new ZstdCompressStream(Zstd.defaultCompressionLevel(), dict)) {
            MemorySegment src = toNative(arena, message);
            MemorySegment dst = arena.allocate(Zstd.compressBound(src.byteSize()));
            ZstdStreamResult result = cs.compress(dst, src, ZstdEndDirective.END);
            check(result.isComplete(), "dictionary ZstdCompressStream did not complete");
            byte[] compressed = toByteArray(dst, result.bytesProduced());

            try (ZstdDecompressStream ds = new ZstdDecompressStream(dict)) {
                MemorySegment frameSeg = toNative(arena, compressed);
                MemorySegment out = arena.allocate(message.length);
                ZstdStreamResult decodeResult = ds.decompress(out, frameSeg);
                check(decodeResult.isComplete(), "dictionary ZstdDecompressStream did not complete");
                check(Arrays.equals(message, toByteArray(out, decodeResult.bytesProduced())),
                        "dictionary zero-copy streaming round-trip mismatch");
            }
        }
    }

    private static void streamingIo(ZstdDictionary dict, byte[] message) throws IOException {
        byte[] original = sampleText();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sink)) {
            zout.write(original);
        }
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
            check(Arrays.equals(original, zin.readAllBytes()),
                    "ZstdOutputStream/ZstdInputStream default round-trip mismatch");
        }

        ByteArrayOutputStream sinkLeveled = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sinkLeveled, 15)) {
            for (byte b : original) {
                zout.write(b);
            }
            zout.flush();
        }
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sinkLeveled.toByteArray()))) {
            int first = zin.read();
            check(first == (original[0] & 0xFF), "ZstdInputStream.read() first byte mismatch");
            byte[] rest = zin.readAllBytes();
            byte[] restored = new byte[rest.length + 1];
            restored[0] = (byte) first;
            System.arraycopy(rest, 0, restored, 1, rest.length);
            check(Arrays.equals(original, restored),
                    "byte-at-a-time ZstdOutputStream/ZstdInputStream round-trip mismatch");
        }

        ByteArrayOutputStream sinkDict = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sinkDict, dict)) {
            zout.write(message);
        }
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sinkDict.toByteArray()), dict)) {
            check(Arrays.equals(message, zin.readAllBytes()),
                    "dictionary ZstdOutputStream/ZstdInputStream round-trip mismatch");
        }

        ByteArrayOutputStream sinkDictLevel = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sinkDictLevel, 5, dict)) {
            zout.write(message);
        }
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sinkDictLevel.toByteArray()), dict)) {
            check(Arrays.equals(message, zin.readAllBytes()),
                    "level+dictionary ZstdOutputStream/ZstdInputStream round-trip mismatch");
        }

        ByteArrayOutputStream sinkPledged = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sinkPledged, 5, original.length)) {
            zout.write(original);
        }
        byte[] pledgedFrame = sinkPledged.toByteArray();
        check(ZstdFrame.header(pledgedFrame).contentSize().orElseThrow() == original.length,
                "withPledgedSize() did not stamp the declared content size into the frame header");
        check(Arrays.equals(original, Zstd.decompress(pledgedFrame)), "withPledgedSize() round-trip mismatch");
    }

    private static byte[] sampleText() {
        return "the quick brown fox ".repeat(2000).getBytes(StandardCharsets.UTF_8);
    }

    private static List<byte[]> jsonSamples() {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            samples.add(("{\"id\":" + i + ",\"user\":\"user_" + (i % 100) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return samples;
    }

    private static MemorySegment toNative(Arena arena, byte[] data) {
        MemorySegment seg = arena.allocate(Math.max(data.length, 1));
        MemorySegment.copy(data, 0, seg, JAVA_BYTE, 0, data.length);
        return seg;
    }

    private static byte[] toByteArray(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what + " on " + PLATFORM);
        }
    }
}
