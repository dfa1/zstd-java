package io.github.dfa1.zstd;

import java.util.OptionalLong;

/// Parsed contents of a zstd frame header, from `ZSTD_getFrameHeader`.
///
/// @param frameContentSize the stored decompressed size, or a sentinel if unknown;
///                         prefer [#contentSize()]
/// @param windowSize       the back-reference window size needed to decode the frame
/// @param blockSizeMax     the maximum block size used in the frame
/// @param frameType        whether this is a standard or skippable frame
/// @param headerSize       the size of the frame header in bytes
/// @param dictId           the dictionary id, or [ZstdDictionaryId#NONE] if none (for a
///                         skippable frame, [ZstdDictionaryId#raw()] is the magic variant 0..15)
/// @param hasChecksum      whether a 4-byte content checksum follows the frame
public record ZstdFrameHeader(
        long frameContentSize,
        long windowSize,
        long blockSizeMax,
        ZstdFrameType frameType,
        int headerSize,
        ZstdDictionaryId dictId,
        boolean hasChecksum) {

    /// The decompressed size, if the frame records it.
    ///
    /// @return the content size, or empty if the frame does not store it
    public OptionalLong contentSize() {
        return frameContentSize == Zstd.CONTENTSIZE_UNKNOWN
                ? OptionalLong.empty()
                : OptionalLong.of(frameContentSize);
    }
}
