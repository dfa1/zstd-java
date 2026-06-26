package io.github.dfa1.zstd;

/// A live snapshot of a streaming compressor's progress, from
/// `ZSTD_getFrameProgression`.
///
/// @param ingested      input bytes read and buffered so far
/// @param consumed      input bytes actually compressed so far
/// @param produced      output bytes generated so far, including still-buffered
/// @param flushed       output bytes flushed out so far
/// @param currentJobId  current multithreading job id (0 when single-threaded)
/// @param activeWorkers number of worker threads currently active (0 when single-threaded)
public record ZstdFrameProgression(
        long ingested,
        long consumed,
        long produced,
        long flushed,
        int currentJobId,
        int activeWorkers) {
}
