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

import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.annotations.ReturnValuesAreNonnullByDefault;
import com.diffplug.spotless.generic.EndWithNewlineStep;
import com.diffplug.spotless.generic.IndentStep;
import com.diffplug.spotless.generic.TrimTrailingWhitespaceStep;
import com.diffplug.spotless.java.GoogleJavaFormatStep;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight CLI wrapper around spotless-lib for fast file formatting. This avoids the overhead of
 * starting Maven for each commit.
 */
public class SpotlessCli {

  private static final String GOOGLE_JAVA_FORMAT_VERSION =
      VersionCatalog.get("spotless.googleJavaFormat", "1.28.0xxx");
  private static final String SPOTLESS_LIB_VERSION = VersionCatalog.get("spotless.lib", "4.3.0");
  private static final String SLF4J_API_VERSION = VersionCatalog.get("slf4j.api", "2.0.17");
  private static final String GUAVA_VERSION = VersionCatalog.get("guava", "33.5.0-jre");
  private static final String DOM4J_VERSION = VersionCatalog.get("dom4j", "2.2.0");
  private static final String SORTPOM_VERSION = VersionCatalog.get("sortpom", "4.0.0");

  public static void main(String[] args) {
    int exitCode = run(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(String[] args) {
    if (args.length < 2) {
      System.err.println("Usage: java -jar tiger-spotless-cli.jar apply <file1> <file2> ...");
      return 1;
    }

    String command = args[0];
    if (!"apply".equals(command)) {
      System.err.println("Unknown command: " + command);
      System.err.println("Supported commands: apply");
      return 1;
    }

    try {
      List<File> filesToFormat = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
        File file = new File(args[i]);
        if (file.exists() && file.isFile()) {
          filesToFormat.add(file);
        }
      }

      if (filesToFormat.isEmpty()) {
        System.out.println("No files to format.");
        return 0;
      }

      formatFiles(filesToFormat);
      System.out.println("Formatted " + filesToFormat.size() + " file(s).");
      return 0;
    } catch (Exception e) {
      System.err.println("Error formatting files: " + e.getMessage());
      return 1;
    }
  }

  private static void formatFiles(List<File> files) throws Exception {
    // Create a provisioner that provides dependencies from the local Maven repo first
    Provisioner provisioner =
        new Provisioner() {
          @Override
          @ReturnValuesAreNonnullByDefault
          public Set<File> provisionWithTransitives(
              boolean withTransitives, Collection<String> mavenCoordinates) {
            Set<File> result = new HashSet<>();
            for (String coord : mavenCoordinates) {
              File resolved = resolveMavenJar(coord);
              if (resolved != null) {
                result.add(resolved);
                addKnownTransitives(coord, result);
                if (withTransitives) {
                  addKnownTransitivesForTransitivesOnly(coord, result);
                }
                continue;
              }
              File fallback = getSelfJar();
              if (fallback != null) {
                result.add(fallback);
              } else {
                System.err.println("Unable to resolve artifact: " + coord);
              }
            }
            return result;
          }
        };

    // Group files by type for batch processing
    List<File> javaFiles = new ArrayList<>();
    List<File> markdownFiles = new ArrayList<>();
    List<File> groovyFiles = new ArrayList<>();
    List<File> yamlFiles = new ArrayList<>();
    List<File> pomFiles = new ArrayList<>();

    for (File file : files) {
      String name = file.getName().toLowerCase();

      if (name.endsWith(".java")) {
        javaFiles.add(file);
      } else if (name.endsWith(".md")) {
        markdownFiles.add(file);
      } else if (name.endsWith(".groovy") || name.contains("jenkinsfile")) {
        groovyFiles.add(file);
      } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
        yamlFiles.add(file);
      } else if (name.equals("pom.xml")) {
        pomFiles.add(file);
      }
    }

    // Format each file type with appropriate formatter
    formatJavaFiles(javaFiles, provisioner);
    formatMarkdownFiles(markdownFiles);
    formatGroovyFiles(groovyFiles);
    formatYamlFiles(yamlFiles);
    formatPomFiles(pomFiles);
  }

