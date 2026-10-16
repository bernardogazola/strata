package br.pucpr.tcc.strata.analyzer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PackageDeclarationsTest {

  @Test
  void cabecalhoDeLicencaEComentariosDeLinhaAntesDoPackage() {
    final String source =
        """
        /*
         * Licensed to the Apache Software Foundation (ASF). package not.this;
         */
        // package nor.this;

        package com.acme.core;

        public class Config {}
        """;

    assertThat(PackageDeclarations.declaredPackage(source)).contains("com.acme.core");
  }

  @Test
  void packageInfoComAnotacoesComArgumentos() {
    final String source =
        """
        /** A documentação do pacote. */
        @Deprecated
        @javax.annotation.Generated(value = "gen(\\"x\\")", comments = ")")
        @SuppressWarnings({"unchecked", "rawtypes"})
        @ Spaced . Out ( /* ) */ 1 )
        package br.pucpr.broken;
        """;

    assertThat(PackageDeclarations.declaredPackage(source)).contains("br.pucpr.broken");
  }

  @Test
  void anotacaoComBlocoDeTextoECaractereComParenteses() {
    final String source =
        """
        @Note(value = \"""
            ) ( "
            \""", open = '(', close = ')')
        package a.b;
        """;

    assertThat(PackageDeclarations.declaredPackage(source)).contains("a.b");
  }

  @Test
  void brancosEComentariosDentroDoNomeQualificado() {
    assertThat(PackageDeclarations.declaredPackage("package  a . b /* x */ . c ;"))
        .contains("a.b.c");
  }

  @Test
  void identificadorUnicodeELido() {
    assertThat(PackageDeclarations.declaredPackage("package br.pucpr.pedidos.avaliação;"))
        .contains("br.pucpr.pedidos.avaliação");
  }

  @Test
  void bomEmBytesETextoETolerado() {
    final byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    final byte[] body = "package com.x;".getBytes(StandardCharsets.UTF_8);
    final byte[] content = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, content, 0, bom.length);
    System.arraycopy(body, 0, content, bom.length, body.length);

    assertThat(PackageDeclarations.declaredPackage(content)).contains("com.x");
    assertThat(PackageDeclarations.declaredPackage("﻿package com.y;")).contains("com.y");
  }

  @Test
  void bytesQueNaoSaoUtf8NaoDerrubamALeitura() {
    final byte[] invalid = {'p', 'a', 'c', 'k', 'a', 'g', 'e', ' ', (byte) 0xC3, 0x28, ';'};

    assertThat(PackageDeclarations.declaredPackage(invalid)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   \n\t ",
        "// só comentário",
        "/* comentário sem fecho package a;",
        "import java.util.List;\npackage a;",
        "public class Bootstrap {}",
        "class A {}",
        "module com.x { requires java.sql; }",
        "@interface Marker {}",
        "@Ann(unclosed package a;",
        "package a.b",
        "package ;",
        "package a..b;",
        "package a.;",
        "packages a;",
        "\u0000\u0001 garbage ;;; package a;"
      })
  void qualquerOutraCoisaPrimeiroEPacotePadrao(String source) {
    assertThat(PackageDeclarations.declaredPackage(source)).isEmpty();
  }
}
