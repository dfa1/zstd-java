package io.github.dfa1.zstd.it;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdInputStream;
import io.github.dfa1.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Format-compatibility tests: frames produced by the reference zstd-jni binding
/// must decode with zstd-java and vice versa. zstd-jni bundles a different zstd
/// version (1.5.x) than this library (1.6.0); the frame format is compatible, so
/// these prove real interop, not just internal round-trips.
class ZstdJniInteropTest {

    static Stream<Arguments> payloadsAndLevels() {
        Random r = new Random(0xABCD);
        List<Arguments> cases = new ArrayList<>();
        int[] sizes = {0, 1, 1024, 64 * 1024, 1024 * 1024};
        int[] levels = {Zstd.minCompressionLevel(), 1, Zstd.defaultCompressionLevel(), Zstd.maxCompressionLevel()};
        for (int size : sizes) {
            byte[] data = size % 2 == 0 ? compressible(r, size) : random(r, size);
            for (int level : levels) {
                cases.add(Arguments.of(level, data));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("payloadsAndLevels")
    void jniCompressJavaDecompress(int level, byte[] data) {
        byte[] frame = com.github.luben.zstd.Zstd.compress(data, level);
        assertThat(Zstd.decompress(frame, data.length)).isEqualTo(data);
    }

    @ParameterizedTest
    @MethodSource("payloadsAndLevels")
    void javaCompressJniDecompress(int level, byte[] data) {
        byte[] frame = Zstd.compress(data, level);
        assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
    }

    @Nested
    class Streaming {

        @Test
        void javaStreamToJniStream() throws Exception {
            byte[] data = "interop streaming ".repeat(50_000).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, 7)) {
                zout.write(data);
            }
            byte[] restored;
            try (var zin = new com.github.luben.zstd.ZstdInputStream(
                    new ByteArrayInputStream(sink.toByteArray()))) {
                restored = zin.readAllBytes();
            }
            assertThat(restored).isEqualTo(data);
        }

        @Test
        void jniStreamToJavaStream() throws Exception {
            byte[] data = "interop streaming ".repeat(50_000).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (var zout = new com.github.luben.zstd.ZstdOutputStream(sink)) {
                zout.write(data);
            }
            byte[] restored;
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
                restored = zin.readAllBytes();
            }
            assertThat(restored).isEqualTo(data);
        }
    }

    @Nested
    class Dictionary {

        @Test
        void javaDictCompressJniDictDecompress() {
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(11);

            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                frame = ctx.compress(sample, dict);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }

        @Test
        void jniDictCompressJavaDictDecompress() {
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(22);

            ZstdDictCompress jniDict = new ZstdDictCompress(dict.toByteArray(), Zstd.defaultCompressionLevel());
            byte[] frame = com.github.luben.zstd.Zstd.compress(sample, jniDict);

            byte[] restored;
            try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
                restored = ctx.decompress(frame, sample.length, dict);
            }
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void javaLoadedDictWithChecksumJniDictDecompress() {
            // A sticky loaded dictionary combined with an advanced parameter
            // (checksum) — the COMPRESS2 path — must still produce a frame zstd-jni
            // decodes against the same dictionary.
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(33);

            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext().checksum(true)) {
                ctx.loadDictionary(dict);
                frame = ctx.compress(sample);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }

        @Test
        void javaReferencedDigestedDictJniDictDecompress() {
            // A frame from a context referencing a digested CDict must decode in zstd-jni.
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(44);

            byte[] frame;
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict, Zstd.defaultCompressionLevel());
                 ZstdCompressContext ctx = new ZstdCompressContext()) {
                ctx.refDictionary(cdict);
                frame = ctx.compress(sample);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }

        private ZstdDictionary trainDict() {
            List<byte[]> samples = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(sample(i));
            }
            return ZstdDictionary.train(samples, 8 * 1024);
        }

        private byte[] sample(int i) {
            return ("{\"id\":" + i + ",\"user\":\"u" + (i % 30) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] random(Random r, int size) {
        byte[] b = new byte[size];
        r.nextBytes(b);
        return b;
    }

    private static byte[] compressible(Random r, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i % 13 == 0) ? r.nextInt(256) : 'a' + (i % 8));
        }
        return b;
    }
}
