package io.github.dfa1.zstd.it;

import io.github.dfa1.zstd.Zstd;

import java.util.Random;

final class ItTestSupport {

    static final int[] LEVELS = {
        Zstd.minCompressionLevel(), 1, Zstd.defaultCompressionLevel(), Zstd.maxCompressionLevel()
    };

    static byte[] random(Random r, int size) {
        byte[] b = new byte[size];
        r.nextBytes(b);
        return b;
    }

    static byte[] compressible(Random r, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i % 13 == 0) ? r.nextInt(256) : 'a' + (i % 8));
        }
        return b;
    }

    private ItTestSupport() {
        // no instances
    }
}
