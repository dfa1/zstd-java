package io.github.dfa1.zstd;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// Shared low-level helpers for the segment-based tests: copy a `byte[]` into a
/// native segment, read it back out, and train a small dictionary from a common
/// structured schema. Kept in one place so the helpers cannot drift apart.
final class ZstdTestSupport {

    /// Copy `bytes` into a freshly allocated native segment in `arena`.
    /// Allocates at least one byte so a zero-length payload still yields a valid
    /// (non-null) segment.
    static MemorySegment segmentOf(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    /// Read the first `len` bytes out of `seg` into a heap `byte[]`.
    static byte[] bytesOf(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    /// `int`-length overload of [ZstdTestSupport#bytesOf(MemorySegment,long)].
    static byte[] bytesOf(MemorySegment seg, int len) {
        return bytesOf(seg, (long) len);
    }

    /// Train a dictionary from `sampleCount` structured sample records using an
    /// 8 KiB dictionary buffer.
    static ZstdDictionary trainDictionary(int sampleCount) {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < sampleCount; i++) {
            samples.add(("{\"id\":" + i + ",\"user\":\"user_" + (i % 100) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return ZstdDictionary.train(samples, 8 * 1024);
    }

    private ZstdTestSupport() {
        // no instances
    }
}
