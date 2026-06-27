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
            assertThat(Zstd.estimateCompressContextSize(3)).isPositive();
            assertThat(Zstd.estimateDecompressContextSize()).isPositive();
        }

        @Test
        void higherLevelEstimatesAtLeastAsLarge() {
            assertThat(Zstd.estimateCompressContextSize(19))
                    .isGreaterThanOrEqualTo(Zstd.estimateCompressContextSize(1));
        }

        @Test
        void dictionaryEstimatesArePositive() {
            assertThat(Zstd.estimateCompressDictSize(64 * 1024, 3)).isPositive();
            assertThat(Zstd.estimateDecompressDictSize(64 * 1024)).isPositive();
        }
    }

    @Nested
    class LiveSizes {

        @Test
        void contextSizeGrowsAfterUse() {
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                long before = ctx.sizeOf();
                ctx.compress(PAYLOAD);
                long after = ctx.sizeOf();
                assertThat(before).isPositive();
                assertThat(after).isGreaterThanOrEqualTo(before);
            }
        }

        @Test
        void decompressContextHasSize() {
            try (ZstdDecompressCtx ctx = new ZstdDecompressCtx()) {
                assertThat(ctx.sizeOf()).isPositive();
            }
        }

        @Test
        void digestedDictionariesHaveSize() {
            ZstdDictionary dict = trainDict();
            try (ZstdCompressDict cdict = new ZstdCompressDict(dict);
                 ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {
                assertThat(cdict.sizeOf()).isPositive();
                assertThat(ddict.sizeOf()).isPositive();
            }
        }

        @Test
        void streamsReportContextSize() {
            try (ZstdCompressStream cs = new ZstdCompressStream();
                 ZstdDecompressStream ds = new ZstdDecompressStream()) {
                assertThat(cs.sizeOf()).isPositive();
                assertThat(ds.sizeOf()).isPositive();
            }
        }

        private ZstdDictionary trainDict() {
            return trainDictionary(2000);
        }
    }
}
