package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdFrameTest {

    private static final byte[] PAYLOAD =
            "frame inspection payload ".repeat(500).getBytes(StandardCharsets.UTF_8);

    @Nested
    class IsFrame {

        @Test
        void recognisesAZstdFrame() {
            assertThat(ZstdFrame.isZstdFrame(Zstd.compress(PAYLOAD))).isTrue();
        }

        @Test
        void rejectsGarbage() {
            assertThat(ZstdFrame.isZstdFrame("not a frame".getBytes(StandardCharsets.UTF_8))).isFalse();
        }
    }

    @Nested
    class CompressedSize {

        @Test
        void equalsLengthForSingleFrame() {
            // Given one frame
            byte[] frame = Zstd.compress(PAYLOAD);

            // Then its compressed size is the whole buffer
            assertThat(ZstdFrame.compressedSize(frame)).isEqualTo(frame.length);
        }

        @Test
        void locatesFirstFrameBoundaryWhenConcatenated() throws Exception {
            // Given two frames back to back
            byte[] first = Zstd.compress(PAYLOAD);
            byte[] second = Zstd.compress("second".getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream both = new ByteArrayOutputStream();
            both.write(first);
            both.write(second);

            // Then compressedSize reports where the second frame begins
            assertThat(ZstdFrame.compressedSize(both.toByteArray())).isEqualTo(first.length);
        }

        @Test
        void rejectsGarbage() {
            assertThatThrownBy(() -> ZstdFrame.compressedSize("xxxx".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class DecompressedBound {

        @Test
        void boundsTheDecompressedSize() {
            byte[] frame = Zstd.compress(PAYLOAD);
            assertThat(ZstdFrame.decompressedBound(frame)).isGreaterThanOrEqualTo(PAYLOAD.length);
        }

        @Test
        void rejectsGarbage() {
            assertThatThrownBy(() -> ZstdFrame.decompressedBound("xx".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class DictId {

        @Test
        void isZeroForDictionarylessFrame() {
            assertThat(ZstdFrame.dictId(Zstd.compress(PAYLOAD))).isZero();
        }

        @Test
        void matchesTheDictionaryUsed() {
            // Given a frame compressed with a dictionary
            ZstdDictionary dict = trainDict();
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                frame = ctx.compress(PAYLOAD, dict);
            }

            // Then the frame's dictionary id matches the dictionary
            assertThat(ZstdFrame.dictId(frame)).isEqualTo(dict.id());
        }

        private ZstdDictionary trainDict() {
            List<byte[]> samples = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(("{\"id\":" + i + ",\"k\":\"v" + (i % 30) + "\"}").getBytes(StandardCharsets.UTF_8));
            }
            return ZstdDictionary.train(samples, 8 * 1024);
        }
    }
}
