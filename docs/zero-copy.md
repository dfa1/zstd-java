# Zero-copy with `MemorySegment`

zstd-java exposes two shapes of API:

- **`byte[]`** — convenient, for callers whose data is already on the heap.
- **`MemorySegment`** — zero-copy *at the call boundary*, for callers whose data
  is already off-heap.

This note explains why the segment shape exists and when it pays off. For the
recipes — sizing output, letting the codec allocate, `ByteBuffer` interop,
streaming, and pledging the size — see the [how-to guide](how-to.md).

## What "zero-copy" means here

It means **no copy at the Java↔native boundary** — the same sense as zero-copy
I/O, where bytes still move but not redundantly between buffers. Compression
itself always reads all input and writes all output; that is the work, not a
copy. "Zero-copy" is about the *boundary*, and applies only to the
`MemorySegment` path — the `byte[]` overloads copy twice (see the honest caveat).

## The core win: no copy at the call boundary

FFM downcalls need a *stable* native pointer. A heap `byte[]` can be relocated by
the GC, so the FFM runtime copies it into native memory for the duration of the
call — and copies the result back. **Two copies per call.**

A native `MemorySegment` already *is* a native address. You hand
`ZSTD_compress` / `ZSTD_decompress` the pointer directly. **No boundary copy.**

```text
byte[] path:   heap byte[] ──copy──▶ native scratch ──ZSTD──▶ native scratch ──copy──▶ heap byte[]
segment path:  native src ───────────────────────────ZSTD──▶ native dst        (no boundary copy)
```

## When it actually pays off (not always)

This only helps if the data is **already native** on both ends. The canonical
case is a memory-mapped reader (e.g. Vortex):

- **Compressed input** — the reader `mmap`s the file into one `MemorySegment`;
  the zstd frame is already a slice of it. A `byte[]` API forces
  `frame.toArray()` → `new byte[]` just to make the call. The segment API passes
  the mmap slice straight to `ZSTD_decompress`.
- **Decompressed output** — allocate the output in your arena
  (`arena.allocate(n)`) and let `ZSTD_decompress` write directly into it. That
  segment becomes the materialized backing buffer as-is — no temp `byte[]`, no
  `MemorySegment.copy`.

The decode path collapses from **mmap → byte[] → byte[] → arena** (three copies)
to **mmap-slice → arena** (no boundary copy).

## Secondary wins

