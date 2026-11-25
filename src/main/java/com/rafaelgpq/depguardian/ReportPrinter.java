package com.rafaelgpq.depguardian;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Prints Markdown to console and writes CSV + dependencyManagement XML.
 */
public final class ReportPrinter {

    private ReportPrinter() {}

    public static void printMarkdown(List<DependencyConflictFinder.Conflict> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            System.out.println("No dependency version conflicts found.");
            return;
        }
        System.out.println();
        System.out.println(
                "| GroupId | ArtifactId | Versions found | Parents introducing versions | Suggested version |"
        );
        System.out.println(
                "|---------|------------|----------------|-----------------------------|------------------|"
        );

        for (DependencyConflictFinder.Conflict c : conflicts) {
            String groupId = c.key().g;
            String artifactId = c.key().a;
            String versionsFound = String.join(", ", c.versions());

            Map<String, List<DependencyConflictFinder.Occurrence>> byVersion =
                    c.occs().stream().collect(Collectors.groupingBy(o -> o.version, LinkedHashMap::new,
                            Collectors.toList()));

            String parentsCell = joinParentsByVersion(byVersion);
            String suggested = (c.suggested() == null || c.suggested().isBlank()) ? "-" : c.suggested();

            System.out.println("| " + escMd(groupId) + " | " +
                    escMd(artifactId) + " | " +
                    escMd(versionsFound) + " | " +
                    escMd(parentsCell) + " | " +
                    escMd(suggested) + " |");
        }
        System.out.println();
    }

    public static void printCsv(List<DependencyConflictFinder.Conflict> conflicts, Path out) {
        ensureParentDir(out);
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("GroupId,ArtifactId,Version,SuggestedVersion,Modules,ParentsPath");
            w.newLine();

            for (DependencyConflictFinder.Conflict c : conflicts) {
                String g = c.key().g;
                String a = c.key().a;
                String suggested = (c.suggested() == null || c.suggested().isBlank()) ? "" : c.suggested();

                Map<String, List<DependencyConflictFinder.Occurrence>> byVersion =
                        c.occs().stream()
                                .collect(Collectors.groupingBy(o -> o.version,
                                        LinkedHashMap::new, Collectors.toList()));

                for (Map.Entry<String, List<DependencyConflictFinder.Occurrence>> e : byVersion.entrySet()) {
                    String version = e.getKey();
                    List<DependencyConflictFinder.Occurrence> occs = e.getValue();

                    String modules = occs.stream()
                            .map(o -> o.module)
                            .distinct()
                            .collect(Collectors.joining("; "));
                    String paths = occs.stream()
                            .map(x -> ReportPrinter.abbreviatePath(x.parentPath))
                            .distinct()
                            .collect(Collectors.joining(" | "));

                    String row = String.join(",",
                            csv(g), csv(a), csv(version), csv(suggested), csv(modules), csv(paths)
                    );
                    w.write(row);
                    w.newLine();
                }
            }
            w.flush();
            System.out.println("CSV written to: " + out.toAbsolutePath());
        } catch (IOException ioe) {
            System.err.println("Failed to write CSV " + out + ": " + ioe.getMessage());
        }
    }

    public static void printDependencyManagementXml(List<DependencyConflictFinder.Conflict> conflicts,
                                                   Path out) {
        ensureParentDir(out);
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("<dependencyManagement>\n  <dependencies>\n");
            for (DependencyConflictFinder.Conflict c : conflicts) {
                String g = c.key().g;
                String a = c.key().a;
                String v = c.suggested();
                if (v != null && !v.isBlank()) {
                    w.write("    <dependency>\n");
                    w.write("      <groupId>" + g + "</groupId>\n");
                    w.write("      <artifactId>" + a + "</artifactId>\n");
                    w.write("      <version>" + v + "</version>\n");
                    w.write("    </dependency>\n");
                }
            }
            w.write("  </dependencies>\n</dependencyManagement>\n");
            w.flush();
            System.out.println("dependencyManagement snippet written to: " + out.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write dependencyManagement XML: " + e.getMessage());
        }
    }

    private static String joinParentsByVersion(Map<String, List<DependencyConflictFinder.Occurrence>> byVersion) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<DependencyConflictFinder.Occurrence>> e : byVersion.entrySet()) {
            String v = e.getKey();
            String paths = e.getValue().stream()
                    .map(o -> o.module + " " + abbreviatePath(o.parentPath))
                    .distinct()
                    .collect(Collectors.joining("; "));
            parts.add(v + " -> [" + paths + "]");
        }
        return String.join("; ", parts);
    }

    private static void ensureParentDir(Path out) {
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
        } catch (IOException ignored) { }
    }

    // Parent path abbreviator: root -> ... -> leaf
    private static String abbreviatePath(List<String> path) {
        if (path == null || path.isEmpty()) return "";
        List<String> copy = new ArrayList<>(path);
        int max = 4;
        if (copy.size() > max) {
            return copy.get(0) + " -> ... -> " + copy.get(copy.size() - 2) + " -> " + copy.get(copy.size() - 1);
        }
        return String.join(" -> ", copy);
    }

    private static String escMd(String s) { return s == null ? "" : s.replace("|", "\\|"); }

    private static String csv(String s) {
        if (s == null) s = "";
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
