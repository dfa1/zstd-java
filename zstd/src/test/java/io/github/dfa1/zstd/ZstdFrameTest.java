package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
            // Given bytes that are not a zstd frame
            byte[] garbage = "xxxx".getBytes(StandardCharsets.UTF_8);

            // When asking for the first frame's compressed size
            ThrowingCallable result = () -> ZstdFrame.compressedSize(garbage);

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
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
            // Given bytes that are not valid zstd data
            byte[] garbage = "xx".getBytes(StandardCharsets.UTF_8);

            // When asking for the decompressed bound
            ThrowingCallable result = () -> ZstdFrame.decompressedBound(garbage);

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class Header {

        @Test
        void reportsContentSizeAndNoChecksumByDefault() {
            // Given a default frame
            ZstdFrameHeader header = ZstdFrame.header(Zstd.compress(PAYLOAD));

            // Then it is a standard frame storing the content size, no checksum, no dict
            assertThat(header.frameType()).isEqualTo(ZstdFrameType.STANDARD);
            assertThat(header.contentSize()).hasValue(PAYLOAD.length);
            assertThat(header.hasChecksum()).isFalse();
            assertThat(header.dictId()).isEqualTo(ZstdDictionaryId.NONE);
            assertThat(header.windowSize()).isPositive();
        }

        @Test
        void reflectsChecksumFlag() {
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx().checksum(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(ZstdFrame.header(frame).hasChecksum()).isTrue();
        }

        @Test
        void reportsASaneBlockSizeMax() {
            // Given a default frame
            ZstdFrameHeader header = ZstdFrame.header(Zstd.compress(PAYLOAD));

            // Then blockSizeMax is the masked 32-bit field — a real block size, never
            // the all-ones value a sign-extension or wrong mask would produce
            assertThat(header.blockSizeMax()).isPositive().isLessThanOrEqualTo(128 * 1024L);
        }

        @Test
        void rejectsATruncatedHeader() {
            // Given the first two bytes of a frame — too short to hold a header
            byte[] truncated = Arrays.copyOf(Zstd.compress(PAYLOAD), 2);

            // When its header is parsed
            ThrowingCallable result = () -> ZstdFrame.header(truncated);

            // Then it fails instead of returning a bogus header
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class Skippable {

        @Test
        void roundTripsContentAndMagicVariant() {
            // Given user metadata wrapped in a skippable frame
            byte[] meta = "sidecar metadata".getBytes(StandardCharsets.UTF_8);
            byte[] frame = ZstdFrame.writeSkippableFrame(meta, 7);

            // Then it is recognised as skippable and decodes back with its variant
            assertThat(ZstdFrame.isSkippableFrame(frame)).isTrue();
            assertThat(ZstdFrame.header(frame).frameType()).isEqualTo(ZstdFrameType.SKIPPABLE);

            ZstdSkippableContent read = ZstdFrame.readSkippableFrame(frame);
            assertThat(read.content()).isEqualTo(meta);
            assertThat(read.magicVariant()).isEqualTo(7);
        }

        @Test
        void standardFrameIsNotSkippable() {
            assertThat(ZstdFrame.isSkippableFrame(Zstd.compress(PAYLOAD))).isFalse();
        }

        @Test
        void defensivelyCopiesContentInAndOut() {
            // Given a backing array wrapped in a skippable-content value
            byte[] backing = "metadata".getBytes(StandardCharsets.UTF_8);
            ZstdSkippableContent content = new ZstdSkippableContent(backing, 2);

            // When the source array and a value returned by the accessor are mutated
            backing[0] = 'X';
            content.content()[1] = 'X';

            // Then the record's own bytes are untouched
            assertThat(content.content()).isEqualTo("metadata".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void contentHasValueEqualityOverTheBytesNotArrayIdentity() {
            // Given two separately built payloads with the same bytes and variant, and one differing
            ZstdSkippableContent a = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), 3);
            ZstdSkippableContent b = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), 3);
            ZstdSkippableContent differentVariant = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), 4);

            // When compared by value and rendered as text
            boolean sameBytesEqual = a.equals(b);
            boolean differentVariantEqual = a.equals(differentVariant);

            // Then equality and hashCode follow the content bytes, and toString omits the identity hash
            assertThat(sameBytesEqual).isTrue();
            assertThat(differentVariantEqual).isFalse();
            assertThat(a).hasSameHashCodeAs(b);
            assertThat(a).hasToString("ZstdSkippableContent[content=4 bytes, magicVariant=3]");
        }
    }

    @Nested
    class DictIdLookup {

        @Test
        void isNoneForDictionarylessFrame() {
            assertThat(ZstdFrame.dictId(Zstd.compress(PAYLOAD))).isEqualTo(ZstdDictionaryId.NONE);
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

        @Test
        void matchesThroughTheSegmentOverload() {
            // Given a dictionary frame in a native segment
            ZstdDictionary dict = trainDict();
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                frame = ctx.compress(PAYLOAD, dict);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the segment overload reports the same present id
                assertThat(ZstdFrame.dictId(seg)).isNotEqualTo(ZstdDictionaryId.NONE).isEqualTo(dict.id());
            }
        }

        private ZstdDictionary trainDict() {
            List<byte[]> samples = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(("{\"id\":" + i + ",\"k\":\"v" + (i % 30) + "\"}").getBytes(StandardCharsets.UTF_8));
            }
            return ZstdDictionary.train(samples, 8 * 1024);
        }
    }

    @Nested
    class SegmentOverloads {

        @Test
        void mirrorTheByteArrayOverloadsForANativeFrame() {
            // Given a frame as both a byte[] and the same bytes in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // When inspected through the zero-copy MemorySegment overloads
                // Then each agrees with its byte[] counterpart
                assertThat(ZstdFrame.isZstdFrame(seg)).isEqualTo(ZstdFrame.isZstdFrame(frame));
                assertThat(ZstdFrame.compressedSize(seg)).isEqualTo(ZstdFrame.compressedSize(frame));
                assertThat(ZstdFrame.decompressedBound(seg)).isEqualTo(ZstdFrame.decompressedBound(frame));
                assertThat(ZstdFrame.dictId(seg)).isEqualTo(ZstdFrame.dictId(frame));
            }
        }

        @Test
        void recognisesASkippableNativeFrame() {
            // Given a skippable frame in a native segment
            byte[] frame = ZstdFrame.writeSkippableFrame("meta".getBytes(StandardCharsets.UTF_8), 5);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // When tested through the MemorySegment overload / Then it is skippable
                assertThat(ZstdFrame.isSkippableFrame(seg)).isTrue();
            }
        }

        @Test
        void headerThroughTheSegmentOverloadMatchesTheByteArrayForm() {
            // Given a frame in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the parsed header equals the one from the byte[] overload
                assertThat(ZstdFrame.header(seg)).isEqualTo(ZstdFrame.header(frame));
            }
        }

        @Test
        void garbageNativeSegmentIsNotAFrame() {
            // Given non-frame bytes in a native segment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, "not a frame".getBytes(StandardCharsets.UTF_8));

                // Then the segment overload rejects it
                assertThat(ZstdFrame.isZstdFrame(seg)).isFalse();
            }
        }

        @Test
        void standardNativeFrameIsNotSkippable() {
            // Given a standard frame in a native segment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, Zstd.compress(PAYLOAD));

                // Then the segment overload reports it is not skippable
                assertThat(ZstdFrame.isSkippableFrame(seg)).isFalse();
            }
        }
    }
}
