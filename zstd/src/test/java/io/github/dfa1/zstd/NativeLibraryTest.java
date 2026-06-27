package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeLibraryTest {

    @Nested
    class Classifier {

        @ParameterizedTest
        @CsvSource({
            // os.name variants -> os part
            "Mac OS X,        aarch64, osx-aarch64",
            "Darwin,          arm64,   osx-aarch64",
            "Windows 11,      amd64,   windows-x86_64",
            "Windows 11,      aarch64, windows-aarch64",
            "Linux,           x86_64,  linux-x86_64",
            // unknown os falls back to linux; arch aliases normalise
            "FreeBSD,         aarch64, linux-aarch64",
            "Linux,           amd64,   linux-x86_64",
            "Linux,           arm64,   linux-aarch64",
        })
        void mapsOsAndArchToClassifier(String osName, String osArch, String expected) {
            // Given a JVM os.name / os.arch pair, in mixed case
            // When mapped to a native-jar classifier
            String classifier = NativeLibrary.classifier(osName, osArch);

            // Then it names the matching platform's native jar
            assertThat(classifier).isEqualTo(expected);
        }

        @Test
        void rejectsUnsupportedArchitecture() {
            // Given a CPU architecture this library ships no native jar for
            ThrowingCallable result = () -> NativeLibrary.classifier("Linux", "sparc");

            // Then it fails fast naming the offending arch, not a cryptic dlopen error
            assertThatThrownBy(result)
                    .isInstanceOf(UnsatisfiedLinkError.class)
                    .hasMessageContaining("sparc");
        }
    }

    @Nested
    class Lookup {

        @Test
        void bindsAnExistingSymbolToAnInvokableHandle() throws Throwable {
            // Given the descriptor for unsigned ZSTD_versionNumber(void)
            FunctionDescriptor fd = FunctionDescriptor.of(JAVA_INT);

            // When the symbol is looked up
            MethodHandle handle = NativeLibrary.lookup("ZSTD_versionNumber", fd);

            // Then a real, callable handle comes back (not null) and reports a version
            assertThat(handle).isNotNull();
            assertThat((int) handle.invokeExact()).isPositive();
        }

        @Test
        void rejectsAMissingSymbol() {
            // Given a symbol the library does not export
            FunctionDescriptor fd = FunctionDescriptor.of(JAVA_INT);

            // When it is looked up
            ThrowingCallable result = () -> NativeLibrary.lookup("ZSTD_no_such_symbol_xyz", fd);

            // Then it fails fast naming the missing symbol, rather than returning null
            assertThatThrownBy(result)
                    .isInstanceOf(UnsatisfiedLinkError.class)
                    .hasMessageContaining("Symbol not found");
        }
    }

    @Nested
    class LibraryExtension {

        @ParameterizedTest
        @CsvSource({
            "osx-aarch64,     dylib",
            "osx-x86_64,      dylib",
            "windows-x86_64,  dll",
            "windows-aarch64, dll",
            "linux-x86_64,    so",
            "linux-aarch64,   so",
        })
        void mapsClassifierToSharedLibraryExtension(String classifier, String extension) {
            // Given a platform classifier
            // When its shared-library extension is resolved
            String ext = NativeLibrary.libExtension(classifier);

            // Then it matches the platform's native library suffix
            assertThat(ext).isEqualTo(extension);
        }
    }
}
