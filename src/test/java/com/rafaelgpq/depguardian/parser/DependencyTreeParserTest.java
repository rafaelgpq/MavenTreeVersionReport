package com.rafaelgpq.depguardian.parser;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static com.rafaelgpq.depguardian.support.TestTrees.parse;
import static com.rafaelgpq.depguardian.support.TestTrees.scopes;
import static org.assertj.core.api.Assertions.assertThat;

/** Tree/log parsing: markers, depth, ancestry, scope filtering, omitted nodes, headers and diagnostics. */
class DependencyTreeParserTest {

    private static final String ROOT = "org.example:app:pom:1.0";

    @Test
    void capturesBothPlusAndBackslashBranches() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile"));
        assertThat(r.isComplete()).isTrue();
        assertThat(r.modules()).hasSize(1);
        assertThat(r.modules().get(0).occurrences()).extracting(o -> o.child().identity().artifactId())
                .containsExactly("a", "b");
        assertThat(r.modules().get(0).occurrences()).allSatisfy(o ->
                assertThat(o.parent()).isEqualTo(Coordinate.of("org.example", "app", "pom", "", "1.0")));
    }

    @Test
    void nestedLastSiblingsUseSpaceIndentationForDepth() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.example:c:jar:1.0:compile",
                "      \\- org.example:d:jar:1.0:compile",
                "         +- org.example:e:jar:1.0:compile",
                "         \\- org.example:f:jar:1.0:compile"));
        assertThat(r.isComplete()).isTrue();
        Occurrence f = r.modules().get(0).occurrences().get(5);
        assertThat(f.child().toString()).isEqualTo("org.example:f:jar:1.0");
        assertThat(f.parent().toString()).isEqualTo("org.example:d:jar:1.0");
        assertThat(f.fullPath()).extracting(c -> c.identity().artifactId())
                .containsExactly("app", "b", "c", "d", "f");
        assertThat(f.parentPath()).extracting(c -> c.identity().artifactId())
                .containsExactly("app", "b", "c", "d");
    }

    @Test
    void mixedBarAndSpaceIndentation() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  +- org.example:x:jar:1.0:compile",
                "|  |  \\- org.example:y:jar:1.0:compile",
                "|  \\- org.example:z:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile"));
        List<Occurrence> o = r.modules().get(0).occurrences();
        assertThat(o.get(2).parent().identity().artifactId()).isEqualTo("x");
        assertThat(o.get(3).parent().identity().artifactId()).isEqualTo("a");
        assertThat(o.get(4).parent().identity().artifactId()).isEqualTo("app");
    }

    @Test
    void classifierIsNotParsedAsVersion() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:shared:jar:native:2.0:compile",
                "\\- org.example:shared:test-jar:tests:3.0:test"));
        List<Occurrence> o = r.modules().get(0).occurrences();
        assertThat(o.get(0).version()).isEqualTo("2.0");
        assertThat(o.get(0).identity().classifier()).isEqualTo("native");
        assertThat(o.get(1).version()).isEqualTo("3.0");
        assertThat(o.get(1).identity().type()).isEqualTo("test-jar");
        assertThat(o.get(1).scope()).isEqualTo("test");
    }

    @Test
    void omittedForConflictKeepsRequestedAndSelectedVersions() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)"));
        Occurrence omitted = r.modules().get(0).occurrences().get(3);
        assertThat(omitted.status()).isEqualTo(NodeStatus.OMITTED_FOR_CONFLICT);
        assertThat(omitted.version()).isEqualTo("2.0");
        assertThat(omitted.selectedVersion()).isEqualTo("1.0");
        assertThat(omitted.identity().artifactId()).isEqualTo("s");
        assertThat(omitted.scope()).isEqualTo("compile");
        assertThat(r.modules().get(0).occurrences().get(1).status()).isEqualTo(NodeStatus.SELECTED);
    }

    @Test
    void duplicateCycleAndManagedAnnotations() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  +- org.example:s:jar:2.0:compile (version managed from 1.0)",
                "|  +- (org.example:d:jar:1.0:compile - omitted for duplicate)",
                "|  \\- (org.example:app:jar:1.0:compile - omitted for cycle)",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- (org.example:s:jar:2.0:compile - version managed from 2.0; omitted for duplicate)"));
        assertThat(r.isComplete()).isTrue();
        List<Occurrence> o = r.modules().get(0).occurrences();
        assertThat(o.get(1).status()).isEqualTo(NodeStatus.SELECTED);
        assertThat(o.get(1).version()).isEqualTo("2.0");
        assertThat(o.get(1).managedFrom()).isEqualTo("1.0");
        assertThat(o.get(2).status()).isEqualTo(NodeStatus.OMITTED_FOR_DUPLICATE);
        assertThat(o.get(3).status()).isEqualTo(NodeStatus.OMITTED_FOR_CYCLE);
        assertThat(o.get(5).version()).isEqualTo("2.0");
        assertThat(o.get(5).status()).isEqualTo(NodeStatus.OMITTED_FOR_DUPLICATE);
        assertThat(o.get(5).managedFrom()).isEqualTo("2.0");
    }

    @Test
    void managedFromVersionIsNotAnotherOccurrence() {
        ParseResult r = parse(lines(ROOT,
                "\\- org.example:a:jar:1.0:compile",
                "   \\- org.example:s:jar:2.0:compile (version managed from 1.0)"));
        assertThat(r.modules().get(0).occurrences()).hasSize(2);
        assertThat(r.modules().get(0).occurrences()).extracting(Occurrence::version).containsExactly("1.0", "2.0");
    }

    @Test
    void childrenOfOmittedNodesAreRejectedNotInvented() {
        ParseResult r = parse(lines(ROOT,
                "\\- (org.example:a:jar:1.0:compile - omitted for duplicate)",
                "   \\- org.example:x:jar:1.0:compile"));
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("OMITTED_PARENT");
        assertThat(r.modules().get(0).occurrences()).hasSize(1);
    }

    @Test
    void rootOnlyTreeIsValidWithNoOccurrences() {
        ParseResult r = parse(lines(ROOT));
        assertThat(r.isComplete()).isTrue();
        assertThat(r.modules()).hasSize(1);
        assertThat(r.modules().get(0).occurrences()).isEmpty();
        assertThat(r.diagnostics()).isEmpty();
    }

    @Test
    void emptyInputIsReported() {
        assertThat(parse("").errors()).extracting(Diagnostic::code).containsExactly("EMPTY_INPUT");
        assertThat(parse("\n  \n").errors()).extracting(Diagnostic::code).containsExactly("EMPTY_INPUT");
    }

    @Test
    void unrelatedTextIsReportedAsNoTree() {
        ParseResult r = parse(lines("hello world", "this is not a tree"));
        assertThat(r.modules()).isEmpty();
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("NO_TREE_FOUND");
        assertThat(r.statistics().ignoredLines()).isEqualTo(2);
    }

    @Test
    void orphanedNodeAndDepthJumpAreImpossibleHierarchy() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "      \\- org.example:deep:jar:1.0:compile"));
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("IMPOSSIBLE_HIERARCHY");
        assertThat(r.errors().get(0).line()).isEqualTo(3);
        assertThat(r.errors().get(0).source()).isEqualTo("test-input");
    }

    @Test
    void nodeBeforeAnyRootIsMissingModule() {
        ParseResult r = parse(lines("+- org.example:a:jar:1.0:compile"));
        assertThat(r.errors()).extracting(Diagnostic::code).contains("MISSING_MODULE");
    }

    @Test
    void malformedLinesAreActionableErrors() {
        ParseResult legacy = parse(lines(ROOT, "-- org.example:a:jar:1.0:compile"));
        assertThat(legacy.errors()).extracting(Diagnostic::code).containsExactly("MALFORMED_TREE_LINE");
        ParseResult badIndent = parse(lines(ROOT, " +- org.example:a:jar:1.0:compile"));
        assertThat(badIndent.errors()).extracting(Diagnostic::code).containsExactly("MALFORMED_TREE_LINE");
        ParseResult fields = parse(lines(ROOT, "+- org.example:a:jar"));
        assertThat(fields.errors()).extracting(Diagnostic::code).containsExactly("MALFORMED_NODE");
        ParseResult unicode = parse(lines(ROOT, "\u251C\u2500 org.example:a:jar:1.0:compile"));
        assertThat(unicode.errors()).extracting(Diagnostic::code).containsExactly("UNSUPPORTED_TOKENS");
    }

    @Test
    void ambiguousFiveFieldCoordinateIsDiagnosedNotGuessed() {
        ParseResult r = parse(lines(ROOT, "+- org.example:a:jar:1.0:weird"));
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("AMBIGUOUS_COORDINATE");
    }

    @Test
    void errorInParentDoesNotCascadeToChildren() {
        ParseResult r = parse(lines(ROOT,
                "+- org.example:broken",
                "|  \\- org.example:child:jar:1.0:compile",
                "\\- org.example:ok:jar:1.0:compile"));
        assertThat(r.errors()).hasSize(1);
        assertThat(r.modules().get(0).occurrences()).extracting(o -> o.identity().artifactId())
                .containsExactly("ok");
    }

    @Test
    void scopeFilterKeepsStructureAndDropsExcludedSubtrees() {
        String text = lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:t:jar:1.0:compile",
                "+- org.example:test-lib:jar:1.0:test",
                "|  \\- org.example:hidden:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.example:t:jar:2.0:compile");
        ParseResult r = parse(text, scopes("compile"));
        assertThat(r.isComplete()).isTrue();
        List<Occurrence> o = r.modules().get(0).occurrences();
        assertThat(o).extracting(x -> x.identity().artifactId()).containsExactly("a", "t", "b", "t");
        assertThat(o.get(3).parent().identity().artifactId()).isEqualTo("b");
        assertThat(r.statistics().scopeFilteredOccurrences()).isEqualTo(2);
        assertThat(parse(text).modules().get(0).occurrences()).hasSize(6);
    }

    @Test
    void crlfLineEndingsAndBomAreAccepted() {
        ParseResult r = parse("\uFEFF" + ROOT + "\r\n+- org.example:a:jar:1.0:compile\r\n"
                + "\\- org.example:b:jar:1.0:compile\r\n");
        assertThat(r.isComplete()).isTrue();
        assertThat(r.modules().get(0).occurrences()).hasSize(2);
    }

    @Test
    void ansiColorEscapesAndLogPrefixesAreStripped() {
        ParseResult r = parse(lines(
                "\u001B[1;34mINFO\u001B[m ignored",
                "[\u001B[1;34mINFO\u001B[m] \u001B[1m--- dependency:3.11.0:tree (default-cli) @ app ---\u001B[m",
                "[\u001B[1;34mINFO\u001B[m] " + ROOT,
                "[\u001B[1;34mINFO\u001B[m] \\- org.example:a:jar:1.0:compile"));
        assertThat(r.errors()).isEmpty();
        assertThat(r.modules().get(0).occurrences()).hasSize(1);
    }

    @Test
    void decoratedHeaderMustAgreeWithRoot() {
        String header = "[INFO] ---------------------< org.example:other >----------------------";
        ParseResult mismatch = parse(lines(header, "[INFO] " + ROOT));
        assertThat(mismatch.errors()).extracting(Diagnostic::code).containsExactly("HEADER_ROOT_MISMATCH");
        ParseResult ok = parse(lines(header.replace("other", "app"), "[INFO] " + ROOT));
        assertThat(ok.diagnostics()).isEmpty();
    }

    @Test
    void mavenErrorLinesMakeInputInvalid() {
        ParseResult r = parse(lines("[INFO] " + ROOT, "[ERROR] Failed to execute goal"));
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("MAVEN_ERROR_LOGGED");
    }

    @Test
    void repeatedRootIsDuplicateModule() {
        ParseResult r = parse(lines(ROOT, ROOT));
        assertThat(r.errors()).extracting(Diagnostic::code).containsExactly("DUPLICATE_MODULE");
    }

    @Test
    void childLineNeverResetsTheRootStack() {
        // A coordinate-looking child with markers must stay a child; a banner is noise.
        ParseResult r = parse(lines("[INFO] --- dependency:3.11.0:tree (default-cli) @ app ---", ROOT,
                "\\- org.example:a:jar:1.0:compile"));
        assertThat(r.modules()).hasSize(1);
        assertThat(r.modules().get(0).occurrences()).hasSize(1);
    }
}
