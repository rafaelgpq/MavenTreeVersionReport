package com.rafaelgpq.depguardian.config;

import com.rafaelgpq.depguardian.model.DependencyModel;

import com.rafaelgpq.depguardian.input.DependencyTreeReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Validated application settings.
 *
 * <p>Precedence per key: override properties (normally {@code -D} system properties) over the properties file.
 * Relative {@code deps.file} and {@code report.dir} are resolved against the directory containing the file.
 */
public record ApplicationConfig(Path depsFile, Path reportDir, Set<String> includedScopes, boolean strict,
                                boolean dependencyManagement) {

    public static final String DEFAULT_REPORT_DIR = "target/dependency-conflict-report";

    /** Invalid or unusable configuration. */
    public static class ConfigurationException extends IllegalArgumentException {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ApplicationConfig {
        includedScopes = Set.copyOf(includedScopes);
    }

    public DependencyTreeReader.ParseOptions parseOptions() {
        return new DependencyTreeReader.ParseOptions(strict, includedScopes);
    }

    public static ApplicationConfig load(Path configFile, Properties overrides) throws IOException {
        Properties fileProperties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(configFile), StandardCharsets.UTF_8)) {
            fileProperties.load(reader);
        }
        Path base = configFile.toAbsolutePath().getParent();
        if (base == null) base = Path.of("").toAbsolutePath();

        String deps = value("deps.file", fileProperties, overrides, configFile);
        if (deps == null) {
            throw new ConfigurationException("Property 'deps.file' is missing in " + configFile
                    + " (set it there or pass -Ddeps.file=<path>)");
        }
        String report = value("report.dir", fileProperties, overrides, configFile);
        if (report == null) report = DEFAULT_REPORT_DIR;

        return new ApplicationConfig(
                resolve("deps.file", deps, base),
                resolve("report.dir", report, base),
                scopes(value("scopes.include", fileProperties, overrides, configFile)),
                bool("parser.strict", value("parser.strict", fileProperties, overrides, configFile), true),
                bool("report.dependencyManagement",
                        value("report.dependencyManagement", fileProperties, overrides, configFile), false));
    }

    /**
     * Resolves one key by presence: a key present in the overrides always wins over the file, even if its value is
     * unusable, so a typo such as {@code -Ddeps.file=} cannot silently fall back to the file. A key that is present
     * but blank in either source is an error for every property; only an absent key selects the default.
     *
     * @return the stripped value, or {@code null} when the key is absent from both sources
     */
    private static String value(String key, Properties file, Properties overrides, Path configFile) {
        String override = overrides.getProperty(key);
        String chosen = override != null ? override : file.getProperty(key);
        if (chosen == null) return null;
        if (chosen.isBlank()) {
            String where = override != null ? "the -D override" : "the file " + configFile;
            throw new ConfigurationException("Property '" + key + "' is present but blank in " + where
                    + "; give it a value or remove it" + (override != null ? " to use the file/default value" : ""));
        }
        return chosen.strip();
    }

    private static Path resolve(String key, String value, Path base) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
        } catch (InvalidPathException e) {
            throw new ConfigurationException("Property '" + key + "' is not a valid path: '" + value + "'", e);
        }
    }

    private static Set<String> scopes(String value) {
        if (value == null || value.equalsIgnoreCase("all")) return Set.of();
        Set<String> scopes = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String scope = token.strip().toLowerCase(Locale.ROOT);
            if (scope.isEmpty()) continue;
            if (!DependencyModel.KNOWN_SCOPES.contains(scope)) {
                throw new ConfigurationException("Property 'scopes.include' contains unknown scope '" + token.strip()
                        + "'; allowed: all or a comma-separated list of " + new java.util.TreeSet<>(DependencyModel.KNOWN_SCOPES));
            }
            scopes.add(scope);
        }
        if (scopes.isEmpty()) {
            throw new ConfigurationException("Property 'scopes.include' is empty; use 'all' or list scopes");
        }
        return scopes;
    }

    private static boolean bool(String key, String value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new ConfigurationException("Property '" + key + "' must be true or false but was '" + value + "'");
    }
}
