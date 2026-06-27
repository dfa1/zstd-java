package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZstdDictionaryTest {

    private List<byte[]> samples;
    private ZstdDictionary sut;

    @BeforeAll
    void trainDictionary() {
        // Given many small, structurally-similar records — the case dictionaries win on
        samples = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            samples.add(record(i));
        }
        sut = ZstdDictionary.train(samples, 16 * 1024);
    }

    @Nested
    class CoverTraining {

        @Test
        void fastCoverRoundTrips() {
            // Given a fast-COVER-trained dictionary
            ZstdDictionary dict = ZstdDictionary.trainFastCover(samples, 16 * 1024);
            assertThat(dict.size()).isGreaterThan(0);

            // Then records round-trip and compress smaller than dictionaryless
            byte[] record = samples.get(321);
            byte[] plain;
            byte[] withDict;
            byte[] restored;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                plain = cctx.compress(record);
                withDict = cctx.compress(record, dict);
                restored = dctx.decompress(cctx.compress(record, dict), record.length, dict);
            }
            assertThat(withDict.length).isLessThan(plain.length);
            assertThat(restored).isEqualTo(record);
        }

        @Test
        void coverRoundTrips() {
            // COVER is slower, so train on a subset to keep the test quick
            ZstdDictionary dict = ZstdDictionary.trainCover(samples.subList(0, 1000), 8 * 1024);
            assertThat(dict.size()).isGreaterThan(0);

            byte[] record = samples.get(5);
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                byte[] frame = cctx.compress(record, dict);
                assertThat(dctx.decompress(frame, record.length, dict)).isEqualTo(record);
            }
        }

        @Test
        void coverFailsWithoutSamples() {
            // When COVER-training on no samples
            ThrowingCallable result = () -> ZstdDictionary.trainCover(List.of(), 4096);

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }

        @Test
        void fastCoverFailsWithoutSamples() {
            // When fast-COVER-training on no samples
            ThrowingCallable result = () -> ZstdDictionary.trainFastCover(List.of(), 4096);

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }
    }

    @Nested
    class Finalize {

        @Test
        void finalizesRawContentIntoUsableDictionary() {
            // Given some raw dictionary content plus tuning samples
            byte[] content = "{\"user\":\"\",\"event\":\"click\",\"id\":}".repeat(40)
                    .getBytes(StandardCharsets.UTF_8);

            ZstdDictionary dict = ZstdDictionary.finalizeFrom(content, samples, 16 * 1024, 0);

            // Then it carries a header and round-trips a record
            assertThat(dict.size()).isGreaterThan(0);
            assertThat(dict.headerSize()).isPositive();

            byte[] record = samples.get(3);
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                byte[] frame = cctx.compress(record, dict);
                assertThat(dctx.decompress(frame, record.length, dict)).isEqualTo(record);
            }
        }

        @Test
        void trainedDictionaryHasHeader() {
            assertThat(sut.headerSize()).isPositive();
            assertThat(sut.headerSize()).isLessThanOrEqualTo(sut.size());
        }

        @Test
        void finalizeFailsWithoutSamples() {
            // When finalising raw content with no tuning samples
            ThrowingCallable result = () -> ZstdDictionary.finalizeFrom(new byte[]{1, 2, 3}, List.of(), 4096, 0);

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }
    }

    @Nested
    class Training {

        @Test
        void producesNonEmptyDictionary() {
            // Then the trained dictionary has content matching its reported size
            assertThat(sut.size()).isGreaterThan(0);
            assertThat(sut.toByteArray()).hasSize(sut.size());
        }

        @Test
        void beatsDictionarylessOnTinyPayload() {
            // Given a single small record
            byte[] record = samples.get(123);

            // When compressed with and without the dictionary
            byte[] plain;
            byte[] withDict;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                plain = ctx.compress(record);
                withDict = ctx.compress(record, sut);
            }

            // Then the dictionary compresses the tiny record noticeably better
            assertThat(withDict.length).isLessThan(plain.length);
        }

        @Test
        void failsWithoutSamples() {
            // When training on no samples
            ThrowingCallable result = () -> ZstdDictionary.train(List.of(), 4096);

            // Then it fails fast with the empty-samples guard, before reaching ZDICT
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }

        @Test
        void surfacesTheNativeErrorNameWhenTrainingFails() {
            // Given samples that pass the empty-check but are far too little for ZDICT
            List<byte[]> tooFew = List.of(new byte[]{1, 2, 3});

            // When training
            ThrowingCallable result = () -> ZstdDictionary.train(tooFew, 112_640);

            // Then the failure carries the native ZDICT error name, not an empty string
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageMatching("dictionary training failed: .+");
        }
    }

    @Nested
    class RawDictionary {

        @ParameterizedTest
        @ValueSource(ints = {0, 7, 123, 2048, 3999})
        void roundTripsRecord(int index) {
            // Given a record
            byte[] record = samples.get(index);

            // When compressed and decompressed against the raw dictionary
            byte[] restored;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                byte[] frame = cctx.compress(record, sut);
                restored = dctx.decompress(frame, record.length, sut);
            }

            // Then the record is recovered
            assertThat(restored).isEqualTo(record);
        }
    }

    @Nested
    class DigestedDictionary {

        @Test
        void roundTripsViaCDictAndDDict() {
            // Given digested compress/decompress dictionaries at a fixed level
            byte[] record = samples.get(999);

            byte[] restored;
            int level;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(sut, 19);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {

                // When round-tripped through the digested dictionaries
                byte[] frame = cctx.compress(record, cdict);
                restored = dctx.decompress(frame, record.length, ddict);
                level = cdict.level();
            }

            // Then the record is recovered at the requested level
            assertThat(restored).isEqualTo(record);
            assertThat(level).isEqualTo(19);
        }

        @Test
        void digestedDictionariesReportTheSameId() {
            try (ZstdCompressDict cdict = new ZstdCompressDict(sut);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {
                assertThat(cdict.id()).isEqualTo(sut.id());
                assertThat(ddict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void interoperatesWithRawPath() {
            // Given a record compressed with the raw dictionary
            byte[] record = samples.get(2048);

            byte[] restored;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {
                byte[] frame = cctx.compress(record, sut);

                // When decompressed with the digested dictionary
                restored = dctx.decompress(frame, record.length, ddict);
            }

            // Then the two dictionary forms interoperate
            assertThat(restored).isEqualTo(record);
        }
    }

    @Nested
    class Serialisation {

        @Test
        void reloadedDictionaryKeepsIdentityAndDecodes() {
            // Given the dictionary serialised and reloaded
            ZstdDictionary reloaded = ZstdDictionary.of(sut.toByteArray());

            // Then it carries the same dictionary id
            assertThat(reloaded.id()).isEqualTo(sut.id());

            // And a frame from the reload decodes against the original
            byte[] record = samples.get(1);
            byte[] restored;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                byte[] frame = cctx.compress(record, reloaded);
                restored = dctx.decompress(frame, record.length, sut);
            }
            assertThat(restored).isEqualTo(record);
        }
    }

    @Nested
    class SegmentDigestedDictionary {

        @Test
        void roundTripsViaSegmentBuiltCDictAndDDict() {
            // Given digested dictionaries built straight from native dictionary
            // segments (the zero-copy path — no heap byte[] bounce)
            byte[] record = samples.get(999);
            byte[] raw = sut.toByteArray();

            byte[] restored;
            int level;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(nativeDict(arena, raw), 19);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(nativeDict(arena, raw))) {

                // When round-tripped through the segment-built dictionaries
                byte[] frame = cctx.compress(record, cdict);
                restored = dctx.decompress(frame, record.length, ddict);
                level = cdict.level();
            }

            // Then the record is recovered at the requested level
            assertThat(restored).isEqualTo(record);
            assertThat(level).isEqualTo(19);
        }

        @Test
        void segmentBuiltDictionariesReportSameIdAsHeap() {
            // Given dictionaries digested from a native segment
            byte[] raw = sut.toByteArray();

            // Then they carry the same id as the heap-built dictionary
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressDict cdict = new ZstdCompressDict(nativeDict(arena, raw));
                 ZstdDecompressDict ddict = new ZstdDecompressDict(nativeDict(arena, raw))) {
                assertThat(cdict.id()).isEqualTo(sut.id());
                assertThat(ddict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void interoperatesWithHeapBuiltDictionaries() {
            // Given a frame compressed with a segment-built CDict
            byte[] record = samples.get(2048);
            byte[] raw = sut.toByteArray();

            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(nativeDict(arena, raw));
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {
                byte[] frame = cctx.compress(record, cdict);

                // When decompressed with a heap-built DDict
                restored = dctx.decompress(frame, record.length, ddict);
            }

            // Then segment- and heap-built dictionaries interoperate
            assertThat(restored).isEqualTo(record);
        }

        @Test
        void rejectsHeapDecompressDictSegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When digesting it as a decompress dictionary
            ThrowingCallable result = () -> new ZstdDecompressDict(heap);

            // Then it fails fast rather than dereferencing a heap address in C
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsHeapCompressDictSegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When digesting it as a compress dictionary
            ThrowingCallable result = () -> new ZstdCompressDict(heap);

            // Then it fails fast
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        private MemorySegment nativeDict(Arena arena, byte[] raw) {
            MemorySegment seg = arena.allocate(raw.length);
            MemorySegment.copy(raw, 0, seg, ValueLayout.JAVA_BYTE, 0, raw.length);
            return seg;
        }
    }

    @Nested
    class StickyDictionary {

        @Test
        void loadedDictionaryCombinesWithAdvancedParameters() {
            // Given a context with both a loaded dictionary AND a checksum — the
            // combination the per-call compress(src, dict) overloads cannot give
            byte[] record = samples.get(123);
            byte[] frame;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx().checksum(true)) {
                cctx.loadDictionary(sut);
                frame = cctx.compress(record);
            }
            byte[] plain;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                plain = ctx.compress(record);
            }

            // Then the dictionary is honoured (smaller than dictionaryless) and decodes
            assertThat(frame.length).isLessThan(plain.length);
            byte[] restored;
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                dctx.loadDictionary(sut);
                restored = dctx.decompress(frame, record.length);
            }
            assertThat(restored).isEqualTo(record);
        }

        @Test
        void referencedDigestedDictionarySurvivesSessionReset() {
            // Given a pooled context referencing a digested dictionary, recycled between frames
            byte[] first = samples.get(1);
            byte[] second = samples.get(2);
            byte[] restoredFirst;
            byte[] restoredSecond;
            try (ZstdCompressDict cdict = new ZstdCompressDict(sut, 19);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut);
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                cctx.refDictionary(cdict);
                byte[] frameFirst = cctx.compress(first);
                cctx.reset(ZstdResetDirective.SESSION_ONLY);
                byte[] frameSecond = cctx.compress(second);

                dctx.refDictionary(ddict);
                restoredFirst = dctx.decompress(frameFirst, first.length);
                dctx.reset(ZstdResetDirective.SESSION_ONLY);
                restoredSecond = dctx.decompress(frameSecond, second.length);
            }

            // Then both frames round-trip: the reference outlived the session reset
            assertThat(restoredFirst).isEqualTo(first);
            assertThat(restoredSecond).isEqualTo(second);
        }

        @Test
        void parameterResetClearsTheLoadedDictionary() {
            // Given a context that loaded a dictionary, then cleared its parameters
            byte[] record = samples.get(7);
            byte[] afterReset;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                cctx.loadDictionary(sut);
                cctx.compress(record);
                cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
                afterReset = cctx.compress(record);
            }
            byte[] noDict;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                noDict = ctx.compress(record);
            }

            // Then the dictionary is gone: the frame matches a fresh dictionaryless one
            assertThat(afterReset).isEqualTo(noDict);
        }

        @Test
        void nullClearsTheLoadedDictionary() {
            // Given a context whose loaded dictionary is then cleared with null
            byte[] record = samples.get(7);
            byte[] cleared;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                cctx.loadDictionary(sut);
                cctx.loadDictionary((ZstdDictionary) null);
                cleared = cctx.compress(record);
            }
            byte[] noDict;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                noDict = ctx.compress(record);
            }

            // Then it compresses as if no dictionary was ever loaded
            assertThat(cleared).isEqualTo(noDict);
        }

        @Test
        void loadsDictionaryFromNativeSegmentWithoutHeapCopy() {
            // Given a dictionary loaded straight from native segments (zero-copy path)
            byte[] record = samples.get(2048);
            byte[] raw = sut.toByteArray();
            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                cctx.loadDictionary(nativeDict(arena, raw));
                byte[] frame = cctx.compress(record);
                dctx.loadDictionary(nativeDict(arena, raw));
                restored = dctx.decompress(frame, record.length);
            }

            // Then the record round-trips through the segment-loaded dictionary
            assertThat(restored).isEqualTo(record);
        }

        @Test
        void rejectsHeapDictionarySegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When loaded into a context
            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                ThrowingCallable result = () -> cctx.loadDictionary(heap);

                // Then it fails fast rather than handing C a heap address
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void nullNativeSegmentClearsTheLoadedDictionary() {
            // Given a context whose dictionary is cleared through the native overload
            byte[] record = samples.get(7);
            byte[] cleared;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                cctx.loadDictionary(sut);
                cctx.loadDictionary((MemorySegment) null);
                cleared = cctx.compress(record);
            }
            byte[] noDict;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                noDict = ctx.compress(record);
            }

            // Then it compresses as if no dictionary was ever loaded
            assertThat(cleared).isEqualTo(noDict);
        }

        @Test
        void loadAndRefReturnTheSameCompressContext() {
            // Given a compress context and dictionaries to load and reference
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(sut, 19)) {

                // Then every sticky-dictionary call returns the same context, for chaining
                assertThat(cctx.loadDictionary(sut)).isSameAs(cctx);
                assertThat(cctx.loadDictionary(nativeDict(arena, sut.toByteArray()))).isSameAs(cctx);
                assertThat(cctx.loadDictionary((ZstdDictionary) null)).isSameAs(cctx);
                assertThat(cctx.refDictionary(cdict)).isSameAs(cctx);
                assertThat(cctx.refDictionary(null)).isSameAs(cctx);
            }
        }

        @Test
        void loadAndRefReturnTheSameDecompressContext() {
            // Given a decompress context and dictionaries to load and reference
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {

                // Then every sticky-dictionary call returns the same context, for chaining
                assertThat(dctx.loadDictionary(sut)).isSameAs(dctx);
                assertThat(dctx.loadDictionary(nativeDict(arena, sut.toByteArray()))).isSameAs(dctx);
                assertThat(dctx.loadDictionary((ZstdDictionary) null)).isSameAs(dctx);
                assertThat(dctx.refDictionary(ddict)).isSameAs(dctx);
                assertThat(dctx.refDictionary(null)).isSameAs(dctx);
            }
        }

        private MemorySegment nativeDict(Arena arena, byte[] raw) {
            MemorySegment seg = arena.allocate(raw.length);
            MemorySegment.copy(raw, 0, seg, ValueLayout.JAVA_BYTE, 0, raw.length);
            return seg;
        }
    }

    private static byte[] record(int i) {
        return ("{\"id\":" + i
                + ",\"user\":\"user_" + (i % 50)
                + "\",\"active\":" + (i % 2 == 0)
                + ",\"score\":" + (i * 7 % 1000)
                + ",\"tag\":\"event\"}").getBytes(StandardCharsets.UTF_8);
    }
}
