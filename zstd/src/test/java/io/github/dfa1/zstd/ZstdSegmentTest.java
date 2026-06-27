package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            ZstdDictionary dict = trainDictionary(2000);
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

        @Test
        void arenaAllocatingDecompressSizesOutputFromTheDigestedDictionaryFrame() {
            // Given a digested-dictionary frame that stores its decompressed size
            ZstdDictionary dict = trainDictionary(2000);
            byte[] record = "{\"id\":99,\"user\":\"u\",\"active\":false}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx cctx = new ZstdCompressCtx();
                 ZstdDecompressCtx dctx = new ZstdDecompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(dict);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {

                MemorySegment src = segmentOf(arena, record);
                MemorySegment frame = cctx.compress(arena, src, cdict);

                // When decoded through the arena-allocating ddict overload
                MemorySegment out = dctx.decompress(arena, frame, ddict);

                // Then it allocates the exact size and returns the record (a non-null segment)
                assertThat(out).isNotNull();
                assertThat(out.byteSize()).isEqualTo(record.length);
                assertThat(bytesOf(out, out.byteSize())).isEqualTo(record);
            }
        }
    }

    @Nested
    class HeapSegmentGuard {

        @Test
        void compressRejectsHeapSource() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx sut = new ZstdCompressCtx()) {
                // Given a heap-backed source segment handed to the zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When compressing from it
                ThrowingCallable result = () -> sut.compress(dst, heapSrc);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressRejectsHeapDestination() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx sut = new ZstdDecompressCtx()) {
                // Given a heap-backed destination segment handed to the zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When decompressing into it
                ThrowingCallable result = () -> sut.decompress(heapDst, src);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressRejectsHeapDestination() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx sut = new ZstdCompressCtx()) {
                // Given a heap-backed destination segment handed to the zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When compressing into it
                ThrowingCallable result = () -> sut.compress(heapDst, src);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressRejectsHeapSource() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx sut = new ZstdDecompressCtx()) {
                // Given a heap-backed source segment handed to the zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When decompressing from it
                ThrowingCallable result = () -> sut.decompress(dst, heapSrc);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressWithDictionaryRejectsHeapSource() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx sut = new ZstdCompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(dict)) {
                // Given a heap-backed source handed to the dictionary zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When compressing against the digested dictionary
                ThrowingCallable result = () -> sut.compress(dst, heapSrc, cdict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressWithDictionaryRejectsHeapDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressCtx sut = new ZstdCompressCtx();
                 ZstdCompressDict cdict = new ZstdCompressDict(dict)) {
                // Given a heap-backed destination handed to the dictionary zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When compressing against the digested dictionary
                ThrowingCallable result = () -> sut.compress(heapDst, src, cdict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressWithDictionaryRejectsHeapSource() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx sut = new ZstdDecompressCtx();
                 ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {
                // Given a heap-backed source handed to the dictionary zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When decompressing against the digested dictionary
                ThrowingCallable result = () -> sut.decompress(dst, heapSrc, ddict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressWithDictionaryRejectsHeapDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressCtx sut = new ZstdDecompressCtx();
                 ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {
                // Given a heap-backed destination handed to the dictionary zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When decompressing against the digested dictionary
                ThrowingCallable result = () -> sut.decompress(heapDst, src, ddict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressedSizeRejectsHeapFrame() {
            // Given a heap-backed frame segment
            MemorySegment heapFrame = MemorySegment.ofArray(new byte[8]);

            // When reading its decompressed size
            ThrowingCallable result = () -> Zstd.decompressedSize(heapFrame);

            // Then it fails fast with a clear message instead of a cryptic FFM error
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("native");
        }
    }

}
