package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

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
