package com.rafaelgpq.depguardian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.rafaelgpq.depguardian.DependencyConflictFinder.OccurrenceRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencyAnalyzerTest {
    @Mock DependencyTreeReader reader;
    private DependencyAnalyzer analyzer;
    private final Path input = Path.of("input.txt");

    @BeforeEach
    void setUp() {
        analyzer = new DependencyAnalyzer(reader);
    }

    @Test
    void reportsSharedChildVersionsAndRetainsTheirParentOrigins() throws IOException {
        var first = occurrence("org.example", "shared", "2.9", "parent-a");
        var second = occurrence("org.example", "shared", "2.10", "parent-b");
        when(reader.read(input)).thenReturn(List.of(first, second, first));

        assertThat(analyzer.analyze(input)).singleElement().satisfies(conflict -> {
            assertThat(conflict.key().toString()).isEqualTo("org.example:shared");
            assertThat(conflict.versions()).containsExactly("2.9", "2.10");
            assertThat(conflict.suggested()).isEqualTo("2.10");
            assertThat(conflict.occs()).hasSize(3);
            assertThat(conflict.occs().get(0).parentPath).containsExactly("root:app:pom:1", "parent-a");
            assertThat(conflict.occs().get(1).parentPath).containsExactly("root:app:pom:1", "parent-b");
        });
        verify(reader).read(input);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void doesNotReportIdenticalVersionsFromDifferentParents() throws IOException {
        when(reader.read(input)).thenReturn(List.of(
                occurrence("org.example", "shared", "1.0", "parent-a"),
                occurrence("org.example", "shared", "1.0", "parent-b")));
        assertThat(analyzer.analyze(input)).isEmpty();
    }

    @Test
    void doesNotCombineSameArtifactNameFromDifferentGroups() throws IOException {
        when(reader.read(input)).thenReturn(List.of(
                occurrence("org.first", "shared", "1.0", "parent-a"),
                occurrence("org.second", "shared", "2.0", "parent-b")));
        assertThat(analyzer.analyze(input)).isEmpty();
    }

    @Test
    void excludesNonConflictingChildrenFromMixedInput() throws IOException {
        when(reader.read(input)).thenReturn(List.of(
                occurrence("org.example", "shared", "1.0", "parent-a"),
                occurrence("org.example", "shared", "2.0", "parent-b"),
                occurrence("org.example", "other", "3.0", "parent-a")));
        assertThat(analyzer.analyze(input)).extracting(c -> c.key().a).containsExactly("shared");
    }

    @Test
    void handlesEmptyDependencyTree() throws IOException {
        when(reader.read(input)).thenReturn(List.of());
        assertThat(analyzer.analyze(input)).isEmpty();
    }

    @Test
    void propagatesReaderFailureInsteadOfReportingNoConflicts() throws IOException {
        IOException failure = new IOException("Unable to read dependency tree");
        when(reader.read(input)).thenThrow(failure);
        assertThatThrownBy(() -> analyzer.analyze(input)).isSameAs(failure);
    }

    private OccurrenceRecord occurrence(String group, String artifact, String version, String parent) {
        return new OccurrenceRecord("example:app", group, artifact, version,
                List.of("root:app:pom:1", parent));
    }
}
