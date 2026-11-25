package com.rafaelgpq.depguardian.input;


import com.rafaelgpq.depguardian.model.DependencyModel.ModuleTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Boundary for reading a dependency-tree input, together with the types that describe how it is parsed and what
 * the parse produced. Allows analysis and the command to be tested without files.
 */
@FunctionalInterface
public interface DependencyTreeReader {

    ParseResult read(Path input, ParseOptions options) throws IOException;

    /**
     * Parser settings.
     *
     * @param includedScopes scopes whose occurrences are reported; empty means all scopes. Excluded nodes still
     *                       take part in the structural parent stack, and their descendants are excluded as well.
     */
    record ParseOptions(boolean strict, Set<String> includedScopes) {

        public ParseOptions {
            includedScopes = Set.copyOf(includedScopes);
        }

        public static ParseOptions defaults() {
            return new ParseOptions(true, Set.of());
        }

        public boolean includes(String scope) {
            return includedScopes.isEmpty() || (scope != null && includedScopes.contains(scope));
        }
    }

    /** A parsing observation tied to an input location. */
    record Diagnostic(Severity severity, String code, String source, int line, String message) {

        /** ERROR makes the input incomplete (strict: exit 1, lenient: exit 2); WARNING never does. */
        public enum Severity { WARNING, ERROR }

        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return severity + " " + code + " " + source + ":" + line + " " + message;
        }
    }

    /**
     * Outcome of parsing one input.
     *
     * @param source      input name used in diagnostics
     * @param modules     module roots found, in input order
     * @param diagnostics warnings and errors, in input order
     * @param statistics  line classification counts
     */
    record ParseResult(String source, List<ModuleTree> modules, List<Diagnostic> diagnostics,
                       Statistics statistics) {

        /** Line counts; {@code ignoredLines} are lines that were neither tree structure nor recognized log noise. */
        public record Statistics(int totalLines, int blankLines, int recognizedTreeLines, int recognizedNoiseLines,
                                 int ignoredLines, int scopeFilteredOccurrences) {}

        public ParseResult {
            Objects.requireNonNull(source, "source");
            modules = List.copyOf(modules);
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(statistics, "statistics");
        }

        public List<Diagnostic> errors() {
            return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
        }

        /** True when no error diagnostics were raised, i.e. everything dependency-like was understood. */
        public boolean isComplete() {
            return errors().isEmpty();
        }
    }

    /** Strict-mode (or no-module) parse failure; analysis must not be reported as successful. */
    class InvalidInputException extends RuntimeException {
        private final transient ParseResult parseResult;

        public InvalidInputException(String message, ParseResult parseResult) {
            super(message);
            this.parseResult = parseResult;
        }

        public ParseResult parseResult() {
            return parseResult;
        }

        public List<Diagnostic> diagnostics() {
            return parseResult.diagnostics();
        }
    }
}
