package com.rafaelgpq.depguardian.report;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.input.DependencyTreeReader;

import com.rafaelgpq.depguardian.model.DependencyModel.AnalysisResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes report files into a directory. Each file is written to a temporary sibling and then moved over its
 * destination (atomically where the platform supports it, otherwise with a replace). This is per-file replacement,
 * not an all-reports transaction.
 */
public final class ReportWriter {

    public static final String MARKDOWN = "dependency-conflicts.md";
    public static final String CSV = "dependency-occurrences.csv";
    public static final String XML = "dependency-management-candidates.xml";

    /** Run metadata that the reports state alongside the findings. */
    public record ReportContext(String source, boolean complete, String scopes,
                                List<DependencyTreeReader.Diagnostic> diagnostics) {

        public ReportContext {
            diagnostics = List.copyOf(diagnostics);
        }

        public static ReportContext of(String source, DependencyAnalyzer.Result result,
                                       DependencyTreeReader.ParseOptions options) {
            String scopes = options.includedScopes().isEmpty() ? "all"
                    : String.join(",", options.includedScopes().stream().sorted().toList());
            return new ReportContext(source, result.complete(), scopes, result.parse().diagnostics());
        }
    }

    /** Report output could not be created or written. */
    public static class ReportException extends IOException {
        public ReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public List<Path> write(Path directory, AnalysisResult analysis, ReportContext context,
                            boolean dependencyManagement) throws ReportException {
        try {
            Files.createDirectories(directory);
        } catch (IOException | RuntimeException e) {
            throw new ReportException("Cannot create report directory " + directory + ": " + e, e);
        }
        if (!dependencyManagement) removeStaleCandidateXml(directory.resolve(XML));
        List<Path> written = new ArrayList<>();
        written.add(writeFile(directory.resolve(MARKDOWN), MarkdownReport.render(analysis, context)));
        written.add(writeFile(directory.resolve(CSV), CsvReport.render(analysis)));
        if (dependencyManagement) {
            written.add(writeFile(directory.resolve(XML), DependencyManagementXml.render(analysis)));
        }
        return written;
    }

    /**
     * Deletes a candidate XML left behind by an earlier run that enabled it, so a reused report directory never
     * pairs this run's Markdown/CSV with an outdated XML. Only this tool's own fixed file name is touched; if it
     * cannot be removed the run fails instead of leaving a misleading file.
     */
    private static void removeStaleCandidateXml(Path xml) throws ReportException {
        try {
            Files.deleteIfExists(xml);
        } catch (IOException | RuntimeException e) {
            throw new ReportException("Cannot remove stale " + xml + " (report.dependencyManagement is false): " + e, e);
        }
    }

    static Path writeFile(Path target, String content) throws ReportException {
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException | RuntimeException e) {
            throw new ReportException("Cannot write report " + target + ": " + e, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup; the original failure (if any) is already propagated
                }
            }
        }
    }
}
