# Benchmarks

JMH microbenchmarks comparing **zstd-java** against the common JVM zstd options,
in both heap (`byte[]`) and zero-copy (`MemorySegment`) modes. Source lives in
the [`benchmark/`](../benchmark) module; see its
[README](../benchmark/README.md) to reproduce.

| Contestant | Binding | Modes |
|------------|---------|-------|
| **zstd-java** (this project) | FFM (no JNI) | `byte[]` and zero-copy `MemorySegment` |
| **zstd-jni** (`com.github.luben`) | JNI | `byte[]` |
| **aircompressor** (`io.airlift:aircompressor-v3`) | pure Java | caller-buffer `byte[]` |

## TL;DR

- **Throughput:** zstd-java's `MemorySegment` path is fastest at small/cache-resident
  payloads (~5-9% over `byte[]`, more over JNI). The edge **shrinks to nothing at
  64 MiB**, where everything is DRAM-bandwidth bound and converges. aircompressor
  (pure Java) trails the native bindings, ~2x on compress.
- **Allocation:** this is the real `MemorySegment` win. The segment path is
  **allocation-free** (flat ~0 B/op at any size); the `byte[]`/JNI paths allocate
  ~the output size **on every call**. At 64 MiB the `byte[]` path churns 67 MB/op
  through the young generation; JNI compress churns ~79 MB/op. Invisible in
  throughput, brutal under sustained load.

The headline isn't raw speed — it's **eliminating per-op heap allocation**, which
matters most exactly where throughput converges (large, bandwidth-bound payloads
under GC).

## Environment

- Apple M5, 32 GB. P-core L2 16 MiB (Apple Silicon: shared SLC, no classic L3).
  The 64 MiB payload is the cache-busting case.
- JDK 25 (Azul). zstd-jni 1.5.7-11, aircompressor-v3 3.6, JMH 1.37.
- Level 3 (zstd default). Payloads are deterministic, ~3x-compressible text.

**These are a quick, low-iteration run (1 fork, 2 warmup, 3 measurement) — a
directional read, not publication-grade. The 64 MiB rows in particular have wide
intervals (few ops per iteration). Rerun with JMH defaults on the target host
before quoting.**

## Throughput (ops/ms, higher is better)

### Compress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **287.7** | 278.2 | 238.3 | 148.3 |
| 64 KiB | **14.34** | 13.60 | 13.46 | 8.00 |
| 1 MiB | **0.927** | 0.889 | 0.906 | 0.562 |
| 64 MiB | 0.014 | 0.014 | 0.013 | 0.007 |

### Decompress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **590.7** | 560.1 | 460.6 | 477.0 |
| 64 KiB | **32.3** | 29.7 | 26.6 | 26.8 |
| 1 MiB | **2.15** | 2.02 | 1.82 | 1.74 |
| 64 MiB | 0.029 | 0.029 | 0.028 | 0.016 |

At 64 MiB the segment/byte[]/jni columns are statistically indistinguishable —
all bound by memory bandwidth, not API overhead.

## Allocation (`gc.alloc.rate.norm`, bytes/op, lower is better)

The decisive chart. Note the segment column is flat near zero while the others
scale linearly with payload size.

### Compress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **0.07** | 408 | 1,464 | 35,464 |
| 64 KiB | **6.8** | 11,694 | 77,473 | 724,034 |
| 1 MiB | **44** | 182,069 | 1,234,501 | 1,449,282 |
| 64 MiB | **1,296** | 11,621,915 | 78,992,794 | 2,971,072 |

### Decompress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **0.03** | 1,136 | 1,072 | 48 |
| 64 KiB | **13** | 65,651 | 65,585 | 49 |
| 1 MiB | **16** | 1,048,785 | 1,048,634 | 58 |
| 64 MiB | **1,969** | 67,111,319 | 67,109,554 | 4,799 |

### Notes

- **Segment is allocation-free both directions** — the caller owns the off-heap
  source and destination, so nothing touches the heap.
- **byte[]/JNI allocate ~the output size every call** (their APIs return a fresh
  array). At 64 MiB that is 11-67 MB/op.
- **JNI compress allocates ~79 MB/op at 64 MiB** (~7x the others): its
  `compress(byte[], level)` allocates a worst-case `compressBound` buffer and then
  a trimmed copy — double buffering.
- **aircompressor decompress is also ~0 B/op** here, because the benchmark reuses
  a caller-supplied destination array. A caller-buffer API kills decode allocation
  regardless of binding; the segment path additionally avoids the off-heap↔heap copy.
  aircompressor *compress* still allocates internal hash tables per call.

## Reproduce

```bash
./mvnw -q -pl benchmark -am package -DskipTests

# throughput + allocation, all sizes
java -jar benchmark/target/benchmarks.jar -prof gc

# single size
java -jar benchmark/target/benchmarks.jar -prof gc -p size=67108864
```
