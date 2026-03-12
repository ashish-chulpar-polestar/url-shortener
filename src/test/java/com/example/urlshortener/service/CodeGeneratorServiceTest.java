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
    void generatedCode_hasLength6() {
        String code = codeGeneratorService.generate();
        assertThat(code).hasSize(6);
    }

    @Test
    void generatedCode_isAlphanumeric() {
        String code = codeGeneratorService.generate();
        assertThat(code).matches("[A-Za-z0-9]{6}");
    }

    @Test
    void generate_producesUniqueValues() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(codeGeneratorService.generate());
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void generate_usesUpperAndLowerCase() {
        boolean hasUpper = false;
        boolean hasLower = false;
        for (int i = 0; i < 1_000; i++) {
            String code = codeGeneratorService.generate();
            for (char c : code.toCharArray()) {
                if (Character.isUpperCase(c)) hasUpper = true;
                if (Character.isLowerCase(c)) hasLower = true;
            }
            if (hasUpper && hasLower) break;
        }
        assertThat(hasUpper).isTrue();
        assertThat(hasLower).isTrue();
    }

    @Test
    void generate_usesDigits() {
        boolean hasDigit = false;
        for (int i = 0; i < 1_000; i++) {
            String code = codeGeneratorService.generate();
            for (char c : code.toCharArray()) {
                if (Character.isDigit(c)) {
                    hasDigit = true;
                    break;
                }
            }
            if (hasDigit) break;
        }
        assertThat(hasDigit).isTrue();
    }
}
