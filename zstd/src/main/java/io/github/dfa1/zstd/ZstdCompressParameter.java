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
    /// Enable long-distance matching (1) for better ratio on large inputs.
    ENABLE_LONG_DISTANCE_MATCHING(160),
    /// Write the decompressed size into the frame header (1, the default) or not (0).
    CONTENT_SIZE_FLAG(200),
    /// Append a 32-bit content checksum to the frame (1) for integrity, or not (0, the default).
    CHECKSUM_FLAG(201),
    /// Write the dictionary id into the frame header (1, the default) or not (0).
    DICT_ID_FLAG(202),
    /// Number of worker threads (0 = single-threaded). Requires a multithreaded
    /// build of the native library; the bundled library is single-threaded, so a
    /// non-zero value has no effect.
    NB_WORKERS(400);

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
}
