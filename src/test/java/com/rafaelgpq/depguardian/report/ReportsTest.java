package com.rafaelgpq.depguardian.report;

import com.rafaelgpq.depguardian.report.ReportWriter.*;
import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.rafaelgpq.depguardian.support.TestTrees.analyze;
import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CSV, Markdown and candidate-XML rendering, escaping, ordering and file writing. */
class ReportsTest {

    @TempDir Path temp;

    private static final String SPECIAL = lines("org.example:app:pom:1.0",
            "+- org.example:a:jar:1.0:compile",
            "|  \\- org.example:s&<x>:jar:1.0:compile",
            "\\- org.example:b:jar:1.0:compile",
            "   \\- org.example:s&<x>:jar:2.0:compile");

    private static ReportContext context(String source) {
        return new ReportContext(source, true, "all", List.of());
    }

    @Test
    void csvQuotesCommasQuotesAndLineBreaks() {
        assertThat(CsvReport.quote("plain")).isEqualTo("plain");
        assertThat(CsvReport.quote("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvReport.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvReport.quote("l1\nl2")).isEqualTo("\"l1\nl2\"");
        assertThat(CsvReport.quote("\u00E9\u4E2D")).isEqualTo("\u00E9\u4E2D");
    }

    @Test
    void csvHasOneRowPerOccurrenceWithHeader() {
        String csv = CsvReport.render(analyze(SPECIAL));
        String[] rows = csv.split("\r\n");
        assertThat(rows[0]).isEqualTo(String.join(",", CsvReport.HEADER));
        assertThat(rows).hasSize(3);
    }

    @Test
    void markdownEscapesPipesAndAngleBrackets() {
        assertThat(MarkdownReport.cell("a|b <c>\nd")).isEqualTo("a\\|b &lt;c&gt;<br>d");
        String md = MarkdownReport.render(analyze(SPECIAL), context("in|put.txt"));
        assertThat(md).contains("s&<x>"); // inside code spans verbatim
        assertThat(md).doesNotContain("\r").contains("org.example:app:pom:1.0 &gt; org.example:a:jar:1.0 &gt; ");
    }

    @Test
    void markdownDistinguishesIncompleteAnalysis() {
        String md = MarkdownReport.render(analyze(SPECIAL), new ReportContext("x", false, "all", List.of()));
        assertThat(md).contains("Analysis INCOMPLETE").doesNotContain("module-local conflict(s) found");
    }

    @Test
    void markdownStatesBoundedNoConflictWording() {
        String md = MarkdownReport.render(analyze(lines("org.example:app:pom:1.0")), context("x"));
        assertThat(md).contains("no child is shared by different parents within the successfully analyzed input")
                .doesNotContain("No conflicts");
    }

    @Test
    void xmlIsWellFormedEscapedAndCarriesTypeAndClassifier() throws Exception {
        String text = lines("org.example:app:pom:1.0",
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:\u00E9<s>:jar:native:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.example:\u00E9<s>:jar:native:2.0:compile");
        String xml = DependencyManagementXml.render(analyze(text));
        Document doc = parseXml(xml);
        assertThat(text(doc, "artifactId")).containsExactly("\u00E9<s>");
        assertThat(text(doc, "classifier")).containsExactly("native");
        assertThat(text(doc, "version")).containsExactly("2.0");
        assertThat(doc.getElementsByTagName("type").getLength()).isZero();
    }

    @Test
    void xmlKeepsModulesSeparate() throws Exception {
        String text = lines(
                "org.example:one:pom:1.0",
                "+- org.example:a:jar:1.0:compile", "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile", "   \\- org.example:s:jar:2.0:compile",
                "org.example:two:pom:1.0",
                "+- org.example:a:jar:1.0:compile", "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile", "   \\- org.example:s:jar:3.0:compile");
        Document doc = parseXml(DependencyManagementXml.render(analyze(text)));
        assertThat(doc.getElementsByTagName("module").getLength()).isEqualTo(2);
        assertThat(text(doc, "version")).containsExactly("2.0", "3.0");
    }

    @Test
    void xmlOnlyWrittenWhenOptedIn() throws IOException {
        AnalysisResult a = analyze(SPECIAL);
        new ReportWriter().write(temp.resolve("off"), a, context("x"), false);
        assertThat(temp.resolve("off").resolve(ReportWriter.XML)).doesNotExist();
        new ReportWriter().write(temp.resolve("on"), a, context("x"), true);
        assertThat(temp.resolve("on").resolve(ReportWriter.XML)).exists();
    }

    @Test
    void utf8RoundTripsThroughFiles() throws IOException {
        AnalysisResult a = analyze(lines("org.example:app:pom:1.0",
                "+- org.example:a:jar:1.0:compile", "|  \\- org.example:caf\u00E9:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile", "   \\- org.example:caf\u00E9:jar:2.0:compile"));
        new ReportWriter().write(temp, a, context("x"), true);
        assertThat(Files.readString(temp.resolve(ReportWriter.CSV), StandardCharsets.UTF_8)).contains("caf\u00E9");
        assertThat(Files.readString(temp.resolve(ReportWriter.MARKDOWN), StandardCharsets.UTF_8)).contains("caf\u00E9");
    }

    @Test
    void writerReplacesExistingFileAndLeavesNoTempFiles() throws IOException {
        Files.writeString(temp.resolve(ReportWriter.CSV), "old");
        new ReportWriter().write(temp, analyze(SPECIAL), context("x"), false);
        assertThat(Files.readString(temp.resolve(ReportWriter.CSV))).startsWith("Category,");
        try (var files = Files.list(temp)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void writerPropagatesDirectoryCreationFailure() throws IOException {
        Path file = Files.writeString(temp.resolve("not-a-dir"), "x");
        assertThatThrownBy(() -> new ReportWriter().write(file.resolve("sub"), analyze(SPECIAL), context("x"), false))
                .isInstanceOf(ReportException.class).hasMessageContaining("report directory");
    }

    @Test
    void writerPropagatesDestinationWriteFailureAndCleansTempFiles() throws IOException {
        Files.createDirectory(temp.resolve(ReportWriter.CSV)); // destination is a directory
        Files.writeString(temp.resolve(ReportWriter.CSV).resolve("keep"), "x");
        assertThatThrownBy(() -> new ReportWriter().write(temp, analyze(SPECIAL), context("x"), false))
                .isInstanceOf(ReportException.class).hasMessageContaining(ReportWriter.CSV);
        try (var files = Files.list(temp)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    private static Document parseXml(String xml) throws ParserConfigurationException, IOException, SAXException {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> text(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        return java.util.stream.IntStream.range(0, nodes.getLength()).mapToObj(i -> nodes.item(i).getTextContent())
                .toList();
    }
}
