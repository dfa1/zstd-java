package io.github.dfa1.zstd;

import java.io.Serial;

/// Thrown when a zstd native call reports an error.
///
/// Unchecked: zstd errors on valid use of this API indicate either corrupt
/// input or a programming error (e.g. an undersized destination buffer), not a
/// recoverable I/O condition.
public final class ZstdException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// The zstd error category for this failure.
    private final ZstdErrorCode code;

    ZstdException(String message) {
        this(message, ZstdErrorCode.UNKNOWN);
    }

    ZstdException(String message, ZstdErrorCode code) {
        super(message);
        this.code = code;
    }

    /// The category of this error, for programmatic branching.
    ///
    /// @return the zstd error category, or [ZstdErrorCode#UNKNOWN] if the failure
    ///         did not originate from a zstd error code
    public ZstdErrorCode code() {
        return code;
    }
}
