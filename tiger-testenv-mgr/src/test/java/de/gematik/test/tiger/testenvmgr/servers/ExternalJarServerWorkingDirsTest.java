/*
 *  Copyright 2021-2025 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */

package de.gematik.test.tiger.testenvmgr.servers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.gematik.test.tiger.common.config.TigerGlobalConfiguration;
import de.gematik.test.tiger.testenvmgr.AbstractTestTigerTestEnvMgr;
import de.gematik.test.tiger.testenvmgr.TigerTestEnvMgr;
import de.gematik.test.tiger.testenvmgr.junit.TigerTest;
import de.gematik.test.tiger.testenvmgr.util.TigerEnvironmentStartupException;
import de.gematik.test.tiger.testenvmgr.util.TigerTestEnvException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the externalJar source/workingDir resolution rules.
 *
 * <p>Variable space:
 *
 * <pre>
 * | Test                                                    | source            | workingDir      | jar    |
 * |---------------------------------------------------------|-------------------|-----------------|--------|
 * | absoluteSourceJarIsAlwaysUsedRegardlessOfWorkingDir...  | absolute path     | absolute        | exists |
 * | absoluteSourceJarWithNoWorkingDirUsesJarsParentDir      | absolute path     | absent          | exists |
 * | nonExistentWorkingDirIsAutoCreated                      | absolute path     | absolute(new)   | exists |
 * | missingJarShouldRaiseHelpfulError                       | absolute path     | absolute        | missing|
 * | relativeSourceWithoutExplicitWorkingDir...              | relative path     | absent          | exists |
 * | emptyWorkingDirBehavesLikeProjectRoot                   | relative path     | ''              | exists |
 * | relativeWorkingDirResolvesFromProjectRoot               | relative path     | relative        | exists |
 * | relativeSourceWithAbsoluteWorkingDirResolvesSourceFrom… | relative path     | absolute        | exists |
 * | bareFilenameSourceFallsBackToWorkingDir                 | bare filename     | absolute        | exists |
 * | wildcardSourceMatchesInWorkingDir                       | wildcard pattern  | absolute        | exists |
 * </pre>
 */
class ExternalJarServerWorkingDirsTest extends AbstractTestTigerTestEnvMgr {

  private static final Path HTTPBIN_JAR = Path.of("target", "tiger-httpbin.jar").toAbsolutePath();

