package com.rafaelgpq.depguardian.model;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Maven-compatible version ordering and deterministic tie-breaking. */
class VersionUtilsTest {
    @ParameterizedTest
    @CsvSource({
            "2.9,2.10,2.10",
            "1.9,2.0,2.0",
            "1.0-rc1,1.0,1.0",
            "1.0-SNAPSHOT,1.0,1.0",
            "1.0-beta,1.0-rc1,1.0-rc1",
            "10.0,9.99,10.0"
    })
    void usesMavenVersionOrdering(String first, String second, String expected) {
        assertThat(VersionUtils.pickHighestSeen(List.of(first, second))).isEqualTo(expected);
        assertThat(VersionUtils.pickHighestSeen(List.of(second, first))).isEqualTo(expected);
    }

    @Test
    void returnsEmptyStringForAbsentVersions() {
        assertThat(VersionUtils.pickHighestSeen(null)).isEmpty();
        assertThat(VersionUtils.pickHighestSeen(List.of())).isEmpty();
    }

    @Test
    void ignoresNullEntriesWhenSelectingHighest() {
        assertThat(VersionUtils.pickHighestSeen(Arrays.asList(null, "1.0", "2.0", null)))
                .isEqualTo("2.0");
    }

    @ParameterizedTest
    @CsvSource({"17.2.1,17", "2-SNAPSHOT,2", "3,3", "invalid,-1", "'',-1"})
    void extractsMajorOrReturnsSentinelForInvalidInput(String version, int expected) {
        assertThat(VersionUtils.major(version)).isEqualTo(expected);
    }

    @Test
    void handlesNullMajorAndUsesExactVersionStringEquality() {
        assertThat(VersionUtils.major(null)).isEqualTo(-1);
        assertThat(VersionUtils.equalsVersion(null, null)).isTrue();
        assertThat(VersionUtils.equalsVersion("1.0", "1.0")).isTrue();
        assertThat(VersionUtils.equalsVersion("1.0", "1.0.0")).isFalse();
        assertThat(VersionUtils.equalsVersion("1.0", null)).isFalse();
    }
}
