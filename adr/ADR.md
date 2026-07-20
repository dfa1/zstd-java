# Architecture Decision Records

This directory contains ADRs following the
[MADR 3.0](https://adr.github.io/madr/) format (Markdown Architectural Decision Records).

## Format

Each ADR is a Markdown file named `NNNN-short-title.md`. Use `template.md` as the starting point.

**Status values:** Proposed → Accepted → Deprecated → Superseded (also: Rejected, Deferred)

`Accepted` is the decision's lifecycle state — it stays Accepted until something
Supersedes it — tracked independently of whether the code has shipped.

Most of the early ADRs are retrospective: they record decisions already made and
shipped, so contributors can see *why* the project looks the way it does.

## Index

| ADR  | Title                                              | Status    |
|------|----------------------------------------------------|-----------|
| 0001 | FFM bindings over JNI                               | Accepted  |
| 0002 | Build the native library with `zig cc`             | Accepted  |
| 0003 | MemorySegment-first API with thin `byte[]` overloads | Accepted  |
| 0004 | Per-classifier native JARs                          | Accepted  |
| 0005 | No `-Dzstd.lib.path` override                       | Accepted  |
| 0006 | Native compile flags                               | Accepted  |
| 0007 | Integration tests as ground truth                  | Accepted  |
| 0008 | `size_t` maps to `JAVA_LONG`, guard sentinels      | Accepted  |
| 0009 | `NativeObject`: AutoCloseable, idempotent close    | Accepted  |
| 0010 | Bounded native-context pool for virtual threads    | Proposed  |
| 0011 | JPMS module descriptor                             | Accepted  |
| 0012 | Benchmark methodology and publishing               | Proposed  |
| 0013 | Binding coverage scope — exclude legacy/deprecated | Accepted  |
| 0014 | Single-threaded native build (no `nbWorkers`)      | Superseded |
| 0015 | Enable `ZSTD_MULTITHREAD` (native `nbWorkers`)     | Accepted  |
| 0016 | RFC 9842 support — module split and naming         | Proposed  |
