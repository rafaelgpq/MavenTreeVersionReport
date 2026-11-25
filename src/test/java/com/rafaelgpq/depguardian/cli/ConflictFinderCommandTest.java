package com.rafaelgpq.depguardian.cli;

import com.rafaelgpq.depguardian.parser.DependencyTreeParser;
import com.rafaelgpq.depguardian.input.DependencyTreeReader;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.report.ReportWriter;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Run coordination, messages and exit statuses of the command, with Mockito used only for collaborator failure paths. */
class ConflictFinderCommandTest {

    @TempDir Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ConflictFinderCommand command() {
        return new ConflictFinderCommand(
                DependencyTreeParser::parse, new DependencyAnalyzer(),
                new ReportWriter(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path config(String input, String extra) throws IOException {
        Files.writeString(temp.resolve("deps.txt"), input);
        return Files.writeString(temp.resolve("application.properties"), "deps.file=deps.txt\n" + extra);
    }

    private static final String CONFLICT = lines("org.example:app:pom:1.0",
            "+- org.example:a:jar:1.0:compile", "|  \\- org.example:s:jar:1.0:compile",
            "\\- org.example:b:jar:1.0:compile", "   \\- org.example:s:jar:2.0:compile");

    @Test
    void validConflictInputExitsZeroAndAnnouncesReports() throws IOException {
        assertThat(command().run(config(CONFLICT, ""), new Properties())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RESULT: 1 module-local conflict(s)")
                .contains("Report written");
        assertThat(temp.resolve("target/dependency-conflict-report/" + ReportWriter.CSV)).exists();
    }

    @Test
    void noSharedChildrenIsAScopedStatement() throws IOException {
        assertThat(command().run(config(lines("org.example:app:pom:1.0"), ""), new Properties())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("within the analyzed input and selected scopes");
    }

    @Test
    void malformedInputFailsWithDiagnosticsAndNoReports() throws IOException {
        Path config = config(lines("org.example:app:pom:1.0", "-- org.example:a:jar:1.0:compile"), "");
        assertThat(command().run(config, new Properties())).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("MALFORMED_TREE_LINE").contains("deps.txt:2")
                .contains("analysis not performed");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("RESULT");
        assertThat(temp.resolve("target")).doesNotExist();
    }

    @Test
    void emptyAndUnrelatedInputFail() throws IOException {
        assertThat(command().run(config("", ""), new Properties())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("EMPTY_INPUT");
        assertThat(command().run(config("hello\n", ""), new Properties())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("NO_TREE_FOUND");
    }

    @Test
    void nonStrictModeContinuesButIsReportedIncomplete() throws IOException {
        Path config = config(CONFLICT + "-- junk\n", "parser.strict=false\n");
        assertThat(command().run(config, new Properties())).isEqualTo(ConflictFinderCommand.EXIT_INCOMPLETE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("INCOMPLETE");
        assertThat(Files.readString(temp.resolve("target/dependency-conflict-report/" + ReportWriter.MARKDOWN)))
                .contains("INCOMPLETE");
    }

    @Test
    void nonStrictModeStillFailsWithoutAnyModule() throws IOException {
        assertThat(command().run(config("junk\n", "parser.strict=false\n"), new Properties())).isEqualTo(1);
    }

    @Test
    void missingInputFileFails() throws IOException {
        Path config = config(CONFLICT, "");
        Files.delete(temp.resolve("deps.txt"));
        assertThat(command().run(config, new Properties())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("input not found");
    }

    @Test
    void invalidConfigurationAndMissingConfigFail() throws IOException {
        assertThat(command().run(config(CONFLICT, "scopes.include=bogus\n"), new Properties())).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown scope 'bogus'");
        assertThat(command().run(temp.resolve("absent.properties"), new Properties())).isEqualTo(1);
    }

    @Test
    void invalidReportDirectoryFailsWithoutSuccessSummary() throws IOException {
        Files.writeString(temp.resolve("blocker"), "file");
        Path config = config(CONFLICT, "report.dir=blocker/sub\n");
        assertThat(command().run(config, new Properties())).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("report generation failed");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("RESULT").doesNotContain("Report written");
    }

    @Test
    void scopeFilterAppliesFromConfiguration() throws IOException {
        String input = lines("org.example:app:pom:1.0",
                "+- org.example:a:jar:1.0:compile", "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:test", "   \\- org.example:s:jar:2.0:test");
        command().run(config(input, ""), new Properties());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("1 module-local conflict(s)");
        out.reset();
        command().run(config(input, "scopes.include=compile\n"), new Properties());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("no child shared by different parents");
    }

    @Test
    void serviceFailsStrictOnParseErrorsUsingMockedReaderBoundary() throws IOException {
        DependencyTreeReader reader = mock(DependencyTreeReader.class);
        ParseResult bad = new ParseResult("src", java.util.List.of(
                new ModuleTree(Coordinate.of("g", "a", "pom", "", "1"), 1, java.util.List.of())),
                java.util.List.of(new Diagnostic(Diagnostic.Severity.ERROR, "X", "src", 2, "boom")),
                new ParseResult.Statistics(2, 0, 1, 0, 0, 0));
        when(reader.read(any(), any())).thenReturn(bad);
        DependencyAnalyzer analyzer = new DependencyAnalyzer();

        assertThatThrownBy(() -> analyzer.analyze(Path.of("x"), reader.read(Path.of("x"), ParseOptions.defaults()), ParseOptions.defaults()))
                .isInstanceOf(InvalidInputException.class);
        ParseOptions loose = new ParseOptions(false, java.util.Set.of());
        DependencyAnalyzer.Result lenient = analyzer.analyze(Path.of("x"), reader.read(Path.of("x"), loose), loose);
        assertThat(lenient.complete()).isFalse();
        verify(reader, org.mockito.Mockito.times(2)).read(any(), any());
    }
}
