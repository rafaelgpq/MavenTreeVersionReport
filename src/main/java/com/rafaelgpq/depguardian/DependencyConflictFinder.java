package com.rafaelgpq.depguardian;

import java.nio.file.Path;
import java.util.*;

/**
 * Entry point: parses a saved "mvn dependency:tree" file,
 * finds GA conflicts (same G:A with multiple versions),
 * proposes a single suggested version (highest seen), and prints + writes reports.
 */
public class DependencyConflictFinder {

    // Simple key for groupId + artifactId
    public static final class ArtifactKey {
        public final String g;
        public final String a;
        public ArtifactKey(String g, String a) { this.g = g; this.a = a; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArtifactKey that = (ArtifactKey) o;
            return Objects.equals(g, that.g) && Objects.equals(a, that.a);
        }
        @Override public int hashCode() { return Objects.hash(g, a); }
        @Override public String toString() { return g + ":" + a; }
    }

    // One occurrence of a GA version under a module, with parent path (root -> ... -> leaf)
    public static final class Occurrence {
        public final String version;
        public final String module;
        public final List<String> parentPath;
        public Occurrence(String version, String module, List<String> parentPath) {
            this.version = version;
            this.module = module;
            this.parentPath = parentPath;
        }
    }

    // A GA with multiple versions found, including all occurrences and a suggested convergence
    public static final class Conflict {
        private final ArtifactKey key;
        private final List<String> versions;    // distinct versions found
        private final List<Occurrence> occs;    // all occurrences
        private String suggested;              // chosen version to converge on

        public Conflict(ArtifactKey key, List<String> versions, List<Occurrence> occs) {
            this.key = key;
            this.versions = versions;
            this.occs = occs;
        }
        public ArtifactKey key() { return key; }
        public List<String> versions() { return versions; }
        public List<Occurrence> occs() { return occs; }
        public String suggested() { return suggested; }
        public void suggested(String v) { this.suggested = v; }
    }

    public static void main(String[] args) throws Exception {
        // External configuration can be edited without rebuilding the application.
        Path configFile = Path.of(System.getProperty("app.config", "application.properties"));
        ApplicationConfig config = ApplicationConfig.load(configFile, System.getProperties());
        List<Conflict> conflicts = new DependencyAnalyzer().analyze(config.depsFile());

        // 5) Print and write reports
        ReportPrinter.printMarkdown(conflicts);
        ReportPrinter.printCsv(conflicts,
                Path.of("target/dep-conflicts-from-file.csv"));
        ReportPrinter.printDependencyManagementXml(conflicts,
                Path.of("target/dependencyManagement-from-file.xml"));

        // 6) Short banner
        System.out.println("=== Summary ===");
        System.out.println("Conflicts found: " + conflicts.size());
        System.out.println("CSV: target/dep-conflicts-from-file.csv");
        System.out.println("dependencyManagement XML: target/dependencyManagement-from-file.xml");
    }

    // Helper type used internally by the parser (flattened record)
    public static final class OccurrenceRecord {
        public final String module;        // module GA or module name
        public final String groupId;
        public final String artifactId;
        public final String version;
        public final List<String> parentPath; // list of GA:type:version along the path

        public OccurrenceRecord(String module, String groupId, String artifactId,
                                String version, List<String> parentPath) {
            this.module = module;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.parentPath = parentPath;
        }
    }
}
