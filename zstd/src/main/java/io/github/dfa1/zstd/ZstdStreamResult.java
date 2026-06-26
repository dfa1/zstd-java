package io.github.dfa1.zstd;

/// Outcome of one zero-copy streaming step.
///
/// @param bytesConsumed bytes read from the source segment
/// @param bytesProduced bytes written to the destination segment
/// @param remaining     zstd's hint of work left: for compression the bytes still
///                      buffered for the current directive, for decompression a
///                      non-zero value while the frame is incomplete; `0` means the
///                      directive (flush/end) or frame is fully done
public record ZstdStreamResult(long bytesConsumed, long bytesProduced, long remaining) {

    /// Whether the current directive or frame finished — no buffered data remains.
    ///
    /// @return `true` if `remaining` is zero
    public boolean isComplete() {
        return remaining == 0;
    }
}
