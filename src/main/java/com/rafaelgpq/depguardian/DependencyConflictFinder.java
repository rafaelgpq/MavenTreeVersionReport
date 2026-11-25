package com.rafaelgpq.depguardian;

import com.rafaelgpq.depguardian.parser.DependencyTreeParser;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.cli.ConflictFinderCommand;
import com.rafaelgpq.depguardian.input.DependencyTreeReader;
import com.rafaelgpq.depguardian.report.ReportWriter;

import java.nio.file.Path;
import java.util.Properties;

/** Thin entry point: wires the real collaborators and exits with the command's status. */
public final class DependencyConflictFinder {

    private DependencyConflictFinder() {}

    public static void main(String[] args) {
        Path config = Path.of(System.getProperty("app.config", "application.properties"));
        Properties overrides = new Properties();
        String deps = System.getProperty("deps.file");
        if (deps != null) overrides.setProperty("deps.file", deps);
        for (String key : new String[] {"report.dir", "scopes.include", "parser.strict",
                "report.dependencyManagement"}) {
            String value = System.getProperty(key);
            if (value != null) overrides.setProperty(key, value);
        }
        ConflictFinderCommand command = new ConflictFinderCommand(
                DependencyTreeParser::parse, new DependencyAnalyzer(), new ReportWriter(),
                System.out, System.err);
        int status = command.run(config, overrides);
        if (status != 0) System.exit(status);
    }
}
