package com.rafaelgpq.depguardian.cli;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.parser.DependencyTreeParser;
import com.rafaelgpq.depguardian.report.ReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Process-status tests for unsupported input (strict exit 1, lenient exit 2, valid exit 0) and for the
 * stale candidate-XML policy, driven through the real parser, analyzer and writer.
 */
class UnsupportedInputCommandTest {

    private static final String MALFORMED = lines("org.example:app:pom:1.0",
            "+- org.example:a:jar:1.0:compile",
            "|  \\- org.example:s:jar:1.0:compile",
            "\\- org.example:b:jar:1.0:compile",
            "   - org.example:s:jar:2.0:compile");
    private static final String CONFLICT = lines("org.example:app:pom:1.0",
            "+- org.example:a:jar:1.0:compile",
            "|  \\- org.example:s:jar:1.0:compile",
            "\\- org.example:b:jar:1.0:compile",
            "   \\- org.example:s:jar:2.0:compile");

    @TempDir Path temp;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String input, String extra) throws IOException {
        Files.writeString(temp.resolve("deps.txt"), input);
        Path config = Files.writeString(temp.resolve("application.properties"), "deps.file=deps.txt\n" + extra);
        return new ConflictFinderCommand(DependencyTreeParser::parse, new DependencyAnalyzer(), new ReportWriter(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(config, new Properties());
    }

    static Stream<Arguments> malformedInputs() {
        String plain = lines("org.example:app:pom:1.0", "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile", "\\- org.example:b:jar:1.0:compile");
        String log = lines("[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---", "[INFO] org.example:app:pom:1.0",
                "[INFO] +- org.example:a:jar:1.0:compile", "[INFO] |  \\- org.example:s:jar:1.0:compile",
                "[INFO] \\- org.example:b:jar:1.0:compile");
        return Stream.of(
                Arguments.of("A", plain + "   --- org.example:s:jar:2.0:compile\n", 5),
                Arguments.of("B", log + "[INFO]    --- org.example:s:jar:2.0:compile\n[INFO] BUILD SUCCESS\n", 6),
                Arguments.of("C", plain + "   - org.example:s:jar\n", 5),
                Arguments.of("C-log", log + "[INFO]    - org.example:s:jar\n[INFO] BUILD SUCCESS\n", 6));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedInputs")
    void strictRunRejectsWithExitOneAndWritesNoReports(String name, String input, int line) throws IOException {
        assertThat(run(input, "report.dir=fresh\n")).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("deps.txt:" + line).contains("analysis not performed");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("RESULT");
        assertThat(temp.resolve("fresh")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedInputs")
    void lenientRunExitsTwoLabelsPartialResultAndMarksReportIncomplete(String name, String input, int line)
            throws IOException {
        assertThat(run(input, "parser.strict=false\nreport.dir=fresh\n")).isEqualTo(ConflictFinderCommand.EXIT_INCOMPLETE);
        String console = out.toString(StandardCharsets.UTF_8);
        assertThat(console).contains("PARTIAL RESULT");
        assertThat(console.lines()).noneMatch(l -> l.startsWith("RESULT:"));
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("deps.txt:" + line).contains("INCOMPLETE");
        assertThat(Files.readString(temp.resolve("fresh").resolve(ReportWriter.MARKDOWN)))
                .contains("Input status: **INCOMPLETE**");
    }

    @Test
    void unknownProseOnlyWarnsAndStillCompletesWithExitZero() throws IOException {
        assertThat(run(CONFLICT + "something unexpected happened\n", "")).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("WARNING UNRECOGNIZED_LINE");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RESULT: 1 module-local conflict");
    }

    @Test
    void strictModeRejectsMalformedTreeWithLineDiagnosticAndNoReports() throws IOException {
        assertThat(run(MALFORMED, "")).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("UNPARSED_DEPENDENCY_LINE").contains("deps.txt:5");
        assertThat(temp.resolve("target")).doesNotExist();
    }

    @Test
    void lenientModeReportsIncompleteWithExitTwoInsteadOfATrustedNegative() throws IOException {
        assertThat(run(MALFORMED, "parser.strict=false\nreport.dir=out\n"))
                .isEqualTo(ConflictFinderCommand.EXIT_INCOMPLETE);
        assertThat(Files.readString(temp.resolve("out").resolve(ReportWriter.MARKDOWN)))
                .contains("Input status: **INCOMPLETE**");
    }

    @Test
    void consoleLogWithMalformedLastChildIsAlsoRejected() throws IOException {
        String log = lines("[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---",
                "[INFO] org.example:app:pom:1.0",
                "[INFO] \\- org.example:a:jar:1.0:compile",
                "[INFO]    - org.example:b:jar:1.0:compile",
                "[INFO] BUILD SUCCESS");
        assertThat(run(log, "")).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
    }

    @Test
    void validRootOnlyModuleStillCompletesWithExitZero() throws IOException {
        assertThat(run(lines("org.example:app:pom:1.0"), "")).isZero();
    }

    @Test
    void candidateXmlDisabledOnFirstRunWritesNoXml() throws IOException {
        assertThat(run(CONFLICT, "report.dir=out\n")).isZero();
        assertThat(temp.resolve("out").resolve(ReportWriter.XML)).doesNotExist();
    }

    @Test
    void disablingCandidateXmlRemovesOnlyTheStaleToolOwnedFile() throws IOException {
        assertThat(run(CONFLICT, "report.dir=out\nreport.dependencyManagement=true\n")).isZero();
        Path xml = temp.resolve("out").resolve(ReportWriter.XML);
        assertThat(xml).exists();
        Path unrelated = Files.writeString(temp.resolve("out").resolve("notes.txt"), "keep");

        assertThat(run(lines("org.example:app:pom:1.0"), "report.dir=out\nreport.dependencyManagement=false\n")).isZero();
        assertThat(xml).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void staleXmlCleanupFailureIsAReportFailure() throws IOException {
        // A non-empty directory with the XML's name cannot be deleted by Files.deleteIfExists.
        Path blocker = temp.resolve("out").resolve(ReportWriter.XML);
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("child"), "x");
        assertThat(run(CONFLICT, "report.dir=out\n")).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Cannot remove stale");
    }
}
