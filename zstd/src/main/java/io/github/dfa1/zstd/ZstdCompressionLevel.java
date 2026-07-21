package io.github.dfa1.zstd;

/// A validated zstd compression level, replacing a naked `int` at every public
/// API boundary that takes one.
///
/// zstd levels span two very different ranges under one number. `1..`[#MAX] are
/// the discrete, named levels most people mean by "zstd level"; everything from
/// [#FASTEST] up to (but not including) `1` is a continuous fast-mode
/// acceleration knob (`ZSTD_c_targetLength` under the hood), not discrete named
/// levels. This type does not attempt to model that split — it validates that a
/// raw level falls within the range the linked libzstd actually accepts
/// ([Zstd#minCompressionLevel()]..[Zstd#maxCompressionLevel()]), catching a bad
/// level in Java before it reaches native code, rather than relying on zstd's
/// own clamping/error behavior.
///
/// Prefer the [#DEFAULT] / [#FASTEST] / [#MAX] constants for those specific
/// levels; construct directly for any other level.
///
/// @param value the raw zstd compression level
public record ZstdCompressionLevel(int value) {

    // Queried once: the linked libzstd's accepted range is fixed for the life of
    // the process, so caching it here saves two native calls
    // (ZSTD_minCLevel/ZSTD_maxCLevel) on every construction.
    private static final int MIN_ACCEPTED = Zstd.minCompressionLevel();
    private static final int MAX_ACCEPTED = Zstd.maxCompressionLevel();

    /// The level [Zstd#compress(byte[])] uses when none is given.
    public static final ZstdCompressionLevel DEFAULT = new ZstdCompressionLevel(Zstd.defaultCompressionLevel());

    /// The fastest, lowest-ratio level this linked libzstd accepts.
    public static final ZstdCompressionLevel FASTEST = new ZstdCompressionLevel(MIN_ACCEPTED);

    /// The slowest, highest-ratio level this linked libzstd accepts.
    public static final ZstdCompressionLevel MAX = new ZstdCompressionLevel(MAX_ACCEPTED);

    /// Validates `value` against the linked libzstd's accepted range.
    public ZstdCompressionLevel {
        if (value < MIN_ACCEPTED || value > MAX_ACCEPTED) {
            throw new IllegalArgumentException("level " + value + " outside [" + MIN_ACCEPTED + ", " + MAX_ACCEPTED + "]");
        }
    }
}
