package com.rafaelgpq.depguardian.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Immutable values of the dependency graph (parsed input) and of the analysis built from it. */
public final class DependencyModel {

    /** Scope names the Maven Dependency Plugin prints for resolved dependencies; shared by parser and config. */
    public static final Set<String> KNOWN_SCOPES =
            Set.of("compile", "provided", "runtime", "test", "system", "import");

    private DependencyModel() {}

    /**
     * Child identity: groupId, artifactId, serialized type and classifier. Version and scope are not part of it.
     * An absent classifier is normalized to the empty string.
     */
    public record ArtifactIdentity(String groupId, String artifactId, String type, String classifier)
            implements Comparable<ArtifactIdentity> {

        public ArtifactIdentity {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(type, "type");
            classifier = classifier == null ? "" : classifier;
        }

        @Override
        public int compareTo(ArtifactIdentity other) {
            int result = groupId.compareTo(other.groupId);
            if (result == 0) result = artifactId.compareTo(other.artifactId);
            if (result == 0) result = type.compareTo(other.type);
            if (result == 0) result = classifier.compareTo(other.classifier);
            return result;
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + type + (classifier.isEmpty() ? "" : ":" + classifier);
        }
    }

    /** An identity with an exact, whitespace-trimmed version string. Used for module roots and immediate parents. */
    public record Coordinate(ArtifactIdentity identity, String version) implements Comparable<Coordinate> {

        public Coordinate {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(version, "version");
            version = version.trim();
        }

        public static Coordinate of(String groupId, String artifactId, String type, String classifier,
                                    String version) {
            return new Coordinate(new ArtifactIdentity(groupId, artifactId, type, classifier), version);
        }

        @Override
        public int compareTo(Coordinate other) {
            return Comparator.comparing(Coordinate::identity)
                    .thenComparing(Coordinate::version, VersionUtils::compare)
                    .compare(this, other);
        }

        /** {@code group:artifact:type[:classifier]:version}, as printed by the Maven Dependency Plugin. */
        @Override
        public String toString() {
            return identity.groupId() + ":" + identity.artifactId() + ":" + identity.type()
                    + (identity.classifier().isEmpty() ? "" : ":" + identity.classifier()) + ":" + version;
        }
    }

    /** Whether the Maven resolver kept a node in the resolved tree, and why not if it did not. */
    public enum NodeStatus {
        SELECTED,
        OMITTED_FOR_CONFLICT,
        OMITTED_FOR_DUPLICATE,
        OMITTED_FOR_CYCLE,
        /** An omitted node whose reason is not one of the recognized ones. */
        OMITTED_OTHER;

        public boolean isOmitted() {
            return this != SELECTED;
        }
    }

    /**
     * One child node observed in a module's dependency tree.
     *
     * @param module           the module root whose tree contains the node
     * @param child            observed coordinate (the version Maven printed for this node)
     * @param scope            effective scope as printed, lower case, or {@code null} when the line carried none
     * @param status           selected or omitted, as printed
     * @param selectedVersion  for omitted-for-conflict nodes, the version Maven kept; otherwise {@code null}
     * @param managedFrom      version printed in a "version managed from" annotation; otherwise {@code null}
     * @param annotations      raw annotation/reason text (provenance only)
     * @param parentPath       root-to-immediate-parent coordinates; never empty, first element is the module root
     * @param line             1-based source line
     */
    public record Occurrence(Coordinate module, Coordinate child, String scope, NodeStatus status,
                             String selectedVersion, String managedFrom, List<String> annotations,
                             List<Coordinate> parentPath, int line) {

        public Occurrence {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(status, "status");
            annotations = List.copyOf(annotations);
            parentPath = List.copyOf(parentPath);
            if (parentPath.isEmpty()) throw new IllegalArgumentException("parentPath must start with the module root");
        }

        public ArtifactIdentity identity() {
            return child.identity();
        }

        public String version() {
            return child.version();
        }

        /** Immediate introducing dependency; the module root for a direct child. */
        public Coordinate parent() {
            return parentPath.get(parentPath.size() - 1);
        }

        /** Root-to-child path. */
        public List<Coordinate> fullPath() {
            List<Coordinate> path = new ArrayList<>(parentPath);
            path.add(child);
            return List.copyOf(path);
        }

        /** {@code root > ... > parent > child}; the complete, unabbreviated path used by the reports. */
        public String fullPathText() {
            return fullPath().stream().map(Coordinate::toString).collect(Collectors.joining(" > "));
        }
    }

    /** The tree of one module root and the child occurrences parsed from it. */
    public record ModuleTree(Coordinate root, int line, List<Occurrence> occurrences) {

