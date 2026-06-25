package io.github.dfa1.zstd.bench;

import java.util.Random;

/// Deterministic, semi-compressible test payloads shared by the benchmarks.
///
/// The bytes are assembled from a tiny vocabulary so they compress at a
/// realistic ratio (roughly 3x) rather than being incompressible random noise
/// or trivially compressible zeros — both of which flatter or punish codecs
/// unfairly.
final class BenchData {

    private static final byte[][] VOCAB = {
        " the".getBytes(), " quick".getBytes(), " brown".getBytes(),
        " fox".getBytes(), " jumps".getBytes(), " over".getBytes(),
        " lazy".getBytes(), " dog".getBytes(), " zstd".getBytes(),
        " segment".getBytes(), " arena".getBytes(), " bytes".getBytes(),
    };

    static byte[] generate(int size) {
        byte[] out = new byte[size];
        Random rnd = new Random(0xC0FFEE);
        int pos = 0;
        while (pos < size) {
            byte[] word = VOCAB[rnd.nextInt(VOCAB.length)];
            int n = Math.min(word.length, size - pos);
            System.arraycopy(word, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    private BenchData() {
        // no instances
    }
}
