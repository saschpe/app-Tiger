/*
 *
 * Copyright 2021-2026 gematik GmbH
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
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.test.tiger.spotless.cli;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpotlessCliFileTypeTest {

  @TempDir Path tempDir;

  @Test
  void formatsNonPomFileTypes() throws Exception {
    List<Path> files = new ArrayList<>();
    List<String> originals = new ArrayList<>();

    files.add(writeFile("Example.java", "class Example{public static void main(String[]a){}}"));
    originals.add(Files.readString(files.get(files.size() - 1), StandardCharsets.UTF_8));

    files.add(writeFile("README.md", "# Title   \n\nText\t"));
    originals.add(Files.readString(files.get(files.size() - 1), StandardCharsets.UTF_8));

    Path groovyFile = writeFile("build.gradle", "task hello{doLast{println 'hi'}}  ");
    files.add(groovyFile);
    originals.add(Files.readString(files.get(files.size() - 1), StandardCharsets.UTF_8));

    files.add(writeFile("config.yml", "root:  \n  child: value"));
    originals.add(Files.readString(files.get(files.size() - 1), StandardCharsets.UTF_8));

    List<String> args = new ArrayList<>();
    args.add("apply");
    for (Path file : files) {
      args.add(file.toString());
    }

    SpotlessCli.run(args.toArray(new String[0]));

    for (int i = 0; i < files.size(); i++) {
      String updated = Files.readString(files.get(i), StandardCharsets.UTF_8);
      if (!files.get(i).equals(groovyFile)) {
        assertNotEquals(originals.get(i), updated);
      }
    }
  }

  @Test
  void formatsPomFiles() throws Exception {
    List<Path> files = new ArrayList<>();
    files.add(writeFile("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>"));

    List<String> args = new ArrayList<>();
    args.add("apply");
    for (Path file : files) {
      args.add(file.toString());
    }

    SpotlessCli.run(args.toArray(new String[0]));

    for (Path file : files) {
      assertTrue(Files.readString(file, StandardCharsets.UTF_8).endsWith("\n"));
    }
  }

  private Path writeFile(String name, String content) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }
}
