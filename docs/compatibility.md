# Symbol compatibility

Coverage of the zstd C API by the `io.github.dfa1.zstdffm` bindings. Signatures
and semantics follow the [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).

- zstd version: **1.6.0** (vendored `third_party/zstd`)
- Public symbols exported by `libzstd`: **186**
- Bound so far: **44** (~24%)

"Bound" means the symbol has a `MethodHandle` in `Bindings` and is reachable
through the public Java API. The rest are reachable from native code but not yet
surfaced. Many unbound symbols are deprecated, experimental, or static-buffer /
low-level variants that an idiomatic Java API does not need.

> Regenerate the symbol list with
> `nm -gjU libzstd.dylib | sed 's/^_//' | grep -E '^(ZSTD|ZDICT)_'`.

## Coverage by area

| Area | Bound / Total | Notes |
|---|:---:|---|
| Core one-shot | 6 / 7 | compress/decompress/bound/levels — complete for practical use |
| Version | 2 / 2 | complete |
| Errors | 3 / 4 | name + typed `ZstdErrorCode` (via `getErrorCode`); `getErrorString` not bound |
| Reusable contexts | 6 / 8 | CCtx/DCtx create/free/compress/decompress |
| Dictionary — simple | 8 / 23 | raw + digested (CDict/DDict); `_advanced`/`_byReference` variants not bound |
| Dictionary training (ZDICT) | 4 / 12 | `trainFromBuffer`; cover/fastCover optimizers not bound |
| Streaming — compress | 3 / 22 | `ZstdOutputStream` (compressStream2 + buffer sizes) |
| Streaming — decompress | 3 / 15 | `ZstdInputStream` (decompressStream + buffer sizes) |
| Advanced parameters | 4 / 38 | `CCtx_setParameter` + `compress2` (level, checksum, LDM, windowLog) and `C/DCtx_loadDictionary` (dictionary streams); MT/getBounds not bound |
| Frame inspection | 5 / 13 | `ZstdFrame`: isFrame, compressedSize, decompressedBound, dictId, + getFrameContentSize; getFrameHeader/skippable not bound |
| Memory sizing | 0 / 14 | `sizeof_*` / `estimate*` accounting not bound |
| Low-level block | 0 / 12 | expert block/continue API not bound |
| Sequences | 0 / 5 | sequence producer API not bound |
| Misc / experimental | 0 / 11 | static-buffer init, param helpers, `copy*` not bound |

## Bound symbols → Java

| Native | Java surface |
|---|---|
| `ZSTD_compress`, `ZSTD_decompress`, `ZSTD_compressBound` | `Zstd.compress` / `decompress` / `compressBound` |
| `ZSTD_maxCLevel`, `ZSTD_minCLevel`, `ZSTD_defaultCLevel` | `Zstd.maxCompressionLevel` / `minCompressionLevel` / `defaultCompressionLevel` |
| `ZSTD_versionNumber`, `ZSTD_versionString` | `Zstd.version` |
| `ZSTD_isError`, `ZSTD_getErrorName` | internal error mapping in `Zstd` |
| `ZSTD_getFrameContentSize` | `Zstd.decompress(byte[])`, `Zstd.decompressedSize` |
| `ZSTD_createCCtx`, `ZSTD_freeCCtx`, `ZSTD_compressCCtx` | `ZstdCompressCtx` |
| `ZSTD_createDCtx`, `ZSTD_freeDCtx`, `ZSTD_decompressDCtx` | `ZstdDecompressCtx` |
| `ZSTD_compress_usingDict` | `ZstdCompressCtx.compress(byte[], ZstdDictionary)` |
| `ZSTD_decompress_usingDict` | `ZstdDecompressCtx.decompress(byte[], int, ZstdDictionary)` |
| `ZSTD_createCDict`, `ZSTD_freeCDict`, `ZSTD_compress_usingCDict` | `ZstdCompressDict` |
| `ZSTD_createDDict`, `ZSTD_freeDDict`, `ZSTD_decompress_usingDDict` | `ZstdDecompressDict` |
| `ZDICT_trainFromBuffer` | `ZstdDictionary.train` |
| `ZDICT_getDictID` | `ZstdDictionary.id` |
| `ZDICT_isError`, `ZDICT_getErrorName` | internal error mapping in `ZstdDictionary` |
| `ZSTD_compressStream2`, `ZSTD_CStreamInSize`, `ZSTD_CStreamOutSize`, `ZSTD_CCtx_setParameter` | `ZstdOutputStream` |
| `ZSTD_decompressStream`, `ZSTD_DStreamInSize`, `ZSTD_DStreamOutSize` | `ZstdInputStream` |
| `ZSTD_compress2`, `ZSTD_CCtx_setParameter` | `ZstdCompressCtx.parameter` / `checksum` / `longDistanceMatching` / `windowLog` (+ `ZstdCompressParameter`) |
| `ZSTD_CCtx_loadDictionary`, `ZSTD_DCtx_loadDictionary` | `ZstdOutputStream` / `ZstdInputStream` dictionary constructors |
| `ZSTD_isFrame`, `ZSTD_findFrameCompressedSize`, `ZSTD_decompressBound`, `ZSTD_getDictID_fromFrame` | `ZstdFrame` |
| `ZSTD_getErrorCode` | `ZstdException.code()` (+ `ZstdErrorCode`) |

