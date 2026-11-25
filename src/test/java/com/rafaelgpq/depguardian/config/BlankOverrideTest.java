package com.rafaelgpq.depguardian.config;

import com.rafaelgpq.depguardian.config.ApplicationConfig.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Precedence is decided by key presence, then the chosen value is validated: a present but blank override must fail
 * instead of silently reusing the file value; only an absent key selects the file value or the default.
 */
class BlankOverrideTest {

    @TempDir Path dir;

    private Path config(String contents) throws IOException {
        return Files.writeString(dir.resolve("application.properties"), contents);
    }

    private static Properties override(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankDepsFileOverrideFailsInsteadOfFallingBackToFile(String blank) throws IOException {
        Path file = config("deps.file=from-file.txt\n");
        assertThatThrownBy(() -> ApplicationConfig.load(file, override("deps.file", blank)))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("deps.file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.dir", "scopes.include", "parser.strict", "report.dependencyManagement"})
    void blankOverrideOfAnyPropertyFails(String key) throws IOException {
        Path file = config("deps.file=x.txt\n");
        assertThatThrownBy(() -> ApplicationConfig.load(file, override(key, "  ")))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.dir", "scopes.include", "parser.strict", "report.dependencyManagement"})
    void blankFileValueFails(String key) throws IOException {
        Path file = config("deps.file=x.txt\n" + key + "=\n");
        assertThatThrownBy(() -> ApplicationConfig.load(file, new Properties()))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(key);
    }

    @Test
    void absentOptionalKeysUseDocumentedDefaults() throws IOException {
        ApplicationConfig c = ApplicationConfig.load(config("deps.file=x.txt\n"), new Properties());
        assertThat(c.parseOptions().strict()).isTrue();
        assertThat(c.parseOptions().includedScopes()).isEmpty();
        assertThat(c.dependencyManagement()).isFalse();
        assertThat(c.reportDir()).isNotNull();
    }

    @Test
    void missingDepsFileIsAnError() throws IOException {
        Path file = config("parser.strict=true\n");
        assertThatThrownBy(() -> ApplicationConfig.load(file, new Properties()))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("deps.file");
    }

    @Test
    void validOverrideBeatsFileAndRelativeOverrideResolvesAgainstConfigDirectory() throws IOException {
        Path file = config("deps.file=from-file.txt\nreport.dir=file-out\n");
        Properties p = override("deps.file", "sub/override.txt");
        p.setProperty("report.dir", "other-out");
        ApplicationConfig c = ApplicationConfig.load(file, p);
        assertThat(c.depsFile()).isEqualTo(dir.resolve("sub/override.txt"));
        assertThat(c.reportDir()).isEqualTo(dir.resolve("other-out"));
    }
}
