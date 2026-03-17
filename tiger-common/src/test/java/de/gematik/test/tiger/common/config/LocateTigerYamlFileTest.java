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
package de.gematik.test.tiger.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocateTigerYamlFileTest {

  @BeforeEach
  void setUp() {
    TigerGlobalConfiguration.reset();
    TigerGlobalConfiguration.initialize();
  }

  @AfterEach
  void tearDown() {
    TigerGlobalConfiguration.reset();
  }

  @Nested
  class ExplicitLocation {

    @Test
    void existingFile_returnsPath(@TempDir Path tmp) throws IOException {
      Path yaml = Files.createFile(tmp.resolve("custom.yaml"));
      TigerConfigurationKeys.TIGER_TESTENV_CFGFILE_LOCATION.putValue(yaml.toString());

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(tmp);

      assertThat(result).hasValue(yaml);
    }

    @Test
    void missingFile_throws(@TempDir Path tmp) {
      Path missing = tmp.resolve("no-such-file.yaml");
      TigerConfigurationKeys.TIGER_TESTENV_CFGFILE_LOCATION.putValue(missing.toString());

      assertThatThrownBy(() -> TigerGlobalConfiguration.locateTigerYamlFile(tmp))
          .isInstanceOf(TigerConfigurationException.class)
          .hasMessageContaining("no-such-file.yaml");
    }

    @Test
    void explicitLocation_takePrecedenceOverCandidatesInCwd(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path explicit = Files.createFile(tmp.resolve("explicit.yaml"));
      TigerConfigurationKeys.TIGER_TESTENV_CFGFILE_LOCATION.putValue(explicit.toString());

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(tmp);

      assertThat(result).hasValue(explicit);
    }
  }

  @Nested
  class CandidateOrder {

    @Test
    void yamlPreferredOverYml(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Files.createFile(tmp.resolve("tiger.yml"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(tmp);

      assertThat(result).hasValueSatisfying(p -> assertThat(p).hasFileName("tiger.yaml"));
    }

    @Test
    void ymlFoundWhenYamlAbsent(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yml"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(tmp);

      assertThat(result).hasValueSatisfying(p -> assertThat(p).hasFileName("tiger.yml"));
    }
  }

  @Nested
  class UpwardSearch {

    @Test
    void findsYamlInParentDirectory(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path child = Files.createDirectory(tmp.resolve("subdir"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(child);

      assertThat(result).isPresent();
      assertThat(result.get().toRealPath()).isEqualTo(tmp.resolve("tiger.yaml").toRealPath());
    }

    @Test
    void findsYamlTwoLevelsUp(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path nested = Files.createDirectories(tmp.resolve("a/b"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(nested);

      assertThat(result).isPresent();
      assertThat(result.get().toRealPath()).isEqualTo(tmp.resolve("tiger.yaml").toRealPath());
    }

    @Test
    void emptyWhenNothingFound(@TempDir Path tmp) throws IOException {
      Path child = Files.createDirectory(tmp.resolve("empty"));
      // place a stop marker in tmp so we don't escape the temp dir
      Files.createDirectory(tmp.resolve(".git"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(child);

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class StopMarkers {

    @Test
    void stopsAtGitRoot_doesNotSearchAbove(@TempDir Path tmp) throws IOException {
      // layout: tmp/tiger.yaml, tmp/repo/.git, tmp/repo/sub
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path repo = Files.createDirectories(tmp.resolve("repo"));
      Files.createDirectory(repo.resolve(".git"));
      Path sub = Files.createDirectory(repo.resolve("sub"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(sub);

      // should NOT find tmp/tiger.yaml because .git in repo/ stops the walk
      assertThat(result).isEmpty();
    }

    @Test
    void yamlInSameDirectoryAsGitIsStillFound(@TempDir Path tmp) throws IOException {
      Path repo = Files.createDirectories(tmp.resolve("repo"));
      Files.createDirectory(repo.resolve(".git"));
      Files.createFile(repo.resolve("tiger.yaml"));
      Path sub = Files.createDirectory(repo.resolve("sub"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(sub);

      assertThat(result).isPresent();
      assertThat(result.get().toRealPath()).isEqualTo(repo.resolve("tiger.yaml").toRealPath());
    }

    @Test
    void yamlAboveGitNotReachable(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path repo = Files.createDirectories(tmp.resolve("repo"));
      Files.createDirectory(repo.resolve(".git"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(repo);

      // tiger.yaml is in the same dir as .git? No — it's in tmp, repo has .git → stop
      assertThat(result).isEmpty();
    }

    @Test
    void hgStopMarkerAlsoWorks(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path repo = Files.createDirectories(tmp.resolve("hgrepo"));
      Files.createDirectory(repo.resolve(".hg"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(repo);

      assertThat(result).isEmpty();
    }

    @Test
    void svnStopMarkerAlsoWorks(@TempDir Path tmp) throws IOException {
      Files.createFile(tmp.resolve("tiger.yaml"));
      Path repo = Files.createDirectories(tmp.resolve("svnrepo"));
      Files.createDirectory(repo.resolve(".svn"));

      Optional<Path> result = TigerGlobalConfiguration.locateTigerYamlFile(repo);

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class AdditionalConfigFiles {

    @Test
    void resolvesRelativeToTigerRootFolder_notCwd(@TempDir Path tmp) throws IOException {
      // tiger.yaml and extra.yaml live in tmp/project/ — cwd is somewhere else
      Path project = Files.createDirectories(tmp.resolve("project"));
      Files.createFile(project.resolve("tiger.yaml"));
      Files.createFile(project.resolve("extra.yaml"));

      // simulate what readMainYamlFile does after upward search found the yaml
      TigerConfigurationKeys.TIGER_ROOT_FOLDER.putValue(project.toString());

      Path result = TigerGlobalConfiguration.findAdditionalConfigurationFile("extra.yaml");

      assertThat(result.toRealPath()).isEqualTo(project.resolve("extra.yaml").toRealPath());
    }

    @Test
    void throwsWhenFileNotFoundInTigerRootFolder(@TempDir Path tmp) throws IOException {
      Path project = Files.createDirectories(tmp.resolve("project"));
      Files.createFile(project.resolve("tiger.yaml"));

      TigerConfigurationKeys.TIGER_ROOT_FOLDER.putValue(project.toString());

      assertThatThrownBy(
              () -> TigerGlobalConfiguration.findAdditionalConfigurationFile("extra.yaml"))
          .isInstanceOf(TigerConfigurationException.class)
          .hasMessageContaining("extra.yaml");
    }

    @Test
    void explicitCfgfileLocationTakesPrecedenceOverRootFolder(@TempDir Path tmp)
        throws IOException {
      Path projectA = Files.createDirectories(tmp.resolve("a"));
      Path projectB = Files.createDirectories(tmp.resolve("b"));
      Files.createFile(projectA.resolve("extra.yaml"));
      Files.createFile(projectB.resolve("extra.yaml"));
      Path tigerYaml = Files.createFile(projectA.resolve("tiger.yaml"));

      TigerConfigurationKeys.TIGER_TESTENV_CFGFILE_LOCATION.putValue(tigerYaml.toString());
      TigerConfigurationKeys.TIGER_ROOT_FOLDER.putValue(projectB.toString());

      Path result = TigerGlobalConfiguration.findAdditionalConfigurationFile("extra.yaml");

      // should resolve relative to the explicit tiger.yaml location, not TIGER_ROOT_FOLDER
      assertThat(result.toRealPath()).isEqualTo(projectA.resolve("extra.yaml").toRealPath());
    }
  }
}
