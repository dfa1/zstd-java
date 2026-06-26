package io.github.dfa1.zstd;

/// Controls how much a [ZstdCompressStream#compress] call finalises, mirroring
/// `ZSTD_EndDirective`.
public enum ZstdEndDirective {

    /// Collect more input; flush only what the internal buffers spill.
    CONTINUE(0),
    /// Flush all buffered data to the output, without ending the frame.
    FLUSH(1),
    /// Flush everything and close the frame.
    END(2);

    private final int value;

    ZstdEndDirective(int value) {
        this.value = value;
    }

    int value() {
        return value;
    }
}
