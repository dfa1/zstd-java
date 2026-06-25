package io.github.dfa1.zstd;

/// Advanced compression parameters settable on a [ZstdCompressCtx], mirroring
/// `ZSTD_cParameter` from the
/// [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).
///
/// Set them with [ZstdCompressCtx#parameter(ZstdCompressParameter, int)]; the
/// context applies them on its next [ZstdCompressCtx#compress(byte[])]. Values
/// are validated natively — an out-of-range value raises a [ZstdException].
public enum ZstdCompressParameter {

    /// Compression level. Prefer [ZstdCompressCtx#level(int)].
    COMPRESSION_LEVEL(100),
    /// Maximum back-reference distance, as a power of two (larger = better ratio, more memory).
    WINDOW_LOG(101),
    /// Size of the initial probe table, as a power of two.
    HASH_LOG(102),
    /// Size of the multi-probe search table, as a power of two.
    CHAIN_LOG(103),
    /// Number of search attempts, as a power of two.
    SEARCH_LOG(104),
    /// Minimum match length, in bytes.
    MIN_MATCH(105),
    /// Target match length; impact depends on the strategy.
    TARGET_LENGTH(106),
    /// Compression strategy (1..9: fast … btultra2).
    STRATEGY(107),
    /// Target uncompressed block size, in bytes (0 = no target); trades ratio for latency.
    TARGET_C_BLOCK_SIZE(130),
    /// Enable long-distance matching (1) for better ratio on large inputs.
    ENABLE_LONG_DISTANCE_MATCHING(160),
    /// Long-distance matching hash table size, as a power of two.
    LDM_HASH_LOG(161),
    /// Minimum match length for the long-distance matcher, in bytes.
    LDM_MIN_MATCH(162),
    /// Log size of each bucket in the long-distance matcher's hash table.
    LDM_BUCKET_SIZE_LOG(163),
    /// How often to insert entries into the long-distance matcher's hash table, as a log.
    LDM_HASH_RATE_LOG(164),
    /// Write the decompressed size into the frame header (1, the default) or not (0).
    CONTENT_SIZE_FLAG(200),
    /// Append a 32-bit content checksum to the frame (1) for integrity, or not (0, the default).
    CHECKSUM_FLAG(201),
    /// Write the dictionary id into the frame header (1, the default) or not (0).
    DICT_ID_FLAG(202),
    /// Number of worker threads (0 = single-threaded). Requires a multithreaded
    /// build of the native library; the bundled library is single-threaded, so a
    /// non-zero value has no effect.
    NB_WORKERS(400),
    /// Size of a compression job, in bytes, when multithreading (0 = automatic).
    JOB_SIZE(401),
    /// Overlap between consecutive multithreading jobs, as a log fraction of the window.
    OVERLAP_LOG(402);

    private final int value;

    ZstdCompressParameter(int value) {
        this.value = value;
    }

    /// The native `ZSTD_cParameter` integer value.
    ///
    /// @return the enum value as defined by zstd
    int value() {
        return value;
    }

    /// The inclusive `[lower, upper]` range of values this parameter accepts,
    /// queried from zstd via `ZSTD_cParam_getBounds`.
    ///
    /// @return the valid bounds for this parameter
    /// @throws ZstdException if zstd reports no bounds for this parameter
    public ZstdBounds bounds() {
        return ZstdBounds.query(Bindings.CPARAM_GET_BOUNDS, value);
    }
}
