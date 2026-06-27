# Architecture Decision Records

This directory contains ADRs following the
[MADR 3.0](https://adr.github.io/madr/) format (Markdown Architectural Decision Records).

## Format

Each ADR is a Markdown file named `NNNN-short-title.md`. Use `template.md` as the starting point.

**Status values:** Proposed → Accepted → Completed | Deferred | Deprecated | Superseded

Most of the early ADRs are retrospective: they record decisions already made and
shipped, so contributors can see *why* the project looks the way it does.

## Index

| ADR  | Title                                              | Status    |
|------|----------------------------------------------------|-----------|
| 0001 | FFM bindings over JNI                               | Completed |
| 0002 | Build the native library with `zig cc`             | Completed |
| 0003 | MemorySegment-first API with thin `byte[]` overloads | Completed |
| 0004 | Per-classifier native JARs                          | Completed |
| 0005 | No `-Dzstd.lib.path` override                       | Completed |
| 0006 | Native compile flags                               | Completed |
| 0007 | Integration tests as ground truth                  | Completed |
| 0008 | `size_t` maps to `JAVA_LONG`, guard sentinels      | Completed |
| 0009 | `NativeObject`: AutoCloseable, idempotent close    | Completed |
| 0010 | Bounded native-context pool for virtual threads    | Proposed  |
| 0011 | JPMS module descriptor                             | Accepted  |
| 0012 | Benchmark methodology and publishing               | Accepted  |
| 0013 | Binding coverage scope — exclude legacy/deprecated | Completed |
| 0014 | Single-threaded native build (no `nbWorkers`)      | Completed |
