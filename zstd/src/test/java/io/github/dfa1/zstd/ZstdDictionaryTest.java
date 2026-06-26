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

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
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

    private static byte[] record(int i) {
        return ("{\"id\":" + i
                + ",\"user\":\"user_" + (i % 50)
                + "\",\"active\":" + (i % 2 == 0)
                + ",\"score\":" + (i * 7 % 1000)
                + ",\"tag\":\"event\"}").getBytes(StandardCharsets.UTF_8);
    }
}
