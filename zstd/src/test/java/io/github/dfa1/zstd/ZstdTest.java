package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdTest {

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @MethodSource("io.github.dfa1.zstd.RandomArrays#bytes")
        void preservesArbitraryBytes(byte[] original) {
            // Given a payload and its compressed frame
            byte[] frame = Zstd.compress(original);

            // When it is decompressed
            byte[] restored = Zstd.decompress(frame);

            // Then the original bytes come back exactly
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void handlesEmptyInput() {
            // Given an empty payload
            byte[] empty = new byte[0];

            // When round-tripped
            byte[] restored = Zstd.decompress(Zstd.compress(empty));

            // Then it is still empty
            assertThat(restored).isEmpty();
        }

        @Test
        void shrinksCompressibleInput() {
            // Given highly repetitive text
            byte[] original = "the quick brown fox ".repeat(100).getBytes(StandardCharsets.UTF_8);

            // When compressed
            byte[] frame = Zstd.compress(original);

            // Then the frame is smaller than the input
            assertThat(frame.length).isLessThan(original.length);
        }
    }

    @Nested
    class Levels {

        @ParameterizedTest
        @MethodSource("io.github.dfa1.zstd.RandomArrays#levels")
        void roundTripAtEveryLevel(int level) {
            // Given a payload compressed at the given level
            byte[] original = "payload-data-".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = Zstd.compress(original, level);

            // When decompressed
            byte[] restored = Zstd.decompress(frame);

            // Then the original is recovered
            assertThat(restored).as("level %d", level).isEqualTo(original);
        }

        @Test
        void exposesLevelOrdering() {
            // Given the advertised level bounds
            int min = Zstd.minCompressionLevel();
            int def = Zstd.defaultCompressionLevel();
            int max = Zstd.maxCompressionLevel();

            // Then they are ordered min <= default <= max
            assertThat(min).isLessThanOrEqualTo(def);
            assertThat(def).isLessThanOrEqualTo(max);
        }
    }

    @Nested
    class CompressBound {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 1024, 1_000_000})
        void neverUndersizesTheDestination(long srcSize) {
            // When the worst-case bound is queried
            long bound = Zstd.compressBound(srcSize);

            // Then it is at least the input size
            assertThat(bound).isGreaterThanOrEqualTo(srcSize);
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsCorruptFrame() {
            // Given bytes that are not a zstd frame
            byte[] garbage = "not a zstd frame".getBytes(StandardCharsets.UTF_8);

            // When decompressing
            ThrowingCallable result = () -> Zstd.decompress(garbage);

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }

        @Test
        void rejectsOversizedFrameForBuffer() {
            // Given a frame whose content exceeds the caller's maxSize
            byte[] frame = Zstd.compress("0123456789".getBytes(StandardCharsets.UTF_8));

            // When decompressing into too small a buffer
            ThrowingCallable result = () -> Zstd.decompress(frame, 1);

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class Metadata {

        @Test
        void reportsSemanticVersion() {
            // When the runtime version is read / Then it is an x.y.z string
            assertThat(Zstd.version()).matches("\\d+\\.\\d+\\.\\d+");
        }
    }
}
