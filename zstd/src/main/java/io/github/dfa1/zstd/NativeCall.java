package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Package-private helpers that adapt raw FFM downcalls to the zstd error
/// convention: run a native call, decode a zstd `size_t` error code into a
/// {@link ZstdException}, and guard native-segment arguments. Shared by the
/// binding classes so the conventions live in one place.
final class NativeCall {

    /// A native call returning a zstd `size_t` status that may encode an error.
    @FunctionalInterface
    interface ZstdCall {
        long run() throws Throwable;
    }

    /// Invokes a size-returning zstd call and converts a zstd error code into a
    /// {@link ZstdException}.
    static long checkReturnValue(ZstdCall c) {
        long code;
        try {
            code = c.run();
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (isError(code)) {
            throw new ZstdException(errorName(code), ZstdErrorCode.of(errorCode(code)));
        }
        return code;
    }

    static boolean isError(long code) {
        try {
            return ((int) Bindings.IS_ERROR.invokeExact(code)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int errorCode(long code) {
        try {
            return (int) Bindings.GET_ERROR_CODE.invokeExact(code);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String errorName(long code) {
        try {
            MemorySegment p = (MemorySegment) Bindings.GET_ERROR_NAME.invokeExact(code);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Guards a zero-copy entry point: the segment handed to zstd must be backed
    /// by native (off-heap) memory, since its address is dereferenced in C. Fails
    /// fast with a clear message instead of the FFM linker's cryptic error.
    static void requireNative(MemorySegment seg, String name) {
        Objects.requireNonNull(seg, name);
        if (!seg.isNative()) {
            throw new IllegalArgumentException(
                    name + " must be a native (off-heap) MemorySegment; got a heap segment");
        }
    }

    /// Rethrows any `Throwable` as if unchecked, laundering the checked
    /// `Throwable` that {@link java.lang.invoke.MethodHandle#invokeExact} declares.
    /// The shared sink for every binding class's native-call catch blocks.
    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    private NativeCall() {
        // no instances
    }
}
