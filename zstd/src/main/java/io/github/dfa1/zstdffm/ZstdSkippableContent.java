package io.github.dfa1.zstdffm;

/// The decoded payload of a skippable frame, returned by
/// [ZstdFrame#readSkippableFrame(byte[])].
///
/// @param content      the user bytes carried by the skippable frame
/// @param magicVariant the variant 0..15 the frame was written with
public record ZstdSkippableContent(byte[] content, int magicVariant) {
}
