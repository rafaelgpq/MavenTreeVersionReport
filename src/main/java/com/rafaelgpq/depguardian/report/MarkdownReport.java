package com.rafaelgpq.depguardian.report;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.report.ReportWriter.ReportContext;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.util.List;

/** Human-readable report; full paths are always written in full. */
public final class MarkdownReport {

    private MarkdownReport() {}

    public static String render(AnalysisResult analysis, ReportContext context) {
        StringBuilder out = new StringBuilder();
        out.append("# Dependency version differences\n\n");
        out.append("- Input: ").append(code(context.source())).append('\n');
        out.append("- Included scopes: ").append(code(context.scopes())).append('\n');
        out.append("- Input status: ").append(context.complete() ? "complete" : "**INCOMPLETE** (parse errors)")
                .append('\n');
        out.append("- Modules analyzed: ").append(analysis.modules().size()).append('\n');
        out.append("- Conflicts (different parents and different versions): ").append(analysis.conflictCount())
                .append('\n');
        out.append("- Shared children, same version: ").append(analysis.sharedChildCount()).append('\n');
        out.append("- Same-parent version variations (not primary conflicts): ")
                .append(analysis.sameParentVariationCount()).append("\n\n");
        out.append(summary(analysis, context)).append("\n\n");
        out.append("> Observed requested-version differences do not prove that both versions are on a runtime "
                + "classpath or that a linkage failure occurs. Results describe only the nodes captured in the "
                + "input, its active profiles, scopes and Maven configuration. The highest-seen version is "
                + "informational, not Maven's selection and not a verified compatible fix.\n\n");

        if (!context.diagnostics().isEmpty()) {
            out.append("## Diagnostics\n\n| Severity | Code | Source | Line | Message |\n|---|---|---|---|---|\n");
            for (Diagnostic d : context.diagnostics()) {
                out.append("| ").append(d.severity()).append(" | ").append(cell(d.code())).append(" | ")
                        .append(cell(d.source())).append(" | ").append(d.line()).append(" | ")
                        .append(cell(d.message())).append(" |\n");
            }
            out.append('\n');
        }
        for (ModuleAnalysis module : analysis.modules()) {
            out.append("## Module ").append(code(module.root().toString())).append("\n\n");
            if (module.findings().isEmpty()) {
                out.append("No child shared by different parents was found in this module (")
                        .append(module.occurrenceCount()).append(" occurrence(s) analyzed).\n\n");
                continue;
            }
            section(out, "Conflicts", module.conflicts());
            section(out, "Shared children with the same version", module.sharedChildren());
            section(out, "Same-parent version variations", module.sameParentVariations());
        }
        return out.toString();
    }

    private static String summary(AnalysisResult analysis, ReportContext context) {
        String scope = " within the successfully analyzed input and selected scopes";
        if (!context.complete()) {
            return "**Analysis INCOMPLETE:** parse errors were found; results below may be missing dependencies.";
        }
        return switch (analysis.outcome()) {
            case CONFLICTS_FOUND -> "**Result:** " + analysis.conflictCount()
                    + " module-local conflict(s) found" + scope + ".";
            case SHARED_CHILDREN_ONLY -> "**Result:** shared children were found, but all observed versions are "
                    + "identical; no different-version conflicts" + scope + ".";
            case NO_QUALIFYING_SHARED_CHILDREN -> "**Result:** no child is shared by different parents"
                    + scope + ".";
        };
    }

    private static void section(StringBuilder out, String title, List<ChildFinding> findings) {
        if (findings.isEmpty()) return;
        out.append("### ").append(title).append("\n\n");
        for (ChildFinding finding : findings) {
            out.append("#### ").append(code(finding.identity().toString())).append("\n\n");
            out.append("- Observed versions: ").append(code(String.join(", ", finding.versions()))).append('\n');
            List<String> selected = finding.selectedVersions();
            out.append("- Selected by Maven (per input): ")
                    .append(selected.isEmpty() ? "not shown" : code(String.join(", ", selected))).append('\n');
            if (finding.candidateVersion() != null) {
                out.append("- Highest seen (informational candidate): ").append(code(finding.candidateVersion()))
                        .append('\n');
            }
            out.append("\n| Version | Scope | Status | Immediate parent | Full path | Line |\n|---|---|---|---|---|---|\n");
            for (Occurrence o : finding.occurrences()) {
                String status = o.status().name() + (o.selectedVersion() == null ? ""
                        : " (selected " + o.selectedVersion() + ")")
                        + (o.managedFrom() == null ? "" : " (managed from " + o.managedFrom() + ")");
                out.append("| ").append(cell(o.version())).append(finding.conflicting().contains(o) ? " \u26A0" : "")
                        .append(" | ").append(cell(o.scope() == null ? "" : o.scope()))
                        .append(" | ").append(cell(status))
                        .append(" | ").append(cell(o.parent().toString()))
                        .append(" | ").append(cell(o.fullPathText()))
                        .append(" | ").append(o.line()).append(" |\n");
            }
            out.append('\n');
        }
    }

    static String cell(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }

    static String code(String value) {
        String flat = value.replace("\r", " ").replace("\n", " ");
        int ticks = 1;
        while (flat.contains("`".repeat(ticks))) ticks++;
        String fence = "`".repeat(ticks);
        return fence + " " + flat + " " + fence;
    }
}
