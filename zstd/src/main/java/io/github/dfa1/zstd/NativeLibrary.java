package io.github.dfa1.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/// Infrastructure — loads the bundled `libzstd` shared library and binds
/// native symbols to {@link MethodHandle}s via the Foreign Function & Memory API.
///
/// The library is resolved only from the platform-specific native JAR on the
/// classpath, extracted to a temp file, and loaded once at class-init time.
///
/// There is deliberately no path override (no `-Dzstd.lib.path`): loading a
/// caller-supplied native library is arbitrary native code execution in the JVM
/// process, so the loader trusts only the signed artifact on the classpath. To
/// run a self-built `libzstd`, package it into the native resource jar — see
/// `docs/how-to.md`.
@SuppressWarnings("restricted") // libraryLookup / downcallHandle are restricted FFM methods
final class NativeLibrary {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extractBundledLib(), Arena.ofAuto());

    static MethodHandle lookup(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                LIB.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("Symbol not found: " + name)),
                fd);
    }

    private static String extractBundledLib() {
        String classifier = classifier();
        String ext = libExtension(classifier);
        // Load through the class loader (no leading slash), not Class#getResourceAsStream:
        // the library lives in a separate zstd-native-<classifier> module, and a named
        // module only finds resources in itself. The classifier directory name contains a
        // dash, so it is not a valid package and the resource is not module-encapsulated —
        // the class loader can read it across modules (and on the classpath alike).
        String resource = "native/" + classifier + "/libzstd." + ext;

        try (InputStream in = NativeLibrary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("No bundled zstd library found for platform " + classifier);
            }
            // Extract into a private, owner-only temp directory rather than a file
            // loose in the shared temp root, so no other local user can swap the
            // library between extraction and dlopen. The owner-only (0700) mode is
            // set atomically at creation via a POSIX permission attribute.
            Path dir = Files.createTempDirectory("zstd-", ownerOnlyAttributes());
            Path lib = dir.resolve("libzstd." + ext);
            Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
            lib.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return lib.toString();
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract bundled zstd: " + e.getMessage());
        }
    }

    /// Owner-only (`rwx------`) directory permissions as a creation attribute on
    /// POSIX file systems; an empty array elsewhere (a Windows temp directory is
    /// already per-user, and the POSIX attribute is unsupported there).
    private static FileAttribute<?>[] ownerOnlyAttributes() {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            };
        }
        return new FileAttribute<?>[0];
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
