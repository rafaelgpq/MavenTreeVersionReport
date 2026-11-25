package com.rafaelgpq.depguardian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import com.rafaelgpq.depguardian.DependencyConflictFinder.ArtifactKey;
import com.rafaelgpq.depguardian.DependencyConflictFinder.Conflict;
import com.rafaelgpq.depguardian.DependencyConflictFinder.Occurrence;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock("SYSTEM_OUT")
@ResourceLock("SYSTEM_ERR")
class ReportPrinterTest {
    @TempDir Path directory;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private PrintStream captureOut;
    private PrintStream captureErr;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

    @BeforeEach
    void captureConsole() {
        originalOut = System.out;
        originalErr = System.err;
        captureOut = new PrintStream(output, true, StandardCharsets.UTF_8);
        captureErr = new PrintStream(errors, true, StandardCharsets.UTF_8);
        System.setOut(captureOut);
        System.setErr(captureErr);
    }

    @AfterEach
    void restoreConsole() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        captureOut.close();
        captureErr.close();
    }

    @Test
    void printsExplicitMessageWhenThereAreNoConflicts() {
        ReportPrinter.printMarkdown(List.of());
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("No dependency version conflicts found.");
    }

    @Test
    void markdownIncludesBothVersionsAndTheirOrigins() {
        ReportPrinter.printMarkdown(List.of(conflict()));
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("| org.example | shared | 1.0, 2.0 |")
                .contains("module-a root:app:pom:1 -> parent-a:jar:1")
                .contains("module-b root:app:pom:1 -> parent-b:jar:1")
                .contains("| 2.0 |");
    }

    @Test
    void csvCreatesDirectoriesAndWritesOneRowPerVersion() throws Exception {
        Path destination = directory.resolve("reports/conflicts.csv");
        ReportPrinter.printCsv(List.of(conflict()), destination);
        assertThat(Files.readAllLines(destination)).containsExactly(
                "GroupId,ArtifactId,Version,SuggestedVersion,Modules,ParentsPath",
                "org.example,shared,1.0,2.0,module-a,root:app:pom:1 -> parent-a:jar:1",
                "org.example,shared,2.0,2.0,module-b,root:app:pom:1 -> parent-b:jar:1");
        assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void csvQuotesCommasQuotesAndNewlinesAndPreservesUtf8() throws Exception {
        Occurrence occurrence = new Occurrence("1", "módulo,\"quoted\"\nnext", List.of());
        Conflict conflict = new Conflict(new ArtifactKey("org.example", "shared"),
                List.of("1"), List.of(occurrence));
        conflict.suggested("1");
        Path destination = directory.resolve("quoted.csv");
        ReportPrinter.printCsv(List.of(conflict), destination);
        assertThat(Files.readString(destination, StandardCharsets.UTF_8))
                .contains("org.example,shared,1,1,\"módulo,\"\"quoted\"\"\nnext\",");
    }

    @Test
    void xmlContainsSuggestedCoordinatesAndSkipsAbsentSuggestions() throws Exception {
        Conflict absent = new Conflict(new ArtifactKey("org.example", "unselected"), List.of(), List.of());
        Path destination = directory.resolve("reports/dependencyManagement.xml");
        ReportPrinter.printDependencyManagementXml(List.of(conflict(), absent), destination);
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(destination.toFile());
        assertThat(document.getDocumentElement().getTagName()).isEqualTo("dependencyManagement");
        assertThat(document.getElementsByTagName("dependency").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagName("groupId").item(0).getTextContent()).isEqualTo("org.example");
        assertThat(document.getElementsByTagName("artifactId").item(0).getTextContent()).isEqualTo("shared");
        assertThat(document.getElementsByTagName("version").item(0).getTextContent()).isEqualTo("2.0");
    }

    @Test
    void csvReportsAnUnwritableDestination() {
        ReportPrinter.printCsv(List.of(conflict()), directory);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Failed to write CSV");
    }

    private Conflict conflict() {
        Conflict conflict = new Conflict(new ArtifactKey("org.example", "shared"),
                List.of("1.0", "2.0"), List.of(
                new Occurrence("1.0", "module-a", List.of("root:app:pom:1", "parent-a:jar:1")),
                new Occurrence("2.0", "module-b", List.of("root:app:pom:1", "parent-b:jar:1"))));
        conflict.suggested("2.0");
        return conflict;
    }
}
