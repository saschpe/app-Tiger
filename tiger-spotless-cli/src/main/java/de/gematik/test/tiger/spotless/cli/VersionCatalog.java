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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class VersionCatalog {
  private static final String RESOURCE = "/spotless-cli-versions.properties";
  private static final Properties PROPERTIES = new Properties();

  static {
    try (InputStream input = VersionCatalog.class.getResourceAsStream(RESOURCE)) {
      if (input != null) {
        PROPERTIES.load(input);
      }
    } catch (IOException e) {
      System.err.println("Failed to load " + RESOURCE + ": " + e.getMessage());
    }
  }

  private VersionCatalog() {}

  static String get(String key, String fallback) {
    String sysProp = sanitizeValue(System.getProperty(key));
    if (sysProp != null) {
      return sysProp;
    }

    String envProp = sanitizeValue(System.getenv(toEnvKey(key)));
    if (envProp != null) {
      return envProp;
    }

    String pomProp = sanitizeValue(resolveFromPom(key));
    if (pomProp != null) {
      return pomProp;
    }

    String fileProp = sanitizeValue(PROPERTIES.getProperty(key));
    if (fileProp != null) {
      return fileProp;
    }

    return fallback;
  }

  private static String resolveFromPom(String key) {
    String pomProperty = mapToPomProperty(key);
    String managedDependency = mapToManagedDependency(key);
    if (pomProperty == null && managedDependency == null) {
      return null;
    }

    File pom = findNearestPom(Path.of(System.getProperty("user.dir")));
    if (pom == null) {
      return null;
    }

    if (pomProperty != null) {
      String propValue = readPomPropertyRecursive(pom, pomProperty, 0);
      if (propValue != null) {
        return propValue;
      }
    }

    if (managedDependency != null) {
      String[] parts = managedDependency.split(":", 2);
      return readManagedDependencyVersionRecursive(pom, parts[0], parts[1], 0, new HashSet<>());
    }

    return null;
  }

  private static String mapToPomProperty(String key) {
    switch (key) {
      case "spotless.googleJavaFormat":
        return "version.spotless.google-java-format";
      case "spotless.lib":
        return "version.spotless-lib";
      case "slf4j.api":
        return "version.slf4j";
      case "guava":
        return "version.guava";
      case "dom4j":
        return "version.dom4j";
      case "sortpom":
        return "version.sortpom";
      case "flexmark":
        return "version.flexmark";
      default:
        return null;
    }
  }

  private static String mapToManagedDependency(String key) {
    switch (key) {
      case "guava":
        return "com.google.guava:guava";
      case "dom4j":
        return "org.dom4j:dom4j";
      default:
        return null;
    }
  }

  private static File findNearestPom(Path start) {
    Path current = start;
    while (current != null) {
      Path candidate = current.resolve("pom.xml");
      if (Files.isRegularFile(candidate)) {
        return candidate.toFile();
      }
      current = current.getParent();
    }
    return null;
  }

  private static String readPomPropertyRecursive(File pomFile, String property, int depth) {
    return resolvePropertyRecursive(pomFile, property, depth, new HashSet<>());
  }

  private static String resolvePropertyRecursive(
      File pomFile, String property, int depth, Set<String> visiting) {
    if (depth > 10) {
      return null;
    }

    PomInfo pomInfo = PomInfo.read(pomFile);
    if (pomInfo == null) {
      return null;
    }

    String value = pomInfo.properties.get(property);
    if (value != null) {
      if (visiting.contains(property)) {
        return null;
      }
      visiting.add(property);
      String resolved = resolveValue(pomFile, value, depth, visiting);
      visiting.remove(property);
      if (resolved != null) {
        return resolved;
      }
    }

    File parentPom = pomInfo.resolveParentPom();
    if (parentPom == null) {
      return null;
    }

    return resolvePropertyRecursive(parentPom, property, depth + 1, visiting);
  }

  private static String resolveValue(File pomFile, String value, int depth, Set<String> visiting) {
    String trimmed = value.trim();
    if (!trimmed.contains("${")) {
      return trimmed;
    }

    StringBuilder result = new StringBuilder();
    int index = 0;
    while (index < trimmed.length()) {
      int start = trimmed.indexOf("${", index);
      if (start < 0) {
        result.append(trimmed.substring(index));
        break;
      }
      int end = trimmed.indexOf('}', start + 2);
      if (end < 0) {
        return null;
      }
      result.append(trimmed, index, start);
      String placeholder = trimmed.substring(start + 2, end);
      String resolved = resolvePropertyRecursive(pomFile, placeholder, depth + 1, visiting);
      if (resolved == null) {
        return null;
      }
      result.append(resolved);
      index = end + 1;
    }

    return result.toString();
  }

  private static String readManagedDependencyVersionRecursive(
      File pomFile, String groupId, String artifactId, int depth, Set<String> visiting) {
    if (depth > 10) {
      return null;
    }

    PomInfo pomInfo = PomInfo.read(pomFile);
    if (pomInfo == null) {
      return null;
    }

    String key = groupId + ":" + artifactId;
    String version = pomInfo.dependencyVersions.get(key);
    if (version != null) {
      if (visiting.contains(key)) {
        return null;
      }
      visiting.add(key);
      String resolved = resolveValue(pomFile, version, depth, visiting);
      visiting.remove(key);
      if (resolved != null) {
        return resolved;
      }
    }

    File parentPom = pomInfo.resolveParentPom();
    if (parentPom == null) {
      return null;
    }

    return readManagedDependencyVersionRecursive(
        parentPom, groupId, artifactId, depth + 1, visiting);
  }

  private static final class PomInfo {
    private final File pomFile;
    private final Map<String, String> properties;
    private final Map<String, String> dependencyVersions;
    private final String parentRelativePath;

    private PomInfo(
        File pomFile,
        Map<String, String> properties,
        Map<String, String> dependencyVersions,
        String parentRelativePath) {
      this.pomFile = pomFile;
      this.properties = properties;
      this.dependencyVersions = dependencyVersions;
      this.parentRelativePath = parentRelativePath;
    }

    static PomInfo read(File pomFile) {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(pomFile);
        Element root = document.getDocumentElement();
        Map<String, String> props = readProperties(root);
        Map<String, String> versions = readDependencyManagement(root);
        String parentRel = readParentRelativePath(root);
        return new PomInfo(pomFile, props, versions, parentRel);
      } catch (Exception e) {
        return null;
      }
    }

    File resolveParentPom() {
      if (parentRelativePath != null && !parentRelativePath.isBlank()) {
        Path candidate = pomFile.toPath().getParent().resolve(parentRelativePath).normalize();
        if (Files.isRegularFile(candidate)) {
          return candidate.toFile();
        }
      }

      Path fallback = pomFile.toPath().getParent().resolve(Path.of("..", "pom.xml")).normalize();
      if (Files.isRegularFile(fallback)) {
        return fallback.toFile();
      }

      return null;
    }

    private static Map<String, String> readProperties(Element root) {
      Map<String, String> props = new HashMap<>();
      Element properties = findChild(root, "properties");
      if (properties == null) {
        return props;
      }

      NodeList children = properties.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node node = children.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          String name = element.getLocalName();
          if (name == null) {
            name = element.getNodeName();
          }
          if (name != null) {
            String value = element.getTextContent();
            if (value != null) {
              props.put(name, value.trim());
            }
          }
        }
      }

      return props;
    }

    private static Map<String, String> readDependencyManagement(Element root) {
      Map<String, String> versions = new HashMap<>();
      Element dependencyManagement = findChild(root, "dependencyManagement");
      if (dependencyManagement == null) {
        return versions;
      }
      Element dependencies = findChild(dependencyManagement, "dependencies");
      if (dependencies == null) {
        return versions;
      }

      NodeList children = dependencies.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node node = children.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          String name = element.getLocalName();
          if (name == null) {
            name = element.getNodeName();
          }
          if (!"dependency".equals(name)) {
            continue;
          }
          String groupId = readChildText(element, "groupId");
          String artifactId = readChildText(element, "artifactId");
          String version = readChildText(element, "version");
          if (groupId != null && artifactId != null && version != null) {
            versions.put(groupId.trim() + ":" + artifactId.trim(), version.trim());
          }
        }
      }

      return versions;
    }

    private static String readChildText(Element root, String name) {
      Element child = findChild(root, name);
      if (child == null) {
        return null;
      }
      String value = child.getTextContent();
      return value == null ? null : value.trim();
    }

    private static String readParentRelativePath(Element root) {
      Element parent = findChild(root, "parent");
      if (parent == null) {
        return null;
      }
      Element relativePath = findChild(parent, "relativePath");
      if (relativePath == null) {
        return null;
      }
      String value = relativePath.getTextContent();
      return value == null ? null : value.trim();
    }

    private static Element findChild(Element root, String name) {
      NodeList children = root.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node node = children.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          String localName = element.getLocalName();
          if (localName == null) {
            localName = element.getNodeName();
          }
          if (name.equals(localName)) {
            return element;
          }
        }
      }
      return null;
    }
  }

  private static String sanitizeValue(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || isUnresolvedPlaceholder(trimmed)) {
      return null;
    }
    return trimmed;
  }

  private static String toEnvKey(String key) {
    return key.replace('.', '_').toUpperCase();
  }

  private static boolean isUnresolvedPlaceholder(String value) {
    return value.startsWith("${") && value.endsWith("}");
  }
}