- **Zero GC** — off-heap, no allocation churn in a scan hot loop (measured,
  not just asserted — see [the full reference table](#full-reference-every-variant-every-size-one-run)).
- **No 2 GiB cap** — `byte[]` maxes at `Integer.MAX_VALUE`; segments are
  `long`-indexed.
- **Lifetime safety** — bounds-checked, tied to a confined `Arena`; the same
  ownership model as the rest of an FFM reader, cleaner than raw pointers.
- **Typed reads** — read `JAVA_LONG` / `JAVA_DOUBLE` straight off the
  decompressed segment with no re-wrap.

## The 2 GiB cap: capability vs. throughput

The "no 2 GiB cap" line above is a **capability** claim, and it is unconditional:
`FileChannel.map(mode, pos, size, Arena)` is `long`-indexed and maps a file of
any size in one call, on every OS this library supports. The reference
`zstd-jni` binding cannot do this at all — its entire zero-copy surface is
`java.nio.ByteBuffer`-based, and the classic 3-arg `FileChannel.map(mode, pos,
size)` it depends on throws `IllegalArgumentException` past
`Integer.MAX_VALUE`. There is no workaround inside zstd-jni; the caller would
have to hand-roll mapping the file in sub-2 GiB windows. This is proven by a
runnable test — see [below](#proof-largememorymappedfiletest).

Whether that mapping is also **faster** than zstd-jni's fallback (a classic
`ZstdOutputStream` over `FileInputStream`, copying through a heap `byte[]`) is
a separate question, and the honest answer is: it depends on OS and size, not
a blanket yes. The macOS column below is JMH-backed
([`LargeFileBenchmark`](../benchmark/src/main/java/io/github/dfa1/zstd/bench/LargeFileBenchmark.java),
1 fork, 2 warmup + 3 measurement iterations — a quick, directional cut per
[ADR 0012](../adr/0012-benchmark-methodology.md), not the higher-fork/iteration
publication-grade run, but real iteration statistics with computed error, not
a hand-rolled timing loop). Linux and Windows have not been re-measured with
JMH yet and still carry the original ad hoc numbers — treat those two columns
as more provisional than the macOS one.

Single-threaded at zstd's default level (3), comparing this library's
mmap-and-stream path (no I/O hint) against zstd-jni's stream path, same
synthetic, highly-compressible file:

| size | macOS (Apple Silicon) | Linux (CI, x86_64) | Windows (CI, x86_64) |
|---|---|---|---|
| 4 MiB | roughly a wash, mmap slightly ahead | not measured | not measured |
| 64 MiB | mmap ~28% faster | not measured | not measured |
| 2.25 GiB | mmap ~21% faster | mmap ~2x faster | jni ~30% faster |
| 4 GiB | mmap ~23% faster | mmap ~2x faster | jni ~25% faster |
| 10 GiB | jni ~41% faster | not measured | not measured |

Two things stand out:

- **On macOS, the win reverses with scale** *if you map naively* — mmap is
  clearly ahead from 64 MiB through 4 GiB, then loses by 10 GiB. This turns
  out to be softenable, not fundamental — see
  [below](#the-macos-reversal-was-a-missing-madvise-hint) — though the fix
  closes most of the gap rather than flipping it outright.
- **On Windows, mmap never won** in the sizes tested, not even at 2.25 GiB.
  zstd-jni's classic `ReadFile`-backed stream path was consistently faster.
  Windows' memory-mapped-file implementation (`MapViewOfFile` under the hood)
  is apparently costlier relative to buffered reads than on Linux or macOS.
- **On Linux, mmap won cleanly at both sizes tested**, by roughly 2x, with no
  sign of the macOS-style reversal in this range.

Caveats, stated plainly: the Linux and Windows cells are still single-CI-run,
ad hoc timing-loop measurements — directional, not authoritative, and several
were never measured (marked "not measured" rather than assumed). The macOS
column is JMH-backed but still a single machine and a low-iteration cut, not
the fork/iteration counts this project uses for a publication-grade claim
(see `benchmark/`). If throughput matters for your use case, benchmark your
own workload, OS, and file size.

### The macOS reversal was a missing `madvise` hint

`FileChannel.map()` — both the `ByteBuffer` and `MemorySegment` overloads —
just calls `mmap()`/`MapViewOfFile` under the hood and nothing else. It never
tells the OS how the mapping will be accessed, so the kernel falls back to its
default readahead heuristic. For a large, purely-sequential scan (exactly what
a one-shot compress of an mmap'd file is), that default turned out to be a
real cost on macOS: isolating pure I/O with zstd removed from the loop
entirely (mmap-and-touch-every-byte vs. `read()`-and-touch-every-byte, no
compression at all) showed mmap already losing to buffered `read()` well
before 10 GiB — by ~30% at 2.25 GiB and ~3x at 4 GiB. The compression numbers
above only looked good at those sizes because the actual `ZSTD_compressStream2`
CPU work was masking a growing I/O penalty underneath it; by 10 GiB the masked
penalty had grown past what the compute side could hide.

Calling `posix_madvise(addr, len, POSIX_MADV_WILLNEED)` on the mapped segment
right after mapping it — a raw FFM downcall to libc, the same mechanism this
library already uses for `libzstd` itself — narrows the gap substantially.
JMH-backed (`LargeFileBenchmark`, same run as above), single-threaded, default
level, same synthetic file, macOS, mmap path with vs. without the hint,
compared again to zstd-jni's stream path:

| size | mmap (no hint) | mmap + `WILLNEED` | zstd-jni (stream) |
|---|---|---|---|
| 64 MiB | 7.9 ms | **6.5 ms** | 10.1 ms |
| 2.25 GiB | 305 ms | **242 ms** | 369 ms |
| 4 GiB | 540 ms | **429 ms** | 664 ms |
| 10 GiB | 2300 ms | 1671 ms | **1628 ms** |

The hint consistently cuts mmap's own time by ~18-27% at every size measured.
From 64 MiB through 4 GiB that's on top of an already-large lead — advised
mmap beats zstd-jni's stream path by roughly 50-57% there. At 10 GiB it closes
nearly all of the gap but not quite: 1671 ms vs. 1628 ms is a **statistical
tie** (well within each run's confidence interval), not the ~24% mmap win an
earlier, ad hoc (non-JMH) measurement of this same comparison had suggested.

That earlier number came from a `System.nanoTime` loop with no warmup and no
iteration statistics — and, it turned out once this was rebuilt as a real JMH
benchmark, an accidental extra `byte[]` copy on the compressed-*output* side
of the mmap path (copying the native destination buffer into a heap array
before handing it to the sink, instead of writing the native buffer directly).
That copy pessimized mmap specifically, on top of everything else the ad hoc
harness got wrong. Fixing it and re-measuring properly moved the mmap numbers
considerably (faster at every size below 10 GiB than the ad hoc run ever
showed) without changing the qualitative shape: **mmap, especially advised,
wins clearly up to 4 GiB; at 10 GiB it's a tie with zstd-jni's stream path,
not a win.** For reference, this library's own non-mmap streaming path
(`ZstdOutputStream`, no `MemorySegment` involved) tracks zstd-jni's stream
path closely at every size (1737 ms vs. 1628 ms at 10 GiB) — confirming the
10 GiB story is specifically about mmap at that scale, not about the FFM
binding generally.

Two things keep even these corrected numbers from being a settled conclusion:
it is still single-machine measurement (rerun on your own host before
quoting), and the hint has **only been tried on macOS**. Linux exposes the same
POSIX `posix_madvise`/`madvise` API but is untested here — it's plausible
either way, since Linux already won without any hint and may have little
headroom left to gain. Windows has no direct equivalent — the closest
primitive, `PrefetchVirtualMemory`, is a structurally different API and
hasn't been tried at all. This library does
not currently call `madvise` anywhere; doing so — and deciding whether it
belongs as an internal default, an opt-in flag, or just a documented recipe
for callers — is open follow-up work, not a shipped feature.

### Full reference: every variant, every size, one run

The percentages elsewhere in this doc are computed from these raw numbers —
`LargeFileBenchmark`, `-prof gc`, single run:

**Time (avgt, lower is better):**

| size | `zstdJavaMmap` | `zstdJavaMmapWillNeed` | `zstdJavaStream` | `zstdJniStream` |
|---|---:|---:|---:|---:|
| 4 MiB | 0.49 ms | 0.46 ms | 0.65 ms | 0.63 ms |
| 64 MiB | 7.8 ms | 6.7 ms | 10.7 ms | 10.2 ms |
| 2.25 GiB | 298 ms | 247 ms | 359 ms | 364 ms |
| 4 GiB | 536 ms | 433 ms | 668 ms | 645 ms |
| 10 GiB | 2349 ms | 1692 ms | 1751 ms | 1613 ms |

**Allocation (`gc.alloc.rate.norm`, bytes/op):**

| size | `zstdJavaMmap` | `zstdJavaMmapWillNeed` | `zstdJavaStream` | `zstdJniStream` |
|---|---:|---:|---:|---:|
| 4 MiB | 1,117 B | 1,156 B | 8,521,270 B | 8,520,896 B |
| 64 MiB | 1,424 B | 1,337 B | 8,521,312 B | 8,520,919 B |
| 2.25 GiB | 4,867 B | 4,286 B | 8,525,055 B | 8,521,162 B |
| 4 GiB | 2,977 B | 2,660 B | 8,522,406 B | 8,521,350 B |
| 10 GiB | 2,855 B | 2,624 B | 8,522,471 B | 8,521,912 B |

mmap is allocation-free at every size (a few KB of bookkeeping, never the
payload); the streaming variants allocate a constant ~8.1 MiB/op regardless
of file size — because `copy()`'s read buffer is a fixed 8 MiB `CHUNK`, not
sized to the file. That constant shows up starkly in `gc.count`: at 4 MiB,
where each op takes ~0.6 ms, JMH packs thousands of ops into a single
measurement window, and `zstdJavaStream` racked up 908 GC collections
(~349 ms total) in that window — compressing a 4 MiB file that repeatedly
allocates-and-discards an 8 MiB array. By 10 GiB, where each op is
multi-second, that same fixed allocation costs a single GC (~1-2 ms) —
negligible. This is a real, measured number, not swept under the rug — but
it's an artifact of this benchmark holding buffer size constant across sizes
for a fair cross-variant comparison, not something a size-aware production
implementation would do.

**Machine:** Apple M5, 32 GB RAM, macOS 26.5.2. JDK 25.0.2 (Zulu
`25.0.2+10-LTS`). zstd-jni 1.5.7-11 (bundles zstd 1.5.7, matching this
library's build). Level 3 (zstd default). 1 fork, 2 warmup + 3 measurement
iterations — a quick, directional cut per
[ADR 0012](../adr/0012-benchmark-methodology.md), not a publication-grade run.

**Reproduce:**

```bash
./mvnw -q -pl benchmark -am package -DskipTests
java -jar benchmark/target/benchmarks.jar LargeFileBenchmark -prof gc
```

Writes payload files up to 10 GiB under
`${java.io.tmpdir}/zstd-java-bench-large-files/` (cached across variants and
reruns, never deleted automatically — see
[benchmark/README.md](../benchmark/README.md)) and takes 15-30 minutes
depending on machine load. Always filter or exclude `LargeFileBenchmark`
explicitly — never run it as part of an unfiltered `benchmarks.jar`
invocation.

### Proof: `LargeMemoryMappedFileTest`

`integration-tests/.../LargeMemoryMappedFileTest` is a runnable, disabled-by-
default test that proves the *capability* claim: it writes a file just over
the `Integer.MAX_VALUE`-byte boundary, shows the classic `ByteBuffer` mapping
call rejects it, shows the FFM `MemorySegment` mapping call succeeds and
addresses bytes past the boundary correctly, and round-trips the whole file
through this library's zero-copy streaming compressor — plus a companion test
showing zstd-jni's stream API can still compress a file this large (just not
zero-copy map it) and that the frame it produces decodes correctly through
this library. It is gated behind a system property, since it writes several
gigabytes of real files to disk and is too slow/disk-hungry for a plain `mvn
test`/`mvn verify` or CI:

```
./mvnw -pl integration-tests test -Dzstd.test.large=true -Dtest=LargeMemoryMappedFileTest
```

## Is the extra code worth it?

The capability and throughput numbers above come from two codepaths that
don't tell the same story:

- **This library's own `ZstdOutputStream`** (`zstdJavaStream` in the
  benchmark) is exactly as easy to use as zstd-jni's — a five-line
  try-with-resources over a plain `InputStream`. But it is **not zero-copy**:
  its `write(byte[], ...)` still does a `MemorySegment.copy` from the heap
  into a native staging buffer per chunk, the same tax described in the
  [honest caveat](#honest-caveat) below. It isn't faster than zstd-jni's
  equivalent either — 1713 ms vs. 1616 ms at 10 GiB, a wash.
- **The raw `MemorySegment`/`ZstdCompressStream` path** (what
  `zstdJavaMmap`/`zstdJavaMmapWillNeed` actually do) is the one that's
  genuinely zero-copy — no heap bounce anywhere. It's also meaningfully more
  code: a manual `Arena`, a hand-driven `mmap → compress → drain` loop, and
  your own buffer/channel plumbing to get compressed bytes anywhere other
  than an in-memory sink, since `ZstdCompressStream` works in
  `MemorySegment`s, not `java.io` streams. `compressMmap` in
  [`LargeFileBenchmark`](../benchmark/src/main/java/io/github/dfa1/zstd/bench/LargeFileBenchmark.java)
  is ~15 lines including a custom `WritableByteChannel` sink, against the
  five-line `ZstdOutputStream` snippet above.

That harder path is also the one whose throughput edge shrinks to a
statistical tie right around the size (10 GiB) where the "no 2 GiB cap"
capability claim starts to matter most — below 2 GiB, zstd-jni's own
`ByteBuffer`-based zero-copy surface works fine, so the capability gap only
opens up exactly where, per the numbers above, the speed edge is closing.
**Mapping a file from scratch purely to hand it to zstd is not obviously
worth the extra code** on this data: you pay real `Arena`/`MemorySegment`
complexity for a result that, at large scale, roughly matches what five
lines of `ZstdOutputStream` already gets you.

Where the complexity *is* worth paying: when the `MemorySegment` is
**already in your hand** for reasons that have nothing to do with zstd — the
memory-mapped-reader case from
[above](#when-it-actually-pays-off-not-always) (Vortex-style: the file is
mapped because that's how the reader works, not because compression asked
for it). That caller is managing `Arena`/`MemorySegment` lifetimes in their
own code regardless; handing zstd a slice of a segment they already have is
a small marginal addition, not a 15-line tax paid from zero. The
benchmark's own scenario — mmap-from-scratch, compress, discard — is the
case this project has the least evidence for recommending.

## Honest caveat

If the caller hands you a heap `byte[]` (the aircompressor fallback path, or
external input), wrapping it with `MemorySegment.ofArray(...)` still triggers the
copy for the downcall — no free lunch. A heap `ByteBuffer` is the same: its
`MemorySegment.ofBuffer(...)` wrap is a heap segment and still copies. Only data
that is *already native* avoids the boundary copy. So the API is **segment-first
for the zero-copy fast path, with a thin `byte[]` overload** for the rare heap
caller.

We deliberately do **not** add a parallel `ByteBuffer` API surface: FFM already
defines the conversions (`MemorySegment.ofBuffer` in, `segment.asByteBuffer()`
out), so a direct buffer reaches the same path with one wrapping call — see the
[how-to](how-to.md).

## Why a streamed frame can't be decoded zero-copy

The zero-copy decode path reads the frame's **decompressed-size** header field to
size the output arena in one shot. zstd writes that field only when the encoder
knows the total up front — trivially true for one-shot `ZSTD_compress`, but a
*streaming* encoder is fed incrementally and closes the frame without ever being
told the total. So a plain `ZstdOutputStream` frame omits the size, and a
consumer is forced back onto the bounded streaming decoder (allocate, decode a
chunk, grow, repeat) — the very heap-bounce the segment API exists to avoid.

The fix is to **pledge the size** before the first byte, which stamps the content
size into the header and lets a downstream reader size the arena exactly. This is
not a micro-optimization but a correctness gate: it is the difference between a
frame that participates in the zero-copy decode path and one that does not. The
recipe is in the [how-to](how-to.md#pledge-the-size-so-a-streamed-frame-decodes-in-one-shot).
