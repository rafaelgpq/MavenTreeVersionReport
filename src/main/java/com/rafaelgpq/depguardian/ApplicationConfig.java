package com.rafaelgpq.depguardian;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Loads an external, UTF-8 properties file without requiring a rebuild. */
public record ApplicationConfig(Path depsFile) {

    public ApplicationConfig {
        Objects.requireNonNull(depsFile, "depsFile");
    }

    public static ApplicationConfig load(Path configFile, Properties overrides) throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(overrides, "overrides");
        Path absoluteConfig = configFile.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(absoluteConfig, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        String value = overrides.getProperty("deps.file", properties.getProperty("deps.file"));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Property 'deps.file' must not be missing or blank in "
                    + absoluteConfig);
        }
        Path input = Path.of(value.trim());
        if (!input.isAbsolute()) {
            input = absoluteConfig.getParent().resolve(input);
        }
        return new ApplicationConfig(input.normalize());
    }
}
