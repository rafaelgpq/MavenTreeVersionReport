package com.rafaelgpq.depguardian.report;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.util.ArrayList;
import java.util.List;

/** Occurrence-level CSV (RFC 4180 quoting, CRLF row separators). One row per occurrence; no joins are implied. */
public final class CsvReport {

    static final List<String> HEADER = List.of("Category", "Module", "GroupId", "ArtifactId", "Type", "Classifier",
            "ObservedVersion", "Scope", "Status", "SelectedVersion", "ManagedFromVersion", "ParentCoordinate",
            "FullPath", "InvolvedInConflict", "CandidateVersion", "SourceLine");

    private CsvReport() {}

    public static String render(AnalysisResult analysis) {
        StringBuilder out = new StringBuilder();
        row(out, HEADER);
        for (ModuleAnalysis module : analysis.modules()) {
            for (ChildFinding finding : module.findings()) {
                for (Occurrence o : finding.occurrences()) {
                    List<String> cells = new ArrayList<>();
                    cells.add(finding.kind().name());
                    cells.add(o.module().toString());
                    cells.add(o.identity().groupId());
                    cells.add(o.identity().artifactId());
                    cells.add(o.identity().type());
                    cells.add(o.identity().classifier());
                    cells.add(o.version());
                    cells.add(o.scope() == null ? "" : o.scope());
                    cells.add(o.status().name());
                    cells.add(o.selectedVersion() == null ? "" : o.selectedVersion());
                    cells.add(o.managedFrom() == null ? "" : o.managedFrom());
                    cells.add(o.parent().toString());
                    cells.add(o.fullPathText());
                    cells.add(Boolean.toString(finding.conflicting().contains(o)));
                    cells.add(finding.candidateVersion() == null ? "" : finding.candidateVersion());
                    cells.add(Integer.toString(o.line()));
                    row(out, cells);
                }
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(cells.get(i)));
        }
        out.append("\r\n");
    }

    static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
