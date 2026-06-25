package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdParameterTest {

    private static final byte[] PAYLOAD =
            "advanced-parameter payload ".repeat(2000).getBytes(StandardCharsets.UTF_8);

    @Nested
    class Checksum {

        @Test
        void addsFourByteTrailerAndStillRoundTrips() {
            // Given the same input compressed at the same level with and without a checksum
            byte[] plain;
            byte[] checksummed;
            try (ZstdCompressCtx noSum = new ZstdCompressCtx().level(9);
                 ZstdCompressCtx withSum = new ZstdCompressCtx().level(9).checksum(true)) {
                plain = noSum.compress(PAYLOAD);
                checksummed = withSum.compress(PAYLOAD);
            }

            // Then the checksum adds exactly its 4-byte trailer, and the frame still decodes
            assertThat(checksummed.length).isEqualTo(plain.length + 4);
            assertThat(Zstd.decompress(checksummed)).isEqualTo(PAYLOAD);
        }

        @Test
        void rejectsCorruptedChecksummedFrame() {
            // Given a checksummed frame with one body byte flipped
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx().checksum(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            frame[frame.length / 2] ^= 0x01;

            // When decompressed / Then the integrity check fails
            assertThatThrownBy(() -> Zstd.decompress(frame, PAYLOAD.length))
                    .isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class Ratio {

        @Test
        void longDistanceMatchingRoundTrips() {
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx().level(3).longDistanceMatching(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }

        @Test
        void explicitWindowLogRoundTrips() {
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx().windowLog(24)) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }
    }

    @Nested
    class GenericSetter {

        @Test
        void levelViaParameterMatchesLevelMethod() {
            // Given the level set generically vs via level()
            byte[] viaParam;
            byte[] viaMethod;
            try (ZstdCompressCtx a = new ZstdCompressCtx().parameter(ZstdCompressParameter.COMPRESSION_LEVEL, 17);
                 ZstdCompressCtx b = new ZstdCompressCtx().level(17)) {
                viaParam = a.compress(PAYLOAD);
                viaMethod = b.compress(PAYLOAD);
            }

            // Then both produce the same frame
            assertThat(viaParam).isEqualTo(viaMethod);
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given an absurd window log
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                // When set / Then it is rejected natively
                assertThatThrownBy(() -> ctx.parameter(ZstdCompressParameter.WINDOW_LOG, 99))
                        .isInstanceOf(ZstdException.class);
            }
        }
    }
}
