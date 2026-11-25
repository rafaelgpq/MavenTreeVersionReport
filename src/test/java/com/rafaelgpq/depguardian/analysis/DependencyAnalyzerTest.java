package com.rafaelgpq.depguardian.analysis;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;

import static com.rafaelgpq.depguardian.support.TestTrees.analyze;
import static com.rafaelgpq.depguardian.support.TestTrees.lines;
import static org.assertj.core.api.Assertions.assertThat;

/** Classification rules: module-local conflicts, sharing, same-parent variation, identity (type/classifier), omitted and managed provenance. */
class DependencyAnalyzerTest {

    private static final String ROOT = "org.example:app:pom:1.0";

    @Test
    void differentParentsAndVersionsIsOneModuleLocalConflict() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)"));
        assertThat(r.outcome()).isEqualTo(AnalysisResult.Outcome.CONFLICTS_FOUND);
        ChildFinding f = r.modules().get(0).conflicts().get(0);
        assertThat(r.conflictCount()).isEqualTo(1);
        assertThat(f.identity().toString()).isEqualTo("org.example:s:jar");
        assertThat(f.versions()).containsExactly("1.0", "2.0");
        assertThat(f.conflicting()).extracting(o -> o.parent().toString())
                .containsExactly("org.example:a:jar:1.0", "org.example:b:jar:1.0");
        assertThat(f.conflicting()).extracting(Occurrence::fullPathText).containsExactly(
                "org.example:app:pom:1.0 > org.example:a:jar:1.0 > org.example:s:jar:1.0",
                "org.example:app:pom:1.0 > org.example:b:jar:1.0 > org.example:s:jar:2.0");
        assertThat(f.selectedVersions()).containsExactly("1.0");
        assertThat(f.candidateVersion()).isEqualTo("2.0");
    }

    @Test
    void sameVersionUnderDifferentParentsIsSharedNotConflict() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- (org.example:s:jar:1.0:compile - omitted for duplicate)"));
        assertThat(r.outcome()).isEqualTo(AnalysisResult.Outcome.SHARED_CHILDREN_ONLY);
        assertThat(r.conflictCount()).isZero();
        assertThat(r.sharedChildCount()).isEqualTo(1);
    }

    @Test
    void sameParentCoordinatesViaTwoPathsIsOneParent() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:x:jar:1.0:compile",
                "|  \\- org.example:a:jar:1.0:compile",
                "|     \\- org.example:s:jar:1.0:compile",
                "\\- org.example:y:jar:1.0:compile",
                "   \\- org.example:a:jar:1.0:compile",
                "      \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)"));
        assertThat(r.conflictCount()).isZero();
        assertThat(r.modules().get(0).sharedChildren()).extracting(f -> f.identity().artifactId())
                .containsExactly("a");
        ChildFinding f = r.modules().get(0).sameParentVariations().get(0);
        assertThat(f.identity().artifactId()).isEqualTo("s");
        assertThat(f.occurrences()).extracting(Occurrence::fullPathText).containsExactly(
                "org.example:app:pom:1.0 > org.example:x:jar:1.0 > org.example:a:jar:1.0 > org.example:s:jar:1.0",
                "org.example:app:pom:1.0 > org.example:y:jar:1.0 > org.example:a:jar:1.0 > org.example:s:jar:2.0");
    }

    @Test
    void samePathsRepeatedAreSameParentAndSameVersionNoFinding() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:x:jar:1.0:compile",
                "|  \\- org.example:a:jar:1.0:compile",
                "|     \\- org.example:s:jar:1.0:compile",
                "\\- org.example:y:jar:1.0:compile",
                "   \\- org.example:a:jar:1.0:compile",
                "      \\- (org.example:s:jar:1.0:compile - omitted for duplicate)"));
        assertThat(r.modules().get(0).findings()).extracting(f -> f.identity().artifactId()).containsExactly("a");
        assertThat(r.modules().get(0).findings().get(0).kind()).isEqualTo(ChildFinding.Kind.SHARED_SAME_VERSION);
        assertThat(r.outcome()).isEqualTo(AnalysisResult.Outcome.SHARED_CHILDREN_ONLY);
    }

    @Test
    void differentVersionsUnderOneParentAreNotPrimaryConflicts() {
        AnalysisResult r = analyze(lines(ROOT,
                "\\- org.example:a:jar:1.0:compile",
                "   +- org.example:s:jar:1.0:compile",
                "   \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)"));
        assertThat(r.conflictCount()).isZero();
        assertThat(r.sameParentVariationCount()).isEqualTo(1);
        assertThat(r.modules().get(0).sameParentVariations().get(0).versions()).containsExactly("1.0", "2.0");
    }

    @Test
    void differentParentVersionsCountAsDifferentParents() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:a:jar:2.0:compile",
                "   \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)"));
        assertThat(r.conflictCount()).isEqualTo(1);
    }

    @Test
    void parentWithTwoVersionsPlusSecondParentIsConflictOnlyForQualifyingPairs() {
        AnalysisResult conflict = analyze(lines(ROOT,
                "+- org.example:p1:jar:1.0:compile",
                "|  +- org.example:s:jar:1.0:compile",
                "|  \\- (org.example:s:jar:2.0:compile - omitted for conflict with 1.0)",
                "\\- org.example:p2:jar:1.0:compile",
                "   \\- (org.example:s:jar:1.0:compile - omitted for duplicate)"));
        assertThat(conflict.conflictCount()).isEqualTo(1);
        // (p1,1) pairs only with same-version or same-parent occurrences, so it is evidence but not conflicting
        assertThat(conflict.modules().get(0).conflicts().get(0).occurrences()).hasSize(3);
        assertThat(conflict.modules().get(0).conflicts().get(0).conflicting()).extracting(Occurrence::version)
                .containsExactly("1.0", "2.0");
    }

    @Test
    void sameArtifactIdInDifferentGroupsAreSeparateIdentities() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.one:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.two:s:jar:2.0:compile"));
        assertThat(r.modules().get(0).findings()).isEmpty();
    }

    @Test
    void differentTypesAndClassifiersAreSeparateIdentities() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  +- org.example:s:jar:1.0:compile",
                "|  +- org.example:s:jar:native:1.0:compile",
                "|  \\- org.example:s:test-jar:tests:1.0:test",
                "\\- org.example:b:jar:1.0:compile",
                "   +- org.example:s:jar:2.0:compile",
                "   +- org.example:s:jar:native:1.0:compile",
                "   \\- org.example:s:test-jar:tests:1.0:test"));
        ModuleAnalysis m = r.modules().get(0);
        assertThat(m.conflicts()).extracting(f -> f.identity().toString()).containsExactly("org.example:s:jar");
        assertThat(m.sharedChildren()).extracting(f -> f.identity().toString())
                .containsExactlyInAnyOrder("org.example:s:jar:native", "org.example:s:test-jar:tests");
    }

    @Test
    void independentModulesAreNotMerged() {
        AnalysisResult r = analyze(lines(
                "org.example:one:pom:1.0",
                "\\- org.example:a:jar:1.0:compile",
                "   \\- org.example:s:jar:1.0:compile",
                "org.example:two:pom:1.0",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.example:s:jar:2.0:compile"));
        assertThat(r.modules()).hasSize(2);
        assertThat(r.conflictCount()).isZero();
        assertThat(r.sharedChildCount()).isZero();
    }

    @Test
    void whitespaceIsNormalizedButVersionStringsAreExact() {
        AnalysisResult r = analyze(lines(ROOT,
                "+- org.example:a:jar:1.0:compile",
                "|  \\- org.example:s:jar:1.0:compile",
                "\\- org.example:b:jar:1.0:compile",
                "   \\- org.example:s:jar:1.0.0:compile  "));
        assertThat(r.conflictCount()).isEqualTo(1);
        assertThat(r.modules().get(0).conflicts().get(0).versions()).containsExactly("1.0", "1.0.0");
    }

    @Test
    void repeatedAnalysisIsDeterministic() {
        String text = lines(ROOT,
                "+- org.example:b:jar:1.0:compile",
                "|  \\- org.example:s:jar:2.0:compile",
                "\\- org.example:a:jar:1.0:compile",
                "   \\- org.example:s:jar:1.0:compile");
        AnalysisResult first = analyze(text);
        AnalysisResult second = analyze(text);
        assertThat(first).isEqualTo(second);
        assertThat(first.modules().get(0).conflicts().get(0).occurrences()).extracting(Occurrence::version)
                .containsExactly("1.0", "2.0");
    }
}
