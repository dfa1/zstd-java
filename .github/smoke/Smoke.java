///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
// Dependencies are supplied at run time via `jbang --deps ...` so the workflow
// can pin the released version and the per-arch native jar from the matrix.

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdCompressCtx;
import io.github.dfa1.zstd.ZstdDecompressCtx;
import io.github.dfa1.zstd.ZstdDictionary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Smoke test for a published zstd-java release: proves the bundled native
/// library loads on the host OS/arch and the core + dictionary paths work.
/// Run against a release from Maven Central, one arch per CI matrix leg.
public class Smoke {

    public static void main(String[] args) {
        String platform = System.getProperty("os.name") + "/" + System.getProperty("os.arch");

        // 1. Core round-trip — proves the native library loaded and compress/decompress work.
        byte[] original = "the quick brown fox ".repeat(2000).getBytes();
        byte[] compressed = Zstd.compress(original, 9);
        byte[] restored = Zstd.decompress(compressed);
        if (!Arrays.equals(original, restored)) {
            throw new AssertionError("core round-trip mismatch on " + platform);
        }
        if (compressed.length >= original.length) {
            throw new AssertionError("expected compression to shrink the input on " + platform);
        }

        // 2. Dictionary round-trip — exercises the ZDICT training path (the library's differentiator).
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            samples.add(("{\"id\":" + i + ",\"user\":\"user_" + (i % 100) + "\",\"event\":\"click\"}").getBytes());
        }
        ZstdDictionary dict = ZstdDictionary.train(samples, 8 * 1024);
        try (ZstdCompressCtx cctx = new ZstdCompressCtx();
             ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            byte[] message = samples.get(7);
            byte[] dictCompressed = cctx.compress(message, dict);
            byte[] dictRestored = dctx.decompress(dictCompressed, message.length, dict);
            if (!Arrays.equals(message, dictRestored)) {
                throw new AssertionError("dictionary round-trip mismatch on " + platform);
            }
        }

        System.out.println("OK " + platform
                + " | zstd " + Zstd.version()
                + " | core " + original.length + " -> " + compressed.length + " bytes"
                + " | dictionary round-trip passed");
    }
}
