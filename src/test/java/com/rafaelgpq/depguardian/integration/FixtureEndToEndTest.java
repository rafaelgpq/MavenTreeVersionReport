package com.rafaelgpq.depguardian.integration;

import com.rafaelgpq.depguardian.parser.DependencyTreeParser;
import com.rafaelgpq.depguardian.input.DependencyTreeReader;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.cli.ConflictFinderCommand;
import com.rafaelgpq.depguardian.report.ReportWriter;
import com.rafaelgpq.depguardian.support.TestTrees;

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

import static org.assertj.core.api.Assertions.assertThat;

/** Real parser + analyzer + writers over checked-in Maven-generated fixtures (see fixtures README). */
class FixtureEndToEndTest {

    private static final Path DIR = Path.of("src", "test", "resources", "fixtures", "maven-generated");
    private static final String SHARED = "org.example.lib:shared:jar";

    @TempDir Path temp;

    private DependencyAnalyzer.Result analyze(String file) throws IOException {
        Path input = DIR.resolve(file);
        return new DependencyAnalyzer().analyze(input, DependencyTreeParser.parse(input, ParseOptions.defaults()),
                ParseOptions.defaults());
    }

    @Test
    void everyCheckedInFixtureParsesWithoutAnyDiagnosticSoNoRealWrapperLineIsMisclassified() throws IOException {
        for (String c : new String[] {"conflict", "noconflict", "classifier", "managed", "multimodule"}) {
            for (String ext : new String[] {".tree.txt", ".console.log"}) {
                assertThat(analyze(c + ext).parse().diagnostics()).as(c + ext).isEmpty();
            }
        }
    }

    @Test
    void conflictFixtureReportsDifferentParentConflictWithExactEvidence() throws IOException {
        for (String file : new String[] {"conflict.tree.txt", "conflict.console.log"}) {
            DependencyAnalyzer.Result result = analyze(file);
            assertThat(result.complete()).as(file).isTrue();
            ModuleAnalysis module = result.analysis().modules().get(0);
            assertThat(module.root().toString()).isEqualTo("org.example.app:conflict-app:pom:1.0");
            assertThat(module.conflicts()).hasSize(1);
            ChildFinding f = module.conflicts().get(0);
            assertThat(f.identity().toString()).isEqualTo(SHARED);
            assertThat(f.versions()).containsExactly("1.0", "2.0", "3.0");
            assertThat(f.selectedVersions()).containsExactly("1.0");
            assertThat(f.candidateVersion()).isEqualTo("3.0");
            assertThat(f.conflicting()).extracting(Occurrence::fullPathText).containsExactly(
                    "org.example.app:conflict-app:pom:1.0 > org.example.lib:lib-a:jar:1.0 > " + SHARED + ":1.0",
                    "org.example.app:conflict-app:pom:1.0 > org.example.lib:lib-c:jar:1.0 > " + SHARED + ":1.0",
                    "org.example.app:conflict-app:pom:1.0 > org.example.lib:lib-b:jar:1.0 > " + SHARED + ":2.0",
                    "org.example.app:conflict-app:pom:1.0 > org.example.lib:lib-d:jar:1.0 > "
                            + "org.example.lib:lib-e:jar:1.0 > " + SHARED + ":3.0");
            Occurrence omitted = f.occurrences().stream().filter(o -> o.version().equals("2.0")).findFirst()
                    .orElseThrow();
            assertThat(omitted.status()).isEqualTo(NodeStatus.OMITTED_FOR_CONFLICT);
            assertThat(omitted.selectedVersion()).isEqualTo("1.0");
            assertThat(omitted.parent().toString()).isEqualTo("org.example.lib:lib-b:jar:1.0");
        }
    }

    @Test
    void conflictFixtureWithoutTestScopeStillAttributesStructureCorrectly() throws IOException {
        ParseResult r = DependencyTreeParser.parse(DIR.resolve("conflict.tree.txt"), TestTrees.scopes("compile"));
        assertThat(r.statistics().scopeFilteredOccurrences()).isEqualTo(2);
        assertThat(r.modules().get(0).occurrences()).extracting(o -> o.parent().identity().artifactId())
                .containsExactly("conflict-app", "lib-a", "conflict-app", "lib-b", "conflict-app", "lib-d", "lib-e");
    }

    @Test
    void noConflictFixtureHasSharedChildWithSameVersion() throws IOException {
        for (String file : new String[] {"noconflict.tree.txt", "noconflict.console.log"}) {
            DependencyAnalyzer.Result result = analyze(file);
            assertThat(result.analysis().outcome()).as(file).isEqualTo(AnalysisResult.Outcome.SHARED_CHILDREN_ONLY);
            assertThat(result.analysis().modules().get(0).sharedChildren()).hasSize(1);
            assertThat(result.analysis().modules().get(0).sharedChildren().get(0).versions()).containsExactly("1.0");
        }
    }

