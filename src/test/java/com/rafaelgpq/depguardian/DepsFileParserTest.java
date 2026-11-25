package com.rafaelgpq.depguardian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests supported parser inputs; broader tree-format limitations are documented in README.md. */
class DepsFileParserTest {
    @TempDir Path directory;

    @Test
    void recordsDirectChildAndItsModuleAndRootParent() throws IOException {
        Path input = writeTree("""
                [INFO] --< example:app >--
                [INFO] example:app:pom:1.0
                [INFO] +- org.example:child:jar:2.0:compile
                """);
        assertThat(DepsFileParser.parseTreeFile(input)).singleElement().satisfies(child -> {
            assertThat(child.module).isEqualTo("example:app");
            assertThat(child.groupId).isEqualTo("org.example");
            assertThat(child.artifactId).isEqualTo("child");
            assertThat(child.version).isEqualTo("2.0");
            assertThat(child.parentPath).containsExactly("example:app:pom:1.0");
        });
    }

    @Test
    void excludesTheRootItself() throws IOException {
        assertThat(DepsFileParser.parseTreeFile(writeTree("example:app:pom:1.0\n"))).isEmpty();
    }

    @Test
    void skipsTestAndProvidedScopesButKeepsCompileRuntimeAndUnscoped() throws IOException {
        Path input = writeTree("""
                example:app:pom:1.0
                +- org.example:test-only:jar:1.0:test
                +- org.example:provided-only:jar:1.0:provided
                +- org.example:compile-child:jar:1.0:compile
                +- org.example:runtime-child:jar:1.0:runtime
                +- org.example:unscoped:jar:1.0
                """);
        assertThat(DepsFileParser.parseTreeFile(input)).extracting(c -> c.artifactId)
                .containsExactly("compile-child", "runtime-child", "unscoped");
    }

    @Test
    void resetsModuleAndRootAcrossSupportedModuleHeaders() throws IOException {
        Path input = writeTree("""
                --< example:first >--
                example:first:pom:1
                +- org.example:child:jar:1
                --< example:second >--
                example:second:pom:1
                +- org.example:child:jar:2
                """);
        var parsed = DepsFileParser.parseTreeFile(input);
        assertThat(parsed).extracting(c -> c.module).containsExactly("example:first", "example:second");
        assertThat(parsed.get(1).parentPath).containsExactly("example:second:pom:1");
    }

    @Test
    void ignoresEmptyMalformedAndNonDependencyLines() throws IOException {
        Path input = writeTree("\n[INFO] BUILD SUCCESS\n+- invalid\n");
        assertThat(DepsFileParser.parseTreeFile(input)).isEmpty();
    }

    @Test
    void retainsUnknownModuleWhenNoModuleHeaderExists() throws IOException {
        var children = DepsFileParser.parseTreeFile(writeTree("+- org.example:child:jar:1\n"));
        assertThat(children).singleElement().satisfies(child -> {
            assertThat(child.module).isEqualTo("(unknown)");
            assertThat(child.parentPath).isEmpty();
        });
    }

    @Test
    void propagatesMissingInputFile() {
        assertThatThrownBy(() -> DepsFileParser.parseTreeFile(directory.resolve("missing.txt")))
                .isInstanceOf(NoSuchFileException.class);
    }

    private Path writeTree(String content) throws IOException {
        return Files.writeString(directory.resolve("deps.txt"), content);
    }
}