  @Test
  @DisplayName("A: absolute source jar is always used regardless of what the workingDir contains")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.absolutePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void absoluteSourceJarIsAlwaysUsedRegardlessOfWorkingDirContents(TigerTestEnvMgr envMgr)
      throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path sourceDir = workspace.createDir("sourceDir");
      Path workingDir = workspace.createDir("workingDir");
      workspace.registerJar(sourceDir.resolve("app.jar"), workingDir);
      workspace.writeInvalidJar(workingDir.resolve("app.jar"));
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
    }
  }

  @Test
  @DisplayName(
      "B: absolute source with no workingDir configured → jar's parent dir is used as workingDir")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.absolutePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void absoluteSourceJarWithNoWorkingDirUsesJarsParentDir(TigerTestEnvMgr envMgr)
      throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path sourceDir = workspace.createDir("sourceDir");
      workspace.registerJar(sourceDir.resolve("app.jar"), sourceDir);
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
    }
  }

  @Test
  @DisplayName("C: absolute source with non-existent workingDir → Tiger auto-creates the directory")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.absolutePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void nonExistentWorkingDirIsAutoCreated(TigerTestEnvMgr envMgr) throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path sourceDir = workspace.createDir("sourceDir");
      Path nonExistentWorkingDir = workspace.root.resolve("willBeCreated");
      workspace.registerJar(sourceDir.resolve("app.jar"), nonExistentWorkingDir);
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
      assertThat(nonExistentWorkingDir).exists().isDirectory();
    }
  }

  @Test
  @DisplayName("D: absolute source pointing to a missing jar → error message contains the jar path")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.absolutePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void missingJarShouldRaiseHelpfulError(TigerTestEnvMgr envMgr) throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path workingDir = workspace.createDir("target");
      Path missingJar = workingDir.resolve("app.jar");
      workspace.registerPaths(missingJar, workingDir);
      assertThatThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr))
          .isInstanceOf(TigerEnvironmentStartupException.class)
          .cause()
          .isInstanceOf(TigerTestEnvException.class)
          .hasMessageContaining(missingJar.toString());
    }
  }

  @Test
  @DisplayName(
      "E: relative source with no workingDir configured → resolves relative to project root")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.relativePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void relativeSourceWithoutExplicitWorkingDirResolvesFromProjectRoot(TigerTestEnvMgr envMgr) {
    TigerGlobalConfiguration.putValue("local.jar.relativePath", "target/tiger-httpbin.jar");
    assertThatNoException()
        .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
  }

  @Test
  @DisplayName("F: relative source with empty workingDir '' → behaves like project root")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.relativePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ''
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void emptyWorkingDirBehavesLikeProjectRoot(TigerTestEnvMgr envMgr) {
    TigerGlobalConfiguration.putValue("local.jar.relativePath", "target/tiger-httpbin.jar");
    assertThatNoException()
        .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
  }

  @Test
  @DisplayName(
      "G: relative source and relative workingDir → both resolved relative to project root")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.relativePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.relativePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void relativeWorkingDirResolvesFromProjectRoot(TigerTestEnvMgr envMgr) {
    TigerGlobalConfiguration.putValue("local.jar.relativePath", "target/tiger-httpbin.jar");
    TigerGlobalConfiguration.putValue("working.dir.relativePath", "target");
    assertThatNoException()
        .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
  }

  @Test
  @DisplayName(
      "H: relative source with absolute workingDir → source still resolved from project root")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:${local.jar.relativePath}
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void relativeSourceWithAbsoluteWorkingDirResolvesSourceFromProjectRoot(TigerTestEnvMgr envMgr)
      throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path workingDir = workspace.createDir("workingDir");
      TigerGlobalConfiguration.putValue("local.jar.relativePath", "target/tiger-httpbin.jar");
      TigerGlobalConfiguration.putValue(
          "working.dir.absolutePath", workingDir.toAbsolutePath().toString());
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
    }
  }

  @Test
  @DisplayName("I: bare filename source (no path separator) → resolved as fallback from workingDir")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:app.jar
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void bareFilenameSourceFallsBackToWorkingDir(TigerTestEnvMgr envMgr) throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path workingDir = workspace.createDir("workingDir");
      workspace.registerJar(workingDir.resolve("app.jar"), workingDir);
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
    }
  }

  @Test
  @DisplayName("J: wildcard source pattern → matched against files in workingDir")
  @TigerTest(
      tigerYaml =
          """
          servers:
            sampleExternalJar:
              type: externalJar
              source:
                - local:app-*.jar
              healthcheckUrl: http://127.0.0.1:${free.port.0}
              healthcheckReturnCode: 200
              externalJarOptions:
                workingDir: ${working.dir.absolutePath}
                arguments:
                  - -port=${free.port.0}
          """,
      skipEnvironmentSetup = true)
  void wildcardSourceMatchesInWorkingDir(TigerTestEnvMgr envMgr) throws IOException {
    try (ScenarioWorkspace workspace = new ScenarioWorkspace()) {
      Path workingDir = workspace.createDir("workingDir");
      workspace.registerJar(workingDir.resolve("app-1.2.3.jar"), workingDir);
      assertThatNoException()
          .isThrownBy(() -> executeWithSecureShutdown(envMgr::setUpEnvironment, envMgr));
    }
  }

  private void executeWithSecureShutdown(Runnable test, TigerTestEnvMgr envMgr) {
    try {
      test.run();
    } finally {
      envMgr.shutDown();
    }
  }

  private static class ScenarioWorkspace implements AutoCloseable {
    final Path root;

    ScenarioWorkspace() throws IOException {
      this.root = Files.createTempDirectory("externalJarWorkingDirTest");
    }

    Path createDir(String name) throws IOException {
      return Files.createDirectories(root.resolve(name));
    }

    /** Copy the httpbin jar to {@code jarDestination} and register both paths as config values. */
    Path registerJar(Path jarDestination, Path workingDir) throws IOException {
      Files.createDirectories(jarDestination.getParent());
      Path copiedJar = Files.copy(HTTPBIN_JAR, jarDestination, StandardCopyOption.REPLACE_EXISTING);
      registerPaths(copiedJar, workingDir);
      return copiedJar;
    }

    /** Register paths without copying a file (e.g. missing-jar scenarios). */
    void registerPaths(Path jar, Path workingDir) {
      TigerGlobalConfiguration.putValue("local.jar.absolutePath", jar.toAbsolutePath().toString());
      TigerGlobalConfiguration.putValue(
          "working.dir.absolutePath", workingDir.toAbsolutePath().toString());
    }

    void writeInvalidJar(Path destination) throws IOException {
      Files.createDirectories(destination.getParent());
      Files.writeString(
          destination,
          "invalid jar",
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void close() throws IOException {
      FileUtils.deleteDirectory(root.toFile());
    }
  }
}
