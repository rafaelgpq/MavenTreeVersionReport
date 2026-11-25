package com.rafaelgpq.depguardian.support;

import com.rafaelgpq.depguardian.analysis.DependencyAnalyzer;
import com.rafaelgpq.depguardian.parser.DependencyTreeParser;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;

/** Test helper: parses tree text through the real parser. */
public final class TestTrees {

    private TestTrees() {}

    public static ParseResult parse(String text) {
        return parse(text, ParseOptions.defaults());
    }

    public static ParseResult parse(String text, ParseOptions options) {
        try {
            return DependencyTreeParser.parse(new StringReader(text), "test-input", options);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ParseOptions scopes(String... scopes) {
        return new ParseOptions(true, Set.of(scopes));
    }

    public static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    public static AnalysisResult analyze(String text) {
        ParseResult result = parse(text);
        if (!result.isComplete()) throw new AssertionError("unexpected diagnostics: " + result.diagnostics());
        return new DependencyAnalyzer().analyze(result.modules());
    }
}