  private static void formatJavaFiles(List<File> files, Provisioner provisioner) throws Exception {
    if (files.isEmpty()) return;

    Formatter formatter =
        Formatter.builder()
            .encoding(StandardCharsets.UTF_8)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .steps(List.of(GoogleJavaFormatStep.create(GOOGLE_JAVA_FORMAT_VERSION, provisioner)))
            .build();

    applyFormatter(formatter, files);
  }

  private static void formatMarkdownFiles(List<File> files) throws Exception {
    if (files.isEmpty()) return;

    Formatter formatter =
        Formatter.builder()
            .encoding(StandardCharsets.UTF_8)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .steps(List.of(TrimTrailingWhitespaceStep.create(), EndWithNewlineStep.create()))
            .build();

    applyFormatter(formatter, files);
  }

  private static void formatGroovyFiles(List<File> files) throws Exception {
    if (files.isEmpty()) return;

    Formatter formatter =
        Formatter.builder()
            .encoding(StandardCharsets.UTF_8)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .steps(List.of(TrimTrailingWhitespaceStep.create(), EndWithNewlineStep.create()))
            .build();

    applyFormatter(formatter, files);
  }

  private static void formatYamlFiles(List<File> files) throws Exception {
    if (files.isEmpty()) return;

    Formatter formatter =
        Formatter.builder()
            .encoding(StandardCharsets.UTF_8)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .steps(
                List.of(
                    TrimTrailingWhitespaceStep.create(),
                    EndWithNewlineStep.create(),
                    IndentStep.create(IndentStep.Type.SPACE, 2)))
            .build();

    applyFormatter(formatter, files);
  }

  private static void formatPomFiles(List<File> files) throws Exception {
    if (files.isEmpty()) return;

    runPomFormatterProcess(files);
  }

