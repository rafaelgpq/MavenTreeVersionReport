package com.rafaelgpq.depguardian.cli;

import com.rafaelgpq.depguardian.config.ApplicationConfig;
import com.rafaelgpq.depguardian.input.DependencyTreeReader;
import com.rafaelgpq.depguardian.report.ReportWriter;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;

import com.rafaelgpq.depguardian.config.ApplicationConfig.ConfigurationException;
import com.rafaelgpq.depguardian.model.DependencyModel.AnalysisResult;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.Diagnostic;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.InvalidInputException;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.ParseOptions;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.ParseResult;
import com.rafaelgpq.depguardian.report.ReportWriter.ReportContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Runs one analysis and maps the outcome to an exit status. Never calls {@code System.exit}. */
public final class ConflictFinderCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILURE = 1;
    public static final int EXIT_INCOMPLETE = 2;

    private final DependencyTreeReader reader;
    private final DependencyAnalyzer analyzer;
    private final ReportWriter writer;
    private final PrintStream out;
    private final PrintStream err;

    public ConflictFinderCommand(DependencyTreeReader reader, DependencyAnalyzer analyzer, ReportWriter writer,
                                 PrintStream out, PrintStream err) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public int run(Path configFile, Properties overrides) {
        ApplicationConfig config;
        try {
            config = ApplicationConfig.load(configFile, overrides);
        } catch (NoSuchFileException e) {
            err.println("ERROR: configuration file not found: " + configFile);
            return EXIT_FAILURE;
        } catch (IOException e) {
            err.println("ERROR: cannot read configuration " + configFile + ": " + e);
            return EXIT_FAILURE;
        } catch (ConfigurationException e) {
            err.println("ERROR: invalid configuration: " + e.getMessage());
            return EXIT_FAILURE;
        }

        ParseOptions options = config.parseOptions();
        DependencyAnalyzer.Result result;
        try {
            ParseResult parse = reader.read(config.depsFile(), options);
            result = analyzer.analyze(config.depsFile(), parse, options);
        } catch (NoSuchFileException e) {
            err.println("ERROR: dependency tree input not found: " + config.depsFile()
                    + " (see deps.file; generate it as described in the README)");
            return EXIT_FAILURE;
        } catch (IOException e) {
            err.println("ERROR: cannot read input " + config.depsFile() + ": " + e);
            return EXIT_FAILURE;
        } catch (InvalidInputException e) {
            err.println("ERROR: invalid input, analysis not performed: " + e.getMessage());
            printDiagnostics(e.diagnostics());
            return EXIT_FAILURE;
        }

        ReportContext context = ReportContext.of(config.depsFile().toString(), result, options);
        List<Path> files;
        try {
            files = writer.write(config.reportDir(), result.analysis(), context, config.dependencyManagement());
        } catch (IOException e) {
            err.println("ERROR: report generation failed: " + e.getMessage());
            return EXIT_FAILURE;
        }

        printSummary(result, options);
        printDiagnostics(result.parse().diagnostics());
        files.forEach(f -> out.println("Report written: " + f));
        if (!result.complete()) {
            err.println("INCOMPLETE: non-strict parsing continued after errors; the results may be missing"
                    + " dependencies.");
            return EXIT_INCOMPLETE;
        }
        return EXIT_OK;
    }

    private void printSummary(DependencyAnalyzer.Result result, ParseOptions options) {
        AnalysisResult analysis = result.analysis();
        String scopes = options.includedScopes().isEmpty() ? "all" : String.join(",", options.includedScopes());
        out.println("Analyzed " + analysis.modules().size() + " module(s), scopes: " + scopes);
        // An incomplete scan must never read like a trustworthy "RESULT: nothing found".
        String label = result.complete() ? "RESULT: " : "PARTIAL RESULT (input incomplete, not trustworthy): ";
        switch (analysis.outcome()) {
            case CONFLICTS_FOUND -> out.println(label + analysis.conflictCount()
                    + " module-local conflict(s): same child, different parents, different versions.");
            case SHARED_CHILDREN_ONLY -> out.println(label + analysis.sharedChildCount()
                    + " shared child(ren) with identical versions; no different-version conflicts found within the"
                    + " analyzed input and selected scopes.");
            case NO_QUALIFYING_SHARED_CHILDREN -> out.println(label + "no child shared by different parents was"
                    + " found within the analyzed input and selected scopes.");
        }
        if (analysis.sameParentVariationCount() > 0) {
            out.println("Note: " + analysis.sameParentVariationCount()
                    + " same-parent version variation(s) recorded (not primary conflicts).");
        }
    }

    private void printDiagnostics(List<Diagnostic> diagnostics) {
        for (Diagnostic d : diagnostics) {
            err.println(d.severity() + " " + d.code() + " " + d.source() + ":" + d.line() + " " + d.message());
        }
    }
}
