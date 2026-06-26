# zstd-java

[![CI](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/zstd-java/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-java&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-java&metric=coverage)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-java)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.zstd/zstd.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.dfa1.zstd/zstd)
![zstd](https://img.shields.io/badge/zstd-1.5.7-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)

**zstd-java** is a Java wrapper for [Zstandard](https://github.com/facebook/zstd)
built on the **Foreign Function & Memory (FFM) API** — no JNI, no `sun.misc.Unsafe`.
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

## Install

The `zstd` jar is pure Java and ships no `libzstd` — you always pair it with a
native artifact. Two ways:

**1. Everything, any platform** — one dependency on `zstd-platform`, an empty jar
that transitively pulls the bindings plus all six natives (~3.8 MB). Zero choices;
the build runs on any OS/arch.

```xml
<dependency>
  <groupId>io.github.dfa1.zstd</groupId>
  <artifactId>zstd-platform</artifactId>
  <version>0.3</version>
</dependency>
```

**2. Leaner, one platform** — import `zstd-bom` to pin versions, then take `zstd`
plus only the `zstd-native-<classifier>` you target.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.dfa1.zstd</groupId>
      <artifactId>zstd-bom</artifactId>
      <version>0.3</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd-native-osx-aarch64</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
`windows-x86_64`, `windows-aarch64`. Gradle and more detail in the
[tutorial](docs/tutorial.md). Requires JDK 25+ and
`--enable-native-access=ALL-UNNAMED` at runtime. Building from source is for
contributors — see the [reference](docs/reference.md).

## License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).