  private static void runPomFormatterProcess(List<File> files) throws Exception {
    String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    File selfEntry = getSelfClasspathEntry();
    if (selfEntry == null) {
      throw new IllegalStateException("Unable to locate CLI classes or jar for POM formatting.");
    }

    File spotlessLib =
        resolveMavenJar("com.diffplug.spotless:spotless-lib:" + SPOTLESS_LIB_VERSION);
    if (spotlessLib == null) {
      throw new IllegalStateException(
          "Unable to resolve spotless-lib jar from local Maven repository.");
    }
    File spotlessLibExtra =
        resolveMavenJar("com.diffplug.spotless:spotless-lib-extra:" + SPOTLESS_LIB_VERSION);
    if (spotlessLibExtra == null) {
      throw new IllegalStateException(
          "Unable to resolve spotless-lib-extra jar from local Maven repository.");
    }
    File slf4jApi = resolveMavenJar("org.slf4j:slf4j-api:" + SLF4J_API_VERSION);
    if (slf4jApi == null) {
      throw new IllegalStateException(
          "Unable to resolve slf4j-api jar from local Maven repository.");
    }

    // Add sortpom-sorter to classpath (required by SortPomStep)
    // Note: dom4j is NOT added here to avoid classloader conflicts - Spotless loads it internally
    File sortpomSorter =
        resolveMavenJar("com.github.ekryd.sortpom:sortpom-sorter:" + SORTPOM_VERSION);
    if (sortpomSorter == null) {
      throw new IllegalStateException(
          "Unable to resolve sortpom-sorter jar from local Maven repository.");
    }

    String classpath =
        String.join(
            File.pathSeparator,
            selfEntry.getAbsolutePath(),
            spotlessLib.getAbsolutePath(),
            spotlessLibExtra.getAbsolutePath(),
            slf4jApi.getAbsolutePath(),
            sortpomSorter.getAbsolutePath());

    List<String> command = new ArrayList<>();
    command.add(javaExe);
    command.add("-cp");
    command.add(classpath);
    command.add("de.gematik.test.tiger.spotless.cli.SortPomStandalone");
    for (File file : files) {
      command.add(file.getAbsolutePath());
    }

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("SortPom failed: " + output.trim());
    }
  }

  private static File getSelfClasspathEntry() {
    try {
      return Path.of(SpotlessCli.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .toFile();
    } catch (Exception e) {
      return null;
    }
  }

  private static void applyFormatter(Formatter formatter, List<File> files) throws Exception {
    for (File file : files) {

      Path filePath = file.toPath();
      String originalContent = Files.readString(filePath, StandardCharsets.UTF_8);
      String formattedContent = formatter.compute(originalContent, file);

      if (!originalContent.equals(formattedContent)) {
        Files.writeString(filePath, formattedContent, StandardCharsets.UTF_8);
        System.out.println("Formatted: " + file.getPath());
      }
    }
  }

  private static void addKnownTransitives(String coord, Set<File> result) {
    if (coord.startsWith("com.github.ekryd.sortpom:sortpom-sorter:")) {
      File dom4j = resolveMavenJar("org.dom4j:dom4j:" + DOM4J_VERSION);
      if (dom4j != null) {
        result.add(dom4j);
      }
    }
  }

  private static void addKnownTransitivesForTransitivesOnly(String coord, Set<File> result) {
    if (coord.startsWith("com.google.googlejavaformat:google-java-format:")) {
      File guava = resolveMavenJar("com.google.guava:guava:" + GUAVA_VERSION);
      if (guava != null) {
        result.add(guava);
      }
    }
  }

  private static File resolveMavenJar(String coord) {
    String[] parts = coord.split(":");
    if (parts.length < 3) {
      return null;
    }
    String groupId = parts[0];
    String artifactId = parts[1];
    String version = parts[2];
    String classifier = parts.length >= 4 ? parts[3] : "";
    String extension = parts.length >= 5 ? parts[4] : "jar";

    File candidate =
        resolveFromLocalRepository(groupId, artifactId, version, classifier, extension);
    if (candidate != null) {
      return candidate;
    }

    return resolveFromClasspath(artifactId, version, classifier, extension);
  }

  private static File resolveFromLocalRepository(
      String groupId, String artifactId, String version, String classifier, String extension) {
    String baseDir = System.getProperty("maven.repo.local");
    if (baseDir == null || baseDir.isBlank()) {
      baseDir = System.getenv("MAVEN_REPO_LOCAL");
    }
    if (baseDir == null || baseDir.isBlank()) {
      baseDir =
          System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository";
    }
    String groupPath = groupId.replace('.', File.separatorChar);
    String fileName =
        artifactId
            + "-"
            + version
            + (classifier.isBlank() ? "" : "-" + classifier)
            + "."
            + extension;
    File candidate =
        new File(
            baseDir
                + File.separator
                + groupPath
                + File.separator
                + artifactId
                + File.separator
                + version
                + File.separator
                + fileName);
    return candidate.exists() ? candidate : null;
  }

  private static File resolveFromClasspath(
      String artifactId, String version, String classifier, String extension) {
    String classpath = System.getProperty("java.class.path");
    if (classpath == null || classpath.isBlank()) {
      return null;
    }

    String expectedFileName =
        artifactId
            + "-"
            + version
            + (classifier.isBlank() ? "" : "-" + classifier)
            + "."
            + extension;

    // First try exact match
    for (String entry : classpath.split(File.pathSeparator)) {
      File file = new File(entry);
      if (file.isFile() && expectedFileName.equals(file.getName())) {
        return file;
      }
    }

    // Fallback: try prefix match for artifacts with different versions
    String artifactPrefix = artifactId + "-";
    for (String entry : classpath.split(File.pathSeparator)) {
      File file = new File(entry);
      if (file.isFile()
          && file.getName().startsWith(artifactPrefix)
          && file.getName().endsWith("." + extension)) {
        return file;
      }
    }

    return null;
  }

  private static File getSelfJar() {
    try {
      String jarPath =
          SpotlessCli.class.getProtectionDomain().getCodeSource().getLocation().getPath();
      File jarFile = new File(jarPath);
      if (jarFile.isFile() && jarFile.getName().endsWith(".jar")) {
        return jarFile;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
