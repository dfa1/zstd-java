package io.github.dfa1.zstd;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// Seeded-random input generators for `@MethodSource` tests. Counts are kept low
/// (each case crosses the JNI/FFM boundary). Seed is fixed so failures reproduce.
final class RandomArrays {

    private static final long SEED = 0xC0FFEEL;

    /// A spread of payloads: boundaries (empty, single byte), incompressible
    /// random data, and highly compressible repeated data, across sizes.
    static Stream<byte[]> bytes() {
        Random r = new Random(SEED);
        return Stream.of(
                new byte[0],
                new byte[]{0},
                new byte[]{(byte) 0xFF},
                random(r, 16),
                random(r, 1024),
                random(r, 64 * 1024),
                compressible(r, 1),
                compressible(r, 512),
                compressible(r, 4096),
                compressible(r, 64 * 1024),
                random(r, 1 + r.nextInt(8192)),
                compressible(r, 1 + r.nextInt(8192)));
    }

    /// The compression levels worth exercising: both extremes, default, and a low one.
    static IntStream levels() {
        return IntStream.of(
                Zstd.minCompressionLevel(), 1, Zstd.defaultCompressionLevel(), Zstd.maxCompressionLevel());
    }

    private static byte[] random(Random r, int size) {
        byte[] b = new byte[size];
        r.nextBytes(b);
        return b;
    }

    /// Repetitive data with a little noise — the realistic "compresses well" case.
    private static byte[] compressible(Random r, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i % 17 == 0) ? r.nextInt(256) : 'a' + (i % 8));
        }
        return b;
    }

    private RandomArrays() {
        // no instances
    }
}
