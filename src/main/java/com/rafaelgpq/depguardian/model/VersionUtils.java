package com.rafaelgpq.depguardian.model;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Collection;
import java.util.Objects;

/**
 * Version helpers. Ordering uses Maven's {@link DefaultArtifactVersion} (handles qualifiers such as rc, beta and
 * snapshot) with a string tie-breaker, so different version strings that Maven compares as equal (for example
 * "1.0" and "1.0.0") keep a stable, distinct order and are never merged.
 */
public final class VersionUtils {

    private VersionUtils() {}

    /** Maven ordering, then plain string order as a stable tie-breaker. */
    public static int compare(String a, String b) {
        int result = new DefaultArtifactVersion(a).compareTo(new DefaultArtifactVersion(b));
        return result != 0 ? result : a.compareTo(b);
    }

    /** Highest observed version by {@link #compare}; informational only, not Maven's selection. */
    public static String pickHighestSeen(Collection<String> versions) {
        if (versions == null || versions.isEmpty()) return "";
        String best = null;
        for (String v : versions) {
            if (v == null) continue;
            if (best == null || compare(v, best) > 0) best = v;
        }
        return best != null ? best : "";
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
