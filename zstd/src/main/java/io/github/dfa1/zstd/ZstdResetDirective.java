package io.github.dfa1.zstd;

/// Selects what a context reset clears, mirroring `ZSTD_ResetDirective`.
///
/// Use it with [ZstdCompressContext#reset(ZstdResetDirective)] and
/// [ZstdDecompressContext#reset(ZstdResetDirective)] to recycle a context for the
/// next frame without freeing and recreating its native state.
public enum ZstdResetDirective {

    /// Abort the current frame and discard any unflushed data, keeping all
    /// parameters and the loaded dictionary. Cheap; use it between frames.
    SESSION_ONLY(1),
    /// Restore every parameter to its default and clear the dictionary. Only
    /// valid when no frame is in progress.
    PARAMETERS(2),
    /// Do both: reset the session and the parameters in one call.
    SESSION_AND_PARAMETERS(3);

    private final int value;

    ZstdResetDirective(int value) {
        this.value = value;
    }

    int value() {
        return value;
    }
}
