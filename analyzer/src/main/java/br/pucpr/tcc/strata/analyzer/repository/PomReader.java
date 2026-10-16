package br.pucpr.tcc.strata.analyzer.repository;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

final class PomReader {

  private static final String PROJECT = "project";
  private static final List<String> MODULE = List.of(PROJECT, "modules", "module");
  private static final List<String> PROFILE_MODULE =
      List.of(PROJECT, "profiles", "profile", "modules", "module");
  private static final List<String> SOURCE_DIRECTORY = List.of(PROJECT, "build", "sourceDirectory");
  private static final List<String> TEST_SOURCE_DIRECTORY =
      List.of(PROJECT, "build", "testSourceDirectory");
  private static final List<List<String>> WANTED =
      List.of(MODULE, PROFILE_MODULE, SOURCE_DIRECTORY, TEST_SOURCE_DIRECTORY);
  private static final int MAX_WANTED_DEPTH = PROFILE_MODULE.size();

  private PomReader() {}

  record Pom(
      List<String> modules,
      List<String> profileModules,
      List<String> sourceDirectories,
      List<String> testSourceDirectories) {

    Pom {
      modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
      profileModules = List.copyOf(Objects.requireNonNull(profileModules, "profileModules"));
      sourceDirectories =
          List.copyOf(Objects.requireNonNull(sourceDirectories, "sourceDirectories"));
      testSourceDirectories =
          List.copyOf(Objects.requireNonNull(testSourceDirectories, "testSourceDirectories"));
    }

    List<String> allModules() {
      final Set<String> all = new LinkedHashSet<>(modules);
      all.addAll(profileModules);
      return List.copyOf(all);
    }
  }

  static Pom read(byte[] content) throws XMLStreamException {
    Objects.requireNonNull(content, "content");
    final XMLStreamReader reader =
        hardenedFactory().createXMLStreamReader(new ByteArrayInputStream(content));
    try {
      return collect(reader);
    } finally {
      reader.close();
    }
  }

  private static XMLInputFactory hardenedFactory() {
    final XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    return factory;
  }

  private static Pom collect(XMLStreamReader reader) throws XMLStreamException {
    final Map<List<String>, List<String>> collected = new HashMap<>();
    for (final List<String> wanted : WANTED) {
      collected.put(wanted, new ArrayList<>());
    }
    final Deque<String> path = new ArrayDeque<>();
    final StringBuilder text = new StringBuilder();
    while (reader.hasNext()) {
      switch (reader.next()) {
        case XMLStreamConstants.START_ELEMENT -> {
          if (path.isEmpty() && !PROJECT.equals(reader.getLocalName())) {
            throw new XMLStreamException("o elemento raiz não é <project>");
          }
          path.addLast(reader.getLocalName());
          text.setLength(0);
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
            text.append(reader.getText());
        case XMLStreamConstants.END_ELEMENT -> {
          if (path.size() <= MAX_WANTED_DEPTH) {
            final List<String> sink = collected.get(List.copyOf(path));
            final String value = text.toString().strip();
            if (sink != null && !value.isEmpty()) {
              sink.add(value);
            }
          }
          text.setLength(0);
          path.removeLast();
        }
        default -> {}
      }
    }
    return new Pom(
        collected.get(MODULE),
        collected.get(PROFILE_MODULE),
        collected.get(SOURCE_DIRECTORY),
        collected.get(TEST_SOURCE_DIRECTORY));
  }
}
