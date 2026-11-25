package com.rafaelgpq.depguardian;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Version helpers. Uses Maven's DefaultArtifactVersion to compare versions robustly
 * (handles qualifiers like rc, beta, snapshot, etc.).
 */
public final class VersionUtils {

    private VersionUtils() {}

    public static String pickHighestSeen(List<String> versions) {
        if (versions == null || versions.isEmpty()) return "";
        Comparator<String> cmp
                = (a, b) -> new DefaultArtifactVersion(a)
                .compareTo(new DefaultArtifactVersion(b));
        String best = null;
        for (String v : versions) {
            if (v == null) continue;
            if (best == null || cmp.compare(v, best) > 0) best = v;
        }
        return best != null ? best : versions.get(0);
    }

    public static int major(String v) {
        if (v == null) return -1;
        String[] parts = v.split("[.-]");
        try { return Integer.parseInt(parts[0]); } catch (Exception e) { return -1; }
    }

    public static boolean equalsVersion(String a, String b) {
        return Objects.equals(a, b);
    }
}