    @Test
    void classifierFixtureKeepsClassifierAndTypeOutOfVersion() throws IOException {
        ModuleAnalysis module = analyze("classifier.tree.txt").analysis().modules().get(0);
        assertThat(module.conflicts()).extracting(f -> f.identity().toString())
                .containsExactly("org.example.lib:shared:jar:native");
        assertThat(module.conflicts().get(0).versions()).containsExactly("1.0", "2.0");
        assertThat(module.findings()).extracting(f -> f.identity().toString())
                .doesNotContain(SHARED, "org.example.lib:shared:test-jar:tests");
    }

    @Test
    void managedFixtureTreatsManagedFromAsProvenanceNotAVersionOccurrence() throws IOException {
        DependencyAnalyzer.Result result = analyze("managed.tree.txt");
        ChildFinding f = result.analysis().modules().get(0).sharedChildren().get(0);
        assertThat(f.versions()).containsExactly("2.0");
        assertThat(f.occurrences()).extracting(Occurrence::managedFrom).containsExactly("1.0", "2.0");
        assertThat(result.analysis().conflictCount()).isZero();
    }

    @Test
    void multimoduleOutputFileAndReactorLogGiveSameModuleLocalResults() throws IOException {
        for (String file : new String[] {"multimodule.tree.txt", "multimodule.console.log"}) {
            AnalysisResult a = analyze(file).analysis();
            assertThat(a.modules()).extracting(m -> m.root().identity().artifactId())
                    .containsExactly("mod-one", "mod-three", "mod-two", "multi-parent");
            assertThat(find(a, "mod-one").sharedChildren()).hasSize(1);
            assertThat(find(a, "mod-one").conflicts()).isEmpty();
            assertThat(find(a, "mod-two").findings()).isEmpty();
            assertThat(find(a, "multi-parent").occurrenceCount()).isZero();
            ChildFinding c = find(a, "mod-three").conflicts().get(0);
            assertThat(c.versions()).containsExactly("1.0", "2.0");
            assertThat(c.conflicting()).extracting(o -> o.module().identity().artifactId())
                    .containsOnly("mod-three");
        }
    }

    @Test
    void handAuthoredMalformedFixtureYieldsLocatedDiagnostics() throws IOException {
        ParseResult r = DependencyTreeParser.parse(
                Path.of("src", "test", "resources", "fixtures", "hand-authored", "malformed.console.log"),
                ParseOptions.defaults());
        assertThat(r.errors()).extracting(Diagnostic::code, Diagnostic::line)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("IMPOSSIBLE_HIERARCHY", 4),
                        org.assertj.core.groups.Tuple.tuple("MALFORMED_TREE_LINE", 5));
    }

    private static ModuleAnalysis find(AnalysisResult a, String artifact) {
        return a.modules().stream().filter(m -> m.root().identity().artifactId().equals(artifact)).findFirst()
                .orElseThrow();
    }

    @Test
    void cliWritesOccurrenceLevelReportsForConflictFixtureAndIsRepeatable() throws IOException {
        Path config = Files.writeString(temp.resolve("application.properties"),
                "deps.file=" + DIR.resolve("conflict.tree.txt").toAbsolutePath().toString().replace('\\', '/')
                        + "\nreport.dir=out\nreport.dependencyManagement=true\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConflictFinderCommand command = new ConflictFinderCommand(
                DependencyTreeParser::parse, new DependencyAnalyzer(),
                new ReportWriter(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(command.run(config, new Properties())).isEqualTo(ConflictFinderCommand.EXIT_OK);
        String csv = Files.readString(temp.resolve("out").resolve(ReportWriter.CSV));
        String md = Files.readString(temp.resolve("out").resolve(ReportWriter.MARKDOWN));
        String xml = Files.readString(temp.resolve("out").resolve(ReportWriter.XML));

        assertThat(csv).contains("CONFLICT,org.example.app:conflict-app:pom:1.0,org.example.lib,shared,jar,,2.0,"
                + "compile,OMITTED_FOR_CONFLICT,1.0,,org.example.lib:lib-b:jar:1.0,"
                + "org.example.app:conflict-app:pom:1.0 > org.example.lib:lib-b:jar:1.0 > " + SHARED + ":2.0,"
                + "true,3.0,5\r\n");
        assertThat(csv).contains("CONFLICT,org.example.app:conflict-app:pom:1.0,org.example.lib,shared,jar,,1.0,"
                + "test,OMITTED_FOR_DUPLICATE");
        assertThat(md).contains("1 module-local conflict(s)")
                .contains("lib-d:jar:1.0 &gt; org.example.lib:lib-e:jar:1.0");
        assertThat(xml).contains("<version>3.0</version>").contains("<artifactId>shared</artifactId>");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("RESULT: 1 module-local conflict(s)");

        command.run(config, new Properties());
        assertThat(Files.readString(temp.resolve("out").resolve(ReportWriter.CSV))).isEqualTo(csv);
        assertThat(Files.readString(temp.resolve("out").resolve(ReportWriter.MARKDOWN))).isEqualTo(md);
    }
}
