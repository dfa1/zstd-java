package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

            // When decompressed
            ThrowingCallable result = () -> Zstd.decompress(frame, PAYLOAD.length);

            // Then the integrity check fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
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
    class Bounds {

        @Test
        void compressionLevelBoundsMatchTheLibrary() {
            ZstdBounds bounds = ZstdCompressParameter.COMPRESSION_LEVEL.bounds();
            assertThat(bounds.lowerBound()).isEqualTo(Zstd.minCompressionLevel());
            assertThat(bounds.upperBound()).isEqualTo(Zstd.maxCompressionLevel());
        }

        @ParameterizedTest
        @EnumSource(ZstdCompressParameter.class)
        void everyCompressionParameterReportsBounds(ZstdCompressParameter parameter) {
            ZstdBounds bounds = parameter.bounds();
            assertThat(bounds.upperBound()).isGreaterThanOrEqualTo(bounds.lowerBound());
        }

        @Test
        void decompressionParameterReportsBounds() {
            ZstdBounds bounds = ZstdDecompressParameter.WINDOW_LOG_MAX.bounds();
            assertThat(bounds.upperBound()).isGreaterThan(bounds.lowerBound());
        }
    }

    @Nested
    class MoreParameters {

        @ParameterizedTest
        @EnumSource(value = ZstdCompressParameter.class,
                names = {"TARGET_C_BLOCK_SIZE", "LDM_HASH_LOG", "LDM_MIN_MATCH",
                        "LDM_BUCKET_SIZE_LOG", "STRATEGY"})
        void settingAParameterAtItsLowerBoundRoundTrips(ZstdCompressParameter parameter) {
            // Given a parameter set to a valid in-range value
            int value = Math.max(parameter.bounds().lowerBound(), 1);
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx().parameter(parameter, value)) {
                frame = ctx.compress(PAYLOAD);
            }
            // Then the frame still decompresses
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }
    }

    @Nested
    class Decompress {

        @Test
        void windowLogMaxIsAccepted() {
            // Given a decompressor configured with a raised window limit
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressCtx ctx = new ZstdDecompressCtx().windowLogMax(31)) {
                // Then normal frames still decode
                assertThat(ctx.decompress(frame, PAYLOAD.length)).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a decompression context
            try (ZstdDecompressCtx sut = new ZstdDecompressCtx()) {
                // When setting an absurd window-log-max
                ThrowingCallable result = () -> sut.parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
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
            // Given a compression context
            try (ZstdCompressCtx sut = new ZstdCompressCtx()) {
                // When setting an absurd window log
                ThrowingCallable result = () -> sut.parameter(ZstdCompressParameter.WINDOW_LOG, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }
    }
}
