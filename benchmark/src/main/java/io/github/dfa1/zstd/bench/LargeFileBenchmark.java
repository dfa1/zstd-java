package io.github.dfa1.zstd.bench;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdStreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/// JMH-backed successor to the ad hoc `System.nanoTime` timing loops that
/// produced the mmap-vs-`zstd-jni` numbers in `docs/zero-copy.md`: whether
/// mapping a large file into one `MemorySegment` and streaming it through
/// [ZstdCompressStream] is actually faster than `zstd-jni`'s classic
/// `ZstdOutputStream`-over-`InputStream` path, and whether
/// `posix_madvise(WILLNEED)` changes the answer.
///
/// Four variants at each size, all reading/writing in `CHUNK`-sized (8 MiB)
/// steps so buffer size is not a confound between them:
///
/// - `zstdJavaMmap` — this library, `FileChannel.map` into a `MemorySegment`,
///   streamed through [ZstdCompressStream]. No I/O hint.
/// - `zstdJavaMmapWillNeed` — same, plus `posix_madvise(WILLNEED)` on the
///   mapped segment right after mapping.
/// - `zstdJavaStream` — this library's own [ZstdOutputStream], fed from a
///   `byte[]` read loop. Not mmap — isolates whether this library's own
///   streaming API is competitive independent of the mmap question.
/// - `zstdJniStream` — `zstd-jni`'s `ZstdOutputStream`, fed from the same
///   read loop.
///
/// There is no `FileInputStream#transferTo` variant: `transferTo`'s buffer
/// size is not configurable (a hardcoded 8 KiB when the destination isn't a
/// `FileOutputStream`, as `ZstdOutputStream` never is), so it cannot be made
/// to match `CHUNK` — comparing it here would confound "different code path"
/// with "different buffer size."
///
/// `posix_madvise` is POSIX-only (Linux, macOS); on Windows
/// `zstdJavaMmapWillNeed` silently degrades to plain `zstdJavaMmap` — expected,
/// not a bug, since there is no direct equivalent (`PrefetchVirtualMemory` is a
/// structurally different API).
///
/// Payload files are large (up to 10 GiB) and expensive to generate, so they
/// are cached in `${java.io.tmpdir}/zstd-java-bench-large-files/<size>.bin` and
/// reused across variants and reruns instead of being rewritten per trial.
/// Nothing deletes that directory automatically — clean it up by hand once
/// done. Because of the size range, a full run is disk- and time-heavy (tens
/// of minutes, several GiB free disk); always invoke with an explicit filter,
/// never as part of an unfiltered `java -jar benchmarks.jar`:
///
/// ```
/// java -jar benchmark/target/benchmarks.jar LargeFileBenchmark -p sizeLabel=64MiB
/// ```
@SuppressWarnings("restricted") // downcallHandle is a restricted FFM method
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class LargeFileBenchmark {

    private static final int CHUNK = 8 * 1024 * 1024;
    private static final int POSIX_MADV_WILLNEED = 3;

    private static final Path CACHE_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "zstd-java-bench-large-files");

    private static final Map<String, Long> SIZES = Map.of(
            "4MiB", 4L * 1024 * 1024,
            "64MiB", 64L * 1024 * 1024,
            "2.25GiB", (long) Integer.MAX_VALUE + 256L * 1024 * 1024,
            "4GiB", 4L * 1024 * 1024 * 1024,
            "10GiB", 10L * 1024 * 1024 * 1024);

    private static final Optional<MethodHandle> POSIX_MADVISE = lookupPosixMadvise();

    @Param({"4MiB", "64MiB", "2.25GiB", "4GiB", "10GiB"})
    private String sizeLabel;

    @Param({"3"})
    private int level;

    private Path file;
    private long size;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        size = SIZES.get(sizeLabel);
        file = CACHE_DIR.resolve(sizeLabel + ".bin");
        writeIfMissing(file, size);
    }

    @Benchmark
    public long zstdJavaMmap() throws IOException {
        return compressMmap(false);
    }

    @Benchmark
    public long zstdJavaMmapWillNeed() throws IOException {
        return compressMmap(true);
    }

    @Benchmark
    public long zstdJavaStream() throws IOException {
        CountingOutputStream sink = new CountingOutputStream();
        try (InputStream in = Files.newInputStream(file);
             ZstdOutputStream out = new ZstdOutputStream(sink, level)) {
            copy(in, out);
        }
        return sink.count();
    }

    @Benchmark
    public long zstdJniStream() throws IOException {
        CountingOutputStream sink = new CountingOutputStream();
        try (InputStream in = Files.newInputStream(file);
             com.github.luben.zstd.ZstdOutputStream out =
                     new com.github.luben.zstd.ZstdOutputStream(sink, level)) {
            copy(in, out);
        }
        return sink.count();
    }

    private long compressMmap(boolean advise) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined();
             ZstdCompressStream cs = new ZstdCompressStream(level);
             CountingChannel sink = new CountingChannel()) {
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            if (advise) {
                madviseWillNeed(mapped);
            }
            MemorySegment dst = arena.allocate(CHUNK);
            ByteBuffer dstBuf = dst.asByteBuffer();
            long srcOff = 0;
            ZstdStreamResult r;
            do {
                r = cs.compress(dst, mapped.asSlice(srcOff), ZstdEndDirective.END);
                srcOff += r.bytesConsumed();
                dstBuf.clear().limit((int) r.bytesProduced());
                sink.write(dstBuf);
            } while (!r.isComplete());
            return sink.count();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[CHUNK];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static void madviseWillNeed(MemorySegment mapped) {
        if (POSIX_MADVISE.isEmpty()) {
            return;
        }
        try {
            int rc = (int) POSIX_MADVISE.orElseThrow()
                    .invokeExact(mapped, mapped.byteSize(), POSIX_MADV_WILLNEED);
            if (rc != 0) {
                System.err.println("posix_madvise(WILLNEED) failed rc=" + rc);
            }
        } catch (Throwable t) {
            throw new RuntimeException("posix_madvise failed", t);
        }
    }

    private static Optional<MethodHandle> lookupPosixMadvise() {
        return Linker.nativeLinker().defaultLookup().find("posix_madvise")
                .map(addr -> Linker.nativeLinker().downcallHandle(
                        addr, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)));
    }

    private static void writeIfMissing(Path file, long size) throws IOException {
        if (Files.exists(file) && Files.size(file) == size) {
            return;
        }
        Files.createDirectories(file.getParent());
        System.out.println("[LargeFileBenchmark] writing " + size + " bytes to " + file);
        byte[] chunk = new byte[CHUNK];
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            while (written < size) {
                int len = (int) Math.min(CHUNK, size - written);
                for (int i = 0; i < len; i++) {
                    chunk[i] = expectedByteAt(written + i);
                }
                ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, len);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                written += len;
            }
        }
        System.out.println("[LargeFileBenchmark] done writing " + file);
    }

    private static byte expectedByteAt(long offset) {
        return (byte) ('a' + (offset % 8));
    }

    /// Counts bytes without retaining them — the compressed-output sink for the
    /// `byte[]`-fed variants. The returned count is also each `@Benchmark`
    /// method's return value, which is what stops the JIT from proving the
    /// whole compression call is dead code and eliminating it.
    private static final class CountingOutputStream extends OutputStream {

        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }

        long count() {
            return count;
        }
    }

    /// Counts bytes without retaining them — the compressed-output sink for
    /// [#compressMmap]. A [WritableByteChannel] rather than an [OutputStream]
    /// so the native `dst` segment can be handed over as a direct
    /// [ByteBuffer] view with no heap `byte[]` bounce on the output side,
    /// matching the zero-copy path this benchmark exists to measure.
    private static final class CountingChannel implements WritableByteChannel {

        private long count;
        private boolean open = true;

        // Always drains src fully in one call — unlike a real channel, so
        // callers don't need the usual while (hasRemaining()) write() loop.
        @Override
        public int write(ByteBuffer src) {
            int n = src.remaining();
            src.position(src.limit());
            count += n;
            return n;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        long count() {
            return count;
        }
    }
}
