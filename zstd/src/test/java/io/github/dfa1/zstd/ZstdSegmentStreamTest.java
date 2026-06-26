package io.github.dfa1.zstd;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

class ZstdSegmentStreamTest {

    @Nested
    class SingleStep {

        @Test
        void roundTripsInOneCallEach() {
            byte[] original = "segment stream ".repeat(400).getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = new ZstdCompressStream();
                 ZstdDecompressStream ds = new ZstdDecompressStream()) {

                MemorySegment src = segment(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(original.length));

                // When compressed in one END step
                ZstdStreamResult c = cs.compress(dst, src, ZstdEndDirective.END);

                // Then everything is consumed and the frame is complete
                assertThat(c.isComplete()).isTrue();
                assertThat(c.bytesConsumed()).isEqualTo(original.length);

                MemorySegment frame = dst.asSlice(0, c.bytesProduced());
                MemorySegment out = arena.allocate(original.length);
                ZstdStreamResult d = ds.decompress(out, frame);

                assertThat(d.isComplete()).isTrue();
                assertThat(d.bytesProduced()).isEqualTo(original.length);
                assertThat(bytes(out, d.bytesProduced())).isEqualTo(original);
            }
        }
    }

    @Nested
    class Chunked {

        @Test
        void roundTripsThroughTinyBuffers() {
            // Given a payload far larger than the streaming buffers
            byte[] original = new byte[2 * 1024 * 1024];
            new Random(11).nextBytes(original);

            byte[] frame = drive(original, 8 * 1024, true);
            byte[] restored = drive(frame, 8 * 1024, false);

            // Then chunked compress + decompress reproduce the input exactly
            assertThat(restored).isEqualTo(original);
        }

        /// Streams `input` through fixed `chunk`-sized native buffers using the
        /// segment driver: compress when `compress`, else decompress.
        private byte[] drive(byte[] input, int chunk, boolean compress) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = compress ? new ZstdCompressStream() : null;
                 ZstdDecompressStream ds = compress ? null : new ZstdDecompressStream()) {

                MemorySegment src = segment(arena, input);
                MemorySegment dst = arena.allocate(chunk);
                long srcOff = 0;
                ZstdStreamResult r;
                do {
                    MemorySegment srcSlice = src.asSlice(srcOff, input.length - srcOff);
                    r = compress
                            ? cs.compress(dst, srcSlice, ZstdEndDirective.END)
                            : ds.decompress(dst, srcSlice);
                    srcOff += r.bytesConsumed();
                    collected.writeBytes(bytes(dst, r.bytesProduced()));
                } while (!r.isComplete());
                return collected.toByteArray();
            }
        }
    }

    @Nested
    class WithDictionary {

        @Test
        void roundTripsAgainstDictionary() {
            ZstdDictionary dict = trainDict();
            byte[] record = "{\"id\":1,\"user\":\"u\",\"event\":\"x\"}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = new ZstdCompressStream(3, dict);
                 ZstdDecompressStream ds = new ZstdDecompressStream(dict)) {

                MemorySegment src = segment(arena, record);
                MemorySegment dst = arena.allocate(Zstd.compressBound(record.length));
                ZstdStreamResult c = cs.compress(dst, src, ZstdEndDirective.END);

                MemorySegment out = arena.allocate(record.length);
                ds.decompress(out, dst.asSlice(0, c.bytesProduced()));
                assertThat(bytes(out, record.length)).isEqualTo(record);
            }
        }

        private ZstdDictionary trainDict() {
            List<byte[]> samples = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(("{\"id\":" + i + ",\"user\":\"u" + (i % 30) + "\",\"event\":\"x\"}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return ZstdDictionary.train(samples, 8 * 1024);
        }
    }

    private static MemorySegment segment(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static byte[] bytes(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }
}
