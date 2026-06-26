package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// The inclusive range of values a compression or decompression parameter
/// accepts, from `ZSTD_cParam_getBounds` / `ZSTD_dParam_getBounds`.
///
/// @param lowerBound the smallest accepted value, inclusive
/// @param upperBound the largest accepted value, inclusive
public record ZstdBounds(int lowerBound, int upperBound) {

    /// Calls a `*_getBounds` function (which returns a `ZSTD_bounds` struct by
    /// value: `{ size_t error; int lowerBound; int upperBound; }`).
    static ZstdBounds query(MethodHandle getBounds, int parameter) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bounds = (MemorySegment) getBounds.invokeExact((SegmentAllocator) arena, parameter);
            long error = bounds.get(JAVA_LONG, 0);
            if (NativeCall.isError(error)) {
                throw new ZstdException("parameter has no queryable bounds");
            }
            return new ZstdBounds(bounds.get(JAVA_INT, 8), bounds.get(JAVA_INT, 12));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }
}
