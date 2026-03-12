package com.example.urlshortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorServiceTest {

    private CodeGeneratorService codeGeneratorService;

    @BeforeEach
    void setUp() {
        codeGeneratorService = new CodeGeneratorService();
    }

    @Test
    void generate_returnsExactlySixCharacters() {
        String code = codeGeneratorService.generate();
        assertThat(code).hasSize(6);
    }

    @Test
    void generate_containsOnlyAlphanumericCharacters() {
        String code = codeGeneratorService.generate();
        assertThat(code).matches("[A-Za-z0-9]{6}");
    }

    @Test
    void generate_producesRandomCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(codeGeneratorService.generate());
        }
        assertThat(codes.size()).isGreaterThanOrEqualTo(990);
    }
}
