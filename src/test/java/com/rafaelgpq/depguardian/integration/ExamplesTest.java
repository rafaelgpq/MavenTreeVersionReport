package com.rafaelgpq.depguardian.integration;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.cli.ConflictFinderCommand;
import com.rafaelgpq.depguardian.parser.DependencyTreeParser;
import com.rafaelgpq.depguardian.report.ReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the hand-authored teaching examples under {@code examples/} through the real command, redirecting reports to a
 * temp directory, and asserts the findings the README documents.
 */
class ExamplesTest {

    @TempDir Path temp;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String example, Path reports) {
        Properties overrides = new Properties();
        overrides.setProperty("report.dir", reports.toString());
        return new ConflictFinderCommand(DependencyTreeParser::parse, new DependencyAnalyzer(), new ReportWriter(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(Path.of("examples", example, "application.properties"), overrides);
    }

    @Test
    void conflictExampleReportsSharedWithVersionsOneAndTwoFromBothParents() throws IOException {
        assertThat(run("conflict", temp)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RESULT: 1 module-local conflict(s)");
        String csv = Files.readString(temp.resolve(ReportWriter.CSV));
        assertThat(csv).contains("org.example,shared,jar,,1.0,compile,SELECTED")
                .contains("org.example,shared,jar,,2.0,compile,OMITTED_FOR_CONFLICT,1.0")
                .contains("org.example:parent-a:jar:1.0").contains("org.example:parent-b:jar:1.0");
        assertThat(temp.resolve(ReportWriter.XML)).doesNotExist();
    }

    @Test
    void sharedExampleIsSharingNotAConflict() throws IOException {
        assertThat(run("shared", temp)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("1 shared child(ren) with identical versions");
        assertThat(Files.readString(temp.resolve(ReportWriter.MARKDOWN)))
                .contains("Conflicts (different parents and different versions): 0")
                .contains("Shared children, same version: 1");
    }

    @Test
    void missingInputIsAFailureNotAnEmptySuccess() throws IOException {
        Path config = Files.writeString(temp.resolve("application.properties"), "deps.file=absent.txt\n");
        int status = new ConflictFinderCommand(DependencyTreeParser::parse, new DependencyAnalyzer(),
                new ReportWriter(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(config, new Properties());
        assertThat(status).isEqualTo(ConflictFinderCommand.EXIT_FAILURE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("absent.txt");
    }
}
