package com.rafaelgpq.depguardian;

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

    private Path writeConfig(String contents) throws IOException {
        return Files.writeString(directory.resolve("application.properties"), contents);
    }
}
