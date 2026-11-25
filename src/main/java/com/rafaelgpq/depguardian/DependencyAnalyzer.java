package com.rafaelgpq.depguardian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rafaelgpq.depguardian.DependencyConflictFinder.ArtifactKey;
import com.rafaelgpq.depguardian.DependencyConflictFinder.Conflict;
import com.rafaelgpq.depguardian.DependencyConflictFinder.Occurrence;
import com.rafaelgpq.depguardian.DependencyConflictFinder.OccurrenceRecord;

/** Groups parsed child dependencies and identifies multiple version strings. */
public final class DependencyAnalyzer {
    private final DependencyTreeReader reader;

    public DependencyAnalyzer() {
        this(DepsFileParser::parseTreeFile);
    }

    public DependencyAnalyzer(DependencyTreeReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public List<Conflict> analyze(Path input) throws IOException {
        List<OccurrenceRecord> parsed = reader.read(input);
        Map<ArtifactKey, List<Occurrence>> byGA = new HashMap<>();
        for (OccurrenceRecord r : parsed) {
            ArtifactKey key = new ArtifactKey(r.groupId, r.artifactId);
            Occurrence occ = new Occurrence(r.version, r.module, r.parentPath);
            byGA.computeIfAbsent(key, k -> new ArrayList<>()).add(occ);
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<ArtifactKey, List<Occurrence>> entry : byGA.entrySet()) {
            List<String> distinct = distinctVersions(entry.getValue());
            if (distinct.size() > 1) {
                Conflict conflict = new Conflict(entry.getKey(), distinct, entry.getValue());
                conflict.suggested(VersionUtils.pickHighestSeen(conflict.versions()));
                conflicts.add(conflict);
            }
        }
        return conflicts;
    }

    private static List<String> distinctVersions(List<Occurrence> occurrences) {
        List<String> versions = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.version != null && !versions.contains(occurrence.version)) {
                versions.add(occurrence.version);
            }
        }
        return versions;
    }
}
