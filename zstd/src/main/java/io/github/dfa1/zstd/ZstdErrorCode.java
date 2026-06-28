package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/// Categories of zstd error, exposed by [ZstdException#code()] so callers can
/// branch on the kind of failure (e.g. distinguish [#CHECKSUM_WRONG] from
/// [#DST_SIZE_TOO_SMALL]) instead of matching on message text.
///
/// The numeric values mirror `ZSTD_ErrorCode` in the vendored zstd header. zstd
/// does not guarantee these values across versions, but this library pins a
/// specific zstd source, so they are stable for a given release. An unrecognized
/// code maps to [#UNKNOWN].
public enum ZstdErrorCode {

    /// Not from a zstd error code (e.g. a NULL context or a training failure).
    UNKNOWN(-1),
    /// No error.
    NO_ERROR(0),
    /// Unspecified error.
    GENERIC(1),
    /// Input does not start with a recognized zstd frame prefix.
    PREFIX_UNKNOWN(10),
    /// Frame requires an unsupported zstd version.
    VERSION_UNSUPPORTED(12),
    /// Frame uses an unsupported parameter.
    FRAME_PARAMETER_UNSUPPORTED(14),
    /// Frame's window size exceeds the decoder's limit.
    FRAME_PARAMETER_WINDOW_TOO_LARGE(16),
    /// Compressed data is corrupt.
    CORRUPTION_DETECTED(20),
    /// Frame's content checksum did not match.
    CHECKSUM_WRONG(22),
    /// Literals section header is malformed.
    LITERALS_HEADER_WRONG(24),
    /// Dictionary content is corrupt.
    DICTIONARY_CORRUPTED(30),
    /// Frame was compressed with a different dictionary.
    DICTIONARY_WRONG(32),
    /// Dictionary could not be built.
    DICTIONARY_CREATION_FAILED(34),
    /// The given parameter is not supported.
    PARAMETER_UNSUPPORTED(40),
    /// The combination of parameters is not supported.
    PARAMETER_COMBINATION_UNSUPPORTED(41),
    /// A parameter value is outside its valid range.
    PARAMETER_OUT_OF_BOUND(42),
    /// Table log is too large.
    TABLE_LOG_TOO_LARGE(44),
    /// Maximum symbol value is too large.
    MAX_SYMBOL_VALUE_TOO_LARGE(46),
    /// Maximum symbol value is too small.
    MAX_SYMBOL_VALUE_TOO_SMALL(48),
    /// An uncompressed block could not be produced.
    CANNOT_PRODUCE_UNCOMPRESSED_BLOCK(49),
    /// A required stability condition was not respected.
    STABILITY_CONDITION_NOT_RESPECTED(50),
    /// Operation called at the wrong stage of the context lifecycle.
    STAGE_WRONG(60),
    /// Context was not initialized before use.
    INIT_MISSING(62),
    /// A memory allocation failed.
    MEMORY_ALLOCATION(64),
    /// The provided workspace is too small.
    WORKSPACE_TOO_SMALL(66),
    /// Destination buffer is too small to hold the result.
    DST_SIZE_TOO_SMALL(70),
    /// Declared source size does not match the data.
    SRC_SIZE_WRONG(72),
    /// Destination buffer pointer is NULL.
    DST_BUFFER_NULL(74),
    /// Streaming made no progress because the destination is full.
    NO_FORWARD_PROGRESS_DEST_FULL(80),
    /// Streaming made no progress because the input is empty.
    NO_FORWARD_PROGRESS_INPUT_EMPTY(82),
    /// Requested frame index is out of range (seekable format).
    FRAME_INDEX_TOO_LARGE(100),
    /// Seekable-format I/O error.
    SEEKABLE_IO(102),
    /// Destination buffer is otherwise invalid.
    DST_BUFFER_WRONG(104),
    /// Source buffer is otherwise invalid.
    SRC_BUFFER_WRONG(105),
    /// An external sequence producer failed.
    SEQUENCE_PRODUCER_FAILED(106),
    /// Externally supplied sequences are invalid.
    EXTERNAL_SEQUENCES_INVALID(107);

    private final int value;

    ZstdErrorCode(int value) {
        this.value = value;
    }

    /// The native `ZSTD_ErrorCode` integer for this category.
    ///
    /// @return the zstd error code value
    public int value() {
        return value;
    }

    /// zstd's canonical human-readable description of this error, from
    /// `ZSTD_getErrorString`.
    ///
    /// @return the description text
    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    public String description() {
        try {
            MemorySegment p = (MemorySegment) Bindings.GET_ERROR_STRING.invokeExact(value);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Maps a native `ZSTD_ErrorCode` integer to its category.
    ///
    /// @param value the native error code
    /// @return the matching category, or [#UNKNOWN] if unrecognized
    static ZstdErrorCode of(int value) {
        for (ZstdErrorCode code : values()) {
            if (code.value == value) {
                return code;
            }
        }
        return UNKNOWN;
    }
}
