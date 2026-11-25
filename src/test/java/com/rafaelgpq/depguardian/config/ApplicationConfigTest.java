package com.rafaelgpq.depguardian.config;

import com.rafaelgpq.depguardian.config.ApplicationConfig.ConfigurationException;
import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Property loading, -D overrides, relative-path resolution and validation of each setting. */
class ApplicationConfigTest {
    @TempDir Path directory;

    @Test
    void resolvesRelativeInputAgainstConfigurationDirectory() throws IOException {
        Path configFile = writeConfig("deps.file=data/deps.txt\n");
        ApplicationConfig config = ApplicationConfig.load(configFile, new Properties());
        assertThat(config.depsFile()).isEqualTo(directory.resolve("data/deps.txt"));
    }

    @Test
    void allowsChangingTheInputWithoutChangingJavaCode() throws IOException {
        Path configFile = writeConfig("deps.file=first.txt\n");
        assertThat(ApplicationConfig.load(configFile, new Properties()).depsFile())
                .isEqualTo(directory.resolve("first.txt"));
        Files.writeString(configFile, "deps.file=second.txt\n");
        assertThat(ApplicationConfig.load(configFile, new Properties()).depsFile())
                .isEqualTo(directory.resolve("second.txt"));
    }

    @Test
    void systemPropertyOverrideWinsOverFileValue() throws IOException {
        Path configFile = writeConfig("deps.file=file-value.txt\n");
        Properties overrides = new Properties();
        overrides.setProperty("deps.file", "override.txt");
        assertThat(ApplicationConfig.load(configFile, overrides).depsFile())
                .isEqualTo(directory.resolve("override.txt"));
    }

    @Test
    void preservesAbsoluteInputPaths() throws IOException {
        Path input = directory.resolve("absolute.txt").toAbsolutePath();
        // Forward slashes work in Java properties on Windows as well.
        Path configFile = writeConfig("deps.file=" + input.toString().replace('\\', '/') + "\n");
        assertThat(ApplicationConfig.load(configFile, new Properties()).depsFile()).isEqualTo(input);
    }

    @Test
    void supportsUtf8PathsAndTrimsSurroundingWhitespace() throws IOException {
        Path configFile = writeConfig("deps.file=  données/deps.txt   \n");
        assertThat(ApplicationConfig.load(configFile, new Properties()).depsFile())
                .isEqualTo(directory.resolve("données/deps.txt"));
    }

    @Test
    void rejectsMissingInputProperty() throws IOException {
        Path configFile = writeConfig("# No configured input\n");
        assertThatThrownBy(() -> ApplicationConfig.load(configFile, new Properties()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deps.file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void rejectsBlankInputProperty(String value) throws IOException {
        Path configFile = writeConfig("deps.file=" + value + "\n");
        assertThatThrownBy(() -> ApplicationConfig.load(configFile, new Properties()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deps.file");
    }

    @Test
    void reportsMissingConfigurationFile() {
        assertThatThrownBy(() -> ApplicationConfig.load(directory.resolve("absent.properties"), new Properties()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void defaultsIncludeAllScopesStrictAndConfigRelativeReportDir() throws IOException {
        ApplicationConfig config = ApplicationConfig.load(writeConfig("deps.file=d.txt\n"), new Properties());
        assertThat(config.includedScopes()).isEmpty();
        assertThat(config.strict()).isTrue();
        assertThat(config.dependencyManagement()).isFalse();
        assertThat(config.reportDir()).isEqualTo(directory.resolve("target/dependency-conflict-report"));
    }

    @Test
    void parsesScopesAndOverridesWin() throws IOException {
        Path file = writeConfig("deps.file=d.txt\nscopes.include= Compile , runtime\nparser.strict=false\n"
                + "report.dir=reports\nreport.dependencyManagement=true\n");
        ApplicationConfig config = ApplicationConfig.load(file, new Properties());
        assertThat(config.includedScopes()).containsExactlyInAnyOrder("compile", "runtime");
        assertThat(config.strict()).isFalse();
        assertThat(config.dependencyManagement()).isTrue();
        assertThat(config.reportDir()).isEqualTo(directory.resolve("reports"));
        Properties overrides = new Properties();
        overrides.setProperty("scopes.include", "all");
        overrides.setProperty("report.dir", directory.resolve("abs").toString());
        ApplicationConfig overridden = ApplicationConfig.load(file, overrides);
        assertThat(overridden.includedScopes()).isEmpty();
        assertThat(overridden.reportDir()).isEqualTo(directory.resolve("abs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scopes.include=bogus", "scopes.include=compile,nope", "scopes.include=,",
            "parser.strict=maybe", "report.dependencyManagement=yes"})
    void rejectsInvalidSettings(String line) throws IOException {
        Path file = writeConfig("deps.file=d.txt\n" + line + "\n");
        assertThatThrownBy(() -> ApplicationConfig.load(file, new Properties()))
                .isInstanceOf(ConfigurationException.class);
    }

    private Path writeConfig(String contents) throws IOException {
        return Files.writeString(directory.resolve("application.properties"), contents);
    }
}
