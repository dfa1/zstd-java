package io.github.dfa1.zstd;

/// The kind of a zstd frame, from `ZSTD_FrameType_e`.
public enum ZstdFrameType {

    /// A normal compressed zstd frame.
    STANDARD,
    /// A skippable frame: arbitrary user data a zstd decoder ignores.
    SKIPPABLE;

    static ZstdFrameType of(int value) {
        return value == 1 ? SKIPPABLE : STANDARD;
    }
}
