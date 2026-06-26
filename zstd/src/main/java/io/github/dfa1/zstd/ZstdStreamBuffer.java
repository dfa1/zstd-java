package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

// Backing for ZSTD_inBuffer / ZSTD_outBuffer, which share a layout of a
// void pointer then two size_t values. On LP64: ptr@0 (8), size@8 (8),
// pos@16 (8) -> 24 bytes.
final class ZstdStreamBuffer {

    static final long BYTES = 24;
    private static final long OFF_PTR = 0;
    private static final long OFF_SIZE = 8;
    private static final long OFF_POS = 16;

    private final MemorySegment struct;

    ZstdStreamBuffer(Arena arena) {
        this.struct = arena.allocate(BYTES);
    }

    MemorySegment segment() {
        return struct;
    }

    void set(MemorySegment buffer, long size, long pos) {
        struct.set(ADDRESS, OFF_PTR, buffer);
        struct.set(JAVA_LONG, OFF_SIZE, size);
        struct.set(JAVA_LONG, OFF_POS, pos);
    }

    long size() {
        return struct.get(JAVA_LONG, OFF_SIZE);
    }

    long pos() {
        return struct.get(JAVA_LONG, OFF_POS);
    }
}
