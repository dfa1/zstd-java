package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdDictionaryTest {

	private static List<byte[]> samples;
	private static ZstdDictionary sut;

	@BeforeAll
	static void trainDictionary() {
		// Given many small, structurally-similar records — the case dictionaries win on
		samples = new ArrayList<>();
		for (int i = 0; i < 4000; i++) {
			samples.add(record(i));
		}
		sut = ZstdDictionary.train(samples, 16 * 1024);
	}

	@Nested
	class Training {

		@Test
		void producesNonEmptyDictionary() {
			// Then the trained dictionary has content matching its reported size
			assertThat(sut.size()).isGreaterThan(0);
			assertThat(sut.toByteArray()).hasSize(sut.size());
		}

		@Test
		void beatsDictionarylessOnTinyPayload() {
			// Given a single small record
			byte[] record = samples.get(123);

			// When compressed with and without the dictionary
			byte[] plain;
			byte[] withDict;
			try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
				plain = ctx.compress(record);
				withDict = ctx.compress(record, sut);
			}

			// Then the dictionary compresses the tiny record noticeably better
			assertThat(withDict.length).isLessThan(plain.length);
		}

		@Test
		void failsWithoutSamples() {
			// When training on no samples / Then it fails
			assertThatThrownBy(() -> ZstdDictionary.train(List.of(), 4096))
					.isInstanceOf(ZstdException.class);
		}
	}

	@Nested
	class RawDictionary {

		@ParameterizedTest
		@ValueSource(ints = {0, 7, 123, 2048, 3999})
		void roundTripsRecord(int index) {
			// Given a record
			byte[] record = samples.get(index);

			// When compressed and decompressed against the raw dictionary
			byte[] restored;
			try (ZstdCompressCtx cctx = new ZstdCompressCtx();
			     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
				byte[] frame = cctx.compress(record, sut);
				restored = dctx.decompress(frame, record.length, sut);
			}

			// Then the record is recovered
			assertThat(restored).isEqualTo(record);
		}
	}

	@Nested
	class DigestedDictionary {

		@Test
		void roundTripsViaCDictAndDDict() {
			// Given digested compress/decompress dictionaries at a fixed level
			byte[] record = samples.get(999);

			byte[] restored;
			int level;
			try (ZstdCompressCtx cctx = new ZstdCompressCtx();
			     ZstdDecompressCtx dctx = new ZstdDecompressCtx();
			     ZstdCompressDict cdict = new ZstdCompressDict(sut, 19);
			     ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {

				// When round-tripped through the digested dictionaries
				byte[] frame = cctx.compress(record, cdict);
				restored = dctx.decompress(frame, record.length, ddict);
				level = cdict.level();
			}

			// Then the record is recovered at the requested level
			assertThat(restored).isEqualTo(record);
			assertThat(level).isEqualTo(19);
		}

		@Test
		void interoperatesWithRawPath() {
			// Given a record compressed with the raw dictionary
			byte[] record = samples.get(2048);

			byte[] restored;
			try (ZstdCompressCtx cctx = new ZstdCompressCtx();
			     ZstdDecompressCtx dctx = new ZstdDecompressCtx();
			     ZstdDecompressDict ddict = new ZstdDecompressDict(sut)) {
				byte[] frame = cctx.compress(record, sut);

				// When decompressed with the digested dictionary
				restored = dctx.decompress(frame, record.length, ddict);
			}

			// Then the two dictionary forms interoperate
			assertThat(restored).isEqualTo(record);
		}
	}

	@Nested
	class Serialisation {

		@Test
		void reloadedDictionaryKeepsIdentityAndDecodes() {
			// Given the dictionary serialised and reloaded
			ZstdDictionary reloaded = ZstdDictionary.of(sut.toByteArray());

			// Then it carries the same dictionary id
			assertThat(reloaded.id()).isEqualTo(sut.id());

			// And a frame from the reload decodes against the original
			byte[] record = samples.get(1);
			byte[] restored;
			try (ZstdCompressCtx cctx = new ZstdCompressCtx();
			     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
				byte[] frame = cctx.compress(record, reloaded);
				restored = dctx.decompress(frame, record.length, sut);
			}
			assertThat(restored).isEqualTo(record);
		}
	}

	private static byte[] record(int i) {
		return ("{\"id\":" + i
				+ ",\"user\":\"user_" + (i % 50)
				+ "\",\"active\":" + (i % 2 == 0)
				+ ",\"score\":" + (i * 7 % 1000)
				+ ",\"tag\":\"event\"}").getBytes(StandardCharsets.UTF_8);
	}
}