## Roadmap (priority order)

1. ~~**Streaming**~~ — done: `ZstdOutputStream` / `ZstdInputStream` (`compressStream2`, `decompressStream`, bounded buffers, dictionary constructors). Remaining: `MemorySegment`-buffer driver, `pledgedSrcSize`.
2. **Advanced parameters** — done for compression: `CCtx_setParameter` + `compress2` via `ZstdCompressCtx` (`checksum`, `longDistanceMatching`, `windowLog`, generic `parameter`). Remaining: `cParam_getBounds`, `pledgedSrcSize`, and `nbWorkers` (needs a multithreaded native build).
3. **Frame inspection** — done: `ZstdFrame` (`isFrame`, `compressedSize`, `decompressedBound`, `dictId`). Remaining: `getFrameHeader` (struct out-param), skippable frames, `getDictID_fromDict/CDict/DDict`.
4. **Better dictionaries** — `ZDICT_optimizeTrainFromBuffer_cover` / `_fastCover`, `finalizeDictionary`.
5. ~~**Typed errors**~~ — done: `ZstdException.code()` returns `ZstdErrorCode` (via `getErrorCode`).

## Full symbol table

<!-- generated; see the nm command above to regenerate -->

### Core one-shot (6/7)

| Symbol | Bound |
|---|:---:|
| `ZSTD_compress` | ✅ |
| `ZSTD_compressBound` | ✅ |
| `ZSTD_decompress` | ✅ |
| `ZSTD_defaultCLevel` | ✅ |
| `ZSTD_maxCLevel` | ✅ |
| `ZSTD_minCLevel` | ✅ |
| `ZSTD_compress_advanced` | — |

### Version (2/2)

| Symbol | Bound |
|---|:---:|
| `ZSTD_versionNumber` | ✅ |
| `ZSTD_versionString` | ✅ |

### Errors (3/4)

| Symbol | Bound |
|---|:---:|
| `ZSTD_getErrorName` | ✅ |
| `ZSTD_isError` | ✅ |
| `ZSTD_getErrorCode` | ✅ |
| `ZSTD_getErrorString` | — |

### Reusable contexts (6/8)

| Symbol | Bound |
|---|:---:|
| `ZSTD_compressCCtx` | ✅ |
| `ZSTD_createCCtx` | ✅ |
| `ZSTD_createDCtx` | ✅ |
| `ZSTD_decompressDCtx` | ✅ |
| `ZSTD_freeCCtx` | ✅ |
| `ZSTD_freeDCtx` | ✅ |
| `ZSTD_createCCtx_advanced` | — |
| `ZSTD_createDCtx_advanced` | — |

### Dictionary — simple (8/23)

| Symbol | Bound |
|---|:---:|
| `ZSTD_compress_usingCDict` | ✅ |
| `ZSTD_compress_usingDict` | ✅ |
| `ZSTD_createCDict` | ✅ |
| `ZSTD_createDDict` | ✅ |
| `ZSTD_decompress_usingDDict` | ✅ |
| `ZSTD_decompress_usingDict` | ✅ |
| `ZSTD_freeCDict` | ✅ |
| `ZSTD_freeDDict` | ✅ |
| `ZSTD_compress_usingCDict_advanced` | — |
| `ZSTD_compressBegin_usingCDict` | — |
| `ZSTD_compressBegin_usingCDict_advanced` | — |
| `ZSTD_compressBegin_usingDict` | — |
| `ZSTD_createCDict_advanced` | — |
| `ZSTD_createCDict_advanced2` | — |
| `ZSTD_createCDict_byReference` | — |
| `ZSTD_createDDict_advanced` | — |
| `ZSTD_createDDict_byReference` | — |
| `ZSTD_decompressBegin_usingDDict` | — |
| `ZSTD_decompressBegin_usingDict` | — |
| `ZSTD_getDictID_fromCDict` | — |
| `ZSTD_getDictID_fromDDict` | — |
| `ZSTD_getDictID_fromDict` | — |
| `ZSTD_getDictID_fromFrame` | ✅ |

