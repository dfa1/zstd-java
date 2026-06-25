package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

class ZstdSegmentTest {

    @Nested
    class ExplicitDestination {

        @Test
        void roundTripsNativeToNative() {
            // Given a payload in a native source segment
            byte[] original = "segment payload ".repeat(200).getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(original.length));

                // When compressed into a caller-sized destination
                long packedLen = cctx.compress(dst, src);
                MemorySegment frame = dst.asSlice(0, packedLen);

                // Then the frame header reports the original size, and it decodes back
                long outLen = Zstd.decompressedSize(frame);
                assertThat(outLen).isEqualTo(original.length);

                MemorySegment out = arena.allocate(outLen);
                long written = dctx.decompress(out, frame);
                assertThat(bytesOf(out, written)).isEqualTo(original);
            }
        }
    }

    @Nested
    class ArenaAllocating {

        @Test
        void codecSizesAndAllocatesOutput() {
            // Given a payload in a native source segment
            byte[] original = "arena-sized payload ".repeat(100).getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {

                MemorySegment src = segmentOf(arena, original);

                // When the codec sizes and allocates both buffers from the caller's arena
                MemorySegment frame = cctx.compress(arena, src);
                MemorySegment out = dctx.decompress(arena, frame);

                // Then the output is exactly the original
                assertThat(out.byteSize()).isEqualTo(original.length);
                assertThat(bytesOf(out, out.byteSize())).isEqualTo(original);
            }
        }
    }

    @Nested
    class WithDictionary {

        @Test
        void roundTripsWithDigestedDictionary() {
            // Given a digested dictionary and a record in a native segment
            ZstdDictionary dict = trainSmallDictionary();
            byte[] record = "{\"id\":42,\"user\":\"u\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(dict);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {

                MemorySegment src = segmentOf(arena, record);

                // When round-tripped segment-to-segment against the dictionary
                MemorySegment frame = cctx.compress(arena, src, cdict);
                MemorySegment out = arena.allocate(record.length);
                long written = dctx.decompress(out, frame, ddict);

                // Then the record is recovered
                assertThat(bytesOf(out, written)).isEqualTo(record);
            }
        }
    }

    private static MemorySegment segmentOf(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static byte[] bytesOf(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private static ZstdDictionary trainSmallDictionary() {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            samples.add(("{\"id\":" + i + ",\"user\":\"u\",\"active\":" + (i % 2 == 0) + "}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return ZstdDictionary.train(samples, 8 * 1024);
    }
}
