package com.rafaelgpq.depguardian.analysis;

import com.rafaelgpq.depguardian.model.DependencyModel.AnalysisResult;
import com.rafaelgpq.depguardian.model.DependencyModel.ArtifactIdentity;
import com.rafaelgpq.depguardian.model.DependencyModel.ChildFinding;
import com.rafaelgpq.depguardian.model.DependencyModel.Coordinate;
import com.rafaelgpq.depguardian.model.DependencyModel.ModuleAnalysis;
import com.rafaelgpq.depguardian.model.DependencyModel.ModuleTree;
import com.rafaelgpq.depguardian.model.DependencyModel.Occurrence;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.InvalidInputException;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.ParseOptions;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.ParseResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Module-local analysis of parsed trees. Pure: no file or console access.
 *
 * <p>A child identity (group, artifact, type, classifier) is a primary conflict in a module when two of its
 * occurrences have different complete immediate-parent coordinates (including the parent's version) and different
 * observed version strings. Two paths to the same parent coordinates count as one parent.
 */
public final class DependencyAnalyzer {

    /** The parse plus the analysis; {@code complete} is false when non-strict parsing hit errors. */
    public record Result(ParseResult parse, AnalysisResult analysis, boolean complete) {}

    /** One distinct immediate-parent coordinate together with the child version it requested. */
    private record Origin(Coordinate parent, String version) {}

    public AnalysisResult analyze(List<ModuleTree> trees) {
        Objects.requireNonNull(trees, "trees");
        List<ModuleAnalysis> modules = new ArrayList<>();
        for (ModuleTree tree : trees.stream().sorted(Comparator.comparing(ModuleTree::root)).toList()) {
            modules.add(analyzeModule(tree));
        }
        return new AnalysisResult(modules);
    }

    private ModuleAnalysis analyzeModule(ModuleTree tree) {
        Map<ArtifactIdentity, List<Occurrence>> byIdentity = new TreeMap<>();
        for (Occurrence occurrence : tree.occurrences()) {
            byIdentity.computeIfAbsent(occurrence.identity(), k -> new ArrayList<>()).add(occurrence);
        }
        List<ChildFinding> findings = new ArrayList<>();
        for (Map.Entry<ArtifactIdentity, List<Occurrence>> entry : byIdentity.entrySet()) {
            ChildFinding finding = classify(entry.getKey(), entry.getValue());
            if (finding != null) findings.add(finding);
        }
        return new ModuleAnalysis(tree.root(), tree.occurrences().size(), findings);
    }

    private static ChildFinding classify(ArtifactIdentity identity, List<Occurrence> unsorted) {
        List<Occurrence> occurrences = unsorted.stream().sorted(ChildFinding.occurrenceOrder()).toList();

        Map<Origin, List<Occurrence>> byOrigin = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            byOrigin.computeIfAbsent(new Origin(occurrence.parent(), occurrence.version()),
                    k -> new ArrayList<>()).add(occurrence);
        }
        List<Origin> origins = new ArrayList<>(byOrigin.keySet());
        List<Origin> conflictingOrigins = new ArrayList<>();
        for (int i = 0; i < origins.size(); i++) {
            for (int j = i + 1; j < origins.size(); j++) {
                Origin a = origins.get(i);
                Origin b = origins.get(j);
                if (!a.parent().equals(b.parent()) && !a.version().equals(b.version())) {
                    if (!conflictingOrigins.contains(a)) conflictingOrigins.add(a);
                    if (!conflictingOrigins.contains(b)) conflictingOrigins.add(b);
                }
            }
        }
        if (!conflictingOrigins.isEmpty()) {
            List<Occurrence> conflicting = occurrences.stream()
                    .filter(o -> conflictingOrigins.contains(new Origin(o.parent(), o.version()))).toList();
            return new ChildFinding(ChildFinding.Kind.CONFLICT, identity, occurrences, conflicting);
        }
        long parents = occurrences.stream().map(Occurrence::parent).distinct().count();
        long versions = occurrences.stream().map(Occurrence::version).distinct().count();
        if (parents >= 2) {
            return new ChildFinding(ChildFinding.Kind.SHARED_SAME_VERSION, identity, occurrences, List.of());
        }
        if (versions >= 2) {
            return new ChildFinding(ChildFinding.Kind.SAME_PARENT_VARIATION, identity, occurrences, List.of());
        }
        return null;
    }

    /**
     * Validates a parse and analyzes it. Throws when no module was read, or on parse errors in strict mode.
     *
     * @param input name of the input, used in failure messages
     */
    public Result analyze(Path input, ParseResult parse, ParseOptions options) {
        if (parse.modules().isEmpty()) {
            throw new InvalidInputException("No dependency tree could be read from " + input, parse);
        }
        boolean complete = parse.isComplete();
        if (!complete && options.strict()) {
            throw new InvalidInputException(parse.errors().size() + " parse error(s) in " + input
                    + " (strict mode)", parse);
        }
        return new Result(parse, analyze(parse.modules()), complete);
    }
}
