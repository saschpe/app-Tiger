# Tiger Spotless CLI

A lightweight CLI wrapper around `spotless-lib` that provides fast Java code formatting for git hooks.

## Purpose

This module solves the performance problem with running `mvn spotless:apply` in pre-commit hooks, which is too slow due
to Maven startup overhead. Instead, it creates a standalone executable JAR that can format files directly.

## How it works

- Uses `spotless-lib` 4.3.0 directly (not the Maven plugin)
- Includes Google Java Format and all required dependencies in a shaded JAR
- Can format individual files passed as command-line arguments
- Near-instantaneous execution after the initial build

## Usage

```bash
java -jar tiger-spotless-cli.jar apply <file1.java> <file2.java> ...
```

## Integration

This CLI is automatically used by the `pre-commit` git hook via `githooks/spotless-changed.sh`. The first time the hook
runs, it builds this JAR (one-time setup). Subsequent runs are very fast.

## Building

The module is built as part of the main Tiger build:

```bash
mvn clean package
```

The executable JAR is created at `target/tiger-spotless-cli.jar`.

## POM formatting

POM files are formatted by a dedicated helper process to avoid classloader conflicts between SortPom and the shaded CLI.
The main CLI spawns `de.gematik.test.tiger.spotless.cli.SortPomStandalone` with a minimal classpath that includes the
CLI
JAR and `dom4j` from the local Maven cache.

If the helper cannot resolve `dom4j` from `~/.m2`, the POM formatting step fails with a clear error.

## Usage

```bash
java -jar tiger-spotless-cli.jar apply <file1.java> <file2.java> ...
```
