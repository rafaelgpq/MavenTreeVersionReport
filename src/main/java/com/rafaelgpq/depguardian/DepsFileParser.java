// TRANSCRIPTION NOTE: the package and imports below are reconstructed.
// The screenshots start within the class Javadoc; its opening text is not shown.
package com.rafaelgpq.depguardian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * [TRANSCRIPTION NOTE: opening Javadoc text not shown.]
 * It builds a parent stack by computing tree depth from leading bars ('|') on each line.
 * - Module header lines: "--< groupId:artifactId >--"
 * - Root coordinate lines: "groupId:artifactId:type:version"
 * - Child lines: "+- groupId:artifactId:type:version[:scope]" or "-- groupId:artifactId:type:version[:scope]"
 */
public final class DepsFileParser {

    private DepsFileParser() {}

    public static List<DependencyConflictFinder.OccurrenceRecord> parseTreeFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);

        List<DependencyConflictFinder.OccurrenceRecord> out = new ArrayList<>();
        String currentModule = null;
        Deque<String> parentStack = new ArrayDeque<>();

        for (String raw : lines) {
            String s = sanitize(raw);
            if (s.isEmpty()) continue;

            // Module header: --< group:artifact >--
            if (isModuleHeader(s)) {
                String[] ga = extractModuleGA(s);
                currentModule = (ga != null && ga.length == 2) ? (ga[0] + ":" + ga[1]) : "(unknown)";
                parentStack.clear();
                continue;
            }

            // Root coordinate line (no tree markers)
            if (looksLikeCoordinateWithoutMarkers(s)) {
                String[] parts = s.split(":");
                if (parts.length >= 4) {
                    // Push root coordinate: group:artifact:type:version
                    parentStack.clear();
                    parentStack.push(parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3]);
                }
                // We do not record the root as an occurrence; only children matter for conflicts
                continue;
            }

            // Child dependency line
            ChildLine child = parseChildLine(raw);
            if (child == null) continue;

            // Adjust parent stack to match child depth (bars count + root level)
            // Depth bars: number of "|" before the "+- " or "-- " marker
            int targetDepth = child.bars + 1; // root level is depth 1
            while (parentStack.size() > targetDepth) parentStack.pop();
            // The top of the stack is the parent for this child (if any)
            List<String> path = new ArrayList<>(parentStack); // root -> ... current parent

            // Record occurrence for this child GA
            String g = child.groupId;
            String a = child.artifactId;
            String v = child.version;
            out.add(new DependencyConflictFinder.OccurrenceRecord(
                    currentModule != null ? currentModule : "(unknown)", g, a, v, path
            ));

            // Push the child as a potential parent for deeper nodes
            if (child.type != null && child.version != null) {
                parentStack.push(child.groupId + ":" + child.artifactId + ":" + child.type + ":" + child.version);
            }
        }
        return out;
    }

    // Simple child line model
    private static final class ChildLine {
        int bars;           // number of '|' before marker
        String groupId;
        String artifactId;
        String type;        // jar, pom, etc.
        String version;
        String scope;       // may be null
    }

    // Parse child line from raw (before sanitize), computing bars and GA:type:version[:scope]
    private static ChildLine parseChildLine(String raw) {
        if (raw == null) return null;
        int idx = markerIndex(raw);
        if (idx < 0) return null;

        // Count bars before marker
        int bars = 0;
        for (int i = 0; i < idx; i++) {
            if (raw.charAt(i) == '|') bars++;
        }

        // Extract coordinate part after marker
        String coord = raw.substring(idx + 3).trim(); // skip "+- " or "-- " (both length 3)
        String[] parts = coord.split(":");
        if (parts.length < 4) return null;

        ChildLine c = new ChildLine();
        c.bars = bars;
        c.groupId = parts[0];
        c.artifactId = parts[1];
        c.type = parts[2];
        c.version = parts[3];
        c.scope = (parts.length >= 5) ? parts[4] : null;

        // Optional: skip test/provided scope if you only care about runtime
        if (c.scope != null) {
            String scope = c.scope.toLowerCase(Locale.ROOT);
            if ("test".equals(scope) || "provided".equals(scope)) {
                return null;
            }
        }
        return c;
    }

    // Find marker "+- " or "-- " index; returns the start position or -1 if absent
    private static int markerIndex(String raw) {
        if (raw == null) return -1;
        int idxPlus = raw.indexOf("+- ");
        int idxMinus = raw.indexOf("-- ");
        if (idxPlus >= 0 && idxMinus >= 0) return Math.min(idxPlus, idxMinus);
        if (idxPlus >= 0) return idxPlus;
        return idxMinus;
    }

    // Root coordinate looks like "group:artifact:type:version" with no tree markers at start
    private static boolean looksLikeCoordinateWithoutMarkers(String s) {
        if (s.startsWith("+- ") || s.startsWith("-- ") || s.startsWith("|")) return false;
        int colons = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') colons++;
        }
        return colons >= 3;
    }

    // Remove Maven log prefixes and leading bars/spaces (no regex)
    private static String sanitize(String line) {
        if (line == null) return "";
        String s = line;

        if (s.startsWith("[INFO] ")) s = s.substring(7);
        else if (s.startsWith("[DEBUG] ")) s = s.substring(8);
        else if (s.startsWith("[WARNING] ")) s = s.substring(10);

        // Trim leading bars/spaces used by dependency:tree formatting
        while (!s.isEmpty()) {
            char c = s.charAt(0);
            if (c == '|' || c == ' ') s = s.substring(1);
            else break;
        }
        return s.trim();
    }

    private static boolean isModuleHeader(String line) {
        return line.startsWith("--<") && line.endsWith(">--");
    }

    private static String[] extractModuleGA(String header) {
        int start = header.indexOf("--<");
        int end = header.lastIndexOf(">--");
        if (start < 0 || end < 0 || end <= start + 3) return null;
        String inside = header.substring(start + 3, end).trim();
        while (inside.startsWith(" ")) inside = inside.substring(1);
        while (inside.endsWith(" ")) inside = inside.substring(0, inside.length() - 1);
        String[] parts = inside.split(":");
        if (parts.length != 2) return null;
        return parts;
    }
}
