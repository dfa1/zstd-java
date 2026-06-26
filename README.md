# zstd-java

[![CI](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml)
![zstd](https://img.shields.io/badge/zstd-1.5.7-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)

**zstd-java** is a Java wrapper for [Zstandard](https://github.com/facebook/zstd)
built on the **Foreign Function & Memory (FFM) API** — no JNI, no hand-written C.
It targets **JDK 25+** (for stable `java.lang.foreign`) and leads with the
feature missing from most JVM zstd bindings: **dictionary compression**, trained
straight from your own data.

> **AI-assisted development:** This project uses Claude Code for implementation —
> C header mapping, test generation, docs. Architecture, API design, and all
> decisions are human-driven.

## Documentation

The docs follow the [Diátaxis](https://diataxis.fr) framework:

| | Purpose | Start here |
|---|---|---|
| **[Tutorial](docs/tutorial.md)** | Learning by doing | Clean checkout → first round-trip |
| **[How-to guides](docs/how-to.md)** | Solving a specific task | Hot paths, dictionaries, zero-copy, self-built lib |
| **[Reference](docs/reference.md)** | Looking up facts | Platforms, API surface, symbol coverage, build |
| **[Explanation](docs/explanation.md)** | Understanding the why | Why FFM + Zig, when zero-copy pays, benchmarks |

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-java.git
cd zstd-java && mvn test
```

Requires JDK 25+ and `--enable-native-access=ALL-UNNAMED` at runtime.

## License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).