### Dictionary training, ZDICT (4/12)

| Symbol | Bound |
|---|:---:|
| `ZDICT_getDictID` | ✅ |
| `ZDICT_getErrorName` | ✅ |
| `ZDICT_isError` | ✅ |
| `ZDICT_trainFromBuffer` | ✅ |
| `ZDICT_addEntropyTablesFromBuffer` | — |
| `ZDICT_finalizeDictionary` | — |
| `ZDICT_getDictHeaderSize` | — |
| `ZDICT_optimizeTrainFromBuffer_cover` | — |
| `ZDICT_optimizeTrainFromBuffer_fastCover` | — |
| `ZDICT_trainFromBuffer_cover` | — |
| `ZDICT_trainFromBuffer_fastCover` | — |
| `ZDICT_trainFromBuffer_legacy` | — |

### Streaming — compress (3/22)

| Symbol | Bound |
|---|:---:|
| `ZSTD_CStreamInSize` | ✅ |
| `ZSTD_CStreamOutSize` | ✅ |
| `ZSTD_compressStream2` | ✅ |
| `ZSTD_compressStream` | — |
| `ZSTD_compressStream2_simpleArgs` | — |
| `ZSTD_createCStream` | — |
| `ZSTD_createCStream_advanced` | — |
| `ZSTD_endStream` | — |
| `ZSTD_estimateCStreamSize` | — |
| `ZSTD_estimateCStreamSize_usingCCtxParams` | — |
| `ZSTD_estimateCStreamSize_usingCParams` | — |
| `ZSTD_flushStream` | — |
| `ZSTD_freeCStream` | — |
| `ZSTD_initCStream` | — |
| `ZSTD_initCStream_advanced` | — |
| `ZSTD_initCStream_srcSize` | — |
| `ZSTD_initCStream_usingCDict` | — |
| `ZSTD_initCStream_usingCDict_advanced` | — |
| `ZSTD_initCStream_usingDict` | — |
| `ZSTD_initStaticCStream` | — |
| `ZSTD_resetCStream` | — |
| `ZSTD_sizeof_CStream` | — |

### Streaming — decompress (3/15)

| Symbol | Bound |
|---|:---:|
| `ZSTD_DStreamInSize` | ✅ |
| `ZSTD_DStreamOutSize` | ✅ |
| `ZSTD_decompressStream` | ✅ |
| `ZSTD_decompressStream_simpleArgs` | — |
| `ZSTD_createDStream` | — |
| `ZSTD_createDStream_advanced` | — |
| `ZSTD_estimateDStreamSize` | — |
| `ZSTD_estimateDStreamSize_fromFrame` | — |
| `ZSTD_freeDStream` | — |
| `ZSTD_initDStream` | — |
| `ZSTD_initDStream_usingDDict` | — |
| `ZSTD_initDStream_usingDict` | — |
| `ZSTD_initStaticDStream` | — |
| `ZSTD_resetDStream` | — |
| `ZSTD_sizeof_DStream` | — |

### Advanced parameters (4/38)

