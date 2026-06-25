package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdTest {

	@Test
	void roundTripsText() {
		byte[] original = "the quick brown fox jumps over the lazy dog".repeat(50)
				.getBytes(StandardCharsets.UTF_8);

		byte[] packed = Zstd.compress(original);

		assertThat(packed.length).isLessThan(original.length);
		assertThat(Zstd.decompress(packed)).isEqualTo(original);
	}

	@Test
	void roundTripsEmpty() {
		byte[] packed = Zstd.compress(new byte[0]);
		assertThat(Zstd.decompress(packed)).isEmpty();
	}

	@Test
	void roundTripsBinaryAtEveryLevel() {
		byte[] original = new byte[64 * 1024];
		new Random(42).nextBytes(original);

		for (int level : new int[]{Zstd.minCompressionLevel(), 1, Zstd.defaultCompressionLevel(),
				Zstd.maxCompressionLevel()}) {
			byte[] packed = Zstd.compress(original, level);
			assertThat(Zstd.decompress(packed)).as("level %d", level).isEqualTo(original);
		}
	}

	@Test
	void compressBoundExceedsInput() {
		assertThat(Zstd.compressBound(1000)).isGreaterThanOrEqualTo(1000);
	}

	@Test
	void rejectsCorruptFrame() {
		byte[] garbage = "not a zstd frame".getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> Zstd.decompress(garbage)).isInstanceOf(ZstdException.class);
	}

	@Test
	void reportsVersion() {
		assertThat(Zstd.version()).matches("\\d+\\.\\d+\\.\\d+");
	}

	@Test
	void reusableContextsRoundTrip() {
		byte[] original = "payload-".repeat(1000).getBytes(StandardCharsets.UTF_8);

		try (ZstdCompressCtx cctx = new ZstdCompressCtx().level(19);
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {

			byte[] packed = cctx.compress(original);
			byte[] restored = dctx.decompress(packed, original.length);

			assertThat(restored).isEqualTo(original);
			// second use of the same contexts must also work
			assertThat(dctx.decompress(cctx.compress(original), original.length)).isEqualTo(original);
		}
	}

	@Test
	void closedContextRejectsUse() {
		ZstdCompressCtx ctx = new ZstdCompressCtx();
		ctx.close();
		assertThatThrownBy(() -> ctx.compress(new byte[1])).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void streamWithUnknownSizeNeedsMaxSize() {
		// frames produced here DO store size, so plain decompress works;
		// decompress(src, maxSize) must agree.
		byte[] original = Arrays.copyOf("abc".getBytes(StandardCharsets.UTF_8), 9);
		byte[] packed = Zstd.compress(original);
		assertThat(Zstd.decompress(packed, 100)).isEqualTo(original);
	}
}
