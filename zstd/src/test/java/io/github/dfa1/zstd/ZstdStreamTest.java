package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdStreamTest {

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100, 64 * 1024, 5 * 1024 * 1024})
        void preservesPayloadsAcrossBufferBoundaries(int size) throws IOException {
            // Given a payload, some far larger than the stream buffers
            byte[] original = randomBytes(size);

            // When streamed through compress then decompress
            byte[] restored = streamDecompress(streamCompress(original, 3));

            // Then it is recovered byte for byte
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void compressesIncompressibleAndCompressibleAlike() throws IOException {
            byte[] text = "structured log line ".repeat(100_000).getBytes(StandardCharsets.UTF_8);

            byte[] frame = streamCompress(text, 9);

            assertThat(frame.length).isLessThan(text.length);
            assertThat(streamDecompress(frame)).isEqualTo(text);
        }

        @Test
        void writesSingleBytes() throws IOException {
            // Given bytes written one at a time via write(int)
            byte[] original = "abcdefghij".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink)) {
                for (byte value : original) {
                    zout.write(value);
                }
            }

            // Then the stream still round-trips
            assertThat(streamDecompress(sink.toByteArray())).isEqualTo(original);
        }
    }

    @Nested
    class Interop {

        @Test
        void streamOutputDecodesWithOneShot() throws IOException {
            // Given a frame produced by the streaming compressor
            byte[] original = "interop payload ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, 6);

            // Then the one-shot decompressor reads it (frame stores no size -> give a bound)
            assertThat(Zstd.decompress(frame, original.length)).isEqualTo(original);
        }

        @Test
        void oneShotOutputDecodesWithStream() throws IOException {
            // Given a frame produced by the one-shot compressor
            byte[] original = "interop payload ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] frame = Zstd.compress(original);

            // Then the streaming decompressor reads it
            assertThat(streamDecompress(frame)).isEqualTo(original);
        }
    }

    @Nested
    class Dictionary {

        @Test
        void roundTripsWithDictionary() throws IOException {
            // Given a dictionary and a small record
            ZstdDictionary dict = trainDict();
            byte[] record = record(42);

            // When streamed through compress and decompress with the same dictionary
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, dict)) {
                zout.write(record);
            }
            byte[] restored;
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sink.toByteArray()), dict)) {
                restored = zin.readAllBytes();
            }

            // Then the record is recovered
            assertThat(restored).isEqualTo(record);
        }

        @Test
        void dictionaryShrinksStreamedRecord() throws IOException {
            ZstdDictionary dict = trainDict();
            byte[] record = record(123);

            ByteArrayOutputStream withDict = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(withDict, dict)) {
                zout.write(record);
            }

            // a dictionary frame of a tiny record is smaller than a plain stream frame
            assertThat(withDict.size()).isLessThan(streamCompress(record, Zstd.defaultCompressionLevel()).length);
        }

        @Test
        void streamWithDictionaryDecodesWithOneShot() throws IOException {
            // Given a dictionary frame produced by the streaming compressor
            ZstdDictionary dict = trainDict();
            byte[] record = record(7);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, dict)) {
                zout.write(record);
            }

            // Then the one-shot context decodes it with the same dictionary
            try (ZstdDecompressCtx ctx = new ZstdDecompressCtx()) {
                assertThat(ctx.decompress(sink.toByteArray(), record.length, dict)).isEqualTo(record);
            }
        }

        private ZstdDictionary trainDict() {
            java.util.List<byte[]> samples = new java.util.ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(record(i));
            }
            return ZstdDictionary.train(samples, 8 * 1024);
        }

        private byte[] record(int i) {
            return ("{\"id\":" + i + ",\"user\":\"user_" + (i % 40) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @Nested
    class Truncation {

        @ParameterizedTest
        @ValueSource(ints = {1, 8, 64})
        void throwsWhenFinalFrameIsCutShort(int bytesDropped) throws IOException {
            // Given a valid frame with its tail bytes lopped off (random data so the
            // frame stays large enough to drop bytes from)
            byte[] original = randomBytes(50_000);
            byte[] frame = streamCompress(original, 6);
            byte[] cut = java.util.Arrays.copyOf(frame, frame.length - bytesDropped);

            try (ZstdInputStream sut = new ZstdInputStream(new ByteArrayInputStream(cut))) {
                // When the streaming decompressor drains it
                ThrowingCallable result = sut::readAllBytes;

                // Then it reports the truncation instead of returning a clean EOF
                assertThatThrownBy(result)
                        .isInstanceOf(ZstdException.class)
                        .hasMessageContaining("truncated");
            }
        }

        @Test
        void emptyInputIsCleanEofNotTruncation() throws IOException {
            // Given no input at all
            // When read, it is end-of-stream, not an error
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(new byte[0]))) {
                assertThat(zin.read()).isEqualTo(-1);
            }
        }
    }

    @Nested
    class PledgedSize {

        @Test
        void recordsContentSizeInTheFrame() throws IOException {
            // Given a stream told the exact total up front
            byte[] original = "pledged payload ".repeat(300).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sink, 6, original.length)) {
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // Then the frame header carries the size, so size-less decompress works
            assertThat(ZstdFrame.header(frame).contentSize()).hasValue(original.length);
            assertThat(Zstd.decompress(frame)).isEqualTo(original);
        }

        @Test
        void plainStreamFrameCannotBeSizedForZeroCopyDecode() throws IOException {
            // Given a streamed frame with no pledged size
            byte[] original = "no pledge ".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, 6);

            // When the zero-copy decoder asks the frame how big the output is
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = Zstd.copyIn(arena, frame);
                ThrowingCallable result = () -> Zstd.decompressedSize(src);

                // Then it cannot answer — the content size was never recorded
                assertThatThrownBy(result)
                        .isInstanceOf(ZstdException.class)
                        .hasMessageContaining("not stored");
            }
        }

        @Test
        void pledgedFrameDecodesZeroCopyIntoArenaInOneShot() throws IOException {
            // Given a streamed frame that pledged its total up front
            byte[] original = "pledge enables zero-copy ".repeat(500).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sink, 6, original.length)) {
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // When a memory-mapped-style reader decodes straight into its arena
            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                MemorySegment src = Zstd.copyIn(arena, frame);
                MemorySegment out = dctx.decompress(arena, src);

                // Then the arena was sized exactly from the header and decode round-trips
                assertThat(out.byteSize()).isEqualTo(original.length);
                restored = out.toArray(JAVA_BYTE);
            }
            assertThat(restored).isEqualTo(original);
        }
    }

    private static byte[] streamCompress(byte[] data, int level) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sink, level)) {
            zout.write(data);
        }
        return sink.toByteArray();
    }

    private static byte[] streamDecompress(byte[] frame) throws IOException {
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return zin.readAllBytes();
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] b = new byte[size];
        new Random(7).nextBytes(b);
        return b;
    }
}
