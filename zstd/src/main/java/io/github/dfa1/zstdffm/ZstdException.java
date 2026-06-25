package io.github.dfa1.zstdffm;

/// Thrown when a zstd native call reports an error.
///
/// Unchecked: zstd errors on valid use of this API indicate either corrupt
/// input or a programming error (e.g. an undersized destination buffer), not a
/// recoverable I/O condition.
public final class ZstdException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ZstdException(String message) {
        super(message);
    }
}
