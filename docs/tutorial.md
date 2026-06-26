# Tutorial: Getting started

This walks you from a clean checkout to your first compress/decompress round-trip.

## 1. Clone and build

You need JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH` (Zig is the C
compiler for the native lib).

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java
mvn install
```

The build invokes `scripts/build-zstd.sh`, compiling `libzstd` from the vendored
source — no autotools or CMake needed.

## 2. Your first round-trip

```java
import io.github.dfa1.zstd.Zstd;

byte[] original = "hello world".getBytes();
byte[] packed   = Zstd.compress(original);
byte[] restored = Zstd.decompress(packed);   // size read from the frame header

assert java.util.Arrays.equals(original, restored);
```

## 3. Run with native access enabled

The FFM API requires an explicit flag:

```bash
java --enable-native-access=ALL-UNNAMED Demo.java
```

That's the whole loop. From here, pick a [how-to guide](../README.md#how-to-guides)
for your actual task, or browse the [reference](../README.md#reference).
