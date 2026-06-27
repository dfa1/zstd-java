package io.github.dfa1.zstd;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RefPrefixTest {

    @Test
    void roundTripsWithMatchingPrefix() {
        // Given
        byte[] prefixBytes = "the quick brown fox jumps over the lazy dog".getBytes();
        byte[] dataBytes = "the quick brown fox jumps over the lazy cat".getBytes();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressCtx cctx = new ZstdCompressCtx();
             ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            MemorySegment prefix = copy(arena, prefixBytes);
            MemorySegment src = copy(arena, dataBytes);
            MemorySegment frame = arena.allocate(Zstd.compressBound(dataBytes.length));
            MemorySegment out = arena.allocate(dataBytes.length);

            // When
            cctx.refPrefix(prefix);
            long n = cctx.compress(frame, src);
            dctx.refPrefix(prefix);
            long m = dctx.decompress(out, frame.asSlice(0, n));

            // Then
            assertThat(m).isEqualTo(dataBytes.length);
            assertThat(bytes(out, (int) m)).isEqualTo(dataBytes);
        }
    }

    @Test
    void prefixIsAppliedAndRequiredToDecode() {
        // Given — incompressible (random) data identical to the prefix. With no
        // internal redundancy the encoder can only shrink it by matching against
        // the prefix, so a much smaller frame proves the prefix is applied.
        // (Level 19: the level-3 match finder is too weak to reach into the prefix
        // for random content.)
        byte[] random = randomBytes(0xBEEF, 16384);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prefix = copy(arena, random);
            MemorySegment src = copy(arena, random);

            long baseline;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19)) {
                baseline = cctx.compress(arena, src).byteSize();
            }
            byte[] frame;
            try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19)) {
                cctx.refPrefix(prefix);
                MemorySegment f = cctx.compress(arena, src);
                frame = bytes(f, (int) f.byteSize());
            }

            // Then — the prefix slashes the frame (16 KiB random → tens of bytes)
            assertThat((long) frame.length).isLessThan(baseline / 10);

            // And — it round-trips with the same prefix
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                MemorySegment out = arena.allocate(random.length);
                dctx.refPrefix(prefix);
                long m = dctx.decompress(out, copy(arena, frame));
                assertThat(bytes(out, (int) m)).isEqualTo(random);
            }

            // But — it cannot be recovered without the prefix
            boolean reproduced;
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                MemorySegment out = arena.allocate(random.length);
                long m = dctx.decompress(out, copy(arena, frame));
                reproduced = Arrays.equals(bytes(out, (int) m), random);
            } catch (ZstdException e) {
                reproduced = false;
            }
            assertThat(reproduced).isFalse();
        }
    }

    @Test
    void clearingPrefixWithNullCompressesPlainly() {
        // Given
        byte[] dataBytes = "the quick brown fox".getBytes();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            MemorySegment prefix = copy(arena, "a prior version of the text".getBytes());
            MemorySegment src = copy(arena, dataBytes);
            MemorySegment frame = arena.allocate(Zstd.compressBound(dataBytes.length));

            // When — set then clear the prefix before compressing
            cctx.refPrefix(prefix);
            cctx.refPrefix((MemorySegment) null);
            long n = cctx.compress(frame, src);

            // Then — a plain decoder with no prefix decodes it
            byte[] restored;
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                MemorySegment out = arena.allocate(dataBytes.length);
                long m = dctx.decompress(out, frame.asSlice(0, n));
                restored = bytes(out, (int) m);
            }
            assertThat(restored).isEqualTo(dataBytes);
        }
    }

    @Test
    void prefixIsSingleUseAndDoesNotStickAcrossFrames() {
        // Given — random data identical to the prefix (see prefixIsAppliedAndRequiredToDecode),
        // and one context kept open across two compressions with the prefix set once.
        byte[] random = randomBytes(0xCAFE, 16384);
        try (Arena arena = Arena.ofConfined();
             ZstdCompressCtx cctx = new ZstdCompressCtx().level(19)) {
            MemorySegment prefix = copy(arena, random);
            MemorySegment src = copy(arena, random);
            MemorySegment first = arena.allocate(Zstd.compressBound(random.length));
            MemorySegment second = arena.allocate(Zstd.compressBound(random.length));

            // When — first frame consumes the prefix; second is compressed with no re-set
            cctx.refPrefix(prefix);
            long n1 = cctx.compress(first, src);
            long n2 = cctx.compress(second, src);

            // Then — the prefix shrank only the first frame; the second got no prefix
            // (incompressible random with no prefix stays ~full size)
            assertThat(n1).isLessThan(n2 / 10);

            // And — the second frame carries no prefix, so a plain decoder decodes it
            byte[] restored;
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                MemorySegment out = arena.allocate(random.length);
                long m = dctx.decompress(out, second.asSlice(0, n2));
                restored = bytes(out, (int) m);
            }
            assertThat(restored).isEqualTo(random);
        }
    }

    @Test
    void compressRefPrefixRejectsAHeapSegment() {
        // Given
        MemorySegment heap = MemorySegment.ofArray("prefix".getBytes());

        // When
        ThrowingCallable result = () -> {
            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                cctx.refPrefix(heap);
            }
        };

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    void decompressRefPrefixRejectsAHeapSegment() {
        // Given
        MemorySegment heap = MemorySegment.ofArray("prefix".getBytes());

        // When
        ThrowingCallable result = () -> {
            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                dctx.refPrefix(heap);
            }
        };

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
    }

    private static MemorySegment copy(Arena arena, byte[] src) {
        MemorySegment seg = arena.allocate(src.length);
        MemorySegment.copy(src, 0, seg, JAVA_BYTE, 0, src.length);
        return seg;
    }

    private static byte[] bytes(MemorySegment seg, int len) {
        byte[] out = new byte[len];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, len);
        return out;
    }

    private static byte[] randomBytes(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}
