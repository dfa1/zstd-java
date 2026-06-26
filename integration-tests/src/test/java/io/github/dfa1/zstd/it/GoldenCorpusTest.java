package io.github.dfa1.zstd.it;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdInputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests against zstd's own vendored golden corpus under
/// `third_party/zstd/tests/` — the canonical, version-matched fixtures the C
/// project uses for its own regression suite. These exercise encoder corners no
/// synthetic payload reaches (block-128k boundaries, RLE/empty blocks, huffman,
/// the PR-3517 block-splitter case) and adversarial frames that must fail.
///
/// The corpus ships via the `third_party/zstd` git submodule. When it is not
/// checked out the cases are skipped, so a shallow clone still builds.
class GoldenCorpusTest {

    private static final Path TESTS = locateCorpus();

    /// Walks up from the working directory to find `third_party/zstd/tests`,
    /// or returns `null` if the submodule is absent.
    private static Path locateCorpus() {
        Path dir = Path.of("").toAbsolutePath();
        for (; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("third_party/zstd/tests");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Stream<Arguments> filesIn(String subdir, String suffix) {
        if (TESTS == null) {
            return Stream.empty();
        }
        Path dir = TESTS.resolve(subdir);
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> files = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
            return files.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Streaming decode (handles frames that do not store content size).
    private static byte[] javaStreamDecode(byte[] frame) {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] jniStreamDecode(byte[] frame) {
        try (var in = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Valid frames the C project guarantees decode. Decode with both zstd-java
    /// and the zstd-jni reference; both must succeed and agree byte-for-byte.
    @Nested
    class GoldenDecompression {

        static Stream<Arguments> frames() {
            return filesIn("golden-decompression", ".zst");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("frames")
        void javaDecodeMatchesJni(String name, Path file) {
            // Given
            byte[] frame = read(file);

            // When
            byte[] javaOut = javaStreamDecode(frame);

            // Then
            assertThat(javaOut).isEqualTo(jniStreamDecode(frame));
        }
    }

    /// Raw inputs the C project compresses in its suite. Round-trip them across
    /// the JNI/FFM boundary in both directions.
    @Nested
    class GoldenCompression {

        static Stream<Arguments> inputs() {
            return filesIn("golden-compression", "");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("inputs")
        void javaCompressJniDecompress(String name, Path file) {
            // Given
            byte[] data = read(file);

            // When
            byte[] frame = Zstd.compress(data, Zstd.defaultCompressionLevel());

            // Then
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("inputs")
        void jniCompressJavaDecompress(String name, Path file) {
            // Given
            byte[] data = read(file);

            // When
            byte[] frame = com.github.luben.zstd.Zstd.compress(data, Zstd.maxCompressionLevel());

            // Then
            assertThat(Zstd.decompress(frame, data.length)).isEqualTo(data);
        }
    }

    /// Malformed frames the C project guarantees fail to decode. zstd-java must
    /// reject every one rather than return garbage.
    @Nested
    class GoldenDecompressionErrors {

        static Stream<Arguments> frames() {
            return filesIn("golden-decompression-errors", ".zst");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("frames")
        void javaDecodeThrows(String name, Path file) {
            // Given
            byte[] frame = read(file);

            // When
            org.assertj.core.api.ThrowableAssert.ThrowingCallable result = () -> javaStreamDecode(frame);

            // Then
            assertThatThrownBy(result).isInstanceOfAny(ZstdException.class, UncheckedIOException.class);
        }
    }
}
