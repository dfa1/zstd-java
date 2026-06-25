package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdDictionaryTest {

	private static List<byte[]> samples;
	private static ZstdDictionary dict;

	@BeforeAll
	static void trainDictionary() {
		// many small, structurally-similar records — the case dictionaries win on
		samples = new ArrayList<>();
		for (int i = 0; i < 4000; i++) {
			String record = "{\"id\":" + i
					+ ",\"user\":\"user_" + (i % 50)
					+ "\",\"active\":" + (i % 2 == 0)
					+ ",\"score\":" + (i * 7 % 1000)
					+ ",\"tag\":\"event\"}";
			samples.add(record.getBytes(StandardCharsets.UTF_8));
		}
		dict = ZstdDictionary.train(samples, 16 * 1024);
	}

	@Test
	void trainsNonEmptyDictionary() {
		assertThat(dict.size()).isGreaterThan(0);
		assertThat(dict.toByteArray()).hasSize(dict.size());
	}

	@Test
	void dictionaryBeatsDictionarylessOnTinyPayload() {
		byte[] record = samples.get(123);

		byte[] plain;
		byte[] withDict;
		try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
			plain = ctx.compress(record);
			withDict = ctx.compress(record, dict);
		}

		// on a ~60-byte record, the dictionary should compress noticeably better
		assertThat(withDict.length).isLessThan(plain.length);
	}

	@Test
	void rawDictionaryRoundTrips() {
		byte[] record = samples.get(7);
		try (ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {

			byte[] packed = cctx.compress(record, dict);
			byte[] restored = dctx.decompress(packed, record.length, dict);

			assertThat(restored).isEqualTo(record);
		}
	}

	@Test
	void digestedDictionaryRoundTrips() {
		byte[] record = samples.get(999);
		try (ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx();
		     ZstdCompressDict cdict = new ZstdCompressDict(dict, 19);
		     ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {

			byte[] packed = cctx.compress(record, cdict);
			byte[] restored = dctx.decompress(packed, record.length, ddict);

			assertThat(restored).isEqualTo(record);
			assertThat(cdict.level()).isEqualTo(19);
		}
	}

	@Test
	void rawAndDigestedPathsInteroperate() {
		byte[] record = samples.get(2048);
		try (ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx();
		     ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {

			// compress with raw dict, decompress with digested dict
			byte[] packed = cctx.compress(record, dict);
			assertThat(dctx.decompress(packed, record.length, ddict)).isEqualTo(record);
		}
	}

	@Test
	void serialisedDictionaryReloads() {
		ZstdDictionary reloaded = ZstdDictionary.of(dict.toByteArray());
		assertThat(reloaded.id()).isEqualTo(dict.id());

		byte[] record = samples.get(1);
		try (ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
			byte[] packed = cctx.compress(record, reloaded);
			assertThat(dctx.decompress(packed, record.length, dict)).isEqualTo(record);
		}
	}

	@Test
	void trainingWithNoSamplesFails() {
		assertThatThrownBy(() -> ZstdDictionary.train(List.of(), 4096))
				.isInstanceOf(ZstdException.class);
	}
}
