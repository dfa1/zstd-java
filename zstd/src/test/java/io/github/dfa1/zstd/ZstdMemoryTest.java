package io.github.dfa1.zstd;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;

class ZstdMemoryTest {

    private static final byte[] PAYLOAD = "memory accounting ".repeat(500).getBytes(StandardCharsets.UTF_8);

    @Nested
    class Estimates {

        @Test
        void contextEstimatesArePositive() {
            assertThat(Zstd.estimateCompressContextSize(new ZstdCompressionLevel(3)).value()).isPositive();
            assertThat(Zstd.estimateDecompressContextSize().value()).isPositive();
        }

        @Test
        void higherLevelEstimatesAtLeastAsLarge() {
            assertThat(Zstd.estimateCompressContextSize(new ZstdCompressionLevel(19)).value())
                    .isGreaterThanOrEqualTo(Zstd.estimateCompressContextSize(new ZstdCompressionLevel(1)).value());
        }

        @Test
        void dictionaryEstimatesArePositive() {
            assertThat(Zstd.estimateCompressDictSize(ZstdByteSize.ofKiB(64), new ZstdCompressionLevel(3)).value())
                    .isPositive();
            assertThat(Zstd.estimateDecompressDictSize(ZstdByteSize.ofKiB(64)).value()).isPositive();
        }
    }

    @Nested
    class LiveSizes {

        @Test
        void contextSizeGrowsAfterUse() {
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                long before = ctx.sizeOf().value();
                ctx.compress(PAYLOAD);
                long after = ctx.sizeOf().value();
                assertThat(before).isPositive();
                assertThat(after).isGreaterThanOrEqualTo(before);
            }
        }

        @Test
        void decompressContextHasSize() {
            try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
                assertThat(ctx.sizeOf().value()).isPositive();
            }
        }

        @Test
        void digestedDictionariesHaveSize() {
            ZstdDictionary dict = trainDictionary(2000);
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict)) {
                assertThat(cdict.sizeOf().value()).isPositive();
                assertThat(ddict.sizeOf().value()).isPositive();
            }
        }

        @Test
        void streamsReportContextSize() {
            try (ZstdCompressStream cs = new ZstdCompressStream();
                 ZstdDecompressStream ds = new ZstdDecompressStream()) {
                assertThat(cs.sizeOf().value()).isPositive();
                assertThat(ds.sizeOf().value()).isPositive();
            }
        }

    }
}
