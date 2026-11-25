package com.rafaelgpq.depguardian.parser;

import com.rafaelgpq.depguardian.input.DependencyTreeReader.Diagnostic;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static com.rafaelgpq.depguardian.support.TestTrees.parse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the "unsupported input must not look like a complete successful scan" policy: anything that
 * looks like dependency content but cannot be parsed is an error, unknown prose only warns, and ordinary Maven
 * console noise stays silent.
 */
class UnsupportedInputTest {

    private static final String ROOT = "org.example:app:pom:1.0";

    @Test
    void singleDashLastChildMarkerIsAnErrorNotASilentlyIgnoredLine() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   - org.example:s:jar:2.0:compile"));
        assertThat(r.isComplete()).isFalse();
        assertThat(r.errors()).extracting(Diagnostic::code).contains("UNPARSED_DEPENDENCY_LINE");
        assertThat(r.errors()).anySatisfy(d -> assertThat(d.line()).isEqualTo(5));
    }

    @Test
    void unexpectedDependencyLookingLineAfterValidRootIsAnError() {
        ParseResult r = parse(lines(ROOT, "+- org.example:a:jar:1.0:compile", "+ org.example:stray:jar:1.0:compile"));
        assertThat(r.isComplete()).isFalse();
        assertThat(r.errors()).extracting(Diagnostic::code).contains("UNPARSED_DEPENDENCY_LINE");
    }

    @Test
    void malformedRootAfterAValidRootIsAnError() {
        ParseResult r = parse(lines(ROOT, "org.example:stray:jar:1.0:a:b:c"));
        assertThat(r.isComplete()).isFalse();
    }

    @Test
    void unknownProseAfterTheRootOnlyWarns() {
        ParseResult r = parse(lines(ROOT, "+- org.example:a:jar:1.0:compile", "something unexpected happened"));
        assertThat(r.isComplete()).isTrue();
        assertThat(r.diagnostics()).extracting(Diagnostic::code).contains("UNRECOGNIZED_LINE");
    }

    @Test
    void moduleHeaderWithoutFollowingTreeIsAnError() {
        ParseResult r = parse(lines("[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---",
                "[INFO] " + ROOT,
                "[INFO] ---------------------< org.example:lib >----------------------",
                "[INFO] BUILD SUCCESS"));
        assertThat(r.isComplete()).isFalse();
        assertThat(r.errors()).extracting(Diagnostic::code).contains("HEADER_WITHOUT_TREE");
    }

    /** Hand-authored malformed inputs (NOT Maven output): name, text, 1-based line that must be diagnosed. */
    static Stream<Arguments> malformedBranches() {
        String head = lines(ROOT, "+- org.example:a:jar:1.0:compile", "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile");
        String logHead = lines("[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---", "[INFO] " + ROOT,
                "[INFO] +- org.example:a:jar:1.0:compile", "[INFO] |  \\- org.example:s:jar:1.0:compile",
                "[INFO] \\- org.example:b:jar:1.0:compile");
        return Stream.of(
                Arguments.of("A plain triple dash", head + "   --- org.example:s:jar:2.0:compile\n", 5),
                Arguments.of("B log triple dash", logHead + "[INFO]    --- org.example:s:jar:2.0:compile\n"
                        + "[INFO] BUILD SUCCESS\n", 6),
                Arguments.of("C plain truncated", head + "   - org.example:s:jar\n", 5),
                Arguments.of("C log truncated", logHead + "[INFO]    - org.example:s:jar\n"
                        + "[INFO] BUILD SUCCESS\n", 6),
                Arguments.of("plain pipe-dash prefix", head + "   |-- org.example:s:jar:2.0:compile\n", 5),
                Arguments.of("separator-like prefix", head + "   ---- org.example:s:jar:2.0:compile\n", 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBranches")
    void malformedDependencyContentIsAnErrorAtItsLineEvenBehindLogPrefixOrBuildSuccess(
            String name, String text, int line) {
        ParseResult r = parse(text);
        assertThat(r.isComplete()).isFalse();
        assertThat(r.errors()).anySatisfy(d -> {
            assertThat(d.line()).isEqualTo(line);
            assertThat(d.source()).isNotBlank();
            assertThat(d.message()).contains("org.example:s:jar");
        });
        // The rejected line must not be interpreted as a dependency occurrence.
        assertThat(r.modules().get(0).occurrences()).extracting(o -> o.child().toString())
                .doesNotContain("org.example:s:jar:2.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"something unexpected happened", "[INFO] something unexpected happened",
            "[WARNING] The POM is a bit odd", "Summary: nothing to see"})
    void unknownOrdinaryProseWarnsButDoesNotMakeTheParseIncomplete(String prose) {
        ParseResult r = parse(lines(ROOT, "+- org.example:a:jar:1.0:compile", prose));
        assertThat(r.isComplete()).isTrue();
        assertThat(r.diagnostics()).anySatisfy(d -> {
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
            assertThat(d.code()).isEqualTo("UNRECOGNIZED_LINE");
            assertThat(d.line()).isEqualTo(3);
        });
    }

    @Test
    void realMavenWrappersAreRecognizedWithoutAnyDiagnostic() {
        ParseResult r = parse(lines(
                "[INFO] Scanning for projects...",
                "[INFO] ",
                "[INFO] Reactor Build Order:",
                "[INFO] ",
                "[INFO] app [pom]",
                "[INFO] ",
                "[INFO] -------------------< org.example:app >-------------------",
                "[INFO] Building app 1.0 [1/1]",
                "[INFO]   from pom.xml",
                "[INFO] --------------------------------[ pom ]---------------------------------",
                "[INFO] ",
                "\u001B[1;34m[INFO]\u001B[m \u001B[1m--- dependency:3.11.0:tree (default-cli) @ app ---\u001B[m",
                "[INFO] " + ROOT,
                "[INFO] +- org.example:a:jar:1.0:compile",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] Reactor Summary for app 1.0:",
                "[INFO] app ................................................ SUCCESS [  0.1 s]",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] BUILD SUCCESS",
                "[INFO] Total time:  0.5 s",
                "[INFO] Finished at: 2026-01-01T00:00:00+00:00"));
        assertThat(r.diagnostics()).isEmpty();
        assertThat(r.modules()).hasSize(1);
        assertThat(r.modules().get(0).occurrences()).hasSize(1);
    }

    @Test
    void rootOnlyModuleAndConsoleNoiseStayComplete() {
        ParseResult r = parse(lines("[INFO] Scanning for projects...",
                "[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---",
                "[INFO] " + ROOT,
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] BUILD SUCCESS",
                "[INFO] Total time:  0.5 s"));
        assertThat(r.isComplete()).isTrue();
        assertThat(r.modules()).hasSize(1);
        assertThat(r.diagnostics()).isEmpty();
    }
}
