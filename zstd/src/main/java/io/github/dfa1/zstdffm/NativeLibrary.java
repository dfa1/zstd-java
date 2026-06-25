package io.github.dfa1.zstdffm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Infrastructure — loads the bundled `libzstd` shared library and binds
/// native symbols to {@link MethodHandle}s via the Foreign Function & Memory API.
///
/// The library is resolved from the platform-specific native JAR on the
/// classpath, extracted to a temp file, and loaded once at class-init time.
/// Override with `-Dzstd.lib.path=/path/to/libzstd.so`.
@SuppressWarnings("restricted") // libraryLookup / downcallHandle are restricted FFM methods
final class NativeLibrary {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(resolveLibPath(), Arena.ofAuto());

    static MethodHandle lookup(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                LIB.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("Symbol not found: " + name)),
                fd);
    }

    private static String resolveLibPath() {
        String explicit = System.getProperty("zstd.lib.path");
        if (explicit != null) {
            return explicit;
        }

        String classifier = classifier();
        String ext = libExtension(classifier);
        String resource = "/native/" + classifier + "/libzstd." + ext;

        try (InputStream in = NativeLibrary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("No bundled zstd library found for platform " + classifier);
            }
            Path tmp = Files.createTempFile("libzstd-", "." + ext);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp.toString();
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract bundled zstd: " + e.getMessage());
        }
    }

    private static String libExtension(String classifier) {
        if (classifier.startsWith("osx")) {
            return "dylib";
        }
        if (classifier.startsWith("windows")) {
            return "dll";
        }
        return "so";
    }

    private static String classifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String osName;
        if (os.contains("mac") || os.contains("darwin")) {
            osName = "osx";
        } else if (os.contains("win")) {
            osName = "windows";
        } else {
            osName = "linux";
        }
        String archName = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
        return osName + "-" + archName;
    }

    private NativeLibrary() {
        // no instances
    }
}
