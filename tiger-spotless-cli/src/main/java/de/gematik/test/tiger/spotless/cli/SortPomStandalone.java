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
import com.diffplug.spotless.pom.SortPomCfg;
import com.diffplug.spotless.pom.SortPomStep;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SortPomStandalone {

  private static final String DOM4J_VERSION = VersionCatalog.get("dom4j", "2.2.0");

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: SortPomStandalone <pom1> <pom2> ...");
      System.exit(1);
    }

    try {
      List<File> pomFiles = new ArrayList<>();
      for (String arg : args) {
        File pomFile = new File(arg);
        if (!pomFile.isFile()) {
          System.err.println("Skipping missing POM: " + arg);
          continue;
        }
        pomFiles.add(pomFile);
      }

      formatPomFiles(pomFiles);
    } catch (Exception e) {
      System.err.println("SortPom failed: " + e.getMessage());
      e.printStackTrace();
      System.exit(2);
    }
  }

  static void formatPomFiles(List<File> pomFiles) throws Exception {
    if (pomFiles.isEmpty()) {
      return;
    }

    Formatter formatter =
        Formatter.builder()
            .encoding(StandardCharsets.UTF_8)
            .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
            .steps(List.of(SortPomStep.create(new SortPomCfg(), buildProvisioner())))
            .build();

    applyFormatter(formatter, pomFiles);
  }

  private static Provisioner buildProvisioner() {
    return new Provisioner() {
      @Override
      @ReturnValuesAreNonnullByDefault
      public Set<File> provisionWithTransitives(
          boolean withTransitives, Collection<String> mavenCoordinates) {
        Set<File> result = new HashSet<>();
        for (String coord : mavenCoordinates) {
          File resolved = resolveMavenJar(coord);
          if (resolved != null) {
            result.add(resolved);
            if (withTransitives) {
              addKnownTransitives(coord, result);
            }
          } else {
            String errorMsg = "Unable to resolve artifact: " + coord;
            System.err.println(errorMsg);
            System.err.println("Classpath: " + System.getProperty("java.class.path"));
            throw new RuntimeException(errorMsg);
          }
        }
        if (result.isEmpty() && !mavenCoordinates.isEmpty()) {
          throw new RuntimeException("Failed to resolve any artifacts from: " + mavenCoordinates);
        }
        return result;
      }
    };
  }

  private static void addKnownTransitives(String coord, Set<File> result) {
    if (coord.startsWith("com.github.ekryd.sortpom:sortpom-sorter:")) {
      File dom4j = resolveMavenJar("org.dom4j:dom4j:" + DOM4J_VERSION);
      if (dom4j != null) {
        result.add(dom4j);
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

    // Try local Maven repository first
    File fromRepo = resolveFromLocalRepository(groupId, artifactId, version, classifier, extension);
    if (fromRepo != null) {
      System.err.println("Resolved " + coord + " from repository: " + fromRepo.getAbsolutePath());
      return fromRepo;
    }

    // Fallback to classpath
    File fromClasspath = resolveFromClasspath(artifactId, version, classifier, extension);
    if (fromClasspath != null) {
      System.err.println(
          "Resolved " + coord + " from classpath: " + fromClasspath.getAbsolutePath());
      return fromClasspath;
    }

    System.err.println("Failed to resolve " + coord + " from repository or classpath");
    return null;
  }

  private static File resolveFromLocalRepository(
      String groupId, String artifactId, String version, String classifier, String extension) {
    String groupPath = groupId.replace('.', File.separatorChar);
    String fileName =
        artifactId
            + "-"
            + version
            + (classifier.isBlank() ? "" : "-" + classifier)
            + "."
            + extension;
    String relativePath =
        groupPath
            + File.separator
            + artifactId
            + File.separator
            + version
            + File.separator
            + fileName;

    // Try multiple possible Maven repository locations
    List<String> repoPaths = getMavenRepositoryPaths();
    for (String repoPath : repoPaths) {
      File candidate = new File(repoPath + File.separator + relativePath);
      if (candidate.exists()) {
        return candidate;
      }
    }
    return null;
  }

  private static List<String> getMavenRepositoryPaths() {
    List<String> paths = new ArrayList<>();

    // Default user home location
    paths.add(
        System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository");

    // Try to infer from classpath (for CI environments with custom Maven cache)
    String classpath = System.getProperty("java.class.path");
    if (classpath != null && !classpath.isBlank()) {
      for (String entry : classpath.split(File.pathSeparator)) {
        // Look for a path containing /repository/ which is typical for Maven repos
        int repoIndex = entry.indexOf(File.separator + "repository" + File.separator);
        if (repoIndex > 0) {
          String inferredRepo = entry.substring(0, repoIndex + "/repository".length());
          if (!paths.contains(inferredRepo)) {
            paths.add(inferredRepo);
          }
        }
      }
    }

    // Common CI Maven cache locations
    paths.add("/home/jenkins/maven/cache/repository");
    paths.add("/root/.m2/repository");

    return paths;
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

  private static void applyFormatter(Formatter formatter, List<File> files) throws Exception {
    for (File file : files) {
      Path filePath = file.toPath();
      String originalContent = Files.readString(filePath, StandardCharsets.UTF_8);
      String formattedContent = formatter.compute(originalContent, file);

      if (!originalContent.equals(formattedContent)) {
        Files.writeString(filePath, formattedContent, StandardCharsets.UTF_8);
      }
    }
  }
}