        public ModuleTree {
            Objects.requireNonNull(root, "root");
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Everything observed for one child identity within one module.
     *
     * @param occurrences every occurrence of the identity in the module (deterministically ordered)
     * @param conflicting occurrences that take part in at least one qualifying pair, i.e. a pair with different
     *                    immediate-parent coordinates and different observed version strings (empty unless
     *                    {@code kind == CONFLICT})
     */
    public record ChildFinding(Kind kind, ArtifactIdentity identity, List<Occurrence> occurrences,
                               List<Occurrence> conflicting) {

        /** Classification of a child identity within one module. */
        public enum Kind {
            /** Two occurrences with different complete parent coordinates and different version strings. */
            CONFLICT,
            /** At least two distinct parents, every observed version string identical. */
            SHARED_SAME_VERSION,
            /** Several versions, but only under one immediate-parent coordinate; not a primary conflict. */
            SAME_PARENT_VARIATION
        }

        public ChildFinding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            occurrences = List.copyOf(occurrences);
            conflicting = List.copyOf(conflicting);
        }

        /** Distinct exact version strings of all occurrences, Maven-ordered. */
        public List<String> versions() {
            return occurrences.stream().map(Occurrence::version).distinct().sorted(VersionUtils::compare).toList();
        }

        /** Distinct complete immediate-parent coordinates of all occurrences. */
        public List<Coordinate> parents() {
            return occurrences.stream().map(Occurrence::parent).distinct().sorted().toList();
        }

        /**
         * Versions Maven actually kept, as evidenced by the input: versions of selected nodes plus the winner
         * version named by omitted-for-conflict annotations.
         */
        public List<String> selectedVersions() {
            TreeSet<String> selected = new TreeSet<>(VersionUtils::compare);
            for (Occurrence occurrence : occurrences) {
                if (occurrence.status() == NodeStatus.SELECTED) selected.add(occurrence.version());
                if (occurrence.selectedVersion() != null) selected.add(occurrence.selectedVersion());
            }
            return List.copyOf(selected);
        }

        /** Highest conflicting version; informational only, not Maven's selection or a verified compatible fix. */
        public String candidateVersion() {
            if (kind != Kind.CONFLICT) return null;
            return VersionUtils.pickHighestSeen(conflicting.stream().map(Occurrence::version).toList());
        }

        /** Deterministic order: version (Maven order), parent, full path, then source line. */
        public static Comparator<Occurrence> occurrenceOrder() {
            return Comparator.comparing(Occurrence::version, VersionUtils::compare)
                    .thenComparing(Occurrence::parent)
                    .thenComparing(o -> o.fullPath().toString())
                    .thenComparingInt(Occurrence::line);
        }
    }

    /** Findings of one module. Modules are analyzed independently and never merged. */
    public record ModuleAnalysis(Coordinate root, int occurrenceCount, List<ChildFinding> findings) {

        public ModuleAnalysis {
            Objects.requireNonNull(root, "root");
            findings = List.copyOf(findings);
        }

        public List<ChildFinding> conflicts() {
            return of(ChildFinding.Kind.CONFLICT);
        }

        public List<ChildFinding> sharedChildren() {
            return of(ChildFinding.Kind.SHARED_SAME_VERSION);
        }

        public List<ChildFinding> sameParentVariations() {
            return of(ChildFinding.Kind.SAME_PARENT_VARIATION);
        }

        private List<ChildFinding> of(ChildFinding.Kind kind) {
            return findings.stream().filter(f -> f.kind() == kind).toList();
        }
    }

    /** Module-local findings for every module of one input. */
    public record AnalysisResult(List<ModuleAnalysis> modules) {

        /** Overall result of a successfully analyzed input. */
        public enum Outcome {
            CONFLICTS_FOUND,
            SHARED_CHILDREN_ONLY,
            NO_QUALIFYING_SHARED_CHILDREN
        }

        public AnalysisResult {
            modules = List.copyOf(modules);
        }

        public int conflictCount() {
            return modules.stream().mapToInt(m -> m.conflicts().size()).sum();
        }

        public int sharedChildCount() {
            return modules.stream().mapToInt(m -> m.sharedChildren().size()).sum();
        }

        public int sameParentVariationCount() {
            return modules.stream().mapToInt(m -> m.sameParentVariations().size()).sum();
        }

        public Outcome outcome() {
            if (conflictCount() > 0) return Outcome.CONFLICTS_FOUND;
            if (sharedChildCount() > 0) return Outcome.SHARED_CHILDREN_ONLY;
            return Outcome.NO_QUALIFYING_SHARED_CHILDREN;
        }
    }
}