| Symbol | Bound |
|---|:---:|
| `ZSTD_CCtxParams_getParameter` | — |
| `ZSTD_CCtxParams_init` | — |
| `ZSTD_CCtxParams_init_advanced` | — |
| `ZSTD_CCtxParams_registerSequenceProducer` | — |
| `ZSTD_CCtxParams_reset` | — |
| `ZSTD_CCtxParams_setParameter` | — |
| `ZSTD_CCtx_getParameter` | — |
| `ZSTD_CCtx_loadDictionary` | ✅ |
| `ZSTD_CCtx_loadDictionary_advanced` | — |
| `ZSTD_CCtx_loadDictionary_byReference` | — |
| `ZSTD_CCtx_refCDict` | — |
| `ZSTD_CCtx_refPrefix` | — |
| `ZSTD_CCtx_refPrefix_advanced` | — |
| `ZSTD_CCtx_refThreadPool` | — |
| `ZSTD_CCtx_reset` | — |
| `ZSTD_CCtx_setCParams` | — |
| `ZSTD_CCtx_setFParams` | — |
| `ZSTD_CCtx_setParameter` | ✅ |
| `ZSTD_CCtx_setParametersUsingCCtxParams` | — |
| `ZSTD_CCtx_setParams` | — |
| `ZSTD_CCtx_setPledgedSrcSize` | — |
| `ZSTD_DCtx_getParameter` | — |
| `ZSTD_DCtx_loadDictionary` | ✅ |
| `ZSTD_DCtx_loadDictionary_advanced` | — |
| `ZSTD_DCtx_loadDictionary_byReference` | — |
| `ZSTD_DCtx_refDDict` | — |
| `ZSTD_DCtx_refPrefix` | — |
| `ZSTD_DCtx_refPrefix_advanced` | — |
| `ZSTD_DCtx_reset` | — |
| `ZSTD_DCtx_setFormat` | — |
| `ZSTD_DCtx_setMaxWindowSize` | — |
| `ZSTD_DCtx_setParameter` | — |
| `ZSTD_cParam_getBounds` | — |
| `ZSTD_compress2` | ✅ |
| `ZSTD_createCCtxParams` | — |
| `ZSTD_dParam_getBounds` | — |
| `ZSTD_estimateCCtxSize_usingCCtxParams` | — |
| `ZSTD_freeCCtxParams` | — |

### Frame inspection (5/13)

| Symbol | Bound |
|---|:---:|
| `ZSTD_getFrameContentSize` | ✅ |
| `ZSTD_decompressBound` | ✅ |
| `ZSTD_findDecompressedSize` | — |
| `ZSTD_findFrameCompressedSize` | ✅ |
| `ZSTD_frameHeaderSize` | — |
| `ZSTD_getDecompressedSize` | — |
| `ZSTD_getFrameHeader` | — |
| `ZSTD_getFrameHeader_advanced` | — |
| `ZSTD_getFrameProgression` | — |
| `ZSTD_isFrame` | ✅ |
| `ZSTD_isSkippableFrame` | — |
| `ZSTD_readSkippableFrame` | — |
| `ZSTD_writeSkippableFrame` | — |

### Memory sizing (0/14)

| Symbol | Bound |
|---|:---:|
| `ZSTD_decodingBufferSize_min` | — |
| `ZSTD_decompressionMargin` | — |
| `ZSTD_estimateCCtxSize` | — |
| `ZSTD_estimateCCtxSize_usingCParams` | — |
| `ZSTD_estimateCDictSize` | — |
| `ZSTD_estimateCDictSize_advanced` | — |
| `ZSTD_estimateDCtxSize` | — |
| `ZSTD_estimateDDictSize` | — |
| `ZSTD_getBlockSize` | — |
| `ZSTD_sequenceBound` | — |
| `ZSTD_sizeof_CCtx` | — |
| `ZSTD_sizeof_CDict` | — |
| `ZSTD_sizeof_DCtx` | — |
| `ZSTD_sizeof_DDict` | — |

### Low-level block (0/12)

| Symbol | Bound |
|---|:---:|
| `ZSTD_compressBegin` | — |
| `ZSTD_compressBegin_advanced` | — |
| `ZSTD_compressBlock` | — |
| `ZSTD_compressContinue` | — |
| `ZSTD_compressEnd` | — |
| `ZSTD_decompressBegin` | — |
| `ZSTD_decompressBlock` | — |
| `ZSTD_decompressContinue` | — |
| `ZSTD_insertBlock` | — |
| `ZSTD_nextInputType` | — |
| `ZSTD_nextSrcSizeToDecompress` | — |
| `ZSTD_toFlushNow` | — |

### Sequences (0/5)

| Symbol | Bound |
|---|:---:|
| `ZSTD_compressSequences` | — |
| `ZSTD_compressSequencesAndLiterals` | — |
| `ZSTD_generateSequences` | — |
| `ZSTD_mergeBlockDelimiters` | — |
| `ZSTD_registerSequenceProducer` | — |

### Misc / experimental (0/11)

| Symbol | Bound |
|---|:---:|
| `ZSTD_adjustCParams` | — |
| `ZSTD_checkCParams` | — |
| `ZSTD_copyCCtx` | — |
| `ZSTD_copyDCtx` | — |
| `ZSTD_getCParams` | — |
| `ZSTD_getParams` | — |
| `ZSTD_initStaticCCtx` | — |
| `ZSTD_initStaticCDict` | — |
| `ZSTD_initStaticDCtx` | — |
| `ZSTD_initStaticDDict` | — |
| `ZSTD_isDeterministicBuild` | — |
