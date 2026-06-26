/// Java Foreign Function & Memory (FFM) bindings for Zstandard.
///
/// Exports the single public API package; the native `libzstd` is loaded at
/// runtime from the platform `zstd-native-<classifier>` artifact on the path.
/// Requires `--enable-native-access=io.github.dfa1.zstd` (or `ALL-UNNAMED` on
/// the classpath) since FFM downcalls are a restricted operation.
// The "dfa1" component ends in a digit, which the module-name lint flags as
// possibly version-like. It mirrors the Sonatype-verified io.github.dfa1
// namespace and the package name, so suppress the advisory rather than diverge.
@SuppressWarnings("module")
module io.github.dfa1.zstd {
    exports io.github.dfa1.zstd;
}
