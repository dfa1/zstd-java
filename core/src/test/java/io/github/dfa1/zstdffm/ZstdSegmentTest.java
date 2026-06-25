package io.github.dfa1.zstdffm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

class ZstdSegmentTest {

	@Test
	void zeroCopySegmentRoundTrip() {
		byte[] original = "segment payload ".repeat(200).getBytes(StandardCharsets.UTF_8);

		try (Arena arena = Arena.ofConfined();
		     ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {

			MemorySegment src = arena.allocate(original.length);
			MemorySegment.copy(original, 0, src, JAVA_BYTE, 0, original.length);

			MemorySegment dst = arena.allocate(Zstd.compressBound(original.length));
			long packedLen = cctx.compress(dst, src);
			MemorySegment frame = dst.asSlice(0, packedLen);

			// size the output from the frame header — no copy
			long outLen = Zstd.decompressedSize(frame);
			assertThat(outLen).isEqualTo(original.length);

			MemorySegment out = arena.allocate(outLen);
			long written = dctx.decompress(out, frame);

			byte[] restored = new byte[Math.toIntExact(written)];
			MemorySegment.copy(out, JAVA_BYTE, 0, restored, 0, restored.length);
			assertThat(restored).isEqualTo(original);
		}
	}

	@Test
	void zeroCopySegmentWithDigestedDict() {
		byte[] record = "{\"id\":42,\"user\":\"u\",\"active\":true}".getBytes(StandardCharsets.UTF_8);
		// minimal dictionary built from the record family
		java.util.List<byte[]> samples = new java.util.ArrayList<>();
		for (int i = 0; i < 2000; i++) {
			samples.add(("{\"id\":" + i + ",\"user\":\"u\",\"active\":" + (i % 2 == 0) + "}")
					.getBytes(StandardCharsets.UTF_8));
		}
		ZstdDictionary dict = ZstdDictionary.train(samples, 8 * 1024);

		try (Arena arena = Arena.ofConfined();
		     ZstdCompressCtx cctx = new ZstdCompressCtx();
		     ZstdDecompressCtx dctx = new ZstdDecompressCtx();
		     ZstdCompressDict cdict = new ZstdCompressDict(dict);
		     ZstdDecompressDict ddict = new ZstdDecompressDict(dict)) {

			MemorySegment src = arena.allocate(record.length);
			MemorySegment.copy(record, 0, src, JAVA_BYTE, 0, record.length);

			MemorySegment dst = arena.allocate(Zstd.compressBound(record.length));
			long packedLen = cctx.compress(dst, src, cdict);

			MemorySegment out = arena.allocate(record.length);
			long written = dctx.decompress(out, dst.asSlice(0, packedLen), ddict);

			byte[] restored = new byte[Math.toIntExact(written)];
			MemorySegment.copy(out, JAVA_BYTE, 0, restored, 0, restored.length);
			assertThat(restored).isEqualTo(record);
		}
	}
}
