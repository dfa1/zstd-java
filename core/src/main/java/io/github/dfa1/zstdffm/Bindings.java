package io.github.dfa1.zstdffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Central registry of bound zstd C functions.
///
/// `size_t` and `unsigned long long` are modelled as
/// {@link java.lang.foreign.ValueLayout#JAVA_LONG} (LP64); the public API guards
/// against negative interpretations where zstd uses sentinel values.
final class Bindings {

    // size_t ZSTD_compressBound(size_t srcSize)
    static final MethodHandle COMPRESS_BOUND =
            NativeLibrary.lookup("ZSTD_compressBound", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));

    // size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
    static final MethodHandle COMPRESS =
            NativeLibrary.lookup("ZSTD_compress",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));

    // size_t ZSTD_decompress(void* dst, size_t dstCap, const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESS =
            NativeLibrary.lookup("ZSTD_decompress",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned long long ZSTD_getFrameContentSize(const void* src, size_t srcSize)
    static final MethodHandle GET_FRAME_CONTENT_SIZE =
            NativeLibrary.lookup("ZSTD_getFrameContentSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_isError(size_t code)
    static final MethodHandle IS_ERROR =
            NativeLibrary.lookup("ZSTD_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    // const char* ZSTD_getErrorName(size_t code)
    static final MethodHandle GET_ERROR_NAME =
            NativeLibrary.lookup("ZSTD_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    // int ZSTD_maxCLevel(void) / ZSTD_minCLevel(void) / ZSTD_defaultCLevel(void)
    static final MethodHandle MAX_C_LEVEL =
            NativeLibrary.lookup("ZSTD_maxCLevel", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle MIN_C_LEVEL =
            NativeLibrary.lookup("ZSTD_minCLevel", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle DEFAULT_C_LEVEL =
            NativeLibrary.lookup("ZSTD_defaultCLevel", FunctionDescriptor.of(JAVA_INT));

    // unsigned ZSTD_versionNumber(void)
    static final MethodHandle VERSION_NUMBER =
            NativeLibrary.lookup("ZSTD_versionNumber", FunctionDescriptor.of(JAVA_INT));

    // const char* ZSTD_versionString(void)
    static final MethodHandle VERSION_STRING =
            NativeLibrary.lookup("ZSTD_versionString", FunctionDescriptor.of(ADDRESS));

    // --- reusable contexts ---

    // ZSTD_CCtx* ZSTD_createCCtx(void)
    static final MethodHandle CREATE_CCTX =
            NativeLibrary.lookup("ZSTD_createCCtx", FunctionDescriptor.of(ADDRESS));
    // size_t ZSTD_freeCCtx(ZSTD_CCtx*)
    static final MethodHandle FREE_CCTX =
            NativeLibrary.lookup("ZSTD_freeCCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_compressCCtx(ZSTD_CCtx*, void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
    static final MethodHandle COMPRESS_CCTX =
            NativeLibrary.lookup("ZSTD_compressCCtx",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));

    // ZSTD_DCtx* ZSTD_createDCtx(void)
    static final MethodHandle CREATE_DCTX =
            NativeLibrary.lookup("ZSTD_createDCtx", FunctionDescriptor.of(ADDRESS));
    // size_t ZSTD_freeDCtx(ZSTD_DCtx*)
    static final MethodHandle FREE_DCTX =
            NativeLibrary.lookup("ZSTD_freeDCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_decompressDCtx(ZSTD_DCtx*, void* dst, size_t dstCap, const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESS_DCTX =
            NativeLibrary.lookup("ZSTD_decompressDCtx",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    // --- dictionaries: raw (re-digested per call) ---

    // size_t ZSTD_compress_usingDict(ZSTD_CCtx*, dst, dstCap, src, srcSize, dict, dictSize, int level)
    static final MethodHandle COMPRESS_USING_DICT =
            NativeLibrary.lookup("ZSTD_compress_usingDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                            ADDRESS, JAVA_LONG, JAVA_INT));

    // size_t ZSTD_decompress_usingDict(ZSTD_DCtx*, dst, dstCap, src, srcSize, dict, dictSize)
    static final MethodHandle DECOMPRESS_USING_DICT =
            NativeLibrary.lookup("ZSTD_decompress_usingDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                            ADDRESS, JAVA_LONG));

    // --- dictionaries: digested (built once, reused) ---

    // ZSTD_CDict* ZSTD_createCDict(const void* dict, size_t dictSize, int level)
    static final MethodHandle CREATE_CDICT =
            NativeLibrary.lookup("ZSTD_createCDict",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    // size_t ZSTD_freeCDict(ZSTD_CDict*)
    static final MethodHandle FREE_CDICT =
            NativeLibrary.lookup("ZSTD_freeCDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_compress_usingCDict(ZSTD_CCtx*, dst, dstCap, src, srcSize, const ZSTD_CDict*)
    static final MethodHandle COMPRESS_USING_CDICT =
            NativeLibrary.lookup("ZSTD_compress_usingCDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));

    // ZSTD_DDict* ZSTD_createDDict(const void* dict, size_t dictSize)
    static final MethodHandle CREATE_DDICT =
            NativeLibrary.lookup("ZSTD_createDDict",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    // size_t ZSTD_freeDDict(ZSTD_DDict*)
    static final MethodHandle FREE_DDICT =
            NativeLibrary.lookup("ZSTD_freeDDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_decompress_usingDDict(ZSTD_DCtx*, dst, dstCap, src, srcSize, const ZSTD_DDict*)
    static final MethodHandle DECOMPRESS_USING_DDICT =
            NativeLibrary.lookup("ZSTD_decompress_usingDDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));

    // --- dictionary training (ZDICT, from dictBuilder) ---

    // size_t ZDICT_trainFromBuffer(void* dictBuffer, size_t dictBufferCapacity,
    //                              const void* samplesBuffer, const size_t* samplesSizes, unsigned nbSamples)
    static final MethodHandle ZDICT_TRAIN =
            NativeLibrary.lookup("ZDICT_trainFromBuffer",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    // unsigned ZDICT_isError(size_t)
    static final MethodHandle ZDICT_IS_ERROR =
            NativeLibrary.lookup("ZDICT_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    // const char* ZDICT_getErrorName(size_t)
    static final MethodHandle ZDICT_GET_ERROR_NAME =
            NativeLibrary.lookup("ZDICT_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    // unsigned ZDICT_getDictID(const void* dictBuffer, size_t dictSize)
    static final MethodHandle ZDICT_GET_DICT_ID =
            NativeLibrary.lookup("ZDICT_getDictID", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    private Bindings() {
        // no instances
    }
}
